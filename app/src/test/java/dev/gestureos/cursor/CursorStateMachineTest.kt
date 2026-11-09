package dev.gestureos.cursor

import dev.gestureos.recognition.Point3
import org.junit.Assert.*
import org.junit.Test

class CursorStateMachineTest {
    private val position = CursorPoint(0.3f, 0.4f)
    private fun pose(ratio: Float = 0.7f, pointing: Boolean = true) = CursorPose(true, pointing, true, ratio, Point3(0.5f, 0.5f), 0.2f)
    private fun CursorStateMachine.frame(time: Long, ratio: Float = 0.7f, point: CursorPoint = position, pointing: Boolean = true) =
        update(pose(ratio, pointing), point, time, time)
    private fun armed(config: CursorConfig = CursorConfig()): CursorStateMachine = CursorStateMachine(config).apply {
        frame(0); frame(100); assertTrue(frame(200).armed)
    }
    @Test fun stableReleasedPointingIsRequiredBeforePinching() {
        val machine = CursorStateMachine()
        assertFalse(machine.frame(0, 0.1f).clicked)
        assertFalse(machine.frame(300, 0.1f).clicked)
        assertFalse(machine.frame(400).armed)
        assertFalse(machine.frame(500).armed)
        assertTrue(machine.frame(600).armed)
    }
    @Test fun pinchHasDebounceAndConfirmationAndFreezesTheLastPoint() {
        val machine = armed()
        val start = machine.frame(300, 0.2f, CursorPoint(0.8f, 0.9f), false)
        assertEquals(CursorState.PINCHING, start.state); assertEquals(position, start.position)
        assertEquals(CursorState.PINCHING, machine.frame(350, 0.2f).state)
        assertEquals(CursorState.CLICK_CONFIRMING, machine.frame(400, 0.2f).state)
        val click = machine.frame(580, 0.2f)
        assertEquals(CursorState.CLICKED, click.state); assertTrue(click.clicked); assertEquals(position, click.position)
    }
    @Test fun approachBandFreezesBeforeEntryAndDoesNotStartClickTimer() {
        val machine = armed()
        assertEquals(position, machine.frame(300, 0.35f, CursorPoint(0.9f, 0.9f), false).position)
        assertFalse(machine.frame(600, 0.35f, pointing = false).clicked)
        assertFalse(machine.frame(700, 0.2f, pointing = false).clicked)
        assertTrue(machine.frame(1000, 0.2f, pointing = false).clicked)
    }
    @Test fun hysteresisToleratesThresholdBandAfterEntry() {
        val machine = armed()
        machine.frame(300, 0.24f)
        machine.frame(400, 0.30f)
        assertTrue(machine.frame(600, 0.40f).clicked)
        assertFalse(machine.frame(700, 0.30f).clicked)
    }
    @Test fun heldPinchCannotRepeatAndFullReleaseRearms() {
        val machine = armed()
        machine.frame(300, 0.2f); assertTrue(machine.frame(600, 0.2f).clicked)
        for (time in 700L..2000L step 100) assertFalse(machine.frame(time, 0.2f).clicked)
        machine.frame(2100); machine.frame(2250)
        assertTrue(machine.frame(2450).armed)
        machine.frame(2550, 0.2f)
        assertTrue(machine.frame(2850, 0.2f).clicked)
    }
    @Test fun briefReleaseAbortsPendingClickAndRecloseCannotFinishIt() {
        val machine = armed()
        machine.frame(300, 0.2f)
        machine.frame(400, 0.6f)
        for (time in 450L..1250L step 100) assertFalse(machine.frame(time, 0.2f).clicked)
    }
    @Test fun shortPinchDoesNotClick() {
        val machine = armed()
        machine.frame(300, 0.2f)
        assertFalse(machine.frame(350, 0.6f).clicked)
        assertFalse(machine.frame(550, 0.6f).clicked)
        assertEquals(CursorState.POINTING, machine.frame(650).state)
    }
    @Test fun trackingLossCancelsPinchAndHidesCursor() {
        val machine = armed()
        machine.frame(300, 0.2f)
        val lost = machine.update(CursorPose(), null, 400, 400)
        assertEquals(CursorState.TRACKING_LOST, lost.state); assertNull(lost.position)
        assertFalse(machine.frame(500, 0.2f).clicked)
        assertFalse(machine.frame(800, 0.2f).clicked)
    }
    @Test fun watchdogCannotConfirmAndRequiresFreshPointingAfterExpiry() {
        val machine = armed()
        machine.frame(300, 0.2f)
        assertFalse(machine.expire(600).clicked)
        val expired = machine.expire(651)
        assertEquals(CursorState.TRACKING_LOST, expired.state); assertNull(expired.position)
        assertFalse(machine.frame(700, 0.2f).clicked)
    }
    @Test fun staleFutureDuplicateAndOutOfOrderFramesNeverClick() {
        for ((timestamp, now) in listOf(300L to 700L, 400L to 399L, 200L to 200L, 100L to 200L)) {
            val machine = armed()
            val result = machine.update(pose(0.2f), position, timestamp, now)
            assertEquals(CursorState.TRACKING_LOST, result.state); assertFalse(result.clicked)
        }
    }
    @Test fun observationGapDoesNotCountAsContinuousPinch() {
        val machine = armed()
        machine.frame(300, 0.2f)
        assertFalse(machine.frame(700, 0.2f).clicked)
        assertFalse(machine.frame(1000, 0.2f).clicked)
    }
    @Test fun unrelatedGestureCancelsAndCannotClickAtTheOldLocation() {
        val machine = armed()
        machine.frame(300, 0.2f)
        assertNull(machine.update(pose().copy(pinchEligible = false), position, 400, 400).position)
        assertFalse(machine.frame(600, 0.2f).clicked)
    }
    @Test fun handJumpRequiresRearmingInsteadOfContinuingPinch() {
        val machine = armed()
        machine.frame(300, 0.2f)
        val jumped = machine.update(pose(0.2f, false).copy(wrist = Point3(0.95f, 0.95f)), position, 600, 600)
        assertFalse(jumped.clicked); assertFalse(jumped.armed)
    }
    @Test fun cooldownGuardsASecondRapidClick() {
        val machine = armed(CursorConfig(pointArmMs = 100, releaseMs = 80, clickCooldownMs = 1000))
        machine.frame(300, 0.2f); assertTrue(machine.frame(600, 0.2f).clicked)
        machine.frame(700); machine.frame(800); machine.frame(900)
        machine.frame(1000, 0.2f)
        assertFalse(machine.frame(1300, 0.2f).clicked)
        assertTrue(machine.frame(1600, 0.2f).clicked)
    }
    @Test fun resetRequiresNewReleasedPointingAndRemovesPosition() {
        val machine = armed()
        assertEquals(CursorResult(), machine.reset())
        assertFalse(machine.frame(500, 0.1f).clicked)
        assertNull(machine.frame(800, 0.1f).position)
    }

