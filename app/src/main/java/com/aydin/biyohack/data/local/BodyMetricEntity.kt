package com.aydin.biyohack.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import com.aydin.biyohack.data.BodyMetric
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/** PK = epochDay: günde en fazla bir ölçüm, DailySnapshotEntity ile aynı desen. */
@Entity(tableName = "body_metric")
data class BodyMetricEntity(
    @PrimaryKey val epochDay: Long,
    val userId: String,
    val weightKg: Double?,
    val waistCm: Double?,
    val notes: String?,
    val syncState: SyncState
)

fun BodyMetric.toEntity(syncState: SyncState = SyncState.PENDING) = BodyMetricEntity(
    epochDay = date.toEpochDay(),
    userId = userId,
    weightKg = weightKg,
    waistCm = waistCm,
    notes = notes,
    syncState = syncState
)

fun BodyMetricEntity.toDomain() = BodyMetric(
    userId = userId,
    date = LocalDate.ofEpochDay(epochDay),
    weightKg = weightKg,
    waistCm = waistCm,
    notes = notes
)

@Dao
interface BodyMetricDao {

    @Upsert
    suspend fun upsert(entity: BodyMetricEntity)

    @Query("SELECT * FROM body_metric ORDER BY epochDay DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<BodyMetricEntity>>

    @Query("SELECT * FROM body_metric WHERE syncState = 'PENDING'")
    suspend fun getPending(): List<BodyMetricEntity>

    @Query("UPDATE body_metric SET syncState = 'SYNCED' WHERE epochDay = :epochDay")
    suspend fun markSynced(epochDay: Long)

    @Query("DELETE FROM body_metric WHERE epochDay = :epochDay")
    suspend fun delete(epochDay: Long)
}
