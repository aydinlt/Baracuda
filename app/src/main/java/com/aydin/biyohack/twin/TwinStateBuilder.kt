package com.aydin.biyohack.twin

import com.aydin.biyohack.data.repository.HealthSyncRepository
import kotlinx.coroutines.flow.first
import java.time.Instant

/**
 * Room'daki gecelik snapshot'ları ve bugünkü logları [TwinState]'e döken
 * use-case — Hafta 1/2'nin repository katmanı ile Hafta 3/4'ün kural
 * motoru/LLM katmanı ([TwinGuardrails], [TwinEngine]) arasındaki bağlantı.
 *
 * `pendingTests`, çözülmemiş [com.aydin.biyohack.data.ClinicalFlagRecord]
 * kayıtlarından "test" geçenler filtrelenerek türetilir — kesin bir liste
 * değil, TwinGuardrails'in kendi ürettiği bayraklar (örn. "hekim
 * değerlendirmesi gerekiyor") da buraya düşebilir; bu kasıtlı: ikiz eksik
 * veriyi uydurmaz, elindeki en güncel sinyali kullanır.
 */
class TwinStateBuilder(
    private val healthSyncRepository: HealthSyncRepository
) {
    suspend fun build(
        trigger: Trigger,
        userNote: String? = null,
        plannedTrainingToday: Boolean = false,
        saunaPlannedToday: Boolean = false,
        snapshotLimit: Int = 8
    ): TwinState {
        val snapshots = healthSyncRepository.observeRecentSnapshots(limit = snapshotLimit).first()
        val todayIntake = healthSyncRepository.observeTodayIntake().first()
        val pendingTests = healthSyncRepository.observeUnresolvedFlags().first()
            .filter { it.finding.contains("test", ignoreCase = true) || it.status.contains("test", ignoreCase = true) }
            .map { it.finding }

        return TwinState(
            now = Instant.now(),
            trigger = trigger,
            lastNight = snapshots.firstOrNull(),
            previousNights = snapshots.drop(1),
            todayIntake = todayIntake.map { it.toTwinEntry() },
            userNote = combinedNote(userNote),
            creatineFreeDays = healthSyncRepository.creatineFreeDays(),
            pendingTests = pendingTests,
            plannedTrainingToday = plannedTrainingToday,
            saunaPlannedToday = saunaPlannedToday
        )
    }

    /**
     * TwinState.kt'nin sabit sözleşmesinde kilo/bel çevresi için ayrı bir
     * alan yok (bkz. Hafta 11 — body_metric bu bilgiyi ekledi ama TwinState'i
     * genişletmedi). Bunun yerine son ölçüm, kullanıcının kendi notunun
     * ÖNÜNE eklenerek `userNote`'a taşınır — TwinStateSerializer zaten bu
     * alanı "KULLANICI NOTU" başlığıyla prompt'a katıyor.
     */
    private suspend fun combinedNote(userNote: String?): String? {
        val metric = healthSyncRepository.observeRecentBodyMetrics(limit = 1).first().firstOrNull()
        val metricSummary = metric?.let {
            val parts = listOfNotNull(
                it.weightKg?.let { w -> "%.1f kg".format(w) },
                it.waistCm?.let { c -> "%.1f cm bel".format(c) }
            )
            if (parts.isEmpty()) null else "Son ölçüm (${it.date}): ${parts.joinToString(", ")}"
        }
        return listOfNotNull(metricSummary, userNote).joinToString("\n").ifBlank { null }
    }
}
