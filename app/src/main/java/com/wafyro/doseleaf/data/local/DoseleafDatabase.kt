package com.wafyro.doseleaf.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.wafyro.doseleaf.data.local.dao.DoseLogDao
import com.wafyro.doseleaf.data.local.dao.FamilyProfileDao
import com.wafyro.doseleaf.data.local.dao.InventoryDao
import com.wafyro.doseleaf.data.local.dao.MedicineDao
import com.wafyro.doseleaf.data.local.dao.MedicineScheduleDao
import com.wafyro.doseleaf.data.local.entity.DoseLogEntity
import com.wafyro.doseleaf.data.local.entity.FamilyProfileEntity
import com.wafyro.doseleaf.data.local.entity.InventoryEntity
import com.wafyro.doseleaf.data.local.entity.MedicineEntity
import com.wafyro.doseleaf.data.local.entity.MedicineScheduleEntity

@Database(
    entities = [
        FamilyProfileEntity::class, MedicineEntity::class, DoseLogEntity::class,
        MedicineScheduleEntity::class, InventoryEntity::class
    ],
    version = 5,
    exportSchema = true
)
abstract class DoseleafDatabase : RoomDatabase() {
    abstract fun familyProfileDao(): FamilyProfileDao
    abstract fun medicineDao(): MedicineDao
    abstract fun doseLogDao(): DoseLogDao
    abstract fun medicineScheduleDao(): MedicineScheduleDao
    abstract fun inventoryDao(): InventoryDao

    companion object {
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE medicines ADD COLUMN prescriptionPhotoUri TEXT")
            }
        }
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE medicines ADD COLUMN foodTiming TEXT NOT NULL DEFAULT 'None'")
                db.execSQL("ALTER TABLE medicines ADD COLUMN foodOffsetMinutes INTEGER NOT NULL DEFAULT 0")
            }
        }
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE medicines ADD COLUMN expiryDate INTEGER")
                db.execSQL("ALTER TABLE medicines ADD COLUMN lowStockThreshold INTEGER NOT NULL DEFAULT 5")
            }
        }
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `medicine_schedules` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `medicineId` INTEGER NOT NULL, `hour` INTEGER NOT NULL, `minute` INTEGER NOT NULL, `enabled` INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_medicine_schedules_medicineId` ON `medicine_schedules` (`medicineId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `inventory_transactions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `medicineId` INTEGER NOT NULL, `changeAmount` INTEGER NOT NULL, `reason` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `note` TEXT NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_inventory_transactions_medicineId` ON `inventory_transactions` (`medicineId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_inventory_transactions_createdAt` ON `inventory_transactions` (`createdAt`)")
            }
        }
        @Volatile private var instance: DoseleafDatabase? = null
        fun getInstance(context: Context): DoseleafDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, DoseleafDatabase::class.java, "doseleaf.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build().also { instance = it }
        }
    }
}
