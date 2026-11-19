package com.renderthat.taskassigner

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.ServiceCompat

class TaskMonitorService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var isLoopScheduled = false

    companion object {
        const val ACTION_START = "com.renderthat.ACTION_START"
        const val ACTION_STOP = "com.renderthat.ACTION_STOP"
        const val ACTION_STATUS_UPDATE = "com.renderthat.ACTION_STATUS_UPDATE"
        const val ACTION_MANUAL_CLICK = "com.renderthat.ACTION_MANUAL_CLICK"
        const val ACTION_MANUAL_INSPECT = "com.renderthat.ACTION_MANUAL_INSPECT"

        var onStateChangeListener: (() -> Unit)? = null

        fun startService(context: Context) {
            val intent = Intent(context, TaskMonitorService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, TaskMonitorService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun triggerManualClick(context: Context) {
            val intent = Intent(context, TaskMonitorService::class.java).apply {
                action = ACTION_MANUAL_CLICK
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun triggerManualInspect(context: Context) {
            val intent = Intent(context, TaskMonitorService::class.java).apply {
                action = ACTION_MANUAL_INSPECT
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppPreferences.applyToTaskManager(this)
        NotificationHelper.createChannels(this)

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "RenderThat:TaskMonitorWakeLock"
        ).apply {
            setReferenceCounted(false)
        }

        // Initialize persistent WebView
        WebViewHolder.getOrCreateWebView(this)
        LiveLogger.log("Service", "TaskMonitorService created. Schedule: ${TaskManager.workHourStart}:00-${TaskManager.workHourEnd}:00 (Override: ${TaskManager.overrideWorkingHours})")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                LiveLogger.log("Service", "▶ START command received. Starting monitor loop...")
                TaskManager.start()
                startForegroundWithNotification()
                if (TaskManager.isWithinSchedule()) {
                    acquireWakeLock()
                }
                scheduleNextTick(1000L)
            }
            ACTION_STOP -> {
                LiveLogger.log("Service", "⏸ STOP command received. Monitor paused.")
                TaskManager.pause()
                releaseWakeLock()
                stopLoop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_MANUAL_CLICK -> {
                LiveLogger.log("UserAction", "⚡ Manual Click requested!")
                startForegroundWithNotification()
                performManualClick()
            }
            ACTION_MANUAL_INSPECT -> {
                LiveLogger.log("UserAction", "🔍 Manual Inspect requested!")
                startForegroundWithNotification()
                performManualInspect()
            }
        }
        return START_STICKY
    }

    private fun startForegroundWithNotification() {
        val modeStr = if (WebViewHolder.isSimulationMode) "Simulation" else "Live Portal"
        val notification = NotificationHelper.buildForegroundNotification(
            this,
            TaskManager.currentState.displayText,
            TaskManager.currentTasksCount,
            modeStr
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NotificationHelper.NOTIFICATION_STATUS_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NotificationHelper.NOTIFICATION_STATUS_ID, notification)
        }
    }

    private fun updateNotification() {
        val modeStr = if (WebViewHolder.isSimulationMode) "Simulation" else "Live Portal"
        val notification = NotificationHelper.buildForegroundNotification(
            this,
            TaskManager.currentState.displayText,
            TaskManager.currentTasksCount,
            modeStr
        )
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NotificationHelper.NOTIFICATION_STATUS_ID, notification)
        onStateChangeListener?.invoke()
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld != true) {
                wakeLock?.acquire(24 * 60 * 60 * 1000L)
                LiveLogger.log("Power", "WakeLock acquired for background execution.")
            }
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                LiveLogger.log("Power", "WakeLock released.")
            }
        } catch (_: Exception) {}
    }

    private fun scheduleNextTick(delayMs: Long) {
        handler.removeCallbacks(tickRunnable)
        handler.postDelayed(tickRunnable, delayMs)
        isLoopScheduled = true
        val delaySec = String.format("%.1f", delayMs / 1000.0)
        LiveLogger.log("Timer", "Next check in ${delaySec}s [State: ${TaskManager.currentState.name}]")
    }

    private fun stopLoop() {
        handler.removeCallbacks(tickRunnable)
        isLoopScheduled = false
        releaseWakeLock()
    }

    private val tickRunnable = Runnable {
        runTick()
    }

    private fun runTick() {
        if (!TaskManager.isRunning) {
            releaseWakeLock()
            updateNotification()
            return
        }

        // 1. Day transition check
        TaskManager.checkAndResetNewDay()

        // 2. Schedule Check
        if (!TaskManager.isWithinSchedule()) {
            releaseWakeLock()
            TaskManager.updateStateBasedOnSchedule()
            updateNotification()
            scheduleNextTick(60_000L)
            return
        }

        acquireWakeLock()

        // 3. Daily Hard Cap: Stop for the rest of the day once 5 tasks are reached
        if (TaskManager.currentTasksCount >= TaskManager.maxDailyQuota ||
            TaskManager.currentState == MonitorState.DAILY_MAX_REACHED) {
            LiveLogger.log("Monitor", "Daily cap reached (5 tasks). Standing by.")
            TaskManager.updateStateBasedOnSchedule()
            updateNotification()
            scheduleNextTick(60_000L)
            return
        }

        // 4. Batch Exhaustion Guard: If goal met and batch depleted, stop clicking
        if (TaskManager.currentState == MonitorState.BATCH_DEPLETED_STOPPED) {
            LiveLogger.log("Monitor", "Batch completed (>= 3 tasks). Waiting for manual restart or new batch.")
            updateNotification()
            scheduleNextTick(60_000L)
            return
        }

        // 5. Execute DOM inspection
        LiveLogger.log("DOM", "Inspecting page state...")
        WebViewHolder.evaluateJavascript(DomEvaluator.INSPECTION_SCRIPT) { rawResult ->
            try {
                val result = DomEvaluator.parseResult(rawResult)
                handleInspectionResult(result)
            } catch (t: Throwable) {
                LiveLogger.log("DOM", "⚠️ Error evaluating inspection result: ${t.message}")
                scheduleNextTick(5000L)
            }
        }
    }

    private fun handleInspectionResult(result: DomInspectionResult) {
        if (!TaskManager.isRunning) return

        LiveLogger.log("DOM", "Page State: LoggedIn=${result.isLoggedIn}, Loading=${result.isLoading}, Tasks=${result.taskCount}, ButtonAvailable=${result.buttonAvailable}")

        // 1. Session check: Are we logged in?
        if (!result.isLoggedIn) {
            LiveLogger.log("Auth", "⚠️ Session expired or not logged in.")
            if (TaskManager.currentState != MonitorState.SESSION_EXPIRED) {
                TaskManager.onSessionExpired()
                NotificationHelper.sendAlarmAlert(
                    this,
                    "⚠️ Session Expired",
                    "Please open the app to log in to RenderThat."
                )
            }
            updateNotification()
            scheduleNextTick(10_000L)
            return
        } else {
            TaskManager.onSessionRestored()
        }

        // 2. Loading Spinner check: if busy, retry in 2s
        if (result.isLoading) {
            LiveLogger.log("DOM", "⏳ Page spinner active. Retrying in 2s...")
            scheduleNextTick(2000L)
            return
        }

        // 3. Cascading Filters check: if adjusted, wait 2s for options to reload
        if (result.filtersAdjusted) {
            LiveLogger.log("DOM", "🔧 Filters adjusted (Show/Account/Quote/Team). Waiting 2s for table...")
            scheduleNextTick(2000L)
            return
        }

        // 4. Check if external task count changed (e.g. manual claim or prior state)
        val newlyClaimed = TaskManager.onTaskCountInspected(result.taskCount)
        if (newlyClaimed) {
            LiveLogger.log("Tasks", "🎉 New tasks detected in table! Total: ${result.taskCount}")
            if (TaskManager.currentTasksCount >= TaskManager.maxDailyQuota) {
                NotificationHelper.sendAlarmAlert(
                    this,
                    "🎯 Daily Quota Reached!",
                    "You have 5 tasks! Monitoring stopped for the day."
                )
                updateNotification()
                scheduleNextTick(60_000L)
                return
            } else {
                NotificationHelper.sendAlarmAlert(
                    this,
                    "🎉 Task Assigned!",
                    "You now have ${TaskManager.currentTasksCount} tasks assigned!"
                )
            }
        }

        // Check if daily quota reached or batch already depleted
        if (TaskManager.currentTasksCount >= TaskManager.maxDailyQuota) {
            TaskManager.updateStateBasedOnSchedule()
            updateNotification()
            scheduleNextTick(60_000L)
            return
        }
        if (TaskManager.currentState == MonitorState.BATCH_DEPLETED_STOPPED) {
            updateNotification()
            scheduleNextTick(60_000L)
            return
        }

        // 5. Button Presence / Disappearance Handling
        if (!result.buttonAvailable) {
            val waitDelay = TaskManager.getDisappearanceWaitDelayMs()
            LiveLogger.log("DOM", "Button not present/disabled. Waiting ${(waitDelay / 1000)}s...")
            updateNotification()
            scheduleNextTick(waitDelay)
            return
        }

        // 6. Button IS present: Execute Click
        val countBeforeClick = TaskManager.currentTasksCount
        LiveLogger.log("Action", "🔘 Clicking 'Assign me a Task'...")
        WebViewHolder.evaluateJavascript(DomEvaluator.CLICK_SCRIPT) {
            // Wait 1200ms for backend/DOM response, then re-inspect
            handler.postDelayed({
                if (!TaskManager.isRunning) return@postDelayed

                WebViewHolder.evaluateJavascript(DomEvaluator.INSPECTION_SCRIPT) { postRaw ->
                    val postResult = DomEvaluator.parseResult(postRaw)
                    val outcome = TaskManager.onButtonClickExecuted(countBeforeClick, postResult.taskCount)

                    LiveLogger.log("Action", "Result after click: countBefore=$countBeforeClick, countAfter=${postResult.taskCount}, outcome=$outcome")

                    when (outcome) {
                        ClickOutcome.TASK_CLAIMED -> {
                            LiveLogger.log("Tasks", "🎉 SUCCESS: Claimed task #${postResult.taskCount}! Bursting...")
                            NotificationHelper.sendAlarmAlert(
                                this,
                                "🎉 Task Assigned!",
                                "You now have ${TaskManager.currentTasksCount} tasks assigned!"
                            )
                        }
                        ClickOutcome.TASK_CLAIMED_QUOTA_REACHED -> {
                            LiveLogger.log("Tasks", "🎯 Daily quota of 5 reached! Stopping for today.")
                            NotificationHelper.sendAlarmAlert(
                                this,
                                "🎯 Daily Quota Reached!",
                                "You have 5 tasks! Monitoring stopped for today."
                            )
                        }
                        ClickOutcome.MISS_BATCH_DEPLETED_STOPPED -> {
                            LiveLogger.log("Tasks", "🎯 4 consecutive misses and >= 3 tasks. Batch finished. Stopped!")
                            NotificationHelper.sendAlarmAlert(
                                this,
                                "🎯 Task Goal Secured!",
                                "Batch is finished. You have ${TaskManager.currentTasksCount} tasks assigned!"
                            )
                        }
                        ClickOutcome.MISS_CONTINUE_BURST -> {
                            LiveLogger.log("Tasks", "Miss in burst mode (misses: ${TaskManager.consecutiveMisses}/${TaskManager.maxConsecutiveMisses}). Continuing burst...")
                        }
                        ClickOutcome.MISS_FELL_BACK_TO_COLD -> {
                            LiveLogger.log("Tasks", "Miss limit reached with < 3 tasks. Returning to 60s cold search...")
                        }
                    }

                    updateNotification()
                    val nextDelay = TaskManager.getNextDelayMs()
                    scheduleNextTick(nextDelay)
                }
            }, 1200L)
        }
    }

    private fun performManualClick() {
        val countBefore = TaskManager.currentTasksCount
        LiveLogger.log("ManualAction", "Executing direct click on 'Assign me a Task'...")
        WebViewHolder.evaluateJavascript(DomEvaluator.CLICK_SCRIPT) {
            handler.postDelayed({
                WebViewHolder.evaluateJavascript(DomEvaluator.INSPECTION_SCRIPT) { postRaw ->
                    val postResult = DomEvaluator.parseResult(postRaw)
                    val outcome = TaskManager.onButtonClickExecuted(countBefore, postResult.taskCount)
                    LiveLogger.log("ManualAction", "Direct click result: before=$countBefore, after=${postResult.taskCount}, outcome=$outcome")
                    if (postResult.taskCount > countBefore) {
                        NotificationHelper.sendAlarmAlert(
                            this,
                            "🎉 Task Assigned!",
                            "Manual click secured task #${postResult.taskCount}!"
                        )
                    }
                    updateNotification()
                }
            }, 1200L)
        }
    }

    private fun performManualInspect() {
        LiveLogger.log("ManualAction", "Executing direct DOM inspection...")
        WebViewHolder.evaluateJavascript(DomEvaluator.INSPECTION_SCRIPT) { raw ->
            val result = DomEvaluator.parseResult(raw)
            LiveLogger.log("ManualAction", "Inspection: LoggedIn=${result.isLoggedIn}, Spinner=${result.isLoading}, Tasks=${result.taskCount}, ButtonAvailable=${result.buttonAvailable}")
            TaskManager.onTaskCountInspected(result.taskCount)
            updateNotification()
        }
    }

    override fun onDestroy() {
        stopLoop()
        super.onDestroy()
    }
}
