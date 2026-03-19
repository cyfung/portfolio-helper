package com.portfoliohelper.service

import com.portfoliohelper.service.db.PairedDevicesTable
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.*

data class PairedClient(
    val serverAssignedId: String,
    val clientId: String,
    val name: String,
    val pairedAt: Long,
    val lastIp: String,
    val aesKey: String
)

@Serializable
data class PairingResponse(
    val serverAssignedId: String,
    val aesKey: String
)

sealed class PairingResult {
    data class Success(val response: PairingResponse) : PairingResult()
    object Expired : PairingResult()
    object Invalid : PairingResult()
}

enum class PinStatus { ACTIVE, USED, EXPIRED, UNKNOWN }

object PairingService {
    private val logger = LoggerFactory.getLogger(PairingService::class.java)

    // Maps PIN -> expiry timestamp
    private val pendingPins = ConcurrentHashMap<String, Long>()

    // Per-PIN attempt counter
    private val pinAttempts = ConcurrentHashMap<String, AtomicInteger>()

    // Last 3 expired PINs (time-expired or rate-burned) — synchronized on itself
    private val recentlyExpiredPins = mutableListOf<String>()

    // Last 3 successfully-consumed PINs — synchronized on itself
    private val recentlyUsedPins = mutableListOf<String>()

    // Maps serverAssignedId -> PairedClient
    private val pairedClients = ConcurrentHashMap<String, PairedClient>()

    // Rate limiting: maps IP -> failed attempt count
    private val failedAttempts = ConcurrentHashMap<String, AtomicInteger>()

    // Rate limiting: maps IP -> blocked-until timestamp
    private val blockedUntil = ConcurrentHashMap<String, Long>()

    private const val PIN_EXPIRY_MS = 300_000L           // 5 minutes
    private const val MAX_PIN_ATTEMPTS = 5
    private const val MAX_FAILURES = 5
    private const val BLOCK_DURATION_MS = 15 * 60 * 1000L  // 15 minutes

    fun generatePin(): String {
        val pin = (100000..999999).random().toString()
        val now = System.currentTimeMillis()
        pendingPins[pin] = now + PIN_EXPIRY_MS
        pendingPins.entries.removeIf { System.currentTimeMillis() > it.value }
        logger.info("Generated new pairing PIN: $pin")
        return pin
    }

    private fun burnPin(pin: String, used: Boolean = false) {
        pendingPins.remove(pin)
        pinAttempts.remove(pin)
        if (used) {
            synchronized(recentlyUsedPins) {
                recentlyUsedPins.add(0, pin)
                while (recentlyUsedPins.size > 3) recentlyUsedPins.removeLast()
            }
        } else {
            synchronized(recentlyExpiredPins) {
                recentlyExpiredPins.add(0, pin)
                while (recentlyExpiredPins.size > 3) recentlyExpiredPins.removeLast()
            }
        }
    }

    fun getPinStatus(pin: String): PinStatus {
        pendingPins.entries.removeIf { System.currentTimeMillis() > it.value }
        if (pendingPins.containsKey(pin)) return PinStatus.ACTIVE
        val wasUsed = synchronized(recentlyUsedPins) { pin in recentlyUsedPins }
        if (wasUsed) return PinStatus.USED
        val wasExpired = synchronized(recentlyExpiredPins) { pin in recentlyExpiredPins }
        if (wasExpired) return PinStatus.EXPIRED
        return PinStatus.UNKNOWN
    }

    fun isBlocked(ip: String): Boolean =
        blockedUntil[ip]?.let { System.currentTimeMillis() < it } ?: false

    fun recordFailure(ip: String) {
        val count = failedAttempts.computeIfAbsent(ip) { AtomicInteger(0) }.incrementAndGet()
        if (count >= MAX_FAILURES) {
            blockedUntil[ip] = System.currentTimeMillis() + BLOCK_DURATION_MS
            logger.warn("IP $ip blocked for 15 minutes after $count failed pairing attempts")
        }
    }

    fun recordSuccess(ip: String) {
        failedAttempts.remove(ip)
        blockedUntil.remove(ip)
    }

