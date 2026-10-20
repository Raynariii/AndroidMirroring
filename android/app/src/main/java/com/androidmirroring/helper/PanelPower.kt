package com.androidmirroring.helper

import android.os.Build
import android.os.IBinder

/**
 * Turns the physical panel off while the display keeps composing, so mirroring continues
 * with a dark phone screen. Hidden API; the display-token lookup moved to the system
 * server's DisplayControl class in Android 14.
 */
object PanelPower {
    private const val POWER_MODE_OFF = 0
    private const val POWER_MODE_NORMAL = 2

    private val surfaceControl: Class<*> by lazy { Class.forName("android.view.SurfaceControl") }

    /** What the Mac asked for, so [KeepAwake] can dim the panel again after waking the phone. */
    @Volatile
    var wantOff = false
        private set

    fun set(on: Boolean, quiet: Boolean = false): Boolean {
        wantOff = !on
        val tokens = displayTokens()
        if (tokens.isEmpty()) {
            Ln.i("panel: no physical display token found")
            return false
        }
        val setMode = surfaceControl.getMethod("setDisplayPowerMode", IBinder::class.java, Int::class.java)
        tokens.forEach { setMode.invoke(null, it, if (on) POWER_MODE_NORMAL else POWER_MODE_OFF) }
        if (!quiet) Ln.i("panel: ${if (on) "on" else "off"} (${tokens.size} display(s))")
        return true
    }

    /** Best effort; used on disconnect so the phone is never left dark. */
    fun restore() {
        wantOff = false
        try {
            set(true)
        } catch (e: Exception) {
            Ln.e("panel: restore failed", e)
        }
    }

    private fun displayTokens(): List<IBinder> {
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                return tokensFrom(displayControl())
            } catch (e: Exception) {
                Ln.i("panel: DisplayControl unavailable ($e)")
            }
        }
        try {
            return tokensFrom(surfaceControl)
        } catch (e: Exception) {
            Ln.i("panel: SurfaceControl.getPhysicalDisplayIds unavailable ($e)")
        }
        return try {
            listOf(surfaceControl.getMethod("getInternalDisplayToken").invoke(null) as IBinder)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun tokensFrom(holder: Class<*>): List<IBinder> {
        val ids = holder.getMethod("getPhysicalDisplayIds").invoke(null) as LongArray
        val getToken = holder.getMethod("getPhysicalDisplayToken", Long::class.java)
        return ids.toList().mapNotNull { getToken.invoke(null, it) as IBinder? }
    }

    private fun displayControl(): Class<*> {
        val factory = Class.forName("com.android.internal.os.ClassLoaderFactory")
        val create = factory.getDeclaredMethod(
            "createClassLoader",
            String::class.java, String::class.java, String::class.java, ClassLoader::class.java,
            Int::class.java, Boolean::class.java, String::class.java,
        )
        val loader = create.invoke(
            null, "/system/framework/services.jar", null, null, ClassLoader.getSystemClassLoader(), 0, true, null,
        ) as ClassLoader
        val displayControl = loader.loadClass("com.android.server.display.DisplayControl")
        val loadLibrary = Runtime::class.java.getDeclaredMethod("loadLibrary0", Class::class.java, String::class.java)
        loadLibrary.isAccessible = true
        loadLibrary.invoke(Runtime.getRuntime(), displayControl, "android_servers")
        return displayControl
    }
}
