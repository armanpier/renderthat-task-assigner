package com.renderthat.taskassigner

import java.util.Calendar
import kotlin.random.Random

enum class MonitorState(val displayText: String) {
    IDLE("IDLE"),
    DORMANT_OFF_HOURS("DORMANT (Off-Hours)"),
    COLD_SEARCH("SEARCHING (60s)"),
    BURST_MODE("⚡ BURSTING (5s)"),
    BATCH_DEPLETED_STOPPED("🎯 BATCH EMPTY (Done)"),
    DAILY_MAX_REACHED("🎯 5 TASKS CLAIMED (Done for Today!)"),
    SESSION_EXPIRED("⚠️ SESSION EXPIRED")
}

enum class ClickOutcome {
    TASK_CLAIMED,
    TASK_CLAIMED_QUOTA_REACHED,
    MISS_CONTINUE_BURST,
    MISS_FELL_BACK_TO_COLD,
    MISS_BATCH_DEPLETED_STOPPED
}

object TaskManager {

    var minTargetTasks: Int = 3
    var maxDailyQuota: Int = 5
    var maxConsecutiveMisses: Int = 100

    var waitingForButtonReappearance: Boolean = false
        private set

    var buttonWasHiddenInStandby: Boolean = false
        private set

    // Configurable Cadence & Anti-Bot Timing Variety
    var coldIntervalSec: Int = 60
    var burstIntervalSec: Int = 5
    var jitterPercent: Int = 25
    var keepAliveIntervalMinutes: Int = 10

    var currentTasksCount: Int = 0
        private set

    var consecutiveMisses: Int = 0
        private set

    var totalClicksToday: Int = 0
        private set

    var currentState: MonitorState = MonitorState.IDLE
        private set

    var isRunning: Boolean = false
        private set

    var overrideWorkingHours: Boolean = false