    fun verifyAndPair(
        pin: String,
        clientId: String,
        deviceName: String,
        ip: String
    ): PairingResult {
        val expiry = pendingPins[pin]
        if (expiry == null) {
            val wasExpired = synchronized(recentlyExpiredPins) { pin in recentlyExpiredPins }
            if (wasExpired) {
                logger.warn("Pairing attempt from '$deviceName' ($ip) with already-expired PIN")
                return PairingResult.Expired
            }
            // Unknown PIN — count attempts so repeated guessing of the same bad PIN gets burned
            val attempts = pinAttempts.computeIfAbsent(pin) { AtomicInteger(0) }.incrementAndGet()
            if (attempts >= MAX_PIN_ATTEMPTS) burnPin(pin)
            logger.warn("Pairing failed for device '$deviceName' ($ip) — invalid PIN")
            return PairingResult.Invalid
        }
        if (System.currentTimeMillis() > expiry) {
            burnPin(pin)
            logger.warn("Pairing failed for device '$deviceName' ($ip) — PIN expired")
            return PairingResult.Expired
        }

        // Valid PIN — consume it
        burnPin(pin, used = true)

        val serverAssignedId = UUID.randomUUID().toString()
        val aesKey = AesGcm.generateKey()
        val now = System.currentTimeMillis()

        val client = PairedClient(
            serverAssignedId = serverAssignedId,
            clientId = clientId,
            name = deviceName,
            pairedAt = now,
            lastIp = ip,
            aesKey = aesKey
        )
        pairedClients[serverAssignedId] = client

        transaction {
            PairedDevicesTable.insert {
                it[PairedDevicesTable.serverAssignedId] = serverAssignedId
                it[PairedDevicesTable.clientId] = clientId
                it[PairedDevicesTable.displayName] = deviceName
                it[PairedDevicesTable.pairedAt] = now
                it[PairedDevicesTable.lastIp] = ip
                it[PairedDevicesTable.aesKey] = aesKey
            }
        }

        logger.info("Device '$deviceName' ($ip) paired successfully as $serverAssignedId")
        return PairingResult.Success(
            PairingResponse(
                serverAssignedId = serverAssignedId,
                aesKey = aesKey
            )
        )
    }

    fun isAuthorized(serverAssignedId: String): Boolean =
        pairedClients.containsKey(serverAssignedId)

    /**
     * In a single transaction: reads aesKey + useCount, increments (or deletes if > 2000), and returns
     * both values for immediate use. The nonce is persisted before being returned, guaranteeing
     * no repeat even after a reboot. Returns null if the device is not found.
     */
    fun acquireEncryptionNonce(serverAssignedId: String): Pair<String, Int>? {
        val result = transaction {
            val row = PairedDevicesTable.selectAll()
                .where { PairedDevicesTable.serverAssignedId eq serverAssignedId }
                .singleOrNull() ?: return@transaction null
            val aesKey = row[PairedDevicesTable.aesKey]
            val next = row[PairedDevicesTable.useCount] + 1
            if (next > 2000) {
                PairedDevicesTable.deleteWhere { PairedDevicesTable.serverAssignedId eq serverAssignedId }
            } else {
                PairedDevicesTable.update({ PairedDevicesTable.serverAssignedId eq serverAssignedId }) {
                    it[useCount] = next
                }
            }
            aesKey to next
        }
        if (result != null && result.second > 2000) {
            pairedClients.remove(serverAssignedId)
            logger.warn("Device $serverAssignedId retired after exceeding max use count")
        }
        return result
    }

    fun getPairedClients(): List<PairedClient> = pairedClients.values.toList()

    fun unpairClient(serverAssignedId: String): Boolean {
        val removed = pairedClients.remove(serverAssignedId) != null
        if (removed) {
            transaction {
                PairedDevicesTable.deleteWhere { PairedDevicesTable.serverAssignedId eq serverAssignedId }
            }
            logger.info("Device $serverAssignedId unpaired")
        } else {
            logger.warn("Attempted to unpair unknown device: $serverAssignedId")
        }
        return removed
    }

    fun unpairAll() {
        pairedClients.clear()
        logger.info("All paired devices removed from memory (DB rows preserved)")
    }

    /** Called at startup to restore previously paired devices from the DB. */
    fun loadFromDb() {
        transaction {
            PairedDevicesTable.selectAll().forEach { row ->
                val id = row[PairedDevicesTable.serverAssignedId]
                pairedClients[id] = PairedClient(
                    serverAssignedId = id,
                    clientId = row[PairedDevicesTable.clientId],
                    name = row[PairedDevicesTable.displayName],
                    pairedAt = row[PairedDevicesTable.pairedAt],
                    lastIp = row[PairedDevicesTable.lastIp],
                    aesKey = row[PairedDevicesTable.aesKey]
                )
            }
        }
        logger.info("Loaded ${pairedClients.size} paired device(s) from DB")
    }

}
