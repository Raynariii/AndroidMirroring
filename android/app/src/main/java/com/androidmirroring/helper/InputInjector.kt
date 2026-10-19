package com.androidmirroring.helper

import android.hardware.input.InputManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import java.lang.reflect.Method

/** Injects touch, scroll and key events system-wide. Needs the shell uid (INJECT_EVENTS). */
class InputInjector {
    private val manager: Any
    private val injectMethod: Method

    init {
        manager = try {
            // Android 14+ moved the injection entry point to InputManagerGlobal.
            Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null)!!
        } catch (e: ReflectiveOperationException) {
            InputManager::class.java.getDeclaredMethod("getInstance").invoke(null)!!
        }
        injectMethod = manager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.java)
    }

    private var display = DisplayInfoReader.read()
    private var downTime = 0L
    private val touchProps = arrayOf(MotionEvent.PointerProperties().apply {
        id = 0
        toolType = MotionEvent.TOOL_TYPE_FINGER
    })
    private val touchCoords = arrayOf(MotionEvent.PointerCoords().apply {
        pressure = 1f
        size = 1f
    })
    private val keyMap: KeyCharacterMap by lazy { KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD) }

    private fun inject(event: InputEvent): Boolean = injectMethod.invoke(manager, event, INJECT_MODE_ASYNC) as Boolean

    /** [action] is a MotionEvent action; x and y are normalized to 0..1 of the current screen. */
    fun touch(action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) {
            display = DisplayInfoReader.read()
            downTime = now
        }
        touchCoords[0].x = x.coerceIn(0f, 1f) * display.width
        touchCoords[0].y = y.coerceIn(0f, 1f) * display.height
        val event = MotionEvent.obtain(
            downTime, now, action, 1, touchProps, touchCoords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        inject(event)
        event.recycle()
    }

    /** Positive [vertical] scrolls content up (wheel forward), matching AXIS_VSCROLL. */
    fun scroll(x: Float, y: Float, horizontal: Float, vertical: Float) {
        val now = SystemClock.uptimeMillis()
        val d = DisplayInfoReader.read()
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x.coerceIn(0f, 1f) * d.width
            this.y = y.coerceIn(0f, 1f) * d.height
            setAxisValue(MotionEvent.AXIS_HSCROLL, horizontal)
            setAxisValue(MotionEvent.AXIS_VSCROLL, vertical)
        })
        val event = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_SCROLL, 1, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0,
        )
        inject(event)
        event.recycle()
    }

    fun key(keyCode: Int, metaState: Int) {
        val now = SystemClock.uptimeMillis()
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            inject(KeyEvent(now, now, action, keyCode, 0, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD))
        }
    }

    /**
     * Spike-only text path: works for characters the virtual key map can produce (mostly ASCII).
     * Any-language input goes through the Mirror IME in a later spike.
     */
    fun text(text: String) {
        val events = keyMap.getEvents(text.toCharArray())
        if (events == null) {
            Ln.i("text: cannot type \"$text\" with key events (needs the Mirror IME)")
            return
        }
        events.forEach { inject(it) }
    }

    private companion object {
        const val INJECT_MODE_ASYNC = 0
    }
}
