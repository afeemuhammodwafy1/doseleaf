package com.wafyro.doseleaf.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.wafyro.doseleaf.R
import com.wafyro.doseleaf.data.local.DoseleafDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Calendar

object ExpiryReminderScheduler {
    private const val ACTION = "com.wafyro.doseleaf.EXPIRY"
    fun schedule(context: Context, medicineId: Long, name: String, expiryDate: Long?) {
        cancel(context, medicineId)
        if (expiryDate == null) return
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val expiryCal = Calendar.getInstance().apply { timeInMillis = expiryDate; set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val reminders = listOf("7days" to (expiryCal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -7) }, "today" to expiryCal)
        reminders.forEach { (kind, cal) ->
            if (cal.timeInMillis <= System.currentTimeMillis()) return@forEach
            val intent = Intent(context, ExpiryReminderReceiver::class.java).apply {
                action = "$ACTION.$medicineId.$kind"
                putExtra("medicineId", medicineId); putExtra("medicineName", name); putExtra("expiryKind", kind)
            }
            val pending = PendingIntent.getBroadcast(context, "$medicineId:$kind".hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && manager.canScheduleExactAlarms()) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pending)
            else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pending)
        }
    }
    fun cancel(context: Context, medicineId: Long) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        listOf("7days", "today").forEach { kind ->
            val intent = Intent(context, ExpiryReminderReceiver::class.java).apply { action = "$ACTION.$medicineId.$kind" }
            val pending = PendingIntent.getBroadcast(context, "$medicineId:$kind".hashCode(), intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            if (pending != null) { manager.cancel(pending); pending.cancel() }
        }
    }
}

class ExpiryReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val id = intent.getLongExtra("medicineId", -1L)
                val medicine = DoseleafDatabase.getInstance(context).medicineDao().getById(id) ?: return@launch
                if (!medicine.isActive || medicine.isDeleted || medicine.expiryDate == null) return@launch
                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return@launch
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val channel = "doseleaf_expiry_reminders"
                if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(channel, "Medicine expiry reminders", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Alerts before a medicine expiry date." })
                val kind = intent.getStringExtra("expiryKind")
                val title = if (kind == "today") "Medicine expiry today" else "Medicine expiring in 7 days"
                val body = if (kind == "today") "${medicine.name} reaches its entered expiry date today. Check the package or pharmacist's advice before use." else "${medicine.name} reaches its entered expiry date in 7 days. Check the package date."
                val notification = NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_doseleaf_notification).setContentTitle(title).setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body)).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_DEFAULT).build()
                manager.notify((id % Int.MAX_VALUE).toInt() + if (kind == "today") 100000 else 200000, notification)
            } finally { pending.finish() }
        }
    }
}
