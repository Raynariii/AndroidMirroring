package com.androidmirroring.activation

/** One SPAKE2 exchange as the pairing client. Not reusable; call [close] when done. */
class Spake2 : AutoCloseable {
    private var ctx: Long = nativeNew().also { check(it != 0L) { "SPAKE2_CTX_new failed" } }

    fun generateMessage(password: ByteArray): ByteArray =
        nativeGenerateMsg(ctx, password) ?: throw IllegalStateException("SPAKE2_generate_msg failed")

    /** Returns the shared key, or null if the other side used a different password. */
    fun processMessage(theirMessage: ByteArray): ByteArray? = nativeProcessMsg(ctx, theirMessage)

    override fun close() {
        if (ctx != 0L) {
            nativeFree(ctx)
            ctx = 0L
        }
    }

    private companion object {
        init {
            System.loadLibrary("androidmirroring_native")
        }

        @JvmStatic external fun nativeNew(): Long
        @JvmStatic external fun nativeFree(ctx: Long)
        @JvmStatic external fun nativeGenerateMsg(ctx: Long, password: ByteArray): ByteArray?
        @JvmStatic external fun nativeProcessMsg(ctx: Long, theirMessage: ByteArray): ByteArray?
    }
}
