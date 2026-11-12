package dev.gestureos.accessibility

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import dev.gestureos.cursor.CursorPoint
import dev.gestureos.cursor.CursorState

data class SystemCursorStatus(
    val connected: Boolean = false,
    val overlayVisible: Boolean = false,
    val position: CursorPoint? = null,
    val state: CursorState = CursorState.IDLE,
    val progress: Float = 0f,
    val outcome: TapOutcome = TapOutcome.NONE,
    val latencyMs: Long? = null,
    val message: String = "",
    val interaction: String = "",
    val action: String = "",
    val actionOutcome: ActionOutcome = ActionOutcome.NONE,
    val actionFeedback: String = "",
)

object SystemCursorStore {
    private val mutableStatus = MutableLiveData(SystemCursorStatus())
    val status: LiveData<SystemCursorStatus> = mutableStatus
    val current get() = checkNotNull(mutableStatus.value)
    internal fun publish(status: SystemCursorStatus) { mutableStatus.value = status }
}
