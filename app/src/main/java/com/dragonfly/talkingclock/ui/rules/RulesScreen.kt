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
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dragonfly.talkingclock.data.FallbackConfig
import com.dragonfly.talkingclock.data.ScheduleRule
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
fun RulesScreen(viewModel: RulesViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ScheduleRule?>(null) }
    var creatingNew by remember { mutableStateOf(false) }
    var highlightedRuleId by remember { mutableStateOf<Long?>(null) }
    var mergeSelection by remember { mutableStateOf<Set<Long>?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(highlightedRuleId) {
        if (highlightedRuleId != null) {
            delay(3000)
            highlightedRuleId = null
        }
    }

    val s = settings
    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creatingNew = true },
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
                            val index = s.rules.indexOfFirst { it.id == rule.id }
                            if (index >= 0) {
                                highlightedRuleId = rule.id
                                scope.launch { listState.animateScrollToItem(2 + index) }
                            }
                        },
                    )
                }
                item {
                    Column(Modifier.padding(top = 16.dp)) {
                        Text("Правила озвучки", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Правила применяются по порядку списка. Диапазоны — полуоткрытые [начало, конец), время позже начала может пересекать полночь.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (mergeSelection != null) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "Выбрано: ${mergeSelection!!.size}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { mergeSelection = null }) { Text("Отмена") }
                                Button(
                                    enabled = mergeSelection!!.size >= 2,
                                    onClick = {
                                        viewModel.mergeRules(mergeSelection!!)
                                        mergeSelection = null
                                    },
                                ) { Text("Объединить") }
                            }
                        } else if (s.rules.isNotEmpty()) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                OutlinedButton(
                                    onClick = { viewModel.setAllRulesEnabled(false) },
                                    enabled = s.rules.any { it.enabled },
                                    modifier = Modifier.weight(1f),
                                ) { Text("Выключить все") }
                                FilledTonalButton(
                                    onClick = { viewModel.setAllRulesEnabled(true) },
                                    enabled = s.rules.any { !it.enabled },
                                    modifier = Modifier.weight(1f),
                                ) { Text("Включить все") }
                            }
                        }
                    }
                }

                itemsIndexed(s.rules, key = { _, rule -> rule.id }) { index, rule ->
                    RuleCard(
                        rule = rule,
                        colorIndex = index,
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

                if (s.rules.isEmpty()) {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Text(
                                "Правил пока нет — всё время покрыто интервалом вне правил.",
                                Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )
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

    if (creatingNew) {
        val nowMinute = LocalTime.now().let { it.hour * 60 + it.minute }
        RuleEditorDialog(
            initial = ScheduleRule(
                id = viewModel.newRuleId(),
                startMinuteOfDay = nowMinute,
                endMinuteOfDay = (nowMinute + 30) % 1440,
                intervalMinutes = 10,
            ),
            onSave = {
                viewModel.saveRule(it)
                creatingNew = false
            },
            onDelete = null,
            onDismiss = { creatingNew = false },
        )
    }

    editing?.let { rule ->
        RuleEditorDialog(
            initial = rule,
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
    Card(Modifier.fillMaxWidth()) {
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
) {
    var rule by remember { mutableStateOf(initial) }
    var pickStart by remember { mutableStateOf(false) }
    var pickEnd by remember { mutableStateOf(false) }
    var intervalTextValue by remember { mutableStateOf(rule.intervalMinutes.toString()) }
    val parsedInterval = intervalTextValue.toIntOrNull()
    val intervalValid = parsedInterval != null && parsedInterval in 1..1440
    val interval = if (intervalValid) parsedInterval!! else rule.intervalMinutes

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
