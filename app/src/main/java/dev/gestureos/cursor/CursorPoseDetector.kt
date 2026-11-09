package dev.gestureos.cursor

import dev.gestureos.recognition.GestureClassifier
import dev.gestureos.recognition.Point3
import dev.gestureos.recognition.RecognitionConfig
import dev.gestureos.recognition.jointAngle

data class CursorPose(
    val tracked: Boolean = false,
    val pointing: Boolean = false,
    val pinchEligible: Boolean = false,
    val pinchRatio: Float? = null,
    val wrist: Point3? = null,
    val palmWidth: Float = 0f,
)

/** Separate from the four established gestures; geometry uses isotropic x/y/z units. */
class CursorPoseDetector(private val recognition: RecognitionConfig = RecognitionConfig()) {
    fun detect(hand: List<Point3>): CursorPose {
        if (hand.size != 21 || hand.any { !it.isFinite() }) return CursorPose()
        val palm = hand[5].distance(hand[17])
        if (palm < 1e-5f || GestureClassifier.BONES.any { (a, b) -> hand[a].distance(hand[b]) < palm * 0.01f }) return CursorPose()
        fun folded(mcp: Int) = jointAngle(hand[mcp], hand[mcp + 1], hand[mcp + 2]) <= recognition.foldedAngle ||
            jointAngle(hand[mcp + 1], hand[mcp + 2], hand[mcp + 3]) <= recognition.foldedAngle ||
            hand[mcp + 3].distance(hand[0]) < hand[mcp + 1].distance(hand[0]) * 0.95f
        val otherFingersCurled = listOf(9, 13, 17).all(::folded)
        val indexExtended = jointAngle(hand[5], hand[6], hand[7]) >= recognition.extendedAngle &&
            jointAngle(hand[6], hand[7], hand[8]) >= recognition.extendedAngle &&
            hand[8].distance(hand[0]) > hand[6].distance(hand[0]) * recognition.tipReachRatio
        val thumbCurled = jointAngle(hand[2], hand[3], hand[4]) < recognition.thumbAngle ||
            hand[4].distance(hand[5]) < palm * 0.95f
        // Contact tucked into the palm is a fist, not an intentional selection pose.
        val indexClearOfPalm = hand[8].distance(hand[5]) > palm * 0.4f &&
            jointAngle(hand[5], hand[6], hand[7]) >= 110f &&
            hand[8].distance(hand[0]) >= hand[6].distance(hand[0]) * 0.9f
        return CursorPose(true, otherFingersCurled && indexExtended && thumbCurled,
            indexClearOfPalm, hand[4].distance(hand[8]) / palm, hand[0], palm)
    }
}
