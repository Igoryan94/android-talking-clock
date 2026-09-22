package com.dragonfly.talkingclock.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.dragonfly.talkingclock.domain.ScheduleEvaluator
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun HomeScreen(
    onOpenRules: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val nextSpeak by viewModel.nextSpeak.collectAsStateWithLifecycle()
    var showExactDialog by remember { mutableStateOf(false) }

    val s = settings
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (s == null) {
            CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        } else {
            MasterCard(
                enabled = s.masterEnabled,
                nextSpeak = nextSpeak,
                onToggle = viewModel::setMasterEnabled,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FilledTonalButton(onClick = viewModel::speakNow) {
                    Icon(Icons.Filled.RocketLaunch, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Произнести сейчас")
                }
                OutlinedButton(onClick = onOpenRules) {
                    Text("Правила озвучки")
                }
            }

            if (!viewModel.exactAlarmGranted) {
                ExactAlarmCard(onOpenSettings = { showExactDialog = true })
            }
        }
    }

    if (showExactDialog) {
        AlertDialog(
            onDismissRequest = { showExactDialog = false },
            icon = { Icon(Icons.Filled.Alarm, contentDescription = null) },
            title = { Text("Точные будильники") },
            text = {
                Text(
                    "Разрешите TalkClock использовать точные будильники, чтобы время озвучивалось точно по расписанию. " +
                        "Без этого озвучка может опаздывать на несколько минут."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showExactDialog = false
                    viewModel.openExactAlarmSettings()
                }) { Text("Открыть настройки") }
            },
            dismissButton = {
                TextButton(onClick = { showExactDialog = false }) { Text("Позже") }
            },
        )
    }
}

@Composable
private fun MasterCard(
    enabled: Boolean,
    nextSpeak: ScheduleEvaluator.NextSpeak?,
    onToggle: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Говорящие часы",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (enabled) "работают" else "остановлены",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    )
                }
                Switch(checked = enabled, onCheckedChange = onToggle)
            }

            if (enabled) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.GraphicEq,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.size(8.dp))
                    val text = nextSpeak?.let {
                        val time = Instant.ofEpochMilli(it.atEpochMinute * 60_000L)
                            .atZone(ZoneId.systemDefault())
                        val formatter = DateTimeFormatter.ofPattern("HH:mm")
                        "Следующая озвучка: ${formatter.format(time)}" +
                            (if (it.isFallback) " (базовый интервал)" else "")
                    } ?: "Расписание не охватывает будущее время"
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun ExactAlarmCard(onOpenSettings: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Alarm, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(
                    "Нет разрешения на точные будильники",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(
                "Озвучка может опаздывать. Разрешите точные будильники в настройках системы.",
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onOpenSettings) { Text("Открыть настройки") }
        }
    }
}
