package com.dragonfly.talkingclock.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.dragonfly.talkingclock.diag.DiagLogger

/**
 * Transient audio focus for a single utterance: interrupts (pauses) every parallel audio
 * for the few seconds the time is spoken, then releases. Focus loss is logged but never
 * interrupts our own playback — the announcement must be audible.
 */
class AudioFocusHelper(
    context: Context,
    private val diag: DiagLogger,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var request: AudioFocusRequest? = null

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        val name = when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> "LOSS"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "LOSS_TRANSIENT"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "LOSS_TRANSIENT_CAN_DUCK"
            AudioManager.AUDIOFOCUS_GAIN -> "GAIN"
            else -> change.toString()
        }
        diag.log(TAG, "focus change: $name")
    }

    /** Requests AUDIOFOCUS_GAIN_TRANSIENT for the given usage. Returns false when declined. */
    fun acquire(usage: Int): Boolean {
        val attributes = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        return if (Build.VERSION.SDK_INT >= 26) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(listener, Handler(Looper.getMainLooper()))
                .build()
            this.request = request
            audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            val stream = if (usage == AudioAttributes.USAGE_NOTIFICATION) {
                AudioManager.STREAM_NOTIFICATION
            } else {
                AudioManager.STREAM_MUSIC
            }
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(listener, stream, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) ==
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    fun release() {
        request?.let { audioManager.abandonAudioFocusRequest(it) }
        request = null
    }

    companion object {
        private const val TAG = "AudioFocus"
    }
}
