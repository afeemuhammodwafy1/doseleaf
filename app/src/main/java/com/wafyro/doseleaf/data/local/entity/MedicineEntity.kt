package com.wafyro.doseleaf.data.local.entity
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
@Entity(tableName = "medicines", indices = [Index("profileId"), Index("isDeleted")])
data class MedicineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val name: String,
    val dosage: String = "",
    val instructions: String = "",
    val reminderHour: Int = 9,
    val reminderMinute: Int = 0,
    val startDate: Long = System.currentTimeMillis(),
    val endDate: Long? = null,
    val expiryDate: Long? = null,
    val lowStockThreshold: Int = 5,
    val foodTiming: String = "None",
    val foodOffsetMinutes: Int = 0,
    val prescriptionPhotoUri: String? = null,
    val isActive: Boolean = true,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)
