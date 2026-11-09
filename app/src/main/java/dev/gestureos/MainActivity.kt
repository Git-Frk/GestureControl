package dev.gestureos

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import dev.gestureos.camera.CameraSession
import dev.gestureos.camera.TrackingFrame
import dev.gestureos.databinding.ActivityMainBinding
import dev.gestureos.recognition.Gesture
import dev.gestureos.recognition.RecognitionConfig
import dev.gestureos.recognition.RecognitionState
import dev.gestureos.ui.CursorUiController
import dev.gestureos.ui.PreviewProjection
import dev.gestureos.volume.AndroidMediaVolumeDevice
import dev.gestureos.volume.MediaVolumeController
import dev.gestureos.volume.VolumeControlConfig
import dev.gestureos.volume.VolumeFeedback
import dev.gestureos.volume.VolumeOutcome
import dev.gestureos.volume.VolumeReading
import dev.gestureos.volume.VolumeRestriction

@androidx.annotation.OptIn(TransformExperimental::class)
class MainActivity : ComponentActivity(), DisplayManager.DisplayListener {
    private lateinit var binding: ActivityMainBinding
    private var session: CameraSession? = null
    private var resumed = false
    private var wantsCamera = true
    private var config = RecognitionConfig()
    private var volumeConfig = VolumeControlConfig()
    private lateinit var volumeController: MediaVolumeController
    private lateinit var cursorUi: CursorUiController
    private val volumeDevice by lazy { AndroidMediaVolumeDevice(this) }
    private var lastFrameAt = 0L
    private var displayRotation = -1
    private val handler = Handler(Looper.getMainLooper())
    private val preferences by lazy { getSharedPreferences("recognition", MODE_PRIVATE) }
    private val displays by lazy { getSystemService(DisplayManager::class.java) }
    private val volumeRefresh = object : Runnable {
        override fun run() {
            if (!resumed) return
            showVolume(volumeController.read())
            handler.postDelayed(this, 1000)
        }
    }
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && wantsCamera) startCamera()
        else binding.cameraStatus.setText(R.string.permission_denied)
    }
    private val watchdog = object : Runnable {
        override fun run() {
            if (session == null || !resumed) return
            cursorUi.expire(SystemClock.uptimeMillis())
            if (SystemClock.uptimeMillis() - lastFrameAt > 2000) {
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
        config = loadConfig()
        volumeConfig = loadVolumeConfig()
        volumeController = MediaVolumeController(volumeDevice, volumeConfig)
        cursorUi = CursorUiController(this, binding, ::disarmVolume)
        volumeControlStream = AudioManager.STREAM_MUSIC
        wantsCamera = savedInstanceState?.getBoolean("wantsCamera") ?: true
        binding.preview.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        binding.preview.scaleType = PreviewView.ScaleType.FIT_CENTER
        val padding = (16 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, padding + bars.bottom)
            insets
        }
        binding.toggleCamera.setOnClickListener {
            if (session != null) {
                wantsCamera = false
                stopCamera()
            } else {
                wantsCamera = true
                if (hasCameraPermission()) startCamera() else permissionRequest.launch(Manifest.permission.CAMERA)
            }
        }
        binding.appSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
        }
        binding.tune.setOnClickListener { showTuning() }
        binding.volumeEnabled.isChecked = false
        binding.volumeEnabled.isEnabled = false
        binding.volumeEnabled.setOnCheckedChangeListener { _, enabled ->
            if (enabled) cursorUi.disable()
            volumeController.setEnabled(enabled, SystemClock.uptimeMillis())
            binding.volumeAction.setText(if (enabled) R.string.volume_ready else R.string.volume_disabled)
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        handler.post(volumeRefresh)
        displays.registerDisplayListener(this, handler)
        if (wantsCamera && hasCameraPermission()) startCamera()
        else if (!hasCameraPermission()) binding.cameraStatus.setText(R.string.permission_needed)
    }

    override fun onPause() {
        resumed = false
        handler.removeCallbacks(volumeRefresh)
        displays.unregisterDisplayListener(this)
        stopCamera()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("wantsCamera", wantsCamera)
        super.onSaveInstanceState(outState)
    }

    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        if (!resumed || !wantsCamera || session != null || !hasCameraPermission()) return
        binding.preview.doOnLayout {
            if (!resumed || !wantsCamera || session != null || !hasCameraPermission()) return@doOnLayout
            displayRotation = binding.preview.display.rotation
            clearTracking()
            binding.cameraStatus.setText(R.string.starting)
            binding.toggleCamera.setText(R.string.stop)
            binding.preview.keepScreenOn = true
            lastFrameAt = SystemClock.uptimeMillis()
            session = CameraSession(this, this, binding.preview, config,
                onFrame = ::showFrame,
                onReady = {
                    binding.cameraStatus.setText(R.string.camera_waiting)
                    binding.volumeEnabled.isEnabled = true
                    cursorUi.cameraReady(config)
                },
                onError = { message ->
                    stopCamera()
                    binding.cameraStatus.text = getString(R.string.camera_error, message)
                },
            ).also { it.start() }
            handler.postDelayed(watchdog, 500)
        }
    }

    private fun stopCamera() {
        cursorUi.cameraStopped()
        disarmVolume()
        binding.volumeEnabled.isEnabled = false
        handler.removeCallbacks(watchdog)
        session?.close()
        session = null
        binding.preview.keepScreenOn = false
        binding.toggleCamera.setText(R.string.start)
        binding.cameraStatus.setText(R.string.camera_off)
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
        lastFrameAt = SystemClock.uptimeMillis()
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
        if (resumed && session != null) {
            volumeController.onFrame(recognition, frame.rawGesture, frame.observedAtMs, SystemClock.uptimeMillis())?.let(::showVolumeFeedback)
        }
    }

    private fun disarmVolume() {
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

    private fun loadConfig() = runCatching {
        RecognitionConfig(
            holdMs = preferences.getLong("holdMs", 400),
            cooldownMs = preferences.getLong("cooldownMs", 600),
            smoothingAlpha = preferences.getFloat("alpha", 0.65f),
            maxFps = preferences.getInt("fps", 12),
            extendedAngle = preferences.getFloat("angle", 155f),
        )
    }.getOrDefault(RecognitionConfig())

    private fun loadVolumeConfig() = runCatching {
        VolumeControlConfig(
            repeatEnabled = preferences.getBoolean("volumeRepeat", true),
            repeatDelayMs = preferences.getLong("volumeDelayMs", 1200),
            repeatIntervalMs = preferences.getLong("volumeIntervalMs", 700),
        )
    }.getOrDefault(VolumeControlConfig())

    private fun applyConfig(updated: RecognitionConfig, updatedVolume: VolumeControlConfig) {
        stopCamera()
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
        if (wantsCamera) startCamera()
    }

    private fun showTuning() {
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

    override fun onDisplayAdded(displayId: Int) = Unit
    override fun onDisplayRemoved(displayId: Int) = Unit
    override fun onDisplayChanged(displayId: Int) {
        val display = binding.preview.display ?: return
        if (session != null && display.displayId == displayId && display.rotation != displayRotation) {
            stopCamera()
            startCamera()
        }
    }
}
