package com.aydin.biyohack.data.local

import androidx.room.TypeConverter
import java.time.Instant
import java.time.LocalDate

/** Bir satırın Supabase'e itilip itilmediğini takip eder — offline-first kuyruk durumu. */
enum class SyncState { PENDING, SYNCED }

/** Room'un doğal olarak bilmediği tipler için dönüştürücüler. */
class Converters {

    @TypeConverter
    fun instantToEpochMillis(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter
    fun epochMillisToInstant(value: Long?): Instant? = value?.let { Instant.ofEpochMilli(it) }

    @TypeConverter
    fun localDateToEpochDay(value: LocalDate?): Long? = value?.toEpochDay()

    @TypeConverter
    fun epochDayToLocalDate(value: Long?): LocalDate? = value?.let { LocalDate.ofEpochDay(it) }

    @TypeConverter
    fun syncStateToString(value: SyncState): String = value.name

    @TypeConverter
    fun stringToSyncState(value: String): SyncState = SyncState.valueOf(value)
}
