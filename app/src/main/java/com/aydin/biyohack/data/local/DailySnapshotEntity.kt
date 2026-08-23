package com.aydin.biyohack.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import com.aydin.biyohack.data.DailySnapshot
import com.aydin.biyohack.data.SnapshotSource
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate

/**
 * Room yerel önbelleği: kullanıcı tek olduğu için PK olarak `date`in
 * epoch-day değeri kullanılır — günde en fazla bir satır garantisi bu
 * şekilde şema seviyesinde, ekstra unique index'e gerek kalmadan sağlanır.
 */
@Entity(tableName = "daily_snapshot")
data class DailySnapshotEntity(
    @PrimaryKey val epochDay: Long,
    val userId: String,
    val asleepMin: Int?,
    val timeInBedMin: Int?,
    val efficiencyPct: Int?,
    val sleepScore: Int?,
    val remPct: Int?,
    val deepPct: Int?,
    val awakeMin: Int?,
    val bedTime: Instant?,
    val wakeTime: Instant?,
    val spo2Avg: Double?,
    val minutesBelow90: Int?,
    val minutesBelow90IsEstimate: Boolean,
    val snoringMin: Int?,
    val source: String,
    val syncState: SyncState
)

fun DailySnapshot.toEntity(syncState: SyncState = SyncState.PENDING) = DailySnapshotEntity(
    epochDay = date.toEpochDay(),
    userId = userId,
    asleepMin = asleepMin,
    timeInBedMin = timeInBedMin,
    efficiencyPct = efficiencyPct,
    sleepScore = sleepScore,
    remPct = remPct,
    deepPct = deepPct,
    awakeMin = awakeMin,
    bedTime = bedTime,
    wakeTime = wakeTime,
    spo2Avg = spo2Avg,
    minutesBelow90 = minutesBelow90,
    minutesBelow90IsEstimate = minutesBelow90IsEstimate,
    snoringMin = snoringMin,
    source = source.name,
    syncState = syncState
)

fun DailySnapshotEntity.toDomain() = DailySnapshot(
    userId = userId,
    date = LocalDate.ofEpochDay(epochDay),
    asleepMin = asleepMin,
    timeInBedMin = timeInBedMin,
    efficiencyPct = efficiencyPct,
    sleepScore = sleepScore,
    remPct = remPct,
    deepPct = deepPct,
    awakeMin = awakeMin,
    bedTime = bedTime,
    wakeTime = wakeTime,
    spo2Avg = spo2Avg,
    minutesBelow90 = minutesBelow90,
    minutesBelow90IsEstimate = minutesBelow90IsEstimate,
    snoringMin = snoringMin,
    source = SnapshotSource.valueOf(source)
)

@Dao
interface DailySnapshotDao {

    @Upsert
    suspend fun upsert(entity: DailySnapshotEntity)

    @Query("SELECT * FROM daily_snapshot WHERE epochDay = :epochDay LIMIT 1")
    suspend fun getByEpochDay(epochDay: Long): DailySnapshotEntity?

    @Query("SELECT * FROM daily_snapshot ORDER BY epochDay DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<DailySnapshotEntity>>

    @Query("SELECT * FROM daily_snapshot ORDER BY epochDay DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<DailySnapshotEntity>

    @Query("SELECT * FROM daily_snapshot WHERE syncState = 'PENDING'")
    suspend fun getPending(): List<DailySnapshotEntity>

    @Query("UPDATE daily_snapshot SET syncState = 'SYNCED' WHERE epochDay = :epochDay")
    suspend fun markSynced(epochDay: Long)
}
