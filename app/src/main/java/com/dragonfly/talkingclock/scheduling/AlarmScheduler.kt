package com.dragonfly.talkingclock.scheduling

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.dragonfly.talkingclock.data.SettingsRepository
import com.dragonfly.talkingclock.diag.DiagLogger
import com.dragonfly.talkingclock.domain.ScheduleEvaluator
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Owns the single exact alarm that drives time announcements. */
@Singleton
class AlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: SettingsRepository,
    private val diag: DiagLogger,
) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()

    /**
     * Recomputes the next trigger from the current settings and (re)arms the alarm.
     * Cancels the alarm when the app is disabled or nothing will ever be spoken.
     */
    suspend fun reschedule() {
        val settings = repository.currentSettings()
        if (!settings.masterEnabled) {
            cancel()
            return
        }
        val nowEpochMinute = System.currentTimeMillis() / 60_000L
        val next = ScheduleEvaluator.nextSpeakTrigger(
            nowEpochMinute = nowEpochMinute,
            rules = settings.rules,
            fallback = settings.fallback,
        )
        if (next == null) {
            diag.log(TAG, "nothing to speak anymore — cancelling alarm")
            repository.setPendingTrigger(null)
            alarmManager.cancel(alarmPendingIntent())
            return
        }
        repository.setPendingTrigger(next.atEpochMinute)
        val triggerAtMillis = next.atEpochMinute * 60_000L + TRIGGER_OFFSET_MILLIS
        val pendingIntent = alarmPendingIntent(next.atEpochMinute)
        if (canScheduleExact()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        } else {
            alarmManager.setWindow(AlarmManager.RTC_WAKEUP, triggerAtMillis, INEXACT_WINDOW_MILLIS, pendingIntent)
        }
        val nextText = Instant.ofEpochMilli(triggerAtMillis).atZone(ZoneId.systemDefault())
        diag.log(
            TAG,
            "armed next ${if (next.isFallback) "fallback" else "rule"} trigger at $nextText (exact=${canScheduleExact()})"
        )
    }

    fun cancel() {
        alarmManager.cancel(alarmPendingIntent())
    }

    private fun alarmPendingIntent(epochMinute: Long = -1L): PendingIntent {
        val intent = Intent(context, SpeakAlarmReceiver::class.java)
            .putExtra(SpeakAlarmReceiver.EXTRA_EPOCH_MINUTE, epochMinute)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val TAG = "AlarmScheduler"
        private const val REQUEST_CODE = 4001
        private const val TRIGGER_OFFSET_MILLIS = 700L
        private const val INEXACT_WINDOW_MILLIS = 60_000L
    }
}
