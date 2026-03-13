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
    
    // Security Interceptor for /api/sync
    intercept(ApplicationCallPipeline.Plugins) {
        val path = call.request.path()
        if (!path.startsWith("/api/sync/")) return@intercept

        // "Announce" is the only sync endpoint allowed without a paired ID
        if (path == "/api/sync/announce") return@intercept

        val deviceId = call.request.headers["X-Device-ID"]
        if (deviceId == null || !PairingService.isAuthorized(deviceId)) {
            logger.warn("Unauthorized sync access attempt to $path. Device ID: $deviceId")
            call.respond(HttpStatusCode.Unauthorized, "Device not paired")
            return@intercept finish()
        }
    }

    /**
     * Called by Android device to initiate pairing.
     * Android generates a PIN, displays it to user, and sends it here.
     */
    post("/api/sync/announce") {
        val deviceId = call.request.queryParameters["deviceId"] ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing Device ID")
        val deviceName = call.request.queryParameters["deviceName"] ?: "Android Device"
        val pin = call.request.queryParameters["pin"] ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing PIN")
        val clientAddress = call.request.origin.remoteHost
        
        PairingService.addPendingPairing(deviceId, deviceName, pin, clientAddress)
        call.respond(HttpStatusCode.Accepted, "Announcement received. Please authorize on server.")
    }

    /**
     * Data Sync: Positions
     */
    get("/api/sync/positions.csv") {
        val portfolioId = call.request.queryParameters["portfolio"] ?: "main"
        val entry = PortfolioRegistry.get(id = portfolioId) ?: return@get call.respond(HttpStatusCode.NotFound)
        
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
        val entry = PortfolioRegistry.get(id = portfolioId) ?: return@get call.respond(HttpStatusCode.NotFound)
        
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
