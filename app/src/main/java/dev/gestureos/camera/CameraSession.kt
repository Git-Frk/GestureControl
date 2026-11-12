package dev.gestureos.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import android.util.Rational
import android.util.Size
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
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
import java.util.concurrent.atomic.AtomicReference

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
    val sessionGeneration: Long,
    val orientationGeneration: Long,
    val displayRotation: Int,
)

/** Service-owned capture and inference. Preview attachment never creates another camera session. */
@androidx.annotation.OptIn(TransformExperimental::class)
class CameraSession(
    context: Context,
    private val owner: LifecycleOwner,
    initialRotation: Int,
    private val sessionGeneration: Long,
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
    private data class CaptureOrientation(val displayRotation: Int, val imageRotation: Int, val generation: Long)
    private val orientation = AtomicReference(CaptureOrientation(initialRotation, -1, 0))
    private var cameraInfo: CameraInfo? = null
    private var rotation = initialRotation
    private var surfaceProvider: Preview.SurfaceProvider? = null
    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var analysis: ImageAnalysis? = null
    private var tracker: HandTracker? = null
    private var cameraState: LiveData<CameraState>? = null
    private var cameraObserver: Observer<CameraState>? = null
    private val classifier = GestureClassifier(config)
    private var smoother = LandmarkSmoother(config)
    private var stateMachine = GestureStateMachine(config)
    private var processedOrientation = -1L
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
                val resolution = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy(AspectRatio.RATIO_4_3, AspectRatioStrategy.FALLBACK_RULE_AUTO))
                    .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                    .build()
                val previewUseCase = Preview.Builder().setResolutionSelector(resolution)
                    .setTargetRotation(rotation).build().also {
                    it.setSurfaceProvider(surfaceProvider)
                }
                val analysisUseCase = ImageAnalysis.Builder()
                    .setResolutionSelector(resolution)
                    .setTargetRotation(rotation)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build().also { it.setAnalyzer(worker, ::analyze) }
                preview = previewUseCase
                analysis = analysisUseCase
                // A stable, service-owned viewport keeps headless analysis and UI preview aligned.
                val viewport = ViewPort.Builder(Rational(4, 3), rotation).setScaleType(ViewPort.FIT).build()
                val group = UseCaseGroup.Builder().setViewPort(viewport)
                    .addUseCase(previewUseCase).addUseCase(analysisUseCase).build()
                val camera = cameraProvider.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, group)
                cameraInfo = camera.cameraInfo
                orientation.set(CaptureOrientation(rotation, camera.cameraInfo.getSensorRotationDegrees(rotation), orientation.get().generation))
                val observer = Observer<CameraState> { state ->
                    if (!closed.get() && state.error != null) {
                        fail("Camera error ${state.error?.code}", state.error?.cause)
                    }
                }
                cameraState = camera.cameraInfo.cameraState
                cameraObserver = observer
                cameraState?.observe(owner, observer)
                onReady()
                Log.i(TAG, "Camera bound; session=$sessionGeneration; CPU; cap=${config.maxFps} FPS")
            } catch (error: Exception) {
                fail("Front camera could not start", error)
            }
        }, main)
    }

    fun attachPreview(provider: Preview.SurfaceProvider?) {
        if (closed.get()) return
        surfaceProvider = provider
        preview?.setSurfaceProvider(provider)
    }

    fun setRotation(updated: Int) {
        if (closed.get() || rotation == updated) return
        rotation = updated
        orientation.set(CaptureOrientation(updated, cameraInfo?.getSensorRotationDegrees(updated) ?: -1, orientation.get().generation + 1))
        preview?.targetRotation = updated
        analysis?.targetRotation = updated
    }

    private fun analyze(image: ImageProxy) {
        var buffer: Bitmap? = null
        var cropped: Bitmap? = null
        var upright: Bitmap? = null
        try {
            if (closed.get() || failed.get()) return
            val now = SystemClock.uptimeMillis()
            val captureOrientation = orientation.get()
            // Discard frames still carrying the previous CameraX rotation during reconfiguration.
            if (image.imageInfo.rotationDegrees != captureOrientation.imageRotation) return
            if (lastFrameMs >= 0 && now - lastFrameMs < 1000L / config.maxFps) {
                throttleSkips++
                return
            }
            lastFrameMs = now
            if (processedOrientation != captureOrientation.generation) {
                smoother = LandmarkSmoother(config)
                stateMachine = GestureStateMachine(config)
                processedOrientation = captureOrientation.generation
            }
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
                processedFps, throttleSkips, now, sessionGeneration, captureOrientation.generation, captureOrientation.displayRotation)
            if (recognition.newlyConfirmed) Log.d(TAG, "Confirmed ${recognition.gesture}")
            main.execute {
                if (!closed.get() && !failed.get() && orientation.get() == captureOrientation) onFrame(frame)
            }
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
    override fun close() = close {}

    fun close(onReleased: () -> Unit) {
        if (!closed.compareAndSet(false, true)) return
        try {
            cameraObserver?.let { cameraState?.removeObserver(it) }
            analysis?.clearAnalyzer()
            preview?.setSurfaceProvider(null)
            val useCases = listOfNotNull(preview, analysis)
            if (useCases.isNotEmpty()) provider?.unbind(*useCases.toTypedArray())
        } catch (error: RuntimeException) {
            Log.w(TAG, "Camera cleanup failed", error)
        } finally {
            cameraObserver = null
            cameraState = null
            surfaceProvider = null
        }
        worker.execute {
            try {
                tracker?.close()
            } catch (error: Exception) {
                Log.w(TAG, "Tracker close failed", error)
            } finally {
                tracker = null
                main.execute(onReleased)
            }
        }
        worker.shutdown()
        Log.i(TAG, "Camera unbound; session=$sessionGeneration")
    }

    companion object { const val TAG = "GestureOS" }
}
