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
import org.json.JSONObject

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

        // 2. Schedule Check: When outside working hours, perform keep-alive heartbeat ping
        if (!TaskManager.isWithinSchedule()) {
            performOffHoursKeepAlive()
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

        // 4. Execute DOM inspection (inspects page state to detect tasks, button presence, or button reappearance)
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

    private fun performOffHoursKeepAlive() {
        releaseWakeLock()
        TaskManager.updateStateBasedOnSchedule()
        updateNotification()

        val intervalMin = TaskManager.keepAliveIntervalMinutes
        LiveLogger.log("KeepAlive", "🌙 Off-hours idle mode. Pinging server to prevent idle timeout...")

        acquireWakeLock()
        WebViewHolder.evaluateJavascript(DomEvaluator.KEEP_ALIVE_SCRIPT) { rawResult ->
            try {
                var cleaned = rawResult.trim()
                if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length > 2) {
                    cleaned = cleaned.substring(1, cleaned.length - 1)
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\")
                }
                val json = if (cleaned.isNotBlank() && cleaned != "null") JSONObject(cleaned) else null
                val loggedIn = json?.optBoolean("isLoggedIn", true) ?: true

                if (!loggedIn) {
                    LiveLogger.log("Auth", "⚠️ Keep-alive detected session logout!")
                    if (AppPreferences.isAutoLoginEnabled(this) && AppPreferences.hasSavedCredentials(this)) {
                        LiveLogger.log("Auth", "🔑 Auto-filling login credentials during keep-alive...")
                        val autoScript = DomEvaluator.buildAutoLoginScript(
                            AppPreferences.getSavedUsername(this),
                            AppPreferences.getSavedPassword(this),
                            autoSubmit = true
                        )
                        WebViewHolder.evaluateJavascript(autoScript) { autoRes ->
                            LiveLogger.log("Auth", "Auto-login response: $autoRes")
                        }
                    }
                } else {
                    LiveLogger.log("KeepAlive", "💓 Heartbeat OK! Session refreshed. Next keep-alive in ${intervalMin}m.")
                }
            } catch (e: Exception) {
                LiveLogger.log("KeepAlive", "💓 Heartbeat tick completed.")
            } finally {
                releaseWakeLock()
                val nextDelay = TaskManager.getNextDelayMs()
                scheduleNextTick(nextDelay)
            }
        }
    }

    private fun handleInspectionResult(result: DomInspectionResult) {
        if (!TaskManager.isRunning) return

        LiveLogger.log("DOM", "Page State: LoggedIn=${result.isLoggedIn}, Loading=${result.isLoading}, Tasks=${result.taskCount}, ButtonAvailable=${result.buttonAvailable}")

        // 1. Session check: Are we logged in?
        if (!result.isLoggedIn) {
            LiveLogger.log("Auth", "⚠️ Session expired or login screen detected.")
            if (AppPreferences.isAutoLoginEnabled(this) && AppPreferences.hasSavedCredentials(this)) {
                LiveLogger.log("Auth", "🔑 Auto-login enabled. Injecting saved credentials...")
                val autoScript = DomEvaluator.buildAutoLoginScript(
                    AppPreferences.getSavedUsername(this),
                    AppPreferences.getSavedPassword(this),
                    autoSubmit = true
                )
                WebViewHolder.evaluateJavascript(autoScript) { autoRes ->
                    LiveLogger.log("Auth", "Auto-login response: $autoRes")
                    scheduleNextTick(4000L)
                }
                return
            } else {
                if (TaskManager.currentState != MonitorState.SESSION_EXPIRED) {
                    TaskManager.onSessionExpired()
                    NotificationHelper.sendAlarmAlert(
                        this,
                        "⚠️ Session Expired",
                        "Please open the app to log in to RenderThat."
                    )
                }
                updateNotification()
                scheduleNextTick(15_000L)
                return
            }
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
                NotificationHelper.sendTaskAssignedDing(
                    this,
                    "🎯 Daily Quota Reached!",
                    "You have 5 tasks! Monitoring stopped for the day."
                )
                updateNotification()
                scheduleNextTick(60_000L)
                return
            } else {
                NotificationHelper.sendTaskAssignedDing(
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
        // 4. Standby Guard (when 100 misses reached in turbo mode): Wait for button to reappear
        if (TaskManager.currentState == MonitorState.BATCH_DEPLETED_STOPPED) {
            val resumed = TaskManager.checkButtonReappearance(result.buttonAvailable, result.taskCount)
            if (resumed) {
                LiveLogger.log("Turbo", "🔔 'Assign me a Task' button has appeared again! Turning auto-assign back ON.")
                NotificationHelper.sendAlarmAlert(
                    this,
                    "🔔 Button Reappeared!",
                    "Task assign button is available again. Resuming auto-assign!"
                )
                updateNotification()
                scheduleNextTick(1000L)
                return
            } else {
                val statusMsg = if (TaskManager.buttonWasHiddenInStandby) {
                    "Button disappeared. Waiting for it to reappear..."
                } else {
                    "100 attempts reached without new tasks. Waiting for button cycle before resuming."
                }
                LiveLogger.log("Turbo", "⏸️ Standby: $statusMsg (Button visible: ${result.buttonAvailable})")
                updateNotification()
                val nextDelay = TaskManager.getNextDelayMs()
                scheduleNextTick(nextDelay)
                return
            }
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
                            NotificationHelper.sendTaskAssignedDing(
                                this,
                                "🎉 Task Assigned!",
                                "You now have ${TaskManager.currentTasksCount} tasks assigned!"
                            )
                        }
                        ClickOutcome.TASK_CLAIMED_QUOTA_REACHED -> {
                            LiveLogger.log("Tasks", "🎯 Daily quota of 5 reached! Stopping for today.")
                            NotificationHelper.sendTaskAssignedDing(
                                this,
                                "🎯 Daily Quota Reached!",
                                "You have 5 tasks! Monitoring stopped for today."
                            )
                        }
                        ClickOutcome.MISS_BATCH_DEPLETED_STOPPED -> {
                            LiveLogger.log("Turbo", "⏸️ Reached ${TaskManager.maxConsecutiveMisses} attempts in turbo mode without new tasks. Turning OFF until button reappears.")
                            NotificationHelper.sendAlarmAlert(
                                this,
                                "⏸️ Turbo Attempts Limit Reached",
                                "Reached ${TaskManager.maxConsecutiveMisses} attempts in turbo mode. Pushing turned off until button reappears."
                            )
                        }
                        ClickOutcome.MISS_CONTINUE_BURST -> {
                            LiveLogger.log("Turbo", "Miss in turbo mode (${TaskManager.consecutiveMisses}/${TaskManager.maxConsecutiveMisses}). Continuing burst...")
                        }
                        ClickOutcome.MISS_FELL_BACK_TO_COLD -> {
                            LiveLogger.log("Tasks", "Cold search tick finished. Returning to cold cadence...")
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
        if (TaskManager.currentState == MonitorState.BATCH_DEPLETED_STOPPED) {
            TaskManager.resetStandby()
            TaskManager.updateStateBasedOnSchedule()
        }
        val countBefore = TaskManager.currentTasksCount
        LiveLogger.log("ManualAction", "Executing direct click on 'Assign me a Task'...")
        WebViewHolder.evaluateJavascript(DomEvaluator.CLICK_SCRIPT) {
            handler.postDelayed({
                WebViewHolder.evaluateJavascript(DomEvaluator.INSPECTION_SCRIPT) { postRaw ->
                    val postResult = DomEvaluator.parseResult(postRaw)
                    val outcome = TaskManager.onButtonClickExecuted(countBefore, postResult.taskCount)
                    LiveLogger.log("ManualAction", "Direct click result: before=$countBefore, after=${postResult.taskCount}, outcome=$outcome")
                    if (postResult.taskCount > countBefore) {
                        NotificationHelper.sendTaskAssignedDing(
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
