package com.wafyro.doseleaf.backup

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import java.text.DateFormat
import java.util.Date
import android.net.Uri
import androidx.room.withTransaction
import com.wafyro.doseleaf.data.local.DoseleafDatabase
import com.wafyro.doseleaf.data.local.entity.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import android.util.Base64
import java.io.File
import androidx.core.content.FileProvider

object LocalExport {
    suspend fun exportDoseHistoryCsv(
        context: Context, uri: Uri, db: DoseleafDatabase,
        profileId: Long? = null, medicineQuery: String = "", status: String = "All"
    ) {
        val logs = db.doseLogDao().observeAll().first().filter { log ->
            (profileId == null || log.profileId == profileId) &&
            (medicineQuery.isBlank() || log.medicineNameSnapshot.contains(medicineQuery, ignoreCase = true)) &&
            (status == "All" || log.action.equals(status, ignoreCase = true))
        }
        context.contentResolver.openOutputStream(uri)?.use { stream ->
            OutputStreamWriter(stream, Charsets.UTF_8).use { writer ->
                writer.appendLine("medicine,profile_id,scheduled_at,recorded_at,status,note")
                logs.forEach { l -> writer.appendLine(listOf(csv(l.medicineNameSnapshot), l.profileId.toString(), l.scheduledAt.toString(), l.recordedAt.toString(), csv(l.action), csv(l.note)).joinToString(",")) }
            }
        } ?: error("Unable to open selected export destination")
    }


