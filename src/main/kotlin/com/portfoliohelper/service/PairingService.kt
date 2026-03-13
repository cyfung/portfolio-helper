package com.portfoliohelper.service

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

object PairingService {
    private val logger = LoggerFactory.getLogger(PairingService::class.java)
    
    // Maps PIN to PairingRequest
    private val pendingPairings = ConcurrentHashMap<String, Long>()
    
    // Set of authorized client identifiers (e.g., host addresses)
    private val authorizedClients = ConcurrentHashMap<String, Boolean>()

    private const val PIN_EXPIRY_MS = 300_000L // 5 minutes

    fun generatePin(): String {
        // Simple 4-digit PIN
        val pin = (1000..9999).random().toString()
        val now = System.currentTimeMillis()
        pendingPairings[pin] = now
        
        // Cleanup old PINs
        pendingPairings.entries.removeIf { now - it.value > PIN_EXPIRY_MS }
        
        logger.info("Generated new pairing PIN: $pin")
        return pin
    }

    fun verifyPin(pin: String, clientAddress: String): Boolean {
        val timestamp = pendingPairings[pin]
        if (timestamp != null && System.currentTimeMillis() - timestamp <= PIN_EXPIRY_MS) {
            pendingPairings.remove(pin)
            authorizedClients[clientAddress] = true
            logger.info("Client $clientAddress successfully paired with PIN $pin")
            return true
        }
        logger.warn("Pairing failed for client $clientAddress with PIN $pin")
        return false
    }

    fun isAuthorized(clientAddress: String): Boolean {
        // For simplicity in this local tool, we authorize by IP. 
        // In a real Sunshine-like scenario, we'd use certificates/unique IDs.
        return authorizedClients.containsKey(clientAddress)
    }

    fun getActivePins(): Set<String> {
        val now = System.currentTimeMillis()
        pendingPairings.entries.removeIf { now - it.value > PIN_EXPIRY_MS }
        return pendingPairings.keys
    }
}
