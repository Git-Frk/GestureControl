package dev.gestureos.cursor

import kotlin.math.hypot

data class CursorPoint(val x: Float, val y: Float) {
    fun finite() = x.isFinite() && y.isFinite()
    fun clamped() = CursorPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
    fun distance(other: CursorPoint) = hypot(x - other.x, y - other.y)
}

data class CursorRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init { require(listOf(left, top, right, bottom).all { it.isFinite() } && right > left && bottom > top) }
    val width get() = right - left
    val height get() = bottom - top
    fun contains(point: CursorPoint) = point.x in left..right && point.y in top..bottom
    fun region(fraction: CursorRect) = CursorRect(left + fraction.left * width, top + fraction.top * height,
        left + fraction.right * width, top + fraction.bottom * height)
}

/** Row-major affine transform copied from CameraX, including front mirroring (exactly once). */
data class CursorTransform(val a: Float, val b: Float, val tx: Float, val c: Float, val d: Float, val ty: Float) {
    init {
        require(listOf(a, b, tx, c, d, ty).all { it.isFinite() })
        require(kotlin.math.abs(a * d - b * c) > 1e-8f)
    }
    fun map(point: CursorPoint) = CursorPoint(a * point.x + b * point.y + tx, c * point.x + d * point.y + ty)

    fun visibleBounds(width: Float, height: Float, viewWidth: Float, viewHeight: Float): CursorRect? {
        if (width <= 0 || height <= 0 || viewWidth <= 0 || viewHeight <= 0) return null
        val corners = listOf(CursorPoint(0f, 0f), CursorPoint(width, 0f), CursorPoint(width, height), CursorPoint(0f, height)).map(::map)
        val left = corners.minOf { it.x }.coerceAtLeast(0f)
        val top = corners.minOf { it.y }.coerceAtLeast(0f)
        val right = corners.maxOf { it.x }.coerceAtMost(viewWidth)
        val bottom = corners.maxOf { it.y }.coerceAtMost(viewHeight)
        return if (right > left && bottom > top) CursorRect(left, top, right, bottom) else null
    }
}

/** Maps upright, cropped image pixels through CameraX into a normalized interactive area. */
class CursorMapper(private val config: CursorConfig) {
    fun map(imagePoint: CursorPoint, transform: CursorTransform, visibleBounds: CursorRect): CursorPoint? {
        if (!imagePoint.finite()) return null
        val point = transform.map(imagePoint)
        if (!point.finite()) return null
        val active = visibleBounds.region(config.region)
        return CursorPoint(
            ((point.x - active.left) / active.width - 0.5f) * config.sensitivity + 0.5f,
            ((point.y - active.top) / active.height - 0.5f) * config.sensitivity + 0.5f,
        ).clamped()
    }
}

/** Shared drawing/hit-test geometry: selections never escape this local test area. */
object CursorTargets {
    val bounds = List(6) { index ->
        val column = index % 2
        val row = index / 2
        CursorRect(0.06f + column * 0.5f, 0.05f + row * 0.333f,
            0.44f + column * 0.5f, 0.28f + row * 0.333f)
    }
    fun hitTest(point: CursorPoint): Int? = bounds.indexOfFirst { it.contains(point) }.takeIf { it >= 0 }
}
