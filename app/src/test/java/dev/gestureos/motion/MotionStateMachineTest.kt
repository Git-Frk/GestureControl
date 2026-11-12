package dev.gestureos.motion

import dev.gestureos.cursor.CursorConfig
import dev.gestureos.cursor.CursorPoint
import dev.gestureos.interaction.SystemAction
import org.junit.Assert.*
import org.junit.Test

class MotionStateMachineTest {
    private val config = MotionConfig(smoothingMs = 0f)
    private val center = CursorPoint(0.5f, 0.5f)
    private fun machine() = MotionStateMachine(config, CursorConfig())
    private fun MotionStateMachine.frame(at: Long, x: Float = 0.5f, y: Float = 0.5f,
        pose: MotionPose = MotionPose.SCROLL, available: Boolean = true) = update(pose, CursorPoint(x, y), at, available)
    private fun ready(kind: MotionPose = MotionPose.SCROLL) = machine().apply {
        for (time in 0L..300L step 100) assertNull(frame(time, pose = kind).action)
    }

    @Test fun scrollRequiresStationaryConfirmedPose() {
        val machine = machine()
        assertNull(machine.frame(0).action)
        assertNull(machine.frame(100, 0.65f).action)
        assertEquals(MotionState.CONFIRMING_SCROLL, machine.frame(300, 0.65f).state)
        assertEquals(MotionState.SCROLL_READY, machine.frame(400, 0.65f).state)
        assertEquals(SystemAction.SWIPE_RIGHT, machine.frame(500, 0.8f).action)
    }

    @Test fun allFourDirectionsRequireThresholdAndAxisDominance() {
        for ((point, action) in listOf(CursorPoint(0.35f, 0.5f) to SystemAction.SWIPE_LEFT,
            CursorPoint(0.65f, 0.5f) to SystemAction.SWIPE_RIGHT, CursorPoint(0.5f, 0.35f) to SystemAction.SWIPE_UP,
            CursorPoint(0.5f, 0.65f) to SystemAction.SWIPE_DOWN)) {
            assertEquals(action, ready().frame(400, point.x, point.y).action)
        }
        assertNull(ready().frame(400, 0.65f, 0.65f).action)
        assertNull(ready().frame(400, 0.55f).action)
    }

    @Test fun stationaryTremorAndHoldingAnOffsetDoNotRepeat() {
        val machine = ready()
        for (time in 400L..1000L step 100) assertNull(machine.frame(time, 0.51f).action)
        assertNotNull(machine.frame(1100, 0.65f).action)
        for (time in 1200L..5000L step 100) assertNull(machine.frame(time, 0.65f).action)
    }

    @Test fun scrollRepeatsRequireFreshTravelAfterInterval() {
        val machine = ready()
        assertNotNull(machine.frame(400, 0.65f).action)
        assertNull(machine.frame(500, 0.8f).action)
        assertNull(machine.frame(700, 0.8f).action)
        assertNull(machine.frame(900, 0.8f).action)
        assertNotNull(machine.frame(1000, 0.95f).action)
    }

    @Test fun transportBackpressureDiscardsMovementWithoutQueueing() {
        val machine = ready()
        assertNull(machine.frame(400, 0.7f, available = false).action)
        assertNull(machine.frame(500, 0.7f).action)
        assertNotNull(machine.frame(600, 0.85f).action)
    }

    @Test fun poseChangeCancelsScrollAndRequiresFreshConfirmation() {
        val machine = ready()
        machine.frame(400, pose = MotionPose.NONE)
        assertNull(machine.frame(500, 0.7f).action)
        assertNull(machine.frame(600, 0.9f).action)
        assertEquals(MotionState.CONFIRMING_SCROLL, machine.frame(700, 0.9f).state)
    }

    private fun navigation(machine: MotionStateMachine, start: Long = 400, home: Boolean = false): MotionResult {
        if (home) {
            machine.frame(start, y = 0.44f, pose = MotionPose.NAVIGATION)
            machine.frame(start + 100, y = 0.38f, pose = MotionPose.NAVIGATION)
            return machine.frame(start + 200, y = 0.28f, pose = MotionPose.NAVIGATION)
        }
        machine.frame(start, 0.44f, pose = MotionPose.NAVIGATION)
        machine.frame(start + 100, 0.38f, pose = MotionPose.NAVIGATION)
        return machine.frame(start + 200, 0.28f, pose = MotionPose.NAVIGATION)
    }