    private var lastActiveDayOfYear: Int = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)

    // Working schedule defaults: Monday-Friday, 10:00 to 19:00
    var workHourStart: Int = 10
    var workHourEnd: Int = 19
    var workDays: Set<Int> = setOf(
        Calendar.MONDAY,
        Calendar.TUESDAY,
        Calendar.WEDNESDAY,
        Calendar.THURSDAY,
        Calendar.FRIDAY
    )

    fun start() {
        isRunning = true
        resetStandby()
        checkAndResetNewDay()
        updateStateBasedOnSchedule()
    }

    fun pause() {
        isRunning = false
        resetStandby()
        currentState = MonitorState.IDLE
    }

    fun resetDailyStats() {
        currentTasksCount = 0
        resetStandby()
        totalClicksToday = 0
        if (isRunning) {
            updateStateBasedOnSchedule()
        }
    }

    fun checkAndResetNewDay() {
        val today = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        if (today != lastActiveDayOfYear) {
            lastActiveDayOfYear = today
            resetDailyStats()
        }
    }

    fun isWithinSchedule(): Boolean {
        if (overrideWorkingHours) {
            return true
        }
        val now = Calendar.getInstance()
        val dayOfWeek = now.get(Calendar.DAY_OF_WEEK)
        if (!workDays.contains(dayOfWeek)) {
            return false
        }
        val hour = now.get(Calendar.HOUR_OF_DAY)
        return hour in workHourStart until workHourEnd
    }

    fun updateStateBasedOnSchedule() {
        if (!isRunning) {
            currentState = MonitorState.IDLE
            return
        }
        if (!isWithinSchedule()) {
            currentState = MonitorState.DORMANT_OFF_HOURS
            LiveLogger.log("TaskManager", "State -> DORMANT (Outside working hours 10-19. Enable '24/7 Override' to test now)")
            return
        }
        if (currentTasksCount >= maxDailyQuota) {
            currentState = MonitorState.DAILY_MAX_REACHED
            LiveLogger.log("TaskManager", "State -> DAILY_MAX_REACHED ($currentTasksCount/$maxDailyQuota tasks)")
            return
        }
        if (waitingForButtonReappearance) {
            currentState = MonitorState.BATCH_DEPLETED_STOPPED
            return
        }
        if (currentState == MonitorState.SESSION_EXPIRED) {
            return
        }

        val previous = currentState
        currentState = if (currentTasksCount > 0) {
            MonitorState.BURST_MODE
        } else {
            MonitorState.COLD_SEARCH
        }
        if (previous != currentState) {
            LiveLogger.log("TaskManager", "State transition: $previous -> $currentState")
        }
    }

    fun onSessionExpired() {
        currentState = MonitorState.SESSION_EXPIRED
    }

    fun onSessionRestored() {
        if (currentState == MonitorState.SESSION_EXPIRED) {
            updateStateBasedOnSchedule()
        }
    }

    /**
     * Called when DOM inspection reads task count outside of an immediate button click.
     * Returns true if task count increased (new tasks acquired externally).
     */
    fun onTaskCountInspected(newCount: Int): Boolean {
        val countIncreased = newCount > currentTasksCount
        if (countIncreased) {
            consecutiveMisses = 0
        }
        currentTasksCount = newCount

        if (currentTasksCount >= maxDailyQuota) {
            currentState = MonitorState.DAILY_MAX_REACHED
        } else if (currentTasksCount > 0 && currentState != MonitorState.BATCH_DEPLETED_STOPPED) {
            currentState = MonitorState.BURST_MODE
        } else if (currentTasksCount == 0 && currentState != MonitorState.DORMANT_OFF_HOURS && currentState != MonitorState.IDLE) {
            currentState = MonitorState.COLD_SEARCH
        }
        return countIncreased
    }

    /**
     * Records a click attempt and enforces the Batch Exhaustion Guard.
     */
    fun onButtonClickExecuted(countBefore: Int, countAfter: Int): ClickOutcome {
        totalClicksToday++
        currentTasksCount = countAfter

        if (countAfter > countBefore) {
            // Success: task claimed!
            consecutiveMisses = 0
            return if (currentTasksCount >= maxDailyQuota) {
                currentState = MonitorState.DAILY_MAX_REACHED
                ClickOutcome.TASK_CLAIMED_QUOTA_REACHED
            } else {
                currentState = MonitorState.BURST_MODE
                ClickOutcome.TASK_CLAIMED
            }
        } else {
            // No new task assigned on this click
            if (currentState == MonitorState.BURST_MODE) {
                consecutiveMisses++
                if (consecutiveMisses >= maxConsecutiveMisses) {
                    waitingForButtonReappearance = true
                    buttonWasHiddenInStandby = false
                    currentState = MonitorState.BATCH_DEPLETED_STOPPED
                    return ClickOutcome.MISS_BATCH_DEPLETED_STOPPED
                }
                return ClickOutcome.MISS_CONTINUE_BURST
            } else {
                // In cold search or other states, misses reset consecutive misses and remain cold
                consecutiveMisses = 0
                return ClickOutcome.MISS_FELL_BACK_TO_COLD
            }
        }
    }

    /**
     * Checks if the button has disappeared and then reappeared, or if new tasks were assigned,
     * while in standby mode after reaching max consecutive misses in turbo mode.
     * Returns true if auto-assign should resume.
     */
    fun checkButtonReappearance(buttonAvailable: Boolean, newCount: Int): Boolean {
        if (!waitingForButtonReappearance && currentState != MonitorState.BATCH_DEPLETED_STOPPED) {
            return false
        }

        // If new tasks appeared in the table, resume immediately
        if (newCount > currentTasksCount) {
            currentTasksCount = newCount
            resetStandby()
            updateStateBasedOnSchedule()
            return true
        }

        if (!buttonAvailable) {
            // Button is not currently available/visible -> mark that it was hidden
            buttonWasHiddenInStandby = true
            return false
        } else {
            // Button is available. If it was previously hidden during standby, it has now REAPPEARED!
            if (buttonWasHiddenInStandby) {
                resetStandby()
                updateStateBasedOnSchedule()
                return true
            }
            return false
        }
    }

    fun resetStandby() {
        consecutiveMisses = 0
        waitingForButtonReappearance = false
        buttonWasHiddenInStandby = false
        if (currentState == MonitorState.BATCH_DEPLETED_STOPPED) {
            currentState = if (currentTasksCount > 0) MonitorState.BURST_MODE else MonitorState.COLD_SEARCH
        }
    }

    /**
     * Applies configured random jitter percentage (+/- jitterPercent) to baseMs,
     * ensuring natural humanized timing variability to defeat anti-bot heuristics.
     */
    fun applyJitter(baseMs: Long, percent: Int = jitterPercent): Long {
        if (percent <= 0) return baseMs
        val span = (baseMs * (percent / 100.0)).toLong()
        if (span <= 0) return baseMs
        val offset = Random.nextLong(-span, span + 1)
        return (baseMs + offset).coerceAtLeast(1200L)
    }

    /**
     * Determines next polling delay in ms based on state machine and configurable jitter rules.
     */
    fun getNextDelayMs(): Long {
        if (!isRunning) return 5000L
        if (!isWithinSchedule()) {
            val keepAliveMs = (keepAliveIntervalMinutes * 60_000L).coerceAtLeast(60_000L)
            return applyJitter(keepAliveMs)
        }

        return when (currentState) {
            MonitorState.DAILY_MAX_REACHED, MonitorState.BATCH_DEPLETED_STOPPED -> {
                // Goal met or hard cap reached: check at relaxed cold interval
                applyJitter(coldIntervalSec * 1000L)
            }
            MonitorState.BURST_MODE -> {
                // Burst / Turbo mode: configurable burst interval + jitter
                applyJitter(burstIntervalSec * 1000L)
            }
            MonitorState.COLD_SEARCH -> {
                // Cold search: configurable cold interval + jitter
                applyJitter(coldIntervalSec * 1000L)
            }
            MonitorState.DORMANT_OFF_HOURS -> {
                val keepAliveMs = (keepAliveIntervalMinutes * 60_000L).coerceAtLeast(60_000L)
                applyJitter(keepAliveMs)
            }
            MonitorState.SESSION_EXPIRED -> 10_000L
            MonitorState.IDLE -> 5000L
        }
    }

    /**
     * Determines delay when the 'Assign me a Task' button has disappeared from the DOM:
     * - 15s (+ jitter) if current tasks > 0 (waiting for batch assignment to render or next slot)
     * - Configured coldIntervalSec (+ jitter) if 0 tasks
     */
    fun getDisappearanceWaitDelayMs(): Long {
        val baseMs = if (currentTasksCount > 0) 15_000L else (coldIntervalSec * 1000L)
        return applyJitter(baseMs)
    }
}
