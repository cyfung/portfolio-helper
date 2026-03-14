package com.ibviewer.data.repository

import android.util.Log
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * Accepts only a TLS certificate whose SHA-256 fingerprint matches [expectedFingerprint].
 * Used to trust the server's self-signed certificate on first-use (TOFU).
 *
 * If [expectedFingerprint] is null, accepts any certificate (used during the initial
 * fingerprint-capture pairing step over HTTPS before pinning is established).
 */
class FingerprintTrustManager(private val expectedFingerprint: String?) : X509TrustManager {

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        if (expectedFingerprint == null) return  // accept-any mode during initial pairing

        val cert = chain[0]
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        val fingerprint = digest.joinToString(":") { "%02X".format(it) }

        if (!fingerprint.equals(expectedFingerprint, ignoreCase = true)) {
            Log.e("FingerprintTrust", "Certificate fingerprint mismatch!\n  expected: $expectedFingerprint\n  got:      $fingerprint")
            throw javax.net.ssl.SSLException("Certificate fingerprint mismatch")
        }
    }

    companion object {
        fun fingerprintOf(cert: X509Certificate): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            return digest.joinToString(":") { "%02X".format(it) }
        }
    }
}
