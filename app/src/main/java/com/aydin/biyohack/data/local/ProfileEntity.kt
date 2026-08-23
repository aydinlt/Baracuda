package com.aydin.biyohack.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import com.aydin.biyohack.data.Profile
import kotlinx.coroutines.flow.Flow
import java.time.LocalTime

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey val userId: String,
    val fullName: String,
    val birthYear: Int?,
    val sex: String,
    val heightCm: Double,
    val timezone: String,
    val waterTargetMl: Int,
    val proteinTargetMinG: Int,
    val proteinTargetMaxG: Int,
    val wakeTarget: LocalTime,
    val bedEarliest: LocalTime,
    val syncState: SyncState
)

fun Profile.toEntity(syncState: SyncState = SyncState.PENDING) = ProfileEntity(
    userId = userId,
    fullName = fullName,
    birthYear = birthYear,
    sex = sex,
    heightCm = heightCm,
    timezone = timezone,
    waterTargetMl = waterTargetMl,
    proteinTargetMinG = proteinTargetMinG,
    proteinTargetMaxG = proteinTargetMaxG,
    wakeTarget = wakeTarget,
    bedEarliest = bedEarliest,
    syncState = syncState
)

fun ProfileEntity.toDomain() = Profile(
    userId = userId,
    fullName = fullName,
    birthYear = birthYear,
    sex = sex,
    heightCm = heightCm,
    timezone = timezone,
    waterTargetMl = waterTargetMl,
    proteinTargetMinG = proteinTargetMinG,
    proteinTargetMaxG = proteinTargetMaxG,
    wakeTarget = wakeTarget,
    bedEarliest = bedEarliest
)

@Dao
interface ProfileDao {

    @Upsert
    suspend fun upsert(entity: ProfileEntity)

    @Query("SELECT * FROM profiles WHERE userId = :userId LIMIT 1")
    fun observe(userId: String): Flow<ProfileEntity?>

    @Query("SELECT * FROM profiles WHERE userId = :userId LIMIT 1")
    suspend fun get(userId: String): ProfileEntity?

    @Query("SELECT * FROM profiles WHERE syncState = 'PENDING'")
    suspend fun getPending(): List<ProfileEntity>

    @Query("UPDATE profiles SET syncState = 'SYNCED' WHERE userId = :userId")
    suspend fun markSynced(userId: String)
}
