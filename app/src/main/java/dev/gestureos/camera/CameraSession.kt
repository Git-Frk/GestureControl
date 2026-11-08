package dev.gestureos.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.view.TransformExperimental
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.camera.view.transform.OutputTransform
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import dev.gestureos.recognition.Gesture
import dev.gestureos.recognition.GestureClassifier
import dev.gestureos.recognition.GestureStateMachine
import dev.gestureos.recognition.LandmarkSmoother
import dev.gestureos.recognition.Point3
import dev.gestureos.recognition.Recognition
import dev.gestureos.recognition.RecognitionConfig
import dev.gestureos.recognition.isotropicLandmarks
import dev.gestureos.tracking.HandTracker
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@androidx.annotation.OptIn(TransformExperimental::class)
data class TrackingFrame(
    val landmarks: List<Point3>,
    val sourceTransform: OutputTransform,
    val width: Int,
    val height: Int,
    val rawGesture: Gesture,
    val recognition: Recognition,
    val inferenceMs: Float,
    val pipelineMs: Float,
    val processedFps: Float,
    val throttleSkips: Long,
    val observedAtMs: Long,
)

/** A disposable foreground session: one worker, at most one inference, no queued camera backlog. */
@androidx.annotation.OptIn(TransformExperimental::class)
class CameraSession(
    context: Context,
    private val owner: LifecycleOwner,
    private val previewView: PreviewView,
    private val config: RecognitionConfig,
    private val onFrame: (TrackingFrame) -> Unit,
    private val onReady: () -> Unit,
    private val onError: (String) -> Unit,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val main = ContextCompat.getMainExecutor(context)
    private val worker = Executors.newSingleThreadExecutor()
    private val closed = AtomicBoolean(false)
    private val failed = AtomicBoolean(false)
    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var analysis: ImageAnalysis? = null
    private var tracker: HandTracker? = null
    private var cameraState: LiveData<CameraState>? = null
    private var cameraObserver: Observer<CameraState>? = null
    private val classifier = GestureClassifier(config)
    private val smoother = LandmarkSmoother(config)
    private val stateMachine = GestureStateMachine(config)
    private var lastFrameMs = -1L
    private var fpsWindowMs = 0L
    private var fpsCount = 0
    private var processedFps = 0f
    private var throttleSkips = 0L
    private val transformFactory = ImageProxyTransformFactory().apply {
        isUsingCropRect = true
        isUsingRotationDegrees = true
    }

    fun start() {
        worker.execute {
            try {
                if (closed.get()) return@execute
                tracker = HandTracker(appContext, config)
                main.execute { if (!closed.get()) bindCamera() }
            } catch (error: Exception) {
                fail("Hand model could not start", error)
            } catch (error: LinkageError) {
                fail("Hand tracking native library could not load", error)
            }
        }
    }

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(appContext)
        future.addListener({
            if (closed.get()) return@addListener
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                check(cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) { "No front-facing camera is available" }
                val rotation = previewView.display.rotation
                val resolution = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy(AspectRatio.RATIO_4_3, AspectRatioStrategy.FALLBACK_RULE_AUTO))
                    .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                    .build()
                val previewUseCase = Preview.Builder().setTargetRotation(rotation).build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysisUseCase = ImageAnalysis.Builder()
                    .setResolutionSelector(resolution)
                    .setTargetRotation(rotation)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build().also { it.setAnalyzer(worker, ::analyze) }
                preview = previewUseCase
                analysis = analysisUseCase
                // The shared viewport gives preview and analysis the same field of view.
                val viewport = checkNotNull(previewView.viewPort) { "Camera preview is not laid out yet" }
                val group = UseCaseGroup.Builder().setViewPort(viewport)
                    .addUseCase(previewUseCase).addUseCase(analysisUseCase).build()
                val camera = cameraProvider.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, group)
                val observer = Observer<CameraState> { state ->
                    if (!closed.get() && state.error != null) {
                        fail("Camera error ${state.error?.code}", state.error?.cause)
                    }
                }
                cameraState = camera.cameraInfo.cameraState
                cameraObserver = observer
                cameraState?.observe(owner, observer)
                onReady()
                Log.i(TAG, "Front-camera session started; CPU, cap=${config.maxFps} FPS")
            } catch (error: Exception) {
                fail("Front camera could not start", error)
            }
        }, main)
    }

    private fun analyze(image: ImageProxy) {
        var buffer: Bitmap? = null
        var cropped: Bitmap? = null
        var upright: Bitmap? = null
        try {
            if (closed.get() || failed.get()) return
            val now = SystemClock.uptimeMillis()
            if (lastFrameMs >= 0 && now - lastFrameMs < 1000L / config.maxFps) {
                throttleSkips++
                return
            }
            lastFrameMs = now
            val start = SystemClock.elapsedRealtimeNanos()
            val sourceTransform = transformFactory.getOutputTransform(image)
            buffer = image.toBitmap()
            val crop = image.cropRect
            cropped = Bitmap.createBitmap(buffer, crop.left, crop.top, crop.width(), crop.height())
            upright = Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height,
                Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }, false)
            val result = checkNotNull(tracker).detect(upright, now)
            val normalized = result.normalizedLandmarks
            val geometry = isotropicLandmarks(normalized, upright.width, upright.height)
            val raw = classifier.classify(geometry)
            val smoothed = smoother.apply(geometry, now)
            val recognition = stateMachine.update(classifier.classify(smoothed), now)
            // Drawing raw landmarks keeps overlay latency independent of classification smoothing.
            val end = SystemClock.uptimeMillis()
            if (fpsWindowMs == 0L) fpsWindowMs = end
            else fpsCount++
            if (end - fpsWindowMs >= 1000) {
                processedFps = fpsCount * 1000f / (end - fpsWindowMs)
                fpsWindowMs = end
                fpsCount = 0
            }
            val frame = TrackingFrame(normalized, sourceTransform, upright.width, upright.height, raw,
                recognition, result.inferenceMs, (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000f,
                processedFps, throttleSkips, now)
            if (recognition.newlyConfirmed) Log.d(TAG, "Confirmed ${recognition.gesture}")
            main.execute { if (!closed.get() && !failed.get()) onFrame(frame) }
        } catch (error: Exception) {
            fail("Frame processing failed", error)
        } catch (error: LinkageError) {
            fail("Native inference failed", error)
        } finally {
            upright?.recycle()
            if (cropped !== upright) cropped?.recycle()
            if (buffer !== cropped && buffer !== upright) buffer?.recycle()
            image.close()
        }
    }

    private fun fail(message: String, error: Throwable?) {
        if (closed.get() || !failed.compareAndSet(false, true)) return
        Log.e(TAG, message, error)
        main.execute { if (!closed.get()) onError(error?.message?.let { "$message ($it)" } ?: message) }
    }

    /** Called on the main thread; queued cleanup runs after any in-flight detection. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        cameraObserver?.let { cameraState?.removeObserver(it) }
        cameraObserver = null
        cameraState = null
        analysis?.clearAnalyzer()
        val useCases = listOfNotNull(preview, analysis)
        if (useCases.isNotEmpty()) provider?.unbind(*useCases.toTypedArray())
        worker.execute {
            try {
                tracker?.close()
            } catch (error: Exception) {
                Log.w(TAG, "Tracker close failed", error)
            } finally {
                tracker = null
            }
        }
        worker.shutdown()
        Log.i(TAG, "Camera and tracking session stopped")
    }

    companion object { const val TAG = "GestureOS" }
}
