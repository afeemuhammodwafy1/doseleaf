package com.wafyro.doseleaf.data.local.dao
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.wafyro.doseleaf.data.local.entity.MedicineEntity
import kotlinx.coroutines.flow.Flow
@Dao interface MedicineDao {
    @Query("SELECT * FROM medicines WHERE isDeleted = 0 ORDER BY isActive DESC, name COLLATE NOCASE")
    fun observeCurrent(): Flow<List<MedicineEntity>>
    @Query("SELECT * FROM medicines WHERE isDeleted = 1 ORDER BY deletedAt DESC")
    fun observeRecycleBin(): Flow<List<MedicineEntity>>
    @Query("SELECT * FROM medicines WHERE profileId = :profileId AND isDeleted = 0 ORDER BY name COLLATE NOCASE")
    fun observeForProfile(profileId: Long): Flow<List<MedicineEntity>>
    @Insert suspend fun insert(medicine: MedicineEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun restoreAll(items: List<MedicineEntity>)
    @Query("DELETE FROM medicines") suspend fun clearAll()
    @Update suspend fun update(medicine: MedicineEntity)
    @Query("UPDATE medicines SET isDeleted = 1, deletedAt = :deletedAt, isActive = 0 WHERE id = :id")
    suspend fun softDelete(id: Long, deletedAt: Long = System.currentTimeMillis())
    @Query("UPDATE medicines SET isDeleted = 0, deletedAt = NULL, isActive = 1 WHERE id = :id")
    suspend fun restore(id: Long)
    @Query("DELETE FROM medicines WHERE id = :id AND isDeleted = 1")
    suspend fun permanentlyDelete(id: Long)
    @Query("SELECT * FROM medicines WHERE id = :id LIMIT 1") suspend fun getById(id: Long): MedicineEntity?
}
