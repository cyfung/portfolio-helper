package com.portfoliohelper.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sign

@Serializable
data class FlexibleWeightMapping(val left: String, val right: String)

data class FlexibleStockInput(
    val symbol: String,
    val currentWeightPct: Double,
    val targetWeight: Double,
    val rebalDollars: Double,
)

data class FlexibleStockDisplay(
    val targetWeight: Map<String, Double>,
    val rebalDollars: Map<String, Double>,
)

private data class ExpressionTerm(val symbol: String, val weight: Double)

private data class ParsedFlexibleRule(
    val leftTerms: List<ExpressionTerm>,
    val rightTerms: List<ExpressionTerm>,
    val leftKey: String,
    val rightKey: String,
)

private const val EPSILON = 1e-9
private const val MAX_EXPANDED_EXPRESSIONS = 100
private const val MAX_EXPRESSION_TERMS = 12
private const val MAX_RULE_APPLICATION_PASSES = 20
private val flexibleJson = Json { ignoreUnknownKeys = true }

fun parseFlexibleWeightMappings(raw: String?): List<FlexibleWeightMapping> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        flexibleJson.parseToJsonElement(raw).jsonArray.mapNotNull { item ->
            runCatching {
                val fields = item.jsonObject
                FlexibleWeightMapping(
                    fields["left"]?.jsonPrimitive?.contentOrNull.orEmpty().trim(),
                    fields["right"]?.jsonPrimitive?.contentOrNull.orEmpty().trim(),
                )
            }.getOrNull()
        }
            .filter { it.left.isNotBlank() && it.right.isNotBlank() }
    }.getOrDefault(emptyList())
}

fun hasFlexibleWeightMappings(raw: String?): Boolean =
    parseFlexibleWeightMappings(raw).any { parseFlexibleRule(it) != null }

fun computeFlexibleStockDisplay(
    stocks: List<FlexibleStockInput>,
    mappings: List<FlexibleWeightMapping>,
): FlexibleStockDisplay {
    val targetWeight = stocks.associate { it.symbol to it.targetWeight }.toMutableMap()
    val currentWeight = stocks.associate { it.symbol to it.currentWeightPct }
    val rebalDollars = stocks.associate { it.symbol to it.rebalDollars }.toMutableMap()
    val rules = mappings.mapNotNull(::parseFlexibleRule)
    val expansionCache = mutableMapOf<String, List<List<ExpressionTerm>>>()

    repeat(MAX_RULE_APPLICATION_PASSES) {
        var changed = false
        for (rule in rules) {
            val leftCandidates = getExpandedExpression(rule.leftTerms, rules, expansionCache)
            val rightCandidates = getExpandedExpression(rule.rightTerms, rules, expansionCache)
            changed = applyBestFlexibleWeightRule(
                targetWeight,
                currentWeight,
                leftCandidates,
                rightCandidates,
            ) || changed
            changed = applyBestFlexibleRebalRule(
                rebalDollars,
                leftCandidates,
                rightCandidates,
            ) || changed
        }
        if (!changed) return FlexibleStockDisplay(targetWeight, rebalDollars)
    }
    return FlexibleStockDisplay(targetWeight, rebalDollars)
}

private fun parseFlexibleExpression(raw: String): List<ExpressionTerm> {
    val tokens = raw.trim().split(Regex("\\s+")).filter(String::isNotBlank)
    val terms = mutableListOf<ExpressionTerm>()
    var index = 0
    while (index < tokens.size) {
        val weight = tokens[index].toDoubleOrNull()
        if (weight != null && weight.isFinite() && index + 1 < tokens.size) {
            terms += ExpressionTerm(tokens[index + 1].trim().uppercase(), abs(weight))
            index += 2
        } else {
            terms += ExpressionTerm(tokens[index].trim().uppercase(), 1.0)
            index += 1
        }
    }
    return terms.filter { it.symbol.isNotBlank() && it.weight > 0 }
}

private fun normalizeWeight(value: Double): Double = round(value * 1e10) / 1e10

