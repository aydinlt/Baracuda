package com.aydin.biyohack.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.aydin.biyohack.data.DailySnapshot
import com.aydin.biyohack.data.SnapshotSource
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Cihazdaki sağlık verisine tek okuma kapısı.
 *
 * Neden ayrı bir "Samsung Health SDK" entegrasyonu YOK: Samsung Health,
 * kullanıcı ayarlarında "Health Connect ile eşitle" açık olduğu sürece uyku
 * ve SpO2 verisini zaten Health Connect'e yazıyor. Samsung'un native Partner
 * SDK'sı (com.samsung.android.sdk.health) ayrı başvuru/onay gerektirir ve
 * Health Connect'te bulunmayan tipler (sürekli stres, EKG vb.) dışında ek
 * bir şey kazandırmaz. Bu yüzden [HealthDataSource] arayüzü kaynaktan
 * bağımsız tutuldu — Samsung Health SDK onayı gelirse `SamsungHealthDataSource`
 * aynı arayüzü implemente ederek eklenir, üst katmanlar değişmez.
 */
interface HealthDataSource {
    suspend fun readSnapshotForNight(date: LocalDate, userId: String): DailySnapshot?

    /**
     * Bugünün şu ana kadarki adım toplamı. system_twin.md Bölüm A "10.000 adım
     * hedefi"nden söz eder ama önceden uygulamada adım verisini okuyan hiçbir
     * kod yolu yoktu — Health Connect izinleri yalnızca uyku/SpO2 kapsıyordu.
     * Kalıcı bir kayıt değil (DailySnapshot'ın aksine Room/Supabase'e yazılmaz) —
     * gün içinde değişen anlık bir okuma, "Bugünkü loglar" gibi canlı gösterilir.
     */
    suspend fun readTodaySteps(): Long?
}

enum class HealthConnectAvailability { INSTALLED, NOT_INSTALLED, NOT_SUPPORTED }

class HealthConnectManager(private val context: Context) : HealthDataSource {

    private val client: HealthConnectClient? by lazy {
        if (availability() == HealthConnectAvailability.INSTALLED)
            HealthConnectClient.getOrCreate(context)
        else null
    }

    fun availability(): HealthConnectAvailability =
        when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> HealthConnectAvailability.INSTALLED
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                HealthConnectAvailability.NOT_INSTALLED
            else -> HealthConnectAvailability.NOT_SUPPORTED
        }

    /** Compose'da `rememberLauncherForActivityResult` ile kullanılacak izin sözleşmesi. */
    fun requestPermissionsContract() = PermissionController.createRequestPermissionResultContract()

    suspend fun hasAllPermissions(): Boolean {
        val c = client ?: return false
        return c.permissionController.getGrantedPermissions().containsAll(PERMISSIONS)
    }

    /**
     * Verilen tarihin gecesini okur: [tarih-1 20:00, tarih 12:00) penceresinde
     * en uzun uyku oturumunu bulur, evre sürelerini ve o pencereye denk gelen
     * SpO2 örneklerini özetleyip [DailySnapshot] üretir. Uyku oturumu yoksa null.
     */
    override suspend fun readSnapshotForNight(date: LocalDate, userId: String): DailySnapshot? {
        val c = client ?: return null
        val zone = ZoneId.systemDefault()
        val windowStart = date.minusDays(1).atTime(LocalTime.of(20, 0)).atZone(zone).toInstant()
        val windowEnd = date.atTime(LocalTime.of(12, 0)).atZone(zone).toInstant()

        val sessions = c.readRecords(
            ReadRecordsRequest(
                recordType = SleepSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(windowStart, windowEnd)
            )
        ).records

        val session = sessions.maxByOrNull { Duration.between(it.startTime, it.endTime) }
            ?: return null

        val totalMin = Duration.between(session.startTime, session.endTime).toMinutes().toInt()
        val stages = session.stages
        fun stageMinutes(vararg types: Int) =
            stages.filter { it.stage in types }
                .sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }.toInt()

        val remMin = stageMinutes(SleepSessionRecord.STAGE_TYPE_REM)
        val deepMin = stageMinutes(SleepSessionRecord.STAGE_TYPE_DEEP)
        val awakeMin = stageMinutes(
            SleepSessionRecord.STAGE_TYPE_AWAKE,
            SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED
        )
        val asleepMin = (totalMin - awakeMin).coerceAtLeast(0)
        val efficiencyPct = if (totalMin > 0) (asleepMin * 100 / totalMin) else null

        val spo2Records = c.readRecords(
            ReadRecordsRequest(
                recordType = OxygenSaturationRecord::class,
                timeRangeFilter = TimeRangeFilter.between(session.startTime, session.endTime)
            )
        ).records

        val spo2Avg = spo2Records.map { it.percentage.value }.takeIf { it.isNotEmpty() }?.average()
        val minutesBelow90 = if (spo2Records.isEmpty()) null else {
            // Örneklem noktalarından tahmin: her nokta yaklaşık kendi aralığını temsil eder,
            // bu yüzden minutesBelow90IsEstimate = true ile işaretlenir (bkz. DailySnapshot).
            val belowCount = spo2Records.count { it.percentage.value < 90.0 }
            if (spo2Records.size <= 1) belowCount
            else (belowCount * totalMin / spo2Records.size)
        }

        return DailySnapshot(
            userId = userId,
            date = date,
            asleepMin = asleepMin,
            timeInBedMin = totalMin,
            efficiencyPct = efficiencyPct,
            sleepScore = null, // Health Connect skor sağlamıyor; cihaz-native skor varsa Hafta 2'de eklenir
            remPct = if (asleepMin > 0) (remMin * 100 / asleepMin) else null,
            deepPct = if (asleepMin > 0) (deepMin * 100 / asleepMin) else null,
            awakeMin = awakeMin,
            bedTime = session.startTime,
            wakeTime = session.endTime,
            spo2Avg = spo2Avg,
            minutesBelow90 = minutesBelow90,
            minutesBelow90IsEstimate = true,
            snoringMin = null, // Health Connect'te horlama kaydı yok; Samsung Health kendi UI'ında tutuyor
            source = SnapshotSource.HEALTH_CONNECT
        )
    }

    /** Bugün 00:00'dan şu ana kadarki StepsRecord'ları toplar. İzin yoksa/kayıt yoksa null. */
    override suspend fun readTodaySteps(): Long? {
        val c = client ?: return null
        val zone = ZoneId.systemDefault()
        val startOfDay = LocalDate.now().atStartOfDay(zone).toInstant()
        val now = Instant.now()

        val records = c.readRecords(
            ReadRecordsRequest(
                recordType = StepsRecord::class,
                timeRangeFilter = TimeRangeFilter.between(startOfDay, now)
            )
        ).records

        return records.sumOf { it.count }
    }

    companion object {
        val PERMISSIONS = setOf(
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getReadPermission(OxygenSaturationRecord::class),
            HealthPermission.getReadPermission(StepsRecord::class)
        )
    }
}
