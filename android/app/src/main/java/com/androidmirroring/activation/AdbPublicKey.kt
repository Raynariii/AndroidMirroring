package com.androidmirroring.activation

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/**
 * Encodes an RSA-2048 public key the way adbd stores authorized keys
 * (AOSP libcrypto_utils android_pubkey_encode), followed by " <name>".
 */
object AdbPublicKey {
    private const val MODULUS_BYTES = 256
    private const val MODULUS_WORDS = MODULUS_BYTES / 4
    const val STRUCT_SIZE = 4 + 4 + MODULUS_BYTES + MODULUS_BYTES + 4

    fun encode(key: RSAPublicKey): ByteArray {
        val n = key.modulus
        require(n.bitLength() <= MODULUS_BYTES * 8) { "only RSA-2048 keys are supported" }
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = n.mod(r32).modInverse(r32).negate().mod(r32)
        val rr = BigInteger.ONE.shiftLeft(MODULUS_BYTES * 8 * 2).mod(n)

        return ByteBuffer.allocate(STRUCT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(MODULUS_WORDS)
            .putInt(n0inv.toInt())
            .put(littleEndian(n))
            .put(littleEndian(rr))
            .putInt(key.publicExponent.toInt())
            .array()
    }

    /** The string adb sends in pairing PeerInfo and writes to adb_keys. */
    fun adbFormat(key: RSAPublicKey, name: String): ByteArray =
        (Base64.getEncoder().encodeToString(encode(key)) + " " + name).toByteArray(Charsets.UTF_8)

    private fun littleEndian(value: BigInteger): ByteArray {
        val bigEndian = value.toByteArray()
        val out = ByteArray(MODULUS_BYTES)
        // toByteArray may carry a leading sign byte; copy only the low MODULUS_BYTES bytes, reversed.
        for (i in 0 until minOf(MODULUS_BYTES, bigEndian.size)) {
            out[i] = bigEndian[bigEndian.size - 1 - i]
        }
        return out
    }
}
