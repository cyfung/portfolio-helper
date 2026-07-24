package db.migration

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context

class V11__TaggedPortfolioRows : BaseJavaMigration() {
    override fun migrate(context: Context) {
        context.connection.prepareStatement("SELECT name, config FROM saved_backtest_portfolios").use { select ->
            select.executeQuery().use { rows ->
                context.connection.prepareStatement(
                    "UPDATE saved_backtest_portfolios SET config = ? WHERE name = ?"
                ).use { update ->
                    while (rows.next()) {
                        update.setString(1, migrateJson(rows.getString("config"), "saved portfolio '${rows.getString("name")}'"))
                        update.setString(2, rows.getString("name"))
                        update.addBatch()
                    }
                    update.executeBatch()
                }
            }
        }

        val autosaved = context.connection.prepareStatement(
            "SELECT value FROM global_settings WHERE key = ?"
        ).use { select ->
            select.setString(1, AUTOSAVED_PORTFOLIOS_KEY)
            select.executeQuery().use { rows -> if (rows.next()) rows.getString("value") else null }
        }
        if (autosaved != null) {
            context.connection.prepareStatement(
                "UPDATE global_settings SET value = ? WHERE key = ?"
            ).use { update ->
                update.setString(1, migrateJson(autosaved, "autosaved portfolios"))
                update.setString(2, AUTOSAVED_PORTFOLIOS_KEY)
                update.executeUpdate()
            }
        }
    }

    private fun migrateJson(raw: String, location: String): String {
        val parsed = runCatching { JSON.parseToJsonElement(raw) }
            .getOrElse { throw IllegalStateException("Cannot migrate $location: invalid JSON", it) }
        return migrateElement(parsed, location).toString()
    }

    private fun migrateElement(element: JsonElement, location: String): JsonElement = when (element) {
        is JsonArray -> JsonArray(element.mapIndexed { index, child ->
            migrateElement(child, "$location[$index]")
        })
        is JsonObject -> migrateObject(element, location)
        else -> element
    }

    private fun migrateObject(value: JsonObject, location: String): JsonObject {
        val migratedChildren = value.mapValues { (key, child) ->
            migrateElement(child, "$location.$key")
        }.toMutableMap()
        val legacyRows = value["tickers"] as? JsonArray ?: return JsonObject(migratedChildren)
        if (value["rows"] is JsonArray) {
            migratedChildren.remove("tickers")
            return JsonObject(migratedChildren)
        }

        migratedChildren.remove("tickers")
        migratedChildren["rows"] = JsonArray(legacyRows.mapIndexed { index, row ->
            convertLegacyRow(
                runCatching { row.jsonObject }.getOrElse {
                    error("Cannot migrate $location.tickers[$index]: row is not an object")
                },
                index,
                "$location.tickers[$index]",
            )
        })
        return JsonObject(migratedChildren)
    }

