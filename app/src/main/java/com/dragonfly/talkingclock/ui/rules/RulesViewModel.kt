package com.dragonfly.talkingclock.ui.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dragonfly.talkingclock.data.AppSettings
import com.dragonfly.talkingclock.data.FallbackConfig
import com.dragonfly.talkingclock.data.ScheduleRule
import com.dragonfly.talkingclock.data.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class RulesViewModel @Inject constructor(
    private val repository: SettingsRepository,
) : ViewModel() {

    val settings: StateFlow<AppSettings?> = repository.settings
        .map { s -> s.copy(rules = s.rules.sortedBy { it.startMinuteOfDay }) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun saveRule(rule: ScheduleRule) {
        viewModelScope.launch {
            repository.update { s ->
                val exists = s.rules.any { it.id == rule.id }
                val rules = if (exists) s.rules.map { if (it.id == rule.id) rule else it } else s.rules + rule
                s.copy(rules = rules.sortedBy { it.startMinuteOfDay })
            }
        }
    }

    fun deleteRule(ruleId: Long) {
        viewModelScope.launch {
            repository.update { it.copy(rules = it.rules.filterNot { r -> r.id == ruleId }) }
        }
    }

    fun setRuleEnabled(ruleId: Long, enabled: Boolean) {
        viewModelScope.launch {
            repository.update { s ->
                s.copy(rules = s.rules.map { if (it.id == ruleId) it.copy(enabled = enabled) else it })
            }
        }
    }

    fun setFallback(fallback: FallbackConfig) {
        viewModelScope.launch {
            repository.update { it.copy(fallback = fallback) }
        }
    }

    fun newRuleId(): Long = System.currentTimeMillis()
}
