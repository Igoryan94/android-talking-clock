package com.dragonfly.talkingclock.data

enum class AudioStream {
    MEDIA,
    NOTIFICATION,
}

data class TtsSettings(
    val enginePackage: String? = null,
    val audioStream: AudioStream = AudioStream.MEDIA,
    val speechRate: Float = 1f,
    val prefix: String = "",
    val postfix: String = "",
    val timeTemplate: String = "H:mm",
    val regexPattern: String = "",
    val regexReplacement: String = "",
) {
    val regexEnabled: Boolean get() = regexPattern.isNotBlank()
}

data class ScheduleRule(
    val id: Long,
    val enabled: Boolean = true,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
    val intervalMinutes: Int = 5,
    val silent: Boolean = false,
    val oneShot: Boolean = true,
    /**
     * Epoch minute when the one-shot countdown started (creation/duplication). The rule is
     * dropped one minute after the end of the first occurrence that starts after this anchor.
     * `0` = unknown (legacy) — falls back to the current-day occurrence.
     */
    val oneShotAnchorMinute: Long = 0L,
) {
    val crossesMidnight: Boolean get() = endMinuteOfDay < startMinuteOfDay
    val coversWholeDay: Boolean get() = startMinuteOfDay == 0 && endMinuteOfDay == 0

    val durationMinutes: Int
        get() = if (coversWholeDay) 1440 else (endMinuteOfDay - startMinuteOfDay + 1440) % 1440

    fun split(newIds: Pair<Long, Long>): Pair<ScheduleRule, ScheduleRule>? {
        val duration = durationMinutes
        if (duration < 2) return null
        val half = duration / 2
        val mid = (startMinuteOfDay + half) % 1440
        val first = copy(id = newIds.first, endMinuteOfDay = mid)
        val second = copy(id = newIds.second, startMinuteOfDay = mid)
        return first to second
    }

    /**
     * Inverse of [split]: unions this rule with [others] into a single contiguous range.
     * The rule with the earliest start (first in list order on ties) is the base — its
     * interval/silent/enabled/oneShot carry over. Disjoint ranges are bridged by the gap.
     */
    fun merge(others: List<ScheduleRule>, newId: Long): ScheduleRule {
        val all = listOf(this) + others
        val base = all.minBy { it.startMinuteOfDay }
        var span = 0
        for (r in all) {
            val offset = (r.startMinuteOfDay - base.startMinuteOfDay + 1440) % 1440
            span = maxOf(span, offset + r.durationMinutes)
        }
        return if (span >= 1440) {
            base.copy(id = newId, startMinuteOfDay = 0, endMinuteOfDay = 0)
        } else {
            base.copy(id = newId, endMinuteOfDay = (base.startMinuteOfDay + span) % 1440)
        }
    }
}

data class FallbackConfig(
    val silent: Boolean = false,
    val intervalMinutes: Int = 5,
)

/**
 * Temporary forced minimum volume: while enabled (for [durationMinutes] since [armedAtEpochMinute]),
 * any below-threshold MEDIA/NOTIFICATION stream is raised to [thresholdPercent] just for the
 * duration of the announcement, then restored.
 */
data class VolumeGuardConfig(
    val enabled: Boolean = false,
    val thresholdPercent: Int = 30,
    val durationMinutes: Int = 180,
    val armedAtEpochMinute: Long = 0L,
) {
    fun activeAt(nowEpochMinute: Long): Boolean =
        enabled && armedAtEpochMinute > 0L && nowEpochMinute < armedAtEpochMinute + durationMinutes
}

data class AppSettings(
    val masterEnabled: Boolean = true,
    val tts: TtsSettings = TtsSettings(),
    val rules: List<ScheduleRule> = emptyList(),
    val fallback: FallbackConfig = FallbackConfig(),
    val announceMissed: Boolean = true,
    val volumeGuard: VolumeGuardConfig = VolumeGuardConfig(),
)
