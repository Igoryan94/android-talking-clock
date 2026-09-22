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
) {
    val crossesMidnight: Boolean get() = endMinuteOfDay < startMinuteOfDay
    val coversWholeDay: Boolean get() = startMinuteOfDay == 0 && endMinuteOfDay == 0
}

data class FallbackConfig(
    val silent: Boolean = false,
    val intervalMinutes: Int = 5,
)

data class AppSettings(
    val masterEnabled: Boolean = true,
    val tts: TtsSettings = TtsSettings(),
    val rules: List<ScheduleRule> = emptyList(),
    val fallback: FallbackConfig = FallbackConfig(),
    val announceMissed: Boolean = true,
)
