package com.wafyro.doseleaf.reminders
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wafyro.doseleaf.data.local.DoseleafDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                DoseleafDatabase.getInstance(context).medicineDao().observeCurrent().first()
                    .filter { it.isActive && !it.isDeleted }
                    .forEach { ReminderScheduler.schedule(context, it); ExpiryReminderScheduler.schedule(context, it.id, it.name, it.expiryDate) }
            } finally { pending.finish() }
        }
    }
}
