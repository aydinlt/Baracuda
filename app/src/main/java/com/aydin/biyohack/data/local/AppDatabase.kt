package com.aydin.biyohack.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        DailySnapshotEntity::class,
        IntakeRecordEntity::class,
        LabResultEntity::class,
        ClinicalFlagEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dailySnapshotDao(): DailySnapshotDao
    abstract fun intakeRecordDao(): IntakeRecordDao
    abstract fun labResultDao(): LabResultDao
    abstract fun clinicalFlagDao(): ClinicalFlagDao

    companion object {
        const val DB_NAME = "biyohack.db"
    }
}
