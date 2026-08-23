package com.aydin.biyohack.data

import java.time.Instant
import java.time.LocalDate

/**
 * Gecelik uyku/SpO2 özeti — tek kaynak-of-truth domain modeli.
 *
 * ÖNEMLİ: Bu sınıfın alan adları Hafta 4'te yazılan
 * `com.aydin.biyohack.twin.TwinStateSerializer` ile birebir uyumlu olacak
 * şekilde sabitlenmiştir (asleepMin, efficiencyPct, spo2Avg,
 * minutesBelow90IsEstimate, ...). Bu dosyayı değiştirirken Hafta 4
 * kodunu da güncellemeden alan adı/tipini kırma.
 */
data class DailySnapshot(
    val userId: String,
    val date: LocalDate,
    val asleepMin: Int? = null,
    val timeInBedMin: Int? = null,
    val efficiencyPct: Int? = null,
    val sleepScore: Int? = null,
    val remPct: Int? = null,
    val deepPct: Int? = null,
    val awakeMin: Int? = null,
    val bedTime: Instant? = null,
    val wakeTime: Instant? = null,
    val spo2Avg: Double? = null,
    val minutesBelow90: Int? = null,
    // Health Connect sürekli SpO2 akışı vermez, örneklem noktaları verir —
    // bu yüzden "90 altı X dakika" her zaman bir tahmindir, kesin ölçüm değil.
    val minutesBelow90IsEstimate: Boolean = false,
    val snoringMin: Int? = null,
    val source: SnapshotSource = SnapshotSource.HEALTH_CONNECT
)

enum class SnapshotSource { HEALTH_CONNECT, SAMSUNG_HEALTH, MANUAL }
