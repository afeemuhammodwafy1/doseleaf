package com.wafyro.doseleaf.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "inventory_transactions", indices = [Index("medicineId"), Index("createdAt")])
data class InventoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val medicineId: Long,
    val changeAmount: Int,
    val reason: String,
    val createdAt: Long = System.currentTimeMillis(),
    val note: String = ""
)