    @Test fun indexMayBendBeforeContactWithoutDiscardingAnArmedPoint() {
        val machine = armed()
        // T20 reproduction: index DIP falls below 155 degrees while the ratio is
        // still 0.46, before entering the 0.42 release band and reaching contact.
        assertEquals(position, machine.frame(300, 0.46f, CursorPoint(0.8f, 0.8f), false).position)
        assertEquals(CursorState.PINCHING, machine.frame(600, 0.35f, pointing = false).state)
        machine.frame(900, 0.24f, pointing = false)
        assertTrue(machine.frame(1200, 0.24f, pointing = false).clicked)
    }

    @Test fun approachMustFinishWithinABoundedWindowAndCannotLeaveAStaleClickArmed() {
        val machine = armed()
        for (time in listOf(300L, 600L, 900L)) assertFalse(machine.frame(time, 0.46f, pointing = false).clicked)
        assertNull(machine.frame(1200, 0.24f, pointing = false).position)
        assertFalse(machine.frame(1500, 0.24f, pointing = false).clicked)
    }

    @Test fun measuredT20ContactSlightlyAbovePointTwentyFiveCanConfirmOnce() {
        val machine = armed()
        machine.frame(300, 0.28f, pointing = false)
        assertTrue(machine.frame(600, 0.28f, pointing = false).clicked)
        assertFalse(machine.frame(900, 0.34f, pointing = false).clicked)
    }
}
