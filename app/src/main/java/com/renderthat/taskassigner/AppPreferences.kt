package com.renderthat.taskassigner

import android.content.Context
import android.content.SharedPreferences
import java.util.Calendar

/**
 * SharedPreferences helper to store and manage user configuration:
 * - Target tasks (default 3, max 5)
 * - Work hour start (default 10) and end (default 19)
 * - Workdays (default Mon-Fri)
 * - Push cadence: Cold search interval (default 60s), Burst mode interval (default 5s)
 * - Anti-bot randomness / jitter variety (default 25%)
 * - Keep-alive off-hours ping interval (default 10m)
 * - Saved portal credentials & auto-login toggle
 * - Mode preference (Simulation vs Live)
 * - Sound & Vibration alert toggles
 */
object AppPreferences {

    private const val PREFS_NAME = "renderthat_task_assigner_prefs"

    private const val KEY_TARGET_TASKS = "pref_target_tasks"
    private const val KEY_MAX_DAILY_QUOTA = "pref_max_daily_quota"
    private const val KEY_MAX_CONSECUTIVE_MISSES = "pref_max_consecutive_misses"
    private const val KEY_WORK_HOUR_START = "pref_work_hour_start"
    private const val KEY_WORK_HOUR_END = "pref_work_hour_end"
    private const val KEY_WORK_DAYS = "pref_work_days"
    private const val KEY_SIMULATION_MODE = "pref_simulation_mode"
    private const val KEY_OVERRIDE_HOURS = "pref_override_hours"
    private const val KEY_SOUND_ENABLED = "pref_sound_enabled"
    private const val KEY_VIBRATION_ENABLED = "pref_vibration_enabled"

    // Timing & Anti-Bot Cadence Keys
    private const val KEY_COLD_INTERVAL_SEC = "pref_cold_interval_sec"
    private const val KEY_BURST_INTERVAL_SEC = "pref_burst_interval_sec"
    private const val KEY_JITTER_PERCENT = "pref_jitter_percent"
    private const val KEY_KEEP_ALIVE_MINUTES = "pref_keep_alive_minutes"

    // Credentials & Auto-Login Keys
    private const val KEY_SAVED_USERNAME = "pref_saved_username"
    private const val KEY_SAVED_PASSWORD = "pref_saved_password"
    private const val KEY_AUTO_LOGIN_ENABLED = "pref_auto_login_enabled"

    const val DEFAULT_TARGET_TASKS = 3
    const val MIN_TARGET_TASKS = 1
    const val MAX_TARGET_TASKS = 5
    const val DEFAULT_MAX_DAILY_QUOTA = 5

    const val DEFAULT_WORK_HOUR_START = 10
    const val DEFAULT_WORK_HOUR_END = 19

    const val DEFAULT_COLD_INTERVAL_SEC = 60
    const val DEFAULT_BURST_INTERVAL_SEC = 5
    const val DEFAULT_JITTER_PERCENT = 25
    const val DEFAULT_KEEP_ALIVE_MINUTES = 10
    const val DEFAULT_MAX_CONSECUTIVE_MISSES = 4

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

    fun getMaxDailyQuota(context: Context): Int {
        return getPrefs(context).getInt(KEY_MAX_DAILY_QUOTA, DEFAULT_MAX_DAILY_QUOTA).coerceIn(1, 10)
    }

    fun setMaxDailyQuota(context: Context, quota: Int) {
        val clamped = quota.coerceIn(1, 10)
        getPrefs(context).edit().putInt(KEY_MAX_DAILY_QUOTA, clamped).apply()
        TaskManager.maxDailyQuota = clamped
    }

    fun getMaxConsecutiveMisses(context: Context): Int {
        return getPrefs(context).getInt(KEY_MAX_CONSECUTIVE_MISSES, DEFAULT_MAX_CONSECUTIVE_MISSES).coerceIn(1, 10)
    }

