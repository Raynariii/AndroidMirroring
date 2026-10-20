package com.androidmirroring.helper

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build

/**
 * Captures everything the phone plays and sends it to the Mac as raw PCM (48 kHz, 16-bit,
 * stereo, little-endian) in AUDIO messages. The REMOTE_SUBMIX source reroutes the phone's media
 * output for as long as it records, so sound plays on the Mac instead of the phone and comes back
 * to the phone when the session ends. Raw PCM is about 1.5 Mbit/s, fine on a LAN for this spike.
 */
class AudioStreamer(private val writer: FrameWriter) {
    @Volatile
    var running = true

    @SuppressLint("MissingPermission") // The shell uid holds CAPTURE_AUDIO_OUTPUT.
    fun run() {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val builder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.REMOTE_SUBMIX)
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuffer, CHUNK_BYTES * 8))
        if (Build.VERSION.SDK_INT >= 31) builder.setContext(ShellContext.get())
        val record = builder.build()
        try {
            record.startRecording()
            Ln.i("audio: forwarding phone sound to the Mac")
            val chunk = ByteArray(CHUNK_BYTES)
            while (running) {
                val n = record.read(chunk, 0, chunk.size)
                if (n < 0) error("AudioRecord.read returned $n")
                if (n > 0) writer.send(Msg.AUDIO, if (n == chunk.size) chunk else chunk.copyOf(n))
            }
        } finally {
            record.stop()
            record.release()
        }
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        // 10 ms of stereo 16-bit audio.
        const val CHUNK_BYTES = SAMPLE_RATE / 100 * 2 * 2
    }
}
