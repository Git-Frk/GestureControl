package dev.gestureos.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Surface
import androidx.camera.core.Preview
import androidx.camera.view.TransformExperimental
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import dev.gestureos.MainActivity
import dev.gestureos.R
import dev.gestureos.camera.CameraSession
import dev.gestureos.camera.TrackingFrame
import dev.gestureos.recognition.RecognitionSettings
import java.util.Locale

/** The only camera owner. Binding attaches a UI surface without starting capture. */
@androidx.annotation.OptIn(TransformExperimental::class)
class TrackingService : Service(), LifecycleOwner, DisplayManager.DisplayListener {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
    private val handler = Handler(Looper.getMainLooper())
    private val policy get() = TrackingStore.policy
    private val displays by lazy { getSystemService(DisplayManager::class.java) }
    private var session: CameraSession? = null
    private var previewClient: Any? = null
    private var lastFrameAt = 0L
    private var lastReportAt = 0L
    private var frames = 0L
    private var handFrames = 0L
    private var destroyed = false
    private var restartAfterRelease = false

    inner class LocalBinder : Binder() {
        fun attach(client: Any, provider: Preview.SurfaceProvider) {
            previewClient = client
            session?.attachPreview(provider)
        }

        fun detach(client: Any) {
            if (previewClient !== client) return
            previewClient = null
            session?.attachPreview(null)
        }

        fun setBackground(enabled: Boolean) {
            if (!TrackingStore.uiVisible || !policy.status.active) return
            policy.start(true, cameraGranted(), enabled)
            TrackingStore.publishStatus()
            updateNotification()
        }

        fun stop(reason: StopReason = StopReason.USER) = stopTracking(reason)
    }

    private val binder = LocalBinder()
    private val watchdog = object : Runnable {
        override fun run() {
            if (!policy.status.active || destroyed) return
            when {
                !cameraGranted() -> stopTracking(StopReason.FAILURE, getString(R.string.permission_revoked))
                !TrackingStore.uiVisible && policy.shouldStopWhenHidden() -> stopTracking(StopReason.HIDDEN)
                SystemClock.uptimeMillis() - lastFrameAt > 10_000 ->
                    stopTracking(StopReason.FAILURE, getString(R.string.tracking_stalled))
                else -> handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        registry.currentState = Lifecycle.State.STARTED
        displays.registerDisplayListener(this, handler)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.tracking_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onBind(intent: Intent) = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTracking(StopReason.USER)
            if (!policy.status.active && policy.status.phase != TrackingPhase.STOPPING) stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RECONFIGURE) {
            stopTracking(StopReason.RECONFIGURE)
            if (!policy.status.active && policy.status.phase != TrackingPhase.STOPPING) stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) {
            stopSelf()
            return START_NOT_STICKY
        }
        val decision = policy.start(TrackingStore.uiVisible, cameraGranted(), intent.getBooleanExtra(EXTRA_BACKGROUND, false))
        if (decision == StartDecision.REJECTED) {
            if (!policy.status.active) stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            if (decision == StartDecision.START) startTracking()
            TrackingStore.publishStatus()
        } catch (error: RuntimeException) {
            Log.e(TAG, "Foreground camera start rejected", error)
            stopTracking(StopReason.FAILURE, error.message ?: getString(R.string.service_start_failed))
        }
        return START_NOT_STICKY
    }

    private fun startTracking() {
        val generation = policy.status.generation
        lastFrameAt = SystemClock.uptimeMillis()
        lastReportAt = 0
        frames = 0
        handFrames = 0
        TrackingStore.clearFrame()
        session = CameraSession(this, this, rotation(), generation, RecognitionSettings.load(this),
            onFrame = ::onFrame,
            onReady = {
                policy.ready(generation)
                TrackingStore.publishStatus()
            },
            onError = { stopTracking(StopReason.FAILURE, it) },
        ).also { it.start() }
        handler.postDelayed(watchdog, 1000)
        Log.i(TAG, "Started session=$generation background=${policy.status.backgroundEnabled}")
    }

    private fun onFrame(frame: TrackingFrame) {
        if (!policy.accepts(frame.sessionGeneration)) return
        lastFrameAt = SystemClock.uptimeMillis()
        frames++
        if (frame.landmarks.isNotEmpty()) handFrames++
        TrackingStore.publishFrame(frame)
        if (lastFrameAt - lastReportAt >= 10_000) {
            lastReportAt = lastFrameAt
            val thermal = getSystemService(PowerManager::class.java).currentThermalStatus
            Log.i(TAG, String.format(Locale.US,
                "Metrics session=%d visible=%s frames=%d hands=%d fps=%.2f inferenceMs=%.2f pipelineMs=%.2f raw=%s state=%s thermal=%d",
                frame.sessionGeneration, TrackingStore.uiVisible, frames, handFrames, frame.processedFps,
                frame.inferenceMs, frame.pipelineMs, frame.rawGesture, frame.recognition.state, thermal))
        }
    }

    private fun stopTracking(reason: StopReason, error: String? = null) {
        restartAfterRelease = reason == StopReason.RECONFIGURE
        val background = policy.status.backgroundEnabled
        val generation = policy.stop(reason, error) ?: return
        handler.removeCallbacks(watchdog)
        TrackingStore.clearFrame()
        TrackingStore.publishStatus()
        previewClient = null
        val closing = session
        session = null
        // CameraX is unbound synchronously. Native cleanup waits for the current inference.
        val released: () -> Unit = {
            policy.released(generation)
            if (restartAfterRelease && !destroyed && TrackingStore.uiVisible && cameraGranted()) {
                policy.start(true, true, background)
                startTracking()
                updateNotification()
            } else {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            TrackingStore.publishStatus()
            Log.i(TAG, "Stopped reason=$reason error=$error")
        }
        if (closing == null) released() else closing.close(released)
    }

    private fun cameraGranted() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    private fun rotation() = displays.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0

    private fun notification(): android.app.Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, TrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tracking_notification)
            .setContentTitle(getString(R.string.tracking_notification_title))
            .setContentText(getString(if (policy.status.backgroundEnabled) R.string.tracking_notification_background else R.string.tracking_notification_visible))
            .setContentIntent(open).setOngoing(true).setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.stop_tracking), stop)
            .build()
    }

    private fun updateNotification() {
        // Notification permission affects drawer visibility, never foreground-service eligibility.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
        }
    }

    override fun onDisplayAdded(displayId: Int) = Unit
    override fun onDisplayRemoved(displayId: Int) = Unit
    override fun onDisplayChanged(displayId: Int) {
        if (displayId == Display.DEFAULT_DISPLAY) session?.setRotation(rotation())
    }

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        displays.unregisterDisplayListener(this)
        previewClient = null
        val closing = session
        session = null
        if (policy.status.active) {
            val generation = policy.stop(StopReason.INTERRUPTED)
            TrackingStore.clearFrame()
            TrackingStore.publishStatus()
            val released: () -> Unit = {
                if (generation != null) policy.released(generation)
                TrackingStore.publishStatus()
            }
            if (closing == null) released() else closing.close(released)
        }
        registry.currentState = Lifecycle.State.DESTROYED
        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.i(TAG, "Service destroyed")
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "dev.gestureos.START_TRACKING"
        const val ACTION_STOP = "dev.gestureos.STOP_TRACKING"
        const val ACTION_RECONFIGURE = "dev.gestureos.RECONFIGURE_TRACKING"
        const val EXTRA_BACKGROUND = "background"
        private const val CHANNEL = "camera_tracking"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "GestureOSTracking"
    }
}
