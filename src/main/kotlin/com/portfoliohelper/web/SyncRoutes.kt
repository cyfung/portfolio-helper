package com.portfoliohelper.web

import com.portfoliohelper.service.PortfolioRegistry
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import java.io.StringWriter

fun Route.configureSyncRoutes() {
    get("/positions.csv") {
        val portfolioId = call.request.queryParameters["portfolio"] ?: "main"
        val entry = PortfolioRegistry.get(id = portfolioId) ?: return@get call.respond(HttpStatusCode.NotFound)
        
        val sw = StringWriter()
        val csvFormat = CSVFormat.DEFAULT.builder()
            .setHeader("symbol", "quantity", "targetWeight", "groups")
            .build()
            
        CSVPrinter(sw, csvFormat).use { printer ->
            entry.getStocks().forEach { stock ->
                // Format groups as "mult name;mult name" to avoid commas
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

    get("/cash.csv") {
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
