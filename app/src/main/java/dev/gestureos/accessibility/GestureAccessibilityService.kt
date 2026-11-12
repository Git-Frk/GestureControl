package dev.gestureos.accessibility

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.camera.view.TransformExperimental
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import dev.gestureos.R
import dev.gestureos.BuildConfig
import dev.gestureos.camera.TrackingFrame
import dev.gestureos.cursor.CursorConfig
import dev.gestureos.cursor.CursorPoint
import dev.gestureos.cursor.CursorPoseDetector
import dev.gestureos.cursor.CursorRect
import dev.gestureos.cursor.CursorSettings
import dev.gestureos.interaction.ControlRequirements
import dev.gestureos.interaction.InteractionMode
import dev.gestureos.interaction.InteractionModes
import dev.gestureos.interaction.SystemAction
import dev.gestureos.motion.MotionConfig
import dev.gestureos.motion.MotionPoseDetector
import dev.gestureos.motion.MotionSettings
import dev.gestureos.motion.MotionState
import dev.gestureos.motion.NavigationRejection
import dev.gestureos.recognition.RecognitionSettings
import dev.gestureos.recognition.isotropicLandmarks
import dev.gestureos.service.TrackingPhase
import dev.gestureos.service.TrackingStore

/** Consumes the existing service's results. Android alone binds and authorises this service. */
@androidx.annotation.OptIn(TransformExperimental::class)
class GestureAccessibilityService : AccessibilityService(), LifecycleOwner, DisplayManager.DisplayListener {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
    private val handler = Handler(Looper.getMainLooper())
    private val windows by lazy { getSystemService(WindowManager::class.java) }
    private val displays by lazy { getSystemService(DisplayManager::class.java) }
    private val power by lazy { getSystemService(PowerManager::class.java) }
    private val keyguard by lazy { getSystemService(KeyguardManager::class.java) }
    private var connected = false
    private var overlay: SystemCursorOverlay? = null
    private var overlayAdded = false
    private var area: DisplayArea? = null
    private var config = CursorConfig()
    private var mapper = DisplayCursorMapper(config)
    private var detector = CursorPoseDetector()
    private var engine: SystemInteractionEngine? = null
    private val dispatchGuard = DispatchGuard()
    private var motionConfig = MotionConfig()
    private var motionDetector = MotionPoseDetector()
    private var activatedAt = 0L
    private var initialized = false
    private var latest = SystemInteractionUpdate()
    private var lastReportedState = ""
    private var lastMetricAt = 0L
    private var drawLatency: Long? = null
    private var feedbackUntil = 0L
    private var lastPoseDiagnosticAt = 0L
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!unlocked()) disarm(getString(R.string.system_locked))
        }
    }
    private val watchdog = object : Runnable {
        override fun run() {
            if (InteractionModes.current != InteractionMode.SYSTEM_CURSOR) return
            if (!requirements().permits(InteractionMode.SYSTEM_CURSOR)) {
                disarm(getString(R.string.system_prerequisites_lost))
                return
            }
            val now = SystemClock.uptimeMillis()
            val active = engine ?: return
            if (dispatchGuard.timedOut(now)) {
                disarm(getString(R.string.system_dispatch_timeout))
                return
            }
            val expired = active.expire(now)
            if (initialized && expired != latest) render(expired, null)
            overlay?.invalidate()
            if (SystemCursorStore.current.overlayVisible != (overlay?.pointerVisible == true)) {
                publish(getString(R.string.system_active))
            }
            handler.postDelayed(this, 100)
        }
    }

    override fun onCreate() {
        super.onCreate()
        registry.currentState = Lifecycle.State.CREATED
        TrackingStore.status.observe(this) { status ->
            if (status.phase != TrackingPhase.RUNNING && InteractionModes.current == InteractionMode.SYSTEM_CURSOR) {
                disarm(getString(R.string.system_tracking_stopped))
            }
        }
        TrackingStore.frames.observe(this) { frame -> if (frame != null) onFrame(frame) }
        InteractionModes.state.observe(this) { mode ->
            if (mode == InteractionMode.SYSTEM_CURSOR) startMode()
            else stopMode(getString(R.string.system_mode_off))
        }
        displays.registerDisplayListener(this, handler)
        ContextCompat.registerReceiver(this, screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = serviceInfo.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES != 0
        // Reconnection grants availability only. Every activation still comes from the Activity switch.
        InteractionModes.clearIf(InteractionMode.SYSTEM_CURSOR)
        registry.currentState = Lifecycle.State.STARTED
        publish(getString(if (connected) R.string.system_connected else R.string.system_capability_missing))
        Log.i(TAG, "Accessibility connected=$connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() { disarm(getString(R.string.system_interrupted)) }

    private fun unlocked() = power.isInteractive && !keyguard.isKeyguardLocked

    private fun requirements() = ControlRequirements(
        tracking = TrackingStore.status.value?.phase == TrackingPhase.RUNNING,
        cameraPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        unlocked = unlocked(), accessibility = connected,
    )

    private fun startMode() {
        if (overlay != null) return
        if (!requirements().permits(InteractionMode.SYSTEM_CURSOR)) {
            disarm(getString(R.string.system_prerequisites_lost))
            return
        }
        activatedAt = SystemClock.uptimeMillis()
        initialized = false
        config = CursorSettings.load(this)
        mapper = DisplayCursorMapper(config)
        val recognition = RecognitionSettings.load(this)
        detector = CursorPoseDetector(recognition)
        motionDetector = MotionPoseDetector(recognition, config.pinchRelease)
        motionConfig = MotionSettings.load(this)
        engine = SystemInteractionEngine(config, motionConfig)
        latest = SystemInteractionUpdate()
        drawLatency = null
        feedbackUntil = 0L
        val view = SystemCursorOverlay(this).apply {
            onGeometryChanged = { updateArea() }
            onCursorDraw = { latency ->
                drawLatency = latency
                publish(getString(R.string.system_active))
            }
        }
        overlay = view
        try {
            val params = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.START
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                setFitInsetsTypes(0)
                title = "GestureOS virtual cursor"
            }
            windows.addView(view, params)
            overlayAdded = true
            handler.post(watchdog)
            publish(getString(R.string.system_waiting))
        } catch (error: RuntimeException) {
            Log.e(TAG, "Overlay could not attach", error)
            disarm(getString(R.string.system_overlay_failed))
        }
    }

    private fun updateArea() {
        val view = overlay ?: return
        if (!view.isAttachedToWindow || view.width <= 0 || view.height <= 0) return
        val metrics = windows.maximumWindowMetrics
        val bounds = metrics.bounds
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val origin = IntArray(2).also(view::getLocationOnScreen)
        val updated = runCatching {
            DisplayArea(Display.DEFAULT_DISPLAY, displays.getDisplay(Display.DEFAULT_DISPLAY).rotation,
                CursorRect(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat()),
                CursorRect(maxOf(bounds.left + insets.left, origin[0]).toFloat(),
                    maxOf(bounds.top + insets.top, origin[1]).toFloat(),
                    minOf(bounds.right - insets.right, origin[0] + view.width).toFloat(),
                    minOf(bounds.bottom - insets.bottom, origin[1] + view.height).toFloat()))
        }.getOrNull()
        if (updated == null || (area != null && area != updated)) {
            disarm(getString(R.string.system_geometry_changed))
            return
        }
        area = updated
        Log.d(TAG, "Display geometry=$updated")
    }

    private fun onFrame(frame: TrackingFrame) {
        logPoseDiagnostics(frame)
        if (InteractionModes.current != InteractionMode.SYSTEM_CURSOR) return
        if (!requirements().permits(InteractionMode.SYSTEM_CURSOR)) {
            disarm(getString(R.string.system_prerequisites_lost))
            return
        }
        val display = area ?: return
        val active = engine ?: return
        val now = SystemClock.uptimeMillis()
        if (frame.sessionGeneration != TrackingStore.status.value?.generation ||
            frame.displayRotation != displays.getDisplay(Display.DEFAULT_DISPLAY).rotation) {
            disarm(getString(R.string.system_geometry_changed))
            return
        }
        val alignment = FrameAlignment(frame.sessionGeneration, frame.orientationGeneration, frame.displayRotation)
        if (!initialized) {
            if (frame.observedAtMs < activatedAt || now - frame.observedAtMs > config.maxFrameAgeMs) return
            initialized = active.enable(alignment, display.rotation, activatedAt, requirements())
        }
        val valid = frame.landmarks.size == 21 && frame.landmarks.all { it.isFinite() && it.x in 0f..1f && it.y in 0f..1f }
        val hand = if (valid) isotropicLandmarks(frame.landmarks, frame.width, frame.height) else emptyList()
        val pose = detector.detect(hand)
        val motionPose = motionDetector.detect(hand)
        val palm = if (valid) {
            val bases = listOf(0, 5, 9, 13, 17).map { frame.landmarks[it] }
            mapper.mapMotion(CursorPoint(bases.sumOf { it.x.toDouble() }.toFloat() / bases.size,
                bases.sumOf { it.y.toDouble() }.toFloat() / bases.size), frame.displayRotation, display.rotation)
        } else null
        val mapped = if (valid) mapper.map(CursorPoint(frame.landmarks[8].x, frame.landmarks[8].y), frame.displayRotation, display.rotation) else null
        val update = active.update(alignment, pose, mapped, motionPose, palm,
            frame.observedAtMs, now, dispatchGuard.busy)
        if (!active.enabled) {
            disarm(getString(R.string.system_geometry_changed))
            return
        }
        render(update, frame.observedAtMs)
        update.request?.let { dispatch(active, it, display) }
    }

    private fun logPoseDiagnostics(frame: TrackingFrame) {
        val now = SystemClock.uptimeMillis()
        if (!BuildConfig.DEBUG || !TrackingStore.uiVisible || now - lastPoseDiagnosticAt < 1000) return
        lastPoseDiagnosticAt = now
        val cursorConfig = CursorSettings.load(this)
        val poseDetector = MotionPoseDetector(RecognitionSettings.load(this), cursorConfig.pinchRelease)
        val hand = isotropicLandmarks(frame.landmarks, frame.width, frame.height)
        val inImage = frame.landmarks.size == 21 && frame.landmarks.all { it.isFinite() && it.x in 0f..1f && it.y in 0f..1f }
        val inRegion = if (inImage) {
            val bases = listOf(0, 5, 9, 13, 17).map { frame.landmarks[it] }
            val centre = CursorPoint(bases.sumOf { it.x.toDouble() }.toFloat() / bases.size,
                bases.sumOf { it.y.toDouble() }.toFloat() / bases.size)
            DisplayCursorMapper(cursorConfig).mapMotion(centre, frame.displayRotation, frame.displayRotation) != null
        } else false
        Log.d(TAG, "Pose sample kind=${poseDetector.detect(hand)} inImage=$inImage inRegion=$inRegion ${poseDetector.describe(hand)}")
    }

    private fun dispatch(active: SystemInteractionEngine, request: SystemActionRequest, display: DisplayArea) {
        if (InteractionModes.current != InteractionMode.SYSTEM_CURSOR || !requirements().permits(InteractionMode.SYSTEM_CURSOR) ||
            displays.getDisplay(display.displayId)?.rotation != display.rotation || area != display) {
            disarm(getString(R.string.system_prerequisites_lost))
            return
        }
        val token = dispatchGuard.reserve(SystemClock.uptimeMillis())
        if (token == null) {
            disarm(getString(R.string.system_dispatch_busy))
            return
        }
        if (request.action.isNavigation) {
            val actionId = if (request.action == SystemAction.BACK) GLOBAL_ACTION_BACK else GLOBAL_ACTION_HOME
            val outcome = try {
                if (systemActions.none { it.id == actionId }) ActionOutcome.UNAVAILABLE
                else if (performGlobalAction(actionId)) ActionOutcome.COMPLETED else ActionOutcome.FAILED
            } catch (error: RuntimeException) {
                Log.w(TAG, "Navigation failed action=${request.action}", error)
                ActionOutcome.FAILED
            }
            finishDispatch(active, request, token, outcome)
            return
        }
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                finishDispatch(active, request, token, ActionOutcome.COMPLETED)
            }
            override fun onCancelled(gestureDescription: GestureDescription) {
                finishDispatch(active, request, token, ActionOutcome.CANCELLED)
            }
        }
        val accepted = try {
            val path = Path()
            val duration = if (request.action.isSwipe) {
                val swipe = SwipeGeometry.create(request.action, request.position, motionConfig.scrollDistance, display)
                path.moveTo(swipe.start.x, swipe.start.y)
                path.lineTo(swipe.end.x, swipe.end.y)
                motionConfig.swipeDurationMs
            } else {
                val point = checkNotNull(display.toDisplay(request.position))
                path.moveTo(point.x, point.y)
                1L
            }
            val gesture = GestureDescription.Builder().setDisplayId(display.displayId).addStroke(
                GestureDescription.StrokeDescription(path, 0, duration)).build()
            dispatchGesture(gesture, callback, handler)
        } catch (error: RuntimeException) {
            Log.e(TAG, "Gesture dispatch failed action=${request.action}", error)
            false
        }
        Log.i(TAG, "Action id=${request.id} action=${request.action} accepted=$accepted")
        if (!accepted) finishDispatch(active, request, token, ActionOutcome.FAILED)
    }

    private fun finishDispatch(active: SystemInteractionEngine, request: SystemActionRequest, token: Long, outcome: ActionOutcome) {
        // Always release the matching transport reservation, even after a mode/session change.
        val valid = dispatchGuard.complete(token)
        if (!valid || engine !== active || !active.complete(request.id, outcome)) return
        Log.i(TAG, "Action id=${request.id} action=${request.action} outcome=$outcome")
        feedbackUntil = SystemClock.uptimeMillis() + 1000
        when (outcome) {
            ActionOutcome.CANCELLED -> disarm(getString(R.string.system_dispatch_cancelled))
            ActionOutcome.FAILED -> disarm(getString(R.string.system_dispatch_failed))
            else -> render(active.latest, null)
        }
    }

    private fun render(update: SystemInteractionUpdate, observedAtMs: Long?) {
        latest = update.copy(request = null)
        if (engine?.pending == null && dispatchGuard.busy) dispatchGuard.invalidate()
        val result = update.cursor
        val motionActive = result.position == null && update.motion.state != MotionState.IDLE
        val normalized = if (motionActive) engine?.pending?.position ?: lastAim else result.position
        if (result.position != null) lastAim = result.position
        val position = normalized?.let { area?.toDisplay(it) }
        val visual = if (motionActive) result.copy(position = normalized, progress = update.motion.progress) else result
        overlay?.show(position, visual, engine?.tapOutcome ?: TapOutcome.NONE, observedAtMs, interactionLabel())
        val state = "${result.state}/${update.motion.state}/${update.motion.rejection}/${engine?.lastAction}/${engine?.outcome}"
        val now = SystemClock.uptimeMillis()
        if (state != lastReportedState || now - lastMetricAt >= 10_000) {
            lastReportedState = state
            lastMetricAt = now
            Log.d(TAG, "Interaction state=$state position=$position travel=${update.motion.travel} movementMs=${update.motion.movementMs} latencyMs=$drawLatency")
        }
        publish(getString(R.string.system_active))
    }

    private var lastAim: CursorPoint? = CursorPoint(0.5f, 0.5f)

    private fun interactionLabel(): String {
        if (latest.waitingForDispatch) return getString(R.string.system_waiting_dispatch)
        if (SystemClock.uptimeMillis() < feedbackUntil && engine?.lastAction != null) {
            return getString(R.string.system_action_feedback, actionLabel(engine?.lastAction), actionFeedback())
        }
        return when (latest.motion.state) {
            MotionState.IDLE -> ""
            MotionState.CONFIRMING_SCROLL -> getString(R.string.motion_confirm_scroll)
            MotionState.SCROLL_READY -> getString(R.string.motion_scroll_ready)
            MotionState.SCROLLING -> actionLabel(engine?.lastAction)
            MotionState.CONFIRMING_NAVIGATION -> getString(R.string.motion_confirm_navigation)
            MotionState.NAVIGATION_READY -> getString(R.string.motion_navigation_ready)
            MotionState.WAITING_NEUTRAL -> getString(when (latest.motion.rejection) {
                NavigationRejection.NONE -> R.string.motion_neutral_required
                NavigationRejection.POSE_CHANGED -> R.string.navigation_pose_changed
                NavigationRejection.TOO_FAST -> R.string.navigation_too_fast
                NavigationRejection.TIMED_OUT -> R.string.navigation_timed_out
                NavigationRejection.WRONG_DIRECTION -> R.string.navigation_wrong_direction
                NavigationRejection.COOLDOWN -> R.string.navigation_cooldown
            })
        }
    }

    private fun actionLabel(action: SystemAction?): String = when (action) {
        SystemAction.TAP -> getString(R.string.action_tap)
        SystemAction.SWIPE_LEFT -> getString(R.string.action_swipe_left)
        SystemAction.SWIPE_RIGHT -> getString(R.string.action_swipe_right)
        SystemAction.SWIPE_UP -> getString(R.string.action_swipe_up)
        SystemAction.SWIPE_DOWN -> getString(R.string.action_swipe_down)
        SystemAction.BACK -> getString(R.string.action_back)
        SystemAction.HOME -> getString(R.string.action_home)
        null -> getString(R.string.action_none)
    }

    private fun actionFeedback(): String {
        val outcome = engine?.outcome ?: SystemCursorStore.current.actionOutcome
        return getString(when (outcome) {
            ActionOutcome.NONE -> R.string.action_none
            ActionOutcome.DISPATCHING -> R.string.action_dispatching
            ActionOutcome.COMPLETED -> if (engine?.lastAction?.isSwipe == true) R.string.action_swipe_completed else R.string.action_completed
            ActionOutcome.CANCELLED -> R.string.action_cancelled
            ActionOutcome.FAILED -> R.string.action_failed
            ActionOutcome.UNAVAILABLE -> R.string.action_unavailable
            ActionOutcome.INVALIDATED -> R.string.action_invalidated
        })
    }

    private fun publish(message: String) {
        SystemCursorStore.publish(SystemCursorStatus(connected, overlay?.pointerVisible == true,
            overlay?.pointerPosition, latest.cursor.state, latest.cursor.progress,
            engine?.tapOutcome ?: SystemCursorStore.current.outcome, drawLatency, message,
            interaction = interactionLabel(), action = actionLabel(engine?.lastAction),
            actionOutcome = engine?.outcome ?: SystemCursorStore.current.actionOutcome, actionFeedback = actionFeedback()))
    }

    private fun disarm(message: String) {
        InteractionModes.clearIf(InteractionMode.SYSTEM_CURSOR)
        stopMode(message)
        Log.i(TAG, "Disarmed reason=$message")
    }

    private fun stopMode(message: String) {
        handler.removeCallbacks(watchdog)
        engine?.disable()
        dispatchGuard.invalidate()
        latest = SystemInteractionUpdate()
        feedbackUntil = 0L
        lastAim = CursorPoint(0.5f, 0.5f)
        overlay?.let { view ->
            view.onGeometryChanged = null
            view.onCursorDraw = null
            view.visibility = android.view.View.GONE
            if (overlayAdded) {
                runCatching { windows.removeViewImmediate(view) }
                    .onFailure { Log.w(TAG, "Overlay removal failed", it) }
            }
        }
        overlayAdded = false
        overlay = null
        area = null
        publish(message)
        engine = null
    }

    override fun onDisplayAdded(displayId: Int) = Unit
    override fun onDisplayRemoved(displayId: Int) {
        if (displayId == Display.DEFAULT_DISPLAY) disarm(getString(R.string.system_geometry_changed))
    }
    override fun onDisplayChanged(displayId: Int) {
        if (displayId == Display.DEFAULT_DISPLAY && area?.rotation?.let { it != displays.getDisplay(displayId)?.rotation } == true) {
            disarm(getString(R.string.system_geometry_changed))
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        connected = false
        disarm(getString(R.string.system_disconnected))
        registry.currentState = Lifecycle.State.CREATED
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        connected = false
        disarm(getString(R.string.system_disconnected))
        handler.removeCallbacksAndMessages(null)
        displays.unregisterDisplayListener(this)
        unregisterReceiver(screenReceiver)
        registry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    companion object { private const val TAG = "GestureOSSystem" }
}
