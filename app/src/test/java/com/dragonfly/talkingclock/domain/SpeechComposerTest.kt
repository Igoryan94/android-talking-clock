package com.dragonfly.talkingclock.domain

import com.dragonfly.talkingclock.data.TtsSettings
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechComposerTest {

    @Test
    fun `default template`() {
        val text = SpeechComposer.compose(LocalTime.of(8, 5), TtsSettings())
        assertEquals("8:05", text)
    }

    @Test
    fun `zero padding`() {
        val text = SpeechComposer.compose(LocalTime.of(8, 5), TtsSettings(timeTemplate = "HH:mm"))
        assertEquals("08:05", text)
    }

    @Test
    fun `12 hour tokens`() {
        assertEquals("1:05", SpeechComposer.compose(LocalTime.of(13, 5), TtsSettings(timeTemplate = "h:mm")))
        assertEquals("12:30", SpeechComposer.compose(LocalTime.of(0, 30), TtsSettings(timeTemplate = "h:mm")))
        assertEquals("01:05", SpeechComposer.compose(LocalTime.of(13, 5), TtsSettings(timeTemplate = "hh:mm")))
    }

    @Test
    fun `prefix and postfix with spacing`() {
        val text = SpeechComposer.compose(
            LocalTime.of(8, 5),
            TtsSettings(prefix = "Сейчас", postfix = "секунд в минуту не больше"),
        )
        assertEquals("Сейчас 8:05 секунд в минуту не больше", text)
    }

    @Test
    fun `regex replacement`() {
        val text = SpeechComposer.compose(
            LocalTime.of(8, 5),
            TtsSettings(
                timeTemplate = "H:mm",
                regexPattern = "(\\d):(\\d+)",
                regexReplacement = "$1 часов $2 минут",
            ),
        )
        assertEquals("8 часов 05 минут", text)
    }

    @Test
    fun `invalid regex keeps text intact`() {
        val text = SpeechComposer.compose(
            LocalTime.of(8, 5),
            TtsSettings(regexPattern = "([invalid"),
        )
        assertEquals("8:05", text)
        assertNotNull(SpeechComposer.regexError(TtsSettings(regexPattern = "([invalid")))
        assertNull(SpeechComposer.regexError(TtsSettings(regexPattern = "(\\d+)")))
        assertNull(SpeechComposer.regexError(TtsSettings()))
    }

    @Test
    fun `hour 20 stays two digit with H`() {
        val text = SpeechComposer.compose(LocalTime.of(20, 0), TtsSettings(timeTemplate = "H:mm"))
        assertEquals("20:00", text)
    }

    @Test
    fun `russian template`() {
        val text = SpeechComposer.compose(
            LocalTime.of(9, 41),
            TtsSettings(timeTemplate = "Сейчас HH часов mm минут"),
        )
        assertEquals("Сейчас 09 часов 41 минут", text)
    }

    @Test
    fun `preview equals compose`() {
        val settings = TtsSettings(prefix = "Время", timeTemplate = "HH:mm")
        val now = LocalTime.now()
        assertEquals(SpeechComposer.compose(now, settings), SpeechComposer.preview(now, settings))
    }

    @Test
    fun `regex applied after prefix and postfix`() {
        val text = SpeechComposer.compose(
            LocalTime.of(8, 5),
            TtsSettings(
                prefix = "PREFIX",
                postfix = "POSTFIX",
                regexPattern = "^PREFIX (.*) POSTFIX$",
                regexReplacement = "$1!",
            ),
        )
        assertEquals("8:05!", text)
        assertTrue(text.isNotEmpty())
    }
}
