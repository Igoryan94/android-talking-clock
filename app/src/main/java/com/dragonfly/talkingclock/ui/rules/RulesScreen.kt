package com.dragonfly.talkingclock.ui.rules

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dragonfly.talkingclock.data.FallbackConfig
import com.dragonfly.talkingclock.data.RuleGroup
import com.dragonfly.talkingclock.data.STANDARD_GROUP_ID
import com.dragonfly.talkingclock.data.STANDARD_GROUP_NAME
import com.dragonfly.talkingclock.data.ScheduleRule
import com.dragonfly.talkingclock.data.effectiveGroupId
import com.dragonfly.talkingclock.data.suggestNewRuleRange
import com.dragonfly.talkingclock.ui.common.intervalGridHint
import com.dragonfly.talkingclock.ui.common.intervalText
import com.dragonfly.talkingclock.ui.common.minuteToText
import com.dragonfly.talkingclock.ui.common.ruleTimeRangeText
import java.time.LocalTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val rulePalette = listOf(
    Color(0xFF3F51B5),
    Color(0xFF00897B),
    Color(0xFF8E24AA),
    Color(0xFFC2923B),
    Color(0xFFD81B60),
    Color(0xFF43A047),
    Color(0xFF039BE5),
    Color(0xFF6D4C41),
)

