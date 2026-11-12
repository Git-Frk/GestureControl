package dev.gestureos.accessibility

/** A transport reservation survives logical cancellation until Android returns its callback. */
class DispatchGuard {
    private var sequence = 0L
    private var token: Long? = null
    private var valid = false
    private var startedAtMs = 0L
    val busy get() = token != null

    fun reserve(nowMs: Long = 0): Long? {
        if (busy) return null
        startedAtMs = nowMs
        return (++sequence).also { token = it; valid = true }
    }

    fun timedOut(nowMs: Long) = busy && nowMs - startedAtMs > 1000

    fun invalidate() { valid = false }

    fun complete(completedToken: Long): Boolean {
        if (token != completedToken) return false
        token = null
        return valid.also { valid = false }
    }
}
