package com.dragonfly.talkingclock.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.dragonfly.talkingclock.data.AudioStream
import com.dragonfly.talkingclock.data.TtsSettings
import com.dragonfly.talkingclock.data.VolumeGuardConfig
import com.dragonfly.talkingclock.diag.DiagLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Single utterance pipeline with MultiTTS-friendly recovery.
 *
 * MultiTTS (and some other engines) occasionally deny playback to one of several concurrent
 * TTS clients: the utterance is queued but never starts. The pipeline therefore:
 *  1. requests transient audio focus (interrupting parallel audio so time is clearly audible);
 *  2. watches for utterance start with a short watchdog;
 *  3. on a hang: retries after stop(), re-initializes the engine client, and finally
 *     falls back to another installed engine so the time is never silently lost.
 */
@Singleton
class TtsManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val diag: DiagLogger,
) {
    private var tts: TextToSpeech? = null
    private var initializedEngine: String? = null
    private val initMutex = Mutex()

    private var listTts: TextToSpeech? = null
    private val listMutex = Mutex()
    private var utteranceCounter = 0

    /** Returns engines installed on the device (label + package). */
    suspend fun availableEngines(): List<TextToSpeech.EngineInfo> = withContext(Dispatchers.IO) {
        listMutex.withLock {
            if (listTts == null) {
                listTts = TextToSpeech(context) { }
            }
            listTts?.engines.orEmpty().sortedBy { it.label?.lowercase() }
        }
    }

    /**
     * Blocking engine init; must run off the main thread because the OnInit callback is
     * delivered on the main thread (see [speak]).
     * Retries on every call — a previous failure is never cached.
     */
    private fun getInitializedBlocking(enginePackage: String?): Result<TextToSpeech> {
        if (tts != null && initializedEngine == enginePackage) {
            return Result.success(tts!!)
        }
        var created: TextToSpeech? = null
        val startedAt = System.currentTimeMillis()
        val result = runCatching {
            val latch = CountDownLatch(1)
            var initStatus = TextToSpeech.ERROR
            val instance = if (enginePackage == null) {
                TextToSpeech(context) { status -> initStatus = status; latch.countDown() }
            } else {
                TextToSpeech(context, { status -> initStatus = status; latch.countDown() }, enginePackage)
            }
            created = instance
            latch.await(INIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (initStatus != TextToSpeech.SUCCESS) {
                error("TTS init failed (status=$initStatus, waited=${System.currentTimeMillis() - startedAt}ms)")
            }
            instance
        }
        if (result.isSuccess) {
            tts?.shutdown()
            tts = result.getOrThrow()
            initializedEngine = enginePackage
            diag.log(TAG, "init OK engine=${enginePackage ?: "system"} in ${System.currentTimeMillis() - startedAt}ms")
        } else {
            created?.shutdown()
            diag.log(TAG, "init FAILED engine=${enginePackage ?: "system"}: ${result.exceptionOrNull()?.message}")
        }
        return result
    }

    private suspend fun getInitialized(enginePackage: String?): Result<TextToSpeech> =
        withContext(Dispatchers.IO) { getInitializedBlocking(enginePackage) }

    /**
     * Speaks [text] with the given settings; suspends until the utterance finishes.
     * Returns true on success, false on failure/timeout.
     */
    suspend fun speak(
        text: String,
        settings: TtsSettings,
        volumeGuardConfig: VolumeGuardConfig? = null,
    ): Boolean = initMutex.withLock {
        val usage = if (settings.audioStream == AudioStream.MEDIA) {
            AudioAttributes.USAGE_MEDIA
        } else {
            AudioAttributes.USAGE_NOTIFICATION
        }
        diag.log(TAG, "speak requested: \"$text\" engine=${settings.enginePackage ?: "system"} stream=${settings.audioStream}")

        // Engine init may take seconds — do it before touching audio focus.
        val engine = ensureEngineWithFallback(settings.enginePackage)
            ?: return@withLock false

        val focus = AudioFocusHelper(context, diag)
        val volumeGuard = volumeGuardConfig?.let { VolumeGuard(context, diag) }
        var volumeRestore: (suspend () -> Unit)? = null
        try {
            if (!focus.acquire(usage)) {
                diag.log(TAG, "audio focus NOT granted — speaking anyway")
            }
            // Raise below-threshold streams only after holding focus, for the announcement only.
            volumeGuardConfig?.let { cfg ->
                volumeRestore = volumeGuard?.apply(cfg, System.currentTimeMillis() / 60_000L)
            }
            var outcome = speakAttempt(engine, text, settings, usage)
            if (outcome == Outcome.START_TIMEOUT || outcome == Outcome.ERROR) {
                diag.log(TAG, "attempt 1 failed ($outcome) — retrying after stop()")
                engine.stop()
                outcome = speakAttempt(engine, text, settings, usage)
            }
            if (outcome == Outcome.START_TIMEOUT || outcome == Outcome.ERROR) {
                diag.log(TAG, "attempt 2 failed ($outcome) — re-initializing engine client")
                shutdown()
                val fresh = getInitialized(settings.enginePackage).getOrNull()
                if (fresh != null) {
                    outcome = speakAttempt(fresh, text, settings, usage)
                }
            }
            if (outcome == Outcome.DONE) return@withLock true

            // Last resort: another engine so the time is never silently lost.
            val fallback = pickFallbackEngine(settings.enginePackage) ?: return@withLock false
            diag.log(TAG, "engine ${settings.enginePackage ?: "system"} unusable — falling back to $fallback")
            val fallbackInstance = getInitialized(fallback).getOrNull() ?: return@withLock false
            speakAttempt(fallbackInstance, text, settings, usage) == Outcome.DONE
        } finally {
            volumeRestore?.invoke()
            focus.release()
        }
    }

    /** Initialized configured engine; on init failure immediately tries another engine. */
    private suspend fun ensureEngineWithFallback(enginePackage: String?): TextToSpeech? {
        getInitialized(enginePackage).getOrNull()?.let { return it }
        val fallback = pickFallbackEngine(enginePackage) ?: return null
        diag.log(TAG, "engine ${enginePackage ?: "system"} init failed — trying $fallback")
        return getInitialized(fallback).getOrNull()
    }

    /** Another installed engine to try when the configured one hangs/fails. */
    private suspend fun pickFallbackEngine(current: String?): String? = withContext(Dispatchers.IO) {
        val installed = availableEngines().map { it.name }
        if (installed.isEmpty()) return@withContext null
        val systemDefault = runCatching {
            Settings.Secure.getString(context.contentResolver, "tts_default_synth")
        }.getOrNull()
        when (current) {
            null -> installed.firstOrNull { it != systemDefault }
                ?: "com.google.android.tts".takeIf { it in installed && it != systemDefault }
            else -> systemDefault?.takeIf { it != current && it in installed }
                ?: "com.google.android.tts".takeIf { it != current && it in installed }
                ?: installed.firstOrNull { it != current }
        }
    }

    private sealed interface UtteranceEvent {
        object Started : UtteranceEvent
        data class Finished(val ok: Boolean) : UtteranceEvent
    }

    private sealed interface StartPhase {
        object Started : StartPhase
        data class EarlyFinish(val ok: Boolean) : StartPhase
    }

    private enum class Outcome { DONE, START_TIMEOUT, TOTAL_TIMEOUT, ERROR }

    /** One speak() call with a start watchdog; see class docs for the hang scenario. */
    private suspend fun speakAttempt(
        engine: TextToSpeech,
        text: String,
        settings: TtsSettings,
        usage: Int,
    ): Outcome {
        val id = "tc_${utteranceCounter++}"
        engine.setSpeechRate(settings.speechRate)
        engine.setPitch(1f)
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(usage)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )

        val events = Channel<UtteranceEvent>(Channel.UNLIMITED)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (utteranceId == id) events.trySend(UtteranceEvent.Started)
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId == id) events.trySend(UtteranceEvent.Finished(true))
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId == id) events.trySend(UtteranceEvent.Finished(false))
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId == id) events.trySend(UtteranceEvent.Finished(false))
            }
        })

        // Legacy stream param: engines such as MultiTTS route by KEY_PARAM_STREAM, ignoring
        // AudioAttributes — without it the Notification stream plays at Media volume on BT.
        val params = Bundle().apply {
            putInt(
                TextToSpeech.Engine.KEY_PARAM_STREAM,
                if (usage == AudioAttributes.USAGE_NOTIFICATION) {
                    AudioManager.STREAM_NOTIFICATION
                } else {
                    AudioManager.STREAM_MUSIC
                },
            )
            // Max engine gain; combined with the raised stream volume this keeps the
            // announcement as loud as the stream allows.
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f)
        }

        val startedAt = System.currentTimeMillis()
        val resultCode = engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, id)
        if (resultCode != TextToSpeech.SUCCESS) {
            diag.log(TAG, "utterance $id rejected (rc=$resultCode)")
            events.close()
            return Outcome.ERROR
        }

        val phase: StartPhase? = withTimeoutOrNull(START_TIMEOUT_MILLIS) {
            while (true) {
                when (val e = events.receive()) {
                    UtteranceEvent.Started -> return@withTimeoutOrNull StartPhase.Started
                    is UtteranceEvent.Finished -> return@withTimeoutOrNull StartPhase.EarlyFinish(e.ok)
                }
            }
            @Suppress("UNREACHABLE_CODE")
            StartPhase.Started
        }
        return when (phase) {
            null -> {
                diag.log(TAG, "utterance $id: START TIMEOUT after ${System.currentTimeMillis() - startedAt}ms — engine hung")
                Outcome.START_TIMEOUT
            }

            is StartPhase.EarlyFinish -> {
                val ok = phase.ok
                diag.log(TAG, "utterance $id: finished ok=$ok in ${System.currentTimeMillis() - startedAt}ms")
                if (ok) Outcome.DONE else Outcome.ERROR
            }

            StartPhase.Started -> {
                val done = withTimeoutOrNull(UTTERANCE_TIMEOUT_MILLIS) {
                    while (true) {
                        when (val e = events.receive()) {
                            UtteranceEvent.Started -> Unit
                            is UtteranceEvent.Finished -> return@withTimeoutOrNull e
                        }
                    }
                    @Suppress("UNREACHABLE_CODE")
                    UtteranceEvent.Finished(false)
                }
                when {
                    done == null -> {
                        diag.log(TAG, "utterance $id: TOTAL TIMEOUT (started but never finished)")
                        Outcome.TOTAL_TIMEOUT
                    }

                    done.ok -> {
                        diag.log(TAG, "utterance $id: done ok=true in ${System.currentTimeMillis() - startedAt}ms")
                        Outcome.DONE
                    }

                    else -> {
                        diag.log(TAG, "utterance $id: done ok=false in ${System.currentTimeMillis() - startedAt}ms")
                        Outcome.ERROR
                    }
                }
            }
        }
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        initializedEngine = null
    }

    companion object {
        private const val TAG = "TtsManager"
        private const val INIT_TIMEOUT_SECONDS = 15L
        private const val UTTERANCE_TIMEOUT_MILLIS = 60_000L

        /** Watchdog for "queued but never started" hangs (MultiTTS multi-client conflict). */
        private const val START_TIMEOUT_MILLIS = 4_000L
    }
}
