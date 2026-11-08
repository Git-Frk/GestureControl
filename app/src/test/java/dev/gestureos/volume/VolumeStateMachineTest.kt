package dev.gestureos.volume

import dev.gestureos.recognition.Gesture
import dev.gestureos.recognition.Recognition
import dev.gestureos.recognition.RecognitionState
import org.junit.Assert.*
import org.junit.Test

class VolumeStateMachineTest {
    private fun confirmed(gesture: Gesture = Gesture.THUMBS_UP, fresh: Boolean = false) =
        Recognition(gesture, RecognitionState.CONFIRMED, 1f, fresh)

    private fun frame(machine: VolumeStateMachine, at: Long, gesture: Gesture = Gesture.THUMBS_UP, fresh: Boolean = false) =
        machine.update(confirmed(gesture, fresh), gesture, at, at)

    @Test fun firstConfirmedThumbRequestsOneStepInTheCorrectDirection() {
        for ((gesture, direction) in listOf(Gesture.THUMBS_UP to VolumeDirection.UP, Gesture.THUMBS_DOWN to VolumeDirection.DOWN)) {
            val machine = VolumeStateMachine()
            assertEquals(VolumeRequest(direction, false), frame(machine, 0, gesture, true))
            assertNull(frame(machine, 100, gesture))
        }
    }

    @Test fun otherPosesAndUnconfirmedStatesNeverAct() {
        for (gesture in Gesture.entries) for (state in RecognitionState.entries) {
            if (gesture in listOf(Gesture.THUMBS_UP, Gesture.THUMBS_DOWN) && state == RecognitionState.CONFIRMED) continue
            assertNull(VolumeStateMachine().update(Recognition(gesture, state, 1f, true), gesture, 0, 0))
        }
    }

    @Test fun alreadyHeldConfirmationDoesNotActWhenEnabledMidPose() {
        val machine = VolumeStateMachine()
        for (time in 0L..2000L step 100) assertNull(frame(machine, time))
    }

    @Test fun repeatsHaveInitialDelayAndIntervalIndependentOfFrameRate() {
        for (frameInterval in listOf(50L, 100L, 200L)) {
            val machine = VolumeStateMachine()
            val actions = mutableListOf<Pair<Long, VolumeRequest>>()
            for (time in 0L..3000L step frameInterval) {
                frame(machine, time, fresh = time == 0L)?.let { actions += time to it }
            }
            assertEquals(0L, actions.first().first)
            assertFalse(actions.first().second.repeated)
            assertTrue(actions.drop(1).all { it.second.repeated })
            assertEquals(1200L, actions[1].first)
            assertTrue(actions.drop(1).zipWithNext().all { (a, b) -> b.first - a.first >= 700 })
        }
    }

    @Test fun repeatCanBeDisabled() {
        val machine = VolumeStateMachine(VolumeControlConfig(repeatEnabled = false))
        assertNotNull(frame(machine, 0, fresh = true))
        for (time in 100L..5000L step 100) assertNull(frame(machine, time))
    }

    @Test fun configuredTimingIsUsed() {
        val machine = VolumeStateMachine(VolumeControlConfig(repeatDelayMs = 600, repeatIntervalMs = 400))
        val times = (0L..1500L step 100).filter { frame(machine, it, fresh = it == 0L) != null }
        assertEquals(listOf(0L, 600L, 1000L, 1400L), times)
    }

    @Test fun handLossUnknownAndUnconfirmedFramesCancelRepeats() {
        for (interruption in listOf(
            Recognition(Gesture.NO_HAND, RecognitionState.NO_HAND),
            Recognition(Gesture.UNKNOWN, RecognitionState.UNRECOGNIZED),
            Recognition(Gesture.THUMBS_UP, RecognitionState.HOLDING),
            Recognition(Gesture.THUMBS_UP, RecognitionState.COOLDOWN),
            confirmed(Gesture.OPEN_PALM), confirmed(Gesture.CLOSED_FIST),
        )) {
            val machine = VolumeStateMachine()
            frame(machine, 0, fresh = true)
            assertNull(machine.update(interruption, interruption.gesture, 100, 100))
            for (time in 200L..2000L step 100) assertNull(frame(machine, time))
            assertNotNull(frame(machine, 2100, fresh = true))
        }
    }

