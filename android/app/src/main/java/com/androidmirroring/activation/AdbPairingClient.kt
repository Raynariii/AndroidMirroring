package com.androidmirroring.activation

import org.conscrypt.Conscrypt
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import javax.crypto.AEADBadTagException
import javax.net.ssl.SSLSocket

class WrongPairingCodeException : Exception("The pairing code was not accepted")

/**
 * Client side of Android 11+ Wireless debugging pairing ("Pair device with pairing code"),
 * following AOSP adb/pairing_connection:
 *  1. TLS 1.3 to the pairing port; export 64 bytes of keying material ("adb-label\0").
 *  2. SPAKE2 with password = pairing code + exported material.
 *  3. Exchange PeerInfo (our adb public key) encrypted with AES-128-GCM from the SPAKE2 key.
 * On success adbd adds our key to its authorized keys, so [AdbConnection] can then connect.
 */
class AdbPairingClient(private val host: String, private val port: Int, private val key: AdbKey) {

    fun pair(pairingCode: String) {
        Socket().use { raw ->
            raw.tcpNoDelay = true
            raw.connect(InetSocketAddress(host, port), TIMEOUT_MS)
            raw.soTimeout = TIMEOUT_MS
            val tls = key.sslContext().socketFactory.createSocket(raw, host, port, true) as SSLSocket
            tls.use {
                tls.startHandshake()
                val exported = Conscrypt.exportKeyingMaterial(tls, EXPORTED_KEY_LABEL, null, EXPORTED_KEY_SIZE)
                val input = DataInputStream(tls.inputStream)
                val output = DataOutputStream(tls.outputStream)

                val cipher = Spake2().use { spake2 ->
                    val ourMessage = spake2.generateMessage(pairingCode.toByteArray(Charsets.UTF_8) + exported)
                    writePacket(output, TYPE_SPAKE2_MSG, ourMessage)
                    val theirMessage = readPacket(input, TYPE_SPAKE2_MSG)
                    val secret = spake2.processMessage(theirMessage) ?: throw WrongPairingCodeException()
                    PairingCipher(secret)
                }

                val peerInfo = ByteBuffer.allocate(MAX_PEER_INFO_SIZE)
                    .put(PEER_INFO_RSA_PUB_KEY)
                    .put(key.adbPublicKey().copyOf(MAX_PEER_INFO_SIZE - 1))
                    .array()
                writePacket(output, TYPE_PEER_INFO, cipher.encrypt(peerInfo))

                val theirPeerInfo = try {
                    cipher.decrypt(readPacket(input, TYPE_PEER_INFO))
                } catch (e: AEADBadTagException) {
                    throw WrongPairingCodeException()
                }
                check(theirPeerInfo.size == MAX_PEER_INFO_SIZE) { "unexpected peer info size ${theirPeerInfo.size}" }
            }
        }
    }

    private fun writePacket(output: DataOutputStream, type: Int, payload: ByteArray) {
        output.writeByte(HEADER_VERSION)
        output.writeByte(type)
        output.writeInt(payload.size)
        output.write(payload)
        output.flush()
    }

    private fun readPacket(input: DataInputStream, expectedType: Int): ByteArray {
        val version = input.readUnsignedByte()
        val type = input.readUnsignedByte()
        val size = input.readInt()
        check(version == HEADER_VERSION) { "unsupported pairing header version $version" }
        // adbd closes or answers with garbage when the code is wrong; treat a type mismatch the same way.
        if (type != expectedType) throw WrongPairingCodeException()
        check(size in 1..MAX_PAYLOAD_SIZE) { "bad pairing payload size $size" }
        return ByteArray(size).also { input.readFully(it) }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000
        const val HEADER_VERSION = 1
        const val TYPE_SPAKE2_MSG = 0
        const val TYPE_PEER_INFO = 1
        const val MAX_PEER_INFO_SIZE = 8192
        const val MAX_PAYLOAD_SIZE = MAX_PEER_INFO_SIZE * 2
        const val PEER_INFO_RSA_PUB_KEY: Byte = 0
        const val EXPORTED_KEY_LABEL = "adb-label\u0000"
        const val EXPORTED_KEY_SIZE = 64
    }
}
