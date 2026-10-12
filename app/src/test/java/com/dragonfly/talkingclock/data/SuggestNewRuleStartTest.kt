package com.dragonfly.talkingclock.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SuggestNewRuleStartTest {

    private fun rule(start: Int, end: Int, enabled: Boolean = true, id: Long = start.toLong()) =
        ScheduleRule(id = id, enabled = enabled, startMinuteOfDay = start, endMinuteOfDay = end)

    @Test
    fun `chain of enabled rules suggests end of chain`() {
        val rules = listOf(
            rule(0, 600),
            rule(600, 840),
            rule(840, 1020),
        )
        assertEquals(1020, suggestNewRuleStart(rules, nowMinute = 500))
        assertEquals(1020 to 1050, suggestNewRuleRange(rules, nowMinute = 500))
    }

    @Test
    fun `all disabled falls back to now`() {
        val rules = listOf(rule(0, 600, enabled = false), rule(600, 840, enabled = false))
        assertEquals(500, suggestNewRuleStart(rules, nowMinute = 500))
        assertEquals(500 to 530, suggestNewRuleRange(rules, nowMinute = 500))
    }

    @Test
    fun `empty list falls back to now`() {
        assertEquals(500, suggestNewRuleStart(emptyList(), nowMinute = 500))
    }

    @Test
    fun `disabled long rule does not shift suggestion`() {
        val rules = listOf(
            rule(0, 600),
            rule(600, 1200, enabled = false),
        )
        assertEquals(600, suggestNewRuleStart(rules, nowMinute = 100))
    }

    @Test
    fun `whole-day enabled rule falls back to now`() {
        val rules = listOf(rule(0, 0))
        assertEquals(500, suggestNewRuleStart(rules, nowMinute = 500))
    }

    @Test
    fun `end wraps past midnight`() {
        val rules = listOf(rule(0, 1420))
        assertEquals(1420 to 10, suggestNewRuleRange(rules, nowMinute = 100))
    }
}