    @Test fun deliberateThreeFingerLeftAndUpTriggerBackAndHome() {
        assertEquals(SystemAction.BACK, navigation(ready(MotionPose.NAVIGATION)).action)
        assertEquals(SystemAction.HOME, navigation(ready(MotionPose.NAVIGATION), home = true).action)
    }

    @Test fun eachNavigationFiresOnceAndRequiresVisibleNeutral() {
        val machine = ready(MotionPose.NAVIGATION)
        assertNotNull(navigation(machine).action)
        for (time in 700L..1700L step 100) assertNull(machine.frame(time, 0.1f, pose = MotionPose.NAVIGATION).action)
        machine.frame(1800, pose = MotionPose.NONE)
        assertEquals(MotionState.WAITING_NEUTRAL, machine.frame(1900, pose = MotionPose.NAVIGATION).state)
        machine.frame(2000, pose = MotionPose.NEUTRAL)
        assertEquals(MotionState.WAITING_NEUTRAL, machine.frame(2100, pose = MotionPose.NEUTRAL).state)
        machine.frame(2300, pose = MotionPose.NEUTRAL)
        for (time in 2400L..2700L step 100) machine.frame(time, pose = MotionPose.NAVIGATION)
        assertNotNull(navigation(machine, 2800).action)
    }

    @Test fun cooldownSurvivesNeutralReleaseAndReset() {
        val machine = ready(MotionPose.NAVIGATION)
        navigation(machine)
        machine.frame(700, pose = MotionPose.NEUTRAL)
        machine.frame(1000, pose = MotionPose.NEUTRAL)
        machine.reset()
        for (time in 1100L..1400L step 100) machine.frame(time, pose = MotionPose.NAVIGATION)
        assertNull(navigation(machine, 1500).action)
    }

    @Test fun rightDownDiagonalAndFastJumpsCannotNavigate() {
        for (point in listOf(CursorPoint(0.75f, 0.5f), CursorPoint(0.5f, 0.75f), CursorPoint(0.25f, 0.25f))) {
            val machine = ready(MotionPose.NAVIGATION)
            for (time in 400L..1600L step 100) assertNull(machine.frame(time, point.x, point.y, MotionPose.NAVIGATION).action)
        }
        val machine = ready(MotionPose.NAVIGATION)
        assertEquals(MotionState.WAITING_NEUTRAL, machine.frame(400, 0.25f, pose = MotionPose.NAVIGATION).state)
    }

    @Test fun slowMovementTimesOutAndNeedsNeutral() {
        val machine = ready(MotionPose.NAVIGATION)
        for (time in 400L..1400L step 100) assertNull(machine.frame(time, 0.44f, pose = MotionPose.NAVIGATION).action)
        assertEquals(MotionState.WAITING_NEUTRAL, machine.frame(1500, 0.28f, pose = MotionPose.NAVIGATION).state)
    }

    @Test fun navigationInterruptionNeedsFreshScrollConfirmationAndNeutralForNavigation() {
        val machine = ready(MotionPose.NAVIGATION)
        machine.frame(400, 0.44f, pose = MotionPose.NAVIGATION)
        assertEquals(MotionState.CONFIRMING_SCROLL, machine.frame(500, 0.3f, pose = MotionPose.SCROLL).state)
        for (time in 600L..800L step 100) assertNull(machine.frame(time, 0.3f, pose = MotionPose.SCROLL).action)
        assertNotNull(machine.frame(900, 0.1f, pose = MotionPose.SCROLL).action)
        assertEquals(MotionState.WAITING_NEUTRAL, machine.frame(1000, pose = MotionPose.NAVIGATION).state)
    }

    @Test fun trackingResetNeedsSustainedNeutralForNavigation() {
        val machine = ready()
        machine.reset(requireNeutral = true)
        assertEquals(MotionState.WAITING_NEUTRAL, machine.frame(400, 0.8f, pose = MotionPose.NAVIGATION).state)
        machine.frame(500, pose = MotionPose.NEUTRAL)
        machine.frame(800, pose = MotionPose.NEUTRAL)
        assertEquals(MotionState.CONFIRMING_SCROLL, machine.frame(900).state)
    }

    @Test fun trackingResetLetsScrollingReconfirmWithoutCarryingPreviousTravel() {
        val machine = ready()
        machine.reset(requireNeutral = true)
        for (time in 400L..700L step 100) assertNull(machine.frame(time, 0.7f).action)
        assertEquals(MotionState.SCROLL_READY, machine.frame(800, 0.7f).state)
        assertNotNull(machine.frame(900, 0.85f).action)
    }

