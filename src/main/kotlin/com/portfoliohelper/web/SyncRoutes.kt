package com.portfoliohelper.web

import com.portfoliohelper.service.*
import com.portfoliohelper.util.appJson
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("SyncRoutes")

private val syncAuthPlugin = createRouteScopedPlugin("SyncAuth") {
    onCall { call ->
        if (call.request.path() == "/api/sync/pair") return@onCall
        val deviceId = call.request.headers["X-Device-ID"]
        if (deviceId == null || !PairingService.isAuthorized(deviceId)) {
            logger.warn("Unauthorized sync access to ${call.request.path()}. Device-ID: $deviceId")
            call.respond(HttpStatusCode.Unauthorized, "Device not paired")
        }
    }
}

fun Route.configureSyncRoutes() {

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
            call.respond(
                HttpStatusCode.TooManyRequests,
                "Too many failed attempts. Try again in 15 minutes."
            )
            return@post
        }

        val pin = call.request.queryParameters["pin"]
            ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing PIN")

        val clientId = call.request.headers["X-Device-ID"]
            ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing X-Device-ID header")

        val deviceName = call.request.headers["X-Device-Name"] ?: clientId

        logger.info("Pairing attempt from '$deviceName' ($ip) with PIN $pin")

        when (val result = PairingService.verifyAndPair(pin, clientId, deviceName, ip)) {
            is PairingResult.Success -> {
                PairingService.recordSuccess(ip)
                call.respond(
                    HttpStatusCode.OK,
                    appJson.encodeToString(PairingResponse.serializer(), result.response)
                )
            }

            is PairingResult.Expired -> {
                PairingService.recordFailure(ip)
                call.respond(HttpStatusCode.Gone, "PIN expired. Generate a new one.")
            }

            is PairingResult.Invalid -> {
                PairingService.recordFailure(ip)
                call.respond(HttpStatusCode.Unauthorized, "Invalid PIN.")
            }
        }
    }

    route("/api/sync") {
        install(syncAuthPlugin)

        /**
         * Consolidated data sync endpoint.
         * Returns AES-256-GCM encrypted BackupRoot JSON (same format as /api/backup/export-json).
         * P-type cash entries include snapshotUsd resolved at request time.
         * Response: application/octet-stream bytes (12-byte IV + ciphertext + 16-byte GCM tag)
         */
        get("/data") {
            val entry = ManagedPortfolio.resolve(call.request.queryParameters["portfolio"])
                ?: return@get call.respond(HttpStatusCode.NotFound)

            val deviceId = call.request.headers["X-Device-ID"]!!
            val (aesKey, nonce) = PairingService.acquireEncryptionNonce(deviceId)
                ?: return@get call.respond(HttpStatusCode.Unauthorized, "Device not found or key expired")

            val json = BackupService.exportJson(entry)
            val encrypted = AesGcm.encrypt(json.toByteArray(Charsets.UTF_8), aesKey, nonce)
            call.respondBytes(encrypted, ContentType.Application.OctetStream)
        }

        /**
         * Bulk sync endpoint — all portfolios in one encrypted payload.
         * Response: AES-GCM encrypted JSON of AllSyncResponse
         */
        get("/all") {
            val deviceId = call.request.headers["X-Device-ID"]!!
            val (aesKey, nonce) = PairingService.acquireEncryptionNonce(deviceId)
                ?: return@get call.respond(HttpStatusCode.Unauthorized, "Device not found or key expired")

            val roots = ManagedPortfolio.getAll().associate { entry ->
                entry.serialId to BackupService.exportRoot(entry)
            }
            val payload = appJson.encodeToString(AllSyncResponse.serializer(), AllSyncResponse(roots))
            val encrypted = AesGcm.encrypt(payload.toByteArray(Charsets.UTF_8), aesKey, nonce)
            call.respondBytes(encrypted, ContentType.Application.OctetStream)
        }
    }
}
