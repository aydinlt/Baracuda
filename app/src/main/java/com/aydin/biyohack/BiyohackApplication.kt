package com.aydin.biyohack

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.aydin.biyohack.sync.HealthSyncWorker
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
        TwinMorningWorker.scheduleNext(this)
        TwinWeeklyReviewWorker.scheduleNext(this)
    }
}
