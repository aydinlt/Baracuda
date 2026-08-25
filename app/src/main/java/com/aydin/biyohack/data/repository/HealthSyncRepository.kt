package com.aydin.biyohack.data.repository

import com.aydin.biyohack.data.BodyMetric
import com.aydin.biyohack.data.ClinicalFlagRecord
import com.aydin.biyohack.data.DailySnapshot
import com.aydin.biyohack.data.IntakeKind
import com.aydin.biyohack.data.IntakeRecord
import com.aydin.biyohack.data.LabResult
import com.aydin.biyohack.data.LabResultTemplate
import com.aydin.biyohack.data.QuickTemplate
import com.aydin.biyohack.data.SnapshotSource
import com.aydin.biyohack.data.local.BodyMetricDao
import com.aydin.biyohack.data.local.ClinicalFlagDao
import com.aydin.biyohack.data.local.DailySnapshotDao
import com.aydin.biyohack.data.local.IntakeRecordDao
import com.aydin.biyohack.data.local.LabResultDao
import com.aydin.biyohack.data.local.LabResultTemplateDao
import com.aydin.biyohack.data.local.QuickTemplateDao
import com.aydin.biyohack.data.local.SyncState
import com.aydin.biyohack.data.local.toDomain
import com.aydin.biyohack.data.local.toEntity
import com.aydin.biyohack.data.remote.toDomain
import com.aydin.biyohack.data.remote.toRow
import com.aydin.biyohack.health.HealthDataSource
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
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
    private val quickTemplateDao: QuickTemplateDao,
    private val labResultTemplateDao: LabResultTemplateDao,
    private val postgrest: Postgrest,
    private val healthDataSource: HealthDataSource,
    private val currentUserId: suspend () -> String?
) {
    fun observeRecentSnapshots(limit: Int = 14): Flow<List<DailySnapshot>> =
        dailySnapshotDao.observeRecent(limit).map { list -> list.map { it.toDomain() } }

    /**
     * ÖNEMLİ: "bugün" sınırı önceden bu fonksiyon ÇAĞRILDIĞI anda tek seferlik
     * hesaplanıyordu. DashboardViewModel gibi uzun ömürlü bir collector Activity
     * yeniden oluşturulmadan gece yarısını geçerse, Room'un Flow'u tablo
     * değişmediği sürece hiç yeniden emit etmediği için "bugünkü loglar" listesi
     * sessizce dünün penceresinde kilitli kalırdı — su/protein ilerleme çubukları
     * ve TwinGuardrails'e giden todayIntake de aynı şekilde yanlış günü gösterirdi.
     * [dateChangeTicker] her gece yarısı yeni bir tarih yayınlayıp alt sorguyu
     * [flatMapLatest] ile yeniden kurar.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeTodayIntake(): Flow<List<IntakeRecord>> =
        dateChangeTicker().flatMapLatest { today ->
            val zone = ZoneId.systemDefault()
            val startOfDay = today.atStartOfDay(zone).toInstant().toEpochMilli()
            val endOfDay = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            intakeRecordDao.observeBetween(startOfDay, endOfDay)
        }.map { list -> list.map { it.toDomain() } }

    private fun dateChangeTicker(): Flow<LocalDate> = flow {
        while (true) {
            val today = LocalDate.now()
            emit(today)
            val zone = ZoneId.systemDefault()
            val nextMidnight = today.plusDays(1).atStartOfDay(zone)
            delay(Duration.between(ZonedDateTime.now(zone), nextMidnight).toMillis().coerceAtLeast(1_000))
        }
    }

    fun observeLabResults(): Flow<List<LabResult>> =
        labResultDao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeUnresolvedFlags(): Flow<List<ClinicalFlagRecord>> =
        clinicalFlagDao.observeUnresolved().map { list -> list.map { it.toDomain() } }

    fun observeRecentBodyMetrics(limit: Int = 30): Flow<List<BodyMetric>> =
        bodyMetricDao.observeRecent(limit).map { list -> list.map { it.toDomain() } }

    /** Kullanıcının kaydettiği hızlı log şablonları (bkz. LogScreen "Şablonlar / Favoriler"). */
    fun observeQuickTemplates(): Flow<List<QuickTemplate>> =
        quickTemplateDao.observeAll().map { list -> list.map { it.toDomain() } }

    /** "Sık tekrarlanan panel" laboratuvar şablonları (bkz. LabScreen "Şablonlar"). */
    fun observeLabResultTemplates(): Flow<List<LabResultTemplate>> =
        labResultTemplateDao.observeAll().map { list -> list.map { it.toDomain() } }

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

    /**
     * Yanlış dokunulmuş bir logu (ör. "Su içtim +500ml" yanlışlıkla ikinci kez
     * basıldı) siler — önceden hiç geri alma/silme yolu yoktu, tek dokunuşluk
     * hızlı log butonlarında (LogScreen/DashboardScreen) bu riski yüksek
     * kılıyordu: fazladan bir su/protein logu hem Dashboard'daki ilerleme
     * çubuğunu hem TwinGuardrails'in ürettiği uyarıları yanlış hesaplatırdı.
     * deleteLabResult ile aynı sıra: önce Supabase, sonra yerel.
     */
    suspend fun deleteIntake(id: String): Result<Unit> = runCatching {
        postgrest.from("intake_entry").delete { filter { eq("id", id) } }
        intakeRecordDao.delete(id)
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

    /**
     * Yanlış girilmiş bir laboratuvar sonucunu siler. Önceden düzeltme yolu
     * yoktu — LabScreen'e eklenen her sonuç kalıcıydı, yanlış marker/değer/
     * tarih girilse bile silinemiyordu. Önce Supabase'den siliniyor, ardından
     * yerelden: aksi sırada, ağ hatası durumunda satır yerelde kaybolur ama
     * `pullLabResultsFromRemote()` bir sonraki senkronda onu geri getirirdi.
     */
    suspend fun deleteLabResult(id: String): Result<Unit> = runCatching {
        postgrest.from("lab_result").delete { filter { eq("id", id) } }
        labResultDao.delete(id)
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

    /**
     * Sık tekrarlanan bir kombinasyonu ("Standart Sabah Kahvesi" vb.) tek
     * dokunuşla tekrar loglanabilecek bir şablon olarak kaydeder. `logIntake`'in
     * aksine bir [IntakeRecord] üretmez — LogScreen bu şablonu listeler,
     * kullanıcı ona dokununca ayrıca `logIntake` çağrılır.
     */
    suspend fun addQuickTemplate(kind: IntakeKind, label: String, amount: Double?, unit: String?): Result<Unit> =
        runCatching {
            val userId = currentUserId() ?: error("Oturum açık değil")
            val template = QuickTemplate(userId = userId, kind = kind, label = label, amount = amount, unit = unit)
            quickTemplateDao.insert(template.toEntity(SyncState.PENDING))
            pushPendingQuickTemplates()
            Unit
        }

    /**
     * Var olan bir şablonu düzenler — id/createdAt korunur, geri kalan alanlar
     * değişir. Önceden bir şablon yanlış/eksik eklendiğinde tek yol silip
     * yeniden eklemekti (createdAt değişir, sıralaması değişir).
     */
    suspend fun updateQuickTemplate(
        original: QuickTemplate,
        kind: IntakeKind,
        label: String,
        amount: Double?,
        unit: String?
    ): Result<Unit> = runCatching {
        val updated = original.copy(kind = kind, label = label, amount = amount, unit = unit)
        quickTemplateDao.update(updated.toEntity(SyncState.PENDING))
        pushPendingQuickTemplates()
        Unit
    }

    /** Artık kullanılmayan/yanlış girilmiş bir şablonu kaldırır. deleteIntake ile aynı sıra: önce Supabase, sonra yerel. */
    suspend fun deleteQuickTemplate(id: String): Result<Unit> = runCatching {
        postgrest.from("quick_template").delete { filter { eq("id", id) } }
        quickTemplateDao.delete(id)
        Unit
    }

    suspend fun pushPendingQuickTemplates() = runCatching {
        quickTemplateDao.getPending().forEach { entity ->
            val row = entity.toDomain().toRow()
            postgrest.from("quick_template").upsert(row, onConflict = "id")
            quickTemplateDao.markSynced(entity.id)
        }
    }

    /** Yeni bir "sık tekrarlanan panel" laboratuvar şablonu kaydeder (bkz. LabResultTemplate). */
    suspend fun addLabResultTemplate(
        panel: String,
        marker: String,
        unit: String?,
        refLow: Double?,
        refHigh: Double?
    ): Result<Unit> = runCatching {
        val userId = currentUserId() ?: error("Oturum açık değil")
        val template = LabResultTemplate(
            userId = userId, panel = panel, marker = marker,
            unit = unit, refLow = refLow, refHigh = refHigh
        )
        labResultTemplateDao.insert(template.toEntity(SyncState.PENDING))
        pushPendingLabResultTemplates()
        Unit
    }

    suspend fun deleteLabResultTemplate(id: String): Result<Unit> = runCatching {
        postgrest.from("lab_result_template").delete { filter { eq("id", id) } }
        labResultTemplateDao.delete(id)
        Unit
    }

    suspend fun pushPendingLabResultTemplates() = runCatching {
        labResultTemplateDao.getPending().forEach { entity ->
            val row = entity.toDomain().toRow()
            postgrest.from("lab_result_template").upsert(row, onConflict = "id")
            labResultTemplateDao.markSynced(entity.id)
        }
    }

    /**
     * Henüz Supabase'e itilmemiş (PENDING) kayıt sayısı — tüm tablolar
     * toplanır. Önceden bu bilgi hiçbir ekranda gösterilmiyordu; offline
     * senkron sessizce arka planda çalıştığı için bir sorun olduğunda
     * (ör. sürekli ağ hatası) kullanıcının fark etmesinin hiçbir yolu yoktu.
     * Bkz. SettingsScreen "Senkronizasyon durumu".
     */
    suspend fun pendingSyncCount(): Int =
        dailySnapshotDao.getPending().size +
            intakeRecordDao.getPending().size +
            labResultDao.getPending().size +
            clinicalFlagDao.getPending().size +
            bodyMetricDao.getPending().size +
            quickTemplateDao.getPending().size +
            labResultTemplateDao.getPending().size

    /**
     * Yanlış girilmiş/yanlış güne düşmüş bir kilo-bel çevresi ölçümünü siler.
     * Önceden bunun hiçbir yolu yoktu — `logBodyMetric` yalnızca AYNI GÜN
     * içinde tekrar girilirse üzerine yazıyordu (upsert, epochDay PK); geçmiş
     * bir güne yanlış girilen bir ölçüm kalıcı olarak orada kalırdı. Diğer
     * tüm log tipleri (intake, lab_result, quick_template, lab_result_template)
     * için zaten bir "Sil" yolu vardı, body_metric bu desende dışarıda
     * kalmıştı. deleteLabResult ile aynı sıra: önce Supabase, sonra yerel.
     *
     * BodyMetricRow'da `id` taşınmıyor (bkz. SupabaseDto.kt) — upsert
     * `onConflict = "user_id,date"` kullanıyor, silme de aynı doğal anahtarla
     * (user_id + date) filtrelenir.
     */
    suspend fun deleteBodyMetric(epochDay: Long): Result<Unit> = runCatching {
        val userId = currentUserId() ?: error("Oturum açık değil")
        val date = LocalDate.ofEpochDay(epochDay)
        postgrest.from("body_metric").delete {
            filter {
                eq("user_id", userId)
                eq("date", date.toString())
            }
        }
        bodyMetricDao.delete(epochDay)
        Unit
    }

    suspend fun pushPendingBodyMetrics() = runCatching {
        bodyMetricDao.getPending().forEach { entity ->
            val row = entity.toDomain().toRow()
            postgrest.from("body_metric").upsert(row, onConflict = "user_id,date")
            bodyMetricDao.markSynced(entity.epochDay)
        }
    }

    /**
     * Yanlış/yanlış güne düşmüş bir gecelik özeti (uyku/SpO2) siler. Aynı sınıftan
     * eksik deleteBodyMetric ile bulundu (bkz. Hafta 43 commit notu): `daily_snapshot`
     * da epochDay PK ile upsert ediyor, yani AYNI GÜN tekrar senkronize/elle
     * girilirse üzerine yazılıyordu ama geçmiş bir günün kaydını (özellikle
     * `logManualSnapshot` ile elle girilmiş, kaynağı Health Connect olmayan bir
     * kaydı — bkz. SnapshotSource.MANUAL) silmenin hiçbir yolu yoktu. Health
     * Connect kaynaklı bir kayıt silinirse bir sonraki syncAll() onu otomatik
     * geri getirir (bu istenen davranış — kaynak veri hâlâ cihazda); MANUAL
     * kayıtlar için ise kalıcı bir düzeltme yoludur.
     */
    suspend fun deleteDailySnapshot(epochDay: Long): Result<Unit> = runCatching {
        val userId = currentUserId() ?: error("Oturum açık değil")
        val date = LocalDate.ofEpochDay(epochDay)
        postgrest.from("daily_snapshot").delete {
            filter {
                eq("user_id", userId)
                eq("date", date.toString())
            }
        }
        dailySnapshotDao.delete(epochDay)
        Unit
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

    /**
     * WorkManager tetikleyicisi ve "Şimdi Senkronize Et" butonunun ortak giriş noktası.
     *
     * ÖNEMLİ: Adımlar önceden tek bir `runCatching` içinde `.getOrThrow()` ile
     * zincirlenmişti — tek bir adımın (ör. ağ hatası yüzünden pushPendingIntake)
     * başarısız olması, ondan sonraki TÜM bağımsız adımları (lab sonuçları, klinik
     * bayraklar, vücut ölçümleri, uzak lab çekme) hiç denenmeden atlatıyordu. Bu,
     * sınıfın en üstündeki "best-effort" tasarımına aykırıydı — her veri tipinin
     * kendi PENDING kuyruğu var, biri diğerini bloklamamalı. Artık her adım
     * bağımsız denenir; en az biri başarısız olursa hangilerinin başarısız
     * olduğunu listeleyen tek bir hata döner, ama başarılı olanlar zaten
     * senkronize edilmiş olur.
     */
    suspend fun syncAll(): Result<Unit> {
        val failures = buildList {
            runCatching { syncLastNightFromDevice().getOrThrow() }.exceptionOrNull()?.let { add("gece verisi" to it) }
            runCatching { pushPendingSnapshots().getOrThrow() }.exceptionOrNull()?.let { add("gecelik özet" to it) }
            runCatching { pushPendingIntake().getOrThrow() }.exceptionOrNull()?.let { add("loglar" to it) }
            runCatching { pushPendingLabResults().getOrThrow() }.exceptionOrNull()?.let { add("lab sonuçları" to it) }
            runCatching { pushPendingClinicalFlags().getOrThrow() }.exceptionOrNull()?.let { add("klinik bayraklar" to it) }
            runCatching { pushPendingBodyMetrics().getOrThrow() }.exceptionOrNull()?.let { add("vücut ölçümleri" to it) }
            runCatching { pushPendingQuickTemplates().getOrThrow() }.exceptionOrNull()?.let { add("şablonlar" to it) }
            runCatching { pushPendingLabResultTemplates().getOrThrow() }.exceptionOrNull()?.let { add("lab şablonları" to it) }
            runCatching { pullLabResultsFromRemote().getOrThrow() }.exceptionOrNull()?.let { add("uzak lab çekme" to it) }
        }
        return if (failures.isEmpty()) {
            Result.success(Unit)
        } else {
            Result.failure(
                IllegalStateException(
                    "Senkron kısmen başarısız: " + failures.joinToString(", ") { (name, e) -> "$name (${e.message})" }
                )
            )
        }
    }
}
