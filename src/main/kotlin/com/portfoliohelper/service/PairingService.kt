package com.portfoliohelper.service

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

data class PairedClient(
    val id: String,
    val name: String,
    val pairedAt: Long,
    val lastIp: String
)

object PairingService {
    private val logger = LoggerFactory.getLogger(PairingService::class.java)

    // Maps PIN -> expiry timestamp
    private val pendingPins = ConcurrentHashMap<String, Long>()

    // Maps deviceId (IP or X-Device-ID header) -> PairedClient
    private val pairedClients = ConcurrentHashMap<String, PairedClient>()

    private const val PIN_EXPIRY_MS = 300_000L // 5 minutes

    /**
     * Server calls this to generate and display a PIN in the UI.
     */
    fun generatePin(): String {
        val pin = (1000..9999).random().toString()
        val now = System.currentTimeMillis()
        pendingPins[pin] = now + PIN_EXPIRY_MS

        // Cleanup expired PINs
        pendingPins.entries.removeIf { System.currentTimeMillis() > it.value }

        logger.info("Generated new pairing PIN: $pin")
        return pin
    }

    /**
     * Client calls POST /api/sync/pair?pin=XXXX.
     * If the PIN is valid and not expired, the client is immediately authorized.
     */
    fun verifyAndPair(pin: String, deviceId: String, deviceName: String, ip: String): Boolean {
        val expiry = pendingPins[pin]
        if (expiry == null || System.currentTimeMillis() > expiry) {
            logger.warn("Pairing failed for device '$deviceName' ($ip) — invalid or expired PIN: $pin")
            return false
        }
        pendingPins.remove(pin)
        pairedClients[deviceId] = PairedClient(
            id = deviceId,
            name = deviceName,
            pairedAt = System.currentTimeMillis(),
            lastIp = ip
        )
        logger.info("Device '$deviceName' ($ip) paired successfully")
        return true
    }

    fun isAuthorized(deviceId: String): Boolean = pairedClients.containsKey(deviceId)

    fun getPairedClients(): List<PairedClient> = pairedClients.values.toList()

    fun unpairClient(deviceId: String): Boolean {
        val removed = pairedClients.remove(deviceId) != null
        if (removed) logger.info("Device $deviceId unpaired")
        else logger.warn("Attempted to unpair unknown device: $deviceId")
        return removed
    }

    fun unpairAll() {
        pairedClients.clear()
        logger.info("All paired devices removed")
    }

    fun getActivePins(): Set<String> {
        pendingPins.entries.removeIf { System.currentTimeMillis() > it.value }
        return pendingPins.keys
    }
}
