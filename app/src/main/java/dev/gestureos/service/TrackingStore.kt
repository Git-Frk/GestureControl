package dev.gestureos.service

import androidx.camera.view.TransformExperimental
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import dev.gestureos.camera.TrackingFrame

/** Process-local, latest-value stream. Retains landmarks/metrics only, never camera images. */
@androidx.annotation.OptIn(TransformExperimental::class)
object TrackingStore {
    internal val policy = TrackingLifecycle()
    private val mutableStatus = MutableLiveData(policy.status)
    private val mutableFrame = MutableLiveData<TrackingFrame?>(null)
    val status: LiveData<TrackingStatus> = mutableStatus
    val frames: LiveData<TrackingFrame?> = mutableFrame
    var uiVisible = false
        private set

    fun setUiVisible(visible: Boolean) { uiVisible = visible }
    internal fun publishStatus() { mutableStatus.value = policy.status }
    internal fun publishFrame(frame: TrackingFrame) { mutableFrame.value = frame }
    internal fun clearFrame() { mutableFrame.value = null }
}
