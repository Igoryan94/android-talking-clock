package com.dragonfly.talkingclock.domain

import com.dragonfly.talkingclock.data.TtsSettings
import java.time.LocalTime
import java.util.Locale

/**
 * Builds the spoken text: prefix + formatted time (from a token template) + postfix,
 * then optionally applies the user's regex replacement.
 *
 * Template tokens:
 *   HH — zero-padded 24h hour (08)
 *   H  — 24h hour (8)
 *   hh — zero-padded 12h hour (08)
 *   h  — 12h hour (8)
 *   mm — zero-padded minute (05)
 *   m  — minute (5)
 */
object SpeechComposer {

    fun compose(time: LocalTime, settings: TtsSettings): String {
        val hour24 = time.hour
        val hour12 = if (hour24 % 12 == 0) 12 else hour24 % 12
        var text = settings.timeTemplate
            .replace("HH", String.format(Locale.ROOT, "%02d", hour24))
            .replace("hh", String.format(Locale.ROOT, "%02d", hour12))
            .replace("mm", String.format(Locale.ROOT, "%02d", time.minute))
            .replace("H", hour24.toString())
            .replace("h", hour12.toString())
            .replace("m", time.minute.toString())

        val raw = listOf(settings.prefix, text, settings.postfix)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        text = raw.replace(Regex("\\s+"), " ").trim()

        if (settings.regexEnabled) {
            text = applyRegex(text, settings)
        }
        return text
    }

    /** Returns null when the pattern compiles fine; error message otherwise. */
    fun regexError(settings: TtsSettings): String? {
        if (!settings.regexEnabled) return null
        return try {
            Regex(settings.regexPattern)
            null
        } catch (e: Exception) {
            e.message
        }
    }

    private fun applyRegex(text: String, settings: TtsSettings): String = runCatching {
        text.replace(Regex(settings.regexPattern), settings.regexReplacement)
    }.getOrDefault(text)

    fun preview(now: LocalTime, settings: TtsSettings): String = compose(now, settings)
}
