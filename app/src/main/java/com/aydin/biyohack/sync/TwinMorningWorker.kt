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
import com.aydin.biyohack.twin.Trigger
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Sabah protokolünü her gün 07:30'da otomatik çalıştırır — system_twin.md
 * Bölüm F'deki sabit kalkış hedefiyle aynı saat, Bölüm 4'teki "Sabah
 * protokolü 07:30, Sonnet 5, 1×/gün" tetikleyicisi.
 *
 * WorkManager günlük SABİT SAATTE çalışmayı doğrudan desteklemez
 * (PeriodicWorkRequest yalnızca aralık garantisi verir, ilk çalışma anı
 * kesin değildir) — bu yüzden kendini bir sonraki güne yeniden zamanlayan
 * (self-rescheduling) OneTimeWorkRequest deseni kullanılır.
 *
 * ÖNEMLİ: `runProtocol()`'dan önce `syncAll()` çağrılır. HealthSyncWorker
 * yalnızca 6 saatte bir çalışıyor (bkz. HealthSyncWorker.kt) — doze mode
 * gecikmesi ya da basitçe zamanlama yüzünden 07:30'da Room'daki gece
 * verisi hâlâ dünkü olabilir. Senkron etmeden protokolü çalıştırmak,
 * TwinEngine'e eski/eksik veri vermek demektir.
 */
@HiltWorker
class TwinMorningWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val healthSyncRepository: HealthSyncRepository,
    private val twinRepository: TwinRepository,
    private val notifier: TwinNotifier
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        healthSyncRepository.syncAll()
        val outcome = twinRepository.runProtocol(Trigger.MORNING_PROTOCOL)
        outcome.getOrNull()?.let { notifier.notify(it) }
        scheduleNext(applicationContext)
        return if (outcome.isSuccess) Result.success() else Result.retry()
    }

    companion object {
        private const val WORK_NAME = "twin_morning_protocol"
        private val TARGET_TIME: LocalTime = LocalTime.of(7, 30)

        /** İlk kurulum (Application.onCreate) ve her çalışmadan sonra (kendini yeniden zamanlama) çağrılır. */
        fun scheduleNext(context: Context) {
            val zone = ZoneId.systemDefault()
            val now = ZonedDateTime.now(zone)
            var next = now.toLocalDate().atTime(TARGET_TIME).atZone(zone)
            if (!next.isAfter(now)) next = next.plusDays(1)
            val delay = Duration.between(now, next)

            val request = OneTimeWorkRequestBuilder<TwinMorningWorker>()
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