    fun setMaxConsecutiveMisses(context: Context, misses: Int) {
        val clamped = misses.coerceIn(1, 10)
        getPrefs(context).edit().putInt(KEY_MAX_CONSECUTIVE_MISSES, clamped).apply()
        TaskManager.maxConsecutiveMisses = clamped
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
     * Cold search interval in seconds (default 60s, min 10s, max 600s)
     */
    fun getColdIntervalSec(context: Context): Int {
        return getPrefs(context).getInt(KEY_COLD_INTERVAL_SEC, DEFAULT_COLD_INTERVAL_SEC).coerceIn(10, 600)
    }

    fun setColdIntervalSec(context: Context, seconds: Int) {
        val clamped = seconds.coerceIn(10, 600)
        getPrefs(context).edit().putInt(KEY_COLD_INTERVAL_SEC, clamped).apply()
        TaskManager.coldIntervalSec = clamped
    }

    /**
     * Burst / turbo mode interval in seconds (default 5s, min 2s, max 60s)
     */
    fun getBurstIntervalSec(context: Context): Int {
        return getPrefs(context).getInt(KEY_BURST_INTERVAL_SEC, DEFAULT_BURST_INTERVAL_SEC).coerceIn(2, 60)
    }

    fun setBurstIntervalSec(context: Context, seconds: Int) {
        val clamped = seconds.coerceIn(2, 60)
        getPrefs(context).edit().putInt(KEY_BURST_INTERVAL_SEC, clamped).apply()
        TaskManager.burstIntervalSec = clamped
    }

    /**
     * Jitter / variety percentage (default 25%, min 0%, max 60%)
     */
    fun getJitterPercent(context: Context): Int {
        return getPrefs(context).getInt(KEY_JITTER_PERCENT, DEFAULT_JITTER_PERCENT).coerceIn(0, 60)
    }

    fun setJitterPercent(context: Context, percent: Int) {
        val clamped = percent.coerceIn(0, 60)
        getPrefs(context).edit().putInt(KEY_JITTER_PERCENT, clamped).apply()
        TaskManager.jitterPercent = clamped
    }

    /**
     * Keep-alive off-hours heartbeat interval in minutes (default 10m, min 2m, max 60m)
     */
    fun getKeepAliveMinutes(context: Context): Int {
        return getPrefs(context).getInt(KEY_KEEP_ALIVE_MINUTES, DEFAULT_KEEP_ALIVE_MINUTES).coerceIn(2, 60)
    }

    fun setKeepAliveMinutes(context: Context, minutes: Int) {
        val clamped = minutes.coerceIn(2, 60)
        getPrefs(context).edit().putInt(KEY_KEEP_ALIVE_MINUTES, clamped).apply()
        TaskManager.keepAliveIntervalMinutes = clamped
    }

    /**
     * Saved Credentials for Auto-Login
     */
    fun getSavedUsername(context: Context): String {
        return getPrefs(context).getString(KEY_SAVED_USERNAME, "") ?: ""
    }

    fun setSavedUsername(context: Context, username: String) {
        getPrefs(context).edit().putString(KEY_SAVED_USERNAME, username.trim()).apply()
    }

    fun getSavedPassword(context: Context): String {
        return getPrefs(context).getString(KEY_SAVED_PASSWORD, "") ?: ""
    }

    fun setSavedPassword(context: Context, password: String) {
        getPrefs(context).edit().putString(KEY_SAVED_PASSWORD, password).apply()
    }

    fun hasSavedCredentials(context: Context): Boolean {
        return getSavedUsername(context).isNotBlank() && getSavedPassword(context).isNotBlank()
    }

    fun isAutoLoginEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_AUTO_LOGIN_ENABLED, true)
    }

    fun setAutoLoginEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_AUTO_LOGIN_ENABLED, enabled).apply()
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
     * Override working hours toggle: When enabled, runs 24/7 ignoring the 10-19 schedule.
     */
    fun isOverrideHours(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_OVERRIDE_HOURS, false)
    }

    fun setOverrideHours(context: Context, override: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_OVERRIDE_HOURS, override).apply()
        TaskManager.overrideWorkingHours = override
        TaskManager.updateStateBasedOnSchedule()
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
        TaskManager.maxDailyQuota = getMaxDailyQuota(context)
        TaskManager.maxConsecutiveMisses = getMaxConsecutiveMisses(context)
        TaskManager.workHourStart = getWorkHourStart(context)
        TaskManager.workHourEnd = getWorkHourEnd(context)
        TaskManager.workDays = getWorkDays(context)
        TaskManager.overrideWorkingHours = isOverrideHours(context)
        TaskManager.coldIntervalSec = getColdIntervalSec(context)
        TaskManager.burstIntervalSec = getBurstIntervalSec(context)
        TaskManager.jitterPercent = getJitterPercent(context)
        TaskManager.keepAliveIntervalMinutes = getKeepAliveMinutes(context)

        val simMode = isSimulationMode(context)
        WebViewHolder.setSimulationMode(simMode)
    }
}
