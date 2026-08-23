package com.aydin.biyohack.twin

import com.aydin.biyohack.data.repository.HealthSyncRepository
import kotlinx.coroutines.flow.first
import java.time.Instant

/**
 * Room'daki gecelik snapshot'ları ve bugünkü logları [TwinState]'e döken
 * use-case — Hafta 1/2'nin repository katmanı ile Hafta 4'ün kural
 * motoru/LLM katmanı ([TwinGuardrails], [TwinEngine]) arasındaki bağlantı.
 *
 * NOT: `creatineFreeDays` ve `pendingTests` şimdilik varsayılan/boş.
 * İkisi de kreatin log geçmişi ve `clinical_flag`/`lab_result` analizine
 * dayanıyor — bu, clinical_flag entegrasyonuyla birlikte Hafta 4'te dolar.
 */
class TwinStateBuilder(
    private val healthSyncRepository: HealthSyncRepository
) {
    suspend fun build(
        trigger: Trigger,
        userNote: String? = null,
        plannedTrainingToday: Boolean = false,
        saunaPlannedToday: Boolean = false
    ): TwinState {
        val snapshots = healthSyncRepository.observeRecentSnapshots(limit = 8).first()
        val todayIntake = healthSyncRepository.observeTodayIntake().first()

        return TwinState(
            now = Instant.now(),
            trigger = trigger,
            lastNight = snapshots.firstOrNull(),
            previousNights = snapshots.drop(1),
            todayIntake = todayIntake.map { it.toTwinEntry() },
            userNote = userNote,
            creatineFreeDays = 0,
            pendingTests = emptyList(),
            plannedTrainingToday = plannedTrainingToday,
            saunaPlannedToday = saunaPlannedToday
        )
    }
}