    suspend fun exportDoseHistoryPdf(
        context: Context, uri: Uri, db: DoseleafDatabase, fromMillis: Long, toMillis: Long,
        profileId: Long? = null, medicineQuery: String = "", status: String = "All"
    ) {
        require(fromMillis <= toMillis) { "Start date must be before end date." }
        val logs = db.doseLogDao().observeAll().first()
            .filter { it.recordedAt in fromMillis..toMillis }
            .filter { log -> (profileId == null || log.profileId == profileId) && (medicineQuery.isBlank() || log.medicineNameSnapshot.contains(medicineQuery, ignoreCase = true)) && (status == "All" || log.action.equals(status, ignoreCase = true)) }
            .sortedByDescending { it.recordedAt }
        val document = PdfDocument()
        val titlePaint = Paint().apply { textSize = 18f; isFakeBoldText = true }
        val bodyPaint = Paint().apply { textSize = 10f }
        val smallPaint = Paint().apply { textSize = 8f }
        val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        var pageNumber = 1
        var page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNumber).create())
        var canvas = page.canvas
        var y = 42f
        canvas.drawText("Doseleaf — Dose History Report", 36f, y, titlePaint); y += 24f
        canvas.drawText("Range: ${dateFormat.format(Date(fromMillis))} — ${dateFormat.format(Date(toMillis))}", 36f, y, bodyPaint); y += 18f
        canvas.drawText("This report records app entries; it does not verify that a dose was taken.", 36f, y, smallPaint); y += 22f
        canvas.drawText("Recorded time", 36f, y, bodyPaint)
        canvas.drawText("Medicine", 170f, y, bodyPaint)
        canvas.drawText("Status", 420f, y, bodyPaint); y += 14f
        logs.forEach { log ->
            if (y > 800f) {
                document.finishPage(page)
                pageNumber++
                page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNumber).create())
                canvas = page.canvas
                y = 42f
                canvas.drawText("Doseleaf — Dose History (continued)", 36f, y, titlePaint); y += 26f
                canvas.drawText("Recorded time", 36f, y, bodyPaint)
                canvas.drawText("Medicine", 170f, y, bodyPaint)
                canvas.drawText("Status", 420f, y, bodyPaint); y += 14f
            }
            canvas.drawText(dateFormat.format(Date(log.recordedAt)).take(25), 36f, y, smallPaint)
            canvas.drawText(log.medicineNameSnapshot.take(36), 170f, y, smallPaint)
            canvas.drawText(log.action.take(20), 420f, y, smallPaint)
            y += 15f
        }
        if (logs.isEmpty()) canvas.drawText("No entries in this date range.", 36f, y, bodyPaint)
        document.finishPage(page)
        context.contentResolver.openOutputStream(uri)?.use { document.writeTo(it) }
            ?: error("Unable to open selected PDF destination")
        document.close()
    }

    suspend fun exportBackupJson(context: Context, uri: Uri, db: DoseleafDatabase) {
        val profiles = db.familyProfileDao().observeAll().first()
        val medicines = db.medicineDao().observeCurrent().first() + db.medicineDao().observeRecycleBin().first()
        val logs = db.doseLogDao().observeAll().first()
        val schedules = db.medicineScheduleDao().getAll()
        val inventory = db.inventoryDao().observeAll().first()
        val root = JSONObject().put("format", "doseleaf-local-backup").put("schemaVersion", 6).put("createdAt", System.currentTimeMillis())
        root.put("profiles", JSONArray().apply { profiles.forEach { p -> put(JSONObject().put("id",p.id).put("name",p.name).put("relationship",p.relationship).put("createdAt",p.createdAt).put("isDefault",p.isDefault)) } })
        root.put("medicines", JSONArray().apply { medicines.forEach { m -> put(JSONObject().put("id",m.id).put("profileId",m.profileId).put("name",m.name).put("dosage",m.dosage).put("instructions",m.instructions).put("reminderHour",m.reminderHour).put("reminderMinute",m.reminderMinute).put("startDate",m.startDate).put("endDate",m.endDate ?: JSONObject.NULL).put("expiryDate",m.expiryDate ?: JSONObject.NULL).put("lowStockThreshold",m.lowStockThreshold).put("foodTiming",m.foodTiming).put("foodOffsetMinutes",m.foodOffsetMinutes).put("prescriptionPhotoUri",m.prescriptionPhotoUri ?: JSONObject.NULL).put("prescriptionPhotoBase64", encodePhoto(context, m.prescriptionPhotoUri)).put("isActive",m.isActive).put("isDeleted",m.isDeleted).put("deletedAt",m.deletedAt ?: JSONObject.NULL).put("createdAt",m.createdAt)) } })
        root.put("doseLogs", JSONArray().apply { logs.forEach { l -> put(JSONObject().put("id",l.id).put("medicineId",l.medicineId).put("profileId",l.profileId).put("medicineNameSnapshot",l.medicineNameSnapshot).put("scheduledAt",l.scheduledAt).put("action",l.action).put("recordedAt",l.recordedAt).put("note",l.note)) } })
        root.put("schedules", JSONArray().apply { schedules.forEach { x -> put(JSONObject().put("id",x.id).put("medicineId",x.medicineId).put("hour",x.hour).put("minute",x.minute).put("enabled",x.enabled)) } })
        root.put("inventory", JSONArray().apply { inventory.forEach { x -> put(JSONObject().put("id",x.id).put("medicineId",x.medicineId).put("changeAmount",x.changeAmount).put("reason",x.reason).put("createdAt",x.createdAt).put("note",x.note)) } })
        context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(root.toString(2)) } ?: error("Unable to open selected backup destination")
    }

    suspend fun restoreBackupJson(context: Context, uri: Uri, db: DoseleafDatabase) {
        val raw = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: error("Unable to read selected backup file")
        val root = JSONObject(raw)
        require(root.optString("format") == "doseleaf-local-backup") { "This is not a Doseleaf backup file." }
        require(root.optInt("schemaVersion", 1) in 1..6) { "This backup version is not supported." }
        fun arr(key: String) = root.optJSONArray(key) ?: JSONArray()
        fun JSONObject.nullLong(key: String): Long? = if (!has(key) || isNull(key)) null else optLong(key)
        fun JSONObject.nullString(key: String): String? = if (!has(key) || isNull(key)) null else optString(key)
        val profiles = buildList { val a=arr("profiles"); for(i in 0 until a.length()){ val o=a.getJSONObject(i); add(FamilyProfileEntity(o.getLong("id"),o.getString("name"),o.optString("relationship"),o.optLong("createdAt",System.currentTimeMillis()),o.optBoolean("isDefault"))) } }
        val medicines = buildList { val a=arr("medicines"); for(i in 0 until a.length()){ val o=a.getJSONObject(i); add(MedicineEntity(id=o.getLong("id"),profileId=o.getLong("profileId"),name=o.getString("name"),dosage=o.optString("dosage"),instructions=o.optString("instructions"),reminderHour=o.optInt("reminderHour",9),reminderMinute=o.optInt("reminderMinute",0),startDate=o.optLong("startDate",System.currentTimeMillis()),endDate=o.nullLong("endDate"),expiryDate=o.nullLong("expiryDate"),lowStockThreshold=o.optInt("lowStockThreshold",5),foodTiming=o.optString("foodTiming","None"),foodOffsetMinutes=o.optInt("foodOffsetMinutes",0),prescriptionPhotoUri=restorePhoto(context, o.optString("prescriptionPhotoBase64", ""), o.nullString("prescriptionPhotoUri"), o.optLong("id")),isActive=o.optBoolean("isActive",true),isDeleted=o.optBoolean("isDeleted"),deletedAt=o.nullLong("deletedAt"),createdAt=o.optLong("createdAt",System.currentTimeMillis()))) } }
        val logs = buildList { val a=arr("doseLogs"); for(i in 0 until a.length()){ val o=a.getJSONObject(i); add(DoseLogEntity(id=o.getLong("id"),medicineId=o.getLong("medicineId"),profileId=o.getLong("profileId"),medicineNameSnapshot=o.getString("medicineNameSnapshot"),scheduledAt=o.getLong("scheduledAt"),action=o.getString("action"),recordedAt=o.optLong("recordedAt",System.currentTimeMillis()),note=o.optString("note"))) } }
        val schedules = buildList { val a=arr("schedules"); for(i in 0 until a.length()){ val o=a.getJSONObject(i); add(MedicineScheduleEntity(o.getLong("id"),o.getLong("medicineId"),o.getInt("hour"),o.getInt("minute"),o.optBoolean("enabled",true))) } }
        val inventory = buildList { val a=arr("inventory"); for(i in 0 until a.length()){ val o=a.getJSONObject(i); add(InventoryEntity(o.getLong("id"),o.getLong("medicineId"),o.getInt("changeAmount"),o.optString("reason"),o.optLong("createdAt",System.currentTimeMillis()),o.optString("note"))) } }
        require(profiles.isNotEmpty()) { "Backup has no profiles; restore cancelled." }
        val pids=profiles.map { it.id }.toSet(); val mids=medicines.map { it.id }.toSet()
        require(medicines.all { it.profileId in pids }) { "Backup contains medicines with missing profiles." }
        require(logs.all { it.medicineId in mids } && schedules.all { it.medicineId in mids } && inventory.all { it.medicineId in mids }) { "Backup contains records with missing medicines." }
        db.withTransaction {
            db.inventoryDao().clearAll(); db.medicineScheduleDao().clearAll(); db.doseLogDao().clearAll(); db.medicineDao().clearAll(); db.familyProfileDao().clearAll()
            db.familyProfileDao().restoreAll(profiles); db.medicineDao().restoreAll(medicines); db.doseLogDao().restoreAll(logs)
            db.medicineScheduleDao().restoreAll(schedules); db.inventoryDao().restoreAll(inventory)
        }
    }
    private fun encodePhoto(context: Context, photoUri: String?): String? {
        if (photoUri.isNullOrBlank()) return null
        return runCatching {
            context.contentResolver.openInputStream(Uri.parse(photoUri))?.use { input ->
                val bytes = input.readBytes()
                // Avoid unexpectedly huge backup files from full-resolution camera images.
                if (bytes.size > 12 * 1024 * 1024) return@runCatching null
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            }
        }.getOrNull()
    }

    private fun restorePhoto(context: Context, base64: String, oldUri: String?, medicineId: Long): String? {
        if (base64.isBlank()) return oldUri
        return runCatching {
            val dir = File(context.filesDir, "prescriptions").apply { mkdirs() }
            val file = File(dir, "prescription-$medicineId.jpg")
            file.writeBytes(Base64.decode(base64, Base64.DEFAULT))
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file).toString()
        }.getOrElse { oldUri }
    }

    private fun csv(value: String) = "\"${value.replace("\"", "\"\"").replace("\n", " ")}\""
}
