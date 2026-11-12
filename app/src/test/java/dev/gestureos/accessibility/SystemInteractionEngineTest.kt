package dev.gestureos.accessibility

import dev.gestureos.cursor.CursorConfig
import dev.gestureos.cursor.CursorPoint
import dev.gestureos.cursor.CursorPose
import dev.gestureos.cursor.CursorState
import dev.gestureos.interaction.ControlRequirements
import dev.gestureos.interaction.SystemAction
import dev.gestureos.motion.MotionConfig
import dev.gestureos.motion.MotionPose
import dev.gestureos.motion.MotionState
import dev.gestureos.recognition.Point3
import org.junit.Assert.*
import org.junit.Test

class SystemInteractionEngineTest {
    private val ready = ControlRequirements(true, true, true, true)
    private val alignment = FrameAlignment(1, 0, 1)
    private val aim = CursorPoint(0.3f, 0.4f)
    private fun engine() = SystemInteractionEngine(CursorConfig(), MotionConfig(smoothingMs = 0f)).apply {
        assertTrue(enable(alignment, 1, 0, ready))
    }
    private fun pose(ratio: Float = 0.7f) = CursorPose(true, ratio >= 0.45f, true, ratio, Point3(0.5f, 0.5f), 0.2f)
    private fun SystemInteractionEngine.frame(at: Long, kind: MotionPose = MotionPose.NONE, x: Float = 0.5f,
        ratio: Float = 0.7f, busy: Boolean = false) = update(alignment, pose(ratio), aim, kind,
            CursorPoint(x, 0.5f), at, at, busy)
    private fun scrollReady() = engine().apply {
        for (time in 0L..300L step 100) frame(time, MotionPose.SCROLL)
    }
    private fun pinch(engine: SystemInteractionEngine): SystemActionRequest {
        for (time in 0L..200L step 100) engine.frame(time)
        engine.frame(300, ratio = 0.2f)
        return checkNotNull(engine.frame(600, ratio = 0.2f).request)
    }

    @Test fun disabledAndMissingPrerequisitesCannotEmitAnyAction() {
        val engine = SystemInteractionEngine(CursorConfig(), MotionConfig())
        assertNull(engine.frame(300, MotionPose.SCROLL, 0.8f).request)
        for (requirements in listOf(ready.copy(tracking = false), ready.copy(accessibility = false),
            ready.copy(cameraPermission = false), ready.copy(unlocked = false))) {
            assertFalse(engine.enable(alignment, 1, 0, requirements))
            assertNull(engine.frame(600, MotionPose.NAVIGATION, 0.1f).request)
        }
    }

    @Test fun existingPinchRemainsExactlyOnceAndFrozen() {
        val engine = engine()
        val tap = pinch(engine)
        assertEquals(SystemAction.TAP, tap.action)
        assertEquals(aim, tap.position)
        engine.complete(tap.id, ActionOutcome.COMPLETED)
        for (time in 700L..5700L step 100) assertNull(engine.frame(time, ratio = 0.2f).request)
    }

    @Test fun scrollPoseCancelsArmedPinchWithoutClicking() {
        val engine = engine()
        for (time in 0L..200L step 100) engine.frame(time)
        engine.frame(300, MotionPose.SCROLL)
        for (time in 400L..1000L step 100) assertNull(engine.frame(time, ratio = 0.2f).request)
    }

    @Test fun scrollAndNavigationCannotArmACursorClick() {
        for (kind in listOf(MotionPose.SCROLL, MotionPose.NAVIGATION)) {
            val engine = engine()
            for (time in 0L..300L step 100) assertFalse(engine.frame(time, kind).cursor.armed)
            for (time in 400L..1000L step 100) assertNull(engine.frame(time, ratio = 0.2f).request)
        }
    }

    @Test fun scrollReservesExactlyOneActionAndNeverQueuesBehindIt() {
        val engine = scrollReady()
        val request = engine.frame(400, MotionPose.SCROLL, 0.65f).request!!
        assertEquals(SystemAction.SWIPE_RIGHT, request.action)
        for (time in 500L..1000L step 100) assertNull(engine.frame(time, MotionPose.SCROLL, 0.85f, busy = true).request)
        assertTrue(engine.complete(request.id, ActionOutcome.COMPLETED))
        assertNull(engine.frame(1100, MotionPose.SCROLL, 0.85f).request)
        assertNotNull(engine.frame(1200, MotionPose.SCROLL, 0.98f).request)
    }

