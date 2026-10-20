package com.androidmirroring.helper

import android.content.ClipData
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Types text that key events can't produce (Cyrillic, accents, emoji, any language) by putting it
 * on the phone clipboard and sending Paste. The user's own clipboard is put back once typing pauses.
 */
class ClipboardPaste(private val injector: InputInjector) {
    private val clipboard: Any by lazy { SystemServices.get("clipboard", "android.content.IClipboard") }
    private val restorer = Executors.newSingleThreadScheduledExecutor { Thread(it, "clipboard-restore").apply { isDaemon = true } }
    private var saved: ClipData? = null
    private var restoring = false
    private var pendingRestore: ScheduledFuture<*>? = null

    @Synchronized
    fun type(text: String) {
        pendingRestore?.cancel(false)
        if (!restoring) {
            saved = read()
            restoring = true
        }
        write(ClipData.newPlainText("androidmirroring", text))
        injector.key(KEYCODE_PASTE, 0)
        pendingRestore = restorer.schedule({ restore() }, RESTORE_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    private fun restore() {
        saved?.let { write(it) }
        saved = null
        restoring = false
    }

    private fun read(): ClipData? = try {
        SystemServices.call(clipboard, "getPrimaryClip", ints = listOf(0, 0), strings = listOf(SystemServices.SHELL_PACKAGE, null)) as ClipData?
    } catch (e: Exception) {
        Ln.i("clipboard: cannot read ($e); it will not be restored")
        null
    }

    private fun write(clip: ClipData) {
        SystemServices.call(clipboard, "setPrimaryClip", objects = listOf(clip), ints = listOf(0, 0), strings = listOf(SystemServices.SHELL_PACKAGE, null))
    }

    private companion object {
        const val KEYCODE_PASTE = 279
        const val RESTORE_DELAY_MS = 800L
    }
}
