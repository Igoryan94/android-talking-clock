package com.dragonfly.talkingclock.scheduling

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dragonfly.talkingclock.data.SettingsRepository
import com.dragonfly.talkingclock.diag.DiagLogger
import com.dragonfly.talkingclock.speech.SpeakService
import com.dragonfly.talkingclock.speech.TtsManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.first

/**
 * WorkManager safety net: re-arms the exact alarm if it was lost, and detects missed
 * triggers (device was off / process killed) announcing an apology when enabled.
 */
@HiltWorker
class KeepAliveWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: SettingsRepository,
    private val alarmScheduler: AlarmScheduler,
    private val ttsManager: TtsManager,
    private val diag: DiagLogger,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        diag.log(TAG, "keep-alive check")
        val settings = repository.currentSettings()
        if (!settings.masterEnabled) {
            diag.log(TAG, "master off — nothing to do")
            return Result.success()
        }

        try {
            detectMissedAndApologize(settings.announceMissed)
        } finally {
            alarmScheduler.reschedule()
        }
        return Result.success()
    }

    private suspend fun detectMissedAndApologize(announce: Boolean) {
        val nowEpochMinute = System.currentTimeMillis() / 60_000L
        val pending = repository.pendingTrigger.first()
        val triggered = repository.lastTriggered.first()
        val missedMinute = pending?.takeIf {
            it != triggered && it < nowEpochMinute - MISSED_THRESHOLD_MINUTES
        } ?: return

        diag.log(TAG, "missed trigger detected: $missedMinute (now=$nowEpochMinute)")
        // Mark handled immediately to avoid repeated apologies.
        repository.setLastTriggered(missedMinute)

        if (!announce) return

        val settings = repository.currentSettings()
        val missedTime = Instant.ofEpochMilli(missedMinute * 60_000L)
            .atZone(ZoneId.systemDefault()).toLocalTime()
        val nowTime = Instant.ofEpochMilli(nowEpochMinute * 60_000L)
            .atZone(ZoneId.systemDefault()).toLocalTime()

        val missedText = com.dragonfly.talkingclock.domain.SpeechComposer.compose(missedTime, settings.tts)
        val nowText = com.dragonfly.talkingclock.domain.SpeechComposer.compose(nowTime, settings.tts)
        val apology = "Озвучка времени $missedText была пропущена: приложение было выгружено " +
            "из памяти системой. Ошибка будет исправлена. Сейчас $nowText."

        val started = runCatching {
            applicationContext.startForegroundService(SpeakService.speakIntent(applicationContext, apology))
            true
        }.getOrElse {
            diag.log(TAG, "FGS start failed (${it.message}); speaking in-process")
            false
        }
        if (!started) {
            // FGS start is restricted from the background on Android 12+; speak directly.
            runCatching { ttsManager.speak(apology, settings.tts) }
                .onFailure { diag.log(TAG, "in-process apology failed: ${it.message}") }
        }
    }

    companion object {
        private const val TAG = "KeepAliveWorker"
        private const val MISSED_THRESHOLD_MINUTES = 3L
    }
}
