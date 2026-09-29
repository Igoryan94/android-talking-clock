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
    fun `uncovered time uses fallback on hour grid`() {
        val next = next(9 * 60 + 58, userRules, fallback)!!
        assertEquals(abs(10 * 60), next.atEpochMinute)
        assertEquals(true, next.isFallback)
    }

    @Test
    fun `evening rule ends at exclusive bound, fallback continues on grid`() {
        val next = next(22 * 60 + 57, userRules, fallback)!!
        assertEquals(abs(23 * 60), next.atEpochMinute)
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

    // --- strict wall-clock tick grid (v1.0.1) ---

    @Test
    fun `interval 4 ticks on minute-of-hour multiples`() {
        val rule = ScheduleRule(id = 20, startMinuteOfDay = 9 * 60 + 10, endMinuteOfDay = 9 * 60 + 20, intervalMinutes = 4)
        val silentFallback = FallbackConfig(silent = true)
        assertEquals(abs(9 * 60 + 12), next(9 * 60 + 10, listOf(rule), silentFallback)!!.atEpochMinute)
        assertEquals(abs(9 * 60 + 16), next(9 * 60 + 12, listOf(rule), silentFallback)!!.atEpochMinute)
        // today's range exhausted — the rule fires again tomorrow on the same grid
        assertEquals((day + 1) * 1440 + 9 * 60 + 12, next(9 * 60 + 16, listOf(rule), silentFallback)!!.atEpochMinute)
    }

    @Test
    fun `interval 12 grid repeats every hour`() {
        val rule = ScheduleRule(id = 21, startMinuteOfDay = 5, endMinuteOfDay = 2 * 60, intervalMinutes = 12)
        val silentFallback = FallbackConfig(silent = true)
        assertEquals(abs(12), next(0, listOf(rule), silentFallback)!!.atEpochMinute)
        assertEquals(abs(60), next(48, listOf(rule), silentFallback)!!.atEpochMinute)
        assertEquals(abs(72), next(60, listOf(rule), silentFallback)!!.atEpochMinute)
    }

    @Test
    fun `interval 7 stays aligned to range start`() {
        val rule = ScheduleRule(id = 22, startMinuteOfDay = 9 * 60 + 10, endMinuteOfDay = 9 * 60 + 31, intervalMinutes = 7)
        val silentFallback = FallbackConfig(silent = true)
        assertEquals(abs(9 * 60 + 10), next(9 * 60, listOf(rule), silentFallback)!!.atEpochMinute)
        assertEquals(abs(9 * 60 + 17), next(9 * 60 + 10, listOf(rule), silentFallback)!!.atEpochMinute)
        assertEquals(abs(9 * 60 + 24), next(9 * 60 + 17, listOf(rule), silentFallback)!!.atEpochMinute)
    }

    @Test
    fun `interval 120 ticks from midnight`() {
        val rule = ScheduleRule(id = 23, startMinuteOfDay = 0, endMinuteOfDay = 12 * 60, intervalMinutes = 120)
        val silentFallback = FallbackConfig(silent = true)
        assertEquals(abs(120), next(30, listOf(rule), silentFallback)!!.atEpochMinute)
        assertEquals(abs(600), next(481, listOf(rule), silentFallback)!!.atEpochMinute)
    }

    @Test
    fun `fallback ticks on hour grid inside gap`() {
        val silent = ScheduleRule(id = 24, startMinuteOfDay = 9 * 60 + 12, endMinuteOfDay = 9 * 60 + 31, silent = true)
        val next = next(9 * 60 + 13, listOf(silent), FallbackConfig(intervalMinutes = 5))!!
        assertEquals(abs(9 * 60 + 35), next.atEpochMinute)
        assertEquals(true, next.isFallback)
    }

    // --- activeRuleAt (header display) ---

    @Test
    fun `active rule inside speaking range`() {
        val active = ScheduleEvaluator.activeRuleAt(abs(8 * 60 + 30), userRules, utc)
        assertEquals(2L, active.rule?.id)
        assertEquals(false, active.isFallback)
    }

    @Test
    fun `active rule inside silent range`() {
        val active = ScheduleEvaluator.activeRuleAt(abs(3 * 60), userRules, utc)
        assertEquals(1L, active.rule?.id)
        assertEquals(true, active.rule?.silent)
    }

    @Test
    fun `active rule in gap is fallback`() {
        val active = ScheduleEvaluator.activeRuleAt(abs(12 * 60), userRules, utc)
        assertEquals(null, active.rule)
        assertEquals(true, active.isFallback)
    }

    @Test
    fun `active overnight rule after midnight`() {
        val rule = ScheduleRule(id = 25, startMinuteOfDay = 22 * 60, endMinuteOfDay = 6 * 60, intervalMinutes = 30)
        assertEquals(25L, ScheduleEvaluator.activeRuleAt(abs(23 * 60 + 30), listOf(rule), utc).rule?.id)
        assertEquals(25L, ScheduleEvaluator.activeRuleAt(abs(24 * 60 + 30), listOf(rule), utc).rule?.id)
    }

    @Test
    fun `active rule disabled is ignored`() {
        val rule = ScheduleRule(id = 26, startMinuteOfDay = 8 * 60, endMinuteOfDay = 9 * 60, enabled = false)
        val active = ScheduleEvaluator.activeRuleAt(abs(8 * 60 + 30), listOf(rule), utc)
        assertEquals(null, active.rule)
        assertEquals(true, active.isFallback)
    }

    @Test
    fun `first rule wins when ranges overlap`() {
        val first = ScheduleRule(id = 27, startMinuteOfDay = 8 * 60, endMinuteOfDay = 12 * 60, intervalMinutes = 5)
        val second = ScheduleRule(id = 28, startMinuteOfDay = 9 * 60, endMinuteOfDay = 10 * 60, intervalMinutes = 5)
        assertEquals(27L, ScheduleEvaluator.activeRuleAt(abs(9 * 60 + 30), listOf(first, second), utc).rule?.id)
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

        val expected = ZonedDateTime.of(2026, 9, 22, 9, 12, 0, 0, moscow)
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

    // --- one-shot expiry: rule is spent one minute AFTER its period ends ---

    @Test
    fun `one-shot lives through its period and expires a minute after the end`() {
        val rule = ScheduleRule(id = 30, startMinuteOfDay = 9 * 60, endMinuteOfDay = 9 * 60 + 30, oneShot = true)

        // Inside the period — still alive, cleanup armed for end + 1 min.
        assertEquals(emptySet<Long>(), ScheduleEvaluator.expiredOneShotIds(abs(9 * 60 + 10), listOf(rule), utc))
        assertEquals(abs(9 * 60 + 31), ScheduleEvaluator.nextOneShotExpiry(abs(9 * 60 + 10), listOf(rule), utc))
        // Exactly at the exclusive end — the one-minute grace has not elapsed yet.
        assertEquals(emptySet<Long>(), ScheduleEvaluator.expiredOneShotIds(abs(9 * 60 + 30), listOf(rule), utc))
        // One minute after the end — spent.
        assertEquals(setOf(30L), ScheduleEvaluator.expiredOneShotIds(abs(9 * 60 + 31), listOf(rule), utc))
        // Much later (app was off) — still spent.
        assertEquals(setOf(30L), ScheduleEvaluator.expiredOneShotIds(abs(12 * 60), listOf(rule), utc))
    }

    @Test
    fun `silent one-shot expires the same way`() {
        val rule = ScheduleRule(
            id = 31,
            startMinuteOfDay = 10 * 60,
            endMinuteOfDay = 10 * 60 + 30,
            silent = true,
            oneShot = true,
        )
        assertEquals(emptySet<Long>(), ScheduleEvaluator.expiredOneShotIds(abs(10 * 60 + 29), listOf(rule), utc))
        assertEquals(setOf(31L), ScheduleEvaluator.expiredOneShotIds(abs(10 * 60 + 31), listOf(rule), utc))
    }

    @Test
    fun `crossing-midnight one-shot expires after the night occurrence`() {
        val rule = ScheduleRule(id = 32, startMinuteOfDay = 23 * 60, endMinuteOfDay = 60, oneShot = true)
        assertEquals(emptySet<Long>(), ScheduleEvaluator.expiredOneShotIds(abs(24 * 60 + 30), listOf(rule), utc))
        assertEquals(abs(24 * 60 + 61), ScheduleEvaluator.nextOneShotExpiry(abs(24 * 60 + 30), listOf(rule), utc))
        assertEquals(setOf(32L), ScheduleEvaluator.expiredOneShotIds(abs(24 * 60 + 61), listOf(rule), utc))
    }

    @Test
    fun `upcoming one-shot waits for its period end`() {
        val rule = ScheduleRule(id = 33, startMinuteOfDay = 14 * 60, endMinuteOfDay = 14 * 60 + 30, oneShot = true)
        assertEquals(emptySet<Long>(), ScheduleEvaluator.expiredOneShotIds(abs(10 * 60), listOf(rule), utc))
        assertEquals(abs(14 * 60 + 31), ScheduleEvaluator.nextOneShotExpiry(abs(10 * 60), listOf(rule), utc))
    }

    @Test
    fun `regular and disabled rules never expire`() {
        val regular = ScheduleRule(id = 34, startMinuteOfDay = 9 * 60, endMinuteOfDay = 10 * 60, oneShot = false)
        val disabled = ScheduleRule(id = 35, startMinuteOfDay = 9 * 60, endMinuteOfDay = 10 * 60, oneShot = true, enabled = false)
        assertEquals(emptySet<Long>(), ScheduleEvaluator.expiredOneShotIds(abs(12 * 60), listOf(regular, disabled), utc))
        assertNull(ScheduleEvaluator.nextOneShotExpiry(abs(12 * 60), listOf(regular, disabled), utc))
    }

    @Test
    fun `earliest one-shot expiry wins`() {
        val early = ScheduleRule(id = 36, startMinuteOfDay = 9 * 60, endMinuteOfDay = 9 * 60 + 10, oneShot = true)
        val late = ScheduleRule(id = 37, startMinuteOfDay = 9 * 60, endMinuteOfDay = 10 * 60, oneShot = true)
        assertEquals(abs(9 * 60 + 11), ScheduleEvaluator.nextOneShotExpiry(abs(8 * 60), listOf(late, early), utc))
    }

    // --- one-shot anchor: rule created after its window targets the NEXT occurrence ---

    @Test
    fun `one-shot created after its window waits for the next day`() {
        val rule = ScheduleRule(
            id = 40,
            startMinuteOfDay = 9 * 60,
            endMinuteOfDay = 9 * 60 + 30,
            oneShot = true,
            oneShotAnchorMinute = abs(10 * 60),
        )
        val now = abs(10 * 60 + 5)
        assertEquals(emptySet<Long>(), ScheduleEvaluator.expiredOneShotIds(now, listOf(rule), utc))
        assertEquals((day + 1) * 1440 + 9 * 60 + 31, ScheduleEvaluator.nextOneShotExpiry(now, listOf(rule), utc))
    }

    @Test
    fun `one-shot anchored before its window expires at that window's end`() {
        val rule = ScheduleRule(
            id = 41,
            startMinuteOfDay = 9 * 60,
            endMinuteOfDay = 9 * 60 + 30,
            oneShot = true,
            oneShotAnchorMinute = abs(8 * 60),
        )
        assertEquals(emptySet<Long>(), ScheduleEvaluator.expiredOneShotIds(abs(8 * 60 + 30), listOf(rule), utc))
        assertEquals(abs(9 * 60 + 31), ScheduleEvaluator.nextOneShotExpiry(abs(8 * 60 + 30), listOf(rule), utc))
    }

    @Test
    fun `one-shot anchored inside its window`() {
        val rule = ScheduleRule(
            id = 42,
            startMinuteOfDay = 9 * 60,
            endMinuteOfDay = 9 * 60 + 30,
            oneShot = true,
            oneShotAnchorMinute = abs(9 * 60 + 10),
        )
        assertEquals(emptySet<Long>(), ScheduleEvaluator.expiredOneShotIds(abs(9 * 60 + 20), listOf(rule), utc))
        assertEquals(abs(9 * 60 + 31), ScheduleEvaluator.nextOneShotExpiry(abs(9 * 60 + 20), listOf(rule), utc))
    }

    @Test
    fun `one-shot anchored before a missed window still expires`() {
        val rule = ScheduleRule(
            id = 43,
            startMinuteOfDay = 10 * 60,
            endMinuteOfDay = 10 * 60 + 30,
            oneShot = true,
            oneShotAnchorMinute = abs(10 * 60),
        )
        assertEquals(setOf(43L), ScheduleEvaluator.expiredOneShotIds(abs(11 * 60), listOf(rule), utc))
    }
}
