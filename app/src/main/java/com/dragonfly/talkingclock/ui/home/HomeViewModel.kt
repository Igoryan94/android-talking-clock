package com.dragonfly.talkingclock.ui.home

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dragonfly.talkingclock.data.AppSettings
import com.dragonfly.talkingclock.data.SettingsRepository
import com.dragonfly.talkingclock.domain.ScheduleEvaluator
import com.dragonfly.talkingclock.scheduling.AlarmScheduler
import com.dragonfly.talkingclock.speech.SpeakService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val repository: SettingsRepository,
    private val alarmScheduler: AlarmScheduler,
) : ViewModel() {

    val settings: StateFlow<AppSettings?> = repository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val ticker = flow {
        while (true) {
            emit(Unit)
            delay(20_000)
        }
    }

    val nextSpeak: StateFlow<ScheduleEvaluator.NextSpeak?> = combine(settings, ticker) { s, _ ->
        s?.takeIf { it.masterEnabled }?.let { computeNext(it) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val activeCoverage: StateFlow<ScheduleEvaluator.ActiveCoverage?> = combine(settings, ticker) { s, _ ->
        s?.takeIf { it.masterEnabled }?.let {
            ScheduleEvaluator.activeRuleAt(System.currentTimeMillis() / 60_000L, it.rules)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val exactAlarmGranted: Boolean get() = alarmScheduler.canScheduleExact()

    fun setMasterEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.update { it.copy(masterEnabled = enabled) }
        }
    }

    fun speakNow() {
        val nowEpochMinute = System.currentTimeMillis() / 60_000L
        val intent = SpeakService.speakTimeIntent(appContext, nowEpochMinute)
        appContext.startForegroundService(intent)
    }

    fun openExactAlarmSettings() {
        runCatching {
            appContext.startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun computeNext(settings: AppSettings): ScheduleEvaluator.NextSpeak? =
        ScheduleEvaluator.nextSpeakTrigger(
            nowEpochMinute = System.currentTimeMillis() / 60_000L,
            rules = settings.rules,
            fallback = settings.fallback,
        )
}
