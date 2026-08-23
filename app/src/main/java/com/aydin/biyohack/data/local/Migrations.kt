package com.aydin.biyohack.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1 → v2: `profiles` tablosu eklendi (Hafta 2 — Supabase Auth + kullanıcı
 * profili). Kolon adları [ProfileEntity] ve supabase/schema.sql'deki
 * `profiles` tablosuyla birebir eşleşir; elle yazıldı çünkü Room bu
 * migration'ı KSP ile üretmiyor (şema diff'i yalnızca test amaçlı export
 * ediliyor, bkz. app/build.gradle.kts `room.schemaLocation`).
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `profiles` (
                `userId` TEXT NOT NULL,
                `fullName` TEXT NOT NULL,
                `birthYear` INTEGER,
                `sex` TEXT NOT NULL,
                `heightCm` REAL NOT NULL,
                `timezone` TEXT NOT NULL,
                `waterTargetMl` INTEGER NOT NULL,
                `proteinTargetMinG` INTEGER NOT NULL,
                `proteinTargetMaxG` INTEGER NOT NULL,
                `wakeTarget` TEXT NOT NULL,
                `bedEarliest` TEXT NOT NULL,
                `syncState` TEXT NOT NULL,
                PRIMARY KEY(`userId`)
            )
            """.trimIndent()
        )
    }
}

/** di/AppModule.kt'de `Room.databaseBuilder(...).addMigrations(*ALL_MIGRATIONS)` ile kullanılır. */
val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2)
