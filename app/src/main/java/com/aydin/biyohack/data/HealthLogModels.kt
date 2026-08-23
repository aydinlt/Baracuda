package com.aydin.biyohack.data

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Öğün / kahve / su / takviye logu — yerel-öncelikli (offline-first) kayıt.
 *
 * NOT: `com.aydin.biyohack.twin.IntakeType` (Hafta 4) ile kasıtlı olarak
 * BAĞIMSIZ tutulur — data katmanı twin katmanına bağımlı olmamalı (katman
 * yönü tek taraflı: twin → data). İkisi arasındaki köprü (IntakeKind →
 * twin.IntakeEntry dönüşümü) Hafta 2'de ViewModel/use-case katmanında kurulur.
 */
enum class IntakeKind { MEAL, COFFEE, WATER, SUPPLEMENT }

data class IntakeRecord(
    val id: String = UUID.randomUUID().toString(),
    val userId: String,
    val ts: Instant,
    val kind: IntakeKind,
    val label: String,
    val amount: Double? = null,
    val unit: String? = null
)

/** Šeškinės poliklinika laboratuvar seyrindeki tek bir ölçüm satırı. */
data class LabResult(
    val id: String = UUID.randomUUID().toString(),
    val userId: String,
    val panel: String,       // "BÖBREK", "HEMATOLOJİ", "LİPİD", "METABOLİK", ...
    val marker: String,      // "eGFR", "Kreatinin", "Hematokrit", ...
    val value: Double,
    val unit: String? = null,
    val refLow: Double? = null,
    val refHigh: Double? = null,
    val takenAt: LocalDate,
    val sourceLab: String = "Šeškinės poliklinika",
    val notes: String? = null
)

/** TwinGuardrails'in ürettiği kırmızı bölge bayrağının kalıcı kaydı. */
data class ClinicalFlagRecord(
    val id: String = UUID.randomUUID().toString(),
    val userId: String,
    val finding: String,
    val status: String,
    val action: String = "none",
    val raisedAt: Instant = Instant.now(),
    val resolved: Boolean = false
)
