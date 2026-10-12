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

    @Test
    fun `remaining counts down from the full duration`() {
        val cfg = VolumeGuardConfig(enabled = true, durationMinutes = 180, armedAtEpochMinute = 1_000L)
        assertEquals(180L, cfg.remainingMinutes(1_000L))
        assertEquals(179L, cfg.remainingMinutes(1_001L))
        assertEquals(1L, cfg.remainingMinutes(1_179L))
    }

    @Test
    fun `remaining is zero when expired or inactive`() {
        val cfg = VolumeGuardConfig(enabled = true, durationMinutes = 180, armedAtEpochMinute = 1_000L)
        assertEquals(0L, cfg.remainingMinutes(1_180L))
        assertEquals(0L, cfg.remainingMinutes(2_000L))
        assertEquals(0L, VolumeGuardConfig().remainingMinutes(1_000L))
        assertEquals(0L, VolumeGuardConfig(enabled = true, armedAtEpochMinute = 0L).remainingMinutes(1_000L))
    }
}
