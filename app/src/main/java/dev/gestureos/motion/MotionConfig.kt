package dev.gestureos.motion

/** Movement distances are fractions of the active camera region. Swipe distance is a display fraction. */
data class MotionConfig(
    val poseConfirmMs: Long = 300,
    val deadZone: Float = 0.025f,
    val smoothingMs: Float = 60f,
    val sensitivity: Float = 1f,
    val scrollThreshold: Float = 0.08f,
    val scrollDistance: Float = 0.26f,
    val swipeDurationMs: Long = 180,
    val repeatIntervalMs: Long = 450,
    val navigationDistance: Float = 0.18f,
    val navigationMinMs: Long = 120,
    val navigationMaxMs: Long = 1000,
    val navigationCooldownMs: Long = 1500,
    val neutralMs: Long = 250,
) {
    init {
        require(poseConfirmMs in 200..1000)
        require(deadZone in 0.01f..0.06f && smoothingMs in 0f..200f && sensitivity in 0.5f..2f)
        require(scrollThreshold in 0.04f..0.25f && scrollThreshold >= deadZone * 2)
        require(scrollDistance in 0.1f..0.6f && swipeDurationMs in 80..350)
        require(repeatIntervalMs in 250..2000 && repeatIntervalMs >= swipeDurationMs + 100)
        require(navigationDistance in 0.1f..0.4f && navigationDistance >= deadZone * 3)
        require(navigationMinMs in 80..500 && navigationMaxMs in 400..2000 && navigationMaxMs > navigationMinMs)
        require(navigationCooldownMs in 700..5000 && neutralMs in 150..800)
    }
}
