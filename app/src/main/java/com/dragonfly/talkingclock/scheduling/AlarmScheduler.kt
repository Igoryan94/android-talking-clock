package com.dragonfly.talkingclock.scheduling

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.dragonfly.talkingclock.data.ScheduleRule
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
        var settings = repository.currentSettings()
        if (!settings.masterEnabled) {
            cancel()
            return
        }
        val nowEpochMinute = System.currentTimeMillis() / 60_000L

        // One-shot rules are spent one minute after their whole period ends, not on first trigger.
        val expired = ScheduleEvaluator.expiredOneShotIds(nowEpochMinute, settings.rules)
        if (expired.isNotEmpty()) {
            repository.update { s -> s.copy(rules = s.rules.filterNot { it.id in expired }) }
            diag.log(TAG, "removed spent one-shot rules: $expired")
            settings = repository.currentSettings()
        }

        // The temporary volume guard switches itself off after its duration.
        if (settings.volumeGuard.enabled && !settings.volumeGuard.activeAt(nowEpochMinute)) {
            diag.log(TAG, "volume guard expired — disabling")
            repository.update { s -> s.copy(volumeGuard = s.volumeGuard.copy(enabled = false, armedAtEpochMinute = 0L)) }
            settings = repository.currentSettings()
        }

        val next = ScheduleEvaluator.nextSpeakTrigger(
            nowEpochMinute = nowEpochMinute,
            rules = settings.rules,
            fallback = settings.fallback,
        )
        if (next == null) {
            diag.log(TAG, "nothing to speak anymore — cancelling alarm")
            repository.setPendingTrigger(null)
            alarmManager.cancel(alarmPendingIntent())
        } else {
            repository.setPendingTrigger(next.atEpochMinute)
            val triggerAtMillis = next.atEpochMinute * 60_000L + TRIGGER_OFFSET_MILLIS
            val pendingIntent = alarmPendingIntent(next.atEpochMinute, next.ruleId)
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

        armOneShotCleanup(nowEpochMinute, settings.rules)
    }

    /** Arms a dedicated alarm that prunes spent one-shot rules exactly one minute after their period. */
    private fun armOneShotCleanup(nowEpochMinute: Long, rules: List<ScheduleRule>) {
        val expiry = ScheduleEvaluator.nextOneShotExpiry(nowEpochMinute, rules)
        if (expiry == null) {
            alarmManager.cancel(cleanupPendingIntent())
            return
        }
        val atMillis = expiry * 60_000L + TRIGGER_OFFSET_MILLIS
        val pendingIntent = cleanupPendingIntent(expiry)
        if (canScheduleExact()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pendingIntent)
        } else {
            alarmManager.setWindow(AlarmManager.RTC_WAKEUP, atMillis, INEXACT_WINDOW_MILLIS, pendingIntent)
        }
        diag.log(TAG, "armed one-shot cleanup at ${Instant.ofEpochMilli(atMillis).atZone(ZoneId.systemDefault())}")
    }

    fun cancel() {
        alarmManager.cancel(alarmPendingIntent())
        alarmManager.cancel(cleanupPendingIntent())
    }

    private fun alarmPendingIntent(epochMinute: Long = -1L, ruleId: Long? = null): PendingIntent {
        val intent = Intent(context, SpeakAlarmReceiver::class.java)
            .putExtra(SpeakAlarmReceiver.EXTRA_EPOCH_MINUTE, epochMinute)
            .putExtra(SpeakAlarmReceiver.EXTRA_RULE_ID, ruleId ?: -1L)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun cleanupPendingIntent(epochMinute: Long = -1L): PendingIntent {
        val intent = Intent(context, SpeakAlarmReceiver::class.java)
            .putExtra(SpeakAlarmReceiver.EXTRA_CLEANUP_ONLY, true)
            .putExtra(SpeakAlarmReceiver.EXTRA_EPOCH_MINUTE, epochMinute)
        return PendingIntent.getBroadcast(
            context,
            CLEANUP_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val TAG = "AlarmScheduler"
        private const val REQUEST_CODE = 4001
        private const val CLEANUP_REQUEST_CODE = 4002
        private const val TRIGGER_OFFSET_MILLIS = 700L
        private const val INEXACT_WINDOW_MILLIS = 60_000L
    }
}
