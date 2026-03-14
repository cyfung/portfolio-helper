package com.portfoliohelper.service

import com.portfoliohelper.service.db.AdminSessionsTable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.*

sealed class CodeResult {
    object Success : CodeResult()
    object Expired : CodeResult()
    object Invalid : CodeResult()
}

object AdminService {
    private val logger = LoggerFactory.getLogger(AdminService::class.java)

    const val SESSION_COOKIE = "admin_session"

    private data class AdminCode(
        val value: String,
        val expiresAt: Long,
        val attempts: AtomicInteger = AtomicInteger(0)
    )

    private val codeRef = AtomicReference<AdminCode?>(null)
    private val recentlyExpired = mutableListOf<String>()   // last 3, synchronized on itself
    private const val CODE_EXPIRY_MS = 5 * 60 * 1000L
    private const val MAX_CODE_ATTEMPTS = 5

    // Per-IP rate limiting
    private val failedAttempts = ConcurrentHashMap<String, AtomicInteger>()
    private val blockedUntil = ConcurrentHashMap<String, Long>()
    private const val MAX_FAILURES = 5
    private const val BLOCK_DURATION_MS = 15 * 60 * 1000L

    // First-run session state
    @Volatile
    private var sessionExists = false

    // ── Session state ────────────────────────────────────────────────────────

    fun loadSessionState() {
        sessionExists = transaction { AdminSessionsTable.selectAll().count() > 0L }
    }

    fun hasAnySessions() = sessionExists

    /** Atomically creates the very first session if none exist yet.
     *  Returns a session token if this caller wins, null if someone else already did. */
    @Synchronized
    fun tryClaimFirstSession(ip: String, userAgent: String): String? = transaction {
        if (AdminSessionsTable.selectAll().count() > 0L) return@transaction null
        val token = UUID.randomUUID().toString()
        AdminSessionsTable.insert {
            it[AdminSessionsTable.token] = token
            it[createdAt] = System.currentTimeMillis()
            it[AdminSessionsTable.ip] = ip
            it[AdminSessionsTable.userAgent] = userAgent.take(512)
        }
        sessionExists = true
        token
    }

    fun createSession(ip: String, userAgent: String): String {
        val token = UUID.randomUUID().toString()
        transaction {
            AdminSessionsTable.insert {
                it[AdminSessionsTable.token] = token
                it[createdAt] = System.currentTimeMillis()
                it[AdminSessionsTable.ip] = ip
                it[AdminSessionsTable.userAgent] = userAgent.take(512)
            }
        }
        sessionExists = true
        return token
    }

    fun validateSession(token: String): Boolean = transaction {
        AdminSessionsTable.selectAll()
            .where { AdminSessionsTable.token eq token }
            .firstOrNull() != null
    }

    fun invalidateSession(token: String) = transaction {
        AdminSessionsTable.deleteWhere { AdminSessionsTable.token eq token }
    }

    data class SessionInfo(val token: String, val createdAt: Long, val ip: String, val userAgent: String)

    fun getSessions(): List<SessionInfo> = transaction {
        AdminSessionsTable.selectAll()
            .orderBy(AdminSessionsTable.createdAt)
            .map { row ->
                SessionInfo(
                    token = row[AdminSessionsTable.token],
                    createdAt = row[AdminSessionsTable.createdAt],
                    ip = row[AdminSessionsTable.ip],
                    userAgent = row[AdminSessionsTable.userAgent]
                )
            }
    }

    // ── Code generation / verification ───────────────────────────────────────

    private fun randomValue(): String =
        UUID.randomUUID().toString().replace("-", "").take(12).uppercase()

    /** Generates a fresh one-time code, replacing any existing one. */
    fun generateCode(): String {
        val code = AdminCode(randomValue(), System.currentTimeMillis() + CODE_EXPIRY_MS)
        codeRef.set(code)
        logger.info("Admin code generated")
        return code.value
    }

    /** Returns the current non-expired code, or generates a new one if absent/expired. */
    fun getCurrentOrGenerate(): String {
        val current = codeRef.get()
        return if (current != null && System.currentTimeMillis() < current.expiresAt) {
            current.value
        } else {
            generateCode()
        }
    }

    fun verifyAndConsume(code: String): CodeResult {
        val current = codeRef.get()
        if (current == null) {
            return if (isRecentlyExpired(code)) CodeResult.Expired else CodeResult.Invalid
        }
        if (System.currentTimeMillis() >= current.expiresAt) {
            burnCode(current.value)
            return if (isRecentlyExpired(code)) CodeResult.Expired else CodeResult.Invalid
        }
        val a = MessageDigest.getInstance("SHA-256").digest(code.toByteArray())
        val b = MessageDigest.getInstance("SHA-256").digest(current.value.toByteArray())
        if (!MessageDigest.isEqual(a, b)) {
            val attempts = current.attempts.incrementAndGet()
            if (attempts >= MAX_CODE_ATTEMPTS) burnCode(current.value)
            return CodeResult.Invalid
        }
        // Constant-time compare passed — CAS to consume atomically
        if (!codeRef.compareAndSet(current, null)) {
            return CodeResult.Invalid  // another thread consumed it first
        }
        burnCode(current.value)  // add to recentlyExpired so replay returns EXPIRED
        return CodeResult.Success
    }

    private fun burnCode(value: String) {
        val current = codeRef.get()
        if (current != null && current.value == value) codeRef.compareAndSet(current, null)
        synchronized(recentlyExpired) {
            recentlyExpired.add(0, value)
            while (recentlyExpired.size > 3) recentlyExpired.removeLast()
        }
    }

    private fun isRecentlyExpired(value: String): Boolean =
        synchronized(recentlyExpired) { value in recentlyExpired }

    // ── Per-IP rate limiting ─────────────────────────────────────────────────

    fun isBlocked(ip: String): Boolean =
        blockedUntil[ip]?.let { System.currentTimeMillis() < it } ?: false

    fun recordFailure(ip: String) {
        val count = failedAttempts.computeIfAbsent(ip) { AtomicInteger(0) }.incrementAndGet()
        if (count >= MAX_FAILURES) {
            blockedUntil[ip] = System.currentTimeMillis() + BLOCK_DURATION_MS
            logger.warn("IP $ip blocked for 15 minutes after $count failed admin login attempts")
        }
    }

    fun recordSuccess(ip: String) {
        failedAttempts.remove(ip)
        blockedUntil.remove(ip)
    }
}
