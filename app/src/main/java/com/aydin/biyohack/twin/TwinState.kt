package com.aydin.biyohack.twin

import com.aydin.biyohack.data.DailySnapshot
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

// ────────────────────────────────────────────────────────────
// Gün içi loglar (Hafta 2'de Room'dan gelecek)
// ────────────────────────────────────────────────────────────

enum class IntakeType { MEAL, COFFEE, WATER, SUPPLEMENT }

data class IntakeEntry(
    val ts: Instant,
    val type: IntakeType,
    val label: String,
    val amount: Double? = null,      // ml, mg, g
    val unit: String? = null
)

enum class Trigger { MORNING_PROTOCOL, COFFEE_LOGGED, MEAL_LOGGED, WATER_LOGGED,
                     SUPPLEMENT_LOGGED, FATIGUE_REPORTED, MANUAL }

/**
 * İkize gönderilen anlık durum. Statik profil promptta cache'li olduğu için
 * burada YALNIZCA değişen şeyler var — token maliyetinin tamamı bu.
 */
data class TwinState(
    val now: Instant,
    val trigger: Trigger,
    val lastNight: DailySnapshot?,
    val previousNights: List<DailySnapshot>,
    val todayIntake: List<IntakeEntry>,
    val userNote: String? = null,
    val creatineFreeDays: Int = 0,
    val pendingTests: List<String> = emptyList(),
    val plannedTrainingToday: Boolean = false,
    val saunaPlannedToday: Boolean = false
)

object TwinStateSerializer {

    private val zone: ZoneId = ZoneId.systemDefault()

