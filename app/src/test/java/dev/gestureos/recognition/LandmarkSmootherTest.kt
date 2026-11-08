package dev.gestureos.recognition

import org.junit.Assert.*
import org.junit.Test

class LandmarkSmootherTest {
    @Test fun attenuatesJitterAndCanBeDisabled() {
        val original = HandFixtures.hand(Gesture.OPEN_PALM)
        val shifted = original.map { it.copy(x = it.x + 0.04f) }
        val smoother = LandmarkSmoother(RecognitionConfig(smoothingAlpha = 0.5f))
        assertEquals(original, smoother.apply(original, 0))
        assertEquals(original[0].x + 0.02f, smoother.apply(shifted, 100)[0].x, 0.0001f)
        val disabled = LandmarkSmoother(RecognitionConfig(smoothingAlpha = 1f))
        disabled.apply(original, 0)
        assertEquals(shifted, disabled.apply(shifted, 100))
    }

    @Test fun lossGapAndNewHandResetSmoothing() {
        val original = HandFixtures.hand(Gesture.OPEN_PALM)
        val nearby = original.map { it.copy(x = it.x + 0.04f) }
        val far = original.map { it.copy(x = it.x + 0.8f) }
        val smoother = LandmarkSmoother()
        smoother.apply(original, 0)
        assertTrue(smoother.apply(emptyList(), 100).isEmpty())
        assertEquals(nearby, smoother.apply(nearby, 200))
        assertEquals(original, smoother.apply(original, 1000))
        assertEquals(far, smoother.apply(far, 1100))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidConfigurationRejected() { RecognitionConfig(smoothingAlpha = Float.NaN) }

    @Test fun invalidFrameDoesNotContaminateSubsequentFrames() {
        val smoother = LandmarkSmoother()
        val hand = HandFixtures.hand(Gesture.OPEN_PALM)
        smoother.apply(hand, 0)
        smoother.apply(hand.map { it.copy(x = Float.NaN) }, 100)
        assertEquals(hand, smoother.apply(hand, 200))
    }

    @Test(expected = IllegalArgumentException::class)
    fun overlappingFingerThresholdsRejected() { RecognitionConfig(extendedAngle = 140f, foldedAngle = 140f) }
}
