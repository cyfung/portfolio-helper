package com.portfoliohelper.service

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.slf4j.LoggerFactory
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date

object CertificateManager {
    private val logger = LoggerFactory.getLogger(CertificateManager::class.java)
    private const val ALIAS = "ibviewer"
    private const val P12_PATH = "tls/server.p12"

    fun loadOrGenerate(dataDir: File): Pair<KeyStore, String> {
        val p12File = File(dataDir, P12_PATH)
        if (p12File.exists()) {
            logger.info("Loading existing TLS certificate from ${p12File.absolutePath}")
            val ks = KeyStore.getInstance("PKCS12").apply {
                p12File.inputStream().use { load(it, charArrayOf()) }
            }
            val cert = ks.getCertificate(ALIAS) as X509Certificate
            val fp = fingerprint(cert)
            logger.info("TLS certificate fingerprint: $fp")
            return ks to fp
        }
        return generate(p12File)
    }

    private fun generate(dest: File): Pair<KeyStore, String> {
        dest.parentFile.mkdirs()
        logger.info("Generating new TLS key pair and self-signed certificate...")

        val keyPair = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(2048, SecureRandom()) }
            .generateKeyPair()

        val now = System.currentTimeMillis()
        val notBefore = Date(now)
        val notAfter = Date(now + 10L * 365 * 24 * 60 * 60 * 1000) // 10 years

        val subject = X500Name("CN=localhost")

        val certBuilder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(SecureRandom().nextLong().and(0x7FFFFFFFFFFFFFFF)),
            notBefore,
            notAfter,
            subject,
            keyPair.public
        )

        // SANs: localhost + 127.0.0.1 only — the browser always accesses via loopback.
        // Android uses fingerprint pinning and ignores SANs entirely.
        certBuilder.addExtension(
            Extension.subjectAlternativeName,
            false,
            GeneralNames(arrayOf(
                GeneralName(GeneralName.dNSName,  "localhost"),
                GeneralName(GeneralName.iPAddress, "127.0.0.1"),
                GeneralName(GeneralName.iPAddress, "::1")
            ))
        )

        certBuilder.addExtension(
            Extension.extendedKeyUsage,
            false,
            ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth)
        )

        val cert = JcaX509CertificateConverter()
            .getCertificate(certBuilder.build(
                JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
            ))

        val ks = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry(ALIAS, keyPair.private, charArrayOf(), arrayOf(cert))
        }
        dest.outputStream().use { ks.store(it, charArrayOf()) }

        val fp = fingerprint(cert)
        logger.info("Generated TLS certificate. Fingerprint: $fp")
        return ks to fp
    }

    fun fingerprint(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256")
            .digest(cert.encoded)
            .joinToString(":") { "%02X".format(it) }
}
