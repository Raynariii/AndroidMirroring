package com.androidmirroring.helper

import android.os.Build
import android.os.Looper
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlin.concurrent.thread

data class Options(
    val port: Int = 27183,
    val token: String = "",
    val maxSize: Int = 1920,
    val bitRate: Int = 8_000_000,
    val maxFps: Int = 60,
) {
    companion object {
        fun parse(args: Array<String>): Options {
            var o = Options()
            for (arg in args) {
                val (k, v) = arg.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                o = when (k) {
                    "port" -> o.copy(port = v.toInt())
                    "token" -> o.copy(token = v)
                    "max_size" -> o.copy(maxSize = v.toInt())
                    "bit_rate" -> o.copy(bitRate = v.toInt())
                    "max_fps" -> o.copy(maxFps = v.toInt())
                    else -> error("unknown option $k")
                }
            }
            if (o.token.isEmpty()) {
                val bytes = ByteArray(8).also { SecureRandom().nextBytes(it) }
                o = o.copy(token = bytes.joinToString("") { "%02x".format(it) })
            }
            return o
        }
    }
}

/**
 * M0 spike helper. Started from a computer with:
 *   adb shell CLASSPATH=/data/local/tmp/androidmirroring.apk app_process / com.androidmirroring.helper.HelperMain
 * Runs as the shell uid, listens on TCP, serves one Mac at a time.
 *
 * Spike only: plain TCP guarded by a shared token, no encryption. The product design
 * (BLE discovery, Noise, WebRTC) replaces this transport in M1.
 */
object HelperMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val options = Options.parse(args)
        @Suppress("DEPRECATION")
        Looper.prepareMainLooper()
        Workarounds.apply()
        Ln.i("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        Ln.i("listening on port ${options.port}, token ${options.token}")
        ServerSocket(options.port).use { server ->
            var current: Pair<Socket, Thread>? = null
            while (true) {
                val socket = server.accept()
                Ln.i("connection from ${socket.inetAddress.hostAddress}")
                val session = try {
                    handshake(socket, options)
                } catch (e: Exception) {
                    Ln.i("rejected: $e")
                    null
                }
                if (session == null) {
                    socket.close()
                    continue
                }
                // The newest Mac wins. A Mac that vanished without closing its connection (sleep,
                // Wi-Fi drop) would otherwise hold the helper forever.
                current?.let { (oldSocket, oldThread) ->
                    Ln.i("ending the previous session for the new connection")
                    oldSocket.close()
                    oldThread.join(3000)
                }
                current = socket to thread(name = "session") {
                    try {
                        serve(socket, session.first, session.second, options)
                    } catch (e: Exception) {
                        Ln.e("session ended", e)
                    } finally {
                        if (Ln.remote === session.second) Ln.remote = null
                        PanelPower.restore()
                        Rotation.restore()
                        socket.close()
                        Ln.i("disconnected")
                    }
                }
            }
        }
    }

    /** Checks the token; returns the session's streams, or null to reject. */
    private fun handshake(socket: Socket, options: Options): Pair<DataInputStream, FrameWriter>? {
        socket.tcpNoDelay = true
        // The Mac pings every second, so a silent connection is a dead one.
        socket.soTimeout = 5000
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        val writer = FrameWriter(BufferedOutputStream(socket.getOutputStream(), 256 * 1024))
        val type = input.readUnsignedByte()
        val length = input.readInt()
        if (type != Msg.HELLO || length !in 1..256) {
            Ln.i("rejected: bad handshake")
            return null
        }
        val token = String(ByteArray(length).also { input.readFully(it) })
        if (token != options.token) {
            Ln.i("rejected: wrong token")
            return null
        }
        return input to writer
    }

    private fun serve(socket: Socket, input: DataInputStream, writer: FrameWriter, options: Options) {
        writer.send(Msg.HELLO_ACK, "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}".toByteArray())
        Ln.remote = writer

        val streamer = VideoStreamer(writer, options)
        val video = thread(name = "video") {
            try {
                streamer.run()
            } catch (e: Exception) {
                Ln.e("video stopped", e)
                socket.close()
            }
        }
        val audio = AudioStreamer(writer)
        thread(name = "audio") {
            try {
                audio.run()
            } catch (e: Exception) {
                if (audio.running) Ln.e("audio unavailable, it keeps playing on the phone", e)
            }
        }
        val injector = InputInjector()
        KeepAwake.start(injector)
        try {
            ControlReceiver(input, writer, streamer, injector).loop()
        } finally {
            audio.running = false
            KeepAwake.stop()
            streamer.running = false
            video.join(2000)
        }
    }
}
