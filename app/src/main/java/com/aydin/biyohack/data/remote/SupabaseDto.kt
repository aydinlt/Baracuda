package com.aydin.biyohack.data.remote

import com.aydin.biyohack.data.BodyMetric
import com.aydin.biyohack.data.ClinicalFlagRecord
import com.aydin.biyohack.data.DailySnapshot
import com.aydin.biyohack.data.IntakeKind
import com.aydin.biyohack.data.IntakeRecord
import com.aydin.biyohack.data.LabResult
import com.aydin.biyohack.data.Profile
import com.aydin.biyohack.data.QuickTemplate
import com.aydin.biyohack.data.SnapshotSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalTime

/**
 * Supabase (Postgrest) satırlarına karşılık gelen DTO'lar — kolon adları
 * supabase/schema.sql ile snake_case olarak birebir eşleşir. Instant/LocalDate
 * yerine ISO-8601 String taşınır; dönüşüm mapper fonksiyonlarında yapılır
 * (supabase-kt'nin varsayılan Json'ı java.time tiplerini tanımaz).
 */

@Serializable
data class DailySnapshotRow(
    @SerialName("user_id") val userId: String,
    val date: String, // yyyy-MM-dd
    @SerialName("asleep_min") val asleepMin: Int? = null,
    @SerialName("time_in_bed_min") val timeInBedMin: Int? = null,
    @SerialName("efficiency_pct") val efficiencyPct: Int? = null,
    @SerialName("sleep_score") val sleepScore: Int? = null,
    @SerialName("rem_pct") val remPct: Int? = null,
    @SerialName("deep_pct") val deepPct: Int? = null,
    @SerialName("awake_min") val awakeMin: Int? = null,
    @SerialName("bed_time") val bedTime: String? = null,
    @SerialName("wake_time") val wakeTime: String? = null,
    @SerialName("spo2_avg") val spo2Avg: Double? = null,
    @SerialName("minutes_below_90") val minutesBelow90: Int? = null,
    @SerialName("minutes_below_90_is_estimate") val minutesBelow90IsEstimate: Boolean = false,
    @SerialName("snoring_min") val snoringMin: Int? = null,
    val source: String = "HEALTH_CONNECT"
)

fun DailySnapshot.toRow() = DailySnapshotRow(
    userId = userId, date = date.toString(), asleepMin = asleepMin,
    timeInBedMin = timeInBedMin, efficiencyPct = efficiencyPct, sleepScore = sleepScore,
    remPct = remPct, deepPct = deepPct, awakeMin = awakeMin,
    bedTime = bedTime?.toString(), wakeTime = wakeTime?.toString(), spo2Avg = spo2Avg,
    minutesBelow90 = minutesBelow90, minutesBelow90IsEstimate = minutesBelow90IsEstimate,
    snoringMin = snoringMin, source = source.name
)

fun DailySnapshotRow.toDomain() = DailySnapshot(
    userId = userId, date = LocalDate.parse(date), asleepMin = asleepMin,
    timeInBedMin = timeInBedMin, efficiencyPct = efficiencyPct, sleepScore = sleepScore,
    remPct = remPct, deepPct = deepPct, awakeMin = awakeMin,
    bedTime = bedTime?.let { java.time.Instant.parse(it) },
    wakeTime = wakeTime?.let { java.time.Instant.parse(it) },
    spo2Avg = spo2Avg, minutesBelow90 = minutesBelow90,
    minutesBelow90IsEstimate = minutesBelow90IsEstimate, snoringMin = snoringMin,
    source = SnapshotSource.valueOf(source)
)

@Serializable
data class IntakeEntryRow(
    val id: String,
    @SerialName("user_id") val userId: String,
    val ts: String,
    val type: String,
    val label: String,
    val amount: Double? = null,
    val unit: String? = null
)

fun IntakeRecord.toRow() = IntakeEntryRow(
    id = id, userId = userId, ts = ts.toString(), type = kind.name,
    label = label, amount = amount, unit = unit
)

fun IntakeEntryRow.toDomain() = IntakeRecord(
    id = id, userId = userId, ts = java.time.Instant.parse(ts),
    kind = IntakeKind.valueOf(type), label = label, amount = amount, unit = unit
)

@Serializable
data class LabResultRow(
    val id: String,
    @SerialName("user_id") val userId: String,
    val panel: String,
    val marker: String,
    val value: Double,
    val unit: String? = null,
    @SerialName("ref_low") val refLow: Double? = null,
    @SerialName("ref_high") val refHigh: Double? = null,
    @SerialName("taken_at") val takenAt: String,
    @SerialName("source_lab") val sourceLab: String = "Šeškinės poliklinika",
    val notes: String? = null
)

