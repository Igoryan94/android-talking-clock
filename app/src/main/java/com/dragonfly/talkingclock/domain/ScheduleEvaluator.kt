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
 *  - Tick alignment is strict wall-clock when possible: intervals that divide 60
 *    (1,2,3,4,5,6,10,12,15,20,30,60) tick on minute-of-hour multiples
 *    ("every 4 min" → 00:00, 00:04 … 00:56, 01:00 …); intervals that are multiples of 60
 *    (2h, 3h …) tick from midnight. Others (7, 8, 9, 11 …) tick from the range start.
 *    The same applies to fallback ticks inside uncovered gaps.
 *  - Enabled rules (including silent ones) mark time as "covered" — the fallback only ticks in
 *    uncovered gaps.
 *  - Silent rules and silent fallback produce no ticks.
 *  - When several candidates fall on the same minute, rule with lower list index wins over fallback.
 */
object ScheduleEvaluator {

    data class NextSpeak(
        val atEpochMinute: Long,
        val ruleId: Long?,
        val isFallback: Boolean = false,
    )

    /** What governs the given minute: a specific rule (may be silent) or the fallback. */
    data class ActiveCoverage(
        val rule: ScheduleRule?,
        val isFallback: Boolean,
    )

    /**
     * The rule whose [start, end) range contains [nowEpochMinute] (first in list order wins),
     * or fallback when no rule covers the moment. Disabled and whole-day-empty rules are ignored.
     */
    fun activeRuleAt(
        nowEpochMinute: Long,
        rules: List<ScheduleRule>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): ActiveCoverage {
        val enabled = rules.filter { it.enabled && !it.coversWholeDay }
        val today = Instant.ofEpochSecond(nowEpochMinute * 60).atZone(zone).toLocalDate()
        for (rule in enabled) {
            for (d in listOf(today.minusDays(1), today)) {
                val (s, e) = occurrenceOn(d, zone, rule)
                if (nowEpochMinute in s until e) return ActiveCoverage(rule, isFallback = false)
            }
        }
        return ActiveCoverage(rule = null, isFallback = true)
    }

    /**
     * Ids of enabled one-shot rules whose grace period is over: they are considered spent and
     * should be removed. A one-shot rule lives through its whole [start, end) period (silent or
     * speaking) and is dropped one minute after that period ends — never at its first trigger.
     */
    fun expiredOneShotIds(
        nowEpochMinute: Long,
        rules: List<ScheduleRule>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Set<Long> = rules
        .filter { it.oneShot && it.enabled && !it.coversWholeDay }
        .filter { oneShotExpiry(nowEpochMinute, it, zone) <= nowEpochMinute }
        .map { it.id }
        .toSet()

    /**
     * Earliest epoch minute at which an enabled one-shot rule must be auto-removed, i.e. one
     * minute after the end of its current or next period. Null when nothing is pending.
     */
    fun nextOneShotExpiry(
        nowEpochMinute: Long,
        rules: List<ScheduleRule>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long? = rules
        .asSequence()
        .filter { it.oneShot && it.enabled && !it.coversWholeDay }
        .map { oneShotExpiry(nowEpochMinute, it, zone) }
        .filter { it > nowEpochMinute }
        .minOrNull()

    /**
     * Epoch minute one minute after the end of the occurrence that is currently active, or —
     * when none is active — the occurrence starting on the current local day (upcoming or spent).
     */
    private fun oneShotExpiry(nowEpochMinute: Long, rule: ScheduleRule, zone: ZoneId): Long {
        val anchor = rule.oneShotAnchorMinute
        if (anchor > 0L) {
            // Known creation moment: target the first occurrence ending strictly after it, so a
            // duplicated/created rule whose window already passed today waits for the next one.
            val anchorDate = Instant.ofEpochSecond(anchor * 60).atZone(zone).toLocalDate()
            var end = Long.MAX_VALUE
            for (d in listOf(anchorDate.minusDays(1), anchorDate, anchorDate.plusDays(1))) {
                val e = occurrenceOn(d, zone, rule).second
                if (e > anchor && e < end) end = e
            }
            return if (end == Long.MAX_VALUE) nowEpochMinute + 1 else end + 1
        }

        val today = Instant.ofEpochSecond(nowEpochMinute * 60).atZone(zone).toLocalDate()
        for (d in listOf(today.minusDays(1), today)) {
            val (s, e) = occurrenceOn(d, zone, rule)
            if (nowEpochMinute in s until e) return e + 1
        }
        if (rule.crossesMidnight) {
            // Between last night's occurrence end and tonight's start — the night is already spent.
            val (_, yesterdayEnd) = occurrenceOn(today.minusDays(1), zone, rule)
            val (todayStart, _) = occurrenceOn(today, zone, rule)
            if (nowEpochMinute >= yesterdayEnd && nowEpochMinute < todayStart) return yesterdayEnd + 1
        }
        val (_, e) = occurrenceOn(today, zone, rule)
        return e + 1
    }

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
                val tick = nextTickIn(nowEpochMinute, occStart, occEnd, rule.intervalMinutes, zone)
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
                val tick = nextTickIn(nowEpochMinute, gap.first, gap.last + 1, fallback.intervalMinutes, zone)
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

    /** True when ticks of [intervalMinutes] stick to a strict wall-clock grid. */
    private fun isWallClockGrid(intervalMinutes: Int): Boolean =
        intervalMinutes in 1..1440 && (60 % intervalMinutes == 0 || intervalMinutes % 60 == 0)

    /**
     * Returns the next tick strictly after [now] inside [start, end) with the given interval.
     *
     * Grid intervals (see [isWallClockGrid]) tick at minute-of-day multiples of the interval:
     * every 4 min → 00:00, 00:04 … 00:56, 01:00; every 2h → 00:00, 02:00 …
     * Other intervals keep the legacy behavior — aligned to [start].
     */
    private fun nextTickIn(now: Long, start: Long, end: Long, intervalMinutes: Int, zone: ZoneId): Long? {
        if (intervalMinutes <= 0) return null
        if (isWallClockGrid(intervalMinutes)) {
            return nextGridTick(now, start, end, intervalMinutes.toLong(), zone)
        }
        val k = floorDiv(now - start, intervalMinutes.toLong()) + 1
        val tickIndex = maxOf(0L, k)
        val tick = start + tickIndex * intervalMinutes
        return if (tick < end) tick else null
    }

    /** Next minute-of-day-grid tick: t > now, t >= start, t < end, (t - dayAnchor) % grid == 0. */
    private fun nextGridTick(now: Long, start: Long, end: Long, grid: Long, zone: ZoneId): Long? {
        val t = maxOf(now + 1, start)
        val anchor = dayStart(Instant.ofEpochSecond(t * 60).atZone(zone).toLocalDate(), zone)
        val m = t - anchor
        val tick = if (m % grid == 0L) {
            t
        } else {
            val nextM = (m / grid + 1) * grid
            if (nextM < MINUTES_PER_DAY) anchor + nextM else dayStartOfNextDay(anchor, zone)
        }
        return if (tick < end) tick else null
    }

    /** Epoch minute of the local midnight that follows [anchor]'s day. */
    private fun dayStartOfNextDay(anchor: Long, zone: ZoneId): Long =
        dayStart(Instant.ofEpochSecond(anchor * 60).atZone(zone).toLocalDate().plusDays(1), zone)

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
