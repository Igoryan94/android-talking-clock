package com.dragonfly.talkingclock.speech

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.dragonfly.talkingclock.data.SettingsRepository
import com.dragonfly.talkingclock.diag.DiagLogger
import com.dragonfly.talkingclock.domain.SpeechComposer
import dagger.hilt.android.AndroidEntryPoint
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Short-lived foreground service that speaks one utterance and stops.
 * Extras:
 *  - EXTRA_TEXT — ready-to-speak text (missed apology, manual speak).
 *  - EXTRA_EPOCH_MINUTE — epoch minute of the trigger; text is composed from it (exact time).
 */
@AndroidEntryPoint
class SpeakService : Service() {

    @Inject lateinit var ttsManager: TtsManager
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var diag: DiagLogger

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT)
        val epochMinute = intent?.getLongExtra(EXTRA_EPOCH_MINUTE, -1L)?.takeIf { it >= 0 }
        diag.log(TAG, "onStart id=$startId text=${text != null} epochMinute=$epochMinute")

        startInForeground()

        serviceScope.launch {
            try {
                val settings = settingsRepository.currentSettings()
                val toSpeak = text ?: run {
                    val time = Instant.ofEpochMilli((epochMinute ?: 0) * 60_000L)
                        .atZone(ZoneId.systemDefault())
                        .toLocalTime()
                    SpeechComposer.compose(time, settings.tts)
                }
                if (toSpeak.isNotBlank()) {
                    val ok = ttsManager.speak(toSpeak, settings.tts, settings.volumeGuard)
                    diag.log(TAG, "speak result ok=$ok")
                } else {
                    diag.log(TAG, "nothing to speak (empty text)")
                }
            } finally {
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startInForeground() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Озвучка времени", NotificationManager.IMPORTANCE_LOW)
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("TalkClock")
            .setContentText("Озвучиваю время")
            .setSilent(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "SpeakService"
        private const val CHANNEL_ID = "speech"
        private const val NOTIFICATION_ID = 1001
        const val EXTRA_TEXT = "text"
        const val EXTRA_EPOCH_MINUTE = "epoch_minute"

        fun speakIntent(context: Context, text: String): Intent =
            Intent(context, SpeakService::class.java).putExtra(EXTRA_TEXT, text)

        fun speakTimeIntent(context: Context, epochMinute: Long): Intent =
            Intent(context, SpeakService::class.java).putExtra(EXTRA_EPOCH_MINUTE, epochMinute)
    }
}
