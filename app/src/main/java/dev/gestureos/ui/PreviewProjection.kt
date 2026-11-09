package dev.gestureos.ui

import android.graphics.Matrix
import androidx.camera.view.PreviewView
import androidx.camera.view.TransformExperimental
import androidx.camera.view.transform.CoordinateTransform
import dev.gestureos.camera.TrackingFrame
import dev.gestureos.cursor.CursorPoint
import dev.gestureos.cursor.CursorRect
import dev.gestureos.cursor.CursorTransform

/** Uses the SAME CameraX transform for landmarks, active-region bounds, and pointer mapping. */
@androidx.annotation.OptIn(TransformExperimental::class)
data class PreviewProjection(val transform: CursorTransform, val visibleBounds: CursorRect) {
    fun landmarks(frame: TrackingFrame): FloatArray = FloatArray(frame.landmarks.size * 2).also { points ->
        frame.landmarks.forEachIndexed { index, p ->
            val mapped = transform.map(CursorPoint(p.x * frame.width, p.y * frame.height))
            points[index * 2] = mapped.x
            points[index * 2 + 1] = mapped.y
        }
    }

    companion object {
        fun from(frame: TrackingFrame, preview: PreviewView): PreviewProjection? {
            val target = preview.outputTransform ?: return null
            return runCatching {
                val matrix = Matrix()
                CoordinateTransform(frame.sourceTransform, target).transform(matrix)
                val values = FloatArray(9)
                matrix.getValues(values)
                val transform = CursorTransform(values[0], values[1], values[2], values[3], values[4], values[5])
                transform.visibleBounds(frame.width.toFloat(), frame.height.toFloat(), preview.width.toFloat(), preview.height.toFloat())
                    ?.let { PreviewProjection(transform, it) }
            }.getOrNull()
        }
    }
}
