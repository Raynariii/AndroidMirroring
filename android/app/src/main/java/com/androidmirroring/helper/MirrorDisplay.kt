package com.androidmirroring.helper

import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.IBinder
import android.view.Surface

/**
 * A display that mirrors the main display into [surface]. Tries the hidden
 * DisplayManager.createVirtualDisplay(name, w, h, displayIdToMirror, surface) first (Android 12+),
 * then falls back to a SurfaceControl display bound to the main layer stack.
 *
 * The SurfaceControl display is created with secure=false on purpose: FLAG_SECURE windows
 * (banking, password managers) must render black in the mirror, as the spec requires.
 */
class MirrorDisplay private constructor(
    private val virtualDisplay: VirtualDisplay?,
    private val token: IBinder?,
) {
    fun release() {
        virtualDisplay?.release()
        token?.let { surfaceControl.getMethod("destroyDisplay", IBinder::class.java).invoke(null, it) }
    }

    companion object {
        private val surfaceControl: Class<*> by lazy { Class.forName("android.view.SurfaceControl") }

        fun create(surface: Surface, display: DisplayState, width: Int, height: Int): MirrorDisplay {
            try {
                val method = DisplayManager::class.java.getMethod(
                    "createVirtualDisplay",
                    String::class.java, Int::class.java, Int::class.java, Int::class.java, Surface::class.java,
                )
                val vd = method.invoke(null, "androidmirroring", width, height, 0, surface) as VirtualDisplay
                Ln.i("capture: DisplayManager mirror display ${width}x$height")
                return MirrorDisplay(vd, null)
            } catch (e: Exception) {
                Ln.i("capture: DisplayManager mirror unavailable ($e), falling back to SurfaceControl")
            }

            val sc = surfaceControl
            val token = sc.getMethod("createDisplay", String::class.java, Boolean::class.java)
                .invoke(null, "androidmirroring", false) as IBinder
            sc.getMethod("openTransaction").invoke(null)
            try {
                sc.getMethod("setDisplaySurface", IBinder::class.java, Surface::class.java)
                    .invoke(null, token, surface)
                sc.getMethod("setDisplayProjection", IBinder::class.java, Int::class.java, Rect::class.java, Rect::class.java)
                    .invoke(null, token, 0, Rect(0, 0, display.width, display.height), Rect(0, 0, width, height))
                sc.getMethod("setDisplayLayerStack", IBinder::class.java, Int::class.java)
                    .invoke(null, token, display.layerStack)
            } finally {
                sc.getMethod("closeTransaction").invoke(null)
            }
            Ln.i("capture: SurfaceControl display ${width}x$height")
            return MirrorDisplay(null, token)
        }
    }
}
