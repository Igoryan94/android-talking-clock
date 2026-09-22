package com.dragonfly.talkingclock.scheduling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.dragonfly.talkingclock.data.SettingsRepository
import com.dragonfly.talkingclock.diag.DiagLogger
import com.dragonfly.talkingclock.speech.SpeakService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Fired by the exact alarm; speaks the time and re-arms the next alarm. */
@AndroidEntryPoint
class SpeakAlarmReceiver : BroadcastReceiver() {

    @Inject lateinit var repository: SettingsRepository
    @Inject lateinit var alarmScheduler: AlarmScheduler
    @Inject lateinit var diag: DiagLogger

    override fun onReceive(context: Context, intent: Intent) {
        val expectedMinute = intent.getLongExtra(EXTRA_EPOCH_MINUTE, -1L)
        diag.log(TAG, "alarm fired, expected epochMinute=$expectedMinute")

        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            try {
                val settings = repository.currentSettings()
                if (!settings.masterEnabled) {
                    diag.log(TAG, "master switch is off — skipping")
                    return@launch
                }
                val nowEpochMinute = System.currentTimeMillis() / 60_000L
                val late = expectedMinute in 0 until nowEpochMinute - MAX_LATE_MINUTES
                if (late) {
                    // Too old — the KeepAliveWorker handles the missed announcement.
                    diag.log(TAG, "alarm too late ($expectedMinute vs now $nowEpochMinute) — skipping speech")
                } else {
                    repository.setLastTriggered(expectedMinute)
                    val speakIntent = SpeakService.speakTimeIntent(context, expectedMinute)
                    runCatching {
                        if (Build.VERSION.SDK_INT >= 26) {
                            context.startForegroundService(speakIntent)
                        } else {
                            context.startService(speakIntent)
                        }
                    }.onFailure {
                        diag.log(TAG, "failed to start SpeakService: ${it.message}")
                        Log.e(TAG, "Failed to start SpeakService", it)
                    }
                }
                alarmScheduler.reschedule()
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val EXTRA_EPOCH_MINUTE = "epoch_minute"
        private const val TAG = "SpeakAlarmReceiver"
        private const val MAX_LATE_MINUTES = 2L
    }
}
