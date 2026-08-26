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
         * Her çalışmadan sonra (kendini profile göre yeniden zamanlama) ve
         * kullanıcı kalkış hedefini Ayarlar'dan değiştirdiğinde çağrılır —
         * her ikisi de ELİNDE gerçek/güncel bir hedef olduğu için REPLACE
         * kullanır (bkz. [ensureScheduled] için Application.onCreate() notu).
         */
        fun scheduleNext(context: Context, targetTime: LocalTime = DEFAULT_TARGET_TIME) {
            enqueue(context, targetTime, ExistingWorkPolicy.REPLACE)
        }

        /**
         * Application.onCreate()'in bootstrap çağrısı için — [scheduleNext]'in
         * aksine ExistingWorkPolicy.KEEP kullanır.
         *
         * ÖNCEDEN Application.onCreate() doğrudan scheduleNext(context)'i (varsayılan
         * DEFAULT_TARGET_TIME=07:30 ile) çağırıyordu. Application.onCreate() yalnızca
         * ilk kurulumda değil, WorkManager'ın HERHANGİ bir worker'ı (bu ikisi dahil
         * TwinWeeklyReviewWorker/MiddayReminderWorker/HealthSyncWorker) çalıştırmak
         * için önce sürecin ayağa kalkması gerektiği HER seferinde de çalışır — yani
         * uygulama arayüzü hiç açılmasa bile, bir arka plan işi süreci yeniden
         * başlattığında da tetiklenir. REPLACE ile birleşince bu, kullanıcının
         * Ayarlar'da seçtiği gerçek kalkış hedefine göre ÖNCEDEN DOĞRU zamanlanmış
         * bir işi sessizce iptal edip sabit 07:30'a döndürüyordu — worker kendi
         * çalışmasının sonunda gerçek profile göre tekrar doğru saate dönene kadar,
         * kullanıcının o günkü sabah protokolü yanlış saatte tetiklenirdi.
         *
         * KEEP: iş zaten planlıysa (normal durum — worker kendi kendini zaten doğru
         * zamanlıyor) DOKUNMAZ; yalnızca hiç plan yoksa (yalnızca gerçek ilk kurulum)
         * varsayılan saatle kurar.
         */
        fun ensureScheduled(context: Context) {
            enqueue(context, DEFAULT_TARGET_TIME, ExistingWorkPolicy.KEEP)
        }

        private fun enqueue(context: Context, targetTime: LocalTime, policy: ExistingWorkPolicy) {
            val zone = ZoneId.systemDefault()
            val now = ZonedDateTime.now(zone)
            var next = now.toLocalDate().atTime(targetTime).atZone(zone)
            if (!next.isAfter(now)) next = next.plusDays(1)
            val delay = Duration.between(now, next)

            val request = OneTimeWorkRequestBuilder<TwinMorningWorker>()
                .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request)
        }
    }
}
