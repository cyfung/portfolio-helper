package com.portfoliohelper.service

import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object AdminService {
    private val logger = LoggerFactory.getLogger(AdminService::class.java)

    // Random alphanumeric passcode generated at startup (first 12 chars of UUID, uppercased)
    private val passcode: String = UUID.randomUUID().toString().replace("-", "").take(12).uppercase()

    // In-memory session tokens -> expiry timestamp (cleared on restart)
    private val activeSessions = ConcurrentHashMap<String, Long>()
    private const val SESSION_EXPIRY_MS = 8 * 60 * 60 * 1000L // 8 hours

    init {
        logger.info("Admin passcode generated (copy via tray menu to access /admin)")
    }

    fun getPasscode(): String = passcode

    fun verifyPasscode(code: String): Boolean {
        // Constant-time comparison via MessageDigest to avoid timing attacks
        val a = MessageDigest.getInstance("SHA-256").digest(code.toByteArray())
        val b = MessageDigest.getInstance("SHA-256").digest(passcode.toByteArray())
        return MessageDigest.isEqual(a, b)
    }

    fun createSession(): String {
        val token = UUID.randomUUID().toString()
        activeSessions[token] = System.currentTimeMillis() + SESSION_EXPIRY_MS
        return token
    }

    fun validateSession(token: String): Boolean {
        val expiry = activeSessions[token] ?: return false
        if (System.currentTimeMillis() > expiry) {
            activeSessions.remove(token)
            return false
        }
        return true
    }
}