    @Test fun outstandingSwipeBlocksPinchAndLateCallbackIsIgnoredAfterPoseChange() {
        val engine = scrollReady()
        val request = engine.frame(400, MotionPose.SCROLL, 0.65f).request!!
        assertNull(engine.frame(500, busy = true).request)
        assertEquals(ActionOutcome.INVALIDATED, engine.outcome)
        for (time in 600L..1000L step 100) assertNull(engine.frame(time, ratio = 0.2f, busy = true).request)
        assertFalse(engine.complete(request.id, ActionOutcome.COMPLETED))
        assertNull(engine.frame(1100, ratio = 0.2f).request)
    }

    @Test fun acceptedTapCannotBeFollowedByASwipeWhileTransportIsBusy() {
        val engine = engine()
        val tap = pinch(engine)
        for (time in 700L..1500L step 100) assertNull(engine.frame(time, MotionPose.SCROLL, 0.8f, busy = true).request)
        assertFalse(engine.complete(tap.id, ActionOutcome.COMPLETED))
        assertEquals(ActionOutcome.INVALIDATED, engine.outcome)
    }

    @Test fun regionExitStopsScrollAndRequiresFreshPoseConfirmation() {
        val engine = scrollReady()
        val request = engine.frame(400, MotionPose.SCROLL, 0.65f).request!!
        val result = engine.update(alignment, pose(), aim, MotionPose.SCROLL, null, 500, 500, true)
        assertEquals(CursorState.TRACKING_LOST, result.cursor.state)
        assertFalse(engine.complete(request.id, ActionOutcome.COMPLETED))
        assertEquals(MotionState.CONFIRMING_SCROLL, engine.frame(600, MotionPose.SCROLL, 0.8f).motion.state)
        for (time in 700L..900L step 100) assertNull(engine.frame(time, MotionPose.SCROLL, 0.8f).request)
        assertNotNull(engine.frame(1000, MotionPose.SCROLL, 0.95f).request)
    }

    @Test fun staleDuplicateOutOfOrderFutureAndPreactivationFramesRejectMotion() {
        for ((frameAt, now) in listOf(400L to 751L, 300L to 300L, 100L to 400L, 500L to 499L, -1L to 400L)) {
            val engine = scrollReady()
            val update = engine.update(alignment, pose(), aim, MotionPose.SCROLL, CursorPoint(0.8f, 0.5f), frameAt, now, false)
            assertNull(update.request)
            assertEquals(CursorState.TRACKING_LOST, update.cursor.state)
        }
    }

    @Test fun handLossGapAndWatchdogInvalidateActionWithoutReplay() {
        val engine = scrollReady()
        val request = engine.frame(400, MotionPose.SCROLL, 0.65f).request!!
        assertEquals(CursorState.TRACKING_LOST, engine.expire(751).cursor.state)
        assertFalse(engine.complete(request.id, ActionOutcome.COMPLETED))
        assertNull(engine.frame(800, MotionPose.SCROLL, 0.9f).request)
        assertNull(engine.update(alignment, CursorPose(), null, MotionPose.NONE, null, 900, 900, false).request)
        assertEquals(CursorState.TRACKING_LOST, engine.frame(1400, MotionPose.SCROLL, 0.1f).cursor.state)
    }

    @Test fun orientationAndSessionChangesDisableAllActions() {
        for (changed in listOf(alignment.copy(session = 2), alignment.copy(orientation = 1), alignment.copy(displayRotation = 0))) {
            val engine = scrollReady()
            assertNull(engine.update(changed, pose(), aim, MotionPose.SCROLL, CursorPoint(0.8f, 0.5f), 400, 400, false).request)
            assertFalse(engine.enabled)
        }
    }

    @Test fun disconnectRequiresExplicitEnableAndRejectsOldCompletion() {
        val engine = scrollReady()
        val request = engine.frame(400, MotionPose.SCROLL, 0.65f).request!!
        engine.disable()
        assertNull(engine.frame(500, MotionPose.SCROLL, 0.9f).request)
        assertFalse(engine.complete(request.id, ActionOutcome.COMPLETED))
        assertTrue(engine.enable(alignment, 1, 600, ready))
        assertNull(engine.frame(700, MotionPose.SCROLL, 0.9f).request)
        assertFalse(engine.complete(request.id, ActionOutcome.COMPLETED))
    }

