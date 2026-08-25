package com.aydin.biyohack.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aydin.biyohack.data.repository.HealthSyncRepository
import com.aydin.biyohack.data.repository.TwinRepository
import com.aydin.biyohack.notifications.TwinNotifier
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Haftalık seyir analizini ([TwinRepository.runWeeklyReview] — Opus tier,
 * bkz. supabase/functions/twin/index.ts MODELS.weekly) her Pazar 20:00'de
 * otomatik çalıştırır.
 *
 * Şimdiye kadar bu analiz yalnızca TwinScreen'deki "Haftalık seyir analizi"
 * butonuna manuel basılarak tetikleniyordu — kullanıcı unutursa hiç
 * çalışmıyordu. TwinMorningWorker günlük protokolü otomatikleştiriyordu ama
 * haftalık analiz için eşdeğer bir tetikleyici hiç eklenmemişti; index.ts
 * "weekly" tier'ı zaten sabit bir sözleşme olarak tanımlıyor, yalnızca
 * uygulama tarafındaki zamanlayıcı eksikti.
 *
 * TwinMorningWorker ile aynı self-rescheduling OneTimeWorkRequest deseni
 * kullanılır — WorkManager'ın PeriodicWorkRequest'i haftanın belirli bir
 * gününde/SABİT saatte çalışmayı garanti etmez.
 */
@HiltWorker
class TwinWeeklyReviewWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val healthSyncRepository: HealthSyncRepository,
    private val twinRepository: TwinRepository,
    private val notifier: TwinNotifier
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // TwinMorningWorker.doWork()'ün kendi "ÖNEMLİ" notunda açıkladığı aynı sorun
        // burada da geçerliydi ama hiç ele alınmamıştı: HealthSyncWorker yalnızca 6
        // saatte bir çalışıyor, bu worker ise haftanın sabit bir anında (Pazar 20:00)
        // — herhangi bir "az önce senkronize edildi" olayına bağlı olmadan — tetikleniyor.
        // syncAll() çağrılmadan runWeeklyReview() son 14 gecenin Room'daki en güncel
        // halini değil, en son ne zaman HealthSyncWorker çalıştıysa o anki (saatler
        // öncesine ait olabilecek) veriyi okuyordu.
        healthSyncRepository.syncAll()
        val outcome = twinRepository.runWeeklyReview()
        outcome.getOrNull()?.let { notifier.notify(it) }
        scheduleNext(applicationContext)
        return if (outcome.isSuccess) Result.success() else Result.retry()
    }

    companion object {
        private const val WORK_NAME = "twin_weekly_review"
        private val TARGET_DAY: DayOfWeek = DayOfWeek.SUNDAY
        private val TARGET_TIME: LocalTime = LocalTime.of(20, 0)

        /** İlk kurulum (Application.onCreate) ve her çalışmadan sonra (kendini yeniden zamanlama) çağrılır. */
        fun scheduleNext(context: Context) {
            val zone = ZoneId.systemDefault()
            val now = ZonedDateTime.now(zone)
            var next = now.toLocalDate().atTime(TARGET_TIME).atZone(zone)
            while (next.dayOfWeek != TARGET_DAY || !next.isAfter(now)) {
                next = next.plusDays(1)
            }
            val delay = Duration.between(now, next)

            val request = OneTimeWorkRequestBuilder<TwinWeeklyReviewWorker>()
                .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
                .build()

            // REPLACE: scheduleNext her çağrıldığında (worker'ın kendisi dahil) bekleyen
            // eski zamanlamayı iptal edip yenisini kurar — çift tetikleme olmaz.
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
