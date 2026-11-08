package dev.gestureos.tracking

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import dev.gestureos.recognition.Point3
import dev.gestureos.recognition.RecognitionConfig

data class HandResult(val normalizedLandmarks: List<Point3>, val inferenceMs: Float)

/** Created, called, and closed on the same camera worker. VIDEO mode retains temporal tracking. */
class HandTracker(context: Context, config: RecognitionConfig) : AutoCloseable {
    private val landmarker = HandLandmarker.createFromOptions(
        context,
        HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").setDelegate(Delegate.CPU).build())
            .setRunningMode(RunningMode.VIDEO)
            .setNumHands(1)
            .setMinHandDetectionConfidence(config.minDetectionConfidence)
            .setMinHandPresenceConfidence(config.minPresenceConfidence)
            .setMinTrackingConfidence(config.minTrackingConfidence)
            .build(),
    )

    fun detect(uprightBitmap: Bitmap, timestampMs: Long): HandResult {
        val image = BitmapImageBuilder(uprightBitmap).build()
        return try {
            val start = SystemClock.elapsedRealtimeNanos()
            val result = landmarker.detectForVideo(image, timestampMs)
            val elapsedMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000f
            HandResult(
                result.landmarks().firstOrNull()?.map { Point3(it.x(), it.y(), it.z()) }.orEmpty(),
                elapsedMs,
            )
        } finally {
            image.close()
        }
    }

    override fun close() = landmarker.close()
}
