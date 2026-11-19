package com.renderthat.taskassigner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat

object NotificationHelper {

    const val CHANNEL_STATUS_ID = "renderthat_status_channel"
    const val CHANNEL_ALARM_ID = "renderthat_alarm_channel"
    const val NOTIFICATION_STATUS_ID = 1001
    const val NOTIFICATION_ALARM_ID = 1002

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Status channel for background Foreground Service (Low importance, silent, ongoing)
            val statusChannel = NotificationChannel(
                CHANNEL_STATUS_ID,
                "Task Monitor Service Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live ongoing status of the RenderThat task monitor"
                setShowBadge(false)
            }

            // Alarm channel for task claimed / goal reached notifications (High importance, USAGE_ALARM)
            val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_ALARM)
                .build()

            val alarmChannel = NotificationChannel(
                CHANNEL_ALARM_ID,
                "Task Assignment Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High-priority sound and vibration alerts when tasks are assigned or batch goal is met"
                setSound(alarmSound, audioAttributes)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 600)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            notificationManager.createNotificationChannel(statusChannel)
            notificationManager.createNotificationChannel(alarmChannel)
        }
    }

    fun buildForegroundNotification(context: Context, statusText: String, taskCount: Int, modeText: String): Notification {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_STATUS_ID)
            .setContentTitle("RenderThat Monitor: $statusText")
            .setContentText("Tasks: $taskCount/5 | Mode: $modeText")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    fun sendAlarmAlert(context: Context, title: String, message: String) {
        val soundEnabled = AppPreferences.isSoundEnabled(context)
        val vibrationEnabled = AppPreferences.isVibrationEnabled(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmSound = if (soundEnabled) {
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        } else {
            null
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ALARM_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        if (soundEnabled && alarmSound != null) {
            builder.setSound(alarmSound)
        } else {
            builder.setSilent(true)
        }

        if (vibrationEnabled) {
            builder.setVibrate(longArrayOf(0, 400, 200, 400, 200, 600))
        } else {
            builder.setVibrate(longArrayOf(0))
        }

        val notification = builder.build()
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ALARM_ID, notification)

        // Trigger hardware vibrator explicitly so it vibrates even under custom OEM sound profiles
        if (vibrationEnabled) {
            try {
                val pattern = longArrayOf(0, 400, 200, 400, 200, 600)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                    vibratorManager?.defaultVibrator?.vibrate(
                        VibrationEffect.createWaveform(pattern, -1)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                    vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
                }
            } catch (_: Exception) {}
        }

        // Cut through silent/vibrate by playing alarm ringtone on USAGE_ALARM stream if sound is enabled
        if (soundEnabled && alarmSound != null) {
            try {
                val ringtone = RingtoneManager.getRingtone(context, alarmSound)
                val audioAttributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .build()
                ringtone.audioAttributes = audioAttributes
                ringtone.play()
            } catch (_: Exception) {}
        }
    }
}
