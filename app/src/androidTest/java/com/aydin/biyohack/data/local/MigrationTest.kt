package com.aydin.biyohack.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v1 → v6 migration'ların veri kaybetmeden ve şema uyumsuzluğu olmadan
 * çalıştığını doğrular (bkz. Migrations.kt).
 *
 * DURUM (bkz. Hafta 56 commit notu): Bu dosyadaki 6 test de ŞU AN
 * ÇALIŞTIRILAMAZ — `app/schemas/` dizini bu repoda hiç var olmadı,
 * yalnızca `.gitignore`'daki `!app/schemas/` istisnası ve bu yorumun
 * kendisi onun commitlenmesi GEREKTİĞİNİ söylüyordu. `MigrationTestHelper.
 * createDatabase(name, version)` her sürüm için `app/schemas/
 * com.aydin.biyohack.data.local.AppDatabase/{version}.json` şema
 * export'unu classpath/test-assets üzerinden okur; bu dosyalar yoksa
 * testler derleme sırasında değil, ÇALIŞTIRMA anında "şema bulunamadı"
 * hatasıyla başarısız olur (derleme başarılı görünür, testler kırmızı
 * geçer). Bu ajan bu JSON'ları elle üretmeyi denemedi — Room'un
 * identityHash'i ve alan/index metaverisi KSP'nin gerçek derleme çıktısı
 * olmalı, elle yazılmış yanlış bir şema sessizce yanlış doğrulama yapabilir
 * (belgelenmiş bir eksiklikten daha kötü).
 *
 * TEK SEFERLİK DÜZELTME (bir Android SDK'sı kurulu makinede):
 *   ./gradlew assembleDebug
 *   git add app/schemas && git commit -m "app/schemas: 1-6 sürüm export'ları"
 * Bundan sonra bu 6 test normal şekilde çalışır (`./gradlew
 * connectedAndroidTest` veya Android Studio'dan). Yeni bir migration
 * eklenince (`MIGRATION_N_(N+1)`), `assembleDebug` otomatik olarak
 * yalnızca YENİ sürümün JSON'ını üretir — var olanlar bozulmaz, bu yüzden
 * bu adım her yeni migration'da tek seferlik tekrarlanır.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate1To2_addsProfilesTableWithoutTouchingExistingData() {
        // v1 şemasıyla veritabanını oluştur ve içine bir satır yaz —
        // migration'ın mevcut tabloları bozmadığını kanıtlamak için.
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                """
                INSERT INTO daily_snapshot
                    (epochDay, userId, asleepMin, timeInBedMin, efficiencyPct, sleepScore,
                     remPct, deepPct, awakeMin, bedTime, wakeTime, spo2Avg, minutesBelow90,
                     minutesBelow90IsEstimate, snoringMin, source, syncState)
                VALUES (19000, 'test-user', 420, 480, 87, NULL, 20, 15, 30, NULL, NULL,
                        96.5, 5, 1, NULL, 'HEALTH_CONNECT', 'SYNCED')
                """.trimIndent()
            )
            close()
        }

        // Migration'ı çalıştır: yeni `profiles` şemasını doğrular, eski veriyi korur.
        helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2).apply {
            val cursor = query("SELECT userId FROM daily_snapshot WHERE epochDay = 19000")
            cursor.use { assert(it.moveToFirst()) { "v1'de yazılan satır migration sonrası kayboldu" } }
            close()
        }
    }

    @Test
    fun migrate2To3_addsBodyMetricTableWithoutTouchingExistingData() {
        // v2 şemasıyla veritabanını oluştur ve profiles'a bir satır yaz.
        helper.createDatabase(TEST_DB, 2).apply {
            execSQL(
                """
                INSERT INTO profiles
                    (userId, fullName, birthYear, sex, heightCm, timezone, waterTargetMl,
                     proteinTargetMinG, proteinTargetMaxG, wakeTarget, bedEarliest, syncState)
                VALUES ('test-user', 'Test', NULL, 'male', 180.0, 'Europe/Vilnius', 4000,
                        140, 170, '07:00', '23:00', 'SYNCED')
                """.trimIndent()
            )
            close()
        }

        // Migration'ı çalıştır: yeni `body_metric` şemasını doğrular, eski veriyi korur.
        helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_2_3).apply {
            val cursor = query("SELECT userId FROM profiles WHERE userId = 'test-user'")
            cursor.use { assert(it.moveToFirst()) { "v2'de yazılan profiles satırı migration sonrası kayboldu" } }
            close()
        }
    }

    @Test
    fun migrate3To4_addsQuickTemplateTableWithoutTouchingExistingData() {
        // v3 şemasıyla veritabanını oluştur ve body_metric'e bir satır yaz.
        helper.createDatabase(TEST_DB, 3).apply {
            execSQL(
                """
                INSERT INTO body_metric (epochDay, userId, weightKg, waistCm, notes, syncState)
                VALUES (19000, 'test-user', 84.0, 92.0, NULL, 'SYNCED')
                """.trimIndent()
            )
            close()
        }

        // Migration'ı çalıştır: yeni `quick_template` şemasını doğrular, eski veriyi korur,
        // ve yeni tabloya bir satır yazılabildiğini kanıtlar (kolon adları/tipler tutarlı mı).
        helper.runMigrationsAndValidate(TEST_DB, 4, true, MIGRATION_3_4).apply {
            val bodyMetricCursor = query("SELECT userId FROM body_metric WHERE epochDay = 19000")
            bodyMetricCursor.use { assert(it.moveToFirst()) { "v3'te yazılan body_metric satırı migration sonrası kayboldu" } }

            execSQL(
                """
                INSERT INTO quick_template (id, userId, kind, label, amount, unit, createdAt, syncState)
                VALUES ('t1', 'test-user', 'COFFEE', 'Standart Sabah Kahvesi', NULL, NULL, 1700000000000, 'PENDING')
                """.trimIndent()
            )
            val templateCursor = query("SELECT label FROM quick_template WHERE id = 't1'")
            templateCursor.use { assert(it.moveToFirst()) { "quick_template'e yazılan satır okunamadı" } }
            close()
        }
    }

    @Test
    fun migrate4To5_addsLabResultTemplateTableWithoutTouchingExistingData() {
        // v4 şemasıyla veritabanını oluştur ve quick_template'e bir satır yaz.
        helper.createDatabase(TEST_DB, 4).apply {
            execSQL(
                """
                INSERT INTO quick_template (id, userId, kind, label, amount, unit, createdAt, syncState)
                VALUES ('t1', 'test-user', 'COFFEE', 'Standart Sabah Kahvesi', NULL, NULL, 1700000000000, 'SYNCED')
                """.trimIndent()
            )
            close()
        }

        // Migration'ı çalıştır: yeni `lab_result_template` şemasını doğrular, eski veriyi
        // korur, ve yeni tabloya bir satır yazılabildiğini kanıtlar.
        helper.runMigrationsAndValidate(TEST_DB, 5, true, MIGRATION_4_5).apply {
            val templateCursor = query("SELECT label FROM quick_template WHERE id = 't1'")
            templateCursor.use { assert(it.moveToFirst()) { "v4'te yazılan quick_template satırı migration sonrası kayboldu" } }

            execSQL(
                """
                INSERT INTO lab_result_template (id, userId, panel, marker, unit, refLow, refHigh, createdAt, syncState)
                VALUES ('lt1', 'test-user', 'BÖBREK', 'eGFR', 'mL/min', 90.0, NULL, 1700000000000, 'PENDING')
                """.trimIndent()
            )
            val labTemplateCursor = query("SELECT marker FROM lab_result_template WHERE id = 'lt1'")
            labTemplateCursor.use { assert(it.moveToFirst()) { "lab_result_template'e yazılan satır okunamadı" } }
            close()
        }
    }

    @Test
    fun migrate5To6_addsStepsTargetColumnWithDefaultWithoutTouchingExistingData() {
        // v5 şemasıyla veritabanını oluştur ve profiles'a (stepsTarget kolonu OLMADAN) bir satır yaz.
        helper.createDatabase(TEST_DB, 5).apply {
            execSQL(
                """
                INSERT INTO profiles
                    (userId, fullName, birthYear, sex, heightCm, timezone, waterTargetMl,
                     proteinTargetMinG, proteinTargetMaxG, wakeTarget, bedEarliest, syncState)
                VALUES ('test-user', 'Test', NULL, 'male', 180.0, 'Europe/Vilnius', 4000,
                        140, 170, '07:00', '23:00', 'SYNCED')
                """.trimIndent()
            )
            close()
        }

        // Migration'ı çalıştır: eski satır kaybolmaz ve yeni `stepsTarget` kolonu
        // DEFAULT 10000 ile otomatik doldurulur (ALTER TABLE ... ADD COLUMN ... DEFAULT).
        helper.runMigrationsAndValidate(TEST_DB, 6, true, MIGRATION_5_6).apply {
            val cursor = query("SELECT userId, stepsTarget FROM profiles WHERE userId = 'test-user'")
            cursor.use {
                assert(it.moveToFirst()) { "v5'te yazılan profiles satırı migration sonrası kayboldu" }
                val stepsTarget = it.getInt(it.getColumnIndexOrThrow("stepsTarget"))
                assert(stepsTarget == 10000) { "stepsTarget varsayılanı 10000 değil: $stepsTarget" }
            }
            close()
        }
    }

    companion object {
        private const val TEST_DB = "migration-test"
    }
}
