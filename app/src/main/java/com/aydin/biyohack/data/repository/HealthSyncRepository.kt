package com.aydin.biyohack.data.repository

import com.aydin.biyohack.data.BodyMetric
import com.aydin.biyohack.data.ClinicalFlagRecord
import com.aydin.biyohack.data.DailySnapshot
import com.aydin.biyohack.data.IntakeKind
import com.aydin.biyohack.data.IntakeRecord
import com.aydin.biyohack.data.LabResult
import com.aydin.biyohack.data.SnapshotSource
import com.aydin.biyohack.data.local.BodyMetricDao
import com.aydin.biyohack.data.local.ClinicalFlagDao
import com.aydin.biyohack.data.local.DailySnapshotDao
import com.aydin.biyohack.data.local.IntakeRecordDao
import com.aydin.biyohack.data.local.LabResultDao
import com.aydin.biyohack.data.local.SyncState
import com.aydin.biyohack.data.local.toDomain
import com.aydin.biyohack.data.local.toEntity
import com.aydin.biyohack.data.remote.toDomain
import com.aydin.biyohack.data.remote.toRow
import com.aydin.biyohack.health.HealthDataSource
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Offline-first orkestrasyon: Health Connect → Room (anında, güvenilir) →
 * Supabase (best-effort, ağ yoksa PENDING kalır, HealthSyncWorker sonra dener).
 * UI hiçbir zaman doğrudan Supabase'e yazmaz — her zaman bu repository üzerinden.
 */
