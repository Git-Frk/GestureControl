package dev.gestureos.cursor

/** All distances are normalized to the visible image, interactive area, or palm width. */
data class CursorConfig(
    val sensitivity: Float = 1f,
    val smoothingMs: Float = 85f,
    val deadZone: Float = 0.004f,
    val maxSpeed: Float = 3f,
    val region: CursorRect = CursorRect(0.15f, 0.15f, 0.85f, 0.85f),
    val pinchEntry: Float = 0.30f,
    val pinchRelease: Float = 0.45f,
    val pointArmMs: Long = 200,
    val approachTimeoutMs: Long = 800,
    val pinchDebounceMs: Long = 80,
    val pinchConfirmMs: Long = 200,
    val releaseMs: Long = 150,
    val clickCooldownMs: Long = 450,
    val maxFrameAgeMs: Long = 350,
) {
    init {
        require(sensitivity in 0.5f..2f && smoothingMs in 0f..300f)
        require(deadZone in 0f..0.03f && maxSpeed in 0.5f..6f)
        require(region.left >= 0f && region.top >= 0f && region.right <= 1f && region.bottom <= 1f)
        require(region.width >= 0.3f && region.height >= 0.3f)
        require(pinchEntry in 0.1f..0.5f && pinchRelease in 0.2f..0.8f && pinchRelease >= pinchEntry + 0.1f)
        require(pointArmMs in 100..1000 && pinchDebounceMs in 40..500 && pinchConfirmMs in 100..1000)
        require(approachTimeoutMs in 250..1500)
        require(releaseMs in 80..500 && clickCooldownMs in 200..1500 && maxFrameAgeMs in 150..500)
    }
}
