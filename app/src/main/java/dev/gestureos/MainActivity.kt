package dev.gestureos

import android.Manifest
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.camera.view.TransformExperimental
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import dev.gestureos.accessibility.SystemCursorStatus
import dev.gestureos.accessibility.SystemCursorStore
import dev.gestureos.interaction.ControlRequirements
import dev.gestureos.interaction.InteractionMode
import dev.gestureos.interaction.InteractionModes
import dev.gestureos.camera.TrackingFrame
import dev.gestureos.databinding.ActivityMainBinding
import dev.gestureos.recognition.Gesture
import dev.gestureos.recognition.RecognitionConfig
import dev.gestureos.recognition.RecognitionSettings
import dev.gestureos.recognition.RecognitionState
import dev.gestureos.service.StopReason
import dev.gestureos.service.TrackingPhase
import dev.gestureos.service.TrackingService
import dev.gestureos.service.TrackingStatus
import dev.gestureos.service.TrackingStore
import dev.gestureos.ui.CursorUiController
import dev.gestureos.ui.MotionTuningDialog
import dev.gestureos.ui.PreviewProjection
import dev.gestureos.volume.AndroidMediaVolumeDevice
import dev.gestureos.volume.MediaVolumeController
import dev.gestureos.volume.VolumeControlConfig
import dev.gestureos.volume.VolumeFeedback
import dev.gestureos.volume.VolumeOutcome
import dev.gestureos.volume.VolumeReading
import dev.gestureos.volume.VolumeRestriction

