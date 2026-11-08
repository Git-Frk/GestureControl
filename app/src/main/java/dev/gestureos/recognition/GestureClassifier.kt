package dev.gestureos.recognition

import kotlin.math.abs
import kotlin.math.hypot

enum class Gesture { NO_HAND, UNKNOWN, OPEN_PALM, CLOSED_FIST, THUMBS_UP, THUMBS_DOWN }

/** Conservative geometric rules. Ambiguous/partial poses are UNKNOWN, never a fabricated score. */
class GestureClassifier(private val config: RecognitionConfig = RecognitionConfig()) {
    fun classify(hand: List<Point3>): Gesture {
        if (hand.isEmpty()) return Gesture.NO_HAND
        if (hand.size != 21 || hand.any { !it.isFinite() }) return Gesture.UNKNOWN
        val palmWidth = hand[5].distance(hand[17])
        if (palmWidth < 1e-5f) return Gesture.UNKNOWN
        // Reject degenerate joints instead of interpreting zero-length bones as folded fingers.
        if (BONES.any { (a, b) -> hand[a].distance(hand[b]) < palmWidth * 0.01f }) {
            return Gesture.UNKNOWN
        }
        val extended = FINGERS.map { mcp ->
            jointAngle(hand[mcp], hand[mcp + 1], hand[mcp + 2]) >= config.extendedAngle &&
                jointAngle(hand[mcp + 1], hand[mcp + 2], hand[mcp + 3]) >= config.extendedAngle &&
                hand[mcp + 3].distance(hand[0]) > hand[mcp + 1].distance(hand[0]) * config.tipReachRatio
        }
        val folded = FINGERS.map { mcp ->
            jointAngle(hand[mcp], hand[mcp + 1], hand[mcp + 2]) <= config.foldedAngle ||
                hand[mcp + 3].distance(hand[0]) < hand[mcp + 1].distance(hand[0]) * 0.95f
        }
        val thumbExtended = jointAngle(hand[2], hand[3], hand[4]) >= config.thumbAngle &&
            hand[4].distance(hand[5]) >= palmWidth * config.thumbReachRatio
        if (extended.all { it } && thumbExtended) return Gesture.OPEN_PALM
        if (folded.all { it }) {
            if (!thumbExtended) return Gesture.CLOSED_FIST
            // Up/down are relative to the upright display. Horizontal thumbs stay UNKNOWN.
            val thumb = hand[4] - hand[2]
            val projectedLength = hypot(thumb.x, thumb.y)
            if (projectedLength > palmWidth * 0.4f &&
                abs(thumb.y) / projectedLength >= config.verticalThumbRatio
            ) {
                return if (thumb.y < 0f) Gesture.THUMBS_UP else Gesture.THUMBS_DOWN
            }
        }
        return Gesture.UNKNOWN
    }

    companion object {
        private val FINGERS = listOf(5, 9, 13, 17)
        val BONES = listOf(
            0 to 1, 1 to 2, 2 to 3, 3 to 4,
            0 to 5, 5 to 6, 6 to 7, 7 to 8,
            5 to 9, 9 to 10, 10 to 11, 11 to 12,
            9 to 13, 13 to 14, 14 to 15, 15 to 16,
            13 to 17, 0 to 17, 17 to 18, 18 to 19, 19 to 20,
        )
    }
}
