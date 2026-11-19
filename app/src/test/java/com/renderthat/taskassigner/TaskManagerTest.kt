package com.renderthat.taskassigner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

class TaskManagerTest {

    @Before
    fun setUp() {
        TaskManager.pause()
        TaskManager.resetDailyStats()
        // Default working hours for testing: 0 to 24 on all days
        TaskManager.workHourStart = 0
        TaskManager.workHourEnd = 24
        TaskManager.workDays = (1..7).toSet()
    }

    @Test
    fun testColdSearchInitialState() {
        TaskManager.start()
        assertEquals(MonitorState.COLD_SEARCH, TaskManager.currentState)
        assertEquals(0, TaskManager.currentTasksCount)

        val delay = TaskManager.getNextDelayMs()
        assertTrue("Cold delay should be >= 60000, got $delay", delay >= 60_000L)
        assertTrue("Cold delay should be <= 63000, got $delay", delay <= 63_000L)
    }

    @Test
    fun testTaskClaimedTransitionsToBurstMode() {
        TaskManager.start()
        assertEquals(MonitorState.COLD_SEARCH, TaskManager.currentState)

        val outcome = TaskManager.onButtonClickExecuted(0, 1)
        assertEquals(ClickOutcome.TASK_CLAIMED, outcome)
        assertEquals(MonitorState.BURST_MODE, TaskManager.currentState)
        assertEquals(1, TaskManager.currentTasksCount)
        assertEquals(0, TaskManager.consecutiveMisses)

        val burstDelay = TaskManager.getNextDelayMs()
        assertTrue("Burst delay should be >= 5000, got $burstDelay", burstDelay >= 5000L)
        assertTrue("Burst delay should be <= 6500, got $burstDelay", burstDelay <= 6500L)
    }

    @Test
    fun testBatchExhaustionGuardWithGoalReached() {
        TaskManager.start()
        // Claim 3 tasks first
        TaskManager.onButtonClickExecuted(0, 3)
        assertEquals(MonitorState.BURST_MODE, TaskManager.currentState)
        assertEquals(3, TaskManager.currentTasksCount)

        // 3 consecutive misses
        for (i in 1..3) {
            val outcome = TaskManager.onButtonClickExecuted(3, 3)
            assertEquals(ClickOutcome.MISS_CONTINUE_BURST, outcome)
            assertEquals(i, TaskManager.consecutiveMisses)
            assertEquals(MonitorState.BURST_MODE, TaskManager.currentState)
        }

        // 4th miss triggers Batch Exhaustion Guard -> BATCH_DEPLETED_STOPPED
        val finalOutcome = TaskManager.onButtonClickExecuted(3, 3)
        assertEquals(ClickOutcome.MISS_BATCH_DEPLETED_STOPPED, finalOutcome)
        assertEquals(MonitorState.BATCH_DEPLETED_STOPPED, TaskManager.currentState)
        assertEquals(4, TaskManager.consecutiveMisses)
    }

    @Test
    fun testBatchExhaustionGuardWithUnderThreeTasksFallsBackToColdSearch() {
        TaskManager.start()
        // Claim only 2 tasks
        TaskManager.onButtonClickExecuted(0, 2)
        assertEquals(MonitorState.BURST_MODE, TaskManager.currentState)
        assertEquals(2, TaskManager.currentTasksCount)

        // 3 consecutive misses
        for (i in 1..3) {
            val outcome = TaskManager.onButtonClickExecuted(2, 2)
            assertEquals(ClickOutcome.MISS_CONTINUE_BURST, outcome)
            assertEquals(i, TaskManager.consecutiveMisses)
        }

        // 4th miss triggers fallback to COLD_SEARCH because tasks < 3
        val finalOutcome = TaskManager.onButtonClickExecuted(2, 2)
        assertEquals(ClickOutcome.MISS_FELL_BACK_TO_COLD, finalOutcome)
        assertEquals(MonitorState.COLD_SEARCH, TaskManager.currentState)
        assertEquals(0, TaskManager.consecutiveMisses) // Resets for cold search
    }

    @Test
    fun testDailyHardCapAtFiveTasks() {
        TaskManager.start()
        // Claim 5 tasks
        val outcome = TaskManager.onButtonClickExecuted(4, 5)
        assertEquals(ClickOutcome.TASK_CLAIMED_QUOTA_REACHED, outcome)
        assertEquals(MonitorState.DAILY_MAX_REACHED, TaskManager.currentState)
        assertEquals(5, TaskManager.currentTasksCount)
    }

    @Test
    fun testDisappearanceWaitDelays() {
        TaskManager.start()

        // 0 tasks: wait 60s (+ jitter)
        val coldWait = TaskManager.getDisappearanceWaitDelayMs()
        assertTrue("Cold disappearance wait >= 60000, got $coldWait", coldWait >= 60_000L)
        assertTrue("Cold disappearance wait <= 63000, got $coldWait", coldWait <= 63_000L)

        // 1+ tasks: wait 15s (+ jitter)
        TaskManager.onButtonClickExecuted(0, 2)
        val burstWait = TaskManager.getDisappearanceWaitDelayMs()
        assertTrue("Burst disappearance wait >= 15000, got $burstWait", burstWait >= 15_000L)
        assertTrue("Burst disappearance wait <= 16500, got $burstWait", burstWait <= 16_500L)
    }

    @Test
    fun testOffHoursScheduleDormancy() {
        // Configure schedule to an impossible hour (e.g. 1 to 2 when now is outside)
        val nowHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        TaskManager.workHourStart = (nowHour + 2) % 24
        TaskManager.workHourEnd = (nowHour + 3) % 24

        assertFalse(TaskManager.isWithinSchedule())

        TaskManager.start()
        assertEquals(MonitorState.DORMANT_OFF_HOURS, TaskManager.currentState)
    }
}
