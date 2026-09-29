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
            val anchored = anchorOneShot(rule)
            repository.update { s ->
                val exists = s.rules.any { it.id == anchored.id }
                val rules = if (exists) s.rules.map { if (it.id == anchored.id) anchored else it } else s.rules + anchored
                s.copy(rules = rules.sortedBy { it.startMinuteOfDay })
            }
        }
    }

    /** Stamps the creation moment onto a one-shot rule unless it already has one. */
    private fun anchorOneShot(rule: ScheduleRule): ScheduleRule = when {
        !rule.oneShot -> rule.copy(oneShotAnchorMinute = 0L)
        rule.oneShotAnchorMinute > 0L -> rule
        else -> rule.copy(oneShotAnchorMinute = System.currentTimeMillis() / 60_000L)
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

    fun splitRule(rule: ScheduleRule) {
        viewModelScope.launch {
            val baseId = System.currentTimeMillis()
            repository.update { s ->
                val parts = rule.split(baseId to baseId + 1) ?: return@update s
                val (first, second) = parts
                s.copy(
                    rules = s.rules
                        .flatMap { r -> if (r.id == rule.id) listOf(first, second) else listOf(r) }
                        .sortedBy { it.startMinuteOfDay },
                )
            }
        }
    }

    fun duplicateRule(rule: ScheduleRule) {
        viewModelScope.launch {
            val copy = rule.copy(
                id = System.currentTimeMillis(),
                oneShot = true,
                oneShotAnchorMinute = System.currentTimeMillis() / 60_000L,
            )
            repository.update { s ->
                s.copy(rules = (s.rules + copy).sortedBy { it.startMinuteOfDay })
            }
        }
    }

    fun mergeRules(ids: Set<Long>) {
        viewModelScope.launch {
            val newId = System.currentTimeMillis()
            repository.update { s ->
                val selected = s.rules.filter { it.id in ids }
                if (selected.size < 2) return@update s
                val merged = selected.first().merge(selected.drop(1), newId)
                s.copy(
                    rules = (s.rules.filterNot { it.id in ids } + merged)
                        .sortedBy { it.startMinuteOfDay },
                )
            }
        }
    }

    fun setAllRulesEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.update { s ->
                s.copy(rules = s.rules.map { it.copy(enabled = enabled) })
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
