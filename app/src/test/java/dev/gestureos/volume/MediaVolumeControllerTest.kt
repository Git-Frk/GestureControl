package dev.gestureos.volume

import dev.gestureos.recognition.Gesture
import dev.gestureos.recognition.GestureStateMachine
import dev.gestureos.recognition.Recognition
import dev.gestureos.recognition.RecognitionState
import org.junit.Assert.*
import org.junit.Test

class MediaVolumeControllerTest {
    private class FakeDevice(current: Int = 5, minimum: Int = 0, maximum: Int = 15) : MediaVolumeDevice {
        var reading = VolumeReading(VolumeLevel(current, minimum, maximum))
        val calls = mutableListOf<VolumeDirection>()
        var ignoreChanges = false
        var rejectChange = false
        override fun read() = reading
        override fun adjustOneStep(direction: VolumeDirection): VolumeReading {
            calls += direction
            if (rejectChange) return VolumeReading(restriction = VolumeRestriction.DENIED)
            if (!ignoreChanges) {
                val level = checkNotNull(reading.level)
                reading = reading.copy(level = level.copy(current = level.current + if (direction == VolumeDirection.UP) 1 else -1))
            }
            return reading
        }
    }

    private fun frame(controller: MediaVolumeController, time: Long, gesture: Gesture = Gesture.THUMBS_UP, fresh: Boolean = false) =
        controller.onFrame(Recognition(gesture, RecognitionState.CONFIRMED, 1f, fresh), gesture, time, time)

    @Test fun disabledByDefaultAndDisablingCancelsRepeats() {
        val device = FakeDevice()
        val controller = MediaVolumeController(device)
        assertNull(frame(controller, 0, fresh = true))
        controller.setEnabled(true, 100)
        assertNotNull(frame(controller, 100, fresh = true))
        controller.setEnabled(false, 200)
        for (time in 200L..2000L step 100) assertNull(frame(controller, time, fresh = true))
        assertEquals(1, device.calls.size)
    }

    @Test fun enablingRejectsBufferedFramesAndAlreadyConfirmedThumbs() {
        val device = FakeDevice()
        val controller = MediaVolumeController(device)
        controller.setEnabled(true, 1000)
        assertNull(controller.onFrame(Recognition(Gesture.THUMBS_UP, RecognitionState.CONFIRMED, 1f, true), Gesture.THUMBS_UP, 900, 1000))
        assertNull(frame(controller, 1100))
        assertTrue(device.calls.isEmpty())
        assertNotNull(frame(controller, 1200, fresh = true))
    }

    @Test fun usesActualDeviceLevelAndReportsBothDirections() {
        for ((gesture, outcome, expected) in listOf(
            Triple(Gesture.THUMBS_UP, VolumeOutcome.RAISED, 6),
            Triple(Gesture.THUMBS_DOWN, VolumeOutcome.LOWERED, 4),
        )) {
            val device = FakeDevice()
            val controller = MediaVolumeController(device).apply { setEnabled(true, 0) }
            val result = checkNotNull(frame(controller, 0, gesture, true))
            assertEquals(outcome, result.outcome)
            assertEquals(expected, result.reading.level?.current)
            assertEquals(1, device.calls.size)
        }
    }

    @Test fun boundariesIncludingNonzeroMinimumDoNotCallAndroid() {
        for ((current, gesture, outcome) in listOf(
            Triple(2, Gesture.THUMBS_DOWN, VolumeOutcome.MINIMUM),
            Triple(15, Gesture.THUMBS_UP, VolumeOutcome.MAXIMUM),
        )) {
            val device = FakeDevice(current, minimum = 2)
            val controller = MediaVolumeController(device).apply { setEnabled(true, 0) }
            assertEquals(outcome, frame(controller, 0, gesture, true)?.outcome)
            for (time in 100L..2000L step 100) assertNull(frame(controller, time, gesture))
            assertTrue(device.calls.isEmpty())
        }
    }

    @Test fun reachingLimitStopsFurtherAttemptsUntilFreshConfirmation() {
        val device = FakeDevice(14)
        val controller = MediaVolumeController(device).apply { setEnabled(true, 0) }
        assertEquals(VolumeOutcome.RAISED, frame(controller, 0, fresh = true)?.outcome)
        for (time in 100L..2000L step 100) assertNull(frame(controller, time))
        assertEquals(1, device.calls.size)
        assertEquals(VolumeOutcome.LOWERED, frame(controller, 2100, Gesture.THUMBS_DOWN, true)?.outcome)
    }

    @Test fun fixedPolicyDeniedAndUnavailableReadingsBlockActions() {
        for (restriction in VolumeRestriction.entries) {
            val device = FakeDevice().apply { reading = reading.copy(restriction = restriction) }
            val controller = MediaVolumeController(device).apply { setEnabled(true, 0) }
            assertEquals(VolumeOutcome.BLOCKED, frame(controller, 0, fresh = true)?.outcome)
            for (time in 100L..2000L step 100) assertNull(frame(controller, time))
            assertTrue(device.calls.isEmpty())
        }
    }

    @Test fun policyChangeBetweenReadAndAdjustmentIsReportedAndStopsRepeats() {
        val device = FakeDevice().apply { rejectChange = true }
        val controller = MediaVolumeController(device).apply { setEnabled(true, 0) }
        assertEquals(VolumeRestriction.DENIED, frame(controller, 0, fresh = true)?.reading?.restriction)
        for (time in 100L..2000L step 100) assertNull(frame(controller, time))
        assertEquals(1, device.calls.size)
    }

    @Test fun ignoredPlatformAdjustmentDoesNotClaimSuccessOrRetryWhileHeld() {
        val device = FakeDevice().apply { ignoreChanges = true }
        val controller = MediaVolumeController(device).apply { setEnabled(true, 0) }
        val result = checkNotNull(frame(controller, 0, fresh = true))
        assertEquals(VolumeOutcome.UNCHANGED, result.outcome)
        assertEquals(5, result.reading.level?.current)
        for (time in 100L..2000L step 100) assertNull(frame(controller, time))
        assertEquals(1, device.calls.size)
    }

    @Test fun readOnlyRefreshReflectsHardwareChangesWithoutAdjustingAudio() {
        val device = FakeDevice()
        val controller = MediaVolumeController(device)
        assertEquals(5, controller.read().level?.current)
        device.reading = VolumeReading(VolumeLevel(10, 0, 15))
        assertEquals(10, controller.read().level?.current)
        assertTrue(device.calls.isEmpty())
    }

    @Test fun worksWithExistingRecognitionHoldAndCancelsOnLoss() {
        val recognition = GestureStateMachine()
        val device = FakeDevice()
        val controller = MediaVolumeController(device).apply { setEnabled(true, 0) }
        val results = mutableListOf<Pair<Long, VolumeFeedback>>()
        for (time in 0L..1600L step 100) {
            val pose = recognition.update(Gesture.THUMBS_UP, time)
            controller.onFrame(pose, Gesture.THUMBS_UP, time, time)?.let { results += time to it }
        }
        assertEquals(listOf(400L, 1600L), results.map { it.first })
        assertFalse(results[0].second.repeated)
        assertTrue(results[1].second.repeated)
        for (time in 1700L..3000L step 100) {
            assertNull(controller.onFrame(recognition.update(Gesture.NO_HAND, time), Gesture.NO_HAND, time, time))
        }
        assertEquals(2, device.calls.size)
    }
}
