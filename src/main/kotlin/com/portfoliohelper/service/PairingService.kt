package com.portfoliohelper.service

import com.portfoliohelper.AppDirs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class AuthorizedClient(
    val id: String,
    val name: String,
    val pairedAt: Long,
    val lastIp: String
)

@Serializable
data class PendingPairing(
    val deviceId: String,
    val deviceName: String,
    val pin: String, // The PIN the device generated
    val timestamp: Long,
    val ip: String
)

object PairingService {
    private val logger = LoggerFactory.getLogger(PairingService::class.java)
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val clientsFile = AppDirs.dataDir.resolve("paired_devices.json").toFile()
    
    // Maps Device ID to Pending request
    private val pendingPairings = ConcurrentHashMap<String, PendingPairing>()
    
    // Maps Device ID to Client info
    private val authorizedClients = ConcurrentHashMap<String, AuthorizedClient>()

    private const val PAIRING_EXPIRY_MS = 300_000L // 5 minutes

    init {
        loadClients()
    }

    private fun loadClients() {
        try {
            if (clientsFile.exists()) {
                val list = json.decodeFromString<List<AuthorizedClient>>(clientsFile.readText())
                list.forEach { authorizedClients[it.id] = it }
                logger.info("Loaded ${list.size} paired devices")
            }
        } catch (e: Exception) {
            logger.error("Failed to load paired devices", e)
        }
    }

    private fun saveClients() {
        try {
            clientsFile.parentFile.mkdirs()
            val list = authorizedClients.values.toList()
            clientsFile.writeText(json.encodeToString(list))
        } catch (e: Exception) {
            logger.error("Failed to save paired devices", e)
        }
    }

    /**
     * Called by Android device to announce itself and initiate pairing.
     */
    fun addPendingPairing(deviceId: String, deviceName: String, pin: String, ip: String) {
        val now = System.currentTimeMillis()
        pendingPairings[deviceId] = PendingPairing(deviceId, deviceName, pin, now, ip)
        cleanupPending()
        logger.info("New pairing request from $deviceName ($deviceId) at $ip. Waiting for PIN authorization on server.")
    }

    /**
     * Called by Server Web UI to authorize a device by its ID and the PIN it displayed.
     */
    fun authorizeDevice(deviceId: String, pin: String): Boolean {
        cleanupPending()
        val pending = pendingPairings[deviceId]
        if (pending != null && pending.pin == pin) {
            authorizedClients[deviceId] = AuthorizedClient(
                id = deviceId,
                name = pending.deviceName,
                pairedAt = System.currentTimeMillis(),
                lastIp = pending.ip
            )
            pendingPairings.remove(deviceId)
            saveClients()
            logger.info("Device '${pending.deviceName}' ($deviceId) authorized successfully.")
            return true
        }
        logger.warn("Authorization failed for $deviceId. Incorrect PIN or expired request.")
        return false
    }

    fun isAuthorized(deviceId: String): Boolean {
        return authorizedClients.containsKey(deviceId)
    }

    fun getPairedClients(): List<AuthorizedClient> {
        return authorizedClients.values.sortedByDescending { it.pairedAt }
    }

    fun getPendingRequests(): List<PendingPairing> {
        cleanupPending()
        return pendingPairings.values.sortedByDescending { it.timestamp }
    }

    fun unpairClient(deviceId: String) {
        if (authorizedClients.remove(deviceId) != null) {
            saveClients()
            logger.info("Unpaired device: $deviceId")
        }
    }

    fun unpairAll() {
        authorizedClients.clear()
        saveClients()
        logger.info("Unpaired all devices")
    }

    private fun cleanupPending() {
        val now = System.currentTimeMillis()
        pendingPairings.entries.removeIf { now - it.value.timestamp > PAIRING_EXPIRY_MS }
    }
}