fun LabResult.toRow() = LabResultRow(
    id = id, userId = userId, panel = panel, marker = marker, value = value,
    unit = unit, refLow = refLow, refHigh = refHigh, takenAt = takenAt.toString(),
    sourceLab = sourceLab, notes = notes
)

fun LabResultRow.toDomain() = LabResult(
    id = id, userId = userId, panel = panel, marker = marker, value = value,
    unit = unit, refLow = refLow, refHigh = refHigh, takenAt = LocalDate.parse(takenAt),
    sourceLab = sourceLab, notes = notes
)

@Serializable
data class ClinicalFlagRow(
    val id: String,
    @SerialName("user_id") val userId: String,
    val finding: String,
    val status: String,
    val action: String = "none",
    @SerialName("raised_at") val raisedAt: String,
    val resolved: Boolean = false
)

fun ClinicalFlagRecord.toRow() = ClinicalFlagRow(
    id = id, userId = userId, finding = finding, status = status, action = action,
    raisedAt = raisedAt.toString(), resolved = resolved
)

fun ClinicalFlagRow.toDomain() = ClinicalFlagRecord(
    id = id, userId = userId, finding = finding, status = status, action = action,
    raisedAt = java.time.Instant.parse(raisedAt), resolved = resolved
)

@Serializable
data class ProfileRow(
    val id: String,
    @SerialName("full_name") val fullName: String = "Aydın Kırmızıoğlu",
    @SerialName("birth_year") val birthYear: Int? = null,
    val sex: String = "male",
    @SerialName("height_cm") val heightCm: Double = 180.0,
    val timezone: String = "Europe/Vilnius",
    @SerialName("water_target_ml") val waterTargetMl: Int = 4000,
    @SerialName("protein_target_min_g") val proteinTargetMinG: Int = 140,
    @SerialName("protein_target_max_g") val proteinTargetMaxG: Int = 170,
    @SerialName("wake_target") val wakeTarget: String = "07:00:00",
    @SerialName("bed_earliest") val bedEarliest: String = "23:00:00"
)

fun Profile.toRow() = ProfileRow(
    id = userId, fullName = fullName, birthYear = birthYear, sex = sex,
    heightCm = heightCm, timezone = timezone, waterTargetMl = waterTargetMl,
    proteinTargetMinG = proteinTargetMinG, proteinTargetMaxG = proteinTargetMaxG,
    wakeTarget = wakeTarget.toString(), bedEarliest = bedEarliest.toString()
)

fun ProfileRow.toDomain() = Profile(
    userId = id, fullName = fullName, birthYear = birthYear, sex = sex,
    heightCm = heightCm, timezone = timezone, waterTargetMl = waterTargetMl,
    proteinTargetMinG = proteinTargetMinG, proteinTargetMaxG = proteinTargetMaxG,
    wakeTarget = LocalTime.parse(wakeTarget), bedEarliest = LocalTime.parse(bedEarliest)
)

@Serializable
data class BodyMetricRow(
    @SerialName("user_id") val userId: String,
    val date: String,
    @SerialName("weight_kg") val weightKg: Double? = null,
    @SerialName("waist_cm") val waistCm: Double? = null,
    val notes: String? = null
)

fun BodyMetric.toRow() = BodyMetricRow(
    userId = userId, date = date.toString(), weightKg = weightKg, waistCm = waistCm, notes = notes
)

fun BodyMetricRow.toDomain() = BodyMetric(
    userId = userId, date = LocalDate.parse(date), weightKg = weightKg, waistCm = waistCm, notes = notes
)

@Serializable
data class QuickTemplateRow(
    val id: String,
    @SerialName("user_id") val userId: String,
    val kind: String,
    val label: String,
    val amount: Double? = null,
    val unit: String? = null,
    @SerialName("created_at") val createdAt: String
)

fun QuickTemplate.toRow() = QuickTemplateRow(
    id = id, userId = userId, kind = kind.name, label = label,
    amount = amount, unit = unit, createdAt = createdAt.toString()
)

fun QuickTemplateRow.toDomain() = QuickTemplate(
    id = id, userId = userId, kind = IntakeKind.valueOf(kind), label = label,
    amount = amount, unit = unit, createdAt = java.time.Instant.parse(createdAt)
)
