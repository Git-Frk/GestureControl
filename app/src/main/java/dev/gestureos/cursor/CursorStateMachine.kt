package dev.gestureos.cursor

import dev.gestureos.recognition.Point3

enum class CursorState { IDLE, POINTING, PINCHING, CLICK_CONFIRMING, CLICKED, TRACKING_LOST }
data class CursorResult(
    val state: CursorState = CursorState.IDLE,
    val position: CursorPoint? = null,
    val pinchRatio: Float? = null,
    val progress: Float = 0f,
    val armed: Boolean = false,
    val clicked: Boolean = false,
)

/** Fresh frames alone can emit a click. Every pinch needs a released, stable pointing pose first. */
class CursorStateMachine(private val config: CursorConfig = CursorConfig()) {
    private val filter = CursorFilter(config)
    private var result = CursorResult()
    private var lastFrameAt: Long? = null
    private var pointingSince: Long? = null
    private var pinchSince: Long? = null
    private var approachSince: Long? = null
    private var releasedSince: Long? = null
    private var lastClickAt: Long? = null
    private var armed = false
    private var latched = false
    private var clickFired = false
    private var wrist: Point3? = null

    fun reset(): CursorResult {
        lastFrameAt = null
        lastClickAt = null
        return cancel(CursorState.IDLE)
    }

    /** Switch interaction ownership without shortening the configured click cooldown. */
    fun suspendInteraction(): CursorResult = cancel(CursorState.IDLE)

    private fun cancel(state: CursorState, ratio: Float? = null): CursorResult {
        filter.reset()
        pointingSince = null
        pinchSince = null
        approachSince = null
        releasedSince = null
        armed = false
        latched = false
        clickFired = false
        wrist = null
        result = CursorResult(state, pinchRatio = ratio)
        return result
    }

    /** Watchdog only hides/cancels; it never advances a confirmation or generates a click. */
    fun expire(nowMs: Long): CursorResult {
        if (lastFrameAt?.let { nowMs - it > config.maxFrameAgeMs } == true) cancel(CursorState.TRACKING_LOST)
        return result.copy(clicked = false)
    }

    fun update(pose: CursorPose, mapped: CursorPoint?, frameAtMs: Long, nowMs: Long): CursorResult {
        val previousAt = lastFrameAt
        if (frameAtMs < 0 || nowMs < frameAtMs || nowMs - frameAtMs > config.maxFrameAgeMs ||
            previousAt?.let { frameAtMs <= it } == true
        ) return cancel(CursorState.TRACKING_LOST)
        lastFrameAt = frameAtMs
        val gap = previousAt?.let { frameAtMs - it > config.maxFrameAgeMs } == true
        val jumped = wrist?.let { old -> pose.wrist?.distance(old)?.let { it > pose.palmWidth * 1.5f } } == true
        if (gap || jumped) cancel(CursorState.TRACKING_LOST)
        if (!pose.tracked || mapped == null || !mapped.finite() || pose.pinchRatio?.isFinite() != true) {
            return cancel(CursorState.TRACKING_LOST)
        }
        wrist = pose.wrist
        val ratio = pose.pinchRatio
        if (!pose.pinchEligible) return cancel(CursorState.IDLE, ratio)
        // A full release must persist. Threshold-band noise cannot release a latched pinch.
        if (latched || pinchSince != null) {
            if (ratio >= config.pinchRelease) {
                // Crossing release aborts an unfinished click immediately. A brief reclose
                // cannot finish the old timer; sustained release and pointing must re-arm.
                if (!latched) {
                    latched = true
                    armed = false
                    pinchSince = null
                }
                releasedSince = releasedSince ?: frameAtMs
                if (frameAtMs - checkNotNull(releasedSince) >= config.releaseMs) {
                    armed = false
                    latched = false
                    clickFired = false
                    pinchSince = null
                    pointingSince = null
                    releasedSince = null
                    filter.reset()
                } else return publish(if (clickFired) CursorState.CLICKED else CursorState.PINCHING, ratio)
            } else {
                releasedSince = null
                if (latched) return publish(if (clickFired) CursorState.CLICKED else CursorState.PINCHING, ratio)
            }
        }
        if (pinchSince != null) {
            val elapsed = frameAtMs - checkNotNull(pinchSince)
            val total = config.pinchDebounceMs + config.pinchConfirmMs
            val progress = (elapsed.toFloat() / total).coerceIn(0f, 1f)
            if (elapsed >= total && lastClickAt?.let { frameAtMs - it < config.clickCooldownMs } != true) {
                latched = true
                clickFired = true
                armed = false
                lastClickAt = frameAtMs
                return publish(CursorState.CLICKED, ratio, 1f, clicked = true)
            }
            return publish(if (elapsed < config.pinchDebounceMs) CursorState.PINCHING else CursorState.CLICK_CONFIRMING, ratio, progress)
        }
        if (approachSince?.let { frameAtMs - it > config.approachTimeoutMs } == true) {
            return cancel(CursorState.IDLE, ratio)
        }
        if (armed && ratio <= config.pinchEntry) {
            approachSince = null
            pinchSince = frameAtMs
            return publish(CursorState.PINCHING, ratio)
        }
        // Freeze the last pointing location as the fingers approach, before contact can drag it.
        // A natural pinch bends the index BEFORE the tips reach the release threshold.
        // A fist is excluded by the index geometry. Other fingers may relax after arming.
        // Freeze an armed pointer for a bounded transition while the index bends.
        if (armed && (ratio < config.pinchRelease || !pose.pointing)) {
            approachSince = approachSince ?: frameAtMs
            return publish(CursorState.PINCHING, ratio)
        }
        if (!pose.pointing || ratio < config.pinchRelease) return cancel(CursorState.IDLE, ratio)
        approachSince = null
        pointingSince = pointingSince ?: frameAtMs
        armed = frameAtMs - checkNotNull(pointingSince) >= config.pointArmMs
        result = CursorResult(CursorState.POINTING, filter.update(mapped, frameAtMs), ratio, armed = armed)
        return result
    }

    private fun publish(state: CursorState, ratio: Float, progress: Float = 0f, clicked: Boolean = false): CursorResult {
        result = result.copy(state = state, pinchRatio = ratio, progress = progress, armed = armed, clicked = clicked)
        return result
    }
}
