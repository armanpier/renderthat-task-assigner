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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                TaskManager.start()
                startForegroundWithNotification()
                if (TaskManager.isWithinSchedule()) {
                    acquireWakeLock()
                }
                scheduleNextTick(1000L)
            }
            ACTION_STOP -> {
                TaskManager.pause()
                releaseWakeLock()
                stopLoop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
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
                // Safety limit of 24h prevents leaks if service is killed unexpectedly
                wakeLock?.acquire(24 * 60 * 60 * 1000L)
            }
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
    }

    private fun scheduleNextTick(delayMs: Long) {
        handler.removeCallbacks(tickRunnable)
        handler.postDelayed(tickRunnable, delayMs)
        isLoopScheduled = true
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

        // 2. Schedule Check (Active: Mon-Fri 10:00 - 19:00)
        if (!TaskManager.isWithinSchedule()) {
            releaseWakeLock() // Release wake lock when dormant outside working hours
            TaskManager.updateStateBasedOnSchedule()
            updateNotification()
            scheduleNextTick(60_000L)
            return
        }

        // Inside working hours: hold PARTIAL_WAKE_LOCK so screen lock won't freeze cadence
        acquireWakeLock()

        // 3. Daily Hard Cap: Stop for the rest of the day once 5 tasks are reached
        if (TaskManager.currentTasksCount >= TaskManager.maxDailyQuota ||
            TaskManager.currentState == MonitorState.DAILY_MAX_REACHED) {
            TaskManager.updateStateBasedOnSchedule()
            updateNotification()
            scheduleNextTick(60_000L)
            return
        }

        // 4. Batch Exhaustion Guard: If goal met and batch depleted, stop clicking
        if (TaskManager.currentState == MonitorState.BATCH_DEPLETED_STOPPED) {
            updateNotification()
            scheduleNextTick(60_000L)
            return
        }

        // 5. Execute DOM inspection
        WebViewHolder.evaluateJavascript(DomEvaluator.INSPECTION_SCRIPT) { rawResult ->
            val result = DomEvaluator.parseResult(rawResult)
            handleInspectionResult(result)
        }
    }

    private fun handleInspectionResult(result: DomInspectionResult) {
        if (!TaskManager.isRunning) return

        // 1. Session check: Are we logged in?
        if (!result.isLoggedIn) {
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
            scheduleNextTick(2000L)
            return
        }

        // 3. Cascading Filters check: if adjusted, wait 2s for options to reload
        if (result.filtersAdjusted) {
            scheduleNextTick(2000L)
            return
        }

        // 4. Check if external task count changed (e.g. manual claim or prior state)
        val newlyClaimed = TaskManager.onTaskCountInspected(result.taskCount)
        if (newlyClaimed) {
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
        // If button is not present, skip click and wait (15s if tasks > 0, 60s if 0)
        if (!result.buttonAvailable) {
            val waitDelay = TaskManager.getDisappearanceWaitDelayMs()
            updateNotification()
            scheduleNextTick(waitDelay)
            return
        }

        // 6. Button IS present: Execute Click
        val countBeforeClick = TaskManager.currentTasksCount
        WebViewHolder.evaluateJavascript(DomEvaluator.CLICK_SCRIPT) {
            // Wait 1200ms for backend/DOM response, then re-inspect
            handler.postDelayed({
                if (!TaskManager.isRunning) return@postDelayed

                WebViewHolder.evaluateJavascript(DomEvaluator.INSPECTION_SCRIPT) { postRaw ->
                    val postResult = DomEvaluator.parseResult(postRaw)
                    val outcome = TaskManager.onButtonClickExecuted(countBeforeClick, postResult.taskCount)

                    when (outcome) {
                        ClickOutcome.TASK_CLAIMED -> {
                            NotificationHelper.sendAlarmAlert(
                                this,
                                "🎉 Task Assigned!",
                                "You now have ${TaskManager.currentTasksCount} tasks assigned!"
                            )
                        }
                        ClickOutcome.TASK_CLAIMED_QUOTA_REACHED -> {
                            NotificationHelper.sendAlarmAlert(
                                this,
                                "🎯 Daily Quota Reached!",
                                "You have 5 tasks! Monitoring stopped for today."
                            )
                        }
                        ClickOutcome.MISS_BATCH_DEPLETED_STOPPED -> {
                            NotificationHelper.sendAlarmAlert(
                                this,
                                "🎯 Task Goal Secured!",
                                "Batch is finished. You have ${TaskManager.currentTasksCount} tasks assigned!"
                            )
                        }
                        ClickOutcome.MISS_CONTINUE_BURST,
                        ClickOutcome.MISS_FELL_BACK_TO_COLD -> {
                            // Non-critical miss: no blaring alarm, state transitions handled in TaskManager
                        }
                    }

                    updateNotification()
                    val nextDelay = TaskManager.getNextDelayMs()
                    scheduleNextTick(nextDelay)
                }
            }, 1200L)
        }
    }

    override fun onDestroy() {
        stopLoop()
        super.onDestroy()
    }
}