    @Test fun sensitivityAndSmoothingAreConfigurable() {
        val machine = MotionStateMachine(config.copy(sensitivity = 2f, smoothingMs = 100f), CursorConfig())
        for (time in 0L..300L step 100) machine.update(MotionPose.SCROLL, center, time, true)
        assertNull(machine.frame(310, 0.56f).action)
        assertNotNull(machine.frame(500, 0.56f).action)
    }

    @Test fun smoothingCannotReplayMovementAfterDispatchBackpressure() {
        val machine = MotionStateMachine(config.copy(smoothingMs = 200f), CursorConfig())
        for (time in 0L..300L step 100) machine.frame(time)
        machine.frame(400, 0.9f, available = false)
        for (time in 500L..1500L step 100) assertNull(machine.frame(time, 0.9f).action)
    }

    @Test fun invalidDurationsThresholdsAndNonfiniteSettingsAreRejected() {
        for (create in listOf<() -> MotionConfig>(
            { config.copy(sensitivity = Float.NaN) }, { config.copy(scrollThreshold = 0.04f) },
            { config.copy(swipeDurationMs = 350, repeatIntervalMs = 300) },
            { config.copy(navigationMinMs = 500, navigationMaxMs = 400) })) {
            assertTrue(runCatching(create).isFailure)
        }
    }

    @Test fun briefPoseNoiseBeforeMovementRequiresFreshConfirmationWithoutNeutralLatch() {
        val machine = ready(MotionPose.NAVIGATION)
        machine.frame(400, pose = MotionPose.NONE)
        assertEquals(MotionState.CONFIRMING_NAVIGATION, machine.frame(500, pose = MotionPose.NAVIGATION).state)
        for (time in 600L..800L step 100) assertNull(machine.frame(time, pose = MotionPose.NAVIGATION).action)
        assertEquals(SystemAction.BACK, navigation(machine, 900).action)
    }

    @Test fun smallPostConfirmationDriftDoesNotStartNavigationTimeout() {
        val machine = ready(MotionPose.NAVIGATION)
        for (time in 400L..2400L step 100) {
            assertEquals(MotionState.NAVIGATION_READY, machine.frame(time, 0.46f, pose = MotionPose.NAVIGATION).state)
        }
        assertEquals(SystemAction.BACK, navigation(machine, 2500).action)
    }

    @Test fun interruptedMovementRequiresNeutralAndCannotFinishAfterPoseReturns() {
        val machine = ready(MotionPose.NAVIGATION)
        machine.frame(400, 0.44f, pose = MotionPose.NAVIGATION)
        assertEquals(NavigationRejection.POSE_CHANGED, machine.frame(500, pose = MotionPose.NONE).rejection)
        val blocked = machine.frame(600, 0.28f, pose = MotionPose.NAVIGATION)
        assertNull(blocked.action)
        assertEquals(MotionState.WAITING_NEUTRAL, blocked.state)
    }

    @Test fun rejectedNavigationExplainsTimingAndDirection() {
        assertEquals(NavigationRejection.TOO_FAST, ready(MotionPose.NAVIGATION)
            .frame(400, 0.25f, pose = MotionPose.NAVIGATION).rejection)
        assertEquals(NavigationRejection.WRONG_DIRECTION, ready(MotionPose.NAVIGATION)
            .frame(400, 0.75f, pose = MotionPose.NAVIGATION).rejection)
        val machine = ready(MotionPose.NAVIGATION)
        for (time in 400L..1400L step 100) machine.frame(time, 0.44f, pose = MotionPose.NAVIGATION)
        val rejected = machine.frame(1500, 0.28f, pose = MotionPose.NAVIGATION)
        assertEquals(NavigationRejection.TIMED_OUT, rejected.rejection)
        assertEquals(1100L, rejected.movementMs)
        assertNotNull(rejected.travel)
    }

    @Test fun returningToOriginCannotExtendAnAlreadyStartedMovementForever() {
        val machine = ready(MotionPose.NAVIGATION)
        machine.frame(400, 0.44f, pose = MotionPose.NAVIGATION)
        for (time in 500L..1400L step 100) machine.frame(time, pose = MotionPose.NAVIGATION)
        assertEquals(NavigationRejection.TIMED_OUT, machine.frame(1500, 0.28f, pose = MotionPose.NAVIGATION).rejection)
    }
}
