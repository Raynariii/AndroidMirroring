package com.androidmirroring.activation

import java.io.DataInputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One ADB transport message: 24-byte little-endian header plus payload. */
class AdbMessage(val command: Int, val arg0: Int, val arg1: Int, val payload: ByteArray = ByteArray(0)) {

    fun writeTo(out: OutputStream) {
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command)
            .putInt(arg0)
            .putInt(arg1)
            .putInt(payload.size)
            .putInt(checksum(payload))
            .putInt(command.inv())
        out.write(header.array())
        out.write(payload)
        out.flush()
    }

    override fun toString() = "${name(command)}($arg0, $arg1, ${payload.size} bytes)"

    companion object {
        const val HEADER_SIZE = 24

        const val A_CNXN = 0x4e584e43
        const val A_OPEN = 0x4e45504f
        const val A_OKAY = 0x59414b4f
        const val A_CLSE = 0x45534c43
        const val A_WRTE = 0x45545257
        const val A_AUTH = 0x48545541
        const val A_STLS = 0x534c5453

        const val A_VERSION = 0x01000001
        const val A_STLS_VERSION = 0x01000000
        const val MAX_PAYLOAD = 1024 * 1024

        fun readFrom(input: DataInputStream): AdbMessage {
            val header = ByteArray(HEADER_SIZE).also { input.readFully(it) }
            val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val command = buf.int
            val arg0 = buf.int
            val arg1 = buf.int
            val length = buf.int
            buf.int // checksum, not verified (adbd stops sending it from A_VERSION 0x01000001)
            val magic = buf.int
            require(magic == command.inv()) { "bad ADB message magic" }
            require(length in 0..MAX_PAYLOAD) { "bad ADB payload length $length" }
            val payload = ByteArray(length).also { input.readFully(it) }
            return AdbMessage(command, arg0, arg1, payload)
        }

        fun checksum(data: ByteArray): Int = data.fold(0) { sum, b -> sum + (b.toInt() and 0xff) }

        fun name(command: Int): String {
            val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(command).array()
            return String(b, Charsets.US_ASCII)
        }
    }
}
