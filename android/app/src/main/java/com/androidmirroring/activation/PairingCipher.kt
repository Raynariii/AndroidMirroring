package com.androidmirroring.activation

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-128-GCM keyed from the SPAKE2 secret, as in adb's pairing_auth: HKDF-SHA256 with no salt,
 * nonces are a little-endian 64-bit counter per direction, no associated data.
 */
class PairingCipher(spake2Key: ByteArray) {
    private val key = SecretKeySpec(hkdfSha256(spake2Key, INFO, 16), "AES")
    private var encryptCounter = 0L
    private var decryptCounter = 0L

    fun encrypt(plain: ByteArray): ByteArray = run(Cipher.ENCRYPT_MODE, encryptCounter++, plain)

    /** Throws [javax.crypto.AEADBadTagException] when the pairing code was wrong. */
    fun decrypt(sealed: ByteArray): ByteArray = run(Cipher.DECRYPT_MODE, decryptCounter++, sealed)

    private fun run(mode: Int, counter: Long, input: ByteArray): ByteArray {
        val nonce = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(counter).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, key, GCMParameterSpec(128, nonce))
        return cipher.doFinal(input)
    }

    companion object {
        private val INFO = "adb pairing_auth aes-128-gcm key".toByteArray()

        fun hkdfSha256(ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
            val prk = mac.doFinal(ikm)
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            val out = ByteArray(length)
            var previous = ByteArray(0)
            var offset = 0
            var counter = 1
            while (offset < length) {
                mac.update(previous)
                mac.update(info)
                mac.update(counter.toByte())
                previous = mac.doFinal()
                val n = minOf(previous.size, length - offset)
                previous.copyInto(out, offset, 0, n)
                offset += n
                counter++
            }
            return out
        }
    }
}
