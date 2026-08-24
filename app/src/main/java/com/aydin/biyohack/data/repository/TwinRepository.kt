package com.aydin.biyohack.data.repository

import com.aydin.biyohack.twin.TwinEngine
import com.aydin.biyohack.twin.TwinOutput
import com.aydin.biyohack.twin.TwinStateBuilder
import com.aydin.biyohack.twin.Trigger
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * [TwinEngine] çağrısını sarmalar ve çıktıyı `twin_output_log`'a arşivler
 * — TwinGuardrails'in hangi action'ları düşürdüğü, hangi clinical_flag'i
 * eklediği (bkz. TwinOutput.violations) denetlenebilir kalsın diye.
 * Arşivleme best-effort'tur: başarısız olursa protokol çıktısını etkilemez.
 *
 * Ayrıca `output.clinicalFlags`'i [HealthSyncRepository]'nin `clinical_flag`
 * tablosuna yazar — aksi halde bu bayraklar yalnızca TwinScreen'de bir kez
 * gösterilip kaybolurdu, `LabScreen`'deki "Klinik bayraklar" bölümüne hiç
 * düşmezdi ve [TwinStateBuilder.pendingTests] onları hiç göremezdi.
 */
class TwinRepository(
    private val stateBuilder: TwinStateBuilder,
    private val engine: TwinEngine,
    private val postgrest: Postgrest,
    private val healthSyncRepository: HealthSyncRepository,
    private val profileRepository: ProfileRepository,
    private val currentUserId: suspend () -> String?
) {
    suspend fun runProtocol(
        trigger: Trigger,
        userNote: String? = null,
        plannedTrainingToday: Boolean = false,
        saunaPlannedToday: Boolean = false
    ): Result<TwinOutput> = runCatching {
        val state = stateBuilder.build(trigger, userNote, plannedTrainingToday, saunaPlannedToday)
        val tier = if (trigger == Trigger.MORNING_PROTOCOL) "deep" else "fast"
        val profile = currentProfile()
        val output = engine.generate(
            state,
            waterTargetMl = profile?.waterTargetMl,
            proteinMinG = profile?.proteinTargetMinG,
            proteinMaxG = profile?.proteinTargetMaxG,
            wakeTargetHour = profile?.wakeTarget?.hour
        ).getOrThrow()
        logOutput(trigger, tier, output)
        persistClinicalFlags(output)
        output
    }

    /**
     * TwinGuardrails önceden kullanıcının Ayarlar'da belirlediği su/protein/kalkış
     * hedeflerinden habersizdi — sabit varsayılanlarla çalışıyordu (bkz. Hafta 21
     * commit notu). Profil yüklenemezse (örn. oturum yok) null döner ve
     * TwinEngine/TwinGuardrails kendi varsayılanlarına düşer.
     */
    private suspend fun currentProfile() =
        currentUserId()?.let { profileRepository.observe(it).first() }

    /**
     * Haftalık seyir analizi — son 14 gecenin eğilimini Opus tier ile
     * değerlendirir (bkz. supabase/functions/twin/index.ts MODELS.weekly).
     * Trigger.MANUAL kullanılır: TwinState.Trigger sabit bir sözleşme,
     * yalnızca bunun için genişletilmedi (bkz. twin/TwinState.kt notu).
     */
    suspend fun runWeeklyReview(): Result<TwinOutput> = runCatching {
        val state = stateBuilder.build(
            trigger = Trigger.MANUAL,
            userNote = "Haftalık seyir analizi: son 14 gecenin uyku/SpO2 eğilimini, " +
                "bugünkü loglarla karşılaştırarak değerlendir.",
            snapshotLimit = 14
        )
        val profile = currentProfile()
        val output = engine.generate(
            state,
            tierOverride = "weekly",
            waterTargetMl = profile?.waterTargetMl,
            proteinMinG = profile?.proteinTargetMinG,
            proteinMaxG = profile?.proteinTargetMaxG,
            wakeTargetHour = profile?.wakeTarget?.hour
        ).getOrThrow()
        logOutput(Trigger.MANUAL, "weekly", output)
        persistClinicalFlags(output)
        output
    }

    /**
     * Aynı bulguyu (finding) tekrar tekrar eklememek için açık bayraklarla karşılaştırır.
     * `distinctBy` ayrıca AYNI TwinOutput içinde (ör. birden fazla action aynı finding'e
     * düşürülüp bayraklandığında — bkz. TwinGuardrails.filter) birebir aynı finding'in
     * tek bir toplu işlemde iki kez eklenmesini önler; önceki hâlde yalnızca önceden var
     * olan bayraklara bakılıyordu, aynı partinin içindeki tekrarlar hiç süzülmüyordu.
     */
    private suspend fun persistClinicalFlags(output: TwinOutput) {
        if (output.clinicalFlags.isEmpty()) return
        runCatching {
            val existingFindings = healthSyncRepository.observeUnresolvedFlags().first().map { it.finding }
            output.clinicalFlags
                .distinctBy { it.finding }
                .filter { it.finding !in existingFindings }
                .forEach { healthSyncRepository.addClinicalFlag(it.finding, it.status, it.action) }
        }
    }

    /**
     * `twin_output_log` geçmişini okur — sabah protokolü her gün otomatik
     * çalıştığı için (bkz. sync/TwinMorningWorker.kt) bu, geçmiş sonuçlara
     * ulaşmanın tek yolu: TwinScreen'deki anlık state uygulama kapanınca kaybolur.
     */
    suspend fun observeHistory(limit: Int = 20): Result<List<TwinOutputHistoryEntry>> = runCatching {
        val userId = currentUserId() ?: error("Oturum açık değil")
        postgrest.from("twin_output_log")
            .select {
                filter { eq("user_id", userId) }
                order("created_at", Order.DESCENDING)
                limit(limit.toLong())
            }
            .decodeList<TwinOutputHistoryEntry>()
    }

    private suspend fun logOutput(trigger: Trigger, tier: String, output: TwinOutput) {
        val userId = currentUserId() ?: return
        runCatching {
            postgrest.from("twin_output_log").insert(
                TwinOutputLogRow(
                    userId = userId,
                    trigger = trigger.name,
                    tier = tier,
                    headline = output.headline,
                    brief = output.brief,
                    rawJson = output.toPayload(),
                    violations = output.violations
                )
            )
        }
    }
}

