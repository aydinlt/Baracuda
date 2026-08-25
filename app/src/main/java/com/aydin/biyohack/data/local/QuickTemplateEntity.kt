package com.aydin.biyohack.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import com.aydin.biyohack.data.IntakeKind
import com.aydin.biyohack.data.QuickTemplate
import kotlinx.coroutines.flow.Flow
import java.time.Instant

// ════════════════════════════════════════════════════════════
// quick_template — "Hızlı Şablonlar ve Favoriler" (bkz. HealthLogModels.kt)
// ════════════════════════════════════════════════════════════

@Entity(tableName = "quick_template")
data class QuickTemplateEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val kind: String,
    val label: String,
    val amount: Double?,
    val unit: String?,
    val createdAt: Instant,
    val syncState: SyncState
)

fun QuickTemplate.toEntity(syncState: SyncState = SyncState.PENDING) = QuickTemplateEntity(
    id = id, userId = userId, kind = kind.name, label = label,
    amount = amount, unit = unit, createdAt = createdAt, syncState = syncState
)

fun QuickTemplateEntity.toDomain() = QuickTemplate(
    id = id, userId = userId, kind = IntakeKind.valueOf(kind),
    label = label, amount = amount, unit = unit, createdAt = createdAt
)

@Dao
interface QuickTemplateDao {
    @Insert
    suspend fun insert(entity: QuickTemplateEntity)

    /** id sabit kalır (PK) — kind/label/amount/unit/syncState güncellenir. */
    @Update
    suspend fun update(entity: QuickTemplateEntity)

    /** En yeni eklenen şablon en üstte — kullanıcı az sayıda tutması beklenen, elle kürate edilen bir liste. */
    @Query("SELECT * FROM quick_template ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<QuickTemplateEntity>>

    @Query("SELECT * FROM quick_template WHERE syncState = 'PENDING'")
    suspend fun getPending(): List<QuickTemplateEntity>

    @Query("UPDATE quick_template SET syncState = 'SYNCED' WHERE id = :id")
    suspend fun markSynced(id: String)

    @Query("DELETE FROM quick_template WHERE id = :id")
    suspend fun delete(id: String)
}
