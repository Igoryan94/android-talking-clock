package com.dragonfly.talkingclock.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleRuleMergeTest {

    private fun rule(start: Int, end: Int, id: Long = 100) =
        ScheduleRule(id = id, startMinuteOfDay = start, endMinuteOfDay = end, intervalMinutes = 7, silent = false)

    @Test
    fun `merge restores what split produced`() {
        val original = rule(10 * 60, 12 * 60, id = 1)
        val (first, second) = original.split(2L to 3L)!!
        val merged = first.merge(listOf(second), 9L)
        assertEquals(600, merged.startMinuteOfDay)
        assertEquals(720, merged.endMinuteOfDay)
        assertEquals(7, merged.intervalMinutes)
        assertEquals(9L, merged.id)
    }

    @Test
    fun `disjoint ranges are bridged into one contiguous range`() {
        val merged = rule(600, 660, id = 1).merge(listOf(rule(840, 900, id = 2)), 9L)
        assertEquals(600, merged.startMinuteOfDay)
        assertEquals(900, merged.endMinuteOfDay)
    }

    @Test
    fun `daytime rule merged with midnight-crossing rule wraps around`() {
        val merged = rule(600, 660, id = 1).merge(listOf(rule(22 * 60, 2 * 60, id = 2)), 9L)
        assertEquals(600, merged.startMinuteOfDay)
        assertEquals(120, merged.endMinuteOfDay)
        assertTrue(merged.crossesMidnight)
    }

    @Test
    fun `whole day rule absorbs anything`() {
        val merged = rule(0, 0, id = 1).merge(listOf(rule(600, 660, id = 2)), 9L)
        assertTrue(merged.coversWholeDay)
        assertEquals(1440, merged.durationMinutes)

        val reversed = rule(600, 660, id = 1).merge(listOf(rule(0, 0, id = 2)), 9L)
        assertTrue(reversed.coversWholeDay)
    }

    @Test
    fun `properties come from the earliest rule`() {
        val base = rule(500, 560, id = 1).copy(intervalMinutes = 15, silent = true, enabled = false, oneShot = false)
        val merged = base.merge(listOf(rule(600, 660, id = 2).copy(intervalMinutes = 30)), 9L)
        assertEquals(500, merged.startMinuteOfDay)
        assertEquals(660, merged.endMinuteOfDay)
        assertEquals(15, merged.intervalMinutes)
        assertTrue(merged.silent)
        assertFalse(merged.enabled)
        assertFalse(merged.oneShot)
    }

    @Test
    fun `three rules merge span from earliest start to latest end`() {
        val merged = rule(480, 540, id = 1)
            .merge(listOf(rule(540, 600, id = 2), rule(1200, 60, id = 3)), 9L)
        // offsets+durations: 0+60, 60+60, 720+300 (20:00 crosses midnight) → span 1020
        assertEquals(480, merged.startMinuteOfDay)
        assertEquals(60, merged.endMinuteOfDay)
        assertTrue(merged.crossesMidnight)
    }
}
