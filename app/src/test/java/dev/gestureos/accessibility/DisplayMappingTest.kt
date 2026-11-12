package dev.gestureos.accessibility

import dev.gestureos.cursor.CursorConfig
import dev.gestureos.cursor.CursorPoint
import dev.gestureos.cursor.CursorRect
import org.junit.Assert.*
import org.junit.Test

class DisplayMappingTest {
    private val config = CursorConfig()
    private val mapper = DisplayCursorMapper(config)
    private val landscape = DisplayArea(0, 1, CursorRect(0f, 0f, 2000f, 1200f), CursorRect(0f, 42f, 2000f, 1116f))

    @Test fun uprightFrontCameraIsMirroredExactlyOnce() {
        assertEquals(1f, mapper.map(CursorPoint(0.15f, 0.5f), 1, 1)!!.x, 0.0001f)
        assertEquals(0f, mapper.map(CursorPoint(0.85f, 0.5f), 1, 1)!!.x, 0.0001f)
        assertEquals(0.5f, mapper.map(CursorPoint(0.5f, 0.5f), 1, 1)!!.y, 0.0001f)
    }

    @Test fun allDisplayRotationsUseAlreadyUprightLandmarksWithoutAnotherRotation() {
        for (rotation in 0..3) {
            val point = mapper.map(CursorPoint(0.15f, 0.15f), rotation, rotation)!!
            assertEquals(1f, point.x, 0.0001f)
            assertEquals(0f, point.y, 0.0001f)
        }
    }

    @Test fun aFrameFromThePreviousDisplayRotationIsRejected() {
        for (rotation in 0..3) assertNull(mapper.map(CursorPoint(0.5f, 0.5f), rotation, (rotation + 1) % 4))
        assertNull(mapper.map(CursorPoint(0.5f, 0.5f), -1, -1))
    }

    @Test fun insetDisplayEdgesAreReachableAndExclusiveUpperBoundsAreRespected() {
        assertEquals(CursorPoint(0f, 42f), landscape.toDisplay(CursorPoint(0f, 0f)))
        assertEquals(CursorPoint(1999f, 1115f), landscape.toDisplay(CursorPoint(1f, 1f)))
        assertEquals(CursorPoint(0f, 1115f), landscape.toDisplay(CursorPoint(-4f, 7f)))
    }

    @Test fun portraitCutoutsAndDisplayOriginAreAccountedFor() {
        val portrait = DisplayArea(0, 0, CursorRect(10f, 20f, 1210f, 2020f), CursorRect(50f, 100f, 1180f, 1960f))
        assertEquals(CursorPoint(50f, 100f), portrait.toDisplay(CursorPoint(0f, 0f)))
        assertEquals(CursorPoint(1179f, 1959f), portrait.toDisplay(CursorPoint(1f, 1f)))
    }

    @Test fun sensitivityAndAsymmetricActiveRegionRemainConfigurable() {
        val tuned = DisplayCursorMapper(config.copy(sensitivity = 2f, region = CursorRect(0.1f, 0.2f, 0.7f, 0.8f)))
        assertEquals(0.5f, tuned.map(CursorPoint(0.6f, 0.5f), 1, 1)!!.x, 0.0001f)
        assertEquals(1f, tuned.map(CursorPoint(0.45f, 0.5f), 1, 1)!!.x, 0.0001f)
    }

    @Test fun nonFiniteInputNeverProducesTapCoordinates() {
        assertNull(mapper.map(CursorPoint(Float.NaN, 0.5f), 1, 1))
        assertNull(landscape.toDisplay(CursorPoint(Float.POSITIVE_INFINITY, 0f)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun usableAreaCannotEscapeThePhysicalDisplay() {
        DisplayArea(0, 0, CursorRect(0f, 0f, 100f, 100f), CursorRect(-1f, 0f, 100f, 100f))
    }
}
