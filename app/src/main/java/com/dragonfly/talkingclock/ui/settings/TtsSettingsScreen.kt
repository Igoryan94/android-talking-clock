package com.dragonfly.talkingclock.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import com.dragonfly.talkingclock.data.AudioStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TtsSettingsScreen(viewModel: TtsSettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val engines by viewModel.engines.collectAsStateWithLifecycle()
    val isSpeaking by viewModel.isSpeaking.collectAsStateWithLifecycle()

    val s = settings
    if (s == null) {
        CircularProgressIndicator(Modifier.padding(24.dp))
        return
    }
    val tts = s.tts

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Голос и озвучка", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)

        // Engine picker
        var engineMenuOpen by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = engineMenuOpen,
            onExpandedChange = { engineMenuOpen = it },
        ) {
            OutlinedTextField(
                value = tts.enginePackage?.let { pkg ->
                    engines.firstOrNull { it.name == pkg }?.label ?: pkg
                } ?: "Системный движок",
                onValueChange = {},
                readOnly = true,
                label = { Text("Движок TTS") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = engineMenuOpen) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
            )
            DropdownMenu(
                expanded = engineMenuOpen,
                onDismissRequest = { engineMenuOpen = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Системный движок") },
                    onClick = {
                        viewModel.updateTts { it.copy(enginePackage = null) }
                        engineMenuOpen = false
                    },
                )
                engines.forEach { engine ->
                    DropdownMenuItem(
                        text = { Text(engine.label ?: engine.name) },
                        onClick = {
                            viewModel.updateTts { it.copy(enginePackage = engine.name) }
                            engineMenuOpen = false
                        },
                    )
                }
            }
        }

        // Audio stream
        Column {
            Text("Аудиопоток", style = MaterialTheme.typography.titleSmall)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = tts.audioStream == AudioStream.MEDIA,
                    onClick = { viewModel.updateTts { it.copy(audioStream = AudioStream.MEDIA) } },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text("Медиа") }
                SegmentedButton(
                    selected = tts.audioStream == AudioStream.NOTIFICATION,
                    onClick = { viewModel.updateTts { it.copy(audioStream = AudioStream.NOTIFICATION) } },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text("Уведомления") }
            }
        }

        // Rate
        Column {
            Text(
                "Скорость речи: ${"%.2f".format(tts.speechRate)}×",
                style = MaterialTheme.typography.titleSmall,
            )
            Slider(
                value = tts.speechRate,
                onValueChange = { v -> viewModel.updateTts { it.copy(speechRate = (v * 100).toInt() / 100f) } },
                valueRange = 0.5f..2f,
                steps = 29,
            )
            Button(onClick = viewModel::testVoice, enabled = !isSpeaking) {
                if (isSpeaking) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    Text("Говорю…")
                } else {
                    Text("Проверить голос")
                }
            }
        }

        // Phrase settings
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Формирование фразы", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = tts.prefix,
                    onValueChange = { v -> viewModel.updateTts { it.copy(prefix = v) } },
                    label = { Text("Префикс (до времени)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = tts.postfix,
                    onValueChange = { v -> viewModel.updateTts { it.copy(postfix = v) } },
                    label = { Text("Постфикс (после времени)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = tts.timeTemplate,
                    onValueChange = { v -> viewModel.updateTts { it.copy(timeTemplate = v) } },
                    label = { Text("Шаблон времени") },
                    supportingText = { Text("Токены: H — час, HH — час с нулём, h/hh — 12ч, m — минута, mm — с нулём") },
                    modifier = Modifier.fillMaxWidth(),
                )
                val preview = viewModel.previewText(tts)
                if (preview.isNotBlank()) {
                    Text(
                        "Пример: «$preview»",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        // Regex
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Regex-обработка (опционально)", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = tts.regexPattern,
                    onValueChange = { v -> viewModel.updateTts { it.copy(regexPattern = v) } },
                    label = { Text("Регулярное выражение") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = tts.regexReplacement,
                    onValueChange = { v -> viewModel.updateTts { it.copy(regexReplacement = v) } },
                    label = { Text("Замена ($1 — группа)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                val error = viewModel.regexError(tts)
                if (error != null) {
                    Text(
                        "Ошибка в выражении: $error",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        // Missed announcements
        MissedAnnounceSwitch(viewModel)

        Spacer(Modifier.size(48.dp))
    }
}

@Composable
private fun MissedAnnounceSwitch(viewModel: TtsSettingsViewModel) {
    // Handled via repository update because it belongs to AppSettings
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val s = settings ?: return
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Извинения за пропуски", style = MaterialTheme.typography.titleSmall)
                Text(
                    "«Озвучка была пропущена… ошибка будет исправлена»",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = s.announceMissed,
                onCheckedChange = { viewModel.setAnnounceMissed(it) },
            )
        }
    }
}
