package com.wafyro.doseleaf.data.local.dao
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.wafyro.doseleaf.data.local.entity.DoseLogEntity
import kotlinx.coroutines.flow.Flow
@Dao interface DoseLogDao {
    @Insert suspend fun insert(log: DoseLogEntity): Long
    @Update suspend fun update(log: DoseLogEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun restoreAll(items: List<DoseLogEntity>)
    @Query("DELETE FROM dose_logs") suspend fun clearAll()
    @Query("SELECT * FROM dose_logs ORDER BY scheduledAt DESC") fun observeAll(): Flow<List<DoseLogEntity>>
    @Query("SELECT * FROM dose_logs WHERE profileId = :profileId ORDER BY scheduledAt DESC") fun observeForProfile(profileId: Long): Flow<List<DoseLogEntity>>
    @Query("DELETE FROM dose_logs WHERE id = :id") suspend fun delete(id: Long)
}