/** `observeHistory()` sonucu — liste görünümü için yeterli alanlar, tam `raw_json` dahil değil. */
@Serializable
data class TwinOutputHistoryEntry(
    val id: String,
    val trigger: String,
    val tier: String,
    val headline: String,
    val brief: String,
    @SerialName("created_at") val createdAt: String
)

// ────────────────────────────────────────────────────────────
// twin_output_log satırı (yazma) — yalnızca bu dosyanın içinde kullanılan,
// dışa sızdırılmayan Supabase DTO'ları.
// ────────────────────────────────────────────────────────────

@Serializable
private data class TwinOutputLogRow(
    @SerialName("user_id") val userId: String,
    val trigger: String,
    val tier: String,
    val headline: String,
    val brief: String,
    @SerialName("raw_json") val rawJson: TwinOutputPayload,
    val violations: List<String>
)

@Serializable
private data class TwinOutputPayload(
    val actions: List<TwinActionPayload>,
    val deferred: List<Map<String, String>>,
    val clinicalFlags: List<TwinFlagPayload>,
    val dataGaps: List<String>
)

@Serializable
private data class TwinActionPayload(
    val time: String,
    val action: String,
    val why: String,
    val domain: String,
    val confidence: String
)

@Serializable
private data class TwinFlagPayload(val finding: String, val status: String, val action: String)

private fun TwinOutput.toPayload() = TwinOutputPayload(
    actions = actions.map { TwinActionPayload(it.time, it.action, it.why, it.domain, it.confidence) },
    deferred = deferred.map { mapOf("item" to it.first, "reason" to it.second) },
    clinicalFlags = clinicalFlags.map { TwinFlagPayload(it.finding, it.status, it.action) },
    dataGaps = dataGaps
)
