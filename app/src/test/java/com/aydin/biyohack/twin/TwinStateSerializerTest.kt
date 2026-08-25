package com.aydin.biyohack.twin

import com.aydin.biyohack.data.DailySnapshot
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * toPromptBlock()'un su/kalkış hedefi override'ları — TwinGuardrails.buildFacts()
 * bu iki değeri Hafta 21'den beri kabul ediyordu ama TwinEngine.generate() bunları
 * yalnızca buildFacts()'a geçiriyordu, toPromptBlock()'a hiç ulaşmıyordu (bkz.
 * TwinState.kt commit notu). "KURAL MOTORU" bölümü doğru hedefe göre değerlendirme
 * yaparken, hemen üstündeki "BUGÜNKÜ LOGLAR" bölümü sessizce sabit 4000 ml/07:00
 * yazıyordu — aynı prompt içinde iki farklı hedef görünürdü.
 */
class TwinStateSerializerTest {

    private val zone = ZoneId.systemDefault()
    private val today: LocalDate = LocalDate.of(2026, 6, 15)

    private fun at(hour: Int, minute: Int = 0): Instant =
        today.atTime(hour, minute).atZone(zone).toInstant()

    @Test
    fun `varsayilan cagrida eski sabit degerler korunur`() {
        val state = TwinState(
            now = at(15, 0),
            trigger = Trigger.MANUAL,
            lastNight = DailySnapshot(userId = "u1", date = today.minusDays(1), wakeTime = at(8, 0)),
            previousNights = emptyList(),
            todayIntake = listOf(IntakeEntry(at(9, 0), IntakeType.WATER, "Su", amount = 500.0, unit = "ml"))
        )
        val block = TwinStateSerializer.toPromptBlock(state, ruleFacts = emptyList())
        assertTrue(block.contains("500 ml / 4000 ml hedef"))
        assertTrue(block.contains("hedef 07:00"))
    }

    @Test
    fun `ozel su hedefi bugunku loglar bolumune yansir`() {
        val state = TwinState(
            now = at(15, 0),
            trigger = Trigger.MANUAL,
            lastNight = null,
            previousNights = emptyList(),
            todayIntake = listOf(IntakeEntry(at(9, 0), IntakeType.WATER, "Su", amount = 500.0, unit = "ml"))
        )
        val block = TwinStateSerializer.toPromptBlock(state, ruleFacts = emptyList(), waterTargetMl = 5000)
        assertTrue(block.contains("500 ml / 5000 ml hedef"))
        assertTrue(!block.contains("4000 ml hedef"))
    }

    @Test
    fun `ozel kalkis hedefi dun gece bolumune yansir`() {
        val state = TwinState(
            now = at(15, 0),
            trigger = Trigger.MANUAL,
            lastNight = DailySnapshot(userId = "u1", date = today.minusDays(1), wakeTime = at(6, 0)),
            previousNights = emptyList(),
            todayIntake = emptyList()
        )
        val block = TwinStateSerializer.toPromptBlock(state, ruleFacts = emptyList(), wakeTargetHour = 6)
        assertTrue(block.contains("hedef 06:00"))
        assertTrue(!block.contains("hedef 07:00"))
    }
}
