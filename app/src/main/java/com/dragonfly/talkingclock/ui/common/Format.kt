package com.dragonfly.talkingclock.ui.common

import com.dragonfly.talkingclock.data.ScheduleRule
import java.util.Locale

fun minuteToText(m: Int): String = "%02d:%02d".format(Locale.ROOT, m / 60, m % 60)

fun pluralRu(n: Int, one: String, few: String, many: String): String {
    val mod100 = n % 100
    val mod10 = n % 10
    return when {
        mod10 == 1 && mod100 != 11 -> one
        mod10 in 2..4 && mod100 !in 12..14 -> few
        else -> many
    }
}

fun intervalText(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 1 && m == 0 -> "каждый час"
        h > 1 && m == 0 -> "каждые $h ${pluralRu(h, "час", "часа", "часов")}"
        h == 0 && m == 30 -> "каждые полчаса"
        h == 0 && m == 15 -> "каждые четверть часа"
        h == 0 -> "кажд${pluralRu(m, "ую", "ые", "ые")} $m ${pluralRu(m, "минуту", "минуты", "минут")}"
        else -> "каждые $h ч $m ${pluralRu(m, "мин", "мин", "мин")}"
    }
}

/** True when ticks stick to a strict wall-clock grid (minute-of-hour / midnight multiples). */
fun isWallClockGrid(minutes: Int): Boolean =
    minutes in 1..1440 && (60 % minutes == 0 || minutes % 60 == 0)

/** Short alignment hint shown next to interval inputs, e.g. "00:04, 00:08, …". */
fun intervalGridHint(minutes: Int): String? {
    if (!isWallClockGrid(minutes)) return null
    return if (minutes >= 60) "по круглым значениям: 00:00, ${minuteToText(minutes)}, ${minuteToText(minutes * 2 % 1440)}, …"
    else "по круглым значениям: 00:${"%02d".format(Locale.ROOT, minutes)}, 00:${"%02d".format(Locale.ROOT, minutes * 2)}, …"
}

fun ruleTimeRangeText(rule: ScheduleRule): String =
    "${minuteToText(rule.startMinuteOfDay)} – ${minuteToText(rule.endMinuteOfDay)}" +
        if (rule.crossesMidnight) " (через полночь)" else ""