    @Test fun cancelledOrRejectedSwipeDisarmsWithNoRetries() {
        for (outcome in listOf(ActionOutcome.CANCELLED, ActionOutcome.FAILED)) {
            val engine = scrollReady()
            val request = engine.frame(400, MotionPose.SCROLL, 0.65f).request!!
            assertTrue(engine.complete(request.id, outcome))
            assertFalse(engine.enabled)
            assertEquals(outcome, engine.outcome)
            assertNull(engine.frame(500, MotionPose.SCROLL, 0.9f).request)
        }
    }

    @Test fun unavailableNavigationReportsOutcomeAndRequiresNeutralBeforeRetry() {
        val engine = engine()
        for (time in 0L..300L step 100) engine.frame(time, MotionPose.NAVIGATION)
        engine.frame(400, MotionPose.NAVIGATION, 0.44f)
        engine.frame(500, MotionPose.NAVIGATION, 0.38f)
        val request = engine.frame(600, MotionPose.NAVIGATION, 0.28f).request!!
        assertEquals(SystemAction.BACK, request.action)
        assertTrue(engine.complete(request.id, ActionOutcome.UNAVAILABLE))
        assertTrue(engine.enabled)
        assertEquals(ActionOutcome.UNAVAILABLE, engine.outcome)
        for (time in 700L..2700L step 100) assertNull(engine.frame(time, MotionPose.NAVIGATION, 0.1f).request)
    }

    @Test fun invalidPalmCoordinatesAndSuddenHandReplacementCannotScroll() {
        for (point in listOf(CursorPoint(Float.NaN, 0.5f), CursorPoint(1.2f, 0.5f))) {
            val engine = scrollReady()
            assertNull(engine.update(alignment, pose(), aim, MotionPose.SCROLL, point, 400, 400, false).request)
            assertEquals(CursorState.TRACKING_LOST, engine.latest.cursor.state)
        }
        val engine = scrollReady()
        assertNull(engine.update(alignment, pose().copy(wrist = Point3(0.95f, 0.5f)), aim,
            MotionPose.SCROLL, CursorPoint(0.9f, 0.5f), 400, 400, false).request)
    }

    @Test fun switchingThroughMotionPreservesConfiguredPinchCooldown() {
        val engine = SystemInteractionEngine(CursorConfig(clickCooldownMs = 1500), MotionConfig())
        engine.enable(alignment, 1, 0, ready)
        val tap = pinch(engine)
        engine.complete(tap.id, ActionOutcome.COMPLETED)
        engine.frame(700, MotionPose.SCROLL)
        for (time in 800L..1000L step 100) engine.frame(time)
        engine.frame(1100, ratio = 0.2f)
        for (time in 1200L..2000L step 100) assertNull(engine.frame(time, ratio = 0.2f).request)
        assertNotNull(engine.frame(2100, ratio = 0.2f).request)
    }

    @Test fun trackingLossBeforeNavigationMovementRequiresFreshPoseConfirmation() {
        val engine = engine()
        for (time in 0L..300L step 100) engine.frame(time, MotionPose.NAVIGATION)
        engine.update(alignment, CursorPose(), null, MotionPose.NONE, null, 400, 400, false)
        assertEquals(MotionState.CONFIRMING_NAVIGATION, engine.frame(500, MotionPose.NAVIGATION).motion.state)
        for (time in 600L..800L step 100) assertNull(engine.frame(time, MotionPose.NAVIGATION).request)
        engine.frame(900, MotionPose.NAVIGATION, 0.44f)
        engine.frame(1000, MotionPose.NAVIGATION, 0.38f)
        assertEquals(SystemAction.BACK, engine.frame(1100, MotionPose.NAVIGATION, 0.28f).request!!.action)
    }

    @Test fun trackingLossAfterNavigationMovementStartsStillRequiresNeutral() {
        val engine = engine()
        for (time in 0L..300L step 100) engine.frame(time, MotionPose.NAVIGATION)
        engine.frame(400, MotionPose.NAVIGATION, 0.44f)
        engine.update(alignment, CursorPose(), null, MotionPose.NONE, null, 500, 500, false)
        assertEquals(MotionState.WAITING_NEUTRAL, engine.frame(600, MotionPose.NAVIGATION, 0.28f).motion.state)
    }
}
