package dev.gestureos.motion

import dev.gestureos.recognition.GestureClassifier
import dev.gestureos.recognition.Point3
import dev.gestureos.recognition.RecognitionConfig
import dev.gestureos.recognition.jointAngle
import java.util.Locale

enum class MotionPose { NONE, NEUTRAL, SCROLL, NAVIGATION }

/** Strict finger counts and a tucked, separated thumb keep motion poses away from pinch contact. */
class MotionPoseDetector(
    private val recognition: RecognitionConfig = RecognitionConfig(),
    private val pinchRelease: Float = 0.45f,
) {
    /** Scalar geometry only, sampled by the debug adapter. No camera image or landmark recording. */
    fun describe(hand: List<Point3>): String {
        if (hand.size != 21 || hand.any { !it.isFinite() }) return "hand=missing-or-invalid"
        val palm = hand[5].distance(hand[17])
        if (palm < 1e-5f) return "hand=collapsed"
        val fingers = listOf(5, 9, 13, 17).joinToString(" ") { mcp ->
            String.format(Locale.ROOT, "%d=%.0f/%.0f/reach%.2f", mcp,
                jointAngle(hand[mcp], hand[mcp + 1], hand[mcp + 2]),
                jointAngle(hand[mcp + 1], hand[mcp + 2], hand[mcp + 3]),
                hand[mcp + 3].distance(hand[0]) / hand[mcp + 1].distance(hand[0]))
        }
        return String.format(Locale.ROOT, "%s thumbMcp=%.2f thumbIndex=%.2f thumbMiddle=%.2f straight=%.0f folded=%.0f",
            fingers, hand[4].distance(hand[5]) / palm, hand[4].distance(hand[8]) / palm,
            hand[4].distance(hand[12]) / palm, recognition.extendedAngle, recognition.foldedAngle)
    }

    fun detect(hand: List<Point3>): MotionPose {
        if (hand.size != 21 || hand.any { !it.isFinite() }) return MotionPose.NONE
        val palm = hand[5].distance(hand[17])
        if (palm < 1e-5f || GestureClassifier.BONES.any { (a, b) -> hand[a].distance(hand[b]) < palm * 0.01f }) {
            return MotionPose.NONE
        }
        fun extended(mcp: Int) = jointAngle(hand[mcp], hand[mcp + 1], hand[mcp + 2]) >= recognition.extendedAngle &&
            jointAngle(hand[mcp + 1], hand[mcp + 2], hand[mcp + 3]) >= recognition.extendedAngle &&
            hand[mcp + 3].distance(hand[0]) > hand[mcp + 1].distance(hand[0]) * recognition.tipReachRatio
        fun folded(mcp: Int) = !extended(mcp) && (
            jointAngle(hand[mcp], hand[mcp + 1], hand[mcp + 2]) <= recognition.foldedAngle ||
                jointAngle(hand[mcp + 1], hand[mcp + 2], hand[mcp + 3]) <= recognition.foldedAngle ||
                hand[mcp + 3].distance(hand[0]) < hand[mcp + 1].distance(hand[0]) * 0.95f)
        val thumbTucked = hand[4].distance(hand[5]) < palm * 0.95f
        if (!thumbTucked) return MotionPose.NONE
        if (listOf(5, 9, 13, 17).all(::folded)) return MotionPose.NEUTRAL
        if (hand[4].distance(hand[8]) / palm <= pinchRelease + 0.1f ||
            hand[4].distance(hand[12]) / palm <= pinchRelease + 0.1f) return MotionPose.NONE
        if (!extended(5) || !extended(9) || !folded(17)) return MotionPose.NONE
        return when {
            folded(13) -> MotionPose.SCROLL
            extended(13) -> MotionPose.NAVIGATION
            else -> MotionPose.NONE
        }
    }
}
