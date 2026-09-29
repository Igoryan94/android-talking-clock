package com.dragonfly.talkingclock.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ScheduleRuleSplitTest {

    private fun rule(start: Int, end: Int) =
        ScheduleRule(id = 100, startMinuteOfDay = start, endMinuteOfDay = end, intervalMinutes = 7, silent = true)

    @Test
    fun `two hour rule splits into two one hour rules`() {
        val parts = rule(10 * 60, 12 * 60).split(1L to 2L)
        assertNotNull(parts)
        val (first, second) = parts!!
        assertEquals(600, first.startMinuteOfDay)
        assertEquals(660, first.endMinuteOfDay)
        assertEquals(660, second.startMinuteOfDay)
        assertEquals(720, second.endMinuteOfDay)
        assertEquals(7, first.intervalMinutes)
        assertEquals(true, first.silent)
        assertEquals(1L, first.id)
        assertEquals(2L, second.id)
    }

    @Test
    fun `crossing midnight rule splits at midnight`() {
        val parts = rule(22 * 60, 2 * 60).split(1L to 2L)
        assertNotNull(parts)
        val (first, second) = parts!!
        assertEquals(1320, first.startMinuteOfDay)
        assertEquals(0, first.endMinuteOfDay)
        assertEquals(0, second.startMinuteOfDay)
        assertEquals(120, second.endMinuteOfDay)
    }

    @Test
    fun `whole day rule splits at noon`() {
        val parts = rule(0, 0).split(1L to 2L)
        assertNotNull(parts)
        val (first, second) = parts!!
        assertEquals(0, first.startMinuteOfDay)
        assertEquals(720, first.endMinuteOfDay)
        assertEquals(720, second.startMinuteOfDay)
        assertEquals(0, second.endMinuteOfDay)
    }

    @Test
    fun `odd duration halves differ by one minute`() {
        val parts = rule(0, 5).split(1L to 2L)
        assertNotNull(parts)
        val (first, second) = parts!!
        assertEquals(2, first.endMinuteOfDay - first.startMinuteOfDay)
        assertEquals(3, second.endMinuteOfDay - second.startMinuteOfDay)
    }

    @Test
    fun `zero or one minute duration cannot split`() {
        assertNull(rule(600, 600).split(1L to 2L))
        assertNull(rule(600, 601).split(1L to 2L))
    }
}