    private fun convertLegacyRow(row: JsonObject, index: Int, location: String): JsonObject {
        val id = row["id"]?.jsonPrimitive?.contentOrNull ?: "migrated-$index"
        val rawWeight = row["weight"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val allocation = row["weight"]?.jsonPrimitive?.doubleOrNull
        val isReference =
            row["isPortfolioRef"]?.jsonPrimitive?.booleanOrNull == true ||
                row["type"]?.jsonPrimitive?.contentOrNull == "PORTFOLIO_REF" ||
                row["portfolioRef"] != null
        if (isReference) {
            val portfolioName = (
                row["portfolioRef"]?.jsonPrimitive?.contentOrNull
                    ?: row["ticker"]?.jsonPrimitive?.contentOrNull
                )?.trim().orEmpty()
            require(portfolioName.isNotEmpty() && allocation != null && allocation.isFinite() && allocation != 0.0) {
                "Cannot migrate $location: invalid portfolio reference"
            }
            return JsonObject(linkedMapOf(
                "id" to JsonPrimitive(id),
                "type" to JsonPrimitive("PORTFOLIO_REFERENCE"),
                "portfolioName" to JsonPrimitive(portfolioName),
                "allocation" to JsonPrimitive(allocation),
                "normalizationMode" to JsonPrimitive("NET_100"),
            ))
        }

        val legacyInstrumentExpression = row["ticker"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val swap = parseSwap(legacyInstrumentExpression)
        if (swap != null) {
            val transfer = if (rawWeight == "*") {
                JsonObject(mapOf("mode" to JsonPrimitive("ALL_REMAINING")))
            } else {
                require(allocation != null && allocation.isFinite() && allocation > 0.0) {
                    "Cannot migrate $location: swap transfer amount must be positive"
                }
                JsonObject(mapOf(
                    "mode" to JsonPrimitive("AMOUNT"),
                    "amount" to JsonPrimitive(allocation),
                ))
            }
            return JsonObject(linkedMapOf(
                "id" to JsonPrimitive(id),
                "type" to JsonPrimitive("SWAP"),
                "source" to JsonPrimitive(swap.source),
                "transfer" to transfer,
                "legs" to JsonArray(swap.legs.map { leg ->
                    JsonObject(linkedMapOf(
                        "instrument" to JsonPrimitive(leg.instrument),
                        "multiplier" to JsonPrimitive(leg.multiplier),
                    ))
                }),
            ))
        }
        require(
            !legacyInstrumentExpression.contains('>') &&
                !legacyInstrumentExpression.startsWith("SWAP", ignoreCase = true)
        ) {
            "Cannot migrate $location: unrecognized legacy swap '$legacyInstrumentExpression'"
        }
        val instrument = canonicalInstrument(legacyInstrumentExpression)
        require(instrument != null && allocation != null && allocation.isFinite() && allocation != 0.0) {
            "Cannot migrate $location: invalid holding"
        }
        return JsonObject(linkedMapOf(
            "id" to JsonPrimitive(id),
            "type" to JsonPrimitive("HOLDING"),
            "instrument" to JsonPrimitive(instrument),
            "allocation" to JsonPrimitive(allocation),
        ))
    }

    private fun parseSwap(value: String): ParsedSwap? {
        parseLegacySwapCall(value)?.let { return it }
        if (!balancedParentheses(value)) return null
        val parts = splitTopLevel(value, '>')
        if (parts.size != 2) return null
        val rawSource = parts[0].trim()
        if (!hasSingleOuterGroup(rawSource) && rawSource.split(WHITESPACE).firstOrNull()?.toDoubleOrNull() != null) {
            return null
        }
        val source = canonicalInstrument(rawSource) ?: return null
        val legs = splitTopLevel(parts[1], '+').map { parseLeg(it) }
        if (legs.isEmpty() || legs.any { it == null }) return null
        return ParsedSwap(source, legs.filterNotNull())
    }

    private fun parseLegacySwapCall(value: String): ParsedSwap? {
        val match = LEGACY_SWAP.matchEntire(value.trim()) ?: return null
        val source = canonicalInstrument(match.groupValues[1]) ?: return null
        val destination = canonicalInstrument(match.groupValues[2]) ?: return null
        val multiplier = match.groupValues[3].takeIf { it.isNotEmpty() }?.toDoubleOrNull() ?: 1.0
        if (!multiplier.isFinite() || multiplier == 0.0) return null
        return ParsedSwap(source, listOf(SwapLeg(destination, multiplier)))
    }

    private fun parseLeg(value: String): SwapLeg? {
        val trimmed = value.trim()
        val prefix = PREFIX_MULTIPLIER.matchEntire(trimmed)
        val suffix = SUFFIX_MULTIPLIER.matchEntire(trimmed)
        if (prefix != null && suffix != null) return null
        val multiplier = when {
            prefix != null -> prefix.groupValues[1].toDoubleOrNull()
            suffix != null -> suffix.groupValues[2].toDoubleOrNull()
            else -> 1.0
        } ?: return null
        val rawInstrument = when {
            prefix != null -> prefix.groupValues[2]
            suffix != null -> suffix.groupValues[1]
            else -> trimmed
        }
        val instrument = canonicalInstrument(rawInstrument) ?: return null
        return if (multiplier.isFinite() && multiplier != 0.0) SwapLeg(instrument, multiplier) else null
    }

    private fun canonicalInstrument(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || !balancedParentheses(trimmed) || trimmed.contains('>')) return null
        val segments = splitTopLevel(trimmed, '|').map { canonicalSegment(it) }
        return if (segments.isEmpty() || segments.any { it == null }) null else segments.filterNotNull().joinToString(" | ")
    }

    private fun canonicalSegment(value: String): String? {
        val normalized = value.trim().replace(WHITESPACE, " ").uppercase()
        if (normalized.isEmpty()) return null
        val grouped = hasSingleOuterGroup(normalized)
        val inner = if (grouped) normalized.substring(1, normalized.length - 1).trim() else normalized
        if (inner.isEmpty() || inner.contains('(') || inner.contains(')')) return null
        val tokens = inner.split(' ')
        val modifiers = tokens.filter { MODIFIER.matches(it) }
        if (modifiers.any { !NUMERIC_MODIFIER.matches(it) && !REBALANCE_MODIFIER.matches(it) }) return null
        val expression = tokens.filterNot { MODIFIER.matches(it) }
        if (expression.any { it.contains('=') }) return null
        val validBase =
            expression.size == 1 && expression[0].toDoubleOrNull() == null ||
                expression.size >= 2 && expression.size % 2 == 0 &&
                expression.withIndex().all { (index, token) ->
                    if (index % 2 == 0) token.toDoubleOrNull() != null else token.toDoubleOrNull() == null
                }
        if (!validBase) return null
        val canonical = (expression + modifiers.sorted()).joinToString(" ")
        return if (grouped) "($canonical)" else canonical
    }

    private fun splitTopLevel(value: String, separator: Char): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        var start = 0
        value.forEachIndexed { index, character ->
            when (character) {
                '(' -> depth++
                ')' -> depth--
                separator -> if (
                    depth == 0 &&
                    (separator != '+' ||
                        value.getOrNull(index - 1)?.isWhitespace() == true &&
                        value.getOrNull(index + 1)?.isWhitespace() == true)
                ) {
                    parts += value.substring(start, index).trim()
                    start = index + 1
                }
            }
        }
        parts += value.substring(start).trim()
        return parts
    }

