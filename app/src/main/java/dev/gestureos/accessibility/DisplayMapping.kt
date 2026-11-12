package dev.gestureos.accessibility

import dev.gestureos.cursor.CursorConfig
import dev.gestureos.cursor.CursorMapper
import dev.gestureos.cursor.CursorPoint
import dev.gestureos.cursor.CursorRect
import dev.gestureos.cursor.CursorTransform

data class FrameAlignment(val session: Long, val orientation: Long, val displayRotation: Int)

data class DisplayArea(
    val displayId: Int,
    val rotation: Int,
    val bounds: CursorRect,
    val usable: CursorRect,
) {
    init {
        require(rotation in 0..3)
        require(usable.width > 1f && usable.height > 1f)
        require(bounds.contains(CursorPoint(usable.left, usable.top)))
        require(bounds.contains(CursorPoint(usable.right, usable.bottom)))
    }

    fun toDisplay(normalized: CursorPoint): CursorPoint? {
        if (!normalized.finite()) return null
        val point = normalized.clamped()
        return CursorPoint(usable.left + point.x * (usable.width - 1f),
            usable.top + point.y * (usable.height - 1f))
    }
}

/** Input is already cropped and rotated upright by CameraX. Mirror exactly once here.
 * Each active-region axis spans the usable display, independently of its aspect ratio. */
class DisplayCursorMapper(private val config: CursorConfig) {
    private val mapper = CursorMapper(config)
    private val frontMirror = CursorTransform(-1f, 0f, 1f, 0f, 1f, 0f)
    private val imageBounds = CursorRect(0f, 0f, 1f, 1f)

    fun map(uprightTip: CursorPoint, frameRotation: Int, displayRotation: Int): CursorPoint? {
        if (frameRotation !in 0..3 || frameRotation != displayRotation) return null
        return mapper.map(uprightTip, frontMirror, imageBounds)
    }

    fun mapMotion(uprightPalm: CursorPoint, frameRotation: Int, displayRotation: Int): CursorPoint? {
        if (!uprightPalm.finite() || frameRotation !in 0..3 || frameRotation != displayRotation) return null
        val mirrored = frontMirror.map(uprightPalm)
        if (!config.region.contains(mirrored)) return null
        return CursorPoint((mirrored.x - config.region.left) / config.region.width,
            (mirrored.y - config.region.top) / config.region.height)
    }
}
