package com.androidmirroring.helper

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Captures the main display, encodes H.264 with MediaCodec (surface input, zero copy) and
 * sends VIDEO_CONFIG / VIDEO_FRAME messages. Restarts the encoder when the display size
 * changes (rotation).
 *
 * Frame timestamps are the capture time in CLOCK_MONOTONIC microseconds, the same clock
 * PONG reports, so the Mac can compute capture-to-receive latency.
 */
class VideoStreamer(private val writer: FrameWriter, private val options: Options) {
    @Volatile
    var running = true

    @Volatile
    private var codec: MediaCodec? = null
    private val restart = AtomicBoolean(false)

    fun requestKeyframe() {
        try {
            codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (e: IllegalStateException) {
            // Codec is being torn down for a restart, which produces a keyframe anyway.
        }
    }

    fun run() {
        thread(name = "display-watch", isDaemon = true) { watchDisplaySize() }
        while (running) {
            val display = DisplayInfoReader.read()
            val (width, height) = encodeSize(display.width, display.height, options.maxSize)
            val c = createEncoder(width, height)
            val surface = c.createInputSurface()
            val mirror = MirrorDisplay.create(surface, display, width, height)
            c.start()
            codec = c
            try {
                drain(c, width, height)
            } finally {
                codec = null
                c.stop()
                c.release()
                mirror.release()
                surface.release()
            }
        }
    }

    private fun watchDisplaySize() {
        var last = DisplayInfoReader.read()
        while (running) {
            Thread.sleep(250)
            val now = DisplayInfoReader.read()
            if (now.width != last.width || now.height != last.height) {
                Ln.i("display changed ${last.width}x${last.height} -> ${now.width}x${now.height}, restarting encoder")
                last = now
                restart.set(true)
            }
        }
    }

    private fun createEncoder(width: Int, height: Int): MediaCodec {
        val c = MediaCodec.createEncoderByType(MIME)
        try {
            c.configure(format(width, height, lowLatencyKeys = true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            Ln.i("encoder rejected low-latency keys ($e), retrying with a basic format")
            c.reset()
            c.configure(format(width, height, lowLatencyKeys = false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        Ln.i("encoder: ${c.name} ${width}x$height ${options.bitRate / 1000} kbps max ${options.maxFps} fps")
        return c
    }

    private fun format(width: Int, height: Int, lowLatencyKeys: Boolean) =
        MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, options.bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, 60)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10)
            // When the screen is static, resend the last frame so the stream never stalls.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000)
            setFloat("max-fps-to-encoder", options.maxFps.toFloat())
            if (lowLatencyKeys) {
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(MediaFormat.KEY_LATENCY, 1)
            }
        }

    private fun drain(c: MediaCodec, width: Int, height: Int) {
        val info = MediaCodec.BufferInfo()
        while (running && !restart.getAndSet(false)) {
            val index = c.dequeueOutputBuffer(info, 100_000)
            if (index < 0) continue
            try {
                val buffer = c.getOutputBuffer(index) ?: continue
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    val payload = ByteBuffer.allocate(8 + info.size).putInt(width).putInt(height).put(buffer)
                    writer.send(Msg.VIDEO_CONFIG, payload.array())
                } else if (info.size > 0) {
                    val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    val payload = ByteBuffer.allocate(9 + info.size)
                        .putLong(info.presentationTimeUs)
                        .put((if (key) 1 else 0).toByte())
                        .put(buffer)
                    writer.send(Msg.VIDEO_FRAME, payload.array())
                }
            } finally {
                c.releaseOutputBuffer(index, false)
            }
        }
    }

    companion object {
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC

        /** Scales so the long side is at most [maxSize], keeping both sides multiples of 8. */
        fun encodeSize(width: Int, height: Int, maxSize: Int): Pair<Int, Int> {
            val longSide = maxOf(width, height)
            val scale = if (maxSize in 1 until longSide) maxSize.toDouble() / longSide else 1.0
            fun align(v: Int) = maxOf(8, (v * scale).toInt() and 7.inv())
            return align(width) to align(height)
        }
    }
}