    /**
     * [waterTargetMl]/[wakeTargetHour] — null bırakılırsa eski sabit
     * varsayılanlar (4000 ml, 07:00) kullanılır (geriye dönük uyumlu).
     *
     * ÖNEMLİ: Bu iki değer TwinEngine.generate() içinde zaten TwinGuardrails.
     * buildFacts()'a geçiriliyordu (bkz. Hafta 21 commit notu) ama BURAYA hiç
     * ulaşmıyordu — "KURAL MOTORU" bölümü kullanıcının gerçek hedefine göre
     * doğru "su geride/uygun" değerlendirmesi yaparken, hemen üstündeki
     * "BUGÜNKÜ LOGLAR" bölümü sessizce sabit "4000 ml hedef" yazıyordu.
     * Kullanıcı Ayarlar'dan hedefini değiştirdiğinde (ör. 5000 ml) İkiz aynı
     * prompt içinde birbiriyle çelişen iki hedef görüyordu.
     */
    fun toPromptBlock(
        s: TwinState,
        ruleFacts: List<String>,
        waterTargetMl: Int? = null,
        wakeTargetHour: Int? = null
    ): String = buildString {
        val today = s.now.atZone(zone)
        appendLine("═══ ANLIK DURUM ═══")
        appendLine("Şu an: ${today.toLocalDate()} ${today.toLocalTime().withNano(0)}")
        appendLine("Tetikleyici: ${triggerLabel(s.trigger)}")
        appendLine()

        // ---- Dün gece ----
        appendLine("── DÜN GECE ──")
        if (s.lastNight == null) {
            appendLine("VERİ YOK — uyku hakkında öneri üretme, data_gaps'e yaz.")
        } else {
            val n = s.lastNight
            appendLine(buildString {
                n.asleepMin?.let { append("Uyku ${it / 60}s ${it % 60}d  ") }
                n.timeInBedMin?.let { append("Yatakta ${it / 60}s ${it % 60}d  ") }
                n.efficiencyPct?.let { append("Verim %$it  ") }
                n.sleepScore?.let { append("Skor $it") }
            }.ifBlank { "kısmi veri" })
            appendLine(buildString {
                n.remPct?.let { append("REM %$it  ") }
                n.deepPct?.let { append("Derin %$it  ") }
                n.awakeMin?.let { append("Uyanık ${it}dk") }
            })
            n.bedTime?.let {
                appendLine("Yatış: ${it.atZone(zone).toLocalTime().withNano(0)}")
            }
            n.wakeTime?.let {
                appendLine("Kalkış: ${it.atZone(zone).toLocalTime().withNano(0)} " +
                    "(hedef %02d:00)".format(wakeTargetHour ?: 7))
            }
            n.spo2Avg?.let {
                append("SpO2 ort %.1f%%".format(it))
                n.minutesBelow90?.let { m ->
                    append(" • %90 altı ${m}dk")
                    if (n.minutesBelow90IsEstimate) append(" (TAHMİN)")
                }
                appendLine()
            }
            n.snoringMin?.let { appendLine("Horlama: ${it}dk") }
        }
        appendLine()

        // ---- Son günlerin eğilimi ----
        if (s.previousNights.isNotEmpty()) {
            appendLine("── SON ${s.previousNights.size} GECE ──")
            val avgSleep = s.previousNights.mapNotNull { it.asleepMin }.average()
            val avgEff = s.previousNights.mapNotNull { it.efficiencyPct }.average()
            if (!avgSleep.isNaN())
                appendLine("Ort. uyku: %.0f dk (%.1f saat)".format(avgSleep, avgSleep / 60))
            if (!avgEff.isNaN()) appendLine("Ort. verim: %.0f%%".format(avgEff))
            val bedTimes = s.previousNights.mapNotNull { it.bedTime }
                .map { it.atZone(zone).toLocalTime() }
            if (bedTimes.size >= 2) {
                appendLine("Yatış saati aralığı: ${bedTimes.min()} – ${bedTimes.max()} " +
                    "(dağınıklık sirkadiyen yük demektir)")
            }
            appendLine()
        }

        // ---- Bugünkü loglar ----
        appendLine("── BUGÜNKÜ LOGLAR ──")
        if (s.todayIntake.isEmpty()) {
            appendLine("Hiç log yok.")
        } else {
            s.todayIntake.sortedBy { it.ts }.forEach { e ->
                val t = e.ts.atZone(zone).toLocalTime().withNano(0)
                val amt = e.amount?.let { " ${it.toInt()}${e.unit ?: ""}" } ?: ""
                appendLine("$t  ${typeLabel(e.type)}: ${e.label}$amt")
            }
        }
        val water = s.todayIntake.filter { it.type == IntakeType.WATER }
            .sumOf { it.amount ?: 0.0 }
        appendLine("Su toplamı: ${water.toInt()} ml / ${waterTargetMl ?: 4000} ml hedef")
        val lastMeal = s.todayIntake.filter { it.type == IntakeType.MEAL }.maxByOrNull { it.ts }
        if (lastMeal == null) appendLine("Bugün henüz öğün yok (OMAD penceresi açık)")
        else {
            val h = Duration.between(lastMeal.ts, s.now).toHours()
            appendLine("Son öğünden bu yana: $h saat")
        }
        appendLine()

        // ---- Plan ----
        appendLine("── BUGÜN PLANLI ──")
        appendLine("Antrenman: ${if (s.plannedTrainingToday) "VAR" else "yok"}")
        appendLine("Sauna: ${if (s.saunaPlannedToday) "VAR (elektrolit zorunlu)" else "yok"}")
        appendLine("Kreatinsiz gün sayacı: ${s.creatineFreeDays}")
        if (s.pendingTests.isNotEmpty())
            appendLine("Bekleyen testler: ${s.pendingTests.joinToString(", ")}")
        appendLine()

        // ---- Kural motoru çıktısı: TARTIŞILMAZ ----
        appendLine("── KURAL MOTORU (deterministik, tartışma) ──")
        if (ruleFacts.isEmpty()) appendLine("Tetiklenen kural yok.")
        else ruleFacts.forEach { appendLine("• $it") }
        appendLine()

        s.userNote?.let {
            appendLine("── KULLANICI NOTU ──")
            appendLine(it)
        }
    }

    private fun triggerLabel(t: Trigger) = when (t) {
        // Sabit "(07:30)" kaldırıldı: gerçek çalışma saati profile.wakeTarget + 30dk'dır
        // (bkz. TwinMorningWorker.scheduleNext) — kullanıcı kalkış hedefini değiştirdiğinde
        // bu literal yanlış bilgi vermeye başlıyordu. Üstteki "Şu an: ..." satırı zaten
        // gerçek saati taşıyor, ayrıca tahmini bir saat eklemeye gerek yok.
        Trigger.MORNING_PROTOCOL -> "Sabah protokolü"
        Trigger.COFFEE_LOGGED -> "Kahve loglandı"
        Trigger.MEAL_LOGGED -> "Öğün loglandı"
        Trigger.WATER_LOGGED -> "Su güncellendi"
        Trigger.SUPPLEMENT_LOGGED -> "Takviye loglandı"
        Trigger.FATIGUE_REPORTED -> "Yorgunluk/uykusuzluk bildirildi"
        Trigger.MANUAL -> "Manuel istek"
    }

    private fun typeLabel(t: IntakeType) = when (t) {
        IntakeType.MEAL -> "Öğün"
        IntakeType.COFFEE -> "Kahve"
        IntakeType.WATER -> "Su"
        IntakeType.SUPPLEMENT -> "Takviye"
    }
}
