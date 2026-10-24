package com.wafyro.doseleaf.reminders

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Color
import android.graphics.Typeface
import android.view.ViewGroup
import android.os.Handler
import android.os.Looper
import com.wafyro.doseleaf.data.local.DoseleafDatabase
import com.wafyro.doseleaf.data.local.entity.DoseLogEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

/** Full-screen alarm surface. Android/OS policy may still prevent it from opening over the lock screen. */
class AlarmActivity : Activity() {
    private var ringtone: Ringtone? = null
    private var medicineId: Long = 0L
    private var medicineName: String = "your medicine"
    private var scheduleHour: Int = -1
    private var scheduleMinute: Int = -1
    private var scheduleId: Long = 0L
    private val soundHandler = Handler(Looper.getMainLooper())
    private val soundRepeat = object : Runnable { override fun run() { if (isFinishing) return; if (ringtone?.isPlaying != true) startAlarmSound(); soundHandler.postDelayed(this, 12_000L) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        medicineId = intent.getLongExtra("medicineId", 0L)
        medicineName = intent.getStringExtra("medicineName") ?: "your medicine"
        scheduleHour = intent.getIntExtra("scheduleHour", -1)
        scheduleMinute = intent.getIntExtra("scheduleMinute", -1)
        scheduleId = intent.getLongExtra("scheduleId", 0L)
        val notificationAction = intent.getStringExtra("doseAction")
        if (notificationAction == "Taken" || notificationAction == "Skipped") {
            recordDose(notificationAction)
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        startAlarmSound()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 48, 32, 48)
            setBackgroundColor(Color.rgb(247, 250, 247))
        }
        root.addView(TextView(this).apply {
            text = "Doseleaf"
            textSize = 28f
            setTextColor(Color.rgb(31, 83, 58))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(TextView(this).apply {
            text = "Medicine reminder"
            textSize = 20f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 8)
        })
        root.addView(TextView(this).apply {
            text = "It's time to check your schedule for $medicineName."
            textSize = 18f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 32)
        })
        root.addView(Button(this).apply {
            text = "Snooze ${getSharedPreferences("doseleaf_settings", Context.MODE_PRIVATE).getInt("snooze_minutes", 10)} minutes"
            setOnClickListener { snooze(); finish() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 12 })
        root.addView(Button(this).apply {
            text = "Mark as taken"
            setOnClickListener { recordDose("Taken") }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 10 })
        root.addView(Button(this).apply {
            text = "Skip this dose"
            setOnClickListener { recordDose("Skipped") }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 10 })
        root.addView(Button(this).apply {
            text = "Stop alarm"
            setOnClickListener { stopSound(); finish() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
        soundHandler.postDelayed(soundRepeat, 12_000L)
    }

    private fun startAlarmSound() {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        ringtone = RingtoneManager.getRingtone(this, uri)?.also {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.isLooping = true
            it.play()
        }
    }

    private fun recordDose(action: String) {
        stopSound()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            var resultMessage = "Dose recorded."
            runCatching {
                val db = DoseleafDatabase.getInstance(applicationContext)
                val medicine = db.medicineDao().getById(medicineId)
                if (medicine == null || medicine.isDeleted || !medicine.isActive) {
                    resultMessage = "This medicine is no longer active."
                } else {
                    val now = System.currentTimeMillis()
                    val scheduledAt = java.util.Calendar.getInstance().apply {
                        if (scheduleHour in 0..23 && scheduleMinute in 0..59) {
                            set(java.util.Calendar.HOUR_OF_DAY, scheduleHour)
                            set(java.util.Calendar.MINUTE, scheduleMinute)
                            set(java.util.Calendar.SECOND, 0)
                            set(java.util.Calendar.MILLISECOND, 0)
                            if (timeInMillis > now + 30 * 60 * 1000L) add(java.util.Calendar.DAY_OF_YEAR, -1)
                        } else {
                            // Legacy alarms have no schedule metadata; use the nearest configured reminder time.
                            set(java.util.Calendar.HOUR_OF_DAY, medicine.reminderHour)
                            set(java.util.Calendar.MINUTE, medicine.reminderMinute)
                            set(java.util.Calendar.SECOND, 0)
                            set(java.util.Calendar.MILLISECOND, 0)
                            if (timeInMillis > now + 30 * 60 * 1000L) add(java.util.Calendar.DAY_OF_YEAR, -1)
                        }
                    }.timeInMillis
                    val withinWindow = kotlin.math.abs(now - scheduledAt) <= 30 * 60 * 1000L
                    if (!withinWindow) {
                        resultMessage = "This reminder is outside its 30-minute action window."
                    } else {
                        val existing = db.doseLogDao().observeAll().first()
                        val alreadyRecorded = existing.any { it.medicineId == medicine.id && kotlin.math.abs(it.scheduledAt - scheduledAt) <= 60_000L && (it.action == "Taken" || it.action == "Skipped") }
                        if (alreadyRecorded) {
                            resultMessage = "This dose was already recorded."
                        } else {
                            db.doseLogDao().insert(DoseLogEntity(
                                medicineId = medicine.id,
                                profileId = medicine.profileId,
                                medicineNameSnapshot = medicine.name,
                                scheduledAt = scheduledAt,
                                action = action,
                                note = "Recorded from reminder alarm"
                            ))
                            resultMessage = if (action == "Taken") "Dose marked as taken successfully." else "Dose skipped successfully."
                        }
                    }
                }
            }
            runOnUiThread {
                android.widget.Toast.makeText(this@AlarmActivity, resultMessage, android.widget.Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun snooze() {
        val alarm = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val snoozeIntent = Intent(this, ReminderReceiver::class.java).apply {
            action = "com.wafyro.doseleaf.SNOOZE.$medicineId.${System.currentTimeMillis()}"
            putExtra("medicineId", medicineId)
            putExtra("medicineName", medicineName)
        }
        val pending = PendingIntent.getBroadcast(this, (medicineId xor System.currentTimeMillis()).toInt(), snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val snoozeMinutes = getSharedPreferences("doseleaf_settings", Context.MODE_PRIVATE).getInt("snooze_minutes", 10).coerceIn(1, 60)
        val at = System.currentTimeMillis() + snoozeMinutes * 60 * 1000L
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarm.canScheduleExactAlarms()) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } else {
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    private fun stopSound() { soundHandler.removeCallbacks(soundRepeat); try { ringtone?.stop() } catch (_: Exception) { } }
    override fun onDestroy() { stopSound(); super.onDestroy() }
    override fun onBackPressed() { stopSound(); super.onBackPressed() }
}
