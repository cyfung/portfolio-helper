package com.portfoliohelper.service

import com.portfoliohelper.model.Stock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.math.*
import kotlin.random.Random

// ── Public snapshot ───────────────────────────────────────────────────────────

@Serializable
data class RebalAllocSnapshot(
    val portfolioId: String,
    val perSymbolAllocUsd: Map<String, Double>
)

// ── Internal GA types ─────────────────────────────────────────────────────────

private data class GaGroup(
    val name: String,
    val members: List<String>,
    val weights: Map<String, Double>,  // symbol → multiplier
    val targetWeight: Double           // fraction 0–1
)

private data class GaStockInput(
    val symbol: String,
    val targetWeightFrac: Double,      // 0–1, normalized across all stocks
    val currentValUsd: Double
)

private data class TierResult(
    val allocs: Map<Int, Double>,      // stockIndex → dollarAlloc
    val elites: List<DoubleArray>      // elite genomes from this tier
)

// ── GA algorithm — ported 1-to-1 from rebalance-ga.js ────────────────────────

private fun gaNormalizeAllocs(raw: DoubleArray, caps: DoubleArray, cash: Double): DoubleArray {
    val n = raw.size
    val clamped = DoubleArray(n) { i -> raw[i].coerceIn(0.0, caps[i]) }
    val sum = clamped.sum()
    val result = if (sum < 0.01) {
        DoubleArray(n) { i -> min(cash / n, caps[i]) }
    } else {
        DoubleArray(n) { i -> min(clamped[i] * cash / sum, caps[i]) }
    }
    var rem = cash - result.sum()
    repeat(10) {
        if (rem <= 0.01) return@repeat
        val free = result.indices.filter { result[it] < caps[it] - 0.01 }
        if (free.isEmpty()) return@repeat
        val add = rem / free.size
        for (i in free) {
            val d = min(add, caps[i] - result[i])
            result[i] += d
            rem -= d
        }
    }
    return result
}

private fun gaMedian(arr: DoubleArray): Double {
    val sorted = arr.copyOf().also { it.sort() }
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
}

private fun gaHuber(x: Double, delta: Double = 0.005): Double {
    val ax = abs(x)
    return if (ax <= delta) x * x else 2.0 * delta * ax - delta * delta
}

