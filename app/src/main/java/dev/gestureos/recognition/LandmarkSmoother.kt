package dev.gestureos.recognition

/** EMA for one hand. Reset on loss, long gaps, or large wrist jumps to avoid ghost hands. */
class LandmarkSmoother(private val config: RecognitionConfig = RecognitionConfig()) {
    private var previous: List<Point3> = emptyList()
    private var lastTimestamp: Long? = null

    fun apply(points: List<Point3>, timestampMs: Long): List<Point3> {
        if (points.any { !it.isFinite() }) {
            previous = emptyList()
            lastTimestamp = null
            return points
        }
        val gap = lastTimestamp?.let { timestampMs - it > config.maxObservationGapMs } ?: true
        val jump = previous.size == 21 && points.size == 21 &&
            points[0].distance(previous[0]) > points[5].distance(points[17]) * 1.5f
        val result = if (points.size != previous.size || gap || jump) points else {
            points.zip(previous) { next, old ->
                val a = config.smoothingAlpha
                Point3(old.x + a * (next.x - old.x), old.y + a * (next.y - old.y), old.z + a * (next.z - old.z))
            }
        }
        previous = result
        lastTimestamp = timestampMs
        return result
    }
}
