package com.androidmirroring.helper

/**
 * Rotates the phone between portrait and landscape from the Mac by locking the display rotation,
 * and puts the user's own rotation setting back when the session ends.
 */
object Rotation {
    private const val CALLER = "androidmirroring"

    private val windowManager: Any by lazy { SystemServices.get("window", "android.view.IWindowManager") }

    /** The rotation lock as it was before the first toggle: null if auto-rotate was on. */
    private var original: Int? = null
    private var changed = false

    fun toggle() {
        val current = DisplayInfoReader.read().rotation
        if (!changed) {
            val frozen = windowManager.javaClass.getMethod("isRotationFrozen").invoke(windowManager) as Boolean
            original = if (frozen) current else null
            changed = true
        }
        val target = if (current % 2 == 0) 1 else 0
        freeze(target)
        Ln.i("rotation: $current -> $target")
    }

    /** Best effort; used on disconnect. */
    fun restore() {
        if (!changed) return
        try {
            val lockedAt = original
            if (lockedAt == null) {
                SystemServices.call(windowManager, "thawRotation", strings = listOf(CALLER))
            } else {
                freeze(lockedAt)
            }
        } catch (e: Exception) {
            Ln.e("rotation: restore failed", e)
        }
        changed = false
        original = null
    }

    private fun freeze(rotation: Int) {
        SystemServices.call(windowManager, "freezeRotation", ints = listOf(rotation), strings = listOf(CALLER))
    }
}
