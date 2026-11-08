package dev.gestureos.recognition

import org.junit.Assert.assertEquals
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
}
