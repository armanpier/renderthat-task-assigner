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
    var maxConsecutiveMisses: Int = 4

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
        consecutiveMisses = 0
        checkAndResetNewDay()
        updateStateBasedOnSchedule()
    }

    fun pause() {
        isRunning = false
        currentState = MonitorState.IDLE
    }

    fun resetDailyStats() {
        currentTasksCount = 0
        consecutiveMisses = 0
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
            return
        }
        if (currentTasksCount >= maxDailyQuota) {
            currentState = MonitorState.DAILY_MAX_REACHED
            return
        }
        if (currentState == MonitorState.BATCH_DEPLETED_STOPPED) {
            return
        }
        if (currentState == MonitorState.SESSION_EXPIRED) {
            return
        }

        currentState = if (currentTasksCount > 0) {
            MonitorState.BURST_MODE
        } else {
            MonitorState.COLD_SEARCH
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
                    return if (currentTasksCount >= minTargetTasks) {
                        // Batch Exhaustion Guard: 4 misses and >= 3 tasks -> goal met!
                        currentState = MonitorState.BATCH_DEPLETED_STOPPED
                        ClickOutcome.MISS_BATCH_DEPLETED_STOPPED
                    } else {
                        // Batch Exhaustion Guard: 4 misses and < 3 tasks -> fallback to 60s cold search
                        consecutiveMisses = 0
                        currentState = MonitorState.COLD_SEARCH
                        ClickOutcome.MISS_FELL_BACK_TO_COLD
                    }
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
     * Determines next polling delay in ms based on state machine and jitter rules.
     */
    fun getNextDelayMs(): Long {
        if (!isRunning) return 5000L
        if (!isWithinSchedule()) return 60_000L

        return when (currentState) {
            MonitorState.DAILY_MAX_REACHED, MonitorState.BATCH_DEPLETED_STOPPED -> {
                // Goal met or hard cap reached: check at relaxed 60s interval
                60_000L
            }
            MonitorState.BURST_MODE -> {
                // Burst mode: 5s + jitter (500 - 1500 ms)
                5000L + Random.nextLong(500, 1501)
            }
            MonitorState.COLD_SEARCH -> {
                // Cold search: 60s + jitter (1000 - 3000 ms)
                60_000L + Random.nextLong(1000, 3001)
            }
            MonitorState.DORMANT_OFF_HOURS -> 60_000L
            MonitorState.SESSION_EXPIRED -> 10_000L
            MonitorState.IDLE -> 5000L
        }
    }

    /**
     * Determines delay when the 'Assign me a Task' button has disappeared from the DOM:
     * - 15s (+ jitter) if current tasks > 0 (waiting for batch assignment to render or next slot)
     * - 60s (+ jitter) if 0 tasks (standard cold wait)
     */
    fun getDisappearanceWaitDelayMs(): Long {
        return if (currentTasksCount > 0) {
            15_000L + Random.nextLong(500, 1501)
        } else {
            60_000L + Random.nextLong(1000, 3001)
        }
    }
}
