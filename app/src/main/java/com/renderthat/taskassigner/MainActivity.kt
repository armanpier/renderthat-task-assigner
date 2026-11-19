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

        AppPreferences.applyToTaskManager(this)
        NotificationHelper.createChannels(this)
        requestNotificationPermission()

        setupViews()
    }

    override fun onStart() {
        super.onStart()
        // Attach service-owned persistent WebView to container
        WebViewHolder.attachToContainer(binding.webviewContainer)

        TaskMonitorService.onStateChangeListener = {
            runOnUiThread { updateUI() }
        }
        updateUI()
    }

    override fun onStop() {
        // Detach WebView so Activity doesn't leak, but preserve offscreen desktop rendering
        WebViewHolder.detachFromContainer()
        TaskMonitorService.onStateChangeListener = null
        super.onStop()
    }

    private fun setupViews() {
        // Start / Pause Button
        binding.btnStartPause.setOnClickListener {
            if (TaskManager.isRunning) {
                TaskMonitorService.stopService(this)
            } else {
                TaskMonitorService.startService(this)
            }
            updateUI()
        }

        // Mode Toggle Button (Simulation <-> Live)
        binding.btnToggleMode.setOnClickListener {
            val isSim = WebViewHolder.isSimulationMode
            val newMode = !isSim
            AppPreferences.setSimulationMode(this, newMode)
            binding.btnToggleMode.text = if (newMode) getString(R.string.mode_sim) else getString(R.string.mode_live)
            Toast.makeText(
                this,
                "Switched to: ${if (newMode) "Simulation Mode" else "Live Portal"}",
                Toast.LENGTH_SHORT
            ).show()
            updateUI()
        }

        // Battery Optimization Check Button
        binding.btnBatteryOpt.setOnClickListener {
            checkAndPromptBatteryOptimization()
        }
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
            cornerRadius = 24f
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

        // Mode button text
        binding.btnToggleMode.text = if (WebViewHolder.isSimulationMode) getString(R.string.mode_sim) else getString(R.string.mode_live)

        // Battery button state tint
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val isBatteryIgnored = powerManager.isIgnoringBatteryOptimizations(packageName)
        binding.btnBatteryOpt.setColorFilter(
            Color.parseColor(if (isBatteryIgnored) "#00C853" else "#FF9800")
        )
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
        val packageName = packageName
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
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
            Toast.makeText(this, "✓ Battery optimization is already disabled for this app!", Toast.LENGTH_SHORT).show()
        }
    }
}
