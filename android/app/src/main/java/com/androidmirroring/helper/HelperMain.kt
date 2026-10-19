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
        Ln.i("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        Ln.i("listening on port ${options.port}, token ${options.token}")
        ServerSocket(options.port).use { server ->
            while (true) {
                val socket = server.accept()
                Ln.i("connection from ${socket.inetAddress.hostAddress}")
                try {
                    serve(socket, options)
                } catch (e: Exception) {
                    Ln.e("session ended", e)
                } finally {
                    Ln.remote = null
                    PanelPower.restore()
                    socket.close()
                    Ln.i("disconnected")
                }
            }
        }
    }

    private fun serve(socket: Socket, options: Options) {
        socket.tcpNoDelay = true
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        val writer = FrameWriter(BufferedOutputStream(socket.getOutputStream(), 256 * 1024))

        val type = input.readUnsignedByte()
        val length = input.readInt()
        if (type != Msg.HELLO || length !in 1..256) {
            Ln.i("rejected: bad handshake")
            return
        }
        val token = String(ByteArray(length).also { input.readFully(it) })
        if (token != options.token) {
            Ln.i("rejected: wrong token")
            return
        }
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
        try {
            ControlReceiver(input, writer, streamer, InputInjector()).loop()
        } finally {
            streamer.running = false
            video.join(2000)
        }
    }
}
