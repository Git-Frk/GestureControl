package dev.gestureos.recognition

import org.junit.Assert.*
import org.junit.Test

class GestureStateMachineTest {
    private val palm = Gesture.OPEN_PALM
    private val fist = Gesture.CLOSED_FIST

    @Test fun requiresContinuousHoldAndConfirmsOnlyOnce() {
        val machine = GestureStateMachine()
        assertEquals(RecognitionState.HOLDING, machine.update(palm, 0).state)
        assertEquals(0.5f, machine.update(palm, 200).holdProgress, 0.001f)
        assertFalse(machine.update(palm, 399).newlyConfirmed)
        assertTrue(machine.update(palm, 400).newlyConfirmed)
        assertEquals(RecognitionState.CONFIRMED, machine.update(palm, 600).state)
        assertFalse(machine.update(palm, 800).newlyConfirmed)
    }

    @Test fun flickerRestartsHold() {
        val machine = GestureStateMachine()
        machine.update(palm, 0)
        machine.update(palm, 200)
        machine.update(fist, 300)
        assertEquals(0f, machine.update(palm, 400).holdProgress, 0f)
        assertFalse(machine.update(palm, 600).newlyConfirmed)
        assertTrue(machine.update(palm, 800).newlyConfirmed)
    }

    @Test fun unknownAndHandLossClearConfirmedPoseImmediately() {
        for (lost in listOf(Gesture.UNKNOWN, Gesture.NO_HAND)) {
            val machine = GestureStateMachine()
            machine.update(palm, 0)
            machine.update(palm, 200)
            machine.update(palm, 400)
            assertEquals(lost, machine.update(lost, 450).gesture)
            assertEquals(RecognitionState.HOLDING, machine.update(palm, 500).state)
        }
    }

    @Test fun cooldownPreventsRapidSecondConfirmationWithoutLosingCandidate() {
        val machine = GestureStateMachine()
        machine.update(palm, 0)
        machine.update(palm, 200)
        assertTrue(machine.update(palm, 400).newlyConfirmed)
        machine.update(fist, 500)
        machine.update(fist, 700)
        assertEquals(RecognitionState.COOLDOWN, machine.update(fist, 900).state)
        assertTrue(machine.update(fist, 1000).newlyConfirmed)
    }

    @Test fun missingFramesDoNotCountAsHeldPose() {
        val machine = GestureStateMachine()
        machine.update(palm, 0)
        assertEquals(0f, machine.update(palm, 1000).holdProgress, 0f)
        machine.update(palm, 1200)
        assertTrue(machine.update(palm, 1400).newlyConfirmed)
        assertEquals(RecognitionState.HOLDING, machine.update(palm, 2000).state)
    }

    @Test fun noHandAndUnknownHaveDistinctStates() {
        val machine = GestureStateMachine()
        assertEquals(RecognitionState.NO_HAND, machine.update(Gesture.NO_HAND, 0).state)
        assertEquals(RecognitionState.UNRECOGNIZED, machine.update(Gesture.UNKNOWN, 100).state)
    }

    @Test fun holdIsTimeBasedAcrossFrameRates() {
        for (interval in listOf(40L, 80L, 200L)) {
            val machine = GestureStateMachine()
            var time = 0L
            while (time < 400) {
                assertFalse(machine.update(palm, time).newlyConfirmed)
                time += interval
            }
            assertTrue(machine.update(palm, time).newlyConfirmed)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBackwardsClock() {
        val machine = GestureStateMachine()
        machine.update(palm, 200)
        machine.update(palm, 100)
    }

    @Test fun repeatedTimestampDoesNotAdvanceHold() {
        val machine = GestureStateMachine()
        machine.update(palm, 100)
        assertEquals(0f, machine.update(palm, 100).holdProgress, 0f)
    }
}
