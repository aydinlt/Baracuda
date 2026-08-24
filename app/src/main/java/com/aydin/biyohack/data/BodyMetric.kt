package com.aydin.biyohack.data

import java.time.LocalDate

/**
 * Kilo/bel çevresi seyri — system_twin.md Bölüm A "Kilo seyri: 118 → 84 kg,
 * 22 ayda" statik geçmişin devamı. Günde en fazla bir ölçüm (unique
 * (user_id, date), bkz. supabase/schema.sql) — aynı gün tekrar girilirse
 * üzerine yazılır. DailySnapshot ile aynı desen: doğal anahtar (userId,
 * date), ayrı bir id yok — Supabase tarafındaki uuid PK yalnızca orada yaşar.
 */
data class BodyMetric(
    val userId: String,
    val date: LocalDate,
    val weightKg: Double? = null,
    val waistCm: Double? = null,
    val notes: String? = null
)
