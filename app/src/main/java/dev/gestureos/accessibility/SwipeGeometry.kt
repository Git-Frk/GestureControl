package dev.gestureos.accessibility

import dev.gestureos.cursor.CursorPoint
import dev.gestureos.interaction.SystemAction

data class SwipePath(val start: CursorPoint, val end: CursorPoint)

/** Keep the entire swipe inside usable display bounds, centred near the last pointing location. */
object SwipeGeometry {
    fun create(action: SystemAction, aim: CursorPoint, fraction: Float, display: DisplayArea): SwipePath {
        require(action.isSwipe && aim.finite() && fraction in 0.1f..0.6f)
        val half = fraction / 2f
        val horizontal = action == SystemAction.SWIPE_LEFT || action == SystemAction.SWIPE_RIGHT
        val center = CursorPoint(
            aim.x.coerceIn(0.04f + if (horizontal) half else 0f, 0.96f - if (horizontal) half else 0f),
            aim.y.coerceIn(0.04f + if (horizontal) 0f else half, 0.96f - if (horizontal) 0f else half))
        val sign = if (action == SystemAction.SWIPE_LEFT || action == SystemAction.SWIPE_UP) -1f else 1f
        val dx = if (horizontal) half * sign else 0f
        val dy = if (horizontal) 0f else half * sign
        return SwipePath(checkNotNull(display.toDisplay(CursorPoint(center.x - dx, center.y - dy))),
            checkNotNull(display.toDisplay(CursorPoint(center.x + dx, center.y + dy))))
    }
}
