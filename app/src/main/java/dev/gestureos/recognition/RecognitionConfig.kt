package dev.gestureos.recognition

/** Tunable heuristics, not probabilities. Defaults require calibration on the Nokia T20. */
data class RecognitionConfig(
    val extendedAngle: Float = 155f,
    val foldedAngle: Float = 125f,
    val thumbAngle: Float = 150f,
    val tipReachRatio: Float = 1.08f,
    val thumbReachRatio: Float = 0.65f,
    val verticalThumbRatio: Float = 0.75f,
    // Visible thumb-tip clearance beyond the curled fingers, in palm-width units.
    val thumbClearanceRatio: Float = 0.2f,
    val holdMs: Long = 400,
    val cooldownMs: Long = 600,
    val maxObservationGapMs: Long = 350,
    val smoothingAlpha: Float = 0.65f,
    val maxFps: Int = 12,
    val minDetectionConfidence: Float = 0.5f,
    val minPresenceConfidence: Float = 0.5f,
    val minTrackingConfidence: Float = 0.5f,
) {
    init {
        require(extendedAngle in 140f..175f)
        require(foldedAngle in 90f..140f && foldedAngle < extendedAngle)
        require(thumbAngle in 120f..175f)
        require(tipReachRatio in 1f..2f && thumbReachRatio in 0.2f..2f)
        require(verticalThumbRatio in 0.5f..1f)
        require(thumbClearanceRatio in 0.1f..1f)
        require(holdMs in 100..2000 && cooldownMs in 0..3000)
        require(maxObservationGapMs in 100..2000)
        require(smoothingAlpha in 0.1f..1f && maxFps in 5..30)
        require(minDetectionConfidence in 0f..1f)
        require(minPresenceConfidence in 0f..1f && minTrackingConfidence in 0f..1f)
    }
}
