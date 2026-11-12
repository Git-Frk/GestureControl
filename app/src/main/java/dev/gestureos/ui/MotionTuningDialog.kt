package dev.gestureos.ui

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.gestureos.R
import dev.gestureos.interaction.InteractionModes
import dev.gestureos.motion.MotionConfig
import dev.gestureos.motion.MotionSettings

object MotionTuningDialog {
    fun show(context: Context) {
        InteractionModes.clear()
        val config = MotionSettings.load(context)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            addView(TextView(context).apply { setText(R.string.motion_tuning_help) })
        }
        fun field(label: Int, value: Number): EditText {
            val input = EditText(context).apply {
                id = View.generateViewId()
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setSingleLine()
                setText(value.toString())
                contentDescription = context.getString(label)
            }
            content.addView(TextView(context).apply { setText(label); labelFor = input.id })
            content.addView(input)
            return input
        }
        val confirmation = field(R.string.motion_confirmation_label, config.poseConfirmMs)
        val deadZone = field(R.string.motion_dead_zone_label, config.deadZone)
        val smoothing = field(R.string.motion_smoothing_label, config.smoothingMs)
        val sensitivity = field(R.string.motion_sensitivity_label, config.sensitivity)
        val threshold = field(R.string.motion_threshold_label, config.scrollThreshold)
        val distance = field(R.string.motion_distance_label, config.scrollDistance)
        val duration = field(R.string.motion_duration_label, config.swipeDurationMs)
        val repeat = field(R.string.motion_repeat_label, config.repeatIntervalMs)
        val navDistance = field(R.string.motion_navigation_distance_label, config.navigationDistance)
        val navMin = field(R.string.motion_navigation_min_label, config.navigationMinMs)
        val navMax = field(R.string.motion_navigation_max_label, config.navigationMaxMs)
        val navCooldown = field(R.string.motion_navigation_cooldown_label, config.navigationCooldownMs)
        val neutral = field(R.string.motion_neutral_label, config.neutralMs)
        val dialog = AlertDialog.Builder(context).setTitle(R.string.motion_tune)
            .setView(ScrollView(context).apply { addView(content) })
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.reset_defaults) { _, _ -> MotionSettings.save(context, MotionConfig()) }
            .setPositiveButton(R.string.apply, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                fun EditText.number() = text.toString().replace(',', '.').toFloat()
                fun EditText.duration() = text.toString().toLong()
                val updated = runCatching {
                    MotionConfig(confirmation.duration(), deadZone.number(), smoothing.number(), sensitivity.number(),
                        threshold.number(), distance.number(), duration.duration(), repeat.duration(), navDistance.number(),
                        navMin.duration(), navMax.duration(), navCooldown.duration(), neutral.duration())
                }.getOrNull()
                if (updated == null) confirmation.error = context.getString(R.string.motion_invalid_settings)
                else {
                    MotionSettings.save(context, updated)
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }
}
