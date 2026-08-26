package com.aydin.biyohack.twin

import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId

/**
 * İki iş yapar:
 *  1) ÖNCE  — deterministik kuralları çalıştırıp LLM'e FACT olarak verir
 *  2) SONRA — LLM çıktısını kırmızı bölge ihlaline karşı filtreler
 *
 * Buradaki hiçbir kural modele sorulmaz. Saat aritmetiğinin %100 doğru
 * olması gerekiyor, %99 yetmez.
 */
object TwinGuardrails {

    private val zone: ZoneId = ZoneId.systemDefault()

    private const val WAKE_TARGET = 7          // 07:00
    private const val EARLIEST_BED = 23        // 23:00
    private const val CAFFEINE_CUTOFF_H = 10L  // yatıştan 10 saat önce
    private const val EGCG_CUTOFF_H = 14       // 14:00
    private const val WATER_TARGET_ML = 4000
    private const val PROTEIN_MIN_G = 140
    private const val PROTEIN_MAX_G = 170

    // ───────────────────────────────────────────────────────
    // 1) FACT üretimi
    // ───────────────────────────────────────────────────────

    /**
     * [waterTargetMl]/[proteinMinG]/[proteinMaxG]/[wakeTargetHour]/[bedEarliestHour] —
     * null bırakılırsa yukarıdaki sabitler kullanılır (geriye dönük uyumlu varsayılan).
     * Bu beş değer artık SettingsScreen'den düzenlenebiliyor (bkz. Profile.waterTargetMl
     * vb., Hafta 7/17/52) — kural motoru önceden bunlardan habersiz sabit değerlerle
     * çalışıyordu, yani kullanıcı hedefini değiştirse bile İkiz eski varsayılana göre
     * uyarı üretiyordu. `bedEarliestHour` en son eklenendi (Hafta 52) — bu yorum önceden
     * Profile.bedEarliest'in hiçbir yerde düzenlenebilir olmadığını, bu yüzden kasıtlı
     * olarak parametreleştirilmediğini söylüyordu (bkz. Hafta 17 commit notu); artık
     * diğer dört hedefle aynı desende SettingsScreen'den geliyor.
     */
    fun buildFacts(
        s: TwinState,
        waterTargetMl: Int? = null,
        proteinMinG: Int? = null,
        proteinMaxG: Int? = null,
        wakeTargetHour: Int? = null,
        bedEarliestHour: Int? = null
    ): List<String> {
        val waterTarget = waterTargetMl ?: WATER_TARGET_ML
        val protMin = proteinMinG ?: PROTEIN_MIN_G
        val protMax = proteinMaxG ?: PROTEIN_MAX_G
        val wakeTarget = wakeTargetHour ?: WAKE_TARGET

        val facts = mutableListOf<String>()
        val nowTime = s.now.atZone(zone).toLocalTime()
        val bedTarget = LocalTime.of(bedEarliestHour ?: EARLIEST_BED, 0)

        // --- Kafein ---
        val caffeineCutoff = bedTarget.minusHours(CAFFEINE_CUTOFF_H) // 13:00
        val coffees = s.todayIntake.filter { it.type == IntakeType.COFFEE }
        if (coffees.isNotEmpty()) {
            val last = coffees.maxOf { it.ts }.atZone(zone).toLocalTime()
            if (last.isAfter(caffeineCutoff)) {
                facts += "İHLAL: Son kahve $last — kafein kesme saati $caffeineCutoff " +
                    "(yatış $bedTarget − $CAFFEINE_CUTOFF_H sa). Bugün başka kafein alınmamalı."
            } else {
                facts += "Kafein penceresi uygun (son: $last, sınır: $caffeineCutoff). " +
                    "Bugün $caffeineCutoff sonrası kafein alınmamalı."
            }
        } else if (nowTime.isBefore(caffeineCutoff)) {
            facts += "Bugün henüz kafein yok. Sınır: $caffeineCutoff."
        }

        // --- EGCG / AMPK saati ---
        val egcg = s.todayIntake.filter {
            it.type == IntakeType.SUPPLEMENT &&
                (it.label.contains("EGCG", true) || it.label.contains("berberin", true) ||
                 it.label.contains("AMPK", true))
        }
        egcg.forEach { e ->
            val t = e.ts.atZone(zone).toLocalTime()
            if (t.hour >= EGCG_CUTOFF_H)
                facts += "İHLAL: ${e.label} saat $t'de alınmış — 14:00 sonrası yasak."
        }
        if (egcg.isEmpty() && nowTime.hour >= EGCG_CUTOFF_H)
            facts += "EGCG/AMPK bugün alınmamış ve 14:00 geçti — bugün için ötelenmeli, " +
                "yarın öğün ile alınmalı."

        // --- EGCG aç karnına mı? ---
        val meals = s.todayIntake.filter { it.type == IntakeType.MEAL }
        egcg.filter { it.label.contains("EGCG", true) }.forEach { e ->
            val withFood = meals.any {
                Duration.between(it.ts, e.ts).abs().toMinutes() <= 45
            }
            if (!withFood)
                facts += "İHLAL: EGCG öğünsüz alınmış görünüyor — karaciğer güvenliği " +
                    "için yemekle alınmalı."
        }

        // --- Su ---
        val water = s.todayIntake.filter { it.type == IntakeType.WATER }
            .sumOf { it.amount ?: 0.0 }.toInt()
        val hoursAwake = maxOf(1, nowTime.hour - wakeTarget)
        val expected = (waterTarget * hoursAwake / 15).coerceAtMost(waterTarget)
        when {
            water == 0 && nowTime.hour > 10 ->
                facts += "Su logu boş ve saat $nowTime — veri eksik, öneri üretmeden önce " +
                    "data_gaps'e yaz."
            water < expected * 0.7 ->
                facts += "Su geride: $water ml, bu saatte beklenen ~$expected ml " +
                    "(hedef $waterTarget ml). İdrar dansitesi 1,025 seyrettiği için " +
                    "bu açık önemli."
            else ->
                facts += "Su durumu uygun: $water / $waterTarget ml."
        }
        if (s.saunaPlannedToday)
            facts += "Sauna planlı — elektrolit zorunlu, su hedefi üstüne çıkılmalı."

        // --- Glisin / magnezyum akşam hatırlatması ---
        if (nowTime.hour in 20..23) {
            val glycine = s.todayIntake.any {
                it.type == IntakeType.SUPPLEMENT && it.label.contains("glisin", true)
            }
            if (!glycine)
                facts += "Glisin 3 g henüz alınmamış — yatmadan 30–60 dk önce, " +
                    "yani ${bedTarget.minusMinutes(45)} civarı."
        }

        // --- Antrenman / sauna zamanlaması ---
        if (s.plannedTrainingToday)
            facts += "Antrenman yatıştan ≥3 saat önce bitmeli → en geç " +
                "${bedTarget.minusHours(3)}."
        if (s.saunaPlannedToday)
            facts += "Sauna yatıştan ~90 dk önce → ideal ${bedTarget.minusMinutes(90)}."

        // --- Uyku: aşırı uyku uyarısı ---
        s.lastNight?.asleepMin?.let { mins ->
            if (mins > 600)
                facts += "Dün ${mins / 60}s ${mins % 60}d uyunmuş (>10 sa). Uyku basıncı " +
                    "tükendiği için bu gece dalma zorlaşacak — bu UYKUSUZLUK DEĞİL, " +
                    "aşırı uykunun sonucu. Bugün şekerleme yok, yatak $bedTarget'ten önce yok."
        }
        s.lastNight?.efficiencyPct?.let { eff ->
            if (eff < 80)
                facts += "Dün verim %$eff (<%85). Takip metriği süre değil verimdir."
        }
        s.lastNight?.wakeTime?.let {
            val w = it.atZone(zone).toLocalTime()
            if (w.hour !in (wakeTarget - 1)..(wakeTarget + 1))
                facts += "Kalkış $w — %02d:00 hedefinden sapma. Sirkadiyen ".format(wakeTarget) +
                    "sabitlenme için en kritik değişken budur."
        }

        // --- Öğün / protein ---
        if (meals.isEmpty() && nowTime.hour >= 18)
            facts += "Bugün öğün logu yok. Protein hedefi $protMin–$protMax g " +
                "(bkz. Ayarlar); GLP-1 doz düşüşünde kas koruması için tampon işlevi görür."
        meals.maxByOrNull { it.ts }?.let { last ->
            val t = last.ts.atZone(zone).toLocalTime()
            if (t.isAfter(bedTarget.minusHours(2)))
                facts += "İHLAL: Son öğün $t — yatıştan 2–3 saat önce bitmeliydi."
        }

        // --- Kreatin / test ---
        if (s.pendingTests.any { it.contains("Cistatin", true) || it.contains("Sistatin", true) }) {
            facts += "Cistatin C testi bekliyor. Kreatin 2–3 hafta ara gerektirir " +
                "AMA bu karar HEKİMLE alınmalı — kendi başına bırakma önerisi verilemez. " +
                "Mevcut kreatinsiz gün: ${s.creatineFreeDays}."
        }

        return facts
    }

