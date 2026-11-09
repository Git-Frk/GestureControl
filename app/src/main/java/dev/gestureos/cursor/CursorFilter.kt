package dev.gestureos.cursor

import kotlin.math.exp

/** Time-based EMA plus a stationary dead zone and bounded travel per second. */
class CursorFilter(private val config: CursorConfig) {
    private var previous: CursorPoint? = null
    private var previousAt = 0L
    fun reset() { previous = null; previousAt = 0 }
    fun update(point: CursorPoint, atMs: Long): CursorPoint {
        require(point.finite() && atMs >= 0)
        val target = point.clamped()
        val old = previous
        val dt = (atMs - previousAt).coerceAtLeast(0)
        val result = if (old == null || dt > config.maxFrameAgeMs) target else {
            val distance = target.distance(old)
            if (distance <= config.deadZone || dt == 0L) old else {
                val alpha = if (config.smoothingMs == 0f) 1f else 1f - exp(-dt / config.smoothingMs)
                val fraction = minOf(alpha, config.maxSpeed * dt / 1000f / distance)
                CursorPoint(old.x + (target.x - old.x) * fraction, old.y + (target.y - old.y) * fraction).clamped()
            }
        }
        previous = result
        previousAt = atMs
        return result
    }
}
