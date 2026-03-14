package com.portfoliohelper.service

import com.portfoliohelper.service.db.PairedDevicesTable
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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

object PairingService {
    private val logger = LoggerFactory.getLogger(PairingService::class.java)

    // Maps PIN -> expiry timestamp
    private val pendingPins = ConcurrentHashMap<String, Long>()

    // Maps serverAssignedId -> PairedClient
    private val pairedClients = ConcurrentHashMap<String, PairedClient>()

    // Rate limiting: maps IP -> failed attempt count
    private val failedAttempts = ConcurrentHashMap<String, AtomicInteger>()

    // Rate limiting: maps IP -> blocked-until timestamp
    private val blockedUntil = ConcurrentHashMap<String, Long>()

    private const val PIN_EXPIRY_MS = 300_000L    // 5 minutes
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

    fun verifyAndPair(pin: String, clientId: String, deviceName: String, ip: String): PairingResponse? {
        val expiry = pendingPins[pin]
        if (expiry == null || System.currentTimeMillis() > expiry) {
            logger.warn("Pairing failed for device '$deviceName' ($ip) — invalid or expired PIN: $pin")
            return null
        }
        pendingPins.remove(pin)

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
        return PairingResponse(serverAssignedId = serverAssignedId, aesKey = aesKey)
    }

    fun isAuthorized(serverAssignedId: String): Boolean =
        pairedClients.containsKey(serverAssignedId)

    fun getAesKey(serverAssignedId: String): String? =
        pairedClients[serverAssignedId]?.aesKey

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

    fun getActivePins(): Set<String> {
        pendingPins.entries.removeIf { System.currentTimeMillis() > it.value }
        return pendingPins.keys
    }
}
