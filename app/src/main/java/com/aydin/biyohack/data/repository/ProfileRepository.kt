package com.aydin.biyohack.data.repository

import com.aydin.biyohack.data.Profile
import com.aydin.biyohack.data.local.ProfileDao
import com.aydin.biyohack.data.local.SyncState
import com.aydin.biyohack.data.local.toDomain
import com.aydin.biyohack.data.local.toEntity
import com.aydin.biyohack.data.remote.ProfileRow
import com.aydin.biyohack.data.remote.toDomain
import com.aydin.biyohack.data.remote.toRow
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * `profiles` tablosunun offline-first katmanı — HealthSyncRepository'nin
 * kalıbıyla aynı: Room önce/güvenilir, Supabase best-effort.
 */
class ProfileRepository(
    private val profileDao: ProfileDao,
    private val postgrest: Postgrest
) {
    fun observe(userId: String): Flow<Profile?> =
        profileDao.observe(userId).map { it?.toDomain() }

    /**
     * Supabase'de bu kullanıcı için satır varsa yerele çeker; yoksa
     * varsayılan profille (bkz. [Profile] varsayılan parametreler) hem
     * Supabase'de hem yerelde oluşturur. Auth ekranından ilk girişte çağrılır.
     */
    suspend fun ensureLoaded(userId: String): Result<Profile> = runCatching {
        val remote = postgrest.from("profiles")
            .select { filter { eq("id", userId) } }
            .decodeSingleOrNull<ProfileRow>()

        val profile = remote?.toDomain() ?: Profile(userId = userId)
        profileDao.upsert(profile.toEntity(SyncState.SYNCED))
        if (remote == null) upsert(profile).getOrThrow()
        profile
    }

    suspend fun upsert(profile: Profile): Result<Unit> = runCatching {
        profileDao.upsert(profile.toEntity(SyncState.PENDING))
        pushPending().getOrThrow()
    }

    suspend fun pushPending(): Result<Unit> = runCatching {
        profileDao.getPending().forEach { entity ->
            val row = entity.toDomain().toRow()
            postgrest.from("profiles").upsert(row) { onConflict = "id" }
            profileDao.markSynced(entity.userId)
        }
    }
}
