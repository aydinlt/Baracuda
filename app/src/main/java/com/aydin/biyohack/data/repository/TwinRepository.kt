package com.aydin.biyohack.data.repository

import com.aydin.biyohack.twin.TwinEngine
import com.aydin.biyohack.twin.TwinOutput
import com.aydin.biyohack.twin.TwinStateBuilder
import com.aydin.biyohack.twin.Trigger
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * [TwinEngine] çağrısını sarmalar ve çıktıyı `twin_output_log`'a arşivler
 * — TwinGuardrails'in hangi action'ları düşürdüğü, hangi clinical_flag'i
 * eklediği (bkz. TwinOutput.violations) denetlenebilir kalsın diye.
 * Arşivleme best-effort'tur: başarısız olursa protokol çıktısını etkilemez.
 */
class TwinRepository(
    private val stateBuilder: TwinStateBuilder,
    private val engine: TwinEngine,
    private val postgrest: Postgrest,
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
        val output = engine.generate(state).getOrThrow()
        logOutput(trigger, tier, output)
        output
    }

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
        val output = engine.generate(state, tierOverride = "weekly").getOrThrow()
        logOutput(Trigger.MANUAL, "weekly", output)
        output
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