    private fun balancedParentheses(value: String): Boolean {
        var depth = 0
        value.forEach {
            if (it == '(') depth++
            if (it == ')') depth--
            if (depth < 0) return false
        }
        return depth == 0
    }

    private fun hasSingleOuterGroup(value: String): Boolean {
        if (!value.startsWith('(') || !value.endsWith(')')) return false
        var depth = 0
        value.forEachIndexed { index, character ->
            if (character == '(') depth++
            if (character == ')') depth--
            if (depth == 0 && index < value.lastIndex) return false
        }
        return depth == 0
    }

    private data class ParsedSwap(val source: String, val legs: List<SwapLeg>)
    private data class SwapLeg(val instrument: String, val multiplier: Double)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }
        private const val AUTOSAVED_PORTFOLIOS_KEY = "backtest.portfolios"
        private const val SIGNED_DECIMAL = """[+-]?(?:\d+(?:\.\d+)?|\.\d+)"""
        private val LEGACY_SWAP = Regex(
            """SWAP\s*\(\s*(.+?)\s*,\s*(.+?)(?:\s*,\s*($SIGNED_DECIMAL))?\s*\)""",
            RegexOption.IGNORE_CASE,
        )
        private val PREFIX_MULTIPLIER = Regex("""($SIGNED_DECIMAL)\s+(.+)""")
        private val SUFFIX_MULTIPLIER = Regex("""(.+?)\s*#\s*($SIGNED_DECIMAL)""")
        private val WHITESPACE = Regex("""\s+""")
        private val MODIFIER = Regex("""(?:S|R|E|V|VOL)=.*""", RegexOption.IGNORE_CASE)
        private val NUMERIC_MODIFIER = Regex("""(?:S|E|V|VOL)=$SIGNED_DECIMAL%?""", RegexOption.IGNORE_CASE)
        private val REBALANCE_MODIFIER = Regex("""R=(?:D|W|M|Q|Y)""", RegexOption.IGNORE_CASE)
    }
}
