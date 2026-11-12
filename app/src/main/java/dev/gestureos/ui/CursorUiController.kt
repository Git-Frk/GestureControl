package dev.gestureos.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.camera.view.TransformExperimental
import androidx.core.content.edit
import dev.gestureos.R
import dev.gestureos.camera.TrackingFrame
import dev.gestureos.cursor.CursorSettings
import dev.gestureos.cursor.CursorConfig
import dev.gestureos.cursor.CursorMapper
import dev.gestureos.cursor.CursorPoint
import dev.gestureos.cursor.CursorPose
import dev.gestureos.cursor.CursorPoseDetector
import dev.gestureos.cursor.CursorRect
import dev.gestureos.cursor.CursorResult
import dev.gestureos.cursor.CursorStateMachine
import dev.gestureos.cursor.CursorState
import dev.gestureos.databinding.ActivityMainBinding
import dev.gestureos.recognition.RecognitionConfig
import dev.gestureos.recognition.isotropicLandmarks

/** Main-thread UI adapter; recognition/mapping/timing remain pure Kotlin. */
@androidx.annotation.OptIn(TransformExperimental::class)
class CursorUiController(
    private val activity: Activity,
    private val binding: ActivityMainBinding,
    private val onModeRequested: (Boolean) -> Unit,
    private val onTuning: () -> Unit,
) {
    private val preferences = activity.getSharedPreferences("cursor", Activity.MODE_PRIVATE)
    private var config = CursorSettings.load(activity)
    private var machine = CursorStateMachine(config)
    private var mapper = CursorMapper(config)
    private var detector = CursorPoseDetector()
    private var enabled = false
    private var renderingEnabled = false
    private var enabledAt = 0L
    private val latencies = ArrayDeque<Long>()
    private var lastState: String? = null

    init {
        binding.cursorEnabled.isChecked = false
        binding.cursorEnabled.isEnabled = false
        binding.cursorEnabled.setOnCheckedChangeListener { _, value ->
            if (!renderingEnabled) onModeRequested(value)
        }
        binding.cursorTune.setOnClickListener { showTuning() }
        binding.cursorArea.onGeometryChanged = { resetTracking() }
        binding.cursorArea.onSelection = { target, count ->
            binding.cursorFeedback.text = activity.getString(R.string.cursor_selected, target, count)
            Log.i("GestureOSCursor", "Selected target=$target; total=$count")
        }
        binding.cursorArea.onCursorDraw = { latency ->
            if (enabled && latency in 0..2000) {
                latencies.addLast(latency)
                if (latencies.size > 30) latencies.removeFirst()
                binding.cursorLatency.text = activity.getString(R.string.cursor_latency, latency, latencies.average())
            }
        }
    }

    fun cameraReady(recognition: RecognitionConfig) {
        detector = CursorPoseDetector(recognition)
        binding.cursorEnabled.isEnabled = true
    }

    fun setEnabled(value: Boolean) {
        renderingEnabled = true
        binding.cursorEnabled.isChecked = value
        renderingEnabled = false
        if (enabled == value) return
        enabled = value
        enabledAt = SystemClock.uptimeMillis()
        resetTracking()
    }

    fun disable() {
        onModeRequested(false)
        setEnabled(false)
    }

    fun cameraStopped() { disable(); binding.cursorEnabled.isEnabled = false }

    fun resetTracking() {
        machine.reset()
        latencies.clear()
        lastState = null
        binding.cursorArea.show(CursorResult())
        binding.overlay.setActiveRegion(null)
        binding.cursorStatus.setText(if (enabled) R.string.cursor_ready else R.string.cursor_off)
        binding.cursorLatency.setText(R.string.cursor_latency_idle)
    }

    fun expire(nowMs: Long) {
        if (enabled) {
            val result = machine.expire(nowMs)
            if (result.state == CursorState.TRACKING_LOST && lastState != result.state.name) render(result)
        }
    }

    fun onFrame(frame: TrackingFrame, projection: PreviewProjection?) {
        if (!enabled || frame.observedAtMs < enabledAt) return
        val valid = frame.landmarks.size == 21 && frame.landmarks.all { it.isFinite() && it.x in 0f..1f && it.y in 0f..1f }
        val pose = if (valid) detector.detect(isotropicLandmarks(frame.landmarks, frame.width, frame.height)) else CursorPose()
        val mapped = if (valid && projection != null) {
            val tip = frame.landmarks[8]
            mapper.map(CursorPoint(tip.x * frame.width, tip.y * frame.height), projection.transform, projection.visibleBounds)
        } else null
        binding.overlay.setActiveRegion(projection?.visibleBounds?.region(config.region))
        val result = machine.update(pose, mapped, frame.observedAtMs, SystemClock.uptimeMillis())
        render(result, frame.observedAtMs)
        if (result.clicked && result.position != null) {
            if (!binding.cursorArea.select(result.position)) {
                binding.cursorFeedback.setText(R.string.cursor_missed)
                Log.i("GestureOSCursor", "Confirmed pinch outside targets")
            }
        }
    }

    private fun render(result: CursorResult, frameAtMs: Long? = null) {
        binding.cursorArea.show(result, frameAtMs)
        val position = result.position?.let { activity.getString(R.string.cursor_position, it.x * 100, it.y * 100) }
            ?: activity.getString(R.string.cursor_hidden)
        val ratio = result.pinchRatio?.let { activity.getString(R.string.cursor_ratio, it) } ?: "—"
        binding.cursorStatus.text = activity.getString(R.string.cursor_status, result.state.name, position, ratio,
            (result.progress * 100).toInt(), activity.getString(if (result.armed) R.string.cursor_armed else R.string.cursor_not_armed))
        if (result.state.name != lastState) {
            lastState = result.state.name
            Log.d("GestureOSCursor", "State=${result.state}; armed=${result.armed}")
        }
    }

    private fun applyConfig(updated: CursorConfig) {
        config = updated
        machine = CursorStateMachine(config)
        mapper = CursorMapper(config)
        preferences.edit {
            putFloat("sensitivity", config.sensitivity); putFloat("smoothing", config.smoothingMs); putFloat("deadZone", config.deadZone)
            putFloat("left", config.region.left); putFloat("top", config.region.top)
            putFloat("right", config.region.right); putFloat("bottom", config.region.bottom)
            putFloat("entry", config.pinchEntry); putFloat("release", config.pinchRelease)
            putLong("arm", config.pointArmMs); putLong("debounce", config.pinchDebounceMs); putLong("confirm", config.pinchConfirmMs)
            putLong("releaseMs", config.releaseMs); putLong("cooldown", config.clickCooldownMs)
        }
        resetTracking()
    }

    private fun showTuning() {
        disable()
        onTuning()
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            addView(TextView(context).apply { setText(R.string.cursor_tuning_help) })
        }
        fun field(label: Int, value: String): EditText {
            val input = EditText(activity).apply {
                id = android.view.View.generateViewId()
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setSingleLine(); setText(value); contentDescription = activity.getString(label)
            }
            content.addView(TextView(activity).apply { setText(label); labelFor = input.id })
            content.addView(input)
            return input
        }
        val sensitivity = field(R.string.cursor_sensitivity_label, config.sensitivity.toString())
        val smoothing = field(R.string.cursor_smoothing_label, config.smoothingMs.toString())
        val deadZone = field(R.string.cursor_dead_zone_label, config.deadZone.toString())
        val left = field(R.string.cursor_left_label, config.region.left.toString())
        val top = field(R.string.cursor_top_label, config.region.top.toString())
        val right = field(R.string.cursor_right_label, config.region.right.toString())
        val bottom = field(R.string.cursor_bottom_label, config.region.bottom.toString())
        val entry = field(R.string.cursor_entry_label, config.pinchEntry.toString())
        val release = field(R.string.cursor_release_label, config.pinchRelease.toString())
        val arm = field(R.string.cursor_arm_label, config.pointArmMs.toString())
        val debounce = field(R.string.cursor_debounce_label, config.pinchDebounceMs.toString())
        val confirm = field(R.string.cursor_confirm_label, config.pinchConfirmMs.toString())
        val releaseMs = field(R.string.cursor_release_ms_label, config.releaseMs.toString())
        val cooldown = field(R.string.cursor_cooldown_label, config.clickCooldownMs.toString())
        val dialog = AlertDialog.Builder(activity).setTitle(R.string.cursor_tune)
            .setView(ScrollView(activity).apply { addView(content) })
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.reset_defaults) { _, _ -> applyConfig(CursorConfig()) }
            .setPositiveButton(R.string.apply, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                fun EditText.number() = text.toString().replace(',', '.').toFloat()
                val updated = runCatching {
                    config.copy(sensitivity = sensitivity.number(), smoothingMs = smoothing.number(), deadZone = deadZone.number(),
                        region = CursorRect(left.number(), top.number(), right.number(), bottom.number()),
                        pinchEntry = entry.number(), pinchRelease = release.number(),
                        pointArmMs = arm.text.toString().toLong(), pinchDebounceMs = debounce.text.toString().toLong(),
                        pinchConfirmMs = confirm.text.toString().toLong(), releaseMs = releaseMs.text.toString().toLong(),
                        clickCooldownMs = cooldown.text.toString().toLong())
                }.getOrNull()
                if (updated == null) sensitivity.error = activity.getString(R.string.invalid_settings)
                else { applyConfig(updated); dialog.dismiss() }
            }
        }
        dialog.show()
    }
}
