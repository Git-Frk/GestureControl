package dev.gestureos.service

import org.junit.Assert.*
import org.junit.Test

class TrackingLifecycleTest {
    private val lifecycle = TrackingLifecycle()

    private fun start(background: Boolean = true): Long {
        assertEquals(StartDecision.START, lifecycle.start(true, true, background))
        return lifecycle.status.generation
    }

    @Test fun newProcessRequiresExplicitStart() {
        assertEquals(TrackingPhase.STOPPED, lifecycle.status.phase)
        assertFalse(lifecycle.status.backgroundEnabled)
        assertFalse(lifecycle.accepts(0))
    }

    @Test fun backgroundStartIsRejectedEvenWithPermission() {
        assertEquals(StartDecision.REJECTED, lifecycle.start(false, true, true))
        assertEquals(TrackingPhase.STOPPED, lifecycle.status.phase)
    }

    @Test fun missingCameraPermissionRejectsVisibleStart() {
        assertEquals(StartDecision.REJECTED, lifecycle.start(true, false, true))
        assertEquals(TrackingPhase.STOPPED, lifecycle.status.phase)
    }

    @Test fun framesRequireCameraReady() {
        val generation = start()
        assertFalse(lifecycle.accepts(generation))
        lifecycle.ready(generation)
        assertTrue(lifecycle.accepts(generation))
    }

    @Test fun duplicateStartsKeepOneGeneration() {
        val generation = start()
        repeat(4) { assertEquals(StartDecision.ALREADY_ACTIVE, lifecycle.start(true, true, true)) }
        lifecycle.ready(generation)
        assertEquals(generation, lifecycle.status.generation)
        assertEquals(StartDecision.ALREADY_ACTIVE, lifecycle.start(true, true, true))
        assertEquals(TrackingPhase.RUNNING, lifecycle.status.phase)
    }

    @Test fun backgroundModeChangesWithoutNewSession() {
        val generation = start(false)
        assertTrue(lifecycle.shouldStopWhenHidden())
        lifecycle.start(true, true, true)
        assertFalse(lifecycle.shouldStopWhenHidden())
        lifecycle.start(true, true, false)
        assertTrue(lifecycle.shouldStopWhenHidden())
        assertEquals(generation, lifecycle.status.generation)
    }

    @Test fun stopInvalidatesInFlightFramesImmediately() {
        val generation = start()
        lifecycle.ready(generation)
        lifecycle.stop(StopReason.USER)
        assertFalse(lifecycle.accepts(generation))
        assertFalse(lifecycle.status.backgroundEnabled)
        lifecycle.ready(generation)
        assertEquals(TrackingPhase.STOPPING, lifecycle.status.phase)
    }

    @Test fun cannotRestartUntilNativeResourcesReleased() {
        start()
        val release = lifecycle.stop(StopReason.RECONFIGURE)!!
        assertEquals(StartDecision.REJECTED, lifecycle.start(true, true, true))
        lifecycle.released(release)
        assertEquals(StartDecision.START, lifecycle.start(true, true, true))
    }

    @Test fun repeatedStopIsIdempotent() {
        start()
        val release = lifecycle.stop(StopReason.USER)!!
        assertNull(lifecycle.stop(StopReason.USER))
        lifecycle.released(release)
        lifecycle.released(release)
        assertEquals(TrackingPhase.STOPPED, lifecycle.status.phase)
    }

    @Test fun obsoleteReleaseCannotStopNewSession() {
        start()
        val release = lifecycle.stop(StopReason.USER)!!
        lifecycle.released(release)
        val generation = start()
        lifecycle.ready(generation)
        lifecycle.released(release)
        assertTrue(lifecycle.accepts(generation))
    }

    @Test fun hardwareOrPermissionFailureRequiresFreshVisibleStart() {
        start()
        val release = lifecycle.stop(StopReason.FAILURE, "Camera unavailable")!!
        lifecycle.released(release)
        assertEquals(TrackingPhase.ERROR, lifecycle.status.phase)
        assertEquals("Camera unavailable", lifecycle.status.error)
        assertEquals(StartDecision.REJECTED, lifecycle.start(false, true, true))
        start()
        assertNull(lifecycle.status.error)
    }

    @Test fun interruptedServiceCannotReusePreviousGeneration() {
        val previous = start()
        val release = lifecycle.stop(StopReason.INTERRUPTED)!!
        assertFalse(lifecycle.accepts(previous))
        assertEquals(StopReason.INTERRUPTED, lifecycle.status.stopReason)
        assertEquals(StartDecision.REJECTED, lifecycle.start(true, true, true))
        lifecycle.released(release)
        val next = start()
        lifecycle.ready(previous)
        assertFalse(lifecycle.accepts(next))
        lifecycle.ready(next)
        assertTrue(lifecycle.accepts(next))
    }

    @Test fun homeReturnCyclesKeepBackgroundSessionReady() {
        val generation = start()
        lifecycle.ready(generation)
        repeat(10) {
            assertFalse(lifecycle.shouldStopWhenHidden())
            assertTrue(lifecycle.accepts(generation))
            assertEquals(StartDecision.ALREADY_ACTIVE, lifecycle.start(true, true, true))
        }
    }

    @Test fun stopWhileStartingRejectsLateModelReady() {
        val generation = start()
        val release = lifecycle.stop(StopReason.USER)!!
        lifecycle.ready(generation)
        lifecycle.released(release)
        assertEquals(TrackingPhase.STOPPED, lifecycle.status.phase)
        assertFalse(lifecycle.accepts(generation))
    }
}
