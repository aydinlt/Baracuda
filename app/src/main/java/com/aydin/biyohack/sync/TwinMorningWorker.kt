package com.aydin.biyohack.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aydin.biyohack.data.repository.HealthSyncRepository
import com.aydin.biyohack.data.repository.ProfileRepository
import com.aydin.biyohack.data.repository.TwinRepository
import com.aydin.biyohack.notifications.TwinNotifier
import com.aydin.biyohack.twin.Trigger
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
 * Sabah protokolünü her gün, kullanıcının Ayarlar'da belirlediği kalkış
 * hedefinden ([com.aydin.biyohack.data.Profile.wakeTarget]) 30 dakika sonra
 * otomatik çalıştırır — Health Connect'in gece verisini işleyip senkron
 * edilebilir hale getirmesi için bir tampon. Profil henüz yüklenmemişse
 * (örn. hiç giriş yapılmamış) system_twin.md Bölüm 4'teki varsayılan
 * "Sabah protokolü 07:30" kullanılır.
 *
 * WorkManager günlük SABİT SAATTE çalışmayı doğrudan desteklemez
 * (PeriodicWorkRequest yalnızca aralık garantisi verir, ilk çalışma anı
 * kesin değildir) — bu yüzden kendini bir sonraki güne yeniden zamanlayan
 * (self-rescheduling) OneTimeWorkRequest deseni kullanılır. Kullanıcı
 * kalkış hedefini değiştirdiğinde SettingsViewModel de aynı [scheduleNext]'i
 * çağırır — bir sonraki çalışmayı ertesi güne kadar beklemeden günceller.
 *
 * ÖNEMLİ: `runProtocol()`'dan önce `syncAll()` çağrılır. HealthSyncWorker
 * yalnızca 6 saatte bir çalışıyor (bkz. HealthSyncWorker.kt) — doze mode
 * gecikmesi ya da basitçe zamanlama yüzünden hedef saatte Room'daki gece
 * verisi hâlâ dünkü olabilir. Senkron etmeden protokolü çalıştırmak,
 * TwinEngine'e eski/eksik veri vermek demektir.
 */
@HiltWorker
class TwinMorningWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val healthSyncRepository: HealthSyncRepository,
    private val twinRepository: TwinRepository,
    private val profileRepository: ProfileRepository,
    private val auth: Auth,
    private val notifier: TwinNotifier
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        healthSyncRepository.syncAll()
        val outcome = twinRepository.runProtocol(Trigger.MORNING_PROTOCOL)
        outcome.getOrNull()?.let { notifier.notify(it) }
        scheduleNext(applicationContext, nextTargetTime())
        return if (outcome.isSuccess) Result.success() else Result.retry()
    }

    private suspend fun nextTargetTime(): LocalTime {
        val userId = auth.currentUserOrNull()?.id ?: return DEFAULT_TARGET_TIME
        val profile = profileRepository.observe(userId).first()
        return profile?.wakeTarget?.plusMinutes(30) ?: DEFAULT_TARGET_TIME
    }

    companion object {
        private const val WORK_NAME = "twin_morning_protocol"
        private val DEFAULT_TARGET_TIME: LocalTime = LocalTime.of(7, 30)

        /**
         * İlk kurulum (Application.onCreate — profil henüz Room'a çekilmemiş
         * olabileceği için varsayılan saatle), her çalışmadan sonra (kendini
         * profile göre yeniden zamanlama) ve kullanıcı kalkış hedefini
         * Ayarlar'dan değiştirdiğinde çağrılır.
         */
        fun scheduleNext(context: Context, targetTime: LocalTime = DEFAULT_TARGET_TIME) {
            val zone = ZoneId.systemDefault()
            val now = ZonedDateTime.now(zone)
            var next = now.toLocalDate().atTime(targetTime).atZone(zone)
            if (!next.isAfter(now)) next = next.plusDays(1)
            val delay = Duration.between(now, next)

            val request = OneTimeWorkRequestBuilder<TwinMorningWorker>()
                .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
                .build()

            // REPLACE: scheduleNext her çağrıldığında (worker'ın kendisi ya da Ayarlar'dan
            // gelen bir profil güncellemesi) bekleyen eski zamanlamayı iptal edip yenisini
            // kurar — çift tetikleme olmaz.
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
