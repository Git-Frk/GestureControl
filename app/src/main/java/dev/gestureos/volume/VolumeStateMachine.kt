package dev.gestureos.volume

import dev.gestureos.recognition.Gesture
import dev.gestureos.recognition.Recognition
import dev.gestureos.recognition.RecognitionState

enum class VolumeDirection { UP, DOWN }
data class VolumeRequest(val direction: VolumeDirection, val repeated: Boolean)

/** Frame-driven only: no timers can adjust audio after tracking stops or the app pauses. */
class VolumeStateMachine(private val config: VolumeControlConfig = VolumeControlConfig()) {
    private var lastFrameAt: Long? = null
    private var lastNow: Long? = null
    private var activeDirection: VolumeDirection? = null
    private var actedInHold = false
    private var nextRepeatAt = 0L
    private var lastActionAt: Long? = null
    private var lastDirection: VolumeDirection? = null

    /** Cancelling a hold retains rate/direction limits. A fresh confirmation is required to re-arm. */
    fun cancelHold() {
        activeDirection = null
        actedInHold = false
    }

    fun update(recognition: Recognition, rawGesture: Gesture, frameAtMs: Long, nowMs: Long): VolumeRequest? {
        if (nowMs < 0 || frameAtMs < 0 || nowMs < (lastNow ?: 0) || frameAtMs > nowMs) {
            cancelHold()
            return null
        }
        lastNow = nowMs
        // Ignore duplicate/out-of-order deliveries; they never advance a hold or repeat deadline.
        if (lastFrameAt?.let { frameAtMs <= it } == true) return null
        val gap = lastFrameAt?.let { frameAtMs - it > config.maxObservationGapMs } ?: false
        lastFrameAt = frameAtMs
        if (nowMs - frameAtMs > config.maxFrameAgeMs) {
            cancelHold()
            return null
        }
        if (gap) cancelHold()
        val direction = when (recognition.gesture) {
            Gesture.THUMBS_UP -> VolumeDirection.UP
            Gesture.THUMBS_DOWN -> VolumeDirection.DOWN
            else -> null
        }
        // Raw agreement prevents a smoothed, previously confirmed thumb from causing another step.
        if (direction == null || recognition.state != RecognitionState.CONFIRMED || rawGesture != recognition.gesture) {
            cancelHold()
            return null
        }
        if (activeDirection == null || activeDirection != direction) {
            cancelHold()
            if (!recognition.newlyConfirmed) return null
            activeDirection = direction
        }
        val sinceAction = lastActionAt?.let { nowMs - it }
        if (sinceAction != null && sinceAction < config.minimumActionIntervalMs) return null
        if (lastDirection != null && lastDirection != direction && sinceAction != null &&
            sinceAction < config.reversalCooldownMs
        ) return null
        if (actedInHold && (!config.repeatEnabled || nowMs < nextRepeatAt)) return null

        val request = VolumeRequest(direction, repeated = actedInHold)
        nextRepeatAt = nowMs + if (actedInHold) config.repeatIntervalMs else config.repeatDelayMs
        actedInHold = true
        lastActionAt = nowMs
        lastDirection = direction
        return request
    }
}
