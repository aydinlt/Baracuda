package com.aydin.biyohack.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v1 → v2 migration'ın veri kaybetmeden ve şema uyumsuzluğu olmadan
 * çalıştığını doğrular.
 *
 * ÇALIŞTIRMADAN ÖNCE: bu testin derlenebilmesi/geçebilmesi için önce en az
 * bir kez `./gradlew assembleDebug` (ya da compileDebugKotlin) çalıştırıp
 * app/schemas/com.aydin.biyohack.data.local.AppDatabase/1.json ve 2.json
 * dosyalarının üretilmiş olması ve commitlenmiş olması gerekir — bunlar
 * build çıktısı değil, versiyon kontrollü referans şemalardır (bkz.
 * app/build.gradle.kts: `ksp { arg("room.schemaLocation", ...) }` ve
 * `sourceSets { androidTest.assets.srcDirs(...) }`).
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

    companion object {
        private const val TEST_DB = "migration-test"
    }
}
