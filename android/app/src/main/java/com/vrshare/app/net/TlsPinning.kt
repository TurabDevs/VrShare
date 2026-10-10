package com.vrshare.app.net

import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * TLS with certificate fingerprint pinning only (VrShare spec §2.2):
 * the server certificate is accepted iff the SHA-256 of its DER encoding
 * matches the expected fingerprint. Hostname verification is disabled —
 * self-signed certs on LAN have no meaningful hostname.
 *
 * One OkHttpClient is cached per (fingerprint, timeout-profile).
 */
object TlsPinning {
    private val clients = mutableMapOf<String, OkHttpClient>()

    @Synchronized
    fun clientFor(fpHex: String, longTimeouts: Boolean = false): OkHttpClient {
        val key = "$fpHex|$longTimeouts"
        return clients.getOrPut(key) {
            val tm = FpTrustManager(fpHex)
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf(tm), SecureRandom())
            OkHttpClient.Builder()
                .sslSocketFactory(ctx.socketFactory, tm)
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(if (longTimeouts) 5 else 1, TimeUnit.MINUTES)
                .writeTimeout(if (longTimeouts) 5 else 1, TimeUnit.MINUTES)
                .build()
        }
    }

    class FpTrustManager(private val expectedFp: String) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            // client auth is not used
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val md = MessageDigest.getInstance("SHA-256")
            val ok = chain?.any { c ->
                md.digest(c.encoded).toHex().equals(expectedFp, ignoreCase = true)
            } == true
            if (!ok) throw CertificateException(
                "VrShare: server certificate fingerprint does not match the pinned value",
            )
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
