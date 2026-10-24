package com.wafyro.doseleaf.data.local.entity
import androidx.room.Entity
import androidx.room.PrimaryKey
@Entity(tableName = "family_profiles")
data class FamilyProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val relationship: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val isDefault: Boolean = false
)
