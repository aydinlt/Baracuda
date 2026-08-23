package com.aydin.biyohack.twin

import com.aydin.biyohack.data.DailySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * TwinGuardrails "%100 doğru olmalı, %99 yetmez" diye tasarlandı (bkz. sınıf
 * yorumu) — bu yüzden saat aritmetiği ve kırmızı bölge filtresi burada
 * ayrıntılı test edilir. Zone, TwinGuardrails'in kullandığıyla aynı
 * (ZoneId.systemDefault()) tutulur ki test, hangi makinede çalıştığından
 * bağımsız olsun.
 */
class TwinGuardrailsTest {

    private val zone = ZoneId.systemDefault()
    private val today: LocalDate = LocalDate.of(2026, 6, 15)

    private fun at(hour: Int, minute: Int = 0): Instant =
        today.atTime(hour, minute).atZone(zone).toInstant()

    private fun state(
        now: Instant,
        todayIntake: List<IntakeEntry> = emptyList(),
        lastNight: DailySnapshot? = null,
        plannedTrainingToday: Boolean = false,
        saunaPlannedToday: Boolean = false,
        pendingTests: List<String> = emptyList(),
        creatineFreeDays: Int = 0
    ) = TwinState(
        now = now,
        trigger = Trigger.MANUAL,
        lastNight = lastNight,
        previousNights = emptyList(),
        todayIntake = todayIntake,
        creatineFreeDays = creatineFreeDays,
        pendingTests = pendingTests,
        plannedTrainingToday = plannedTrainingToday,
        saunaPlannedToday = saunaPlannedToday
    )

    // ── buildFacts: kafein ──

    @Test
    fun `13 sonrasi kahve ihlal olarak isaretlenir`() {
        val s = state(
            now = at(15, 0),
            todayIntake = listOf(IntakeEntry(at(14, 30), IntakeType.COFFEE, "Filtre kahve"))
        )
        val facts = TwinGuardrails.buildFacts(s)
        assertTrue(facts.any { it.startsWith("İHLAL") && it.contains("kahve") })
    }

    @Test
    fun `13 ten once kahve ihlal degildir`() {
        val s = state(
            now = at(13, 30),
            todayIntake = listOf(IntakeEntry(at(9, 0), IntakeType.COFFEE, "Filtre kahve"))
        )
        val facts = TwinGuardrails.buildFacts(s)
        assertFalse(facts.any { it.startsWith("İHLAL") && it.contains("kahve") })
    }

    // ── buildFacts: EGCG ──

    @Test
    fun `14 sonrasi EGCG ihlal olarak isaretlenir`() {
        val s = state(
            now = at(16, 0),
            todayIntake = listOf(
                IntakeEntry(at(12, 0), IntakeType.MEAL, "Öğle yemeği"),
                IntakeEntry(at(14, 30), IntakeType.SUPPLEMENT, "EGCG")
            )
        )
        val facts = TwinGuardrails.buildFacts(s)
        assertTrue(facts.any { it.startsWith("İHLAL") && it.contains("EGCG") && it.contains("14:00") })
    }

    @Test
    fun `ogunsuz EGCG ihlal olarak isaretlenir`() {
        val s = state(
            now = at(10, 0),
            todayIntake = listOf(IntakeEntry(at(9, 0), IntakeType.SUPPLEMENT, "EGCG"))
        )
        val facts = TwinGuardrails.buildFacts(s)
        assertTrue(facts.any { it.contains("EGCG öğünsüz") })
    }

    // ── buildFacts: su ──

    @Test
    fun `su hedefin cok gerisindeyse uyari uretilir`() {
        val s = state(
            now = at(18, 0),
            todayIntake = listOf(IntakeEntry(at(9, 0), IntakeType.WATER, "Su", amount = 200.0, unit = "ml"))
        )
        val facts = TwinGuardrails.buildFacts(s)
        assertTrue(facts.any { it.contains("Su geride") })
    }

    // ── buildFacts: kreatin / test öncesi ara ──

    @Test
    fun `sistatin c bekliyorsa kreatin uyarisi hekim ibaresiyle gelir`() {
        val s = state(now = at(9, 0), pendingTests = listOf("Cistatin C"), creatineFreeDays = 2)
        val facts = TwinGuardrails.buildFacts(s)
        assertTrue(facts.any { it.contains("Cistatin C") && it.contains("HEKİMLE") })
    }

    // ── buildFacts: aşırı uyku ──

    @Test
    fun `10 saatten fazla uyku uyarisi uretir`() {
        val snapshot = DailySnapshot(
            userId = "u1",
            date = today.minusDays(1),
            asleepMin = 650,
            efficiencyPct = 80
        )
        val s = state(now = at(9, 0), lastNight = snapshot)
        val facts = TwinGuardrails.buildFacts(s)
        assertTrue(facts.any { it.contains("UYKUSUZLUK DEĞİL") })
    }

    // ── filter: çıktı filtresi ──

    @Test
    fun `clinical domainli action dusurulur ve bayrak eklenir`() {
        val action = TwinAction("09:00", "eGFR düşük, hekime git", "eGFR seyri", "clinical", "high")
        val result = TwinGuardrails.filter(listOf(action))
        assertTrue(result.cleanedActions.isEmpty())
        assertEquals(1, result.addedFlags.size)
        assertTrue(result.violations.isNotEmpty())
    }

    @Test
    fun `kirmizi bolgede karar cumlesi dusurulur`() {
        val action = TwinAction("09:00", "Kreatini bırakıyorum", "eGFR düşük", "nutrition", "high")
        val result = TwinGuardrails.filter(listOf(action))
        assertTrue(result.cleanedActions.isEmpty())
        assertTrue(result.addedFlags.any { it.finding == action.action })
    }

    @Test
    fun `kreatin arasi hekim ibaresi olmadan gelirse eklenir`() {
        val action = TwinAction(
            "09:00",
            "Kreatinin seyrini takip ediyorum, kreatin miktarını sabit tutuyorum",
            "test öncesi rutin takip",
            "nutrition",
            "medium"
        )
        val result = TwinGuardrails.filter(listOf(action))
        assertEquals(1, result.cleanedActions.size)
        assertTrue(result.cleanedActions.first().action.contains("hekime danışarak"))
    }

    @Test
    fun `normal action degismeden gecer`() {
        val action = TwinAction("14:00", "Su iç", "hedefin gerisindesin", "hydration", "high")
        val result = TwinGuardrails.filter(listOf(action))
        assertEquals(listOf(action), result.cleanedActions)
        assertTrue(result.addedFlags.isEmpty())
        assertTrue(result.violations.isEmpty())
    }
}