private fun ruleColor(index: Int) = rulePalette[index % rulePalette.size]

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen(
    viewModel: RulesViewModel = hiltViewModel(),
    // ИИ-комментарий: запрос проскроллить список к пункту «Вне правил» (карточка fallback).
    // Источник — кнопка «Правила озвучки» на Главной (TalkClockRoot инкрементит счётчик при
    // переходе). 0 = запроса не было; каждое нажатие +1. RulesScreen при изменении токена
    // скроллит LazyColumn к индексу fallback-карточки (см. fallbackIndex ниже).
    scrollToFallbackToken: Int = 0,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ScheduleRule?>(null) }
    // ИИ-комментарий: в какую группу создаём новое правило (null = диалог закрыт).
    // FAB кладёт в открытую группу (или Стандартную, если всё свернуто); пункт меню группы
    // «Добавить правило сюда» кладёт строго в эту группу и раскрывает её.
    var creatingInGroup by rememberSaveable { mutableStateOf<Long?>(null) }
    var highlightedRuleId by remember { mutableStateOf<Long?>(null) }
    var mergeSelection by remember { mutableStateOf<Set<Long>?>(null) }
    // ИИ-комментарий (аккордеон групп): id раскрытой группы или null = все свернуты (дефолт —
    // все скрыты по требованию). Открыта максимум одна: тап по свернутой раскрывает её и
    // закрывает остальные; тап по раскрытой — сворачивает всё. rememberSaveable — переживает
    // поворот; свайпы между вкладками состояние не сбрасывают (pager beyondViewportPageCount=2).
    var expandedGroupId by rememberSaveable { mutableStateOf<Long?>(null) }
    var creatingGroup by remember { mutableStateOf(false) }
    var renamingGroup by remember { mutableStateOf<RuleGroup?>(null) }
    var deletingGroup by remember { mutableStateOf<RuleGroup?>(null) }
    // ИИ-комментарий: открыт ли диалог массового перемещения выбранных правил в группу
    // (пункт «В группу…» в панели режима выбора). Сам выбор живёт в mergeSelection.
    var showMoveDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(highlightedRuleId) {
        if (highlightedRuleId != null) {
            delay(3000)
            highlightedRuleId = null
        }
    }

    val s = settings
    // ИИ-комментарий: кастомные группы по порядку создания + витринный список с виртуальной
    // «Стандартной» первой. Эффективная группа правила — через общий хелпер
    // data.effectiveGroupId (битые ссылки → Стандартная); та же логика используется
    // в RulesViewModel.setGroupRulesEnabled, чтобы кнопки вкл/выкл и данные не расходились.
    val customGroups: List<RuleGroup> = remember(s) {
        s?.ruleGroups?.sortedBy { it.id } ?: emptyList()
    }
    val displayGroups: List<RuleGroup> = remember(customGroups) {
        listOf(RuleGroup(STANDARD_GROUP_ID, STANDARD_GROUP_NAME)) + customGroups
    }
    fun groupOf(rule: ScheduleRule): Long = effectiveGroupId(rule.groupId, customGroups)
    val rulesByGroup: Map<Long, List<ScheduleRule>> = remember(s, customGroups) {
        (s?.rules ?: emptyList()).groupBy(::groupOf)
    }
    // ИИ-комментарий: правила ОТКРЫТОЙ группы — на них действуют кнопки «Выключить все» /
    // «Включить все». Если группа не открыта (expandedGroupId == null), список пуст и обе
    // кнопки disabled (будто правил нет — требование задачи).
    val openGroupRules: List<ScheduleRule> = remember(rulesByGroup, expandedGroupId) {
        expandedGroupId?.let { rulesByGroup[it] } ?: emptyList()
    }
    // ИИ-комментарий: индекс fallback-карточки в LazyColumn: timeline(0) + заголовок(1) +
    // шапки групп + видимые (раскрытые) правила. Пересчитывается при каждом запросе скролла,
    // т.к. зависит от раскрытости. Скролл запускаем с небольшой задержкой, чтобы pager успел
    // доехать до страницы Правил (иначе animateScrollToItem стартует на невидимом списке).
    LaunchedEffect(scrollToFallbackToken) {
        if (scrollToFallbackToken > 0 && s != null) {
            delay(350)
            var fallbackIndex = 2 + displayGroups.size
            for (g in displayGroups) {
                if (expandedGroupId == g.id) fallbackIndex += (rulesByGroup[g.id]?.size ?: 0)
            }
            runCatching { listState.animateScrollToItem(fallbackIndex) }
        }
    }
    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creatingInGroup = expandedGroupId ?: STANDARD_GROUP_ID },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Добавить правило") },
            )
        },
    ) { padding ->
        if (s == null) {
            CircularProgressIndicator(Modifier.padding(padding).padding(24.dp))
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    CoverageTimeline(
                        rules = s.rules,
                        fallback = s.fallback,
                        onRuleClick = { rule ->
                            // ИИ-комментарий: тап по графику раскрывает группу правила (аккордеон —
                            // остальные закрываются), подсвечивает карточку и скроллит к ней.
                            val gid = groupOf(rule)
                            expandedGroupId = gid
                            highlightedRuleId = rule.id
                            scope.launch {
                                delay(100)
                                var idx = 2 // timeline + заголовок
                                for (g in displayGroups) {
                                    if (g.id == gid) {
                                        val pos = (rulesByGroup[gid] ?: emptyList())
                                            .indexOfFirst { it.id == rule.id }
                                        if (pos >= 0) {
                                            runCatching { listState.animateScrollToItem(idx + 1 + pos) }
                                        }
                                        break
                                    }
                                    idx += 1 // шапка группы
                                    if (expandedGroupId == g.id) idx += (rulesByGroup[g.id]?.size ?: 0)
                                }
                            }
                        },
                    )
                }
                item {
                    Column(Modifier.padding(top = 16.dp)) {
                        Text("Правила озвучки", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Группы по умолчанию скрыты; открыта может быть только одна. Кнопки ниже включают/выключают только правила открытой группы. При перекрытии времён говорит только самое частое правило (меньший интервал). Диапазоны — полуоткрытые [начало, конец).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (mergeSelection != null) {
                            // ИИ-комментарий: панель режима выбора (вход — «Объединить…» из меню
                            // карточки). «В группу…» — массовое перемещение выбранных в желаемую
                            // группу; «Объединить» требует ≥2 (слияние диапазонов).
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "Выбрано: ${mergeSelection!!.size}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(onClick = { mergeSelection = null }) { Text("Отмена") }
                                }
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    OutlinedButton(
                                        onClick = { showMoveDialog = true },
                                        modifier = Modifier.weight(1f),
                                    ) { Text("В группу…") }
                                    Button(
                                        enabled = mergeSelection!!.size >= 2,
                                        onClick = {
                                            viewModel.mergeRules(mergeSelection!!)
                                            mergeSelection = null
                                        },
                                        modifier = Modifier.weight(1f),
                                    ) { Text("Объединить") }
                                }
                            }
                        } else {
                            // ИИ-комментарий: вкл/выкл действуют ТОЛЬКО на открытую группу
                            // (setGroupRulesEnabled); без открытой группы обе кнопки disabled,
                            // будто правил нет. «Добавить группу» доступна всегда.
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                val groupId = expandedGroupId
                                OutlinedButton(
                                    onClick = { viewModel.setGroupRulesEnabled(groupId!!, false) },
                                    enabled = groupId != null && openGroupRules.any { it.enabled },
                                    modifier = Modifier.weight(1f),
                                ) { Text("Выключить все") }
                                FilledTonalButton(
                                    onClick = { viewModel.setGroupRulesEnabled(groupId!!, true) },
                                    enabled = groupId != null && openGroupRules.any { !it.enabled },
                                    modifier = Modifier.weight(1f),
                                ) { Text("Включить все") }
                            }
                            OutlinedButton(
                                onClick = { creatingGroup = true },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                            ) {
                                Icon(Icons.Filled.CreateNewFolder, contentDescription = null)
                                Spacer(Modifier.size(8.dp))
                                Text("Добавить группу")
                            }
                        }
                    }
                }

                // ИИ-комментарий: аккордеон групп — шапка всегда видна, правила видны только
                // у раскрытой группы. Цветная полоса/индексы цветов считаются глобально по s.rules,
                // чтобы цвета на графике охвата совпадали с карточками.
                displayGroups.forEach { group ->
                    val groupRules = rulesByGroup[group.id] ?: emptyList()
                    val expanded = expandedGroupId == group.id
                    item(key = "group-${group.id}") {
                        GroupHeaderCard(
                            group = group,
                            ruleCount = groupRules.size,
                            expanded = expanded,
                            isStandard = group.id == STANDARD_GROUP_ID,
                            onToggle = { expandedGroupId = if (expanded) null else group.id },
                            onAddRule = {
                                creatingInGroup = group.id
                                expandedGroupId = group.id
                            },
                            onRename = { renamingGroup = group },
                            onDelete = { deletingGroup = group },
                        )
                    }
                    if (expanded) {
                        if (groupRules.isEmpty()) {
                            item(key = "group-${group.id}-empty") {
                                Card(Modifier.fillMaxWidth()) {
                                    Text(
                                        "В группе пока нет правил — всё время покрыто интервалом вне правил.",
                                        Modifier.padding(16.dp),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        } else {
                            itemsIndexed(groupRules, key = { _, rule -> rule.id }) { _, rule ->
                                val colorIndex = s.rules.indexOfFirst { it.id == rule.id }.coerceAtLeast(0)
                                RuleCard(
                                    rule = rule,
                                    colorIndex = colorIndex,
                                    selectionMode = mergeSelection != null,
                                    selected = rule.id in (mergeSelection ?: emptySet()),
                                    canMerge = s.rules.size >= 2,
                                    onToggle = { viewModel.setRuleEnabled(rule.id, it) },
                                    onClick = {
                                        if (mergeSelection != null) {
                                            mergeSelection = mergeSelection?.let { sel ->
                                                if (rule.id in sel) sel - rule.id else sel + rule.id
                                            }
                                        } else {
                                            editing = rule
                                        }
                                    },
                                    onSplit = { viewModel.splitRule(rule) },
                                    onDuplicate = { viewModel.duplicateRule(rule) },
                                    onMerge = { mergeSelection = setOf(rule.id) },
                                    highlighted = rule.id == highlightedRuleId,
                                )
                            }
                        }
                    }
                }

                item {
                    FallbackCard(
                        fallback = s.fallback,
                        onChange = viewModel::setFallback,
                    )
                }
                item { Spacer(Modifier.size(88.dp)) }
            }
        }
    }

    creatingInGroup?.let { targetGroup ->
        // ИИ-комментарий: умный дефолт нового правила — старт с конца ряда активных
        // (max end среди enabled; при всех выключенных — текущее время), длина 30 мин.
        // Заморожено remember(targetGroup): считается один раз при открытии диалога.
        val (suggestedStart, suggestedEnd) = remember(targetGroup) {
            val nowMinute = LocalTime.now().let { it.hour * 60 + it.minute }
            suggestNewRuleRange(s?.rules.orEmpty(), nowMinute)
        }
        RuleEditorDialog(
            initial = ScheduleRule(
                id = viewModel.newRuleId(),
                startMinuteOfDay = suggestedStart,
                endMinuteOfDay = suggestedEnd,
                intervalMinutes = 10,
                groupId = targetGroup,
            ),
            groups = displayGroups,
            onSave = {
                viewModel.saveRule(it)
                creatingInGroup = null
            },
            onDelete = null,
            onDismiss = { creatingInGroup = null },
        )
    }

    editing?.let { rule ->
        RuleEditorDialog(
            initial = rule,
            groups = displayGroups,
            onSave = {
                viewModel.saveRule(it)
                editing = null
            },
            onDelete = {
                viewModel.deleteRule(rule.id)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }

    if (creatingGroup) {
        GroupNameDialog(
            title = "Новая группа",
            onDismiss = { creatingGroup = false },
            onConfirm = {
                viewModel.createGroup(it)
                creatingGroup = false
            },
        )
    }
    renamingGroup?.let { group ->
        GroupNameDialog(
            title = "Переименовать группу",
            initial = group.name,
            onDismiss = { renamingGroup = null },
            onConfirm = {
                viewModel.renameGroup(group.id, it)
                renamingGroup = null
            },
        )
    }
    deletingGroup?.let { group ->
        AlertDialog(
            onDismissRequest = { deletingGroup = null },
            title = { Text("Удалить группу?") },
            text = {
                Text("Группа «${group.name}» будет удалена, а её правила перейдут в «Стандартную» (не потеряются).")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteGroup(group.id)
                    if (expandedGroupId == group.id) expandedGroupId = null
                    deletingGroup = null
                }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { deletingGroup = null }) { Text("Отмена") }
            },
        )
    }
    if (showMoveDialog && mergeSelection != null) {
        // ИИ-комментарий: диалог массового перемещения выбранных правил (пункт «В группу…»).
        // После переноса выбор сбрасывается, а целевая группа раскрывается, чтобы пользователь
        // сразу видел результат (правила «уехали» из текущей группы).
        GroupPickerDialog(
            title = "Переместить в группу",
            groups = displayGroups,
            counts = rulesByGroup.mapValues { it.value.size },
            preselectedId = expandedGroupId,
            onDismiss = { showMoveDialog = false },
            onConfirm = { target ->
                viewModel.moveRulesToGroup(mergeSelection!!, target)
                mergeSelection = null
                showMoveDialog = false
                expandedGroupId = target
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CoverageTimeline(
    rules: List<ScheduleRule>,
    fallback: FallbackConfig,
    onRuleClick: (ScheduleRule) -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val currentMinute = LocalTime.now().let { it.hour * 60 + it.minute }
    val labels = listOf("00", "03", "06", "09", "12", "15", "18", "21")

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Охват времени", style = MaterialTheme.typography.titleMedium)
        Canvas(
             modifier = Modifier
                 .fillMaxWidth()
                 .height(28.dp)
                 .pointerInput(rules) {
                     detectTapGestures { offset ->
                         val minute = ((offset.x / size.width) * 1440).toInt().coerceIn(0, 1439)
                         rules.firstOrNull { rule ->
                             if (!rule.enabled || rule.coversWholeDay) {
                                 false
                             } else if (rule.crossesMidnight) {
                                 rule.startMinuteOfDay <= minute || minute < rule.endMinuteOfDay
                             } else {
                                 rule.startMinuteOfDay <= minute && minute < rule.endMinuteOfDay
                             }
                         }?.let(onRuleClick)
                     }
                 },
         ) {
            val height = size.height
            val width = size.width
            val cornerRadius = 4.dp.toPx()
            val backgroundColor = if (fallback.silent) {
                colorScheme.surfaceVariant.copy(alpha = 0.4f)
            } else {
                colorScheme.secondaryContainer
            }
            drawRoundRect(
                color = backgroundColor,
                size = size,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius),
            )

            fun DrawScope.drawSegment(start: Int, end: Int, rule: ScheduleRule, index: Int) {
                val left = (start / 1440f) * width
                val right = (end / 1440f) * width
                if (right <= left) return
                if (rule.silent) {
                    drawRect(
                        color = colorScheme.outline.copy(alpha = 0.55f),
                        topLeft = Offset(left, 0f),
                        size = Size(right - left, height),
                    )
                    clipRect(left, 0f, right, height) {
                        val step = 6.dp.toPx()
                        var x = left - height
                        while (x < right) {
                            drawLine(
                                color = colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                start = Offset(x, height),
                                end = Offset(x + height, 0f),
                                strokeWidth = 2.dp.toPx(),
                            )
                            x += step
                        }
                    }
                } else {
                    drawRect(
                        color = ruleColor(index),
                        topLeft = Offset(left, 0f),
                        size = Size(right - left, height),
                    )
                }
            }

            rules.withIndex().toList().asReversed().forEach { (index, rule) ->
                if (!rule.enabled || rule.coversWholeDay) return@forEach
                if (rule.crossesMidnight) {
                    drawSegment(rule.startMinuteOfDay, 1440, rule, index)
                    drawSegment(0, rule.endMinuteOfDay, rule, index)
                } else {
                    drawSegment(rule.startMinuteOfDay, rule.endMinuteOfDay, rule, index)
                }
            }

            val currentX = (currentMinute / 1440f) * width
            drawLine(
                color = colorScheme.primary,
                start = Offset(currentX, -4.dp.toPx()),
                end = Offset(currentX, height + 4.dp.toPx()),
                strokeWidth = 2.dp.toPx(),
            )
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp),
        ) {
            val tickHeight = 6.dp.toPx()
            val strokeWidth = 1.dp.toPx()
            (0..8).forEach { index ->
                val x = (index / 8f) * size.width
                drawLine(
                    color = colorScheme.outlineVariant,
                    start = Offset(x, 0f),
                    end = Offset(x, tickHeight),
                    strokeWidth = strokeWidth,
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            labels.forEach { label ->
                Box(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelSmall, color = colorScheme.onSurfaceVariant)
                }
            }
            Text("24", style = MaterialTheme.typography.labelSmall, color = colorScheme.onSurfaceVariant)
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            listOf(
                ruleColor(0) to "правила озвучки",
                colorScheme.outline to "молчание",
                colorScheme.secondaryContainer to "вне правил · ${if (fallback.silent) "молчание" else intervalText(fallback.intervalMinutes)}",
            ).forEach { (color, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(color, CircleShape))
                    Spacer(Modifier.size(4.dp))
                    Text(label, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/**
 * ИИ-комментарий: шапка именованной группы правил (аккордеон).
 * - Тап по всей карточке переключает раскрытость: открыта максимум одна группа, остальные
 *   закрываются (логика в RulesScreen через expandedGroupId); свернутые группы скрывают свои
 *   правила (по требованию «по дефолту скрыты»).
 */
@Composable
private fun GroupHeaderCard(
    group: RuleGroup,
    ruleCount: Int,
    expanded: Boolean,
    isStandard: Boolean,
    onToggle: () -> Unit,
    onAddRule: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (expanded) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Folder,
                contentDescription = null,
                tint = if (expanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(group.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (ruleCount == 0) "пустая" else "правил: $ruleCount",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Свернуть" else "Развернуть",
            )
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Меню группы")
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Добавить правило сюда") },
                        leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                        onClick = {
                            showMenu = false
                            onAddRule()
                        },
                    )
                    )
                    if (!isStandard) {
                        DropdownMenuItem(
                            text = { Text("Переименовать") },
                            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                onRename()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Удалить группу") },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                onDelete()
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * ИИ-комментарий: диалог ввода имени группы (создание/переименование).
 * Пустое имя — кнопка подтверждения disabled; пробелы по краям trim'ятся при сохранении.
 * Лимит 60 символов дублирует trim/take в RulesViewModel (защита и в UI, и в данных).
 */
@Composable
private fun GroupNameDialog(
    title: String,
    initial: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val valid = text.trim().isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(60) },
                label = { Text("Название группы") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onConfirm(text.trim()) }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

/**
 * ИИ-комментарий: диалог выбора целевой группы для массового перемещения правил
 * (пункт «В группу…» в панели режима выбора). Показывает все группы со счётчиками правил;
 * предвыбрана текущая открытая группа (preselectedId), если она есть в списке, иначе Стандартная.
 * Подтверждение возвращает id выбранной группы в onConfirm.
 */
@Composable
private fun GroupPickerDialog(
    title: String,
    groups: List<RuleGroup>,
    counts: Map<Long, Int> = emptyMap(),
    preselectedId: Long? = null,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    val initial = if (groups.any { it.id == preselectedId }) preselectedId!! else STANDARD_GROUP_ID
    var selected by remember(groups) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                groups.forEach { group ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = group.id },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selected == group.id,
                            onClick = { selected = group.id },
                        )
                        Spacer(Modifier.size(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(group.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if ((counts[group.id] ?: 0) == 0) "пустая" else "правил: ${counts[group.id]}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) { Text("Переместить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RuleCard(
    rule: ScheduleRule,
    colorIndex: Int,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    canMerge: Boolean = true,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
    onSplit: () -> Unit,
    onDuplicate: () -> Unit = {},
    onMerge: () -> Unit = {},
    highlighted: Boolean = false,
) {
    val pulse = rememberInfiniteTransition(label = "blink")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(450, easing = LinearEasing),
            RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    var showMenu by remember { mutableStateOf(false) }
    Box {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = if (selectionMode) onClick else ({ showMenu = true }),
                ),
        border = when {
            selected -> BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
            highlighted -> BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha))
            else -> null
        },
        colors = if (rule.silent) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Row {
            if (!rule.silent) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(4.dp)
                        .background(
                            ruleColor(colorIndex).copy(alpha = if (rule.enabled) 1f else 0.35f)
                        )
                )
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (rule.silent) Icons.Filled.Bedtime else Icons.Filled.Schedule,
                    contentDescription = null,
                    tint = if (rule.silent) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        ruleTimeRangeText(rule),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        buildString {
                            append(if (rule.silent) "молчать" else intervalText(rule.intervalMinutes))
                            if (rule.oneShot) append(" · одноразовое")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (selectionMode) {
                    Checkbox(checked = selected, onCheckedChange = { onClick() })
                } else {
                    Switch(checked = rule.enabled, onCheckedChange = onToggle)
                }
            }
        }
        }
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
        ) {
            DropdownMenuItem(
                text = { Text("Раздвоить") },
                leadingIcon = { Icon(Icons.Filled.CallSplit, contentDescription = null) },
                enabled = rule.durationMinutes >= 2,
                onClick = {
                    showMenu = false
                    onSplit()
                },
            )
            DropdownMenuItem(
                text = { Text("Дублировать") },
                leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                onClick = {
                    showMenu = false
                    onDuplicate()
                },
            )
            DropdownMenuItem(
                text = { Text("Объединить…") },
                leadingIcon = { Icon(Icons.Filled.CallMerge, contentDescription = null) },
                enabled = canMerge,
                onClick = {
                    showMenu = false
                    onMerge()
                },
            )
        }
    }
}

@Composable
private fun FallbackCard(fallback: FallbackConfig, onChange: (FallbackConfig) -> Unit) {
    // ИИ-комментарий: testTag нужен для автоскролла-проверки и будущей UI-верификации:
    // кнопка «Правила озвучки» на Главной скроллит список именно к этой карточке.
    Card(Modifier.fillMaxWidth().testTag("fallback_card")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Вне правил", style = MaterialTheme.typography.titleMedium)
            Text(
                "Как озвучивать время, не покрытое правилами.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(if (fallback.silent) "Молчать" else intervalText(fallback.intervalMinutes))
                Switch(
                    checked = !fallback.silent,
                    onCheckedChange = { onChange(fallback.copy(silent = !it)) },
                )
            }
            if (!fallback.silent) {
                IntervalChips(
                    current = fallback.intervalMinutes,
                    onSelect = { onChange(fallback.copy(intervalMinutes = it)) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IntervalChips(current: Int, onSelect: (Int) -> Unit) {
    var showCustom by remember { mutableStateOf(false) }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        val presets = listOf(1, 2, 5, 10, 15, 30, 60)
        val values = if (current in presets) presets else presets + current
        values.forEach { minutes ->
            AssistChip(
                onClick = { onSelect(minutes) },
                label = { Text(intervalText(minutes)) },
                leadingIcon = if (current == minutes) {
                    { Icon(Icons.Filled.Schedule, contentDescription = null, Modifier.size(16.dp)) }
                } else null,
            )
        }
        AssistChip(
            onClick = { showCustom = true },
            label = { Text("Своё…") },
            leadingIcon = { Icon(Icons.Filled.Tune, contentDescription = null, Modifier.size(16.dp)) },
        )
    }
    if (showCustom) {
        CustomIntervalDialog(
            initial = current,
            onDismiss = { showCustom = false },
            onConfirm = {
                onSelect(it)
                showCustom = false
            },
        )
    }
}

@Composable
private fun CustomIntervalDialog(initial: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var text by remember { mutableStateOf(initial.toString()) }
    val parsed = text.toIntOrNull()
    val valid = parsed != null && parsed in 1..1440
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Свой интервал") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter { c -> c.isDigit() }.take(4) },
                label = { Text("Интервал, минут") },
                supportingText = {
                    Text(
                        buildString {
                            if (valid) {
                                append(intervalText(parsed!!))
                                intervalGridHint(parsed)?.let {
                                    append("\n")
                                    append(it)
                                }
                            } else {
                                append("число от 1 до 1440")
                            }
                        },
                    )
                },
                isError = !valid,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onConfirm(parsed!!) }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleEditorDialog(
    initial: ScheduleRule,
    onSave: (ScheduleRule) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
    // ИИ-комментарий: список групп для селектора «Группа» (Стандартная + кастомные).
    // Дефолт — только Стандартная, чтобы превью/старые вызовы не ломались.
    groups: List<RuleGroup> = listOf(RuleGroup(STANDARD_GROUP_ID, STANDARD_GROUP_NAME)),
) {
    var rule by remember { mutableStateOf(initial) }
    var pickStart by remember { mutableStateOf(false) }
    var pickEnd by remember { mutableStateOf(false) }
    var intervalTextValue by remember { mutableStateOf(rule.intervalMinutes.toString()) }
    // ИИ-комментарий: раскрыт ли дропдаун выбора группы; выбранная группа хранится прямо
    // в rule.groupId (копия initial). Неизвестный groupId (битая ссылка) показываем как
    // Стандартную, сохраняем как есть (ViewModel.setRuleGroup при необходимости нормализует).
    var groupMenu by remember { mutableStateOf(false) }
    val parsedInterval = intervalTextValue.toIntOrNull()
    val intervalValid = parsedInterval != null && parsedInterval in 1..1440
    val interval = if (intervalValid) parsedInterval!! else rule.intervalMinutes
    val selectedGroupName = groups.firstOrNull { it.id == rule.groupId }?.name
        ?: STANDARD_GROUP_NAME

    fun current(): ScheduleRule = rule.copy(
        intervalMinutes = interval.coerceIn(1, 1440),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (onDelete == null) "Новое правило" else "Правило озвучки") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Box(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = minuteToText(rule.startMinuteOfDay),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Начало") },
                        trailingIcon = { Icon(Icons.Filled.Schedule, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Box(
                        Modifier
                            .matchParentSize()
                            .clickable { pickStart = true }
                    )
                }
                Box(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = minuteToText(rule.endMinuteOfDay),
                        onValueChange = {},
                        readOnly = true,
                        label = {
                            Text(if (rule.crossesMidnight) "Конец (на следующий день)" else "Конец")
                        },
                        trailingIcon = { Icon(Icons.Filled.Schedule, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Box(
                        Modifier
                            .matchParentSize()
                            .clickable { pickEnd = true }
                    )
                }
                OutlinedTextField(
                    value = intervalTextValue,
                    onValueChange = { intervalTextValue = it.filter { c -> c.isDigit() }.take(4) },
                    label = { Text("Интервал, минут") },
                    supportingText = {
                        Text(
                            buildString {
                                when {
                                    rule.silent -> append("не используется")
                                    intervalValid -> {
                                        append(intervalText(interval))
                                        intervalGridHint(interval)?.let {
                                            append("\n")
                                            append(it)
                                        }
                                    }
                                    else -> append("число от 1 до 1440")
                                }
                            },
                        )
                    },
                    isError = !rule.silent && !intervalValid,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!rule.silent) {
                    IntervalChips(
                        current = interval,
                        onSelect = { intervalTextValue = it.toString() },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Молчать в этом диапазоне")
                    Switch(checked = rule.silent, onCheckedChange = { rule = rule.copy(silent = it) })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Одноразовое срабатывание")
                        Text(
                            "Правило удалится через минуту после окончания периода",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = rule.oneShot, onCheckedChange = { rule = rule.copy(oneShot = it) })
                }
                // ИИ-комментарий: селектор группы правила (аккордеон). Список = Стандартная +
                // кастомные; выбор сразу пишется в rule.groupId и уйдёт в onSave → ViewModel.saveRule.
                Box(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = selectedGroupName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Группа") },
                        trailingIcon = { Icon(Icons.Filled.Folder, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Box(
                        Modifier
                            .matchParentSize()
                            .clickable { groupMenu = true }
                    )
                    DropdownMenu(
                        expanded = groupMenu,
                        onDismissRequest = { groupMenu = false },
                    ) {
                        groups.forEach { g ->
                            DropdownMenuItem(
                                text = { Text(g.name) },
                                onClick = {
                                    rule = rule.copy(groupId = g.id)
                                    groupMenu = false
                                },
                            )
                        }
                    }
                }
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = null)
                        Spacer(Modifier.size(4.dp))
                        Text("Удалить правило")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = rule.silent || intervalValid,
                onClick = { onSave(current()) },
            ) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )

    if (pickStart) {
        TimePickDialog(
            initialMinute = rule.startMinuteOfDay,
            title = "Начало",
            onDismiss = { pickStart = false },
        ) { m ->
            rule = rule.copy(startMinuteOfDay = m)
            pickStart = false
        }
    }
    if (pickEnd) {
        TimePickDialog(
            initialMinute = rule.endMinuteOfDay,
            title = "Конец",
            onDismiss = { pickEnd = false },
        ) { m ->
            rule = rule.copy(endMinuteOfDay = m)
            pickEnd = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickDialog(
    initialMinute: Int,
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = initialMinute / 60,
        initialMinute = initialMinute % 60,
        is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}
