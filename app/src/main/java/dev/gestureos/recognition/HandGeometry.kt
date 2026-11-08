package dev.gestureos.recognition

import kotlin.math.acos
import kotlin.math.sqrt

/** Isotropic coordinates: x/y/z must use the same units before evaluating angles. */
data class Point3(val x: Float, val y: Float, val z: Float = 0f) {
    operator fun minus(other: Point3) = Point3(x - other.x, y - other.y, z - other.z)
    fun length() = sqrt(x * x + y * y + z * z)
    fun distance(other: Point3) = (this - other).length()
    fun isFinite() = x.isFinite() && y.isFinite() && z.isFinite()
}

fun jointAngle(a: Point3, joint: Point3, b: Point3): Float {
    val u = a - joint
    val v = b - joint
    val denominator = u.length() * v.length()
    if (denominator < 1e-8f) return 0f
    val cosine = ((u.x * v.x + u.y * v.y + u.z * v.z) / denominator).coerceIn(-1f, 1f)
    return Math.toDegrees(acos(cosine).toDouble()).toFloat()
}

/** MediaPipe z is expressed on the same scale as normalized x, not normalized y. */
fun isotropicLandmarks(normalized: List<Point3>, width: Int, height: Int): List<Point3> {
    require(width > 0 && height > 0)
    val aspect = height.toFloat() / width
    return normalized.map { Point3(it.x, it.y * aspect, it.z) }
}
