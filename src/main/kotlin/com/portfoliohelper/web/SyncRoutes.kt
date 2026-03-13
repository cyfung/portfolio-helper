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
    
    // Middleware check for sync endpoints
    intercept(ApplicationCallPipeline.Plugins) {
        val path = call.request.path()
        if (path.startsWith("/api/sync/") && path != "/api/sync/pair") {
            val clientAddress = call.request.origin.remoteHost
            if (!PairingService.isAuthorized(clientAddress)) {
                logger.warn("Unauthorized access attempt to $path from $clientAddress")
                call.respond(HttpStatusCode.Unauthorized, "Device not paired")
                return@intercept finish()
            }
        }
    }

    // Pairing Endpoint - PIN verification
    post("/api/sync/pair") {
        val pin = call.request.queryParameters["pin"] ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing PIN")
        val clientAddress = call.request.origin.remoteHost
        
        logger.info("Received pairing request from $clientAddress with PIN $pin")
        if (PairingService.verifyPin(pin, clientAddress)) {
            call.respond(HttpStatusCode.OK, "Paired successfully")
        } else {
            call.respond(HttpStatusCode.Unauthorized, "Invalid or expired PIN")
        }
    }

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
                printer.printRecord(
                    stock.label,
                    formatQty(stock.amount),
                    stock.targetWeight ?: 0.0,
                    groupsStr
                )
            }
        }
        call.respondText(sw.toString(), ContentType.Text.CSV)
    }

    get("/api/sync/cash.csv") {
        val portfolioId = call.request.queryParameters["portfolio"] ?: "main"
        val entry = PortfolioRegistry.get(id = portfolioId) ?: return@get call.respond(HttpStatusCode.NotFound)
        
        val sw = StringWriter()
        val csvFormat = CSVFormat.DEFAULT.builder()
            .setHeader("label", "currency", "amount", "isMargin")
            .build()
            
        CSVPrinter(sw, csvFormat).use { printer ->
            entry.getCash().forEach { cash ->
                printer.printRecord(
                    cash.label,
                    cash.currency,
                    cash.amount,
                    cash.marginFlag
                )
            }
        }
        call.respondText(sw.toString(), ContentType.Text.CSV)
    }
}
