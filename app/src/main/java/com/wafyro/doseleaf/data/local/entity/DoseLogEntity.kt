package com.wafyro.doseleaf.data.local.entity
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
@Entity(tableName = "dose_logs", indices = [Index("medicineId"), Index("scheduledAt")])
data class DoseLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val medicineId: Long,
    val profileId: Long,
    val medicineNameSnapshot: String,
    val scheduledAt: Long,
    val action: String,
    val recordedAt: Long = System.currentTimeMillis(),
    val note: String = ""
)
