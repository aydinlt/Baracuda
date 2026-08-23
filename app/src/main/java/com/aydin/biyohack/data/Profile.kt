package com.aydin.biyohack.data

import java.time.LocalTime

/**
 * Sabit profil — supabase/schema.sql'deki `profiles` tablosu ve
 * system_twin.md Bölüm A ile birebir eşleşir. Varsayılanlar Aydın'ın
 * bilinen profiline göre; her yeni kullanıcı için Supabase Auth ile
 * kayıt olunduğunda [ProfileRepository.ensureLoaded] bu varsayılanlarla
 * bir satır oluşturur.
 */
data class Profile(
    val userId: String,
    val fullName: String = "Aydın Kırmızıoğlu",
    val birthYear: Int? = null,
    val sex: String = "male",
    val heightCm: Double = 180.0,
    val timezone: String = "Europe/Vilnius",
    val waterTargetMl: Int = 4000,
    val proteinTargetMinG: Int = 140,
    val proteinTargetMaxG: Int = 170,
    val wakeTarget: LocalTime = LocalTime.of(7, 0),
    val bedEarliest: LocalTime = LocalTime.of(23, 0)
)
