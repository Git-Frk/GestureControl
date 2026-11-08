package dev.gestureos.volume

import dev.gestureos.recognition.Gesture
import dev.gestureos.recognition.Recognition

data class VolumeLevel(val current: Int, val minimum: Int, val maximum: Int, val muted: Boolean = false) {
    init { require(minimum >= 0 && maximum >= minimum && current in minimum..maximum) }
    fun atLimit(direction: VolumeDirection) = if (direction == VolumeDirection.UP) current == maximum else current == minimum
}

enum class VolumeRestriction { FIXED, POLICY, DENIED, UNAVAILABLE }
data class VolumeReading(val level: VolumeLevel? = null, val restriction: VolumeRestriction? = null)

/** Android is isolated behind this port; the controller and timing rules are plain Kotlin. */
interface MediaVolumeDevice {
    fun read(): VolumeReading
    fun adjustOneStep(direction: VolumeDirection): VolumeReading
}

enum class VolumeOutcome { RAISED, LOWERED, MINIMUM, MAXIMUM, BLOCKED, UNCHANGED }
data class VolumeFeedback(val reading: VolumeReading, val outcome: VolumeOutcome, val repeated: Boolean)

class MediaVolumeController(
    private val device: MediaVolumeDevice,
    config: VolumeControlConfig = VolumeControlConfig(),
) {
    private val stateMachine = VolumeStateMachine(config)
    private var enabled = false
    private var enabledAtMs = 0L
    fun setEnabled(value: Boolean, nowMs: Long) {
        require(nowMs >= 0)
        enabled = value
        enabledAtMs = nowMs
        cancelHold()
    }
    fun cancelHold() = stateMachine.cancelHold()
    fun read() = device.read()

    fun onFrame(recognition: Recognition, rawGesture: Gesture, frameAtMs: Long, nowMs: Long): VolumeFeedback? {
        if (!enabled || frameAtMs < enabledAtMs) return null
        val request = stateMachine.update(recognition, rawGesture, frameAtMs, nowMs) ?: return null
        val before = device.read()
        val level = before.level
        if (before.restriction != null || level == null) {
            cancelHold()
            return VolumeFeedback(before, VolumeOutcome.BLOCKED, request.repeated)
        }
        if (level.atLimit(request.direction)) {
            cancelHold()
            return VolumeFeedback(before, if (request.direction == VolumeDirection.UP) VolumeOutcome.MAXIMUM else VolumeOutcome.MINIMUM, request.repeated)
        }
        val after = device.adjustOneStep(request.direction)
        val outcome = when {
            after.restriction != null || after.level == null -> VolumeOutcome.BLOCKED
            request.direction == VolumeDirection.UP && after.level.current > level.current -> VolumeOutcome.RAISED
            request.direction == VolumeDirection.DOWN && after.level.current < level.current -> VolumeOutcome.LOWERED
            else -> VolumeOutcome.UNCHANGED
        }
        // Do not hammer a platform restriction, limit, or ignored request while the thumb remains held.
        if (outcome == VolumeOutcome.BLOCKED || outcome == VolumeOutcome.UNCHANGED ||
            after.level?.atLimit(request.direction) == true
        ) cancelHold()
        return VolumeFeedback(after, outcome, request.repeated)
    }
}
