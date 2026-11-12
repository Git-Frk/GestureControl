package dev.gestureos.accessibility

import dev.gestureos.cursor.CursorConfig
import dev.gestureos.cursor.CursorPoint
import dev.gestureos.cursor.CursorPose
import dev.gestureos.cursor.CursorState
import dev.gestureos.interaction.ControlRequirements
import dev.gestureos.recognition.Point3
import org.junit.Assert.*
import org.junit.Test

class SystemCursorEngineTest {
    private val ready = ControlRequirements(true, true, true, true)
    private val alignment = FrameAlignment(1, 0, 1)
    private val position = CursorPoint(0.3f, 0.4f)
    private fun pose(ratio: Float) = CursorPose(true, ratio >= 0.45f, true, ratio, Point3(0.5f, 0.5f), 0.2f)
    private fun SystemCursorEngine.frame(time: Long, ratio: Float = 0.7f, point: CursorPoint = position) =
        update(alignment, pose(ratio), point, time, time)
    private fun armed() = SystemCursorEngine(CursorConfig()).apply {
        assertTrue(enable(alignment, 1, 0, ready))
        frame(0)
        frame(100)
        assertTrue(frame(200).cursor.armed)
    }
    private fun click(engine: SystemCursorEngine): TapRequest {
        engine.frame(300, 0.2f, CursorPoint(0.9f, 0.9f))
        engine.frame(400, 0.2f)
        return checkNotNull(engine.frame(600, 0.2f).tap)
    }

    @Test fun defaultDisabledNeverEmitsTaps() {
        val engine = SystemCursorEngine(CursorConfig())
        for (time in 0L..1000L step 100) assertNull(engine.frame(time, 0.2f).tap)
    }

    @Test fun allPrerequisitesAndAlignedDisplayAreRequired() {
        for (requirements in listOf(ready.copy(tracking = false), ready.copy(accessibility = false),
            ready.copy(cameraPermission = false), ready.copy(unlocked = false))) {
            val engine = SystemCursorEngine(CursorConfig())
            assertFalse(engine.enable(alignment, 1, 0, requirements))
            assertNull(engine.frame(600, 0.2f).tap)
        }
        assertFalse(SystemCursorEngine(CursorConfig()).enable(alignment, 0, 0, ready))
    }

    @Test fun confirmedPinchCreatesOneFrozenTap() {
        val engine = armed()
        val tap = click(engine)
        assertEquals(position, tap.position)
        assertEquals(TapOutcome.DISPATCHING, engine.outcome)
        assertTrue(engine.complete(tap.id, TapOutcome.SUCCEEDED))
        for (time in 700L..5700L step 100) assertNull(engine.frame(time, 0.2f).tap)
        assertEquals(TapOutcome.SUCCEEDED, engine.outcome)
    }

    @Test fun releaseAndFreshPointingPermitASecondTap() {
        val engine = armed()
        val first = click(engine)
        engine.complete(first.id, TapOutcome.SUCCEEDED)
        engine.frame(700)
        engine.frame(850)
        engine.frame(1050)
        engine.frame(1150, 0.2f)
        val second = engine.frame(1450, 0.2f).tap!!
        assertTrue(second.id > first.id)
    }

    @Test fun noTapIsQueuedBehindAnOutstandingDispatch() {
        val engine = armed()
        val first = click(engine)
        engine.frame(700)
        engine.frame(850)
        engine.frame(1050)
        engine.frame(1150, 0.2f)
        assertNull(engine.frame(1450, 0.2f).tap)
        engine.complete(first.id, TapOutcome.SUCCEEDED)
        assertNull(engine.frame(1550, 0.2f).tap)
    }

    @Test fun cancellationAndRejectionDisableWithoutRetry() {
        for (outcome in listOf(TapOutcome.CANCELLED, TapOutcome.FAILED)) {
            val engine = armed()
            val tap = click(engine)
            assertTrue(engine.complete(tap.id, outcome))
            assertFalse(engine.enabled)
            assertEquals(outcome, engine.outcome)
            assertNull(engine.frame(700, 0.2f).tap)
        }
    }

    @Test fun disconnectionInvalidatesCallbacksAndReconnectionNeedsExplicitEnable() {
        val engine = armed()
        val tap = click(engine)
        engine.disable()
        assertFalse(engine.complete(tap.id, TapOutcome.SUCCEEDED))
        assertEquals(TapOutcome.INVALIDATED, engine.outcome)
        assertNull(engine.frame(700, 0.2f).tap)
        assertTrue(engine.enable(alignment, 1, 800, ready))
        assertNull(engine.frame(900, 0.2f).tap)
        assertNull(engine.frame(1200, 0.2f).tap)
    }

    @Test fun sessionAndOrientationChangesDisarmImmediately() {
        for (changed in listOf(alignment.copy(session = 2), alignment.copy(orientation = 1), alignment.copy(displayRotation = 0))) {
            val engine = armed()
            engine.frame(300, 0.2f)
            assertNull(engine.update(changed, pose(0.2f), position, 600, 600).tap)
            assertFalse(engine.enabled)
        }
    }

    @Test fun staleFutureDuplicateAndOutOfOrderFramesCannotCompletePinch() {
        for ((frameAt, now) in listOf(300L to 700L, 400L to 399L, 200L to 200L, 100L to 200L)) {
            val engine = armed()
            val update = engine.update(alignment, pose(0.2f), position, frameAt, now)
            assertNull(update.tap)
            assertEquals(CursorState.TRACKING_LOST, update.cursor.state)
        }
    }

    @Test fun observationsBeforeActivationCannotArm() {
        val engine = SystemCursorEngine(CursorConfig())
        engine.enable(alignment, 1, 500, ready)
        assertFalse(engine.frame(300).cursor.armed)
        assertNull(engine.frame(600, 0.2f).tap)
        assertNull(engine.frame(900, 0.2f).tap)
    }

    @Test fun missingHandAndWatchdogCancelPendingConfirmation() {
        val engine = armed()
        engine.frame(300, 0.2f)
        assertEquals(CursorState.TRACKING_LOST, engine.expire(651).state)
        assertNull(engine.frame(700, 0.2f).tap)
        assertNull(engine.update(alignment, CursorPose(), null, 800, 800).tap)
    }

    @Test fun lateOrDuplicateDispatchCallbacksCannotAffectANewAttempt() {
        val engine = armed()
        val first = click(engine)
        assertTrue(engine.complete(first.id, TapOutcome.SUCCEEDED))
        assertFalse(engine.complete(first.id, TapOutcome.CANCELLED))
        engine.disable()
        engine.enable(alignment, 1, 700, ready)
        assertFalse(engine.complete(first.id, TapOutcome.SUCCEEDED))
    }

    @Test fun briefContactAndReleaseHysteresisArePreserved() {
        val engine = armed()
        engine.frame(300, 0.2f)
        engine.frame(350, 0.6f)
        assertNull(engine.frame(550, 0.2f).tap)
        assertNull(engine.frame(850, 0.2f).tap)
    }
}
