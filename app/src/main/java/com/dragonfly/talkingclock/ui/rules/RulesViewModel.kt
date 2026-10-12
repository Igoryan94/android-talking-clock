package com.dragonfly.talkingclock.ui.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dragonfly.talkingclock.data.AppSettings
import com.dragonfly.talkingclock.data.FallbackConfig
import com.dragonfly.talkingclock.data.RuleGroup
import com.dragonfly.talkingclock.data.STANDARD_GROUP_ID
import com.dragonfly.talkingclock.data.ScheduleRule
import com.dragonfly.talkingclock.data.SettingsRepository
import com.dragonfly.talkingclock.data.effectiveGroupId
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ИИ-комментарий: ViewModel экрана Правил.
 * - Сортировка правил для отображения: сначала по группе (Стандартная 0 первая, затем по id
 *   группы = порядку создания), внутри группы — по времени начала. Хранилище при сохранении
 *   тоже сортируется так же (стабильный порядок для evaluator tie-break «первое при равенстве»).
 * - Группы: «Стандартная» (id=0) виртуальная — создать/удалить её нельзя, методы это отбивают.
 *   Удаление кастомной группы НЕ удаляет её правила: они переезжают в Стандартную (безопасно,
 *   без потери озвучки). Планировщик видит все группы одинаково.
 */
@HiltViewModel
class RulesViewModel @Inject constructor(
    private val repository: SettingsRepository,
) : ViewModel() {

    val settings: StateFlow<AppSettings?> = repository.settings
        .map { s -> s.copy(rules = s.rules.sortedWith(compareBy({ it.groupId }, { it.startMinuteOfDay }))) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun saveRule(rule: ScheduleRule) {
        viewModelScope.launch {
            val anchored = anchorOneShot(rule)
            repository.update { s ->
                val exists = s.rules.any { it.id == anchored.id }
                val rules = if (exists) s.rules.map { if (it.id == anchored.id) anchored else it } else s.rules + anchored
                s.copy(rules = rules.sortedWith(compareBy({ it.groupId }, { it.startMinuteOfDay })))
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
                        .sortedWith(compareBy({ it.groupId }, { it.startMinuteOfDay })),
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
                s.copy(rules = (s.rules + copy).sortedWith(compareBy({ it.groupId }, { it.startMinuteOfDay })))
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
                        .sortedWith(compareBy({ it.groupId }, { it.startMinuteOfDay })),
                )
            }
        }
    }

    /**
     * ИИ-комментарий: вкл/выкл ВСЕХ правил одной группы (кнопки «Выключить все»/«Включить все»
     * действуют только на открытую группу — см. RulesScreen). Правила других групп не трогаем.
     * Матчинг — по эффективной группе (см. [effectiveGroupId]): правила с битой ссылкой
     * (groupId удалённой группы) считаются правилами Стандартной и переключаются вместе с ней.
     */
    fun setGroupRulesEnabled(groupId: Long, enabled: Boolean) {
        viewModelScope.launch {
            repository.update { s ->
                s.copy(
                    rules = s.rules.map {
                        if (effectiveGroupId(it.groupId, s.ruleGroups) == groupId) it.copy(enabled = enabled) else it
                    },
                )
            }
        }
    }

    /**
     * ИИ-комментарий: массовое перемещение правил [ids] в группу [groupId] (пункт
     * «Переместить в группу» в меню выделения). Несуществующая целевая группа → кладём
     * в Стандартную (защита от битых ссылок, без потери правил). Порядок после переноса —
     * стабильный (groupId, start), как везде. Якоря one-shot и прочие поля не меняем.
     */
    fun moveRulesToGroup(ids: Set<Long>, groupId: Long) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            repository.update { s ->
                val valid = groupId == STANDARD_GROUP_ID || s.ruleGroups.any { it.id == groupId }
                val target = if (valid) groupId else STANDARD_GROUP_ID
                s.copy(
                    rules = s.rules.map { if (it.id in ids) it.copy(groupId = target) else it }
                        .sortedWith(compareBy({ it.groupId }, { it.startMinuteOfDay })),
                )
            }
        }
    }

    fun setFallback(fallback: FallbackConfig) {
        viewModelScope.launch {
            repository.update { it.copy(fallback = fallback) }
        }
    }

    // ===== ИИ-комментарий: CRUD именованных групп правил (аккордеон) =====

    /**
     * ИИ-комментарий: создать именованную группу с [name]. Пустое имя игнорируется.
     * Возвращает id через новый timestamp; дубли имён разрешены (не мешаем пользователю).
     */
    fun createGroup(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val group = RuleGroup(id = System.currentTimeMillis(), name = trimmed.take(60))
            repository.update { s -> s.copy(ruleGroups = s.ruleGroups + group) }
        }
    }

    /**
     * ИИ-комментарий: переименовать группу. Стандартную (id=0) переименовывать можно
     * (запрещено только удаление) — если понадобится запретить, добавить проверку здесь.
     */
    fun renameGroup(groupId: Long, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        if (groupId == STANDARD_GROUP_ID) return // ИИ: имя «Стандартной» фиксировано.
        viewModelScope.launch {
            repository.update { s ->
                s.copy(ruleGroups = s.ruleGroups.map { if (it.id == groupId) it.copy(name = trimmed.take(60)) else it })
            }
        }
    }

    /**
     * ИИ-комментарий: удалить группу. Стандартную удалить НЕЛЬЗЯ (молча игнорируем).
     * Правила удаляемой группы НЕ удаляются — они переезжают в Стандартную (groupId=0),
     * чтобы не потерять озвучку. Несуществующие id игнорируются.
     */
    fun deleteGroup(groupId: Long) {
        if (groupId == STANDARD_GROUP_ID) return
        viewModelScope.launch {
            repository.update { s ->
                if (s.ruleGroups.none { it.id == groupId }) return@update s
                s.copy(
                    ruleGroups = s.ruleGroups.filterNot { it.id == groupId },
                    rules = s.rules.map { if (it.groupId == groupId) it.copy(groupId = STANDARD_GROUP_ID) else it }
                        .sortedWith(compareBy({ it.groupId }, { it.startMinuteOfDay })),
                )
            }
        }
    }

    /**
     * ИИ-комментарий: переместить правило в другую группу (выбор группы в редакторе правила).
     * Несуществующая целевая группа → правило кладём в Стандартную (защита от битых ссылок).
     */
    fun setRuleGroup(ruleId: Long, groupId: Long) {
        viewModelScope.launch {
            repository.update { s ->
                val valid = groupId == STANDARD_GROUP_ID || s.ruleGroups.any { it.id == groupId }
                val target = if (valid) groupId else STANDARD_GROUP_ID
                s.copy(
                    rules = s.rules.map { if (it.id == ruleId) it.copy(groupId = target) else it }
                        .sortedWith(compareBy({ it.groupId }, { it.startMinuteOfDay })),
                )
            }
        }
    }

    fun newRuleId(): Long = System.currentTimeMillis()

    /** ИИ-комментарий: id для новой группы (timestamp; вызывается из UI перед createGroup). */
    fun newGroupId(): Long = System.currentTimeMillis()
}
