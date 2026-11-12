package dev.gestureos.motion

import dev.gestureos.cursor.CursorPoseDetector
import dev.gestureos.recognition.Gesture
import dev.gestureos.recognition.HandFixtures
import dev.gestureos.recognition.Point3
import dev.gestureos.recognition.RecordedHandFixtures
import org.junit.Assert.*
import org.junit.Test

class MotionPoseDetectorTest {
    private val detector = MotionPoseDetector()

    private fun fingers(count: Int): MutableList<Point3> {
        val hand = HandFixtures.hand(Gesture.CLOSED_FIST).toMutableList()
        val open = HandFixtures.hand(Gesture.OPEN_PALM)
        for (index in 5 until 5 + count * 4) hand[index] = open[index]
        return hand
    }

    @Test fun twoExtendedFingersAndTuckedThumbRecogniseScroll() {
        assertEquals(MotionPose.SCROLL, detector.detect(fingers(2)))
    }

    @Test fun threeExtendedFingersRecogniseNavigation() {
        assertEquals(MotionPose.NAVIGATION, detector.detect(fingers(3)))
    }

    @Test fun scaleMirrorAndDepthTranslationPreserveFingerCount() {
        for (count in 2..3) for (scale in listOf(0.3f, 1f, 2f)) {
            val transformed = fingers(count).map { Point3(1f - it.x * scale, it.y * scale, it.z * scale + 0.2f) }
            assertEquals(if (count == 2) MotionPose.SCROLL else MotionPose.NAVIGATION, detector.detect(transformed))
        }
    }

    @Test fun closedFistIsExplicitNeutral() {
        assertEquals(MotionPose.NEUTRAL, detector.detect(fingers(0)))
    }

    @Test fun pointingPalmAndThumbsCannotScrollOrNavigate() {
        assertEquals(MotionPose.NONE, detector.detect(fingers(1)))
        for (gesture in listOf(Gesture.OPEN_PALM, Gesture.THUMBS_UP, Gesture.THUMBS_DOWN)) {
            assertEquals(MotionPose.NONE, detector.detect(HandFixtures.hand(gesture)))
        }
    }

    @Test fun contactAndExtendedThumbAreRejected() {
        for (count in 2..3) {
            val hand = fingers(count)
            hand[4] = hand[8].copy(x = hand[8].x + 0.01f)
            assertEquals(MotionPose.NONE, detector.detect(hand))
            val open = HandFixtures.hand(Gesture.OPEN_PALM)
            (1..4).forEach { hand[it] = open[it] }
            assertEquals(MotionPose.NONE, detector.detect(hand))
        }
    }

    @Test fun partiallyExtendedRingCannotChooseEitherMode() {
        val hand = fingers(2)
        hand[14] = Point3(0.59f, 0.43f)
        hand[15] = Point3(0.67f, 0.32f)
        hand[16] = Point3(0.72f, 0.25f)
        assertEquals(MotionPose.NONE, detector.detect(hand))
    }

    @Test fun littleFingerMustBeCurledForBothPoses() {
        val open = HandFixtures.hand(Gesture.OPEN_PALM)
        for (count in 2..3) {
            val hand = fingers(count)
            (17..20).forEach { hand[it] = open[it] }
            assertEquals(MotionPose.NONE, detector.detect(hand))
        }
    }

    @Test fun malformedNonfiniteAndCollapsedHandsAreRejected() {
        assertEquals(MotionPose.NONE, detector.detect(emptyList()))
        assertEquals(MotionPose.NONE, detector.detect(List(21) { Point3(0f, 0f) }))
        val hand = fingers(2)
        hand[8] = Point3(Float.NaN, 0f)
        assertEquals(MotionPose.NONE, detector.detect(hand))
    }

    @Test fun motionPosesCannotArmExistingPinch() {
        for (count in 2..3) assertFalse(CursorPoseDetector().detect(fingers(count)).pointing)
    }

    @Test fun recordedNormalFistsCanRearmNavigation() {
        for (hand in listOf(RecordedHandFixtures.frontFacingFist, RecordedHandFixtures.tuckedThumbFist)) {
            assertEquals(MotionPose.NEUTRAL, detector.detect(hand))
        }
    }
}
