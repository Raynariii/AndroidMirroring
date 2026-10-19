package com.androidmirroring.helper

import android.os.Binder
import android.view.KeyEvent
import kotlin.concurrent.thread

/**
 * Keeps the phone awake for the whole session so mirroring continues with a dark screen: holds a
 * screen wake lock (no sleep timeout) and, if the phone goes to sleep anyway (power button),
 * wakes it again and turns the panel back off.
 */
object KeepAwake {
    private const val SCREEN_BRIGHT_WAKE_LOCK = 0x0000000a
    private const val INVALID_DISPLAY = -1

    private val power: Any by lazy { SystemServices.get("power", "android.os.IPowerManager") }
    private val lock = Binder()
    private var held = false
    private var watchdog: Thread? = null

    fun start(injector: InputInjector) {
        try {
            SystemServices.call(
                power, "acquireWakeLock",
                objects = listOf(lock),
                ints = listOf(SCREEN_BRIGHT_WAKE_LOCK, INVALID_DISPLAY),
                strings = listOf("androidmirroring:session", SystemServices.SHELL_PACKAGE, null),
            )
            held = true
        } catch (e: Exception) {
            Ln.e("keep-awake: wake lock unavailable, relying on wake-ups", e)
        }
        watchdog = thread(name = "keep-awake", isDaemon = true) {
            try {
                while (true) {
                    if (!isInteractive()) {
                        Ln.i("keep-awake: phone went to sleep, waking it")
                        injector.key(KeyEvent.KEYCODE_WAKEUP, 0)
                        // The display manager turns the panel on as it wakes; dim it again after.
                        Thread.sleep(500)
                        if (PanelPower.wantOff) PanelPower.set(false)
                    }
                    Thread.sleep(1000)
                }
            } catch (_: InterruptedException) {
            }
        }
    }

    fun stop() {
        watchdog?.interrupt()
        watchdog = null
        if (held) {
            try {
                SystemServices.call(power, "releaseWakeLock", objects = listOf(lock))
            } catch (e: Exception) {
                Ln.e("keep-awake: release failed", e)
            }
            held = false
        }
    }

    private fun isInteractive(): Boolean = try {
        power.javaClass.getMethod("isInteractive").invoke(power) as Boolean
    } catch (e: Exception) {
        true
    }
}
