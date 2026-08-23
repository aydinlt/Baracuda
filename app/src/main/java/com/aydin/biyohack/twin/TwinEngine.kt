package com.aydin.biyohack.twin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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
     * @param tierOverride verilmezse tier tetikleyiciden çıkarılır (sabah
     * protokolü = deep, diğerleri = fast). Haftalık seyir analizi gibi
     * trigger'dan bağımsız senaryolar için "weekly" (Opus) buradan geçilir
     * — bkz. supabase/functions/twin/index.ts MODELS.weekly.
     */
    suspend fun generate(state: TwinState, tierOverride: String? = null): Result<TwinOutput> = withContext(Dispatchers.IO) {
        runCatching {
            val facts = TwinGuardrails.buildFacts(state)
            val stateBlock = TwinStateSerializer.toPromptBlock(state, facts)

            val payload = JSONObject().apply {
                put("trigger", state.trigger.name)
                put("state_block", stateBlock)
                // Sabah protokolü daha derin sentez ister, gün içi override hızlı olmalı
                put("tier", tierOverride ?: if (state.trigger == Trigger.MORNING_PROTOCOL) "deep" else "fast")
            }

            val conn = (URL(proxyUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 60_000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $supabaseAnonKey")
            }
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }

            val body = if (conn.responseCode in 200..299)
                conn.inputStream.bufferedReader().readText()
            else
                throw IllegalStateException(
                    "Proxy ${conn.responseCode}: " +
                        conn.errorStream?.bufferedReader()?.readText()
                )

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