    // ───────────────────────────────────────────────────────
    // 2) Çıktı filtresi
    // ───────────────────────────────────────────────────────

    private val CLINICAL_TERMS = listOf(
        "egfr", "kreatinin", "hematokrit", "eritrositoz", "hemoglobin",
        "spo2", "oksijen satürasyon", "tirzepatid", "mounjaro", "prolaktin",
        "testosteron", "kan bağışı", "epo", "cistatin", "sistatin", "libido",
        // "glp" — Hafta 49'daki kreatin boşluğuyla aynı sınıf: "tirzepatid"/
        // "mounjaro" zaten listedeydi ama model ilacı ilaç sınıfı adıyla
        // ("GLP-1") anarsa (ör. "GLP-1 dozunu azaltıyorum") touchesClinical hiç
        // true olmuyordu — system_twin.md Bölüm D'nin kırmızı bölge listesindeki
        // "tirzepatid dozu" tam olarak bu senaryoyu kapsıyor. Kısa/bare "glp"
        // kasıtlı: "GLP-1", "GLP 1", "GLP1" gibi tire/boşluk varyasyonlarının
        // hepsini substring olarak yakalar (bkz. "epo" ile aynı kısa-terim deseni).
        "glp"
    )

    private val DECISION_VERBS = listOf(
        "bırakıyorum", "bırak", "başlıyorum", "başla", "dozu", "artırıyorum",
        "azaltıyorum", "erteliyorum", "gerek yok", "kesiyorum"
    )

