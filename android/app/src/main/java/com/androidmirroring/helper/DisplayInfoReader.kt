package com.androidmirroring.helper

/** Logical size of a display, already swapped for the current rotation. */
data class DisplayState(val width: Int, val height: Int, val rotation: Int, val layerStack: Int)

object DisplayInfoReader {
    private val global: Any by lazy {
        Class.forName("android.hardware.display.DisplayManagerGlobal").getMethod("getInstance").invoke(null)!!
    }
    private val getDisplayInfo by lazy { global.javaClass.getMethod("getDisplayInfo", Int::class.java) }

    fun read(displayId: Int = 0): DisplayState {
        val info = getDisplayInfo.invoke(global, displayId)
            ?: error("No display info for display $displayId")
        val c = info.javaClass
        return DisplayState(
            width = c.getField("logicalWidth").getInt(info),
            height = c.getField("logicalHeight").getInt(info),
            rotation = c.getField("rotation").getInt(info),
            layerStack = c.getField("layerStack").getInt(info),
        )
    }
}
