package dev.gestureos.volume

data class VolumeControlConfig(
    val repeatEnabled: Boolean = true,
    val repeatDelayMs: Long = 1200,
    val repeatIntervalMs: Long = 700,
    val minimumActionIntervalMs: Long = 400,
    val reversalCooldownMs: Long = 1500,
    val maxFrameAgeMs: Long = 350,
    val maxObservationGapMs: Long = 350,
) {
    init {
        require(repeatDelayMs in 600..5000)
        require(repeatIntervalMs in 400..3000)
        require(minimumActionIntervalMs in 200..repeatIntervalMs)
        require(reversalCooldownMs in minimumActionIntervalMs..5000)
        require(maxFrameAgeMs in 100..1000 && maxObservationGapMs in 100..1000)
    }
}