    data class FilterResult(
        val cleanedActions: List<TwinAction>,
        val addedFlags: List<TwinFlag>,
        val violations: List<String>
    )

    /**
     * clinical domainli veya klinik terim + karar fiili içeren action'ları
     * bildirimden düşürür, yerine bayrak koyar.
     */
    fun filter(actions: List<TwinAction>): FilterResult {
        val clean = mutableListOf<TwinAction>()
        val flags = mutableListOf<TwinFlag>()
        val violations = mutableListOf<String>()

        actions.forEach { a ->
            val text = "${a.action} ${a.why}".lowercase()
            val touchesClinical = CLINICAL_TERMS.any { text.contains(it) }
            val hasDecision = DECISION_VERBS.any { text.contains(it) }
            // "kreatin" bilinçli olarak CLINICAL_TERMS'e eklenmedi — eklenirse
            // GÜNLÜK rutin kreatin hatırlatmaları (bkz. system_twin.md Bölüm E)
            // da touchesClinical=true olur ve alttaki yumuşak "(hekime danışarak)"
            // ekleme dalı her gün tetiklenirdi. Bunun yerine yalnızca "kreatin" +
            // karar fiili birlikte kontrol edilir.
            val touchesCreatine = text.contains("kreatin")

            when {
                a.domain.equals("clinical", true) -> {
                    violations += "clinical domainli action düşürüldü: ${a.action}"
                    flags += TwinFlag(a.action, "hekim değerlendirmesi gerekiyor", "none")
                }
                touchesClinical && hasDecision -> {
                    violations += "kırmızı bölgede karar cümlesi düşürüldü: ${a.action}"
                    flags += TwinFlag(a.action, "kendi başına aksiyon alınmaz", "none")
                }
                // Önceden bu dal yoktu: "Kreatini bırakıyorum" gibi bir karar cümlesi,
                // action/why alanlarının hiçbirinde eGFR/kreatinin gibi başka bir
                // CLINICAL_TERMS kelimesi geçmiyorsa touchesClinical hiç true olmuyordu
                // (yalnızca "kreatinin" listedeydi, "kreatin" değil) — bu yüzden yukarıdaki
                // dal atlanıp bu karar hiç filtrelenmeden bildirime düşüyordu. Bu, system_twin.md
                // Bölüm D Mutlak Kural 1'i ("Kreatini test öncesi bırakma önerisi verirken HER
                // ZAMAN 'hekime danışarak' ibaresi zorunlu") ihlal ediyordu.
                touchesCreatine && hasDecision && !text.contains("hekim") -> {
                    violations += "hekim ibaresi olmadan kreatin karar cümlesi düşürüldü: ${a.action}"
                    flags += TwinFlag(a.action, "kendi başına aksiyon alınmaz", "none")
                }
                touchesClinical && touchesCreatine && !text.contains("hekim") -> {
                    violations += "kreatin ara önerisi 'hekime danışarak' ibaresi olmadan geldi"
                    clean += a.copy(action = a.action + " (hekime danışarak)")
                }
                else -> clean += a
            }
        }
        return FilterResult(clean, flags, violations)
    }
}