private fun solveGATier(
    tierGroups: List<GaGroup>,
    stocks: List<GaStockInput>,
    currentVals: Map<String, Double>,
    eligibleIdxs: List<Int>,
    cash: Double,
    newTotal: Double,
    isLast: Boolean,
    prevElites: List<DoubleArray>,
    tierSize: Int,
    isSell: Boolean,
    rng: Random
): TierResult {
    val POP = 80; val GENS = 2000; val ELITE = 8
    val MUTRATE = 0.3; val MUTSCALE = 0.25
    val m = eligibleIdxs.size
    if (m == 0 || cash < 0.01) {
        return TierResult(eligibleIdxs.associate { it to 0.0 }, emptyList())
    }
    val tierCaps = DoubleArray(m) { cash }

    fun projDev(g: GaGroup, allocs: DoubleArray): Double {
        var mv = 0.0
        for (t in g.members) {
            val si = stocks.indexOfFirst { it.symbol == t }
            val li = eligibleIdxs.indexOf(si)
            val contribution = if (li >= 0) allocs[li] else 0.0
            val w = g.weights[t] ?: 1.0
            mv += ((currentVals[t] ?: 0.0) + if (isSell) -contribution else contribution) * w
        }
        return mv / newTotal - g.targetWeight
    }

    fun fitness(allocs: DoubleArray): Double {
        val groupDevs = DoubleArray(tierGroups.size) { projDev(tierGroups[it], allocs) }
        val groupMed = gaMedian(groupDevs)
        val groupVariance = groupDevs.sumOf { gaHuber(it - groupMed) }
        var stockVariance = 0.0
        for (g in tierGroups) {
            val memberDevsList = mutableListOf<Double>()
            for (t in g.members) {
                val si = stocks.indexOfFirst { it.symbol == t }
                if (si < 0) continue
                val li = eligibleIdxs.indexOf(si)
                val contribution = if (li >= 0) allocs[li] else 0.0
                val w = g.weights[t] ?: 1.0
                memberDevsList += ((currentVals[t] ?: 0.0) + if (isSell) -contribution else contribution) * w / newTotal -
                        stocks[si].targetWeightFrac * w
            }
            if (memberDevsList.size < 2) continue
            val memberDevs = memberDevsList.toDoubleArray()
            val memberMed = gaMedian(memberDevs)
            stockVariance += memberDevs.sumOf { gaHuber(it - memberMed) }
        }
        return groupVariance + stockVariance * 0.5
    }

    fun norm(raw: DoubleArray) = gaNormalizeAllocs(raw, tierCaps, cash)
    fun seed() = norm(DoubleArray(m) { cash / m })
    fun rnd() = norm(DoubleArray(m) { rng.nextDouble() })
    fun mutate(ind: DoubleArray) = norm(DoubleArray(m) { i ->
        if (rng.nextDouble() < MUTRATE) (ind[i] / cash + (rng.nextDouble() * 2 - 1) * MUTSCALE) * cash else ind[i]
    })
    fun cross(a: DoubleArray, b: DoubleArray) = norm(DoubleArray(m) { i -> if (rng.nextDouble() < 0.5) a[i] else b[i] })
    fun avg(a: DoubleArray, b: DoubleArray) = norm(DoubleArray(m) { i -> (a[i] + b[i]) / 2.0 })

    val eliteW = max(tierSize - 1, 0).toDouble() / tierSize
    val rndW = 1.0 / tierSize
    val warmSeeds = prevElites.flatMap { elite ->
        val base = DoubleArray(m) { li -> elite.getOrElse(li) { cash / m } }
        (0..1).map {
            val rndGene = DoubleArray(m) { rng.nextDouble() }
            norm(DoubleArray(m) { i -> (eliteW * base[i] / cash + rndW * rndGene[i]) * cash })
        }
    }
    var pop: MutableList<DoubleArray> = buildList {
        add(seed())
        addAll(warmSeeds)
        repeat(max(0, POP - 1 - warmSeeds.size)) { add(rnd()) }
    }.take(POP).toMutableList()
    var scores: MutableList<Double> = pop.map { fitness(it) }.toMutableList()

    fun evolveStep(breakFitness: Double): Boolean {
        val ranked = scores.indices.sortedBy { scores[it] }
        if (scores[ranked[0]] < breakFitness) return true
        val elites = ranked.take(ELITE).map { pop[it] }
        val np = elites.toMutableList()
        while (np.size < POP) {
            val pa = elites[rng.nextInt(ELITE)]
            val pb = elites[rng.nextInt(ELITE)]
            val r = rng.nextDouble()
            np += if (r < 0.33) mutate(pa) else if (r < 0.66) cross(pa, pb) else avg(pa, pb)
        }
        pop = np
        scores = pop.map { fitness(it) }.toMutableList()
        return false
    }

    for (gen in 0 until GENS) { if (evolveStep(1e-6)) break }
    val extraGens = if (isLast) 1000 else 0
    for (gen in 0 until extraGens) { if (evolveStep(1e-10)) break }

    val ranked = scores.indices.sortedBy { scores[it] }
    val best = pop[ranked[0]]
    val elites = ranked.take(ELITE).map { pop[it] }
    return TierResult(
        allocs = eligibleIdxs.mapIndexed { li, si -> si to best[li] }.toMap(),
        elites = elites
    )
}

private fun runStepwiseGA(
    stocks: List<GaStockInput>,
    groups: List<GaGroup>,
    currentVals: Map<String, Double>,
    cash: Double,
    newTotal: Double,
    isSell: Boolean,
    rng: Random
): DoubleArray {
    val totalPortfolio = currentVals.values.sum()

    fun gDev(g: GaGroup, vals: Map<String, Double>, denom: Double): Double {
        val mv = g.members.sumOf { t -> (vals[t] ?: 0.0) * (g.weights[t] ?: 1.0) }
        return mv / (if (denom > 0) denom else newTotal) - g.targetWeight
    }

    fun applyAllocs(base: Map<String, Double>, idxs: List<Int>, allocs: Map<Int, Double>): Map<String, Double> {
        val out = base.toMutableMap()
        for (si in idxs) {
            val sym = stocks[si].symbol
            out[sym] = (base[sym] ?: 0.0) + if (isSell) -(allocs[si] ?: 0.0) else (allocs[si] ?: 0.0)
        }
        return out
    }

    fun stockIdxsFor(gs: List<GaGroup>): List<Int> =
        gs.flatMap { it.members }.toSet()
            .map { t -> stocks.indexOfFirst { it.symbol == t } }
            .filter { it >= 0 }

    val sorted = groups.sortedWith { a, b ->
        val devA = gDev(a, currentVals, totalPortfolio)
        val devB = gDev(b, currentVals, totalPortfolio)
        if (isSell) compareValues(devB, devA) else compareValues(devA, devB)
    }

    var finalAllocsMap: Map<Int, Double> = emptyMap()
    var finalIdxs: List<Int> = emptyList()
    var finalTargetGroups: List<GaGroup> = emptyList()
    var prevElites: List<DoubleArray> = emptyList()

    for (size in 1..sorted.size) {
        val targetGroups = sorted.take(size)
        val idxs = stockIdxsFor(targetGroups)
        val tierResult = solveGATier(
            targetGroups, stocks, currentVals, idxs, cash, newTotal,
            isLast = false, prevElites = prevElites, tierSize = size, isSell = isSell, rng = rng
        )
        prevElites = tierResult.elites

        val projVals = applyAllocs(currentVals, idxs, tierResult.allocs)
        val devDenom = if (isSell) totalPortfolio else newTotal
        val targetDevs = DoubleArray(targetGroups.size) { gDev(targetGroups[it], projVals, devDenom) }
        val targetMedian = gaMedian(targetDevs)

        finalAllocsMap = tierResult.allocs
        finalIdxs = idxs
        finalTargetGroups = targetGroups

        val nonTarget = sorted.drop(size)
        if (nonTarget.isEmpty()) break

        val lowestNonTargetDev = gDev(nonTarget[0], if (isSell) currentVals else projVals, devDenom)
        if (if (isSell) targetMedian >= lowestNonTargetDev else targetMedian <= lowestNonTargetDev) break
    }

    val refined = solveGATier(
        finalTargetGroups, stocks, currentVals, finalIdxs, cash, newTotal,
        isLast = true, prevElites = prevElites, tierSize = finalTargetGroups.size, isSell = isSell, rng = rng
    )
    val finalAllocs = finalAllocsMap.toMutableMap()
    for (si in finalIdxs) finalAllocs[si] = refined.allocs[si] ?: 0.0

    val result = DoubleArray(stocks.size)
    for (si in finalIdxs) result[si] = finalAllocs[si] ?: 0.0
    return result
}

