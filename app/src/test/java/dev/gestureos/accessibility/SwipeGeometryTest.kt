package dev.gestureos.accessibility

import dev.gestureos.cursor.CursorConfig
import dev.gestureos.cursor.CursorPoint
import dev.gestureos.cursor.CursorRect
import dev.gestureos.interaction.SystemAction
import org.junit.Assert.*
import org.junit.Test

class SwipeGeometryTest {
    private fun display(rotation: Int) = if (rotation % 2 == 0)
        DisplayArea(0, rotation, CursorRect(0f, 0f, 1200f, 2000f), CursorRect(0f, 42f, 1200f, 1916f))
        else DisplayArea(0, rotation, CursorRect(0f, 0f, 2000f, 1200f), CursorRect(25f, 42f, 1975f, 1116f))

    @Test fun swipesStayInsideUsableBoundsAtEveryEdgeAndRotation() {
        for (rotation in 0..3) for (action in SystemAction.entries.filter { it.isSwipe }) {
            val area = display(rotation)
            for (point in listOf(CursorPoint(0f, 0f), CursorPoint(1f, 1f), CursorPoint(0f, 1f), CursorPoint(1f, 0f))) {
                val swipe = SwipeGeometry.create(action, point, 0.6f, area)
                assertTrue(area.usable.contains(swipe.start))
                assertTrue(area.usable.contains(swipe.end))
            }
        }
    }

    @Test fun eachDirectionHasConfiguredLengthAndCorrectSign() {
        val area = display(1)
        for (action in SystemAction.entries.filter { it.isSwipe }) {
            val swipe = SwipeGeometry.create(action, CursorPoint(0.5f, 0.5f), 0.26f, area)
            val dx = swipe.end.x - swipe.start.x
            val dy = swipe.end.y - swipe.start.y
            when (action) {
                SystemAction.SWIPE_LEFT -> assertEquals(-0.26f * (area.usable.width - 1), dx, 0.01f)
                SystemAction.SWIPE_RIGHT -> assertEquals(0.26f * (area.usable.width - 1), dx, 0.01f)
                SystemAction.SWIPE_UP -> assertEquals(-0.26f * (area.usable.height - 1), dy, 0.01f)
                SystemAction.SWIPE_DOWN -> assertEquals(0.26f * (area.usable.height - 1), dy, 0.01f)
                else -> error("Expected swipe")
            }
        }
    }

    @Test fun palmMappingMirrorsOnceAndRejectsOutsideRegionAndMismatchedRotation() {
        val mapper = DisplayCursorMapper(CursorConfig())
        for (rotation in 0..3) {
            val left = mapper.mapMotion(CursorPoint(0.7f, 0.3f), rotation, rotation)!!
            val right = mapper.mapMotion(CursorPoint(0.3f, 0.3f), rotation, rotation)!!
            assertTrue(left.x < right.x)
            assertEquals(left.y, right.y, 0f)
            assertNull(mapper.mapMotion(CursorPoint(0.05f, 0.5f), rotation, rotation))
            assertNull(mapper.mapMotion(CursorPoint(0.5f, 0.5f), rotation, (rotation + 1) % 4))
        }
    }

    @Test fun movementMappingUsesRegionWithoutCursorSensitivityOrEdgeClamping() {
        val config = CursorConfig(region = CursorRect(0.2f, 0.2f, 0.8f, 0.8f), sensitivity = 2f)
        val mapper = DisplayCursorMapper(config)
        assertEquals(0.25f, mapper.mapMotion(CursorPoint(0.65f, 0.35f), 0, 0)!!.x, 0.0001f)
        assertNull(mapper.mapMotion(CursorPoint(0.9f, 0.5f), 0, 0))
        assertNull(mapper.mapMotion(CursorPoint(Float.NaN, 0.5f), 0, 0))
    }
}
