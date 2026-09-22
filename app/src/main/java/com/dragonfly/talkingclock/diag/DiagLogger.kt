package com.dragonfly.talkingclock.diag

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device diagnostics: ColorOS suppresses app logcat output, so key events are also
 * appended to files/diag.log, readable via:
 *   adb shell run-as com.dragonfly.talkingclock cat files/diag.log
 */
@Singleton
class DiagLogger @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun log(tag: String, message: String) {
        Log.i(tag, message)
        runCatching {
            val line = "${LocalDateTime.now().format(FORMATTER)} $tag: $message\n"
            File(context.filesDir, "diag.log").appendText(line)
        }
    }

    companion object {
        private val FORMATTER = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")
    }
}
