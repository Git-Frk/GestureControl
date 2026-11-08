package dev.gestureos.recognition

enum class RecognitionState { NO_HAND, UNRECOGNIZED, HOLDING, CONFIRMED, COOLDOWN }

data class Recognition(
    val gesture: Gesture,
    val state: RecognitionState,
    val holdProgress: Float = 0f,
    val newlyConfirmed: Boolean = false,
)

/** One confirmation per continuous pose; monotonic time makes this independent of frame rate. */
class GestureStateMachine(private val config: RecognitionConfig = RecognitionConfig()) {
    private var candidate = Gesture.NO_HAND
    private var candidateSince = 0L
    private var confirmed = false
    private var lastObservation: Long? = null
    private var lastConfirmation: Long? = null

    fun update(gesture: Gesture, timestampMs: Long): Recognition {
        require(timestampMs >= 0 && timestampMs >= (lastObservation ?: 0))
        val gap = lastObservation?.let { timestampMs - it > config.maxObservationGapMs } ?: false
        lastObservation = timestampMs
        if (gesture != candidate || gap) {
            candidate = gesture
            candidateSince = timestampMs
            confirmed = false
        }
        if (gesture == Gesture.NO_HAND) return Recognition(gesture, RecognitionState.NO_HAND)
        if (gesture == Gesture.UNKNOWN) return Recognition(gesture, RecognitionState.UNRECOGNIZED)
        if (confirmed) return Recognition(gesture, RecognitionState.CONFIRMED, 1f)
        val progress = ((timestampMs - candidateSince).toFloat() / config.holdMs).coerceIn(0f, 1f)
        if (progress < 1f) return Recognition(gesture, RecognitionState.HOLDING, progress)
        if (lastConfirmation?.let { timestampMs - it < config.cooldownMs } == true) {
            return Recognition(gesture, RecognitionState.COOLDOWN, 1f)
        }
        confirmed = true
        lastConfirmation = timestampMs
        return Recognition(gesture, RecognitionState.CONFIRMED, 1f, newlyConfirmed = true)
    }
}
