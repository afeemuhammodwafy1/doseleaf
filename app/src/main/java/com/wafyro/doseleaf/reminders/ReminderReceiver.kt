package com.wafyro.doseleaf.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val vibrationEnabled = context.getSharedPreferences("doseleaf_settings", Context.MODE_PRIVATE).getBoolean("vibration", true)
        val channelId = "doseleaf_medication_reminders_v2"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "Doseleaf medicine alarms", NotificationManager.IMPORTANCE_HIGH)
                    .apply {
                        description = "Audible reminders for medicines saved on this device."
                        enableVibration(vibrationEnabled)
                        vibrationPattern = if (vibrationEnabled) longArrayOf(0, 500, 250, 500, 250, 800) else longArrayOf(0)
                        setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    }
            )
        }
        val name = intent.getStringExtra("medicineName") ?: "your medicine"
        val id = intent.getLongExtra("medicineId", 0L)
        val scheduleHour = intent.getIntExtra("scheduleHour", -1)
        val scheduleMinute = intent.getIntExtra("scheduleMinute", -1)
        val scheduleId = intent.getLongExtra("scheduleId", 0L)
        val alarmIntent = Intent(context, AlarmActivity::class.java).apply {
            putExtra("medicineId", id)
            putExtra("medicineName", name)
            putExtra("scheduleHour", scheduleHour)
            putExtra("scheduleMinute", scheduleMinute)
            putExtra("scheduleId", scheduleId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val openApp = PendingIntent.getActivity(
            context, (id % Int.MAX_VALUE).toInt(), alarmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        fun doseAction(action: String, requestOffset: Int): PendingIntent {
            val actionIntent = Intent(context, AlarmActivity::class.java).apply {
                this.action = "com.wafyro.doseleaf.DOSE_ACTION.$action.$id.${System.currentTimeMillis()}"
                putExtra("medicineId", id)
                putExtra("medicineName", name)
                putExtra("scheduleHour", scheduleHour)
                putExtra("scheduleMinute", scheduleMinute)
                putExtra("scheduleId", scheduleId)
                putExtra("doseAction", action)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            return PendingIntent.getActivity(context, (id % Int.MAX_VALUE).toInt() + requestOffset, actionIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val notification = NotificationCompat.Builder(context, channelId)
            .addAction(0, "Taken", doseAction("Taken", 10001))
            .addAction(0, "Skip", doseAction("Skipped", 20002))
            .setSmallIcon(com.wafyro.doseleaf.R.drawable.ic_doseleaf_notification)
            .setContentTitle("Doseleaf medicine reminder")
            .setContentText("It's time to check your schedule for $name.")
            .setContentIntent(openApp)
            .setFullScreenIntent(openApp, true)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
            .build()
        if (vibrationEnabled) notification.vibrate = longArrayOf(0, 500, 250, 500, 250, 800)
        try {
            if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                manager.notify((id % Int.MAX_VALUE).toInt(), notification)
            }
        } catch (_: SecurityException) { /* Permission can change between check and notify. Keep scheduling next occurrence. */ }

        // Schedule the next daily occurrence. This alarm is a reminder to check the plan,
        // not proof that a dose was taken or missed.
        val db = com.wafyro.doseleaf.data.local.DoseleafDatabase.getInstance(context)
        val pending = goAsync()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                db.medicineDao().getById(id)?.let { ReminderScheduler.schedule(context, it) }
            } finally { pending.finish() }
        }
    }
}
