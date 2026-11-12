package dev.gestureos.accessibility

import dev.gestureos.cursor.CursorConfig
import dev.gestureos.cursor.CursorPoint
import dev.gestureos.cursor.CursorPose
import dev.gestureos.cursor.CursorResult
import dev.gestureos.cursor.CursorState
import dev.gestureos.interaction.ControlRequirements
import dev.gestureos.interaction.SystemAction
import dev.gestureos.motion.MotionConfig
import dev.gestureos.motion.MotionPose
import dev.gestureos.motion.MotionResult
import dev.gestureos.motion.MotionStateMachine
import dev.gestureos.recognition.Point3

enum class ActionOutcome { NONE, DISPATCHING, COMPLETED, CANCELLED, FAILED, UNAVAILABLE, INVALIDATED }
data class SystemActionRequest(val id: Long, val action: SystemAction, val position: CursorPoint, val createdAtMs: Long)
data class SystemInteractionUpdate(
    val cursor: CursorResult = CursorResult(),
    val motion: MotionResult = MotionResult(),
    val request: SystemActionRequest? = null,
    val waitingForDispatch: Boolean = false,
)

/** Coordinates existing pinch logic with motion gestures. No timer or callback can emit an action. */
class SystemInteractionEngine(private val config: CursorConfig, motionConfig: MotionConfig) {
    private val cursor = SystemCursorEngine(config)
    private val motion = MotionStateMachine(motionConfig, config)
    private var alignment: FrameAlignment? = null
    private var enabledAt = 0L
    private var lastFrameAt: Long? = null
    private var sequence = 0L
    private var tapId: Long? = null
    private var aim = CursorPoint(0.5f, 0.5f)
    private var previousPose = MotionPose.NONE
    private var previousWrist: Point3? = null
    var latest = SystemInteractionUpdate()
        private set
    var pending: SystemActionRequest? = null
        private set
    var outcome = ActionOutcome.NONE
        private set
    var lastAction: SystemAction? = null
        private set
    val enabled get() = cursor.enabled
    val tapOutcome get() = cursor.outcome

    fun enable(frame: FrameAlignment, rotation: Int, nowMs: Long, requirements: ControlRequirements): Boolean {
        disable()
        alignment = frame
        enabledAt = nowMs
        lastFrameAt = null
        outcome = ActionOutcome.NONE
        lastAction = null
        return cursor.enable(frame, rotation, nowMs, requirements)
    }

    fun disable() {
        invalidate()
        cursor.disable()
        alignment = null
    }

    private fun invalidate(lost: Boolean = false) {
        if (pending != null) outcome = ActionOutcome.INVALIDATED
        pending = null
        tapId = null
        cursor.suspendInteraction()
        motion.reset()
        previousPose = MotionPose.NONE
        previousWrist = null
        latest = SystemInteractionUpdate(cursor = CursorResult(if (lost) CursorState.TRACKING_LOST else CursorState.IDLE))
    }

    fun expire(nowMs: Long): SystemInteractionUpdate {
        if (lastFrameAt?.let { nowMs - it > config.maxFrameAgeMs } == true) invalidate(lost = true)
        return latest.copy(request = null)
    }

    fun update(
        frame: FrameAlignment, pose: CursorPose, mapped: CursorPoint?, motionPose: MotionPose,
        motionPoint: CursorPoint?, frameAtMs: Long, nowMs: Long, transportBusy: Boolean,
    ): SystemInteractionUpdate {
        if (!enabled) return SystemInteractionUpdate()
        if (alignment != frame) {
            disable()
            return latest
        }
        val previousAt = lastFrameAt
        if (frameAtMs < enabledAt || nowMs < frameAtMs || nowMs - frameAtMs > config.maxFrameAgeMs ||
            previousAt?.let { frameAtMs <= it } == true) {
            invalidate(lost = true)
            return latest
        }
        lastFrameAt = frameAtMs
        val gap = previousAt?.let { frameAtMs - it > config.maxFrameAgeMs } == true
        val jumped = previousWrist?.let { old -> pose.wrist?.distance(old)?.let { it > pose.palmWidth * 1.5f } } == true
        if (!pose.tracked || pose.pinchRatio?.isFinite() != true || mapped?.finite() != true || gap || jumped) {
            invalidate(lost = true)
            return latest
        }
        previousWrist = pose.wrist
        if (transportBusy && pending == null) {
            invalidate()
            latest = latest.copy(waitingForDispatch = true)
            return latest
        }
        if (motionPose == MotionPose.SCROLL || motionPose == MotionPose.NAVIGATION) {
            if (motionPoint?.finite() != true || motionPoint.x !in 0f..1f || motionPoint.y !in 0f..1f) {
                invalidate(lost = true)
                return latest
            }
            if (pending?.action == SystemAction.TAP || (pending != null && previousPose != motionPose)) {
                invalidate()
                latest = latest.copy(waitingForDispatch = transportBusy)
                return latest
            }
            cursor.suspendInteraction()
            previousPose = motionPose
            val movement = motion.update(motionPose, motionPoint, frameAtMs, !transportBusy && pending == null)
            val request = movement.action?.let { reserve(it, aim, nowMs) }
            latest = SystemInteractionUpdate(motion = movement, request = request)
            return latest
        }
        if (pending?.action?.isSwipe == true) {
            invalidate()
            latest = latest.copy(waitingForDispatch = transportBusy)
            return latest
        }
        previousPose = motionPose
        val movement = motion.update(motionPose, motionPoint ?: aim, frameAtMs, !transportBusy)
        val update = cursor.update(frame, pose, mapped, frameAtMs, nowMs)
        if (update.cursor.state == CursorState.TRACKING_LOST) {
            invalidate(lost = true)
            return latest
        }
        if (update.cursor.state == CursorState.POINTING) aim = update.cursor.position ?: aim
        val request = update.tap?.takeIf { pending == null && !transportBusy }?.let {
            tapId = it.id
            reserve(SystemAction.TAP, it.position, nowMs)
        }
        latest = SystemInteractionUpdate(update.cursor, movement, request)
        return latest
    }

    private fun reserve(action: SystemAction, position: CursorPoint, atMs: Long): SystemActionRequest {
        check(pending == null)
        lastAction = action
        outcome = ActionOutcome.DISPATCHING
        return SystemActionRequest(++sequence, action, position, atMs).also { pending = it }
    }

    fun complete(id: Long, result: ActionOutcome): Boolean {
        require(result in listOf(ActionOutcome.COMPLETED, ActionOutcome.CANCELLED, ActionOutcome.FAILED, ActionOutcome.UNAVAILABLE))
        if (!enabled || pending?.id != id) return false
        tapId?.let { cursor.complete(it, if (result == ActionOutcome.COMPLETED) TapOutcome.SUCCEEDED else TapOutcome.FAILED) }
        tapId = null
        pending = null
        outcome = result
        if (result != ActionOutcome.COMPLETED && result != ActionOutcome.UNAVAILABLE) disable()
        return true
    }
}