    @Test fun rawDisagreementCancelsEvenIfSmoothedThumbRemainsConfirmed() {
        val machine = VolumeStateMachine()
        frame(machine, 0, fresh = true)
        assertNull(machine.update(confirmed(), Gesture.UNKNOWN, 100, 100))
        for (time in 200L..2000L step 100) assertNull(frame(machine, time))
    }

    @Test fun staleAndFutureFramesCannotAct() {
        val machine = VolumeStateMachine()
        assertNull(machine.update(confirmed(fresh = true), Gesture.THUMBS_UP, 0, 351))
        assertNull(machine.update(confirmed(fresh = true), Gesture.THUMBS_UP, 1000, 400))
        assertNull(frame(machine, 500))
    }

    @Test fun trackingGapRequiresFreshConfirmationAndNeverCatchesUp() {
        val machine = VolumeStateMachine()
        frame(machine, 0, fresh = true)
        assertNull(frame(machine, 2000))
        assertNotNull(frame(machine, 2100, fresh = true))
        assertNull(frame(machine, 2200))
    }

    @Test fun delayedFrameDoesNotBurstOverdueRepeats() {
        val machine = VolumeStateMachine()
        for (time in 0L..1100L step 100) frame(machine, time, fresh = time == 0L)
        assertNotNull(machine.update(confirmed(), Gesture.THUMBS_UP, 1200, 1500))
        assertNull(machine.update(confirmed(), Gesture.THUMBS_UP, 1300, 1500))
        assertNull(machine.update(confirmed(), Gesture.THUMBS_UP, 1400, 1500))
        assertNull(frame(machine, 1500))
    }

    @Test fun duplicateAndOutOfOrderFramesCannotRepeatAnAction() {
        val machine = VolumeStateMachine()
        frame(machine, 100, fresh = true)
        assertNull(machine.update(confirmed(fresh = true), Gesture.THUMBS_UP, 100, 500))
        assertNull(machine.update(confirmed(fresh = true), Gesture.THUMBS_UP, 50, 500))
    }

    @Test fun backwardsClockCancelsTheHold() {
        val machine = VolumeStateMachine()
        frame(machine, 100, fresh = true)
        assertNull(frame(machine, 90))
        for (time in 200L..2000L step 100) assertNull(frame(machine, time))
    }

    @Test fun cancellingDoesNotBypassTheGlobalRateLimit() {
        val machine = VolumeStateMachine()
        frame(machine, 0, fresh = true)
        machine.cancelHold()
        assertNull(frame(machine, 100, fresh = true))
        assertNull(frame(machine, 200))
        assertNull(frame(machine, 300))
        assertNotNull(frame(machine, 400))
    }

    @Test fun directionReversalWaitsWhileTheOppositeConfirmedPoseIsHeld() {
        val machine = VolumeStateMachine()
        frame(machine, 0, fresh = true)
        for (time in 100L..1400L step 100) assertNull(frame(machine, time, Gesture.THUMBS_DOWN, time == 100L))
        assertEquals(VolumeRequest(VolumeDirection.DOWN, false), frame(machine, 1500, Gesture.THUMBS_DOWN))
        for (time in 1600L..2900L step 100) assertNull(frame(machine, time, fresh = time == 1600L))
        assertEquals(VolumeRequest(VolumeDirection.UP, false), frame(machine, 3000))
    }

    @Test fun cancelledPendingReversalCannotFireLater() {
        val machine = VolumeStateMachine()
        frame(machine, 0, fresh = true)
        frame(machine, 100, Gesture.THUMBS_DOWN, true)
        machine.update(Recognition(Gesture.NO_HAND, RecognitionState.NO_HAND), Gesture.NO_HAND, 200, 200)
        for (time in 300L..2000L step 100) assertNull(frame(machine, time, Gesture.THUMBS_DOWN))
    }

    @Test fun repeatedNewlyConfirmedFlagsDoNotResetTheRepeatDelay() {
        val machine = VolumeStateMachine()
        val times = (0L..2000L step 100).filter { frame(machine, it, fresh = true) != null }
        assertEquals(listOf(0L, 1200L, 1900L), times)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsafeRepeatInterval() { VolumeControlConfig(repeatIntervalMs = 10) }
}
