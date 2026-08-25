package com.aydin.biyohack.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import com.aydin.biyohack.data.LabResultTemplate
import kotlinx.coroutines.flow.Flow
import java.time.Instant

// ════════════════════════════════════════════════════════════
// lab_result_template — "sık tekrarlanan panel" şablonu (bkz. HealthLogModels.kt)
// ════════════════════════════════════════════════════════════

@Entity(tableName = "lab_result_template")
data class LabResultTemplateEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val panel: String,
    val marker: String,
    val unit: String?,
    val refLow: Double?,
    val refHigh: Double?,
    val createdAt: Instant,
    val syncState: SyncState
)

fun LabResultTemplate.toEntity(syncState: SyncState = SyncState.PENDING) = LabResultTemplateEntity(
    id = id, userId = userId, panel = panel, marker = marker, unit = unit,
    refLow = refLow, refHigh = refHigh, createdAt = createdAt, syncState = syncState
)

fun LabResultTemplateEntity.toDomain() = LabResultTemplate(
    id = id, userId = userId, panel = panel, marker = marker, unit = unit,
    refLow = refLow, refHigh = refHigh, createdAt = createdAt
)

@Dao
interface LabResultTemplateDao {
    @Insert
    suspend fun insert(entity: LabResultTemplateEntity)

    /** id sabit kalır (PK) — panel/marker/unit/refLow/refHigh/syncState güncellenir. */
    @Update
    suspend fun update(entity: LabResultTemplateEntity)

    @Query("SELECT * FROM lab_result_template ORDER BY panel, marker")
    fun observeAll(): Flow<List<LabResultTemplateEntity>>

    @Query("SELECT * FROM lab_result_template WHERE syncState = 'PENDING'")
    suspend fun getPending(): List<LabResultTemplateEntity>

    @Query("UPDATE lab_result_template SET syncState = 'SYNCED' WHERE id = :id")
    suspend fun markSynced(id: String)

    @Query("DELETE FROM lab_result_template WHERE id = :id")
    suspend fun delete(id: String)
}
