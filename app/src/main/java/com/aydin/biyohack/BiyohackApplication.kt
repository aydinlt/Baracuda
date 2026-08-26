package com.aydin.biyohack

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.aydin.biyohack.sync.HealthSyncWorker
import com.aydin.biyohack.sync.MiddayReminderWorker
import com.aydin.biyohack.sync.TwinMorningWorker
import com.aydin.biyohack.sync.TwinWeeklyReviewWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class BiyohackApplication : Application(), Configuration.Provider {

    @Inject lateinit var hiltWorkerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(hiltWorkerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        HealthSyncWorker.schedulePeriodic(this)
        // TwinMorningWorker.ensureScheduled() (KEEP) kullanır, scheduleNext() (REPLACE)
        // DEĞİL — bkz. TwinMorningWorker.ensureScheduled() KDoc'u (Hafta 61). onCreate()
        // yalnızca ilk kurulumda değil, WorkManager herhangi bir worker'ı çalıştırmak
        // için süreci her yeniden başlattığında da tetiklenir; REPLACE + varsayılan
        // 07:30 kullanmak, kullanıcının Ayarlar'daki gerçek kalkış hedefine göre önceden
        // doğru zamanlanmış bir işi sessizce iptal edip sabit 07:30'a döndürüyordu.
        TwinMorningWorker.ensureScheduled(this)
        TwinWeeklyReviewWorker.scheduleNext(this)
        MiddayReminderWorker.scheduleNext(this)
    }
}
