package com.wafyro.doseleaf.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "medicine_schedules", indices = [Index("medicineId")])
data class MedicineScheduleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val medicineId: Long,
    val hour: Int,
    val minute: Int,
    val enabled: Boolean = true
)
