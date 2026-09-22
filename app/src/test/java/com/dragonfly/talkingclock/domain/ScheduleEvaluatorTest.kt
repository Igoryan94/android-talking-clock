package com.dragonfly.talkingclock.domain

import com.dragonfly.talkingclock.data.FallbackConfig
import com.dragonfly.talkingclock.data.ScheduleRule
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleEvaluatorTest {

    private val day = 20_000L
    private fun abs(minuteOfDay: Int): Long = day * 1440 + minuteOfDay
    private val utc = ZoneId.of("UTC")

    private fun next(minuteOfDay: Int, rules: List<ScheduleRule>, fallback: FallbackConfig) =
        ScheduleEvaluator.nextSpeakTrigger(abs(minuteOfDay), rules, fallback, utc)

    private val rSilentNight = ScheduleRule(id = 1, startMinuteOfDay = 30, endMinuteOfDay = 8 * 60, intervalMinutes = 5, silent = true)
    private val rMorning = ScheduleRule(id = 2, startMinuteOfDay = 8 * 60, endMinuteOfDay = 9 * 60 + 10, intervalMinutes = 10)
    private val rDense = ScheduleRule(id = 3, startMinuteOfDay = 9 * 60 + 20, endMinuteOfDay = 9 * 60 + 36, intervalMinutes = 1)
    private val rThree = ScheduleRule(id = 4, startMinuteOfDay = 9 * 60 + 36, endMinuteOfDay = 9 * 60 + 57, intervalMinutes = 3)
    private val rEvening = ScheduleRule(id = 5, startMinuteOfDay = 22 * 60 + 41, endMinuteOfDay = 22 * 60 + 58, intervalMinutes = 1)

    private val userRules = listOf(rSilentNight, rMorning, rDense, rThree, rEvening)
    private val fallback = FallbackConfig(intervalMinutes = 5)

    @Test
    fun `silent night is skipped, morning rule wins`() {
        val next = next(3 * 60, userRules, fallback)!!
        assertEquals(abs(8 * 60), next.atEpochMinute)
        assertEquals(2L, next.ruleId)
    }

    @Test
    fun `inside rule aligned to start`() {
        val next = next(8 * 60 + 5, userRules, fallback)!!
        assertEquals(abs(8 * 60 + 10), next.atEpochMinute)
    }

    @Test
    fun `gap between rules falls back`() {
        val next = next(9 * 60 + 15, userRules, fallback)!!
        assertEquals(abs(9 * 60 + 20), next.atEpochMinute)
        assertEquals(3L, next.ruleId)
    }

    @Test
    fun `boundary tick belongs to the next rule`() {
        val next = next(9 * 60 + 35, userRules, fallback)!!
        assertEquals(abs(9 * 60 + 36), next.atEpochMinute)
        assertEquals(4L, next.ruleId)
    }

    @Test
    fun `uncovered time uses fallback aligned to gap start`() {
        val next = next(9 * 60 + 58, userRules, fallback)!!
        assertEquals(abs(10 * 60 + 2), next.atEpochMinute)
        assertEquals(true, next.isFallback)
    }

    @Test
    fun `evening rule ends at exclusive bound, fallback continues`() {
        val next = next(22 * 60 + 57, userRules, fallback)!!
        assertEquals(abs(22 * 60 + 58), next.atEpochMinute)
        assertEquals(true, next.isFallback)
    }

    @Test
    fun `overnight rule ticks across midnight`() {
        val rule = ScheduleRule(id = 10, startMinuteOfDay = 22 * 60, endMinuteOfDay = 1 * 60, intervalMinutes = 30)
        val silentFallback = FallbackConfig(silent = true)

        val beforeMidnight = next(23 * 60 + 50, listOf(rule), silentFallback)!!
        assertEquals(abs(24 * 60), beforeMidnight.atEpochMinute)

        val afterMidnight = next(24 * 60 + 15, listOf(rule), silentFallback)!!
        assertEquals(abs(24 * 60 + 30), afterMidnight.atEpochMinute)
    }

    @Test
    fun `silent rule suppresses fallback coverage but not other rules`() {
        val silent = ScheduleRule(id = 1, startMinuteOfDay = 10 * 60, endMinuteOfDay = 11 * 60, silent = true)
        val nested = ScheduleRule(id = 2, startMinuteOfDay = 10 * 60 + 30, endMinuteOfDay = 10 * 60 + 40, intervalMinutes = 5)

        val next = next(10 * 60, listOf(silent, nested), FallbackConfig(intervalMinutes = 5))!!
        assertEquals(abs(10 * 60 + 30), next.atEpochMinute)
        assertEquals(2L, next.ruleId)
    }

    @Test
    fun `everything silent returns null`() {
        val silent = ScheduleRule(id = 1, startMinuteOfDay = 0, endMinuteOfDay = 12 * 60, silent = true)
        assertNull(
            ScheduleEvaluator.nextSpeakTrigger(
                abs(13 * 60),
                listOf(silent),
                FallbackConfig(silent = true),
                utc,
            )
        )
    }

    @Test
    fun `no rules fallback every five minutes from midnight`() {
        val next = next(2 * 60 + 1, emptyList(), fallback)!!
        assertEquals(abs(2 * 60 + 5), next.atEpochMinute)
        assertEquals(true, next.isFallback)
    }

    @Test
    fun `disabled rule ignored`() {
        val disabled = rMorning.copy(enabled = false)
        val next = next(8 * 60 + 5, listOf(disabled), fallback)!!
        assertEquals(abs(8 * 60 + 5 + 5), next.atEpochMinute) // fallback tick from midnight: 08:05 itself passed, next 08:10
        assertEquals(true, next.isFallback)
    }

    // Regression: on-device (Europe/Moscow, UTC+3) the silent night rule never matched
    // because day boundaries were computed on the UTC grid. Rules are LOCAL wall-clock times.
    // JSON below mirrors the exact ruleset stored on the device.
    private val moscow = ZoneId.of("Europe/Moscow")
    private val deviceSilentNight = ScheduleRule(id = 1790029325198, startMinuteOfDay = 30, endMinuteOfDay = 560, intervalMinutes = 10, silent = true)
    private val deviceMorning = ScheduleRule(id = 1790028764613, startMinuteOfDay = 550, endMinuteOfDay = 560, intervalMinutes = 4)

    @Test
    fun `night in moscow is silent, next speak is morning rule tick`() {
        val now = ZonedDateTime.of(2026, 9, 22, 1, 19, 0, 0, moscow)
        val next = ScheduleEvaluator.nextSpeakTrigger(
            now.toInstant().epochSecond / 60,
            listOf(deviceMorning, deviceSilentNight),
            FallbackConfig(intervalMinutes = 1),
            moscow,
        )!!

        val expected = ZonedDateTime.of(2026, 9, 22, 9, 10, 0, 0, moscow)
        assertEquals(expected.toInstant().epochSecond / 60, next.atEpochMinute)
        assertEquals(1790028764613L, next.ruleId)
        assertTrue(!next.isFallback)
    }

    @Test
    fun `moscow early morning belongs to local day`() {
        // Local 01:00 in Moscow is 22:00 UTC of the previous day; a rule covering
        // 00:00-03:00 local must hold. On the old UTC grid this minute was uncovered
        // (UTC minute-of-day 1320) and the fallback kept ticking.
        val silent = ScheduleRule(id = 7, startMinuteOfDay = 0, endMinuteOfDay = 3 * 60, silent = true)
        val now = ZonedDateTime.of(2026, 9, 22, 1, 0, 0, 0, moscow)
        val next = ScheduleEvaluator.nextSpeakTrigger(
            now.toInstant().epochSecond / 60,
            listOf(silent),
            FallbackConfig(intervalMinutes = 5),
            moscow,
        )!!
        val expected = ZonedDateTime.of(2026, 9, 22, 3, 0, 0, 0, moscow)
        assertEquals(expected.toInstant().epochSecond / 60, next.atEpochMinute)
        assertTrue(next.isFallback)
    }
}
