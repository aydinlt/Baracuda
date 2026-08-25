package com.aydin.biyohack.di

import android.content.Context
import androidx.room.Room
import com.aydin.biyohack.BuildConfig
import com.aydin.biyohack.data.local.ALL_MIGRATIONS
import com.aydin.biyohack.data.local.AppDatabase
import com.aydin.biyohack.data.local.BodyMetricDao
import com.aydin.biyohack.data.local.ClinicalFlagDao
import com.aydin.biyohack.data.local.DailySnapshotDao
import com.aydin.biyohack.data.local.IntakeRecordDao
import com.aydin.biyohack.data.local.LabResultDao
import com.aydin.biyohack.data.local.ProfileDao
import com.aydin.biyohack.data.local.QuickTemplateDao
import com.aydin.biyohack.data.remote.createBiyohackSupabaseClient
import com.aydin.biyohack.data.repository.AuthRepository
import com.aydin.biyohack.data.repository.HealthSyncRepository
import com.aydin.biyohack.data.repository.ProfileRepository
import com.aydin.biyohack.data.repository.TwinRepository
import com.aydin.biyohack.health.HealthConnectManager
import com.aydin.biyohack.health.HealthDataSource
import com.aydin.biyohack.twin.TwinEngine
import com.aydin.biyohack.twin.TwinStateBuilder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.DB_NAME)
            .addMigrations(*ALL_MIGRATIONS)
            .build()

    @Provides fun provideDailySnapshotDao(db: AppDatabase): DailySnapshotDao = db.dailySnapshotDao()
    @Provides fun provideIntakeRecordDao(db: AppDatabase): IntakeRecordDao = db.intakeRecordDao()
    @Provides fun provideLabResultDao(db: AppDatabase): LabResultDao = db.labResultDao()
    @Provides fun provideClinicalFlagDao(db: AppDatabase): ClinicalFlagDao = db.clinicalFlagDao()
    @Provides fun provideProfileDao(db: AppDatabase): ProfileDao = db.profileDao()
    @Provides fun provideBodyMetricDao(db: AppDatabase): BodyMetricDao = db.bodyMetricDao()
    @Provides fun provideQuickTemplateDao(db: AppDatabase): QuickTemplateDao = db.quickTemplateDao()

    @Provides
    @Singleton
    fun provideSupabaseClient(): SupabaseClient = createBiyohackSupabaseClient()

    @Provides fun providePostgrest(client: SupabaseClient): Postgrest = client.postgrest
    @Provides fun provideAuth(client: SupabaseClient): Auth = client.auth

    @Provides
    @Singleton
    fun provideHealthConnectManager(@ApplicationContext context: Context): HealthConnectManager =
        HealthConnectManager(context)

    @Provides
    fun provideHealthDataSource(manager: HealthConnectManager): HealthDataSource = manager

    @Provides
    @Singleton
    fun provideHealthSyncRepository(
        dailySnapshotDao: DailySnapshotDao,
        intakeRecordDao: IntakeRecordDao,
        labResultDao: LabResultDao,
        clinicalFlagDao: ClinicalFlagDao,
        bodyMetricDao: BodyMetricDao,
        quickTemplateDao: QuickTemplateDao,
        postgrest: Postgrest,
        auth: Auth,
        healthDataSource: HealthDataSource
    ): HealthSyncRepository = HealthSyncRepository(
        dailySnapshotDao = dailySnapshotDao,
        intakeRecordDao = intakeRecordDao,
        labResultDao = labResultDao,
        clinicalFlagDao = clinicalFlagDao,
        bodyMetricDao = bodyMetricDao,
        quickTemplateDao = quickTemplateDao,
        postgrest = postgrest,
        healthDataSource = healthDataSource,
        currentUserId = { auth.currentUserOrNull()?.id }
    )

    @Provides
    @Singleton
    fun provideProfileRepository(profileDao: ProfileDao, postgrest: Postgrest): ProfileRepository =
        ProfileRepository(profileDao, postgrest)

    @Provides
    @Singleton
    fun provideAuthRepository(auth: Auth): AuthRepository = AuthRepository(auth)

    @Provides
    @Singleton
    fun provideTwinEngine(): TwinEngine = TwinEngine(
        proxyUrl = "${BuildConfig.SUPABASE_URL}/functions/v1/twin",
        supabaseAnonKey = BuildConfig.SUPABASE_ANON_KEY
    )

    @Provides
    @Singleton
    fun provideTwinStateBuilder(healthSyncRepository: HealthSyncRepository): TwinStateBuilder =
        TwinStateBuilder(healthSyncRepository)

    @Provides
    @Singleton
    fun provideTwinRepository(
        stateBuilder: TwinStateBuilder,
        engine: TwinEngine,
        postgrest: Postgrest,
        auth: Auth,
        healthSyncRepository: HealthSyncRepository,
        profileRepository: ProfileRepository
    ): TwinRepository = TwinRepository(
        stateBuilder = stateBuilder,
        engine = engine,
        postgrest = postgrest,
        healthSyncRepository = healthSyncRepository,
        profileRepository = profileRepository,
        currentUserId = { auth.currentUserOrNull()?.id }
    )
}
