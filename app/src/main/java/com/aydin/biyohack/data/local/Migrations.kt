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

/**
 * v2 → v3: `body_metric` tablosu eklendi (Hafta 11 — kilo/bel çevresi
 * takibi). Kolon adları [BodyMetricEntity] ve supabase/schema.sql'deki
 * `body_metric` tablosuyla birebir eşleşir.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `body_metric` (
                `epochDay` INTEGER NOT NULL,
                `userId` TEXT NOT NULL,
                `weightKg` REAL,
                `waistCm` REAL,
                `notes` TEXT,
                `syncState` TEXT NOT NULL,
                PRIMARY KEY(`epochDay`)
            )
            """.trimIndent()
        )
    }
}

/**
 * v3 → v4: `quick_template` tablosu eklendi (Hafta 41 — Hızlı Şablonlar ve
 * Favoriler). Kolon adları [QuickTemplateEntity] ve supabase/schema.sql'deki
 * `quick_template` tablosuyla birebir eşleşir; `createdAt` Converters.kt'deki
 * Instant↔epoch-millis dönüştürücüsü yüzünden INTEGER'dır.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `quick_template` (
                `id` TEXT NOT NULL,
                `userId` TEXT NOT NULL,
                `kind` TEXT NOT NULL,
                `label` TEXT NOT NULL,
                `amount` REAL,
                `unit` TEXT,
                `createdAt` INTEGER NOT NULL,
                `syncState` TEXT NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
    }
}

/**
 * v4 → v5: `lab_result_template` tablosu eklendi (Hafta 42 — LabScreen için
 * "sık tekrarlanan panel" şablonu, quick_template'in laboratuvar karşılığı).
 * `quick_template` şeması bu sürümde değişmedi (yalnızca yeni bir `update`
 * DAO metodu eklendi, kolon eklenmedi) — bu yüzden ayrı bir migration adımı
 * gerektirmedi.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `lab_result_template` (
                `id` TEXT NOT NULL,
                `userId` TEXT NOT NULL,
                `panel` TEXT NOT NULL,
                `marker` TEXT NOT NULL,
                `unit` TEXT,
                `refLow` REAL,
                `refHigh` REAL,
                `createdAt` INTEGER NOT NULL,
                `syncState` TEXT NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
    }
}

/**
 * v5 → v6: `profiles` tablosuna `stepsTarget` kolonu eklendi (Hafta 45 —
 * adım hedefi artık su/protein/kalkış gibi Ayarlar'dan düzenlenebilir).
 * Varsayılan 10000, mevcut satırları bozmadan tüm var olan profillere
 * uygulanır (bkz. DashboardScreen'deki eski sabit değer).
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `profiles` ADD COLUMN `stepsTarget` INTEGER NOT NULL DEFAULT 10000")
    }
}

/** di/AppModule.kt'de `Room.databaseBuilder(...).addMigrations(*ALL_MIGRATIONS)` ile kullanılır. */
val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
