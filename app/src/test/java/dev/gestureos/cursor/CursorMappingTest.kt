package dev.gestureos.cursor

import org.junit.Assert.*
import org.junit.Test

class CursorMappingTest {
    private val whole = CursorConfig(region = CursorRect(0f, 0f, 1f, 1f))
    private val identity = CursorTransform(1f, 0f, 0f, 0f, 1f, 0f)
    private fun near(expected: CursorPoint, actual: CursorPoint?) {
        assertNotNull(actual); assertEquals(expected.x, actual!!.x, 0.0001f); assertEquals(expected.y, actual.y, 0.0001f)
    }
    @Test fun frontMirrorAndLetterboxingUseVisibleImageNotViewBounds() {
        val transform = CursorTransform(-1f, 0f, 640f, 0f, 1f, 160f)
        val bounds = transform.visibleBounds(640f, 480f, 640f, 800f)!!
        assertEquals(CursorRect(0f, 160f, 640f, 640f), bounds)
        near(CursorPoint(0.75f, 0.25f), CursorMapper(whole).map(CursorPoint(160f, 120f), transform, bounds))
    }
    @Test fun ninetyAndTwoSeventyDegreeRotationMapCorrectly() {
        for ((transform, expected) in listOf(
            CursorTransform(0f, -1f, 480f, 1f, 0f, 0f) to CursorPoint(0.25f, 0.25f),
            CursorTransform(0f, 1f, 0f, -1f, 0f, 640f) to CursorPoint(0.75f, 0.75f),
        )) {
            val bounds = transform.visibleBounds(640f, 480f, 480f, 640f)!!
            near(expected, CursorMapper(whole).map(CursorPoint(160f, 360f), transform, bounds))
        }
    }
    @Test fun cropAndAspectFillIntersectTheVisibleViewport() {
        val transform = CursorTransform(2f, 0f, -140f, 0f, 2f, -80f)
        val bounds = transform.visibleBounds(640f, 480f, 1000f, 800f)!!
        assertEquals(CursorRect(0f, 0f, 1000f, 800f), bounds)
        near(CursorPoint(0f, 0f), CursorMapper(whole).map(CursorPoint(70f, 40f), transform, bounds))
        near(CursorPoint(1f, 1f), CursorMapper(whole).map(CursorPoint(570f, 440f), transform, bounds))
    }
    @Test fun activeRegionSensitivityAndClampingAreIndependentOfResolution() {
        val config = CursorConfig(sensitivity = 1.5f)
        for (scale in listOf(1f, 2f)) {
            val bounds = CursorRect(0f, 0f, 1000f * scale, 600f * scale)
            val mapper = CursorMapper(config)
            near(CursorPoint(0.5f, 0.5f), mapper.map(CursorPoint(500f * scale, 300f * scale), identity, bounds))
            near(CursorPoint(0f, 1f), mapper.map(CursorPoint(-100f, 10000f), identity, bounds))
            near(CursorPoint(0.875f, 0.5f), mapper.map(CursorPoint(675f * scale, 300f * scale), identity, bounds))
        }
    }
    @Test fun invalidAndUnavailableMappingDoesNotInventAPointer() {
        assertNull(identity.visibleBounds(0f, 100f, 100f, 100f))
        assertNull(CursorMapper(whole).map(CursorPoint(Float.NaN, 0f), identity, CursorRect(0f, 0f, 1f, 1f)))
    }
    @Test(expected = IllegalArgumentException::class) fun singularTransformRejected() { CursorTransform(0f, 0f, 0f, 0f, 1f, 0f) }
    @Test fun eachTargetHasADistinctHitAreaAndGapsDoNotSelect() {
        CursorTargets.bounds.forEachIndexed { index, rect ->
            assertEquals(index, CursorTargets.hitTest(CursorPoint((rect.left + rect.right) / 2, (rect.top + rect.bottom) / 2)))
        }
        assertNull(CursorTargets.hitTest(CursorPoint(0.5f, 0.5f)))
        assertNull(CursorTargets.hitTest(CursorPoint(-0.1f, 0.2f)))
    }
    @Test(expected = IllegalArgumentException::class) fun overlappingPinchThresholdsRejected() { CursorConfig(pinchEntry = 0.3f, pinchRelease = 0.35f) }
    @Test(expected = IllegalArgumentException::class) fun TinyRegionRejected() { CursorConfig(region = CursorRect(0.4f, 0.4f, 0.5f, 0.5f)) }
}
