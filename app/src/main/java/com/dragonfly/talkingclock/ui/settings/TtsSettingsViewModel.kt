package com.dragonfly.talkingclock.ui.settings

import android.speech.tts.TextToSpeech
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dragonfly.talkingclock.data.AppSettings
import com.dragonfly.talkingclock.data.SettingsRepository
import com.dragonfly.talkingclock.data.TtsSettings
import com.dragonfly.talkingclock.domain.SpeechComposer
import com.dragonfly.talkingclock.speech.TtsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalTime
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class TtsSettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val ttsManager: TtsManager,
) : ViewModel() {

    val settings: StateFlow<AppSettings?> = repository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _engines = MutableStateFlow<List<TextToSpeech.EngineInfo>>(emptyList())
    val engines: StateFlow<List<TextToSpeech.EngineInfo>> = _engines

    init {
        viewModelScope.launch {
            _engines.value = ttsManager.availableEngines()
        }
    }

    val isSpeaking = MutableStateFlow(false)

    fun updateTts(transform: (TtsSettings) -> TtsSettings) {
        viewModelScope.launch {
            repository.update { it.copy(tts = transform(it.tts)) }
        }
    }

    fun setAnnounceMissed(enabled: Boolean) {
        viewModelScope.launch {
            repository.update { it.copy(announceMissed = enabled) }
        }
    }

    fun setVolumeGuardEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.update { s ->
                val next = if (enabled) {
                    s.volumeGuard.copy(enabled = true, armedAtEpochMinute = System.currentTimeMillis() / 60_000L)
                } else {
                    s.volumeGuard.copy(enabled = false, armedAtEpochMinute = 0L)
                }
                s.copy(volumeGuard = next)
            }
        }
    }

    fun setAudioFocusEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.update { s -> s.copy(tts = s.tts.copy(audioFocusEnabled = enabled)) }
        }
    }

    fun setVolumeGuardThreshold(percent: Int) {
        viewModelScope.launch {
            repository.update { s -> s.copy(volumeGuard = s.volumeGuard.copy(thresholdPercent = percent.coerceIn(0, 100))) }
        }
    }

    /**
     * Sets the remaining guard time from now: expiry becomes now + [minutes].
     * Non-positive values switch the guard off. Preserves the original arm moment so the
     * countdown keeps flowing instead of restarting on every slider tick.
     */
    fun setVolumeGuardRemaining(minutes: Int) {
        viewModelScope.launch {
            val now = System.currentTimeMillis() / 60_000L
            repository.update { s ->
                val g = s.volumeGuard
                if (minutes <= 0) {
                    s.copy(volumeGuard = g.copy(enabled = false, armedAtEpochMinute = 0L))
                } else if (!g.enabled || g.armedAtEpochMinute <= 0L) {
                    s.copy(volumeGuard = g.copy(enabled = true, armedAtEpochMinute = now, durationMinutes = minutes.coerceIn(1, 24 * 60)))
                } else {
                    val elapsed = (now - g.armedAtEpochMinute).coerceAtLeast(0L)
                    s.copy(volumeGuard = g.copy(enabled = true, durationMinutes = (elapsed + minutes).coerceIn(1L, 24 * 60).toInt()))
                }
            }
        }
    }

    fun previewText(settings: TtsSettings): String =
        SpeechComposer.preview(LocalTime.now(), settings)

    fun regexError(settings: TtsSettings): String? = SpeechComposer.regexError(settings)

    fun testVoice() {
        viewModelScope.launch {
            isSpeaking.value = true
            try {
                val s = repository.currentSettings()
                val text = SpeechComposer.compose(LocalTime.now(), s.tts)
                ttsManager.speak(text, s.tts, s.volumeGuard)
            } finally {
                isSpeaking.value = false
            }
        }
    }
}
