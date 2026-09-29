package com.dragonfly.talkingclock.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "talkclock_settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val MASTER = booleanPreferencesKey("master_enabled")
        val ENGINE = stringPreferencesKey("tts_engine")
        val STREAM = stringPreferencesKey("tts_stream")
        val RATE = floatPreferencesKey("tts_rate")
        val PREFIX = stringPreferencesKey("tts_prefix")
        val POSTFIX = stringPreferencesKey("tts_postfix")
        val TEMPLATE = stringPreferencesKey("tts_time_template")
        val REGEX = stringPreferencesKey("tts_regex")
        val REGEX_REPL = stringPreferencesKey("tts_regex_replacement")
        val RULES = stringPreferencesKey("rules_json")
        val FB_SILENT = booleanPreferencesKey("fallback_silent")
        val FB_INTERVAL = intPreferencesKey("fallback_interval")
        val MISSED_ANNOUNCE = booleanPreferencesKey("missed_announce")
        val GUARD_ENABLED = booleanPreferencesKey("volume_guard_enabled")
        val GUARD_THRESHOLD = intPreferencesKey("volume_guard_threshold")
        val GUARD_DURATION = intPreferencesKey("volume_guard_duration")
        val GUARD_ARMED = longPreferencesKey("volume_guard_armed_at")
        val PENDING = longPreferencesKey("pending_trigger")
        val TRIGGERED = longPreferencesKey("last_triggered")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map(::toSettings)

    suspend fun currentSettings(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            write(transform(toSettings(prefs)), prefs)
        }
    }

    val pendingTrigger: Flow<Long?> = context.dataStore.data.map {
        val v = it[Keys.PENDING]
        if (v == null || v < 0) null else v
    }

    val lastTriggered: Flow<Long?> = context.dataStore.data.map {
        val v = it[Keys.TRIGGERED]
        if (v == null || v < 0) null else v
    }

    suspend fun setPendingTrigger(epochMinute: Long?) {
        context.dataStore.edit { it[Keys.PENDING] = epochMinute ?: -1L }
    }

    suspend fun setLastTriggered(epochMinute: Long?) {
        context.dataStore.edit { it[Keys.TRIGGERED] = epochMinute ?: -1L }
    }

    private fun toSettings(prefs: Preferences): AppSettings = AppSettings(
        masterEnabled = prefs[Keys.MASTER] ?: true,
        tts = TtsSettings(
            enginePackage = prefs[Keys.ENGINE]?.takeIf { it.isNotBlank() },
            audioStream = prefs[Keys.STREAM]?.let { runCatching { AudioStream.valueOf(it) }.getOrNull() }
                ?: AudioStream.MEDIA,
            speechRate = (prefs[Keys.RATE] ?: 1f).coerceIn(0.5f, 2f),
            prefix = prefs[Keys.PREFIX] ?: "",
            postfix = prefs[Keys.POSTFIX] ?: "",
            timeTemplate = prefs[Keys.TEMPLATE]?.takeIf { it.isNotBlank() } ?: "H:mm",
            regexPattern = prefs[Keys.REGEX] ?: "",
            regexReplacement = prefs[Keys.REGEX_REPL] ?: "",
        ),
        rules = prefs[Keys.RULES]?.let(::parseRules) ?: emptyList(),
        fallback = FallbackConfig(
            silent = prefs[Keys.FB_SILENT] ?: false,
            intervalMinutes = (prefs[Keys.FB_INTERVAL] ?: 5).coerceIn(1, 24 * 60),
        ),
        announceMissed = prefs[Keys.MISSED_ANNOUNCE] ?: true,
        volumeGuard = VolumeGuardConfig(
            enabled = prefs[Keys.GUARD_ENABLED] ?: false,
            thresholdPercent = (prefs[Keys.GUARD_THRESHOLD] ?: 30).coerceIn(0, 100),
            durationMinutes = (prefs[Keys.GUARD_DURATION] ?: 180).coerceIn(1, 24 * 60),
            armedAtEpochMinute = prefs[Keys.GUARD_ARMED] ?: 0L,
        ),
    )

    private fun write(s: AppSettings, prefs: androidx.datastore.preferences.core.MutablePreferences) {
        prefs[Keys.MASTER] = s.masterEnabled
        prefs[Keys.ENGINE] = s.tts.enginePackage ?: ""
        prefs[Keys.STREAM] = s.tts.audioStream.name
        prefs[Keys.RATE] = s.tts.speechRate
        prefs[Keys.PREFIX] = s.tts.prefix
        prefs[Keys.POSTFIX] = s.tts.postfix
        prefs[Keys.TEMPLATE] = s.tts.timeTemplate
        prefs[Keys.REGEX] = s.tts.regexPattern
        prefs[Keys.REGEX_REPL] = s.tts.regexReplacement
        prefs[Keys.RULES] = serializeRules(s.rules)
        prefs[Keys.FB_SILENT] = s.fallback.silent
        prefs[Keys.FB_INTERVAL] = s.fallback.intervalMinutes
        prefs[Keys.MISSED_ANNOUNCE] = s.announceMissed
        prefs[Keys.GUARD_ENABLED] = s.volumeGuard.enabled
        prefs[Keys.GUARD_THRESHOLD] = s.volumeGuard.thresholdPercent
        prefs[Keys.GUARD_DURATION] = s.volumeGuard.durationMinutes
        prefs[Keys.GUARD_ARMED] = s.volumeGuard.armedAtEpochMinute
    }
}
