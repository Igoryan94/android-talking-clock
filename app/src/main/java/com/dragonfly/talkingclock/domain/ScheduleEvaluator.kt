package com.dragonfly.talkingclock.domain

import com.dragonfly.talkingclock.data.FallbackConfig
import com.dragonfly.talkingclock.data.ScheduleRule
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private fun floorDiv(a: Long, b: Long): Long {
    var q = a / b
    if ((a xor b) < 0 && q * b != a) q--
    return q
}

/**
 * Computes the next time-speaking trigger from the rule set.
 *
 * All wall-clock times (rule ranges, tick alignment) are interpreted in [zone],
 * defaulting to the device time zone; internally everything is absolute epoch minutes.
 *
 * Semantics:
 *  - A rule covers [start, end) minutes of day; end < start means the range crosses midnight.
 *  - Enabled rule ticks are aligned to the range start: start, start+N, ... strictly before end.
 *  - Enabled rules (including silent ones) mark time as "covered" — the fallback only ticks in
 *    uncovered gaps. Fallback ticks are aligned to the gap start.
 *  - Silent rules and silent fallback produce no ticks.
 *  - When several candidates fall on the same minute, rule with lower list index wins over fallback.
 */
object ScheduleEvaluator {

    data class NextSpeak(
        val atEpochMinute: Long,
        val ruleId: Long?,
        val isFallback: Boolean = false,
    )

    fun nextSpeakTrigger(
        nowEpochMinute: Long,
        rules: List<ScheduleRule>,
        fallback: FallbackConfig,
        zone: ZoneId = ZoneId.systemDefault(),
    ): NextSpeak? {
        val enabled = rules.filter { it.enabled && !it.coversWholeDay }
        val today = Instant.ofEpochSecond(nowEpochMinute * 60).atZone(zone).toLocalDate()
        val days = listOf(today.minusDays(1), today, today.plusDays(1))

        val candidates = mutableListOf<Candidate>()

        // Rule ticks
        for ((index, rule) in enabled.withIndex()) {
            if (rule.silent) continue
            for (d in days) {
                val (occStart, occEnd) = occurrenceOn(d, zone, rule)
                val tick = nextTickIn(nowEpochMinute, occStart, occEnd, rule.intervalMinutes)
                if (tick != null) {
                    candidates += Candidate(tick, index, rule.id, isFallback = false)
                }
            }
        }

        // Fallback ticks in uncovered gaps
        if (!fallback.silent) {
            val horizonStart = dayStart(today.minusDays(1), zone)
            val horizonEnd = dayStart(today.plusDays(2), zone)
            val covered = mutableListOf<LongRange>()
            for (rule in enabled) {
                for (d in days) {
                    val (s, e) = occurrenceOn(d, zone, rule)
                    if (s >= e) continue
                    covered += s until e
                }
            }
            val gaps = complement(covered, horizonStart until horizonEnd)
            for (gap in gaps) {
                if (gap.last <= nowEpochMinute) continue
                val tick = nextTickIn(nowEpochMinute, gap.first, gap.last + 1, fallback.intervalMinutes)
                if (tick != null) {
                    candidates += Candidate(tick, index = Int.MAX_VALUE, ruleId = null, isFallback = true)
                }
            }
        }

        return candidates
            .sortedWith(compareBy({ it.at }, { it.isFallback }, { it.index }))
            .firstOrNull()
            ?.let { NextSpeak(it.at, it.ruleId, it.isFallback) }
    }

    /** Returns the next tick strictly after [now] inside [start, end) with the given interval. */
    private fun nextTickIn(now: Long, start: Long, end: Long, intervalMinutes: Int): Long? {
        if (intervalMinutes <= 0) return null
        val k = floorDiv(now - start, intervalMinutes.toLong()) + 1
        val tickIndex = maxOf(0L, k)
        val tick = start + tickIndex * intervalMinutes
        return if (tick < end) tick else null
    }

    /** Epoch minute of local midnight of [date] in [zone]. */
    private fun dayStart(date: LocalDate, zone: ZoneId): Long =
        date.atStartOfDay(zone).toInstant().epochSecond / 60

    /**
     * Occurrence of the rule's day-range starting on [date], in absolute epoch minutes.
     * For ranges crossing midnight, the occurrence ends on the next local day.
     */
    private fun occurrenceOn(date: LocalDate, zone: ZoneId, rule: ScheduleRule): Pair<Long, Long> {
        val start = dayStart(date, zone) + rule.startMinuteOfDay
        val end = if (rule.crossesMidnight) {
            dayStart(date.plusDays(1), zone) + rule.endMinuteOfDay
        } else {
            dayStart(date, zone) + rule.endMinuteOfDay
        }
        return start to end
    }

    /** Merges overlapping [ranges] and returns the complement within [scope]. */
    private fun complement(ranges: List<LongRange>, scope: LongRange): List<LongRange> {
        if (ranges.isEmpty()) return listOf(scope)
        val sorted = ranges
            .map { it.first..it.last }
            .sortedBy { it.first }
        val merged = mutableListOf<LongRange>()
        for (r in sorted) {
            val last = merged.lastOrNull()
            if (last != null && r.first <= last.last + 1) {
                if (r.last > last.last) merged[merged.size - 1] = last.first..r.last
            } else {
                merged += r
            }
        }
        val result = mutableListOf<LongRange>()
        var cursor = scope.first
        for (r in merged) {
            if (r.first > cursor) result += cursor until r.first
            cursor = maxOf(cursor, r.last + 1)
            if (cursor > scope.last) break
        }
        if (cursor <= scope.last) result += cursor..scope.last
        return result
    }

    private data class Candidate(
        val at: Long,
        val index: Int,
        val ruleId: Long?,
        val isFallback: Boolean,
    )

    const val MINUTES_PER_DAY = 1440L
}
