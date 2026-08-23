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
            userNote = userNote,
            creatineFreeDays = healthSyncRepository.creatineFreeDays(),
            pendingTests = pendingTests,
            plannedTrainingToday = plannedTrainingToday,
            saunaPlannedToday = saunaPlannedToday
        )
    }
}
