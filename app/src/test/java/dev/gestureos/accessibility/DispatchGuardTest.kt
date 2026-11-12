package dev.gestureos.accessibility

import org.junit.Assert.*
import org.junit.Test

class DispatchGuardTest {
    @Test fun onlyOneReservationCanExist() {
        val guard = DispatchGuard()
        val token = guard.reserve()!!
        assertTrue(guard.busy)
        assertNull(guard.reserve())
        assertTrue(guard.complete(token))
        assertFalse(guard.busy)
        assertNotNull(guard.reserve())
    }

    @Test fun interruptionRetainsTransportOccupancyUntilItsCallback() {
        val guard = DispatchGuard()
        val token = guard.reserve()!!
        guard.invalidate()
        assertTrue(guard.busy)
        assertNull(guard.reserve())
        assertFalse(guard.complete(token))
        assertFalse(guard.busy)
        assertNotNull(guard.reserve())
    }

    @Test fun oldOrDuplicateCallbacksCannotReleaseANewGesture() {
        val guard = DispatchGuard()
        val old = guard.reserve()!!
        assertTrue(guard.complete(old))
        val current = guard.reserve()!!
        assertFalse(guard.complete(old))
        assertTrue(guard.busy)
        assertTrue(guard.complete(current))
    }

    @Test fun timeoutAlsoAppliesToAnInvalidatedGestureWithoutReleasingIt() {
        val guard = DispatchGuard()
        val token = guard.reserve(500)!!
        guard.invalidate()
        assertFalse(guard.timedOut(1500))
        assertTrue(guard.timedOut(1501))
        assertNull(guard.reserve(1502))
        assertFalse(guard.complete(token))
        assertFalse(guard.timedOut(1600))
    }
}
