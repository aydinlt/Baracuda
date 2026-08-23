package com.aydin.biyohack.twin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TwinEngine.parse() network I/O yapmaz (bkz. TwinEngine.kt: internal
 * görünürlük yalnızca bu test içindir), o yüzden gerçek bir proxy/sunucu
 * olmadan doğrudan test edilebilir. Ağ çağrısı (generate()) burada
 * kapsanmıyor — proxyUrl gerçek bir sunucuya gitmediği için o kısım
 * kasıtlı olarak test dışı bırakıldı (MockWebServer gibi bir bağımlılık
 * gerektirir).
 */
class TwinEngineParseTest {

    private val engine = TwinEngine(proxyUrl = "https://example.invalid/twin", supabaseAnonKey = "anon-test-key")

    @Test
    fun `gecerli JSON aksiyonlari ve bayraklari ayristirir`() {
        val raw = """
            {
              "headline": "Bugün su öncelik",
              "brief": "Su hedefinin gerisindesin, akşam öğünü zamanında.",
              "actions": [
                {"time":"14:00","action":"500 ml su iç","why":"hedefin gerisindesin","domain":"hydration","confidence":"high"}
              ],
              "deferred": [{"item":"AMPK bugün","reason":"14:00 geçti"}],
              "clinical_flags": [{"finding":"eGFR 67","status":"test bekliyor","action":"none"}],
              "data_gaps": ["dün su girişi yok"]
            }
        """.trimIndent()

        val output = engine.parse(raw)

        assertEquals("Bugün su öncelik", output.headline)
        assertEquals(1, output.actions.size)
        assertEquals("500 ml su iç", output.actions.first().action)
        assertEquals(1, output.deferred.size)
        assertEquals("AMPK bugün" to "14:00 geçti", output.deferred.first())
        assertEquals(1, output.clinicalFlags.size)
        assertEquals(listOf("dün su girişi yok"), output.dataGaps)
    }

    @Test
    fun `code fence ile sarili json temizlenir`() {
        val raw = "```json\n" +
            "{\"headline\":\"Test\",\"brief\":\"b\",\"actions\":[],\"deferred\":[],\"clinical_flags\":[],\"data_gaps\":[]}\n" +
            "```"
        val output = engine.parse(raw)
        assertEquals("Test", output.headline)
    }

    @Test
    fun `clinical domainli action kirmizi bolge filtresinden gecer`() {
        val raw = """
            {
              "headline": "Test",
              "brief": "b",
              "actions": [
                {"time":"09:00","action":"eGFR düşük, dozu azalt","why":"böbrek seyri","domain":"clinical","confidence":"high"}
              ],
              "deferred": [],
              "clinical_flags": [],
              "data_gaps": []
            }
        """.trimIndent()

        val output = engine.parse(raw)

        assertTrue("clinical domainli action bildirime sızmamalı", output.actions.isEmpty())
        assertTrue(output.clinicalFlags.isNotEmpty())
        assertTrue(output.violations.isNotEmpty())
    }

    @Test
    fun `eksik alanlar varsayilanlarla doldurulur`() {
        val raw = """{"actions":[{"action":"Su iç"}]}"""
        val output = engine.parse(raw)

        assertEquals("", output.headline)
        assertEquals("şimdi", output.actions.first().time)
        assertEquals("nutrition", output.actions.first().domain)
        assertEquals("medium", output.actions.first().confidence)
    }
}