// ── Simple allocation modes ───────────────────────────────────────────────────

private fun computeSimpleAllocs(
    delta: Double,
    stocks: List<StockDisplay>,
    stockGrossUsd: Double,
    mode: String
): Map<String, Double> {
    return when (mode) {
        "PROPORTIONAL" -> stocks.associate { s ->
            s.symbol to ((s.targetWeightPct ?: 0.0) / 100.0) * delta
        }
        "CURRENT_WEIGHT" -> stocks.associate { s ->
            val w = if (stockGrossUsd > 0) (s.positionValueUsd ?: 0.0) / stockGrossUsd else 0.0
            s.symbol to w * delta
        }
        "UNDERVALUED_PRIORITY" -> {
            val eligible = stocks.filter { it.targetWeightPct != null }
            val alloc = eligible.associate { it.symbol to 0.0 }.toMutableMap()
            val finalTotal = stockGrossUsd + delta
            val sign = if (delta >= 0) 1.0 else -1.0
            val sorted = eligible.sortedBy { s ->
                sign * ((s.positionValueUsd ?: 0.0) / finalTotal - (s.targetWeightPct ?: 0.0) / 100.0)
            }
            var remaining = abs(delta)
            for (s in sorted) {
                if (remaining <= 0) break
                val target = finalTotal * ((s.targetWeightPct ?: 0.0) / 100.0)
                val amount = min(remaining, max(0.0, (target - (s.positionValueUsd ?: 0.0)) * sign))
                alloc[s.symbol] = amount * sign
                remaining -= amount
            }
            if (remaining > 0.01) {
                for (s in eligible) {
                    alloc[s.symbol] = (alloc[s.symbol] ?: 0.0) + (s.targetWeightPct ?: 0.0) / 100.0 * remaining * sign
                }
            }
            alloc
        }
        else -> emptyMap()
    }
}

// ── Group building ─────────────────────────────────────────────────────────────

private fun buildGaGroups(stocks: List<Stock>): List<GaGroup> {
    // groupName → (symbol → sum of targetWeight * mult)
    val groupMap = mutableMapOf<String, MutableMap<String, Double>>()
    for (stock in stocks) {
        val tw = stock.targetWeight ?: 0.0
        for ((mult, groupName) in stock.groups) {
            val memberMap = groupMap.getOrPut(groupName) { mutableMapOf() }
            memberMap[stock.label] = (memberMap[stock.label] ?: 0.0) + tw * mult
        }
    }
    return groupMap.map { (name, memberMap) ->
        val weights = memberMap.mapValues { (sym, wtw) ->
            val tw = stocks.find { it.label == sym }?.targetWeight ?: 0.0
            if (tw > 0) wtw / tw else 1.0
        }
        GaGroup(
            name = name,
            members = memberMap.keys.toList(),
            weights = weights,
            targetWeight = memberMap.values.sum() / 100.0
        )
    }
}

// ── RebalGaService ─────────────────────────────────────────────────────────────

