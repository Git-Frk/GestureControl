package dev.gestureos.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.widget.Button
import android.widget.FrameLayout
import dev.gestureos.R
import dev.gestureos.cursor.CursorPoint
import dev.gestureos.cursor.CursorResult
import dev.gestureos.cursor.CursorState
import dev.gestureos.cursor.CursorTargets

/** Local native buttons only: no injected input events, windows, or system actions. */
class CursorTestArea(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    private val density = resources.displayMetrics.density
    private val inset = 14f * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var cursor = CursorResult()
    private var pendingFrameAt: Long? = null
    private var selections = 0
    var onSelection: ((Int, Int) -> Unit)? = null
    var onCursorDraw: ((Long) -> Unit)? = null
    var onGeometryChanged: (() -> Unit)? = null
    private val targets: List<Button> = List(6) { index ->
        Button(context).apply {
            text = context.getString(R.string.cursor_target, index + 1)
            isAllCaps = false
            textSize = 16f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(30, 55, 67))
            setOnClickListener {
                selections++
                this@CursorTestArea.targets.forEach { it.setBackgroundColor(Color.rgb(30, 55, 67)) }
                setBackgroundColor(Color.rgb(18, 112, 88))
                onSelection?.invoke(index + 1, selections)
            }
            addView(this)
        }
    }

    fun show(result: CursorResult, frameAtMs: Long? = null) {
        cursor = result
        pendingFrameAt = if (result.position != null) frameAtMs else null
        postInvalidateOnAnimation()
    }

    fun select(point: CursorPoint): Boolean {
        val index = CursorTargets.hitTest(point) ?: return false
        return targets[index].performClick()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        onGeometryChanged?.invoke()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        targets.forEachIndexed { index, button ->
            val rect = CursorTargets.bounds[index]
            button.measure(MeasureSpec.makeMeasureSpec(((measuredWidth - 2 * inset) * rect.width).toInt().coerceAtLeast(1), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(((measuredHeight - 2 * inset) * rect.height).toInt().coerceAtLeast(1), MeasureSpec.EXACTLY))
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val usableWidth = (width - 2 * inset).coerceAtLeast(1f)
        val usableHeight = (height - 2 * inset).coerceAtLeast(1f)
        targets.forEachIndexed { index, button ->
            val rect = CursorTargets.bounds[index]
            button.layout((inset + rect.left * usableWidth).toInt(), (inset + rect.top * usableHeight).toInt(),
                (inset + rect.right * usableWidth).toInt(), (inset + rect.bottom * usableHeight).toInt())
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val position = cursor.position ?: return
        val x = inset + position.x * (width - 2 * inset).coerceAtLeast(1f)
        val y = inset + position.y * (height - 2 * inset).coerceAtLeast(1f)
        val radius = 10f * density
        paint.style = Paint.Style.FILL
        paint.color = Color.BLACK
        canvas.drawCircle(x, y, radius + 2 * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3 * density
        paint.color = if (cursor.state == CursorState.POINTING) Color.rgb(117, 228, 189) else Color.rgb(255, 208, 91)
        canvas.drawCircle(x, y, radius, paint)
        canvas.drawLine(x - radius - 3 * density, y, x + radius + 3 * density, y, paint)
        canvas.drawLine(x, y - radius - 3 * density, x, y + radius + 3 * density, paint)
        if (cursor.progress > 0) canvas.drawArc(x - radius, y - radius, x + radius, y + radius,
            -90f, cursor.progress * 360f, false, paint)
        pendingFrameAt?.let { frameAt ->
            pendingFrameAt = null
            val latency = SystemClock.uptimeMillis() - frameAt
            // Measured at draw, not frame receipt; post UI feedback outside the drawing pass.
            post { onCursorDraw?.invoke(latency) }
        }
    }
}
