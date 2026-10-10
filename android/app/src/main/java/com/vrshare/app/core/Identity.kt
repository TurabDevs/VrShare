package com.vrshare.app.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * This phone's identity: a stable UUID plus a self-signed RSA-2048 certificate
 * generated once in the AndroidKeyStore (spec §2.1). The SHA-256 fingerprint of
 * the certificate DER is what we advertise in mDNS and send in POST /api/pair.
 */
object Identity {
    private const val ALIAS = "vrshare_device"
    private const val TEN_YEARS_MS = 10L * 365 * 24 * 3600 * 1000

    lateinit var info: IdentityInfo
        private set

    @Synchronized
    fun ensure(ctx: Context): IdentityInfo {
        if (::info.isInitialized) return info
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(ALIAS)) generate()
        val cert = ks.getCertificate(ALIAS) as X509Certificate
        val fp = MessageDigest.getInstance("SHA-256").digest(cert.encoded).toHex()
        info = IdentityInfo(Store.ownId, Store.deviceName, fp)
        return info
    }

    /** Call after the user renames the device so mDNS re-announces the new name. */
    fun refreshName() {
        if (::info.isInitialized) info = info.copy(name = Store.deviceName)
    }

    private fun generate() {
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
        val now = System.currentTimeMillis()
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        )
            .setKeySize(2048)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            .setCertificateSubject(X500Principal("CN=VrShare"))
            .setCertificateSerialNumber(BigInteger(64, SecureRandom()))
            .setCertificateNotBefore(Date(now))
            .setCertificateNotAfter(Date(now + TEN_YEARS_MS))
            .build()
        kpg.initialize(spec)
        kpg.generateKeyPair()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
