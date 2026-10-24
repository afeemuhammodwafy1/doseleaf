package com.wafyro.doseleaf.data.local.dao
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.wafyro.doseleaf.data.local.entity.FamilyProfileEntity
import kotlinx.coroutines.flow.Flow
@Dao interface FamilyProfileDao {
    @Query("SELECT * FROM family_profiles ORDER BY isDefault DESC, name COLLATE NOCASE")
    fun observeAll(): Flow<List<FamilyProfileEntity>>
    @Insert suspend fun insert(profile: FamilyProfileEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun restoreAll(items: List<FamilyProfileEntity>)
    @Query("DELETE FROM family_profiles") suspend fun clearAll()
    @Update suspend fun update(profile: FamilyProfileEntity)
    @Query("DELETE FROM family_profiles WHERE id = :id") suspend fun deleteById(id: Long)
}
