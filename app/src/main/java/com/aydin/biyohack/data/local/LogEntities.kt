package com.aydin.biyohack.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import com.aydin.biyohack.data.ClinicalFlagRecord
import com.aydin.biyohack.data.IntakeKind
import com.aydin.biyohack.data.IntakeRecord
import com.aydin.biyohack.data.LabResult
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate

// ════════════════════════════════════════════════════════════
// intake_entry
// ════════════════════════════════════════════════════════════

@Entity(tableName = "intake_entry")
data class IntakeRecordEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val ts: Instant,
    val kind: String,
    val label: String,
    val amount: Double?,
    val unit: String?,
    val syncState: SyncState
)

fun IntakeRecord.toEntity(syncState: SyncState = SyncState.PENDING) = IntakeRecordEntity(
    id = id, userId = userId, ts = ts, kind = kind.name, label = label,
    amount = amount, unit = unit, syncState = syncState
)

fun IntakeRecordEntity.toDomain() = IntakeRecord(
    id = id, userId = userId, ts = ts, kind = IntakeKind.valueOf(kind),
    label = label, amount = amount, unit = unit
)

@Dao
interface IntakeRecordDao {
    @Insert
    suspend fun insert(entity: IntakeRecordEntity)

    @Query("SELECT * FROM intake_entry WHERE ts BETWEEN :startMillis AND :endMillis ORDER BY ts")
    fun observeBetween(startMillis: Long, endMillis: Long): Flow<List<IntakeRecordEntity>>

    @Query("SELECT * FROM intake_entry WHERE syncState = 'PENDING'")
    suspend fun getPending(): List<IntakeRecordEntity>

    @Query("UPDATE intake_entry SET syncState = 'SYNCED' WHERE id = :id")
    suspend fun markSynced(id: String)

    /** Kreatinsiz gün sayacı için — TwinStateBuilder bunu kullanır (bkz. system_twin.md Bölüm E). */
    @Query("SELECT * FROM intake_entry WHERE label LIKE '%kreatin%' COLLATE NOCASE ORDER BY ts DESC LIMIT 1")
    suspend fun getLastCreatineLog(): IntakeRecordEntity?

    @Query("DELETE FROM intake_entry WHERE id = :id")
    suspend fun delete(id: String)
}

// ════════════════════════════════════════════════════════════
// lab_result
// ════════════════════════════════════════════════════════════

@Entity(tableName = "lab_result")
data class LabResultEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val panel: String,
    val marker: String,
    val value: Double,
    val unit: String?,
    val refLow: Double?,
    val refHigh: Double?,
    val takenAtEpochDay: Long,
    val sourceLab: String,
    val notes: String?,
    val syncState: SyncState
)

fun LabResult.toEntity(syncState: SyncState = SyncState.PENDING) = LabResultEntity(
    id = id, userId = userId, panel = panel, marker = marker, value = value,
    unit = unit, refLow = refLow, refHigh = refHigh,
    takenAtEpochDay = takenAt.toEpochDay(), sourceLab = sourceLab, notes = notes,
    syncState = syncState
)

fun LabResultEntity.toDomain() = LabResult(
    id = id, userId = userId, panel = panel, marker = marker, value = value,
    unit = unit, refLow = refLow, refHigh = refHigh,
    takenAt = LocalDate.ofEpochDay(takenAtEpochDay), sourceLab = sourceLab, notes = notes
)

@Dao
interface LabResultDao {
    @Upsert
    suspend fun upsert(entity: LabResultEntity)

    @Query("SELECT * FROM lab_result ORDER BY takenAtEpochDay DESC")
    fun observeAll(): Flow<List<LabResultEntity>>

    @Query("SELECT * FROM lab_result WHERE syncState = 'PENDING'")
    suspend fun getPending(): List<LabResultEntity>

    @Query("UPDATE lab_result SET syncState = 'SYNCED' WHERE id = :id")
    suspend fun markSynced(id: String)

    @Query("DELETE FROM lab_result WHERE id = :id")
    suspend fun delete(id: String)
}

// ════════════════════════════════════════════════════════════
// clinical_flag
// ════════════════════════════════════════════════════════════

@Entity(tableName = "clinical_flag")
data class ClinicalFlagEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val finding: String,
    val status: String,
    val action: String,
    val raisedAt: Instant,
    val resolved: Boolean,
    val syncState: SyncState
)

fun ClinicalFlagRecord.toEntity(syncState: SyncState = SyncState.PENDING) = ClinicalFlagEntity(
    id = id, userId = userId, finding = finding, status = status, action = action,
    raisedAt = raisedAt, resolved = resolved, syncState = syncState
)

fun ClinicalFlagEntity.toDomain() = ClinicalFlagRecord(
    id = id, userId = userId, finding = finding, status = status, action = action,
    raisedAt = raisedAt, resolved = resolved
)

@Dao
interface ClinicalFlagDao {
    @Insert
    suspend fun insert(entity: ClinicalFlagEntity)

    @Query("SELECT * FROM clinical_flag WHERE resolved = 0 ORDER BY raisedAt DESC")
    fun observeUnresolved(): Flow<List<ClinicalFlagEntity>>

    @Query("SELECT * FROM clinical_flag WHERE syncState = 'PENDING'")
    suspend fun getPending(): List<ClinicalFlagEntity>

    @Query("UPDATE clinical_flag SET syncState = 'SYNCED' WHERE id = :id")
    suspend fun markSynced(id: String)

    /** resolved=1 yapar VE syncState='PENDING'e çeker — çözüm de Supabase'e itilmeli. */
    @Query("UPDATE clinical_flag SET resolved = 1, syncState = 'PENDING' WHERE id = :id")
    suspend fun markResolved(id: String)
}