@OptIn(FlowPreview::class)
class RebalGaService(
    private val portfolioId: String,
    private val stocks: StateFlow<List<Stock>>,
    private val stockDisplay: StockDisplayService,
    private val cashDisplay: CashDisplayService,
    private val config: StateFlow<Map<String, String>>,
    scope: CoroutineScope
) {
    private val _updates = MutableSharedFlow<RebalAllocSnapshot>(replay = 1, extraBufferCapacity = 4)
    val updates: SharedFlow<RebalAllocSnapshot> = _updates.asSharedFlow()

    init {
        scope.launch {
            combine(stockDisplay.updates, cashDisplay.updates, config, stocks) { s, c, cfg, raw ->
                Quad(s, c, cfg, raw)
            }
                .debounce(300)
                .collect { (stockSnap, cashSnap, cfg, rawStocks) ->
                    val result = withContext(Dispatchers.Default) {
                        compute(stockSnap, cashSnap, cfg, rawStocks)
                    }
                    _updates.emit(result)
                }
        }
    }

    private fun compute(
        stockSnap: StockDisplaySnapshot,
        cashSnap: CashDisplaySnapshot,
        cfg: Map<String, String>,
        rawStocks: List<Stock>
    ): RebalAllocSnapshot {
        if (!stockSnap.stockGrossKnown) return RebalAllocSnapshot(portfolioId, emptyMap())

        val stockGrossUsd = stockSnap.stockGrossUsd
        val marginUsd = cashSnap.marginBaseUsd
        val rebalTargetUsd = cfg["rebalTarget"]?.toDoubleOrNull()
        val marginTargetPct = cfg["marginTarget"]?.toDoubleOrNull()
        val marginTargetUsd = cfg["marginTargetUsd"]?.toDoubleOrNull()
        val allocAddMode = cfg["allocAddMode"] ?: "WATERFALL"
        val allocReduceMode = cfg["allocReduceMode"] ?: "PROPORTIONAL"

        val ec = stockGrossUsd + marginUsd  // equity (unleveraged base)
        val rebalTotal = when {
            rebalTargetUsd != null && rebalTargetUsd > 0 -> rebalTargetUsd
            marginTargetPct != null && marginTargetPct > 0 -> ec * (1.0 + marginTargetPct / 100.0)
            marginTargetUsd != null && marginTargetUsd > 0 -> ec + marginTargetUsd
            else -> stockGrossUsd + max(marginUsd, 0.0)
        }

        val delta = rebalTotal - stockGrossUsd
        if (abs(delta) < 0.01) return RebalAllocSnapshot(portfolioId, emptyMap())

        val allocMode = if (delta >= 0) allocAddMode else allocReduceMode

        if (allocMode != "WATERFALL") {
            return RebalAllocSnapshot(portfolioId, computeSimpleAllocs(delta, stockSnap.stocks, stockGrossUsd, allocMode))
        }

        // WATERFALL: run GA
        val twSum = stockSnap.stocks.sumOf { it.targetWeightPct ?: 0.0 }
        if (twSum <= 0) return RebalAllocSnapshot(portfolioId, emptyMap())

        val gaStocks = stockSnap.stocks.map { s ->
            GaStockInput(
                symbol = s.symbol,
                targetWeightFrac = (s.targetWeightPct ?: 0.0) / twSum,
                currentValUsd = s.positionValueUsd ?: 0.0
            )
        }
        val currentValsUsd = gaStocks.associate { it.symbol to it.currentValUsd }
        val gaGroups = buildGaGroups(rawStocks)
        val totalStockValue = gaStocks.sumOf { it.currentValUsd }

        val isSell = delta < 0
        val cash = abs(delta)
        val newTotal = totalStockValue + if (isSell) -cash else cash

        val exactAllocs = gaStocks.map { s ->
            val ideal = newTotal * s.targetWeightFrac - s.currentValUsd
            if (isSell) max(0.0, -ideal) else max(0.0, ideal)
        }
        val totalNeeded = exactAllocs.sum()

        val rng = Random.Default
        val allocArr: DoubleArray = if (cash >= totalNeeded) {
            val remaining = cash - totalNeeded
            DoubleArray(gaStocks.size) { i -> exactAllocs[i] + remaining * gaStocks[i].targetWeightFrac }
        } else {
            runStepwiseGA(gaStocks, gaGroups, currentValsUsd, cash, newTotal, isSell, rng)
        }

        val sign = if (isSell) -1.0 else 1.0
        return RebalAllocSnapshot(
            portfolioId = portfolioId,
            perSymbolAllocUsd = gaStocks.mapIndexed { i, s -> s.symbol to sign * allocArr[i] }.toMap()
        )
    }
}

/** Simple 4-tuple for combine() — avoids a Triple + extra nesting. */
private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

private operator fun <A, B, C, D> Quad<A, B, C, D>.component1() = first
private operator fun <A, B, C, D> Quad<A, B, C, D>.component2() = second
private operator fun <A, B, C, D> Quad<A, B, C, D>.component3() = third
private operator fun <A, B, C, D> Quad<A, B, C, D>.component4() = fourth
