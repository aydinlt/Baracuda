package com.aydin.biyohack.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        DailySnapshotEntity::class,
        IntakeRecordEntity::class,
        LabResultEntity::class,
        ClinicalFlagEntity::class,
        ProfileEntity::class,
        BodyMetricEntity::class,
        QuickTemplateEntity::class,
        LabResultTemplateEntity::class
    ],
    version = 5,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dailySnapshotDao(): DailySnapshotDao
    abstract fun intakeRecordDao(): IntakeRecordDao
    abstract fun labResultDao(): LabResultDao
    abstract fun clinicalFlagDao(): ClinicalFlagDao
    abstract fun profileDao(): ProfileDao
    abstract fun bodyMetricDao(): BodyMetricDao
    abstract fun quickTemplateDao(): QuickTemplateDao
    abstract fun labResultTemplateDao(): LabResultTemplateDao

    companion object {
        const val DB_NAME = "biyohack.db"
    }
}
