package dev.gestureos.motion

import dev.gestureos.cursor.CursorConfig
import dev.gestureos.cursor.CursorFilter
import dev.gestureos.cursor.CursorPoint
import dev.gestureos.interaction.SystemAction
import kotlin.math.abs

enum class MotionState { IDLE, CONFIRMING_SCROLL, SCROLL_READY, SCROLLING, CONFIRMING_NAVIGATION, NAVIGATION_READY, WAITING_NEUTRAL }
enum class NavigationRejection { NONE, POSE_CHANGED, TOO_FAST, TIMED_OUT, WRONG_DIRECTION, COOLDOWN }
data class MotionResult(
    val state: MotionState = MotionState.IDLE,
    val action: SystemAction? = null,
    val progress: Float = 0f,
    val rejection: NavigationRejection = NavigationRejection.NONE,
    val travel: CursorPoint? = null,
    val movementMs: Long = 0,
)

/** Fresh observations drive all actions. Repeats require new travel and never catch up after a stall. */
class MotionStateMachine(private val config: MotionConfig, cursorConfig: CursorConfig) {
    private val filter = CursorFilter(cursorConfig.copy(smoothingMs = config.smoothingMs, deadZone = 0f))
    private var pose = MotionPose.NONE
    private var anchor: CursorPoint? = null
    private var poseSince = 0L
    private var confirmed = false
    private var movementSince: Long? = null
    private var lastScrollAt: Long? = null
    private var lastNavigationAt: Long? = null
    private var neutralSince: Long? = null
    private var needsNeutral = false
    private var rejection = NavigationRejection.NONE

    fun reset(requireNeutral: Boolean = false) {
        val interruptedMovement = pose == MotionPose.NAVIGATION && movementSince != null
        if (interruptedMovement && !needsNeutral) rejection = NavigationRejection.POSE_CHANGED
        needsNeutral = needsNeutral || requireNeutral || interruptedMovement
        pose = MotionPose.NONE
        anchor = null
        confirmed = false
        movementSince = null
        neutralSince = null
        filter.reset()
        // Rate limits survive observation gaps and interaction changes within this activation.
    }

    fun update(kind: MotionPose, point: CursorPoint, atMs: Long, canAct: Boolean): MotionResult {
        if (kind == MotionPose.NEUTRAL) {
            if (pose != kind) reset()
            pose = kind
            neutralSince = neutralSince ?: atMs
            if (atMs - checkNotNull(neutralSince) >= config.neutralMs) {
                needsNeutral = false
                rejection = NavigationRejection.NONE
            }
            return if (needsNeutral) waitingForNeutral() else MotionResult()
        }
        neutralSince = null
        if (kind == MotionPose.NONE) {
            reset()
            return if (needsNeutral) waitingForNeutral() else MotionResult()
        }
        if (needsNeutral && kind == MotionPose.NAVIGATION) return waitingForNeutral()
        if (pose != kind) {
            reset()
            if (needsNeutral && kind == MotionPose.NAVIGATION) return waitingForNeutral()
            pose = kind
            poseSince = atMs
        }
        val repeatCooling = confirmed && kind == MotionPose.SCROLL &&
            lastScrollAt?.let { atMs - it < config.repeatIntervalMs } == true
        if (!canAct || repeatCooling) {
            // Discard travel and filter lag while blocked. Neither can become a delayed action.
            filter.reset()
            anchor = point
            movementSince = null
            if (confirmed) return ready(kind)
            poseSince = atMs
            return MotionResult(if (kind == MotionPose.SCROLL) MotionState.CONFIRMING_SCROLL else MotionState.CONFIRMING_NAVIGATION)
        }
        val smoothed = filter.update(point, atMs)
        val origin = anchor ?: smoothed.also { anchor = it }
        val dx = (smoothed.x - origin.x) * config.sensitivity
        val dy = (smoothed.y - origin.y) * config.sensitivity
        val distance = maxOf(abs(dx), abs(dy))
        if (!confirmed) {
            if (distance > config.deadZone) {
                poseSince = atMs
                anchor = smoothed
            }
            val elapsed = atMs - poseSince
            if (elapsed < config.poseConfirmMs) return MotionResult(
                if (kind == MotionPose.SCROLL) MotionState.CONFIRMING_SCROLL else MotionState.CONFIRMING_NAVIGATION,
                progress = elapsed.toFloat() / config.poseConfirmMs)
            confirmed = true
            anchor = smoothed
            return ready(kind)
        }
        return if (kind == MotionPose.SCROLL) scroll(smoothed, dx, dy, atMs)
        else navigate(dx, dy, atMs)
    }

    private fun ready(kind: MotionPose) = MotionResult(
        if (kind == MotionPose.SCROLL) MotionState.SCROLL_READY else MotionState.NAVIGATION_READY)

    private fun scroll(point: CursorPoint, dx: Float, dy: Float, atMs: Long): MotionResult {
        val direction = direction(dx, dy, config.scrollThreshold) ?: return ready(MotionPose.SCROLL)
        anchor = point
        lastScrollAt = atMs
        return MotionResult(MotionState.SCROLLING, direction)
    }

    private fun navigate(dx: Float, dy: Float, atMs: Long): MotionResult {
        val distance = maxOf(abs(dx), abs(dy))
        // Small drift after confirmation must not start a navigation timeout.
        val startDistance = minOf(config.deadZone * 2f, config.navigationDistance / 3f)
        if (movementSince == null && distance <= startDistance) {
            return ready(MotionPose.NAVIGATION)
        }
        val start = movementSince ?: atMs.also { movementSince = it }
        val elapsed = atMs - start
        val direction = direction(dx, dy, config.navigationDistance)
        if (elapsed > config.navigationMaxMs || direction != null) {
            needsNeutral = true
            val action = when (direction) {
                SystemAction.SWIPE_LEFT -> SystemAction.BACK
                SystemAction.SWIPE_UP -> SystemAction.HOME
                else -> null
            }
            val allowed = elapsed in config.navigationMinMs..config.navigationMaxMs &&
                lastNavigationAt?.let { atMs - it < config.navigationCooldownMs } != true
            if (action != null && allowed) {
                lastNavigationAt = atMs
                return MotionResult(MotionState.WAITING_NEUTRAL, action, progress = 1f,
                    travel = CursorPoint(dx, dy), movementMs = elapsed)
            }
            rejection = when {
                elapsed > config.navigationMaxMs -> NavigationRejection.TIMED_OUT
                action == null -> NavigationRejection.WRONG_DIRECTION
                elapsed < config.navigationMinMs -> NavigationRejection.TOO_FAST
                else -> NavigationRejection.COOLDOWN
            }
            return waitingForNeutral().copy(travel = CursorPoint(dx, dy), movementMs = elapsed)
        }
        return ready(MotionPose.NAVIGATION).copy(progress = (distance / config.navigationDistance).coerceIn(0f, 1f),
            travel = CursorPoint(dx, dy), movementMs = elapsed)
    }

    private fun waitingForNeutral() = MotionResult(MotionState.WAITING_NEUTRAL, rejection = rejection)

    private fun direction(dx: Float, dy: Float, threshold: Float): SystemAction? = when {
        abs(dx) >= threshold && abs(dx) >= abs(dy) * 1.5f ->
            if (dx < 0) SystemAction.SWIPE_LEFT else SystemAction.SWIPE_RIGHT
        abs(dy) >= threshold && abs(dy) >= abs(dx) * 1.5f ->
            if (dy < 0) SystemAction.SWIPE_UP else SystemAction.SWIPE_DOWN
        else -> null
    }
}
