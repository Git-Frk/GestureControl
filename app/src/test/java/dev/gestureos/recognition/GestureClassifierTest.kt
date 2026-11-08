package dev.gestureos.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class GestureClassifierTest {
    private val classifier = GestureClassifier()
    private val gestures = listOf(Gesture.OPEN_PALM, Gesture.CLOSED_FIST, Gesture.THUMBS_UP, Gesture.THUMBS_DOWN)

    @Test fun distinguishesAllFourPoses() {
        gestures.forEach { assertEquals(it, classifier.classify(HandFixtures.hand(it))) }
    }

    @Test fun supportsMirroredHandsAtDifferentDistances() {
        for (gesture in gestures) for (scale in listOf(0.25f, 0.6f, 1.5f)) {
            val mirrored = HandFixtures.hand(gesture).map { Point3(1f - it.x * scale, it.y * scale + 0.12f, it.z * scale) }
            assertEquals(gesture, classifier.classify(mirrored))
        }
    }

    @Test fun toleratesSmallLandmarkNoise() {
        val random = java.util.Random(23)
        repeat(30) {
            gestures.forEach { gesture ->
                val noisy = HandFixtures.hand(gesture).map {
                    Point3(it.x + (random.nextFloat() - 0.5f) * 0.008f,
                        it.y + (random.nextFloat() - 0.5f) * 0.008f, it.z)
                }
                assertEquals(gesture, classifier.classify(noisy))
            }
        }
    }

    @Test fun palmAndFistAreRotationInvariant() {
        for (gesture in listOf(Gesture.OPEN_PALM, Gesture.CLOSED_FIST)) {
            val angle = 1.2f
            val rotated = HandFixtures.hand(gesture).map {
                Point3(it.x * cos(angle) - it.y * sin(angle), it.x * sin(angle) + it.y * cos(angle), it.z)
            }
            assertEquals(gesture, classifier.classify(rotated))
        }
    }

    @Test fun horizontalThumbIsUnknown() {
        val hand = HandFixtures.hand(Gesture.THUMBS_UP).toMutableList()
        hand[3] = Point3(0.03f, 0.65f)
        hand[4] = Point3(-0.19f, 0.65f)
        assertEquals(Gesture.UNKNOWN, classifier.classify(hand))
    }

    @Test fun singleExtendedFingerIsNotFistOrPalm() {
        val hand = HandFixtures.hand(Gesture.CLOSED_FIST).toMutableList()
        val open = HandFixtures.hand(Gesture.OPEN_PALM)
        (5..8).forEach { hand[it] = open[it] }
        assertEquals(Gesture.UNKNOWN, classifier.classify(hand))
    }

    @Test fun noHandMalformedAndDegenerateInputsAreSafe() {
        assertEquals(Gesture.NO_HAND, classifier.classify(emptyList()))
        assertEquals(Gesture.UNKNOWN, classifier.classify(List(20) { Point3(0f, 0f) }))
        assertEquals(Gesture.UNKNOWN, classifier.classify(List(21) { Point3(0.5f, 0.5f) }))
        val invalid = HandFixtures.hand(Gesture.OPEN_PALM).toMutableList()
        invalid[7] = Point3(Float.NaN, 0f)
        assertEquals(Gesture.UNKNOWN, classifier.classify(invalid))
        invalid[7] = Point3(Float.POSITIVE_INFINITY, 0f)
        assertEquals(Gesture.UNKNOWN, classifier.classify(invalid))
        invalid[7] = invalid[6]
        assertEquals(Gesture.UNKNOWN, classifier.classify(invalid))
    }

    @Test fun aspectCorrectionPreservesPoseForPortraitAndLandscape() {
        for ((width, height) in listOf(480 to 640, 640 to 480, 640 to 360)) {
            for (gesture in gestures) {
                val normalized = HandFixtures.hand(gesture).map { Point3(it.x, it.y * width / height, it.z) }
                assertEquals(gesture, classifier.classify(isotropicLandmarks(normalized, width, height)))
            }
        }
    }

    @Test fun geometryHasExpectedStraightAndRightAngles() {
        assertEquals(180f, jointAngle(Point3(0f, 0f), Point3(1f, 0f), Point3(2f, 0f)), 0.01f)
        assertEquals(90f, jointAngle(Point3(0f, 0f), Point3(1f, 0f), Point3(1f, 1f)), 0.01f)
    }

    @Test fun thumbsAcceptFingersCurledAtTheDistalJointWithStraightMiddleJoint() {
        for (gesture in listOf(Gesture.THUMBS_UP, Gesture.THUMBS_DOWN)) {
            for (fingers in listOf(listOf(5), listOf(9), listOf(5, 9))) {
                val hand = HandFixtures.hand(gesture).toMutableList()
                for (mcp in fingers) {
                    val x = hand[mcp].x
                    hand[mcp + 2] = Point3(x, 0.30f)
                    hand[mcp + 3] = Point3(x + 0.03f, 0.34f, 0.02f)
                }
                assertEquals(gesture, classifier.classify(hand))
            }
        }
    }

    @Test fun verticalThumbWithStraightIndexFingerIsStillUnknown() {
        for (gesture in listOf(Gesture.THUMBS_UP, Gesture.THUMBS_DOWN)) {
            val hand = HandFixtures.hand(gesture).toMutableList()
            val open = HandFixtures.hand(Gesture.OPEN_PALM)
            (5..8).forEach { hand[it] = open[it] }
            assertEquals(Gesture.UNKNOWN, classifier.classify(hand))
        }
    }

    @Test fun slightlyBentDistalJointDoesNotCountAsFolded() {
        val hand = HandFixtures.hand(Gesture.THUMBS_DOWN).toMutableList()
        hand[7] = Point3(hand[5].x, 0.30f)
        // A 145-degree distal joint stays in the uncertainty band, above foldedAngle=125.
        val angle = Math.toRadians(35.0)
        hand[8] = Point3(hand[7].x + (0.1 * sin(angle)).toFloat(), hand[7].y - (0.1 * cos(angle)).toFloat())
        assertEquals(Gesture.UNKNOWN, classifier.classify(hand))
    }

    @Test fun capturedT20ThumbsDownRecognizesWithMirroringAndInversion() {
        val hand = RecordedHandFixtures.curledFingersThumbsDown
        assertEquals(Gesture.THUMBS_DOWN, classifier.classify(hand))
        assertEquals(Gesture.THUMBS_DOWN, classifier.classify(hand.map { it.copy(x = 1f - it.x) }))
        assertEquals(Gesture.THUMBS_UP, classifier.classify(hand.map { Point3(1f - it.x, 1f - it.y, it.z) }))
    }

    @Test fun capturedBentThumbsRemainRecognizableAtDifferentScalesAndOrientations() {
        for (hand in listOf(RecordedHandFixtures.bentThumbsDown, RecordedHandFixtures.bentThumbsDownNearEdge)) {
            for (scale in listOf(0.5f, 1f, 2f)) for (mirror in listOf(-1f, 1f)) {
                val transformed = hand.map { Point3(mirror * it.x * scale + 0.3f, it.y * scale, it.z * scale) }
                assertEquals(Gesture.THUMBS_DOWN, classifier.classify(transformed))
                assertEquals(Gesture.THUMBS_UP, classifier.classify(transformed.map { it.copy(y = -it.y) }))
            }
        }
    }

    @Test fun thumbCanBendTowardOrAwayFromCameraWhileClearlyOutsideFingers() {
        for (gesture in listOf(Gesture.THUMBS_UP, Gesture.THUMBS_DOWN)) for (depth in listOf(-0.14f, 0.14f)) {
            val hand = HandFixtures.hand(gesture).toMutableList()
            hand[4] = hand[4].copy(y = if (gesture == Gesture.THUMBS_UP) 0.32f else 0.98f, z = depth)
            assertEquals(gesture, classifier.classify(hand))
        }
    }

    @Test fun capturedFistsDoNotBecomeThumbGesturesWhenRotatedOrMirrored() {
        for (hand in listOf(RecordedHandFixtures.tuckedThumbFist, RecordedHandFixtures.frontFacingFist)) {
            for (angle in listOf(0f, 0.5f, 1.2f, 3.14159f)) for (mirror in listOf(-1f, 1f)) {
                val rotated = hand.map {
                    Point3(mirror * (it.x * cos(angle) - it.y * sin(angle)), it.x * sin(angle) + it.y * cos(angle), it.z)
                }
                assertEquals(Gesture.CLOSED_FIST, classifier.classify(rotated))
            }
        }
    }

    @Test fun bentSidewaysThumbDoesNotBecomeUpOrDown() {
        val hand = RecordedHandFixtures.bentThumbsDown.map { Point3(it.y, -it.x, it.z) }
        assertFalse(classifier.classify(hand) in listOf(Gesture.THUMBS_UP, Gesture.THUMBS_DOWN))
    }
}