@androidx.annotation.OptIn(TransformExperimental::class)
class MainActivity : ComponentActivity() {
    private lateinit var binding: ActivityMainBinding
    private var service: TrackingService.LocalBinder? = null
    private var bound = false
    private var startRequested = false
    private var userStartPending = false
    private var updatingBackground = false
    private var updatingModes = false
    private val previewClient = Any()
    private var frameOrientation = -1L
    private var frameSession = -1L
    private val tracking get() = TrackingStore.status.value ?: TrackingStatus()
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = binder as TrackingService.LocalBinder
            showSystemCursorStatus(SystemCursorStore.current)
            if (resumed) attachPreview()
        }
        override fun onServiceDisconnected(name: ComponentName) = disconnected()
        override fun onBindingDied(name: ComponentName) = disconnected()
        override fun onNullBinding(name: ComponentName) = disconnected()
    }
    private var resumed = false
    private var wantsCamera = false
    private var config = RecognitionConfig()
    private var volumeConfig = VolumeControlConfig()
    private lateinit var volumeController: MediaVolumeController
    private lateinit var cursorUi: CursorUiController
    private val volumeDevice by lazy { AndroidMediaVolumeDevice(this) }
    private var lastFrameAt = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val preferences by lazy { getSharedPreferences("recognition", MODE_PRIVATE) }
    private val volumeRefresh = object : Runnable {
        override fun run() {
            if (!resumed) return
            showVolume(volumeController.read())
            handler.postDelayed(this, 1000)
        }
    }
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && wantsCamera) requestCameraStart()
        else {
            userStartPending = false
            binding.cameraStatus.setText(R.string.permission_denied)
        }
    }
    private val notificationRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        startCamera()
    }
    private val watchdog = object : Runnable {
        override fun run() {
            if (!tracking.active || !resumed) return
            val now = SystemClock.uptimeMillis()
            cursorUi.expire(now)
            if (now - lastFrameAt > 350) volumeController.cancelHold()
            if (now - lastFrameAt > 2000) {
                clearTracking()
                binding.cameraStatus.setText(R.string.tracking_stalled)
            }
            handler.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        config = RecognitionSettings.load(this)
        volumeConfig = loadVolumeConfig()
        volumeController = MediaVolumeController(volumeDevice, volumeConfig)
        cursorUi = CursorUiController(this, binding,
            onModeRequested = { enabled ->
                if (enabled) selectMode(InteractionMode.IN_APP_CURSOR)
                else InteractionModes.clearIf(InteractionMode.IN_APP_CURSOR)
            },
            onTuning = { InteractionModes.clear() },
        )
        volumeControlStream = AudioManager.STREAM_MUSIC
        wantsCamera = savedInstanceState?.getBoolean("wantsCamera") ?: false
        binding.preview.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        binding.preview.scaleType = PreviewView.ScaleType.FIT_CENTER
        binding.preview.previewStreamState.observe(this) { state ->
            Log.i("GestureOSTracking", "Preview=$state visible=$resumed session=${tracking.generation}")
        }
        val padding = (16 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, padding + bars.bottom)
            insets
        }
        binding.toggleCamera.setOnClickListener {
            if (tracking.active || startRequested) {
                wantsCamera = false
                stopCamera()
            } else {
                wantsCamera = true
                userStartPending = true
                if (hasCameraPermission()) requestCameraStart() else permissionRequest.launch(Manifest.permission.CAMERA)
            }
        }
        binding.backgroundTracking.setOnCheckedChangeListener { _, enabled ->
            if (!updatingBackground) service?.setBackground(enabled)
        }
        TrackingStore.status.observe(this, ::showTrackingStatus)
        TrackingStore.frames.observe(this) { frame ->
            if (frame != null) showFrame(frame)
        }
        binding.appSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
        }
        binding.tune.setOnClickListener { showTuning() }
        binding.volumeEnabled.isChecked = false
        binding.volumeEnabled.isEnabled = false
        binding.volumeEnabled.setOnCheckedChangeListener { _, enabled ->
            if (!updatingModes) {
                if (enabled) selectMode(InteractionMode.VOLUME)
                else InteractionModes.clearIf(InteractionMode.VOLUME)
            }
        }
        binding.systemCursorEnabled.setOnCheckedChangeListener { _, enabled ->
            if (!updatingModes) {
                if (enabled) selectMode(InteractionMode.SYSTEM_CURSOR)
                else InteractionModes.clearIf(InteractionMode.SYSTEM_CURSOR)
            }
        }
        binding.accessibilitySettings.setOnClickListener {
            InteractionModes.clear()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.tuneMotion.setOnClickListener { MotionTuningDialog.show(this) }
        InteractionModes.state.observe(this, ::renderMode)
        SystemCursorStore.status.observe(this, ::showSystemCursorStatus)
    }

    private fun selectMode(mode: InteractionMode) {
        val requirements = ControlRequirements(
            tracking.phase == TrackingPhase.RUNNING && resumed,
            hasCameraPermission(),
            getSystemService(PowerManager::class.java).isInteractive && !getSystemService(KeyguardManager::class.java).isKeyguardLocked,
            SystemCursorStore.current.connected,
        )
        if (mode == InteractionMode.SYSTEM_CURSOR && requirements.permits(mode)) {
            val cameraService = service
            if (cameraService == null) {
                renderMode(InteractionModes.current)
                return
            }
            cameraService.setBackground(true)
        }
        if (!InteractionModes.select(mode, requirements)) {
            renderMode(InteractionModes.current)
            binding.systemCursorStatus.setText(R.string.system_enable_requirements)
        }
    }

    private fun renderMode(mode: InteractionMode) {
        updatingModes = true
        binding.volumeEnabled.isChecked = mode == InteractionMode.VOLUME
        binding.systemCursorEnabled.isChecked = mode == InteractionMode.SYSTEM_CURSOR
        updatingModes = false
        cursorUi.setEnabled(mode == InteractionMode.IN_APP_CURSOR)
        volumeController.setEnabled(mode == InteractionMode.VOLUME, SystemClock.uptimeMillis())
        binding.volumeAction.setText(if (mode == InteractionMode.VOLUME) R.string.volume_ready else R.string.volume_disabled)
    }

    private fun showSystemCursorStatus(status: SystemCursorStatus) {
        binding.systemCursorEnabled.isEnabled = InteractionModes.current == InteractionMode.SYSTEM_CURSOR ||
            (tracking.phase == TrackingPhase.RUNNING && status.connected && service != null)
        val connection = getString(if (status.connected) R.string.system_connected else R.string.system_disconnected)
        val point = status.position?.let { getString(R.string.system_position, it.x, it.y) } ?: getString(R.string.cursor_hidden)
        binding.systemCursorStatus.text = getString(R.string.system_status, connection,
            getString(if (status.overlayVisible) R.string.system_overlay_visible else R.string.system_overlay_hidden),
            point, status.state.name, (status.progress * 100).toInt(), status.outcome.name,
            status.latencyMs?.toString() ?: "—", status.message)
        binding.systemMotionStatus.text = getString(R.string.system_motion_status,
            status.interaction.ifEmpty { status.state.name }, status.action, status.actionFeedback)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        TrackingStore.setUiVisible(true)
        handler.post(volumeRefresh)
        showSystemCursorStatus(SystemCursorStore.current)
        showTrackingStatus(tracking)
        if (tracking.active) {
            bindTracking()
            attachPreview()
        } else if (userStartPending && hasCameraPermission()) {
            wantsCamera = true
            requestCameraStart()
        } else if (wantsCamera && tracking.stopReason == StopReason.HIDDEN) startCamera()
        else if (!hasCameraPermission()) binding.cameraStatus.setText(R.string.permission_needed)
    }

    override fun onPause() {
        resumed = false
        TrackingStore.setUiVisible(false)
        handler.removeCallbacks(volumeRefresh)
        InteractionModes.leaveActivity()
        suspendActions()
        service?.detach(previewClient)
        if (tracking.active && !tracking.backgroundEnabled) service?.stop(StopReason.HIDDEN)
        super.onPause()
    }

    override fun onStop() {
        unbindTracking()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("wantsCamera", wantsCamera)
        super.onSaveInstanceState(outState)
    }

    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requestCameraStart() {
        if (!preferences.getBoolean("notificationAsked", false) &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            preferences.edit { putBoolean("notificationAsked", true) }
            notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else startCamera()
    }

    private fun startCamera() {
        if (!resumed || !wantsCamera || startRequested || tracking.active ||
            tracking.phase == TrackingPhase.STOPPING || !hasCameraPermission()) return
        clearTracking()
        userStartPending = false
        startRequested = true
        binding.toggleCamera.isEnabled = false
        binding.cameraStatus.setText(R.string.starting)
        try {
            ContextCompat.startForegroundService(this, Intent(this, TrackingService::class.java)
                .setAction(TrackingService.ACTION_START)
                .putExtra(TrackingService.EXTRA_BACKGROUND, binding.backgroundTracking.isChecked))
            bindTracking()
        } catch (error: RuntimeException) {
            startRequested = false
            wantsCamera = false
            unbindTracking()
            binding.toggleCamera.isEnabled = true
            binding.cameraStatus.text = getString(R.string.camera_error, error.message ?: getString(R.string.service_start_failed))
        }
    }

    private fun bindTracking() {
        if (bound || !resumed) return
        bound = bindService(Intent(this, TrackingService::class.java), connection, 0)
    }

    private fun unbindTracking() {
        service?.detach(previewClient)
        service = null
        if (bound) unbindService(connection)
        bound = false
    }

    private fun attachPreview() {
        binding.preview.doOnLayout {
            if (resumed && tracking.active) service?.attach(previewClient, binding.preview.surfaceProvider)
        }
    }

    private fun disconnected() {
        InteractionModes.clear()
        wantsCamera = false
        startRequested = false
        unbindTracking()
        suspendActions()
        binding.cameraStatus.setText(R.string.service_disconnected)
    }

    private fun showTrackingStatus(status: TrackingStatus) {
        if (!status.active) InteractionModes.clear()
        showSystemCursorStatus(SystemCursorStore.current)
        startRequested = false
        binding.toggleCamera.isEnabled = status.phase != TrackingPhase.STOPPING
        binding.toggleCamera.setText(if (status.active) R.string.stop else R.string.start)
        binding.backgroundTracking.isEnabled = status.phase != TrackingPhase.STOPPING
        if (status.active) {
            wantsCamera = true
            updatingBackground = true
            binding.backgroundTracking.isChecked = status.backgroundEnabled
            updatingBackground = false
        }
        binding.backgroundStatus.setText(if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
            R.string.background_help else R.string.background_notifications_denied)
        if (status.active && resumed) {
            bindTracking()
            attachPreview()
            binding.preview.keepScreenOn = true
            lastFrameAt = SystemClock.uptimeMillis()
            handler.removeCallbacks(watchdog)
            handler.postDelayed(watchdog, 500)
            binding.cameraStatus.setText(if (status.phase == TrackingPhase.RUNNING) R.string.camera_waiting else R.string.starting)
            if (status.phase == TrackingPhase.RUNNING) {
                binding.volumeEnabled.isEnabled = true
                cursorUi.cameraReady(config)
            }
        } else if (!status.active) {
            suspendActions()
            unbindTracking()
            binding.cameraStatus.text = status.error?.let { getString(R.string.camera_error, it) }
                ?: getString(if (status.phase == TrackingPhase.STOPPING) R.string.stopping else R.string.camera_off)
            if (status.stopReason != StopReason.HIDDEN && status.stopReason != StopReason.RECONFIGURE) wantsCamera = false
            if (status.phase == TrackingPhase.STOPPED && status.stopReason == StopReason.HIDDEN && resumed && wantsCamera) {
                handler.post { startCamera() }
            }
        }
    }

    private fun stopCamera() {
        InteractionModes.clear()
        userStartPending = false
        suspendActions()
        if (service != null) service?.stop()
        else if (tracking.active || startRequested) {
            startService(Intent(this, TrackingService::class.java).setAction(TrackingService.ACTION_STOP))
        }
    }

    private fun suspendActions() {
        cursorUi.cameraStopped()
        disarmVolume()
        binding.volumeEnabled.isEnabled = false
        handler.removeCallbacks(watchdog)
        binding.preview.keepScreenOn = false
        clearTracking()
    }

    private fun clearTracking() {
        cursorUi.resetTracking()
        volumeController.cancelHold()
        binding.overlay.clear()
        binding.gesture.setText(R.string.gesture_no_hand)
        binding.recognitionState.setText(R.string.state_no_hand)
        binding.rawGesture.text = getString(R.string.raw_gesture, gestureName(Gesture.NO_HAND))
        binding.diagnostics.setText(R.string.diagnostics_idle)
        binding.holdProgress.progress = 0
    }

    private fun showFrame(frame: TrackingFrame) {
        if (!resumed || tracking.phase != TrackingPhase.RUNNING || frame.sessionGeneration != tracking.generation) return
        if (SystemClock.uptimeMillis() - frame.observedAtMs > 350) {
            clearTracking()
            return
        }
        if (frameSession != frame.sessionGeneration || frameOrientation != frame.orientationGeneration) {
            cursorUi.disable()
            disarmVolume()
            clearTracking()
            frameSession = frame.sessionGeneration
            frameOrientation = frame.orientationGeneration
        }
        lastFrameAt = frame.observedAtMs
        binding.cameraStatus.setText(R.string.camera_live)
        val projection = PreviewProjection.from(frame, binding.preview)
        if (projection != null && frame.landmarks.size == 21 && frame.landmarks.all { it.isFinite() }) {
            binding.overlay.show(projection.landmarks(frame))
        } else binding.overlay.clear()
        cursorUi.onFrame(frame, projection)
        val recognition = frame.recognition
        binding.gesture.text = gestureName(recognition.gesture)
        binding.recognitionState.text = when (recognition.state) {
            RecognitionState.NO_HAND -> getString(R.string.state_no_hand)
            RecognitionState.UNRECOGNIZED -> getString(R.string.state_unknown)
            RecognitionState.HOLDING -> getString(R.string.state_holding, (recognition.holdProgress * 100).toInt())
            RecognitionState.CONFIRMED -> getString(R.string.state_confirmed)
            RecognitionState.COOLDOWN -> getString(R.string.state_cooldown)
        }
        binding.holdProgress.progress = (recognition.holdProgress * 100).toInt()
        binding.rawGesture.text = getString(R.string.raw_gesture, gestureName(frame.rawGesture))
        binding.diagnostics.text = getString(R.string.diagnostics, frame.processedFps, frame.inferenceMs,
            frame.pipelineMs, frame.width, frame.height, if (frame.landmarks.isEmpty()) 0 else 1,
            config.maxFps, frame.throttleSkips, config.holdMs, config.cooldownMs, config.smoothingAlpha)
        if (resumed && tracking.active) {
            volumeController.onFrame(recognition, frame.rawGesture, frame.observedAtMs, SystemClock.uptimeMillis())?.let(::showVolumeFeedback)
        }
    }

    private fun disarmVolume() {
        InteractionModes.clearIf(InteractionMode.VOLUME)
        volumeController.setEnabled(false, SystemClock.uptimeMillis())
        binding.volumeEnabled.isChecked = false
        binding.volumeAction.setText(R.string.volume_disabled)
    }

    private fun showVolume(reading: VolumeReading) {
        val level = reading.level
        val levelText = level?.let {
            getString(if (it.muted) R.string.volume_level_muted else R.string.volume_level, it.current, it.maximum)
        }
        binding.mediaVolume.text = listOfNotNull(levelText, reading.restriction?.let(::restrictionText))
            .joinToString("\n").ifEmpty { getString(R.string.volume_unavailable) }
        binding.volumeProgress.isIndeterminate = false
        binding.volumeProgress.min = level?.minimum ?: 0
        binding.volumeProgress.max = level?.maximum ?: 1
        binding.volumeProgress.progress = level?.current ?: 0
    }

    private fun restrictionText(restriction: VolumeRestriction?) = getString(when (restriction) {
        VolumeRestriction.FIXED -> R.string.volume_fixed
        VolumeRestriction.POLICY -> R.string.volume_policy
        VolumeRestriction.DENIED -> R.string.volume_denied
        else -> R.string.volume_unavailable
    })

    private fun showVolumeFeedback(feedback: VolumeFeedback) {
        showVolume(feedback.reading)
        val level = feedback.reading.level
        binding.volumeAction.text = when {
            feedback.outcome == VolumeOutcome.BLOCKED -> restrictionText(feedback.reading.restriction)
            feedback.outcome == VolumeOutcome.MAXIMUM || (feedback.outcome == VolumeOutcome.RAISED && level?.current == level?.maximum) -> getString(R.string.volume_maximum)
            feedback.outcome == VolumeOutcome.MINIMUM || (feedback.outcome == VolumeOutcome.LOWERED && level?.current == level?.minimum) -> getString(R.string.volume_minimum)
            feedback.outcome == VolumeOutcome.RAISED -> getString(if (feedback.repeated) R.string.volume_raised_repeat else R.string.volume_raised)
            feedback.outcome == VolumeOutcome.LOWERED -> getString(if (feedback.repeated) R.string.volume_lowered_repeat else R.string.volume_lowered)
            else -> getString(R.string.volume_unchanged)
        }
        Log.i("GestureOSVolume", "${feedback.outcome}; repeat=${feedback.repeated}; level=${level?.current}/${level?.maximum}; restriction=${feedback.reading.restriction}")
    }

    private fun gestureName(gesture: Gesture) = getString(when (gesture) {
        Gesture.NO_HAND -> R.string.gesture_no_hand
        Gesture.UNKNOWN -> R.string.gesture_unknown
        Gesture.OPEN_PALM -> R.string.gesture_open_palm
        Gesture.CLOSED_FIST -> R.string.gesture_closed_fist
        Gesture.THUMBS_UP -> R.string.gesture_thumbs_up
        Gesture.THUMBS_DOWN -> R.string.gesture_thumbs_down
    })

    private fun loadVolumeConfig() = runCatching {
        VolumeControlConfig(
            repeatEnabled = preferences.getBoolean("volumeRepeat", true),
            repeatDelayMs = preferences.getLong("volumeDelayMs", 1200),
            repeatIntervalMs = preferences.getLong("volumeIntervalMs", 700),
        )
    }.getOrDefault(VolumeControlConfig())

    private fun applyConfig(updated: RecognitionConfig, updatedVolume: VolumeControlConfig) {
        suspendActions()
        config = updated
        volumeConfig = updatedVolume
        volumeController = MediaVolumeController(volumeDevice, volumeConfig)
        preferences.edit {
            putLong("holdMs", config.holdMs)
            putLong("cooldownMs", config.cooldownMs)
            putFloat("alpha", config.smoothingAlpha)
            putInt("fps", config.maxFps)
            putFloat("angle", config.extendedAngle)
            putBoolean("volumeRepeat", volumeConfig.repeatEnabled)
            putLong("volumeDelayMs", volumeConfig.repeatDelayMs)
            putLong("volumeIntervalMs", volumeConfig.repeatIntervalMs)
        }
        if (tracking.active) {
            if (service != null) service?.stop(StopReason.RECONFIGURE)
            else startService(Intent(this, TrackingService::class.java).setAction(TrackingService.ACTION_RECONFIGURE))
        }
    }

    private fun showTuning() {
        InteractionModes.clear()
        cursorUi.disable()
        disarmVolume()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, padding / 2)
            addView(TextView(context).apply { setText(R.string.tuning_help) })
        }
        fun field(label: Int, value: String): EditText {
            val input = EditText(this).apply {
                id = android.view.View.generateViewId()
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setSingleLine()
                setText(value)
                contentDescription = getString(label)
            }
            content.addView(TextView(this).apply { setText(label); labelFor = input.id })
            content.addView(input)
            return input
        }
        val hold = field(R.string.hold_label, config.holdMs.toString())
        val cooldown = field(R.string.cooldown_label, config.cooldownMs.toString())
        val alpha = field(R.string.smoothing_label, config.smoothingAlpha.toString())
        val fps = field(R.string.fps_label, config.maxFps.toString())
        val angle = field(R.string.angle_label, config.extendedAngle.toString())
        content.addView(TextView(this).apply { setText(R.string.volume_tuning) })
        val repeatEnabled = CheckBox(this).apply {
            setText(R.string.volume_repeat_enabled)
            isChecked = volumeConfig.repeatEnabled
        }
        content.addView(repeatEnabled)
        val repeatDelay = field(R.string.volume_delay_label, volumeConfig.repeatDelayMs.toString())
        val repeatInterval = field(R.string.volume_interval_label, volumeConfig.repeatIntervalMs.toString())
        val dialog = AlertDialog.Builder(this).setTitle(R.string.tuning_title)
            .setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.reset_defaults) { _, _ -> applyConfig(RecognitionConfig(), VolumeControlConfig()) }
            .setPositiveButton(R.string.apply, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val updated = runCatching {
                    val recognition = config.copy(holdMs = hold.text.toString().toLong(), cooldownMs = cooldown.text.toString().toLong(),
                        smoothingAlpha = alpha.text.toString().replace(',', '.').toFloat(), maxFps = fps.text.toString().toInt(),
                        extendedAngle = angle.text.toString().replace(',', '.').toFloat())
                    val volume = volumeConfig.copy(repeatEnabled = repeatEnabled.isChecked,
                        repeatDelayMs = repeatDelay.text.toString().toLong(), repeatIntervalMs = repeatInterval.text.toString().toLong())
                    recognition to volume
                }.getOrNull()
                if (updated == null) hold.error = getString(R.string.invalid_settings)
                else { applyConfig(updated.first, updated.second); dialog.dismiss() }
            }
        }
        dialog.show()
    }

}