private fun normalizeTerms(terms: List<ExpressionTerm>): List<ExpressionTerm> {
    val weights = mutableMapOf<String, Double>()
    for (term in terms) {
        val symbol = term.symbol.trim().uppercase()
        val weight = normalizeWeight(abs(term.weight))
        if (symbol.isBlank() || weight <= EPSILON) continue
        weights[symbol] = normalizeWeight((weights[symbol] ?: 0.0) + weight)
    }
    return weights.map { ExpressionTerm(it.key, it.value) }
        .filter { it.weight > EPSILON }
        .sortedBy { it.symbol }
}

private fun expressionKey(terms: List<ExpressionTerm>): String = normalizeTerms(terms)
    .joinToString("|") { "${it.symbol}:${it.weight}" }

private fun scaleTerms(terms: List<ExpressionTerm>, scale: Double): List<ExpressionTerm> =
    normalizeTerms(terms.map { it.copy(weight = it.weight * scale) })

private fun parseFlexibleRule(mapping: FlexibleWeightMapping): ParsedFlexibleRule? {
    val left = normalizeTerms(parseFlexibleExpression(mapping.left))
    val right = normalizeTerms(parseFlexibleExpression(mapping.right))
    if (left.isEmpty() || right.isEmpty()) return null
    return ParsedFlexibleRule(left, right, expressionKey(left), expressionKey(right))
}

private fun expressionUnits(values: Map<String, Double>, terms: List<ExpressionTerm>): Double {
    var valueSign = 0.0
    var units = Double.POSITIVE_INFINITY
    for (term in terms) {
        val value = values[term.symbol] ?: return 0.0
        if (value == 0.0) return 0.0
        val legUnits = value / term.weight
        if (valueSign == 0.0) valueSign = legUnits.sign else if (legUnits.sign != valueSign) return 0.0
        units = min(units, abs(legUnits))
    }
    return valueSign * if (units.isFinite()) units else 0.0
}

private fun applyExpressionShift(
    values: MutableMap<String, Double>,
    terms: List<ExpressionTerm>,
    shiftUnits: Double,
) {
    if (shiftUnits == 0.0) return
    terms.forEach { values[it.symbol] = (values[it.symbol] ?: 0.0) + shiftUnits * it.weight }
}

private fun flexibleWeightShift(
    targetWeight: Map<String, Double>,
    currentWeight: Map<String, Double>,
    left: List<ExpressionTerm>,
    right: List<ExpressionTerm>,
): Double {
    val deviations = (left + right).map { it.symbol }.distinct().associateWith {
        (currentWeight[it] ?: 0.0) - (targetWeight[it] ?: 0.0)
    }
    val leftDeviation = expressionUnits(deviations, left)
    val rightDeviation = expressionUnits(deviations, right)
    return when {
        leftDeviation > 0 && rightDeviation < 0 -> min(leftDeviation, -rightDeviation)
        rightDeviation > 0 && leftDeviation < 0 -> -min(rightDeviation, -leftDeviation)
        else -> 0.0
    }
}

private fun flexibleRebalShift(
    rebalDollars: Map<String, Double>,
    left: List<ExpressionTerm>,
    right: List<ExpressionTerm>,
): Double {
    val leftRebal = expressionUnits(rebalDollars, left)
    val rightRebal = expressionUnits(rebalDollars, right)
    return when {
        leftRebal < 0 && rightRebal > 0 -> min(-leftRebal, rightRebal)
        rightRebal < 0 && leftRebal > 0 -> -min(-rightRebal, leftRebal)
        else -> 0.0
    }
}

private fun applyFlexibleWeightRule(
    targetWeight: MutableMap<String, Double>,
    currentWeight: Map<String, Double>,
    left: List<ExpressionTerm>,
    right: List<ExpressionTerm>,
): Boolean {
    val shift = flexibleWeightShift(targetWeight, currentWeight, left, right)
    if (abs(shift) <= EPSILON) return false
    applyExpressionShift(targetWeight, left, shift)
    applyExpressionShift(targetWeight, right, -shift)
    return true
}

private fun applyFlexibleRebalRule(
    rebalDollars: MutableMap<String, Double>,
    left: List<ExpressionTerm>,
    right: List<ExpressionTerm>,
): Boolean {
    val shift = flexibleRebalShift(rebalDollars, left, right)
    if (abs(shift) <= EPSILON) return false
    applyExpressionShift(rebalDollars, left, shift)
    applyExpressionShift(rebalDollars, right, -shift)
    return true
}

