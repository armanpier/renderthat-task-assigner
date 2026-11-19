package com.renderthat.taskassigner

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.renderthat.taskassigner.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    companion object {
        private const val REQ_NOTIF_PERMISSION = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        NotificationHelper.createChannels(this)
        requestNotificationPermission()

        AppPreferences.applyToTaskManager(this)
        setupViews()
    }

    override fun onStart() {
        super.onStart()
        // Attach service-owned persistent WebView to container
        WebViewHolder.attachToContainer(binding.webviewContainer)

        TaskMonitorService.onStateChangeListener = {
            runOnUiThread { updateUI() }
        }

        LiveLogger.onLogListener = {
            runOnUiThread {
                binding.tvLiveLogText.text = LiveLogger.getAllLogs()
                binding.scrollLogConsole.post {
                    binding.scrollLogConsole.fullScroll(ScrollView.FOCUS_DOWN)
                }
            }
        }

        binding.tvLiveLogText.text = LiveLogger.getAllLogs()
        updateUI()
        updateBatteryOptButton()
    }

    override fun onStop() {
        // Detach WebView to prevent leak, keeping offscreen layout alive
        WebViewHolder.detachFromContainer()
        TaskMonitorService.onStateChangeListener = null
        LiveLogger.onLogListener = null
        super.onStop()
    }

    private fun setupViews() {
        // 1. Start / Pause Button
        binding.btnStartPause.setOnClickListener {
            if (TaskManager.isRunning) {
                TaskMonitorService.stopService(this)
            } else {
                TaskMonitorService.startService(this)
            }
            updateUI()
        }

        // 2. Direct Manual Click Now Button
        binding.btnManualClick.setOnClickListener {
            TaskMonitorService.triggerManualClick(this)
            Toast.makeText(this, "⚡ Sent Click command to 'Assign me a Task'!", Toast.LENGTH_SHORT).show()
        }

        // 3. Override Working Hours (24/7 Test Mode) Button
        binding.btnOverrideHours.setOnClickListener {
            val current = AppPreferences.isOverrideHours(this)
            val newOverride = !current
            AppPreferences.setOverrideHours(this, newOverride)
            val stateMsg = if (newOverride) "24/7 Test Mode ON (Hours overridden)" else "Normal Hours (10:00 - 19:00)"
            Toast.makeText(this, "⏰ $stateMsg", Toast.LENGTH_SHORT).show()
            LiveLogger.log("Config", "Working hours override changed: $newOverride")
            updateUI()
        }

        // 4. Reload Web Page Button
        binding.btnReloadWeb.setOnClickListener {
            WebViewHolder.reloadCurrentMode()
            Toast.makeText(this, "🔄 Reloading page...", Toast.LENGTH_SHORT).show()
        }

        // 5. Mode Toggle Button (Simulation <-> Live)
        binding.btnToggleMode.setOnClickListener {
            val isSim = WebViewHolder.isSimulationMode
            val newMode = !isSim
            AppPreferences.setSimulationMode(this, newMode)
            Toast.makeText(
                this,
                "Switched to: ${if (newMode) "Simulation Mode" else "Live Portal"}",
                Toast.LENGTH_SHORT
            ).show()
            updateUI()
        }

        // 6. Battery Optimization Check Button
        binding.btnBatteryOpt.setOnClickListener {
            checkAndPromptBatteryOptimization()
        }

        // 7. Live Activity Log Controls (Clear & Collapse/Expand)
        binding.btnClearLog.setOnClickListener {
            LiveLogger.clear()
        }

        val toggleLogAction = View.OnClickListener {
            if (binding.scrollLogConsole.visibility == View.VISIBLE) {
                binding.scrollLogConsole.visibility = View.GONE
                binding.btnToggleLog.text = "Show ▴"
            } else {
                binding.scrollLogConsole.visibility = View.VISIBLE
                binding.btnToggleLog.text = "Hide ▾"
            }
        }
        binding.btnToggleLog.setOnClickListener(toggleLogAction)
        binding.tvLogHeader.setOnClickListener(toggleLogAction)
    }

    private fun updateUI() {
        val state = TaskManager.currentState
        binding.tvStatusBadge.text = state.displayText

        // Badge color
        val badgeColor = when (state) {
            MonitorState.IDLE -> Color.parseColor("#374151")
            MonitorState.COLD_SEARCH -> Color.parseColor("#1E88E5")
            MonitorState.BURST_MODE -> Color.parseColor("#FB8C00")
            MonitorState.BATCH_DEPLETED_STOPPED, MonitorState.DAILY_MAX_REACHED -> Color.parseColor("#00C853")
            MonitorState.DORMANT_OFF_HOURS -> Color.parseColor("#7C4DFF")
            MonitorState.SESSION_EXPIRED -> Color.parseColor("#E53935")
        }
        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 20f
            setColor(badgeColor)
        }
        binding.tvStatusBadge.background = drawable

        // Task & click counts
        binding.tvTasksCount.text = "Tasks: ${TaskManager.currentTasksCount} / ${TaskManager.maxDailyQuota}"
        binding.tvClicksCount.text = "Clicks: ${TaskManager.totalClicksToday}"

        // Action button text and colors
        if (TaskManager.isRunning) {
            binding.btnStartPause.text = getString(R.string.btn_pause)
            binding.btnStartPause.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E53935"))
            binding.btnStartPause.setTextColor(Color.WHITE)
        } else {
            binding.btnStartPause.text = getString(R.string.btn_start)
            binding.btnStartPause.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#00C853"))
            binding.btnStartPause.setTextColor(Color.BLACK)
        }

        // 24/7 Override button state
        val isOverride = AppPreferences.isOverrideHours(this)
        if (isOverride) {
            binding.btnOverrideHours.text = "⏰ 24/7: ON"
            binding.btnOverrideHours.setTextColor(Color.parseColor("#00E676"))
            binding.btnOverrideHours.strokeColor = ColorStateList.valueOf(Color.parseColor("#00E676"))
        } else {
            binding.btnOverrideHours.text = "⏰ 24/7: OFF"
            binding.btnOverrideHours.setTextColor(Color.parseColor("#FFB300"))
            binding.btnOverrideHours.strokeColor = ColorStateList.valueOf(Color.parseColor("#FFB300"))
        }

        // Mode button text
        binding.btnToggleMode.text = if (WebViewHolder.isSimulationMode) getString(R.string.mode_sim) else getString(R.string.mode_live)
    }

    private fun updateBatteryOptButton() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val isWhitelisted = powerManager.isIgnoringBatteryOptimizations(packageName)
        if (isWhitelisted) {
            binding.btnBatteryOpt.setColorFilter(Color.parseColor("#00C853"))
        } else {
            binding.btnBatteryOpt.setColorFilter(Color.parseColor("#FF9800"))
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQ_NOTIF_PERMISSION
                )
            }
        }
    }

    @SuppressLint("BatteryLife")
    private fun checkAndPromptBatteryOptimization() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val isWhitelisted = powerManager.isIgnoringBatteryOptimizations(packageName)
        if (!isWhitelisted) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            try {
                startActivity(intent)
            } catch (e: Exception) {
                val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                startActivity(fallbackIntent)
            }
        } else {
            Toast.makeText(this, "✓ Battery optimization is already unrestricted!", Toast.LENGTH_SHORT).show()
        }
    }
}
