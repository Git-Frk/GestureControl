package dev.gestureos.cursor

import org.junit.Assert.*
import org.junit.Test

class CursorFilterTest {
    @Test fun smallStationaryNoiseDoesNotMoveThePointer() {
        val filter = CursorFilter(CursorConfig())
        val point = CursorPoint(0.5f, 0.5f)
        filter.update(point, 0)
        for (time in 1L..20) assertEquals(point, filter.update(CursorPoint(0.502f, 0.499f), time * 50))
    }
    @Test fun smoothingIsTimeBasedAcrossDifferentFrameRates() {
        val config = CursorConfig(smoothingMs = 100f, maxSpeed = 6f, deadZone = 0f)
        val a = CursorFilter(config); val b = CursorFilter(config)
        a.update(CursorPoint(0f, 0f), 0); b.update(CursorPoint(0f, 0f), 0)
        val target = CursorPoint(0.2f, 0.2f)
        a.update(target, 50)
        assertEquals(a.update(target, 100).x, b.update(target, 100).x, 0.0001f)
    }
    @Test fun speedLimitPreventsLargeLandmarkJumpsEvenWithoutSmoothing() {
        val filter = CursorFilter(CursorConfig(smoothingMs = 0f, maxSpeed = 1f))
        filter.update(CursorPoint(0f, 0f), 0)
        val next = filter.update(CursorPoint(1f, 1f), 100)
        assertEquals(0.1f, next.distance(CursorPoint(0f, 0f)), 0.0001f)
    }
    @Test fun resetAndLongGapDoNotInterpolateFromStalePosition() {
        val filter = CursorFilter(CursorConfig())
        filter.update(CursorPoint(0f, 0f), 0)
        assertEquals(CursorPoint(1f, 1f), filter.update(CursorPoint(1f, 1f), 1000))
        filter.reset()
        assertEquals(CursorPoint(0.2f, 0.3f), filter.update(CursorPoint(0.2f, 0.3f), 1100))
    }
    @Test fun outputClampsAndDuplicateTimestampCannotAdvance() {
        val filter = CursorFilter(CursorConfig())
        assertEquals(CursorPoint(0f, 1f), filter.update(CursorPoint(-1f, 2f), 10))
        assertEquals(CursorPoint(0f, 1f), filter.update(CursorPoint(0.5f, 0.5f), 10))
    }
}
