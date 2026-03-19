package com.portfoliohelper.service

import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object AesGcm {
    fun encrypt(plaintext: ByteArray, keyBase64: String): ByteArray {
        val key = SecretKeySpec(Base64.getDecoder().decode(keyBase64), "AES")
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(plaintext)
        return iv + ciphertext  // 12-byte IV + ciphertext (includes 16-byte GCM tag)
    }

    /**
     * Encrypts using a counter-based nonce: 8 zero bytes + counter as big-endian Int (4 bytes).
     * The counter must be monotonically increasing and persisted across restarts to guarantee uniqueness.
     */
    fun encrypt(plaintext: ByteArray, keyBase64: String, counter: Int): ByteArray {
        val key = SecretKeySpec(Base64.getDecoder().decode(keyBase64), "AES")
        val iv = ByteBuffer.allocate(12).also { it.putInt(8, counter) }.array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(plaintext)
        return iv + ciphertext
    }

    fun decrypt(data: ByteArray, keyBase64: String): ByteArray {
        val key = SecretKeySpec(Base64.getDecoder().decode(keyBase64), "AES")
        val iv = data.copyOfRange(0, 12)
        val ciphertext = data.copyOfRange(12, data.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    /** Generate a random 256-bit AES key, Base64-encoded. */
    fun generateKey(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return Base64.getEncoder().encodeToString(bytes)
    }
}
