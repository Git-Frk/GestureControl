package dev.gestureos.service

enum class TrackingPhase { STOPPED, STARTING, RUNNING, STOPPING, ERROR }
enum class StopReason { INITIAL, USER, HIDDEN, RECONFIGURE, FAILURE, INTERRUPTED }
enum class StartDecision { START, ALREADY_ACTIVE, REJECTED }

data class TrackingStatus(
    val phase: TrackingPhase = TrackingPhase.STOPPED,
    val generation: Long = 0,
    val backgroundEnabled: Boolean = false,
    val stopReason: StopReason = StopReason.INITIAL,
    val error: String? = null,
) {
    val active get() = phase == TrackingPhase.STARTING || phase == TrackingPhase.RUNNING
}

/** Single-owner lifecycle policy. The service calls it on the main thread. */
class TrackingLifecycle {
    var status = TrackingStatus()
        private set

    fun start(visible: Boolean, cameraGranted: Boolean, background: Boolean): StartDecision {
        if (!visible || !cameraGranted || status.phase == TrackingPhase.STOPPING) return StartDecision.REJECTED
        if (status.active) {
            status = status.copy(backgroundEnabled = background)
            return StartDecision.ALREADY_ACTIVE
        }
        status = TrackingStatus(TrackingPhase.STARTING, status.generation + 1, background)
        return StartDecision.START
    }

    fun ready(generation: Long) {
        if (status.generation == generation && status.phase == TrackingPhase.STARTING) {
            status = status.copy(phase = TrackingPhase.RUNNING)
        }
    }

    fun accepts(generation: Long) = status.generation == generation && status.phase == TrackingPhase.RUNNING

    fun stop(reason: StopReason, error: String? = null): Long? {
        if (!status.active) return null
        status = status.copy(phase = TrackingPhase.STOPPING, generation = status.generation + 1,
            backgroundEnabled = false, stopReason = reason, error = error)
        return status.generation
    }

    fun released(generation: Long) {
        if (status.phase == TrackingPhase.STOPPING && status.generation == generation) {
            status = status.copy(phase = if (status.error == null) TrackingPhase.STOPPED else TrackingPhase.ERROR)
        }
    }

    fun shouldStopWhenHidden() = status.active && !status.backgroundEnabled
}
