package com.dragonfly.talkingclock.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression: rules stored on-device were not seen by the scheduler.
 * The JSON below is byte-for-byte the value read from the device datastore.
 */
class RulesJsonTest {

    private val deviceJson =
        "[{\"id\":1790028764613,\"enabled\":true,\"start\":550,\"end\":560,\"interval\":4,\"silent\":false}," +
            "{\"id\":1790029325198,\"enabled\":true,\"start\":30,\"end\":560,\"interval\":10,\"silent\":true}]"

    @Test
    fun `device json parses into two rules`() {
        val rules = parseRules(deviceJson)
        assertEquals(2, rules.size)

        assertEquals(1790028764613L, rules[0].id)
        assertEquals(550, rules[0].startMinuteOfDay)
        assertEquals(560, rules[0].endMinuteOfDay)
        assertEquals(4, rules[0].intervalMinutes)
        assertEquals(false, rules[0].silent)

        assertEquals(30, rules[1].startMinuteOfDay)
        assertEquals(true, rules[1].silent)
        // Legacy rules stored without the oneShot field keep repeating forever.
        assertEquals(false, rules[0].oneShot)
        assertEquals(false, rules[1].oneShot)
    }

    @Test
    fun `serialize-then-parse round trip preserves rules`() {
        val original = listOf(
            ScheduleRule(id = 1, enabled = true, startMinuteOfDay = 550, endMinuteOfDay = 560, intervalMinutes = 4, silent = false, oneShot = true),
            ScheduleRule(id = 2, enabled = false, startMinuteOfDay = 30, endMinuteOfDay = 560, intervalMinutes = 10, silent = true, oneShot = false),
        )
        assertEquals(original, parseRules(serializeRules(original)))
    }

    @Test
    fun `malformed json yields empty rules`() {
        assertEquals(emptyList<ScheduleRule>(), parseRules("not json"))
    }

    @Test
    fun `one-shot anchor round trips and legacy parses as zero`() {
        val original = listOf(
            ScheduleRule(id = 3, startMinuteOfDay = 100, endMinuteOfDay = 200, oneShot = true, oneShotAnchorMinute = 12345L),
            ScheduleRule(id = 4, startMinuteOfDay = 300, endMinuteOfDay = 400, oneShot = false, oneShotAnchorMinute = 0L),
        )
        assertEquals(original, parseRules(serializeRules(original)))

        val legacy = parseRules(
            "[{\"id\":5,\"enabled\":true,\"start\":100,\"end\":200,\"interval\":5,\"silent\":false,\"oneShot\":true}]"
        )
        assertEquals(0L, legacy[0].oneShotAnchorMinute)
    }
}
