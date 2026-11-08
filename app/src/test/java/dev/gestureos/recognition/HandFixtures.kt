package dev.gestureos.recognition

/** Synthetic 3D poses exercise geometry; these are not a substitute for recorded-device accuracy. */
internal object HandFixtures {
    fun hand(gesture: Gesture): List<Point3> {
        val p = MutableList(21) { Point3(0f, 0f) }
        p[0] = Point3(0.5f, 0.9f)
        val xs = listOf(0.35f, 0.47f, 0.59f, 0.71f)
        for ((finger, x) in xs.withIndex()) {
            val mcp = 5 + finger * 4
            p[mcp] = Point3(x, 0.6f)
            p[mcp + 1] = Point3(x, 0.43f)
            if (gesture == Gesture.OPEN_PALM) {
                p[mcp + 2] = Point3(x, 0.30f)
                p[mcp + 3] = Point3(x, 0.20f)
            } else {
                p[mcp + 2] = Point3(x, 0.52f, -0.06f)
                p[mcp + 3] = Point3(x, 0.65f, -0.08f)
            }
        }
        p[1] = Point3(0.37f, 0.78f)
        p[2] = Point3(0.25f, 0.65f)
        when (gesture) {
            Gesture.OPEN_PALM -> { p[3] = Point3(0.13f, 0.52f); p[4] = Point3(0.01f, 0.39f) }
            Gesture.THUMBS_UP -> { p[3] = Point3(0.25f, 0.43f); p[4] = Point3(0.25f, 0.21f) }
            Gesture.THUMBS_DOWN -> { p[3] = Point3(0.25f, 0.87f); p[4] = Point3(0.25f, 1.09f) }
            else -> { p[3] = Point3(0.31f, 0.51f); p[4] = Point3(0.46f, 0.58f, -0.06f) }
        }
        return p
    }
}
