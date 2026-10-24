package com.wafyro.doseleaf.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.wafyro.doseleaf.data.local.DoseleafDatabase
import com.wafyro.doseleaf.data.local.entity.MedicineEntity
import com.wafyro.doseleaf.data.local.entity.MedicineScheduleEntity
import java.util.Calendar

object ReminderScheduler {
    fun canScheduleExactAlarms(context: Context): Boolean {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
    }

    /** Schedules one repeating-daily alarm per enabled schedule row, falling back to legacy time. */
    suspend fun schedule(context: Context, medicine: MedicineEntity): Boolean {
        if (!isEnabled(context) || !medicine.isActive || medicine.isDeleted) {
            cancel(context, medicine.id)
            return false
        }
        val db = DoseleafDatabase.getInstance(context)
        val schedules = db.medicineScheduleDao().getForMedicine(medicine.id).filter { it.enabled }
        val times = if (schedules.isNotEmpty()) schedules else listOf(
            MedicineScheduleEntity(id = 0, medicineId = medicine.id, hour = medicine.reminderHour, minute = medicine.reminderMinute)
        )
        cancel(context, medicine.id)
        times.forEach { schedule ->
            scheduleOne(context, medicine, schedule)
        }
        return true
    }

    fun scheduleNext(context: Context, medicine: MedicineEntity, schedule: MedicineScheduleEntity) {
        if (!isEnabled(context) || !medicine.isActive || medicine.isDeleted) return
        scheduleOne(context, medicine, schedule)
    }

    private fun scheduleOne(context: Context, medicine: MedicineEntity, schedule: MedicineScheduleEntity) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, schedule.hour.coerceIn(0, 23))
            set(Calendar.MINUTE, schedule.minute.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        val identity = if (schedule.id == 0L) "legacy" else schedule.id.toString()
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = "com.wafyro.doseleaf.REMINDER.${medicine.id}.$identity"
            putExtra("medicineId", medicine.id)
            putExtra("medicineName", medicine.name)
            putExtra("profileId", medicine.profileId)
            putExtra("scheduleId", schedule.id)
            putExtra("scheduleHour", schedule.hour)
            putExtra("scheduleMinute", schedule.minute)
        }
        val requestCode = "${medicine.id}:$identity".hashCode()
        val pending = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (canScheduleExactAlarms(context)) {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pending)
        } else {
            // Best-effort fallback when Android's exact-alarm special access is not granted.
            // The OS may defer this alarm, so it is less precise than an exact alarm.
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pending)
        }
    }

    fun cancel(context: Context, medicineId: Long) {
        val db = DoseleafDatabase.getInstance(context)
        // Cancel legacy alarm and known schedule-row alarms; the stable action/data identity
        // means a cancellation does not depend on extras.
        cancelIdentity(context, medicineId, "legacy")
        // Schedule IDs are stable in Room. Cancel a broad range of IDs created for this medicine.
        // Stored schedule rows are also cancelled explicitly by cancelAllForMedicine.
        try {
            kotlinx.coroutines.runBlocking {
                db.medicineScheduleDao().getForMedicine(medicineId).forEach {
                    cancelIdentity(context, medicineId, it.id.toString())
                }
            }
        } catch (_: Exception) { }
    }

    private fun cancelIdentity(context: Context, medicineId: Long, identity: String) {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = "com.wafyro.doseleaf.REMINDER.$medicineId.$identity"
        }
        val requestCode = "$medicineId:$identity".hashCode()
        val pending = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pending != null) {
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending)
            pending.cancel()
        }
    }

    private fun isEnabled(context: Context) = context.getSharedPreferences("doseleaf_settings", Context.MODE_PRIVATE)
        .getBoolean("reminders", true)
}
