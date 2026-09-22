package com.dragonfly.talkingclock.scheduling

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.dragonfly.talkingclock.data.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Single source of truth for background scheduling state:
 * mirrors settings changes to the exact alarm and the keep-alive worker.
 */
@Singleton
class SchedulingCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: SettingsRepository,
    private val alarmScheduler: AlarmScheduler,
) {
    private var started = false

    /** Starts observing settings; safe to call multiple times. */
    fun start(scope: CoroutineScope) {
        if (started) return
        started = true
        scope.launch {
            repository.settings
                .distinctUntilChanged()
                .collectLatest { apply(it.masterEnabled) }
        }
    }

    suspend fun syncNow() {
        val settings = repository.currentSettings()
        apply(settings.masterEnabled)
    }

    private suspend fun apply(masterEnabled: Boolean) {
        val workManager = WorkManager.getInstance(context)
        if (masterEnabled) {
            alarmScheduler.reschedule()
            workManager.enqueueUniquePeriodicWork(
                KEEP_ALIVE_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<KeepAliveWorker>(15, TimeUnit.MINUTES).build(),
            )
        } else {
            alarmScheduler.cancel()
            repository.setPendingTrigger(null)
            workManager.cancelUniqueWork(KEEP_ALIVE_WORK)
        }
    }

    companion object {
        private const val KEEP_ALIVE_WORK = "talkclock_keep_alive"
    }
}
