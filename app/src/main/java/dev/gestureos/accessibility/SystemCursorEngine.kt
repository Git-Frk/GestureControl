package dev.gestureos.accessibility

import dev.gestureos.cursor.CursorConfig
import dev.gestureos.cursor.CursorPoint
import dev.gestureos.cursor.CursorPose
import dev.gestureos.cursor.CursorResult
import dev.gestureos.cursor.CursorState
import dev.gestureos.cursor.CursorStateMachine
import dev.gestureos.interaction.ControlRequirements
import dev.gestureos.interaction.InteractionMode

enum class TapOutcome { NONE, DISPATCHING, SUCCEEDED, CANCELLED, FAILED, INVALIDATED }
data class TapRequest(val id: Long, val position: CursorPoint, val createdAtMs: Long)
data class SystemCursorUpdate(val cursor: CursorResult = CursorResult(), val tap: TapRequest? = null)

/** Reuses the validated pinch FSM and adds a single in-flight dispatch reservation. No tap queue. */
class SystemCursorEngine(private val config: CursorConfig) {
    private val machine = CursorStateMachine(config)
    private var alignment: FrameAlignment? = null
    private var enabledAt = 0L
    private var sequence = 0L
    var enabled = false
        private set
    var pending: TapRequest? = null
        private set
    var outcome = TapOutcome.NONE
        private set

    fun enable(frame: FrameAlignment, displayRotation: Int, nowMs: Long, requirements: ControlRequirements): Boolean {
        disable()
        if (!requirements.permits(InteractionMode.SYSTEM_CURSOR) || frame.displayRotation != displayRotation) return false
        alignment = frame
        enabledAt = nowMs
        outcome = TapOutcome.NONE
        enabled = true
        return true
    }

    fun disable() {
        enabled = false
        alignment = null
        invalidate()
        machine.reset()
    }

    fun suspendInteraction() {
        invalidate()
        machine.suspendInteraction()
    }

    private fun invalidate() {
        if (pending != null) outcome = TapOutcome.INVALIDATED
        pending = null
    }

    fun update(frame: FrameAlignment, pose: CursorPose, mapped: CursorPoint?, frameAtMs: Long, nowMs: Long): SystemCursorUpdate {
        if (!enabled) return SystemCursorUpdate()
        if (alignment != frame) {
            disable()
            return SystemCursorUpdate()
        }
        if (frameAtMs < enabledAt) {
            invalidate()
            machine.reset()
            return SystemCursorUpdate(CursorResult(CursorState.TRACKING_LOST))
        }
        val result = machine.update(pose, mapped, frameAtMs, nowMs)
        if (result.state == CursorState.TRACKING_LOST) invalidate()
        val request = if (result.clicked && result.position != null && pending == null) {
            TapRequest(++sequence, result.position, nowMs).also {
                pending = it
                outcome = TapOutcome.DISPATCHING
            }
        } else null
        return SystemCursorUpdate(result, request)
    }

    fun expire(nowMs: Long): CursorResult {
        val result = machine.expire(nowMs)
        if (result.state == CursorState.TRACKING_LOST) invalidate()
        return result
    }

    fun complete(id: Long, result: TapOutcome): Boolean {
        require(result == TapOutcome.SUCCEEDED || result == TapOutcome.CANCELLED || result == TapOutcome.FAILED)
        if (!enabled || pending?.id != id) return false
        pending = null
        outcome = result
        if (result != TapOutcome.SUCCEEDED) disable()
        return true
    }
}
