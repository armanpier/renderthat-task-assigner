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
        TaskManager.coldIntervalSec = 60
        TaskManager.burstIntervalSec = 5
        TaskManager.jitterPercent = 25
        TaskManager.keepAliveIntervalMinutes = 10
        TaskManager.minTargetTasks = 3
        TaskManager.maxDailyQuota = 5
        TaskManager.maxConsecutiveMisses = 100
        TaskManager.overrideWorkingHours = false
    }

    @Test
    fun testColdSearchInitialState() {
        TaskManager.start()
        assertEquals(MonitorState.COLD_SEARCH, TaskManager.currentState)
        assertEquals(0, TaskManager.currentTasksCount)

        // 60s base with 25% jitter = 45s to 75s (45000ms to 75000ms)
        val delay = TaskManager.getNextDelayMs()
        assertTrue("Cold delay should be >= 45000, got $delay", delay >= 45_000L)
        assertTrue("Cold delay should be <= 75000, got $delay", delay <= 75_000L)
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

        // 5s base with 25% jitter = 3.75s to 6.25s (3750ms to 6250ms)
        val burstDelay = TaskManager.getNextDelayMs()
        assertTrue("Burst delay should be >= 3700, got $burstDelay", burstDelay >= 3700L)
        assertTrue("Burst delay should be <= 6300, got $burstDelay", burstDelay <= 6300L)
    }

    @Test
    fun testTurboModeTurnsOffAfter100AttemptsWithoutNewTasks() {
        TaskManager.start()
        // Claim 1 task to enter turbo / burst mode
        TaskManager.onButtonClickExecuted(0, 1)
        assertEquals(MonitorState.BURST_MODE, TaskManager.currentState)
        assertEquals(1, TaskManager.currentTasksCount)

        // 99 consecutive misses
        for (i in 1..99) {
            val outcome = TaskManager.onButtonClickExecuted(1, 1)
            assertEquals(ClickOutcome.MISS_CONTINUE_BURST, outcome)
            assertEquals(i, TaskManager.consecutiveMisses)
            assertEquals(MonitorState.BURST_MODE, TaskManager.currentState)
        }

        // 100th miss triggers turning off pushing until button appears again
        val finalOutcome = TaskManager.onButtonClickExecuted(1, 1)
        assertEquals(ClickOutcome.MISS_BATCH_DEPLETED_STOPPED, finalOutcome)
        assertEquals(MonitorState.BATCH_DEPLETED_STOPPED, TaskManager.currentState)
        assertEquals(100, TaskManager.consecutiveMisses)
        assertTrue(TaskManager.waitingForButtonReappearance)
    }

    @Test
    fun testButtonReappearanceResumesTurboMode() {
        TaskManager.start()
        TaskManager.maxConsecutiveMisses = 5 // Small threshold for testing
        TaskManager.onButtonClickExecuted(0, 2)
        assertEquals(MonitorState.BURST_MODE, TaskManager.currentState)

        // Exhaust 5 misses
        for (i in 1..5) {
            TaskManager.onButtonClickExecuted(2, 2)
        }
        assertEquals(MonitorState.BATCH_DEPLETED_STOPPED, TaskManager.currentState)
        assertTrue(TaskManager.waitingForButtonReappearance)

        // 1. In inspection: button is still visible -> standby continues
        val res1 = TaskManager.checkButtonReappearance(buttonAvailable = true, newCount = 2)
        assertFalse(res1)
        assertEquals(MonitorState.BATCH_DEPLETED_STOPPED, TaskManager.currentState)

        // 2. In inspection: button disappears -> recorded that it was hidden
        val res2 = TaskManager.checkButtonReappearance(buttonAvailable = false, newCount = 2)
        assertFalse(res2)
        assertTrue(TaskManager.buttonWasHiddenInStandby)
        assertEquals(MonitorState.BATCH_DEPLETED_STOPPED, TaskManager.currentState)

        // 3. In inspection: button appears AGAIN! -> triggers resume
        val res3 = TaskManager.checkButtonReappearance(buttonAvailable = true, newCount = 2)
        assertTrue(res3)
        assertEquals(MonitorState.BURST_MODE, TaskManager.currentState)
        assertEquals(0, TaskManager.consecutiveMisses)
        assertFalse(TaskManager.waitingForButtonReappearance)
    }

    @Test
    fun testNewTasksInStandbyResumesTurboMode() {
        TaskManager.start()
        TaskManager.maxConsecutiveMisses = 3
        TaskManager.onButtonClickExecuted(0, 1)

        for (i in 1..3) {
            TaskManager.onButtonClickExecuted(1, 1)
        }
        assertEquals(MonitorState.BATCH_DEPLETED_STOPPED, TaskManager.currentState)

        // External new task drop in table (1 -> 3 tasks)
        val res = TaskManager.checkButtonReappearance(buttonAvailable = true, newCount = 3)
        assertTrue(res)
        assertEquals(3, TaskManager.currentTasksCount)
        assertEquals(MonitorState.BURST_MODE, TaskManager.currentState)
        assertEquals(0, TaskManager.consecutiveMisses)
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

        // 0 tasks: wait 60s (+/- 25% jitter: 45000 to 75000)
        val coldWait = TaskManager.getDisappearanceWaitDelayMs()
        assertTrue("Cold disappearance wait >= 45000, got $coldWait", coldWait >= 45_000L)
        assertTrue("Cold disappearance wait <= 75000, got $coldWait", coldWait <= 75_000L)

        // 1+ tasks: wait 15s (+/- 25% jitter: 11250 to 18750)
        TaskManager.onButtonClickExecuted(0, 2)
        val burstWait = TaskManager.getDisappearanceWaitDelayMs()
        assertTrue("Burst disappearance wait >= 11000, got $burstWait", burstWait >= 11_000L)
        assertTrue("Burst disappearance wait <= 19000, got $burstWait", burstWait <= 19_000L)
    }

    @Test
    fun testConfigurableTimingAndCustomJitter() {
        TaskManager.coldIntervalSec = 30
        TaskManager.burstIntervalSec = 3
        TaskManager.jitterPercent = 10 // +/- 10%

        TaskManager.start()
        // Cold: 30s +/- 10% = 27s to 33s
        val coldDelay = TaskManager.getNextDelayMs()
        assertTrue("Custom cold delay >= 27000, got $coldDelay", coldDelay >= 27_000L)
        assertTrue("Custom cold delay <= 33000, got $coldDelay", coldDelay <= 33_000L)

        // Transition to burst
        TaskManager.onButtonClickExecuted(0, 1)
        // Burst: 3s +/- 10% = 2.7s to 3.3s
        val burstDelay = TaskManager.getNextDelayMs()
        assertTrue("Custom burst delay >= 2700, got $burstDelay", burstDelay >= 2700L)
        assertTrue("Custom burst delay <= 3300, got $burstDelay", burstDelay <= 3300L)
    }

    @Test
    fun testZeroJitterReturnsExactDelay() {
        TaskManager.coldIntervalSec = 45
        TaskManager.jitterPercent = 0 // Exactly 0%

        TaskManager.start()
        val exactDelay = TaskManager.getNextDelayMs()
        assertEquals(45_000L, exactDelay)
    }

    @Test
    fun testOffHoursScheduleDormancyAndKeepAliveDelay() {
        // Configure schedule to an impossible hour
        val nowHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        TaskManager.workHourStart = (nowHour + 2) % 24
        TaskManager.workHourEnd = (nowHour + 3) % 24
        TaskManager.keepAliveIntervalMinutes = 12
        TaskManager.jitterPercent = 0 // Exact calculation

        assertFalse(TaskManager.isWithinSchedule())

        TaskManager.start()
        assertEquals(MonitorState.DORMANT_OFF_HOURS, TaskManager.currentState)

        // In off hours, delay should match keep-alive interval: 12 minutes = 720,000 ms
        val offHoursDelay = TaskManager.getNextDelayMs()
        assertEquals(720_000L, offHoursDelay)

        // When override is active, ignores off-hours and goes into cold search
        TaskManager.overrideWorkingHours = true
        assertTrue(TaskManager.isWithinSchedule())
        TaskManager.updateStateBasedOnSchedule()
        assertEquals(MonitorState.COLD_SEARCH, TaskManager.currentState)
    }
}
