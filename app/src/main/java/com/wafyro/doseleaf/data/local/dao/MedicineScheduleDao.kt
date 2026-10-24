package com.wafyro.doseleaf.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.wafyro.doseleaf.data.local.entity.MedicineScheduleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MedicineScheduleDao {
    @Query("SELECT * FROM medicine_schedules WHERE medicineId = :medicineId ORDER BY hour, minute")
    fun observeForMedicine(medicineId: Long): Flow<List<MedicineScheduleEntity>>

    @Query("SELECT * FROM medicine_schedules WHERE enabled = 1 ORDER BY hour, minute")
    suspend fun getEnabled(): List<MedicineScheduleEntity>

    @Query("SELECT * FROM medicine_schedules ORDER BY medicineId, hour, minute")
    fun observeAll(): Flow<List<MedicineScheduleEntity>>

    @Query("SELECT * FROM medicine_schedules WHERE medicineId = :medicineId ORDER BY hour, minute")
    suspend fun getForMedicine(medicineId: Long): List<MedicineScheduleEntity>

    @Query("SELECT * FROM medicine_schedules ORDER BY medicineId, hour, minute")
    suspend fun getAll(): List<MedicineScheduleEntity>

    @Insert
    suspend fun insert(schedule: MedicineScheduleEntity): Long

    @Insert
    suspend fun insertAll(schedules: List<MedicineScheduleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreAll(schedules: List<MedicineScheduleEntity>)

    @Query("DELETE FROM medicine_schedules")
    suspend fun clearAll()

    @Update
    suspend fun update(schedule: MedicineScheduleEntity)

    @Query("DELETE FROM medicine_schedules WHERE medicineId = :medicineId")
    suspend fun deleteForMedicine(medicineId: Long)

    @Query("DELETE FROM medicine_schedules WHERE id = :id")
    suspend fun deleteById(id: Long)
}