private fun rewriteWholeExpression(
    terms: List<ExpressionTerm>,
    rules: List<ParsedFlexibleRule>,
): List<List<ExpressionTerm>> = buildList {
    val key = expressionKey(terms)
    rules.forEach { rule ->
        if (key == rule.leftKey) add(rule.rightTerms)
        if (key == rule.rightKey) add(rule.leftTerms)
    }
}

private fun rewriteSingleTerms(
    terms: List<ExpressionTerm>,
    rules: List<ParsedFlexibleRule>,
): List<List<ExpressionTerm>> = buildList {
    terms.forEachIndexed { index, term ->
        val rest = terms.filterIndexed { candidate, _ -> candidate != index }
        rules.forEach { rule ->
            if (rule.leftTerms.size == 1 && rule.leftTerms.single().symbol == term.symbol) {
                add(normalizeTerms(rest + scaleTerms(rule.rightTerms, term.weight / rule.leftTerms.single().weight)))
            }
            if (rule.rightTerms.size == 1 && rule.rightTerms.single().symbol == term.symbol) {
                add(normalizeTerms(rest + scaleTerms(rule.leftTerms, term.weight / rule.rightTerms.single().weight)))
            }
        }
    }
}

private fun expandExpression(
    startTerms: List<ExpressionTerm>,
    rules: List<ParsedFlexibleRule>,
): List<List<ExpressionTerm>> {
    val start = normalizeTerms(startTerms)
    val seen = linkedMapOf(expressionKey(start) to start)
    val queue = mutableListOf(start)
    var cursor = 0
    while (cursor < queue.size) {
        val current = queue[cursor++]
        val candidates = rewriteWholeExpression(current, rules) + rewriteSingleTerms(current, rules)
        for (candidate in candidates) {
            val normalized = normalizeTerms(candidate)
            if (normalized.isEmpty() || normalized.size > MAX_EXPRESSION_TERMS) continue
            val key = expressionKey(normalized)
            if (key in seen) continue
            if (seen.size >= MAX_EXPANDED_EXPRESSIONS) return seen.values.toList()
            seen[key] = normalized
            queue += normalized
        }
    }
    return seen.values.toList()
}

private fun getExpandedExpression(
    terms: List<ExpressionTerm>,
    rules: List<ParsedFlexibleRule>,
    cache: MutableMap<String, List<List<ExpressionTerm>>>,
): List<List<ExpressionTerm>> = cache.getOrPut(expressionKey(terms)) { expandExpression(terms, rules) }

private fun applyBestFlexibleWeightRule(
    targetWeight: MutableMap<String, Double>,
    currentWeight: Map<String, Double>,
    leftCandidates: List<List<ExpressionTerm>>,
    rightCandidates: List<List<ExpressionTerm>>,
): Boolean {
    var bestLeft: List<ExpressionTerm>? = null
    var bestRight: List<ExpressionTerm>? = null
    var bestShift = 0.0
    for (left in leftCandidates) for (right in rightCandidates) {
        if (expressionKey(left) == expressionKey(right)) continue
        val shift = flexibleWeightShift(targetWeight, currentWeight, left, right)
        if (abs(shift) > abs(bestShift) + EPSILON) {
            bestLeft = left
            bestRight = right
            bestShift = shift
        }
    }
    return if (bestLeft != null && bestRight != null && abs(bestShift) > EPSILON) {
        applyFlexibleWeightRule(targetWeight, currentWeight, bestLeft, bestRight)
    } else false
}

private fun applyBestFlexibleRebalRule(
    rebalDollars: MutableMap<String, Double>,
    leftCandidates: List<List<ExpressionTerm>>,
    rightCandidates: List<List<ExpressionTerm>>,
): Boolean {
    var bestLeft: List<ExpressionTerm>? = null
    var bestRight: List<ExpressionTerm>? = null
    var bestShift = 0.0
    for (left in leftCandidates) for (right in rightCandidates) {
        if (expressionKey(left) == expressionKey(right)) continue
        val shift = flexibleRebalShift(rebalDollars, left, right)
        if (abs(shift) > abs(bestShift) + EPSILON) {
            bestLeft = left
            bestRight = right
            bestShift = shift
        }
    }
    return if (bestLeft != null && bestRight != null && abs(bestShift) > EPSILON) {
        applyFlexibleRebalRule(rebalDollars, bestLeft, bestRight)
    } else false
}
