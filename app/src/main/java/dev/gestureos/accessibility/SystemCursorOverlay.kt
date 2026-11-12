package dev.gestureos.accessibility

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import android.view.WindowInsets
import dev.gestureos.cursor.CursorPoint
import dev.gestureos.cursor.CursorResult
import dev.gestureos.cursor.CursorState

/** A drawing-only view. Its accessibility window is non-touchable and non-focusable. */
class SystemCursorOverlay(context: Context) : View(context) {
    var onGeometryChanged: (() -> Unit)? = null
    var onCursorDraw: ((Long) -> Unit)? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private var point: CursorPoint? = null
    private var result = CursorResult()
    private var outcome = TapOutcome.NONE
    private var frameAt: Long? = null
    private var lossAt: Long? = null
    private var interactionLabel = ""
    private val screenOrigin = IntArray(2)
    val pointerVisible get() = point != null && (result.position != null ||
        lossAt?.let { SystemClock.uptimeMillis() - it < 250 } == true)
    val pointerPosition get() = point.takeIf { pointerVisible }

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun show(position: CursorPoint?, cursor: CursorResult, tapOutcome: TapOutcome, observedAtMs: Long?, label: String = "") {
        if (cursor.state == CursorState.TRACKING_LOST && result.state != CursorState.TRACKING_LOST) {
            lossAt = SystemClock.uptimeMillis()
        }
        if (position != null) point = position
        else if (cursor.state != CursorState.TRACKING_LOST) point = null
        result = cursor
        outcome = tapOutcome
        frameAt = observedAtMs
        interactionLabel = label
        invalidate()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        post { onGeometryChanged?.invoke() }
        return super.onApplyWindowInsets(insets)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { onGeometryChanged?.invoke() }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!pointerVisible) return
        val position = point ?: return
        getLocationOnScreen(screenOrigin)
        val x = position.x - screenOrigin[0]
        val y = position.y - screenOrigin[1]
        val radius = 11f * density
        val color = when {
            result.state == CursorState.TRACKING_LOST -> Color.rgb(255, 107, 107)
            result.state == CursorState.CLICKED && outcome == TapOutcome.SUCCEEDED -> Color.rgb(117, 228, 189)
            result.state == CursorState.PINCHING || result.state == CursorState.CLICK_CONFIRMING -> Color.rgb(255, 210, 90)
            else -> Color.rgb(89, 210, 255)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 5f * density
        paint.color = Color.BLACK
        canvas.drawCircle(x, y, radius, paint)
        paint.strokeWidth = 2.5f * density
        paint.color = color
        canvas.drawCircle(x, y, radius, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(x, y, 2f * density, paint)
        if (result.progress > 0f) {
            paint.style = Paint.Style.STROKE
            canvas.drawArc(x - radius * 1.4f, y - radius * 1.4f, x + radius * 1.4f, y + radius * 1.4f,
                -90f, result.progress * 360f, false, paint)
        }
        if (interactionLabel.isNotEmpty()) {
            paint.textSize = 14f * density
            val textWidth = paint.measureText(interactionLabel)
            val textX = (x - textWidth / 2f).coerceIn(8f * density, maxOf(8f * density, width - textWidth - 8f * density))
            val textY = (y + 36f * density).coerceAtMost(height - 16f * density)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4f * density
            paint.color = Color.BLACK
            canvas.drawText(interactionLabel, textX, textY, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            canvas.drawText(interactionLabel, textX, textY, paint)
        }
        frameAt?.let {
            frameAt = null
            onCursorDraw?.invoke(SystemClock.uptimeMillis() - it)
        }
    }
}
