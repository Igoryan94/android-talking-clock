package com.dragonfly.talkingclock.speech

import android.content.Context
import android.media.AudioManager
import com.dragonfly.talkingclock.data.VolumeGuardConfig
import com.dragonfly.talkingclock.diag.DiagLogger
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Temporarily raises below-threshold stream volumes just for one announcement.
 *
 * While [VolumeGuardConfig] is active, both MEDIA and NOTIFICATION streams are checked; any of
 * them below [VolumeGuardConfig.thresholdPercent] is bumped up for the duration of the utterance
 * and then restored, so time announcements stay audible even when the user keeps their own
 * media/notification volume at zero.
 *
 * Some OEM ROMs (notably ColorOS/Realme) silently ignore `setStreamVolume` from background
 * services. To stay robust the raise is applied on the main thread, verified by re-reading the
 * stream index, retried a few times, and finally stepped up with `adjustStreamVolume` — the same
 * path the hardware volume keys use. Every attempt is logged to diag.log.
 */
class VolumeGuard(
    context: Context,
    private val diag: DiagLogger,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /**
     * Raises any below-threshold stream and returns a restore action, or null when the guard is
     * inactive / every stream is already loud enough (or the raise was fully blocked).
     */
    suspend fun apply(config: VolumeGuardConfig, nowEpochMinute: Long): (suspend () -> Unit)? {
        if (!config.activeAt(nowEpochMinute)) return null

        val changed = mutableListOf<Pair<Int, Int>>()
        for (stream in STREAMS) {
            val max = audioManager.getStreamMaxVolume(stream)
            if (max <= 0) continue
            val target = (max * config.thresholdPercent / 100f).roundToInt().coerceIn(0, max)
            val current = audioManager.getStreamVolume(stream)
            if (current >= target) {
                diag.log(TAG, "${name(stream)}=$current already >= target $target")
                continue
            }
            val result = raiseTo(stream, target, current)
            diag.log(TAG, "${name(stream)}: $current -> $target (raised=${result != null})")
            if (result != null) changed += stream to current
        }
        if (changed.isEmpty()) return null

        diag.log(TAG, "volume guard raised ${changed.map { name(it.first) }} to ${config.thresholdPercent}%")
        return { restore(changed) }
    }

    /**
     * Tries to reach [target]: direct `setStreamVolume` on the main thread (verified, retried),
     * then `adjustStreamVolume(ADJUST_RAISE)` steps. Returns the reached index, or null if no
     * progress could be made.
     */
    private suspend fun raiseTo(stream: Int, target: Int, original: Int): Int? {
        repeat(SET_ATTEMPTS) { attempt ->
            if (setStreamVolume(stream, target)) return target
            diag.log(TAG, "${name(stream)} set #${attempt + 1} ignored (still ${readVolume(stream)})")
            if (attempt < SET_ATTEMPTS - 1) delay(RETRY_DELAY_MILLIS)
        }

        var reached = readVolume(stream)
        var steps = 0
        while (reached < target && steps < MAX_ADJUST_STEPS) {
            val next = withContext(Dispatchers.Main) {
                audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, 0)
                audioManager.getStreamVolume(stream)
            }
            if (next <= reached) break // no progress — blocked too
            reached = next
            steps++
        }
        return reached.takeIf { it > original }
    }

    /** Restores previously raised streams to their original indices; runs even if cancelled. */
    private suspend fun restore(changed: List<Pair<Int, Int>>) {
        withContext(NonCancellable + Dispatchers.Main) {
            for ((stream, original) in changed) {
                audioManager.setStreamVolume(stream, original, 0)
                val actual = audioManager.getStreamVolume(stream)
                if (actual != original) {
                    diag.log(TAG, "${name(stream)} restore to $original NOT applied (still $actual)")
                }
            }
            diag.log(TAG, "volume guard restored ${changed.map { name(it.first) }}")
        }
    }

    private suspend fun setStreamVolume(stream: Int, target: Int): Boolean =
        withContext(Dispatchers.Main) {
            audioManager.setStreamVolume(stream, target, 0)
            audioManager.getStreamVolume(stream) == target
        }

    private suspend fun readVolume(stream: Int): Int =
        withContext(Dispatchers.Main) { audioManager.getStreamVolume(stream) }

    private fun name(stream: Int): String = when (stream) {
        AudioManager.STREAM_MUSIC -> "MEDIA"
        AudioManager.STREAM_NOTIFICATION -> "NOTIFICATION"
        else -> "stream$stream"
    }

    companion object {
        private const val TAG = "VolumeGuard"
        private const val SET_ATTEMPTS = 3
        private const val RETRY_DELAY_MILLIS = 120L
        private const val MAX_ADJUST_STEPS = 160
        private val STREAMS = intArrayOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_NOTIFICATION)
    }
}
