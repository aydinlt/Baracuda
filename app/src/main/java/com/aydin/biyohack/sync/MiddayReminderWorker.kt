package com.aydin.biyohack.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aydin.biyohack.data.IntakeKind
import com.aydin.biyohack.data.repository.HealthSyncRepository
import com.aydin.biyohack.data.repository.ProfileRepository
import com.aydin.biyohack.notifications.TwinNotifier
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.github.jan.supabase.auth.Auth
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Günün ortasında ("bugün henüz su/protein logu yok") tek, birleşik bir
 * hatırlatma bildirimi gönderir. Önceden bu tür bir eksiklik yalnızca
 * TwinGuardrails'in ürettiği fact'ler üzerinden, sabah/haftalık protokol
 * çalıştığında görülebiliyordu — gün içinde proaktif hiçbir uyarı yoktu.
 *
 * KASITLI OLARAK KREATİN'İ KAPSAMAZ: TwinGuardrails/TwinState.creatineFreeDays
 * kreatinsiz günleri Cistatin C testi öncesi BİLİNÇLİ bir ara olarak izliyor
 * (bkz. TwinRepository/TwinGuardrails yorumları) — "bugün kreatin logu yok"
 * diye hatırlatmak bu ilkeyle doğrudan çelişirdi, kreatin kasıtlı olarak
 * her gün loglanması beklenen bir şey değil.
 *
 * TwinMorningWorker/TwinWeeklyReviewWorker ile aynı self-rescheduling
 * OneTimeWorkRequest deseni — WorkManager sabit saatte çalışmayı garanti
 * etmez. Sabit 15:00 hedefi (kullanıcıya özel değil): günün yarısı geçmiş,
 * ama akşama kadar hâlâ tepki verecek zaman var.
 */
@HiltWorker
class MiddayReminderWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val healthSyncRepository: HealthSyncRepository,
    private val profileRepository: ProfileRepository,
    private val auth: Auth,
    private val notifier: TwinNotifier
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        runCatching { checkAndNotify() }
        scheduleNext(applicationContext)
        return Result.success()
    }

    private suspend fun checkAndNotify() {
        val userId = auth.currentUserOrNull()?.id ?: return
        val profile = profileRepository.ensureLoaded(userId).getOrNull()
            ?: profileRepository.observe(userId).first()
            ?: return
        val todayIntake = healthSyncRepository.observeTodayIntake().first()

        val waterMl = todayIntake.filter { it.kind == IntakeKind.WATER }.sumOf { it.amount ?: 0.0 }
        // DashboardScreen'deki aynı kural: MEAL kayıtlarında amount yalnızca
        // unit="g protein" ile loglananlarda protein gramıdır.
        val proteinG = todayIntake
            .filter { it.kind == IntakeKind.MEAL && it.unit == "g protein" }
            .sumOf { it.amount ?: 0.0 }

        val gaps = buildList {
            if (waterMl < profile.waterTargetMl * 0.5) add("su (${waterMl.toInt()}/${profile.waterTargetMl} ml)")
            if (proteinG < profile.proteinTargetMinG * 0.5) add("protein (${proteinG.toInt()}/${profile.proteinTargetMinG} g)")
        }
        if (gaps.isEmpty()) return

        notifier.notifyReminder(
            "Günün yarısı geçti",
            "Bugün henüz geride: " + gaps.joinToString(", ") + "."
        )
    }

    companion object {
        private const val WORK_NAME = "midday_reminder"
        private val TARGET_TIME: LocalTime = LocalTime.of(15, 0)

        /** İlk kurulum (Application.onCreate) ve her çalışmadan sonra (kendini yeniden zamanlama) çağrılır. */
        fun scheduleNext(context: Context) {
            val zone = ZoneId.systemDefault()
            val now = ZonedDateTime.now(zone)
            var next = now.toLocalDate().atTime(TARGET_TIME).atZone(zone)
            if (!next.isAfter(now)) next = next.plusDays(1)
            val delay = Duration.between(now, next)

            val request = OneTimeWorkRequestBuilder<MiddayReminderWorker>()
                .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
                .build()

            // REPLACE: scheduleNext her çağrıldığında bekleyen eski zamanlamayı iptal edip
            // yenisini kurar — çift tetikleme olmaz (bkz. TwinMorningWorker aynı desen).
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
