package com.androidmirroring.activation

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Base64

class ActivationProtocolTest {

    @Test
    fun hkdfMatchesRfc5869TestCase3() {
        val okm = PairingCipher.hkdfSha256(ByteArray(22) { 0x0b }, ByteArray(0), 42)
        assertEquals(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
            okm.joinToString("") { "%02x".format(it) },
        )
    }

    @Test
    fun pairingCipherRoundTripsWithPerDirectionCounters() {
        val secret = ByteArray(64) { it.toByte() }
        val alice = PairingCipher(secret)
        val bob = PairingCipher(secret)
        repeat(3) { i ->
            val message = "peer info $i".toByteArray()
            val sealed = alice.encrypt(message)
            assertEquals(message.size + 16, sealed.size)
            assertArrayEquals(message, bob.decrypt(sealed))
        }
    }

    @Test
    fun adbPublicKeyStructIsConsistent() {
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public as RSAPublicKey
        val struct = AdbPublicKey.encode(key)
        assertEquals(AdbPublicKey.STRUCT_SIZE, struct.size)

        val buf = ByteBuffer.wrap(struct).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(64, buf.int)
        val n0inv = buf.int.toLong() and 0xffffffffL
        val modulus = ByteArray(256).also { buf.get(it) }.reversedArray()
        val rr = ByteArray(256).also { buf.get(it) }.reversedArray()
        assertEquals(65537, buf.int)

        val n = BigInteger(1, modulus)
        assertEquals(key.modulus, n)
        val r32 = BigInteger.ONE.shiftLeft(32)
        // n0inv * n[0] == -1 (mod 2^32)
        assertEquals(r32.subtract(BigInteger.ONE), BigInteger.valueOf(n0inv).multiply(n).mod(r32))
        assertEquals(BigInteger.ONE.shiftLeft(4096).mod(n), BigInteger(1, rr))

        val text = String(AdbPublicKey.adbFormat(key, "AndroidMirroring"))
        val (b64, name) = text.split(" ")
        assertArrayEquals(struct, Base64.getDecoder().decode(b64))
        assertEquals("AndroidMirroring", name)
    }

    @Test
    fun adbMessageRoundTrips() {
        val out = ByteArrayOutputStream()
        AdbMessage(AdbMessage.A_OPEN, 1, 0, "shell:id\u0000".toByteArray()).writeTo(out)
        val bytes = out.toByteArray()
        assertEquals(AdbMessage.HEADER_SIZE + 9, bytes.size)
        // Header starts with "OPEN" in little-endian ASCII.
        assertEquals("OPEN", String(bytes, 0, 4, Charsets.US_ASCII))

        val parsed = AdbMessage.readFrom(DataInputStream(bytes.inputStream()))
        assertEquals(AdbMessage.A_OPEN, parsed.command)
        assertEquals(1, parsed.arg0)
        assertEquals("shell:id\u0000", String(parsed.payload))
        assertEquals("CNXN", AdbMessage.name(AdbMessage.A_CNXN))
        assertEquals("STLS", AdbMessage.name(AdbMessage.A_STLS))
    }

    @Test
    fun launchCommandDetachesHelper() {
        val command = HelperLauncher.launchCommand("/data/app/x/base.apk", "abc123")
        assertTrue(command.contains("CLASSPATH='/data/app/x/base.apk'"))
        assertTrue(command.contains("com.androidmirroring.helper.HelperMain token=abc123 port=27183"))
        assertTrue(command.trimEnd().endsWith("&"))
    }
}
