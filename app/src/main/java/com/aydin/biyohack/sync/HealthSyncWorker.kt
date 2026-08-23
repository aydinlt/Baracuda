package com.aydin.biyohack.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aydin.biyohack.data.repository.HealthSyncRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Health Connect → Room → Supabase zincirini arka planda periyodik çalıştırır.
 * Manuel "Şimdi Senkronize Et" (DashboardViewModel.syncNow) ile aynı
 * [HealthSyncRepository.syncAll] fonksiyonunu paylaşır — iki ayrı senkronizasyon
 * mantığı yok.
 */
@HiltWorker
class HealthSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: HealthSyncRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val outcome = repository.syncAll()
        return if (outcome.isSuccess) Result.success() else Result.retry()
    }

    companion object {
        private const val WORK_NAME = "health_sync_periodic"

        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<HealthSyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
