package com.portfoliohelper.web

import com.portfoliohelper.service.*
import com.portfoliohelper.service.yahoo.YahooMarketDataService
import com.portfoliohelper.util.appJson
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("SyncRoutes")

fun Route.configureSyncRoutes() {

    // Security interceptor for /api/sync — all routes except /pair require a paired device
    intercept(ApplicationCallPipeline.Plugins) {
        val path = call.request.path()
        if (!path.startsWith("/api/sync/")) return@intercept
        if (path == "/api/sync/pair") return@intercept

        val deviceId = call.request.headers["X-Device-ID"]
        if (deviceId == null || !PairingService.isAuthorized(deviceId)) {
            logger.warn("Unauthorized sync access to $path. Device-ID: $deviceId")
            call.respond(HttpStatusCode.Unauthorized, "Device not paired")
            return@intercept finish()
        }
    }

    /**
     * Pairing endpoint.
     * Client sends: POST /api/sync/pair?pin=123456
     * Headers: X-Device-ID (unique device identifier), X-Device-Name (human-readable, optional)
     *
     * On success: returns JSON with serverAssignedId + aesKey.
     * Rate-limited: IP blocked for 15 minutes after 5 failed attempts.
     */
    post("/api/sync/pair") {
        val ip = call.request.origin.remoteHost

        if (PairingService.isBlocked(ip)) {
            logger.warn("Pairing attempt blocked for IP $ip (rate limited)")
            call.respond(HttpStatusCode.TooManyRequests, "Too many failed attempts. Try again in 15 minutes.")
            return@post
        }

        val pin = call.request.queryParameters["pin"]
            ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing PIN")

        val clientId = call.request.headers["X-Device-ID"]
            ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing X-Device-ID header")

        val deviceName = call.request.headers["X-Device-Name"] ?: clientId

        logger.info("Pairing attempt from '$deviceName' ($ip) with PIN $pin")

        val response = PairingService.verifyAndPair(pin, clientId, deviceName, ip)
        if (response != null) {
            PairingService.recordSuccess(ip)
            call.respond(HttpStatusCode.OK, appJson.encodeToString(PairingResponse.serializer(), response))
        } else {
            PairingService.recordFailure(ip)
            call.respond(HttpStatusCode.Unauthorized, "Invalid or expired PIN")
        }
    }

    /**
     * Consolidated data sync endpoint.
     * Returns AES-256-GCM encrypted BackupRoot JSON (same format as /api/backup/export-json).
     * P-type cash entries include snapshotUsd resolved at request time.
     * Response: application/octet-stream bytes (12-byte IV + ciphertext + 16-byte GCM tag)
     */
    get("/api/sync/data") {
        val portfolioId = call.request.queryParameters["portfolio"] ?: "main"
        val entry = ManagedPortfolio.getBySlug(portfolioId)
            ?: return@get call.respond(HttpStatusCode.NotFound)

        val deviceId = call.request.headers["X-Device-ID"]!!
        val (aesKey, nonce) = PairingService.acquireEncryptionNonce(deviceId)
            ?: return@get call.respond(HttpStatusCode.Unauthorized, "Device not found or key expired")

        val json = BackupService.exportJson(entry) { e ->
            when (e.currency) {
                "USD" -> e.amount
                "P"   -> {
                    val ref = ManagedPortfolio.getBySlug(e.portfolioRef ?: return@exportJson null)
                        ?: return@exportJson null
                    e.amount * YahooMarketDataService.getCurrentPortfolio(ref.getStocks()).totalValue
                }
                else  -> YahooMarketDataService.getQuote("${e.currency}USD=X")
                    ?.let { q -> (q.regularMarketPrice ?: q.previousClose ?: return@exportJson null) * e.amount }
            }
        }

        val encrypted = AesGcm.encrypt(json.toByteArray(Charsets.UTF_8), aesKey, nonce)
        call.respondBytes(encrypted, ContentType.Application.OctetStream)
    }
}
