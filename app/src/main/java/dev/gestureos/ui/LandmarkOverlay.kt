package dev.gestureos.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import dev.gestureos.recognition.GestureClassifier
import dev.gestureos.cursor.CursorRect

/** Coordinates arrive in PreviewView pixels after CameraX crop, rotation, fit, and mirroring. */
class LandmarkOverlay(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var points = FloatArray(0)
    private var activeRegion: CursorRect? = null
    private val density = resources.displayMetrics.density
    private val lines = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(117, 228, 189)
        strokeWidth = 2f * density
    }
    private val joints = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    fun show(mappedPoints: FloatArray) {
        points = mappedPoints
        invalidate()
    }

    fun setActiveRegion(region: CursorRect?) { activeRegion = region; invalidate() }

    fun clear() { activeRegion = null; show(FloatArray(0)) }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        activeRegion?.let {
            lines.style = Paint.Style.STROKE
            canvas.drawRect(it.left, it.top, it.right, it.bottom, lines)
        }
        if (points.size != 42) return
        GestureClassifier.BONES.forEach { (a, b) ->
            canvas.drawLine(points[a * 2], points[a * 2 + 1], points[b * 2], points[b * 2 + 1], lines)
        }
        for (i in 0 until 21) canvas.drawCircle(points[i * 2], points[i * 2 + 1], 3f * density, joints)
    }
}
