package com.aydin.biyohack.data.repository

import com.aydin.biyohack.data.ClinicalFlagRecord
import com.aydin.biyohack.data.DailySnapshot
import com.aydin.biyohack.data.IntakeKind
import com.aydin.biyohack.data.IntakeRecord
import com.aydin.biyohack.data.LabResult
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
        pullLabResultsFromRemote().getOrThrow()
    }
}
