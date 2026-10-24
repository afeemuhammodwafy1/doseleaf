package com.wafyro.doseleaf.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.wafyro.doseleaf.data.local.entity.InventoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface InventoryDao {
    @Insert
    suspend fun insert(transaction: InventoryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreAll(items: List<InventoryEntity>)

    @Query("DELETE FROM inventory_transactions")
    suspend fun clearAll()

    @Query("DELETE FROM inventory_transactions WHERE medicineId = :medicineId")
    suspend fun deleteForMedicine(medicineId: Long)

    @Query("SELECT * FROM inventory_transactions WHERE medicineId = :medicineId ORDER BY createdAt DESC")
    fun observeForMedicine(medicineId: Long): Flow<List<InventoryEntity>>

    @Query("SELECT * FROM inventory_transactions ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<InventoryEntity>>
}
