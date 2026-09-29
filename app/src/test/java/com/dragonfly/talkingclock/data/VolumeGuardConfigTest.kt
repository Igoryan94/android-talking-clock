package com.dragonfly.talkingclock.data

import org.junit.Assert.assertEquals
import org.junit.Test

class VolumeGuardConfigTest {

    private fun activeAt(nowMinute: Long, enabled: Boolean, armedAt: Long, durationMinutes: Int = 180): Boolean =
        VolumeGuardConfig(
            enabled = enabled,
            durationMinutes = durationMinutes,
            armedAtEpochMinute = armedAt,
        ).activeAt(nowMinute)

    @Test
    fun `default disabled is never active`() {
        assertEquals(false, VolumeGuardConfig().activeAt(1_000L))
    }

    @Test
    fun `enabled without an anchor is never active`() {
        assertEquals(false, activeAt(nowMinute = 1_000L, enabled = true, armedAt = 0L))
    }

    @Test
    fun `active right after arming and inside the window`() {
        assertEquals(true, activeAt(nowMinute = 1_000L, enabled = true, armedAt = 1_000L))
        assertEquals(true, activeAt(nowMinute = 1_000L + 179L, enabled = true, armedAt = 1_000L))
    }

    @Test
    fun `expires exactly at the end of the window`() {
        assertEquals(false, activeAt(nowMinute = 1_000L + 180L, enabled = true, armedAt = 1_000L))
    }

    @Test
    fun `expired well after the window`() {
        assertEquals(false, activeAt(nowMinute = 1_000L + 500L, enabled = true, armedAt = 1_000L))
    }

    @Test
    fun `duration is configurable`() {
        assertEquals(true, activeAt(nowMinute = 1_050L, enabled = true, armedAt = 1_000L, durationMinutes = 60))
        assertEquals(false, activeAt(nowMinute = 1_061L, enabled = true, armedAt = 1_000L, durationMinutes = 60))
    }

    @Test
    fun `threshold defaults to thirty percent`() {
        assertEquals(30, VolumeGuardConfig().thresholdPercent)
        assertEquals(180, VolumeGuardConfig().durationMinutes)
    }
}
