package com.androidmirroring.helper

import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * Spike wire format (see protocol/spike-wire-format.md): every message is
 * `type: u8, length: u32 BE, payload`. All integers are big-endian.
 */
object Msg {
    // Helper -> Mac
    const val HELLO_ACK = 0x01
    const val VIDEO_CONFIG = 0x02
    const val VIDEO_FRAME = 0x03
    const val PONG = 0x04
    const val LOG = 0x05

    // Mac -> helper
    const val HELLO = 0x10
    const val TOUCH = 0x11
    const val SCROLL = 0x12
    const val KEY = 0x13
    const val TEXT = 0x14
    const val PANEL = 0x15
    const val PING = 0x16
    const val REQUEST_KEYFRAME = 0x17
    const val SWIPE = 0x18

    const val MAX_CONTROL_PAYLOAD = 64 * 1024
}

class FrameWriter(private val out: OutputStream) {
    private val lock = Any()

    fun send(type: Int, payload: ByteArray = EMPTY) {
        synchronized(lock) {
            out.write(ByteBuffer.allocate(5).put(type.toByte()).putInt(payload.size).array())
            out.write(payload)
            out.flush()
        }
    }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}

/** Logs to the adb shell and, once a Mac is connected, to the Mac viewer. */
object Ln {
    @Volatile
    var remote: FrameWriter? = null

    fun i(message: String) {
        println("[helper] $message")
        try {
            remote?.send(Msg.LOG, message.toByteArray())
        } catch (_: Exception) {
            // The connection is going away; stdout still has the message.
        }
    }

    fun e(message: String, t: Throwable? = null) {
        i("ERROR $message${t?.let { ": $it" } ?: ""}")
        t?.printStackTrace()
    }
}
