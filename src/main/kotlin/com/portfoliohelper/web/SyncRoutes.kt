package com.portfoliohelper.web

import com.portfoliohelper.service.PairingService
import com.portfoliohelper.service.PortfolioRegistry
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.pipeline.*
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import org.slf4j.LoggerFactory
import java.io.StringWriter

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
     * Client sends: POST /api/sync/pair?pin=1234
     * Headers: X-Device-ID (unique device identifier), X-Device-Name (human-readable, optional)
     *
     * Flow:
     *   1. Server already displayed the PIN in its own UI (via /api/pairing/generate).
     *   2. User reads the PIN on the server screen and types it into the client app.
     *   3. Client POSTs here — if PIN matches, device is immediately authorized.
     */
    post("/api/sync/pair") {
        val pin = call.request.queryParameters["pin"]
            ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing PIN")

        val deviceId = call.request.headers["X-Device-ID"]
            ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing X-Device-ID header")

        val deviceName = call.request.headers["X-Device-Name"] ?: deviceId
        val ip = call.request.origin.remoteHost

        logger.info("Pairing attempt from '$deviceName' ($ip) with PIN $pin")

        if (PairingService.verifyAndPair(pin, deviceId, deviceName, ip)) {
            call.respond(HttpStatusCode.OK, "Paired successfully")
        } else {
            call.respond(HttpStatusCode.Unauthorized, "Invalid or expired PIN")
        }
    }

    /**
     * Data Sync: Positions
     */
    get("/api/sync/positions.csv") {
        val portfolioId = call.request.queryParameters["portfolio"] ?: "main"
        val entry = PortfolioRegistry.get(id = portfolioId)
            ?: return@get call.respond(HttpStatusCode.NotFound)

        val sw = StringWriter()
        val csvFormat = CSVFormat.DEFAULT.builder()
            .setHeader("symbol", "quantity", "targetWeight", "groups")
            .build()

        CSVPrinter(sw, csvFormat).use { printer ->
            entry.getStocks().forEach { stock ->
                val groupsStr = stock.groups.joinToString(";") { (mult, name) -> "$mult $name" }
                printer.printRecord(stock.label, formatQty(stock.amount), stock.targetWeight ?: 0.0, groupsStr)
            }
        }
        call.respondText(sw.toString(), ContentType.Text.CSV)
    }

    /**
     * Data Sync: Cash
     */
    get("/api/sync/cash.csv") {
        val portfolioId = call.request.queryParameters["portfolio"] ?: "main"
        val entry = PortfolioRegistry.get(id = portfolioId)
            ?: return@get call.respond(HttpStatusCode.NotFound)

        val sw = StringWriter()
        val csvFormat = CSVFormat.DEFAULT.builder()
            .setHeader("label", "currency", "amount", "isMargin")
            .build()

        CSVPrinter(sw, csvFormat).use { printer ->
            entry.getCash().forEach { cash ->
                printer.printRecord(cash.label, cash.currency, cash.amount, cash.marginFlag)
            }
        }
        call.respondText(sw.toString(), ContentType.Text.CSV)
    }
}
