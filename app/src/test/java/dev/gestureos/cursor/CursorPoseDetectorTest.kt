package dev.gestureos.cursor

import dev.gestureos.recognition.Gesture
import dev.gestureos.recognition.HandFixtures
import dev.gestureos.recognition.Point3
import dev.gestureos.recognition.RecordedHandFixtures
import org.junit.Assert.*
import org.junit.Test

class CursorPoseDetectorTest {
    private val detector = CursorPoseDetector()
    private fun pointing(): MutableList<Point3> {
        val hand = HandFixtures.hand(Gesture.CLOSED_FIST).toMutableList()
        val palm = HandFixtures.hand(Gesture.OPEN_PALM)
        (5..8).forEach { hand[it] = palm[it] }
        return hand
    }
    @Test fun extendedIndexAndCurledOthersArePointing() { assertTrue(detector.detect(pointing()).pointing) }
    @Test fun palmFistThumbsAndTwoFingersCannotArmTheCursor() {
        for (gesture in listOf(Gesture.OPEN_PALM, Gesture.CLOSED_FIST, Gesture.THUMBS_UP, Gesture.THUMBS_DOWN)) {
            assertFalse(detector.detect(HandFixtures.hand(gesture)).pointing)
        }
        val hand = pointing(); val open = HandFixtures.hand(Gesture.OPEN_PALM)
        (9..12).forEach { hand[it] = open[it] }
        assertFalse(detector.detect(hand).pointing)
        val machine = CursorStateMachine()
        for (time in listOf(0L, 200L, 400L)) {
            assertFalse(machine.update(detector.detect(hand), CursorPoint(0.3f, 0.3f), time, time).armed)
        }
    }
    @Test fun pinchDistanceUsesPalmScaleInAllThreeDimensions() {
        val hand = pointing()
        hand[4] = hand[8].copy(x = hand[8].x + 0.03f, z = 0.04f)
        val expected = 0.05f / hand[5].distance(hand[17])
        for (scale in listOf(0.3f, 1f, 2f)) {
            val pose = detector.detect(hand.map { Point3(1f - it.x * scale, it.y * scale, it.z * scale) })
            assertTrue(pose.pinchEligible)
            assertEquals(expected, pose.pinchRatio!!, 0.0001f)
        }
    }
    @Test fun malformedOrCollapsedHandIsTrackingLoss() {
        assertFalse(detector.detect(emptyList()).tracked)
        assertFalse(detector.detect(List(21) { Point3(0f, 0f) }).tracked)
        val hand = pointing(); hand[8] = hand[8].copy(x = Float.NaN)
        assertFalse(detector.detect(hand).tracked)
    }
    @Test fun capturedThumbsAndFistsDoNotArmPointing() {
        for (hand in listOf(RecordedHandFixtures.curledFingersThumbsDown, RecordedHandFixtures.bentThumbsDown,
            RecordedHandFixtures.frontFacingFist, RecordedHandFixtures.tuckedThumbFist)) {
            assertFalse(detector.detect(hand).pointing)
        }
        assertFalse(detector.detect(HandFixtures.hand(Gesture.CLOSED_FIST)).pinchEligible)
        assertFalse(detector.detect(RecordedHandFixtures.frontFacingFist).pinchEligible)
        assertFalse(detector.detect(RecordedHandFixtures.tuckedThumbFist).pinchEligible)
    }

    @Test fun touchingTipsInsideAFullyFoldedFistCannotSelect() {
        val fist = HandFixtures.hand(Gesture.CLOSED_FIST).toMutableList()
        fist[4] = fist[8].copy(x = fist[8].x + 0.01f)
        assertTrue(detector.detect(fist).pinchRatio!! < CursorConfig().pinchEntry)
        assertFalse(detector.detect(fist).pinchEligible)
    }

    @Test fun pointingToThumbContactDrivesOneCompleteClick() {
        val machine = CursorStateMachine()
        val hand = pointing()
        val point = CursorPoint(0.3f, 0.3f)
        for (time in listOf(0L, 100L, 200L)) machine.update(detector.detect(hand), point, time, time)
        hand[4] = hand[8].copy(x = hand[8].x + 0.03f, z = 0.02f)
        assertFalse(machine.update(detector.detect(hand), CursorPoint(0.9f, 0.9f), 300, 300).clicked)
        val click = machine.update(detector.detect(hand), CursorPoint(0.9f, 0.9f), 600, 600)
        assertTrue(click.clicked); assertEquals(point, click.position)
        assertFalse(machine.update(detector.detect(hand), point, 700, 700).clicked)
    }

    @Test fun otherFingersMayRelaxDuringPinchAfterStablePointing() {
        val machine = CursorStateMachine()
        val point = CursorPoint(0.3f, 0.3f)
        for (time in listOf(0L, 100L, 200L)) machine.update(detector.detect(pointing()), point, time, time)
        val hand = HandFixtures.hand(Gesture.OPEN_PALM).toMutableList()
        hand[4] = hand[8].copy(x = hand[8].x + 0.03f, z = 0.02f)
        assertFalse(detector.detect(hand).pointing)
        assertTrue(detector.detect(hand).pinchEligible)
        machine.update(detector.detect(hand), CursorPoint(0.9f, 0.9f), 300, 300)
        val click = machine.update(detector.detect(hand), CursorPoint(0.9f, 0.9f), 600, 600)
        assertTrue(click.clicked)
        assertEquals(point, click.position)
        assertFalse(machine.update(detector.detect(hand), point, 700, 700).clicked)
        // Bringing an already-pinched hand into view still cannot arm or click.
        val fresh = CursorStateMachine()
        for (time in listOf(0L, 300L, 600L)) {
            assertFalse(fresh.update(detector.detect(hand), point, time, time).clicked)
        }
    }
}
