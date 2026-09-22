package com.dragonfly.talkingclock.ui.rules

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dragonfly.talkingclock.data.FallbackConfig
import com.dragonfly.talkingclock.data.ScheduleRule
import java.util.Locale

private fun minuteToText(m: Int): String = "%02d:%02d".format(Locale.ROOT, m / 60, m % 60)

private fun plural(n: Int, one: String, few: String, many: String): String {
    val mod100 = n % 100
    val mod10 = n % 10
    return when {
        mod10 == 1 && mod100 != 11 -> one
        mod10 in 2..4 && mod100 !in 12..14 -> few
        else -> many
    }
}

private fun intervalText(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 1 && m == 0 -> "каждый час"
        h > 1 && m == 0 -> "каждые $h ${plural(h, "час", "часа", "часов")}"
        h == 0 && m == 30 -> "каждые полчаса"
        h == 0 && m == 15 -> "каждые четверть часа"
        h == 0 -> "кажд${plural(m, "ую", "ые", "ые")} $m ${plural(m, "минуту", "минуты", "минут")}"
        else -> "каждые $h ч $m ${plural(m, "мин", "мин", "мин")}"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen(viewModel: RulesViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ScheduleRule?>(null) }
    var creatingNew by remember { mutableStateOf(false) }

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
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Column(Modifier.padding(top = 16.dp)) {
                        Text("Правила озвучки", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Правила применяются по порядку списка. Диапазоны — полуоткрытые [начало, конец), время позже начала может пересекать полночь.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                items(s.rules, key = { it.id }) { rule ->
                    RuleCard(
                        rule = rule,
                        onToggle = { viewModel.setRuleEnabled(rule.id, it) },
                        onClick = { editing = rule },
                    )
                }

                if (s.rules.isEmpty()) {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Text(
                                "Правил пока нет — вне правил действует базовый интервал.",
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
        RuleEditorDialog(
            initial = ScheduleRule(id = viewModel.newRuleId(), startMinuteOfDay = 9 * 60, endMinuteOfDay = 10 * 60, intervalMinutes = 10),
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

@Composable
private fun RuleCard(rule: ScheduleRule, onToggle: (Boolean) -> Unit, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = if (rule.silent) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
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
                    "${minuteToText(rule.startMinuteOfDay)} – ${minuteToText(rule.endMinuteOfDay)}" +
                        if (rule.crossesMidnight) " (через полночь)" else "",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    if (rule.silent) "молчать" else intervalText(rule.intervalMinutes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = rule.enabled, onCheckedChange = onToggle)
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
                    Text(if (valid) intervalText(parsed!!) else "число от 1 до 1440")
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

    fun current(): ScheduleRule = rule.copy(intervalMinutes = interval.coerceIn(1, 1440))

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
                            when {
                                rule.silent -> "не используется"
                                intervalValid -> intervalText(interval)
                                else -> "число от 1 до 1440"
                            }
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
