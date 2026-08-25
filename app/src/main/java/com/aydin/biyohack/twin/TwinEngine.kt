package com.aydin.biyohack.twin

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class TwinAction(
    val time: String,
    val action: String,
    val why: String,
    val domain: String,
    val confidence: String
)

data class TwinFlag(val finding: String, val status: String, val action: String)

data class TwinOutput(
    val headline: String,
    val brief: String,
    val actions: List<TwinAction>,
    val deferred: List<Pair<String, String>>,
    val clinicalFlags: List<TwinFlag>,
    val dataGaps: List<String>,
    val violations: List<String> = emptyList()
)

/**
 * API anahtarı burada YOK. Supabase Edge Function proxy'sine gider.
 * Anahtar APK'ya asla gömülmez.
 */
class TwinEngine(
    private val proxyUrl: String,          // https://<proj>.supabase.co/functions/v1/twin
    private val supabaseAnonKey: String
) {
    /**
     * Uygulama boyunca tek bir istemci paylaşılır — bağlantı havuzlaması/keep-alive
     * sağlar. Önceden her generate() çağrısı kendi HttpURLConnection'ını sıfırdan
     * açıp kapatıyordu (bkz. Hafta 35 commit notu); "fast" tier günde 5-15 kez
     * çağrıldığı için (system_twin.md Bölüm 4) bu gereksiz bir maliyetti. Motor
     * OkHttp — supabase-kt'nin Postgrest/Auth çağrıları için zaten kullandığı aynı
     * engine (bkz. build.gradle.kts ktor-client-okhttp), ayrı bir HTTP yığını değil.
     */
    private val httpClient = HttpClient(OkHttp) {
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 60_000
        }
    }

    /**
     * @param tierOverride verilmezse tier tetikleyiciden çıkarılır (sabah
     * protokolü = deep, diğerleri = fast). Haftalık seyir analizi gibi
     * trigger'dan bağımsız senaryolar için "weekly" (Opus) buradan geçilir
     * — bkz. supabase/functions/twin/index.ts MODELS.weekly.
     * @param waterTargetMl/proteinMinG/proteinMaxG/wakeTargetHour/bedEarliestHour —
     * verilmezse TwinGuardrails kendi varsayılanlarını kullanır. Kullanıcının
     * Ayarlar'da belirlediği gerçek hedefleri kural motoruna taşımak için (bkz.
     * Hafta 21/52 commit notu) TwinRepository buradan geçirir.
     */
    suspend fun generate(
        state: TwinState,
        tierOverride: String? = null,
        waterTargetMl: Int? = null,
        proteinMinG: Int? = null,
        proteinMaxG: Int? = null,
        wakeTargetHour: Int? = null,
        bedEarliestHour: Int? = null
    ): Result<TwinOutput> = withContext(Dispatchers.IO) {
        runCatching {
            val facts = TwinGuardrails.buildFacts(
                state, waterTargetMl, proteinMinG, proteinMaxG, wakeTargetHour, bedEarliestHour
            )
            // waterTargetMl/wakeTargetHour buildFacts()'a zaten geçiriliyordu ama
            // toPromptBlock()'a hiç ulaşmıyordu — bkz. TwinState.kt commit notu.
            val stateBlock = TwinStateSerializer.toPromptBlock(state, facts, waterTargetMl, wakeTargetHour)

            val payload = JSONObject().apply {
                put("trigger", state.trigger.name)
                put("state_block", stateBlock)
                // Sabah protokolü daha derin sentez ister, gün içi override hızlı olmalı
                put("tier", tierOverride ?: if (state.trigger == Trigger.MORNING_PROTOCOL) "deep" else "fast")
            }

            val response = httpClient.post(proxyUrl) {
                header("Authorization", "Bearer $supabaseAnonKey")
                contentType(ContentType.Application.Json)
                setBody(payload.toString())
            }
            val body = response.bodyAsText()

            if (!response.status.isSuccess()) {
                throw IllegalStateException("Proxy ${response.status.value}: $body")
            }

            parse(body)
        }
    }

    // internal: TwinEngineParseTest bu fonksiyonu network I/O olmadan doğrudan test eder.
    internal fun parse(raw: String): TwinOutput {
        // Model ara sıra ``` ile sarabiliyor — savunmacı temizlik
        val cleaned = raw.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()

        val o = JSONObject(cleaned)

        val actions = mutableListOf<TwinAction>()
        o.optJSONArray("actions")?.let { arr ->
            for (i in 0 until arr.length()) {
                val a = arr.getJSONObject(i)
                actions += TwinAction(
                    time = a.optString("time", "şimdi"),
                    action = a.optString("action"),
                    why = a.optString("why"),
                    domain = a.optString("domain", "nutrition"),
                    confidence = a.optString("confidence", "medium")
                )
            }
        }

        val flags = mutableListOf<TwinFlag>()
        o.optJSONArray("clinical_flags")?.let { arr ->
            for (i in 0 until arr.length()) {
                val f = arr.getJSONObject(i)
                flags += TwinFlag(
                    f.optString("finding"),
                    f.optString("status"),
                    f.optString("action", "none")
                )
            }
        }

        val deferred = mutableListOf<Pair<String, String>>()
        o.optJSONArray("deferred")?.let { arr ->
            for (i in 0 until arr.length()) {
                val d = arr.getJSONObject(i)
                deferred += d.optString("item") to d.optString("reason")
            }
        }

        val gaps = mutableListOf<String>()
        o.optJSONArray("data_gaps")?.let { arr ->
            for (i in 0 until arr.length()) gaps += arr.getString(i)
        }

        // ── Kırmızı bölge filtresi: modelin çıktısına GÜVENME ──
        val filtered = TwinGuardrails.filter(actions)

        return TwinOutput(
            headline = o.optString("headline"),
            brief = o.optString("brief"),
            actions = filtered.cleanedActions,
            deferred = deferred,
            clinicalFlags = flags + filtered.addedFlags,
            dataGaps = gaps,
            violations = filtered.violations
        )
    }
}
