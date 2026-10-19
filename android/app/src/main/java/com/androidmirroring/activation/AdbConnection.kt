package com.androidmirroring.activation

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket

/**
 * Minimal ADB client for Wireless debugging's TLS port: CNXN, upgrade to TLS on STLS, then run
 * shell commands. Only what the activation flow needs; one stream at a time.
 */
class AdbConnection private constructor(
    private val socket: Socket,
    private val input: DataInputStream,
    private val output: OutputStream,
) : AutoCloseable {
    private var nextLocalId = 1

    /** Runs `shell:<command>` and returns its combined output once the device closes the stream. */
    fun shell(command: String): String {
        val localId = nextLocalId++
        AdbMessage(AdbMessage.A_OPEN, localId, 0, "shell:$command\u0000".toByteArray()).writeTo(output)
        val result = ByteArrayOutputStream()
        var remoteId = 0
        while (true) {
            val message = AdbMessage.readFrom(input)
            when (message.command) {
                AdbMessage.A_OKAY -> remoteId = message.arg0
                AdbMessage.A_WRTE -> {
                    result.write(message.payload)
                    AdbMessage(AdbMessage.A_OKAY, localId, message.arg0).writeTo(output)
                }
                AdbMessage.A_CLSE -> {
                    if (remoteId != 0) AdbMessage(AdbMessage.A_CLSE, localId, remoteId).writeTo(output)
                    return result.toString(Charsets.UTF_8.name())
                }
                else -> error("unexpected $message while running shell command")
            }
        }
    }

    override fun close() {
        socket.close()
    }

    companion object {
        private const val TIMEOUT_MS = 10_000

        fun connect(host: String, port: Int, key: AdbKey): AdbConnection {
            val raw = Socket()
            try {
                raw.tcpNoDelay = true
                raw.connect(InetSocketAddress(host, port), TIMEOUT_MS)
                raw.soTimeout = TIMEOUT_MS

                AdbMessage(AdbMessage.A_CNXN, AdbMessage.A_VERSION, AdbMessage.MAX_PAYLOAD, "host::\u0000".toByteArray())
                    .writeTo(raw.getOutputStream())
                val first = AdbMessage.readFrom(DataInputStream(raw.getInputStream()))
                check(first.command == AdbMessage.A_STLS) {
                    "expected STLS from the Wireless debugging port, got $first"
                }
                AdbMessage(AdbMessage.A_STLS, AdbMessage.A_STLS_VERSION, 0).writeTo(raw.getOutputStream())

                val tls = key.sslContext().socketFactory.createSocket(raw, host, port, true) as SSLSocket
                tls.startHandshake()
                val input = DataInputStream(tls.inputStream.buffered())
                val output = tls.outputStream

                val reply = AdbMessage.readFrom(input)
                if (reply.command == AdbMessage.A_AUTH) {
                    throw IllegalStateException("This device does not know our key yet; pair first")
                }
                check(reply.command == AdbMessage.A_CNXN) { "expected CNXN after TLS, got $reply" }
                return AdbConnection(tls, input, output)
            } catch (e: Exception) {
                raw.close()
                throw e
            }
        }
    }
}
