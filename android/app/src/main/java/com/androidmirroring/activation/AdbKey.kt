package com.androidmirroring.activation

import android.content.Context
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.conscrypt.Conscrypt
import java.io.File
import java.math.BigInteger
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * This app's identity towards adbd: an RSA-2048 key and a self-signed certificate, created once
 * and kept in app-private storage. Pairing registers its public key with adbd.
 *
 * Spike note: the product should keep this key in the Android Keystore; TLS client auth with a
 * Keystore key works through a KeyManager, but adds complexity not needed to prove feasibility.
 */
class AdbKey private constructor(val privateKey: PrivateKey, val certificate: X509Certificate) {

    val publicKey: RSAPublicKey get() = certificate.publicKey as RSAPublicKey

    fun adbPublicKey(): ByteArray = AdbPublicKey.adbFormat(publicKey, NAME)

    /** TLS 1.3 client context presenting our certificate. adbd's certificate is not checked; see note. */
    fun sslContext(): SSLContext {
        val context = SSLContext.getInstance("TLSv1.3", provider)
        context.init(arrayOf(keyManager), arrayOf(TrustAll), SecureRandom())
        return context
    }

    private val keyManager = object : X509ExtendedKeyManager() {
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
        override fun getCertificateChain(alias: String?) = if (alias == ALIAS) arrayOf(certificate) else null
        override fun getPrivateKey(alias: String?) = if (alias == ALIAS) privateKey else null
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
    }

    /**
     * adbd's certificate is self-signed and unknown to us. Pairing authenticates both sides with the
     * pairing code (SPAKE2 binds to the TLS session's exported keys), and the connection is to
     * localhost, so skipping certificate checks here does not open a MITM hole for this spike.
     */
    private object TrustAll : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    companion object {
        private const val NAME = "AndroidMirroring"
        private const val ALIAS = "adbkey"

        val provider by lazy { Conscrypt.newProvider() }

        @Volatile
        private var instance: AdbKey? = null

        fun get(context: Context): AdbKey = instance ?: synchronized(this) {
            instance ?: loadOrCreate(File(context.filesDir, "adb")).also { instance = it }
        }

        private fun loadOrCreate(dir: File): AdbKey {
            val keyFile = File(dir, "key.pk8")
            val certFile = File(dir, "cert.der")
            if (keyFile.exists() && certFile.exists()) {
                val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                val cert = CertificateFactory.getInstance("X.509")
                    .generateCertificate(certFile.inputStream()) as X509Certificate
                return AdbKey(key, cert)
            }

            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val subject = X500Name("CN=$NAME")
            val now = System.currentTimeMillis()
            val builder = JcaX509v3CertificateBuilder(
                subject,
                BigInteger.valueOf(now),
                Date(now - 24L * 3600 * 1000),
                Date(now + 30L * 365 * 24 * 3600 * 1000),
                subject,
                pair.public,
            )
            val signer = JcaContentSignerBuilder("SHA256withRSA").build(pair.private)
            val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))

            dir.mkdirs()
            keyFile.writeBytes(pair.private.encoded)
            certFile.writeBytes(cert.encoded)
            return AdbKey(pair.private, cert)
        }
    }
}