class HealthSyncRepository(
    private val dailySnapshotDao: DailySnapshotDao,
    private val intakeRecordDao: IntakeRecordDao,
    private val labResultDao: LabResultDao,
    private val clinicalFlagDao: ClinicalFlagDao,
    private val bodyMetricDao: BodyMetricDao,
    private val postgrest: Postgrest,
    private val healthDataSource: HealthDataSource,
    private val currentUserId: suspend () -> String?
) {
    fun observeRecentSnapshots(limit: Int = 14): Flow<List<DailySnapshot>> =
        dailySnapshotDao.observeRecent(limit).map { list -> list.map { it.toDomain() } }

    fun observeTodayIntake(): Flow<List<IntakeRecord>> {
        val startOfDay = LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant().toEpochMilli()
        val endOfDay = LocalDate.now().plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant().toEpochMilli()
        return intakeRecordDao.observeBetween(startOfDay, endOfDay)
            .map { list -> list.map { it.toDomain() } }
    }

    fun observeLabResults(): Flow<List<LabResult>> =
        labResultDao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeUnresolvedFlags(): Flow<List<ClinicalFlagRecord>> =
        clinicalFlagDao.observeUnresolved().map { list -> list.map { it.toDomain() } }

    fun observeRecentBodyMetrics(limit: Int = 30): Flow<List<BodyMetric>> =
        bodyMetricDao.observeRecent(limit).map { list -> list.map { it.toDomain() } }

    /**
     * Son kreatin logundan bu yana geçen gün sayısı — TwinGuardrails'in
     * "test öncesi ara" hatırlatmasında kullandığı sayaç (bkz. TwinState.creatineFreeDays).
     * Hiç log yoksa 0 döner (sayaç henüz başlamamış demektir, ihlal değil).
     */
    suspend fun creatineFreeDays(): Int {
        val last = intakeRecordDao.getLastCreatineLog() ?: return 0
        val lastDate = last.ts.atZone(ZoneId.systemDefault()).toLocalDate()
        return ChronoUnit.DAYS.between(lastDate, LocalDate.now()).toInt().coerceAtLeast(0)
    }

    /** Health Connect'ten bu geceyi okuyup Room'a yazar, ardından Supabase'e itmeyi dener. */
    suspend fun syncLastNightFromDevice(date: LocalDate = LocalDate.now()): Result<DailySnapshot?> =
        runCatching {
            val userId = currentUserId() ?: return@runCatching null
            val snapshot = healthDataSource.readSnapshotForNight(date, userId) ?: return@runCatching null
            dailySnapshotDao.upsert(snapshot.toEntity(SyncState.PENDING))
            pushPendingSnapshots()
            snapshot
        }

    /**
     * Health Connect'te bu gece için kayıt yoksa (cihaz takılmadı, izin
     * verilmedi, senkronizasyon henüz çalışmadı vb.) kullanıcının elle
     * girdiği uyku süresini `source = MANUAL` ile kaydeder. Önceden bu durumda
     * "Bu gece" kartı süresiz "Veri yok" kalıyordu ve TwinGuardrails her
     * defasında "VERİ YOK" fact'i üretiyordu — schema.sql'deki `MANUAL` kaynak
     * değeri hiçbir kod yolundan hiç yazılmıyordu.
     */
    suspend fun logManualSnapshot(date: LocalDate, asleepMin: Int): Result<Unit> = runCatching {
        val userId = currentUserId() ?: error("Oturum açık değil")
        val snapshot = DailySnapshot(
            userId = userId,
            date = date,
            asleepMin = asleepMin,
            source = SnapshotSource.MANUAL
        )
        dailySnapshotDao.upsert(snapshot.toEntity(SyncState.PENDING))
        pushPendingSnapshots()
        Unit
    }

    /** Kullanıcının manuel logu — su/kahve/öğün/takviye. Anında yerelde görünür. */
    suspend fun logIntake(kind: IntakeKind, label: String, amount: Double?, unit: String?): Result<Unit> =
        runCatching {
            val userId = currentUserId() ?: error("Oturum açık değil")
            val record = IntakeRecord(
                id = UUID.randomUUID().toString(),
                userId = userId,
                ts = java.time.Instant.now(),
                kind = kind,
                label = label,
                amount = amount,
                unit = unit
            )
            intakeRecordDao.insert(record.toEntity(SyncState.PENDING))
            pushPendingIntake()
            Unit
        }

    /** Elle laboratuvar sonucu ekler (ör. web panelini beklemeden cihazdan). */
    suspend fun addLabResult(
        panel: String,
        marker: String,
        value: Double,
        unit: String?,
        refLow: Double?,
        refHigh: Double?,
        takenAt: LocalDate,
        notes: String? = null
    ): Result<Unit> = runCatching {
        val userId = currentUserId() ?: error("Oturum açık değil")
        val result = LabResult(
            id = UUID.randomUUID().toString(),
            userId = userId,
            panel = panel,
            marker = marker,
            value = value,
            unit = unit,
            refLow = refLow,
            refHigh = refHigh,
            takenAt = takenAt,
            notes = notes
        )
        labResultDao.upsert(result.toEntity(SyncState.PENDING))
        pushPendingLabResults()
        Unit
    }

    /** Elle klinik bayrak ekler — TwinGuardrails'in ürettiklerine ek olarak kullanıcı da açabilir. */
    suspend fun addClinicalFlag(finding: String, status: String, action: String = "none"): Result<Unit> =
        runCatching {
            val userId = currentUserId() ?: error("Oturum açık değil")
            val flag = ClinicalFlagRecord(
                id = UUID.randomUUID().toString(),
                userId = userId,
                finding = finding,
                status = status,
                action = action
            )
            clinicalFlagDao.insert(flag.toEntity(SyncState.PENDING))
            pushPendingClinicalFlags()
            Unit
        }

    suspend fun resolveClinicalFlag(id: String): Result<Unit> = runCatching {
        clinicalFlagDao.markResolved(id)
        pushPendingClinicalFlags()
        Unit
    }

    /** Bugünkü kilo/bel çevresi ölçümünü kaydeder — aynı gün tekrar çağrılırsa üzerine yazar. */
    suspend fun logBodyMetric(weightKg: Double?, waistCm: Double?, notes: String? = null): Result<Unit> =
        runCatching {
            val userId = currentUserId() ?: error("Oturum açık değil")
            val metric = BodyMetric(userId = userId, date = LocalDate.now(), weightKg = weightKg, waistCm = waistCm, notes = notes)
            bodyMetricDao.upsert(metric.toEntity(SyncState.PENDING))
            pushPendingBodyMetrics()
            Unit
        }

    suspend fun pushPendingBodyMetrics() = runCatching {
        bodyMetricDao.getPending().forEach { entity ->
            val row = entity.toDomain().toRow()
            postgrest.from("body_metric").upsert(row, onConflict = "user_id,date")
            bodyMetricDao.markSynced(entity.epochDay)
        }
    }

    suspend fun pushPendingSnapshots() = runCatching {
        dailySnapshotDao.getPending().forEach { entity ->
            val row = entity.toDomain().toRow()
            postgrest.from("daily_snapshot")
                .upsert(row, onConflict = "user_id,date")
            dailySnapshotDao.markSynced(entity.epochDay)
        }
    }

    suspend fun pushPendingIntake() = runCatching {
        intakeRecordDao.getPending().forEach { entity ->
            val row = entity.toDomain().toRow()
            postgrest.from("intake_entry").upsert(row, onConflict = "id")
            intakeRecordDao.markSynced(entity.id)
        }
    }

    suspend fun pushPendingLabResults() = runCatching {
        labResultDao.getPending().forEach { entity ->
            val row = entity.toDomain().toRow()
            postgrest.from("lab_result").upsert(row, onConflict = "id")
            labResultDao.markSynced(entity.id)
        }
    }

    suspend fun pushPendingClinicalFlags() = runCatching {
        clinicalFlagDao.getPending().forEach { entity ->
            val row = entity.toDomain().toRow()
            postgrest.from("clinical_flag").upsert(row, onConflict = "id")
            clinicalFlagDao.markSynced(entity.id)
        }
    }

    /** Panelden (web) elle girilen laboratuvar sonuçlarını cihaza çeker. */
    suspend fun pullLabResultsFromRemote(): Result<Unit> = runCatching {
        val userId = currentUserId() ?: error("Oturum açık değil")
        postgrest.from("lab_result")
            .select { filter { eq("user_id", userId) } }
            .decodeList<com.aydin.biyohack.data.remote.LabResultRow>()
            .forEach { labResultDao.upsert(it.toDomain().toEntity(SyncState.SYNCED)) }
    }

    /** WorkManager tetikleyicisi ve "Şimdi Senkronize Et" butonunun ortak giriş noktası. */
    suspend fun syncAll(): Result<Unit> = runCatching {
        syncLastNightFromDevice().getOrThrow()
        pushPendingSnapshots().getOrThrow()
        pushPendingIntake().getOrThrow()
        pushPendingLabResults().getOrThrow()
        pushPendingClinicalFlags().getOrThrow()
        pushPendingBodyMetrics().getOrThrow()
        pullLabResultsFromRemote().getOrThrow()
    }
}
