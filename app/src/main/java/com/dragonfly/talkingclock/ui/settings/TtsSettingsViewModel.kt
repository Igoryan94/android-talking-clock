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

    fun setVolumeGuardThreshold(percent: Int) {
        viewModelScope.launch {
            repository.update { s -> s.copy(volumeGuard = s.volumeGuard.copy(thresholdPercent = percent.coerceIn(0, 100))) }
        }
    }

    fun setVolumeGuardDuration(minutes: Int) {
        viewModelScope.launch {
            repository.update { s -> s.copy(volumeGuard = s.volumeGuard.copy(durationMinutes = minutes.coerceIn(1, 24 * 60))) }
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
