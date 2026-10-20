package com.androidmirroring.helper

import android.view.MotionEvent
import java.io.DataInputStream
import java.io.EOFException
import java.nio.ByteBuffer

/** Reads Mac -> helper messages until the connection closes. */
class ControlReceiver(
    private val input: DataInputStream,
    private val writer: FrameWriter,
    private val streamer: VideoStreamer,
    private val injector: InputInjector,
) {
    fun loop() {
        while (true) {
            val type = try {
                input.readUnsignedByte()
            } catch (e: EOFException) {
                return
            }
            val length = input.readInt()
            require(length in 0..Msg.MAX_CONTROL_PAYLOAD) { "control message too large: $length" }
            val payload = ByteArray(length).also { input.readFully(it) }
            try {
                handle(type, ByteBuffer.wrap(payload), payload)
            } catch (e: Exception) {
                Ln.e("control message 0x${type.toString(16)} failed", e)
            }
        }
    }

    private fun handle(type: Int, buf: ByteBuffer, raw: ByteArray) {
        when (type) {
            Msg.TOUCH -> {
                val action = when (buf.get().toInt()) {
                    0 -> MotionEvent.ACTION_DOWN
                    1 -> MotionEvent.ACTION_UP
                    else -> MotionEvent.ACTION_MOVE
                }
                injector.touch(action, buf.float, buf.float)
            }
            Msg.SCROLL -> injector.scroll(buf.float, buf.float, buf.float, buf.float)
            Msg.KEY -> injector.key(buf.int, buf.int)
            Msg.TEXT -> injector.text(String(raw))
            Msg.PANEL -> PanelPower.set(buf.get().toInt() != 0)
            Msg.PING -> {
                val echo = buf.long
                val now = System.nanoTime() / 1000
                writer.send(Msg.PONG, ByteBuffer.allocate(16).putLong(echo).putLong(now).array())
            }
            Msg.REQUEST_KEYFRAME -> streamer.requestKeyframe()
            Msg.SWIPE -> injector.swipe(buf.float, buf.float, buf.float, buf.float, buf.int)
            Msg.ROTATE -> Rotation.toggle()
            else -> Ln.i("ignoring unknown message 0x${type.toString(16)}")
        }
    }
}
