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

    // ── buildFacts: kullanıcının Ayarlar'daki gerçek hedefleri (Hafta 21) ──
    // Bu dört parametre eklendiğinden beri hiçbir test onları gerçekten geçmiyordu —
    // yalnızca varsayılan (parametresiz) çağrının hâlâ çalıştığı test ediliyordu.
    // Aşağıdakiler override'ların gerçekten etkili olduğunu doğrular; aksi halde
    // biri satır içinde yanlışlıkla sabiti (ör. WATER_TARGET_ML) kullanmaya geri
    // dönse bile hiçbir test bunu yakalamazdı.

    @Test
    fun `ozel su hedefi esik hesabini degistirir`() {
        // Saat 15:00, wakeTarget varsayılan 7 → hoursAwake = 8.
        // Varsayılan hedef 4000 ml: expected = 4000*8/15 ≈ 2133, %70'i ≈ 1493 →
        // 1000 ml bunun altında kalır → "Su geride".
        // Özel hedef 1000 ml: expected = 1000*8/15 ≈ 533, %70'i ≈ 373 →
        // 1000 ml bunun çok üstünde → "Su durumu uygun".
        val s = state(
            now = at(15, 0),
            todayIntake = listOf(IntakeEntry(at(9, 0), IntakeType.WATER, "Su", amount = 1000.0, unit = "ml"))
        )
        assertTrue(TwinGuardrails.buildFacts(s).any { it.contains("Su geride") })

        val customFacts = TwinGuardrails.buildFacts(s, waterTargetMl = 1000)
        assertTrue(customFacts.any { it.contains("Su durumu uygun") && it.contains("1000 ml") })
    }

    @Test
    fun `ozel protein hedefi hatirlatma metnini degistirir`() {
        val s = state(now = at(19, 0)) // öğün yok, saat >= 18
        val defaultFacts = TwinGuardrails.buildFacts(s)
        assertTrue(defaultFacts.any { it.contains("140") && it.contains("170") })

        val customFacts = TwinGuardrails.buildFacts(s, proteinMinG = 100, proteinMaxG = 130)
        assertTrue(customFacts.any { it.contains("100") && it.contains("130") })
        assertTrue(customFacts.none { it.contains("140") })
    }

    @Test
    fun `ozel yatis hedefi kafein kesme saatini degistirir`() {
        // Varsayılan yatış 23:00 → kafein kesme 13:00 (23:00 - 10sa). 12:30'da içilen
        // kahve bu sınırın altında kaldığı için varsayılanla ihlal DEĞİL. Özel yatış
        // hedefi 22:00 → kesme 12:00'a çekilir, aynı 12:30 kahvesi artık ihlal olur.
        val s = state(
            now = at(13, 0),
            todayIntake = listOf(IntakeEntry(at(12, 30), IntakeType.COFFEE, "Filtre kahve"))
        )
        assertFalse(TwinGuardrails.buildFacts(s).any { it.startsWith("İHLAL") && it.contains("kahve") })

        val customFacts = TwinGuardrails.buildFacts(s, bedEarliestHour = 22)
        assertTrue(customFacts.any { it.startsWith("İHLAL") && it.contains("kahve") && it.contains("12:00") })
    }

    @Test
    fun `ozel kalkis hedefi sapma esigini degistirir`() {
        // Kalkış 09:00. Varsayılan hedef 7 → izinli aralık 6..8 → 9 dışarıda, sapma
        // fact'i üretilir. Özel hedef 9 → izinli aralık 8..10 → 9 içeride, üretilmez.
        val snapshot = DailySnapshot(userId = "u1", date = today.minusDays(1), wakeTime = at(9, 0))
        val s = state(now = at(10, 0), lastNight = snapshot)
        assertTrue(TwinGuardrails.buildFacts(s).any { it.contains("hedefinden sapma") })

        val customFacts = TwinGuardrails.buildFacts(s, wakeTargetHour = 9)
        assertTrue(customFacts.none { it.contains("hedefinden sapma") })
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
    fun `baska klinik terim gecmeyen kreatin karari da dusurulur`() {
        // Önceki davranış: touchesClinical yalnızca "kreatinin" gibi CLINICAL_TERMS
        // kelimelerinden biri geçince true oluyordu — burada action/why hiçbirinde
        // öyle bir kelime yok, yalnızca "kreatin" + karar fiili var. Düzeltmeden önce
        // bu action hiç filtrelenmeden geçerdi (system_twin.md Mutlak Kural 1 ihlali).
        val action = TwinAction("09:00", "Kreatini bırakıyorum", "kas ağrısı hissediyorum", "supplement", "medium")
        val result = TwinGuardrails.filter(listOf(action))
        assertTrue(result.cleanedActions.isEmpty())
        assertTrue(result.addedFlags.any { it.finding == action.action })
    }

    @Test
    fun `hekim ibaresi zaten varsa kreatin karari dusurulmez`() {
        val action = TwinAction(
            "09:00", "Kreatini hekime danışarak bırakıyorum", "test öncesi", "supplement", "medium"
        )
        val result = TwinGuardrails.filter(listOf(action))
        assertEquals(listOf(action), result.cleanedActions)
        assertTrue(result.addedFlags.isEmpty())
    }

    @Test
    fun `rutin gunluk kreatin hatirlatmasi etkilenmez`() {
        // Karar fiili yok (yalnızca doz bilgisi) — her gün üretilen sıradan bir
        // hatırlatma, "(hekime danışarak)" ekiyle kirletilmemeli.
        val action = TwinAction("08:00", "Kreatin 5 g al", "günlük stack", "supplement", "high")
        val result = TwinGuardrails.filter(listOf(action))
        assertEquals(listOf(action), result.cleanedActions)
        assertTrue(result.addedFlags.isEmpty())
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
    fun `ilaci GLP-1 sinif adiyla anan karar cumlesi de dusurulur`() {
        // Hafta 49'daki kreatin boşluğuyla aynı sınıf: "tirzepatid"/"mounjaro"
        // CLINICAL_TERMS'te vardı ama model ilacı yalnızca ilaç sınıfı adıyla
        // ("GLP-1") anarsa, önceki listeyle touchesClinical hiç true olmuyordu —
        // why alanında da başka bir klinik terim yok, yalnızca "glp" + karar fiili var.
        val action = TwinAction("09:00", "GLP-1 dozunu azaltıyorum", "kilo kaybı hızlandı", "nutrition", "medium")
        val result = TwinGuardrails.filter(listOf(action))
        assertTrue(result.cleanedActions.isEmpty())
        assertTrue(result.addedFlags.any { it.finding == action.action })
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
