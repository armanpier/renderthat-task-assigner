package com.renderthat.taskassigner

import android.content.Context
import android.view.LayoutInflater
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.renderthat.taskassigner.databinding.DialogSettingsBinding

object SettingsDialog {

    fun show(context: Context, onDismiss: (() -> Unit)? = null) {
        val binding = DialogSettingsBinding.inflate(LayoutInflater.from(context))
        val dialog = MaterialAlertDialogBuilder(context, R.style.Theme_RenderThatTaskAssigner)
            .setView(binding.root)
            .setCancelable(true)
            .create()

        // 1. Populate current values
        binding.etColdInterval.setText(AppPreferences.getColdIntervalSec(context).toString())
        binding.etBurstInterval.setText(AppPreferences.getBurstIntervalSec(context).toString())
        binding.etJitterPercent.setText(AppPreferences.getJitterPercent(context).toString())
        binding.etKeepAliveMinutes.setText(AppPreferences.getKeepAliveMinutes(context).toString())

        binding.etTargetTasks.setText(AppPreferences.getTargetTasks(context).toString())
        binding.etDailyCap.setText(AppPreferences.getMaxDailyQuota(context).toString())
        binding.etMissesLimit.setText(AppPreferences.getMaxConsecutiveMisses(context).toString())

        binding.etStartHour.setText(AppPreferences.getWorkHourStart(context).toString())
        binding.etEndHour.setText(AppPreferences.getWorkHourEnd(context).toString())
        binding.swOverrideHours.isChecked = AppPreferences.isOverrideHours(context)

        binding.etUsername.setText(AppPreferences.getSavedUsername(context))
        binding.etPassword.setText(AppPreferences.getSavedPassword(context))
        binding.swAutoLogin.isChecked = AppPreferences.isAutoLoginEnabled(context)

        // 2. Test Ding & Pop-Up Notification Button
        binding.btnTestDing.setOnClickListener {
            NotificationHelper.sendTaskAssignedDing(
                context,
                "🎉 Task Assigned! (Test)",
                "You now have 1 task assigned! (This is your ding and pop-up)"
            )
            Toast.makeText(context, "🔔 Sent test ding notification!", Toast.LENGTH_SHORT).show()
        }

        // 3. Cancel Button
        binding.btnCancelSettings.setOnClickListener {
            dialog.dismiss()
        }

        // 4. Save Button
        binding.btnSaveSettings.setOnClickListener {
            val coldSec = binding.etColdInterval.text.toString().toIntOrNull() ?: AppPreferences.DEFAULT_COLD_INTERVAL_SEC
            val burstSec = binding.etBurstInterval.text.toString().toIntOrNull() ?: AppPreferences.DEFAULT_BURST_INTERVAL_SEC
            val jitter = binding.etJitterPercent.text.toString().toIntOrNull() ?: AppPreferences.DEFAULT_JITTER_PERCENT
            val keepAlive = binding.etKeepAliveMinutes.text.toString().toIntOrNull() ?: AppPreferences.DEFAULT_KEEP_ALIVE_MINUTES

            val target = binding.etTargetTasks.text.toString().toIntOrNull() ?: AppPreferences.DEFAULT_TARGET_TASKS
            val dailyCap = binding.etDailyCap.text.toString().toIntOrNull() ?: AppPreferences.DEFAULT_MAX_DAILY_QUOTA
            val misses = binding.etMissesLimit.text.toString().toIntOrNull() ?: AppPreferences.DEFAULT_MAX_CONSECUTIVE_MISSES

            val startH = binding.etStartHour.text.toString().toIntOrNull() ?: AppPreferences.DEFAULT_WORK_HOUR_START
            val endH = binding.etEndHour.text.toString().toIntOrNull() ?: AppPreferences.DEFAULT_WORK_HOUR_END

            AppPreferences.setColdIntervalSec(context, coldSec)
            AppPreferences.setBurstIntervalSec(context, burstSec)
            AppPreferences.setJitterPercent(context, jitter)
            AppPreferences.setKeepAliveMinutes(context, keepAlive)

            AppPreferences.setTargetTasks(context, target)
            AppPreferences.setMaxDailyQuota(context, dailyCap)
            AppPreferences.setMaxConsecutiveMisses(context, misses)

            AppPreferences.setWorkHourStart(context, startH)
            AppPreferences.setWorkHourEnd(context, endH)
            AppPreferences.setOverrideHours(context, binding.swOverrideHours.isChecked)

            AppPreferences.setSavedUsername(context, binding.etUsername.text.toString())
            AppPreferences.setSavedPassword(context, binding.etPassword.text.toString())
            AppPreferences.setAutoLoginEnabled(context, binding.swAutoLogin.isChecked)

            AppPreferences.applyToTaskManager(context)

            LiveLogger.log(
                "Config",
                "⚙️ Settings updated: Cold=${coldSec}s (±${jitter}%), Burst=${burstSec}s, KeepAlive=${keepAlive}m, 24/7=${binding.swOverrideHours.isChecked}"
            )
            Toast.makeText(context, "Settings saved and applied!", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            onDismiss?.invoke()
        }

        dialog.show()
    }
}
