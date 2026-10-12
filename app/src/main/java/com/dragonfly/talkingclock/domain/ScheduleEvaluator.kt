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
 *  - ИИ-комментарий (конфликт перекрывающихся правил): когда несколько ВКЛЮЧЁННЫХ говорящих
 *    (не silent) правил покрывают одну и ту же минуту, задействуется ТОЛЬКО самое частое —
 *    с наименьшим intervalMinutes (пример: каждые 2 мин бьёт каждые 3 мин). При равенстве
 *    интервалов побеждает первое по порядку списка (legacy-поведение). Молчащие (silent)
 *    правила в конфликте НЕ участвуют: они не подавляют говорящие и не подавляются
 *    (вложенное говорящее внутри молчания по-прежнему тикает — см. тест
 *    `silent rule suppresses fallback coverage but not other rules`). Группы правил
 *    (RuleGroup) на конфликт не влияют — все enabled-правила из всех групп равноправны.
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
     * The rule governing [nowEpochMinute]: among enabled rules covering the moment, the most
     * frequent speaking rule wins (smallest interval; ties → first in list); when only silent
     * rules cover it, the first silent wins; otherwise fallback. Disabled and whole-day-empty
     * rules are ignored.
     *
     * ИИ-комментарий: раньше побеждало просто первое по списку; теперь среди перекрывающихся
     * говорящих побеждает самое частое (требование «конфликт → чаще по времени»). Silent-ветка
     * оставлена first-wins, т.к. у молчания частота не имеет смысла, а шапка показывает диапазон.
     */
    fun activeRuleAt(
        nowEpochMinute: Long,
        rules: List<ScheduleRule>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): ActiveCoverage {
        val enabled = rules.filter { it.enabled && !it.coversWholeDay }
        val today = Instant.ofEpochSecond(nowEpochMinute * 60).atZone(zone).toLocalDate()
        // ИИ-комментарий: собираем ВСЕ покрывающие правила (а не первое), затем выбираем
        // победителя по правилу частоты. Проверяем вхождения вчера+сегодня, чтобы покрыть
        // диапазоны через полночь.
        var firstSilent: ScheduleRule? = null
        var bestSpeaking: ScheduleRule? = null
        for (rule in enabled) {
            var covers = false
            for (d in listOf(today.minusDays(1), today)) {
                val (s, e) = occurrenceOn(d, zone, rule)
                if (nowEpochMinute in s until e) {
                    covers = true
                    break
                }
            }
            if (!covers) continue
            if (rule.silent) {
                if (firstSilent == null) firstSilent = rule
            } else {
                if (bestSpeaking == null ||
                    rule.intervalMinutes < bestSpeaking.intervalMinutes
                ) {
                    bestSpeaking = rule
                }
                // ИИ-комментарий: при равном интервале оставляем раннее (first-wins) — ветка
                // `rule.intervalMinutes < best` строго меньше, поэтому ties не перезаписывают.
            }
        }
        if (bestSpeaking != null) return ActiveCoverage(bestSpeaking, isFallback = false)
        if (firstSilent != null) return ActiveCoverage(firstSilent, isFallback = false)
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

        // ИИ-комментарий: говорящие enabled-правила с их порядковым индексом (для tie-break
        // при равном интервале). Silent сюда не входят — они не тикают и никого не подавляют.
        val speaking = enabled.withIndex().filter { !it.value.silent }

        // Rule ticks — с подавлением менее частых в зоне перекрытия (см. losesConflictAt).
        for ((index, rule) in speaking) {
            for (d in days) {
                val (occStart, occEnd) = occurrenceOn(d, zone, rule)
                // ИИ-комментарий: берём не просто ближайший тик, а ближайший НЕПОДАВЛЕННЫЙ:
                // если ближайший тик попадает в перекрытие с более частым правилом, ищем
                // следующий тик того же вхождения (циклом), иначе редкое правило «съедало» бы
                // очередь неверно (пропадало бы совсем вместо сдвига на свой следующий тик).
                val tick = nextUnsuppressedTickIn(
                    now = nowEpochMinute,
                    start = occStart,
                    end = occEnd,
                    rule = rule,
                    ruleIndex = index,
                    speaking = speaking,
                    zone = zone,
                )
                if (tick != null) {
                    candidates += Candidate(tick, index, rule.id, isFallback = false, interval = rule.intervalMinutes)
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
                    candidates += Candidate(tick, index = Int.MAX_VALUE, ruleId = null, isFallback = true, interval = Int.MAX_VALUE)
                }
            }
        }

        // ИИ-комментарий: финальный tie-break учитывает и частоту (на случай, если два
        // неподавленных тика всё же совпали на одной минуте — побеждает частое, затем раннее).
        return candidates
            .sortedWith(compareBy({ it.at }, { it.isFallback }, { it.interval }, { it.index }))
            .firstOrNull()
            ?.let { NextSpeak(it.at, it.ruleId, it.isFallback) }
    }

    /**
     * ИИ-комментарий: true, когда тик [tick] правила [rule] (с порядком [ruleIndex]) должен быть
     * подавлен конфликтом: существует ДРУГОЕ включённое говорящее правило, которое тоже покрывает
     * минуту [tick] и при этом (а) чаще (меньший intervalMinutes) либо (б) с тем же интервалом,
     * но раньше по списку. Молчащие правила игнорируются (не подавляют). Проверка покрытия —
     * через вхождения вчера/сегодня/завтра, чтобы корректно ловить диапазоны через полночь.
     */
    private fun losesConflictAt(
        tick: Long,
        rule: ScheduleRule,
        ruleIndex: Int,
        speaking: List<IndexedValue<ScheduleRule>>,
        zone: ZoneId,
    ): Boolean {
        val tickDate = Instant.ofEpochSecond(tick * 60).atZone(zone).toLocalDate()
        for ((otherIndex, other) in speaking) {
            if (other.id == rule.id) continue
            // Частое бьёт редкое; при равном интервале — раннее по списку бьёт позднее.
            val beats =
                other.intervalMinutes < rule.intervalMinutes ||
                    (other.intervalMinutes == rule.intervalMinutes && otherIndex < ruleIndex)
            if (!beats) continue
            for (d in listOf(tickDate.minusDays(1), tickDate, tickDate.plusDays(1))) {
                val (s, e) = occurrenceOn(d, zone, other)
                if (tick in s until e) return true
            }
        }
        return false
    }

    /**
     * ИИ-комментарий: ближайший тик вхождения [start, end) строго после [now], который НЕ
     * подавлен конфликтом (см. [losesConflictAt]). Перебирает тики вхождения по порядку
     * (через [nextTickIn] цепочкой), пока не найдёт неподавленный или вхождение не кончится.
     * Цикл ограничен длиной вхождения (<=1440 итераций для минутных), правила короткие —
     * производительность не страдает (правил мало, вхождений 3 дня).
     */
    private fun nextUnsuppressedTickIn(
        now: Long,
        start: Long,
        end: Long,
        rule: ScheduleRule,
        ruleIndex: Int,
        speaking: List<IndexedValue<ScheduleRule>>,
        zone: ZoneId,
    ): Long? {
        var cursor = now
        while (true) {
            val tick = nextTickIn(cursor, start, end, rule.intervalMinutes, zone) ?: return null
            if (!losesConflictAt(tick, rule, ruleIndex, speaking, zone)) return tick
            cursor = tick
            // ИИ-комментарий: защита от бесконечного цикла — тик обязан расти (nextTickIn даёт
            // строго > cursor), а выход — когда тик упёрся в end (null выше).
            if (cursor >= end) return null
        }
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
        // ИИ-комментарий: интервал нужен для финального tie-break «частое бьёт редкое»,
        // когда два неподавленных тика совпали (fallback = MAX, всегда проигрывает правилам).
        val interval: Int = Int.MAX_VALUE,
    )

    const val MINUTES_PER_DAY = 1440L
}
