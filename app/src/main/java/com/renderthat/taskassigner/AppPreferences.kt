package com.renderthat.taskassigner

import android.content.Context
import android.content.SharedPreferences
import java.util.Calendar

/**
 * SharedPreferences helper to store and manage user configuration:
 * - Target tasks (default 3, max 5)
 * - Work hour start (default 10) and end (default 19)
 * - Workdays (default Mon-Fri)
 * - Mode preference (Simulation vs Live)
 * - Sound & Vibration alert toggles
 */
object AppPreferences {

    private const val PREFS_NAME = "renderthat_task_assigner_prefs"

    private const val KEY_TARGET_TASKS = "pref_target_tasks"
    private const val KEY_WORK_HOUR_START = "pref_work_hour_start"
    private const val KEY_WORK_HOUR_END = "pref_work_hour_end"
    private const val KEY_WORK_DAYS = "pref_work_days"
    private const val KEY_SIMULATION_MODE = "pref_simulation_mode"
    private const val KEY_SOUND_ENABLED = "pref_sound_enabled"
    private const val KEY_VIBRATION_ENABLED = "pref_vibration_enabled"

    const val DEFAULT_TARGET_TASKS = 3
    const val MIN_TARGET_TASKS = 1
    const val MAX_TARGET_TASKS = 5

    const val DEFAULT_WORK_HOUR_START = 10
    const val DEFAULT_WORK_HOUR_END = 19

    val DEFAULT_WORK_DAYS: Set<Int> = setOf(
        Calendar.MONDAY,
        Calendar.TUESDAY,
        Calendar.WEDNESDAY,
        Calendar.THURSDAY,
        Calendar.FRIDAY
    )

    private fun getPrefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Target tasks: default 3, max 5, min 1
     */
    fun getTargetTasks(context: Context): Int {
        val count = getPrefs(context).getInt(KEY_TARGET_TASKS, DEFAULT_TARGET_TASKS)
        return count.coerceIn(MIN_TARGET_TASKS, MAX_TARGET_TASKS)
    }

    fun setTargetTasks(context: Context, count: Int) {
        val clamped = count.coerceIn(MIN_TARGET_TASKS, MAX_TARGET_TASKS)
        getPrefs(context).edit().putInt(KEY_TARGET_TASKS, clamped).apply()
        TaskManager.minTargetTasks = clamped
    }

    /**
     * Work hour start: default 10 (0..23)
     */
    fun getWorkHourStart(context: Context): Int {
        return getPrefs(context).getInt(KEY_WORK_HOUR_START, DEFAULT_WORK_HOUR_START).coerceIn(0, 23)
    }

    fun setWorkHourStart(context: Context, hour: Int) {
        val clamped = hour.coerceIn(0, 23)
        getPrefs(context).edit().putInt(KEY_WORK_HOUR_START, clamped).apply()
        TaskManager.workHourStart = clamped
    }

    /**
     * Work hour end: default 19 (0..23)
     */
    fun getWorkHourEnd(context: Context): Int {
        return getPrefs(context).getInt(KEY_WORK_HOUR_END, DEFAULT_WORK_HOUR_END).coerceIn(0, 23)
    }

    fun setWorkHourEnd(context: Context, hour: Int) {
        val clamped = hour.coerceIn(0, 23)
        getPrefs(context).edit().putInt(KEY_WORK_HOUR_END, clamped).apply()
        TaskManager.workHourEnd = clamped
    }

    /**
     * Workdays: default Mon-Fri (Calendar.MONDAY..Calendar.FRIDAY)
     */
    fun getWorkDays(context: Context): Set<Int> {
        val stringSet = getPrefs(context).getStringSet(KEY_WORK_DAYS, null)
        return if (stringSet == null) {
            DEFAULT_WORK_DAYS
        } else {
            stringSet.mapNotNull { it.toIntOrNull() }.toSet().ifEmpty { DEFAULT_WORK_DAYS }
        }
    }

    fun setWorkDays(context: Context, days: Set<Int>) {
        val stringSet = days.map { it.toString() }.toSet()
        getPrefs(context).edit().putStringSet(KEY_WORK_DAYS, stringSet).apply()
        TaskManager.workDays = days
    }

    /**
     * Mode preference: Simulation (true) vs Live (false). Default: true.
     */
    fun isSimulationMode(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SIMULATION_MODE, true)
    }

    fun setSimulationMode(context: Context, isSimulation: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SIMULATION_MODE, isSimulation).apply()
        WebViewHolder.setSimulationMode(isSimulation)
    }

    /**
     * Sound alerts toggle. Default: true.
     */
    fun isSoundEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SOUND_ENABLED, true)
    }

    fun setSoundEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SOUND_ENABLED, enabled).apply()
    }

    /**
     * Vibration alerts toggle. Default: true.
     */
    fun isVibrationEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_VIBRATION_ENABLED, true)
    }

    fun setVibrationEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_VIBRATION_ENABLED, enabled).apply()
    }

    /**
     * Synchronizes all stored user preferences into TaskManager and WebViewHolder.
     */
    fun applyToTaskManager(context: Context) {
        TaskManager.minTargetTasks = getTargetTasks(context)
        TaskManager.workHourStart = getWorkHourStart(context)
        TaskManager.workHourEnd = getWorkHourEnd(context)
        TaskManager.workDays = getWorkDays(context)
        val simMode = isSimulationMode(context)
        WebViewHolder.setSimulationMode(simMode)
    }
}
