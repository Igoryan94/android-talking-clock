package com.dragonfly.talkingclock.speech

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.dragonfly.talkingclock.data.AudioStream
import com.dragonfly.talkingclock.data.TtsSettings
import com.dragonfly.talkingclock.diag.DiagLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
    suspend fun speak(text: String, settings: TtsSettings): Boolean = initMutex.withLock {
        val engine = getInitialized(settings.enginePackage).getOrElse {
            return@withLock false
        }
        engine.setSpeechRate(settings.speechRate)
        engine.setPitch(1f)
        val usage = if (settings.audioStream == AudioStream.MEDIA) {
            AudioAttributes.USAGE_MEDIA
        } else {
            AudioAttributes.USAGE_NOTIFICATION
        }
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(usage)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )

        diag.log(TAG, "utterance start: \"$text\"")
        val startedAt = System.currentTimeMillis()
        val finished = withTimeoutOrNull(UTTERANCE_TIMEOUT_SECONDS * 1000L) {
            suspendCancellableCoroutine { cont ->
                val id = "tc_${utteranceCounter++}"
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == id) cont.resume(true)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (utteranceId == id) cont.resume(false)
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        if (utteranceId == id) cont.resume(false)
                    }
                })
                val resultCode = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
                if (resultCode != TextToSpeech.SUCCESS) {
                    cont.resume(false)
                }
            }
        }
        val ok = finished == true
        diag.log(TAG, "utterance finished ok=$ok in ${System.currentTimeMillis() - startedAt}ms")
        ok
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        initializedEngine = null
    }

    companion object {
        private const val TAG = "TtsManager"
        private const val INIT_TIMEOUT_SECONDS = 15L
        private const val UTTERANCE_TIMEOUT_SECONDS = 60L
    }
}
