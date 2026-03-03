package com.portfoliohelper.service

import com.portfoliohelper.AppDirs
import com.portfoliohelper.service.yahoo.YahooHistoricalFetcher
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.temporal.IsoFields
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

// ── Domain types ──────────────────────────────────────────────────────────────

enum class RebalanceStrategy { NONE, DAILY, WEEKLY, MONTHLY, QUARTERLY, YEARLY }

enum class MarginRebalanceMode { CURRENT_WEIGHT, PROPORTIONAL, FULL_REBALANCE, UNDERVALUED_PRIORITY, DAILY }

data class TickerWeight(val ticker: String, val weight: Double)

data class LETFComponent(val ticker: String, val multiplier: Double)

data class LETFDefinition(
    val components: List<LETFComponent>,
    val spread: Double,                                                       // annual fraction, default 0.015
    val rebalanceStrategy: RebalanceStrategy = RebalanceStrategy.QUARTERLY   // default Q
) {
    val totalMultiplier: Double get() = components.sumOf { it.multiplier }
    val borrowedRatio: Double get() = totalMultiplier - 1.0
}

data class MarginConfig(
    val marginRatio: Double,        // e.g. 0.5 = 50% borrow-to-equity
    val marginSpread: Double,       // annualised fraction e.g. 0.015
    val marginDeviationUpper: Double, // upper breach threshold e.g. 0.05
    val marginDeviationLower: Double, // lower breach threshold e.g. 0.05
    val upperRebalanceMode: MarginRebalanceMode = MarginRebalanceMode.PROPORTIONAL,
    val lowerRebalanceMode: MarginRebalanceMode = MarginRebalanceMode.PROPORTIONAL
)

data class PortfolioConfig(
    val label: String,
    val tickers: List<TickerWeight>,
    val rebalanceStrategy: RebalanceStrategy,
    val marginStrategies: List<MarginConfig>  // empty = base curve only
)

data class MultiBacktestRequest(
    val fromDate: String?,
    val toDate: String?,
    val portfolios: List<PortfolioConfig>  // 1–3
)

data class DataPoint(val date: String, val value: Double)

data class BacktestStats(
    val cagr: Double,
    val maxDrawdown: Double,
    val sharpe: Double,
    val endingValue: Double,
    val marginUpperTriggers: Int? = null,   // deviation breach above target (market fell, leverage too high)
    val marginLowerTriggers: Int? = null    // deviation breach below target (market rose, leverage too low)
)

data class CurveResult(
    val label: String,
    val points: List<DataPoint>,
    val stats: BacktestStats
)

data class PortfolioResult(
    val label: String,
    val curves: List<CurveResult>  // index 0 = no-margin; rest = margin variants
)

data class MultiBacktestResult(
    val portfolios: List<PortfolioResult>
)

// ── Service ───────────────────────────────────────────────────────────────────

object BacktestService {
    private val logger = LoggerFactory.getLogger(BacktestService::class.java)
    private val tickerDir get() = AppDirs.dataDir.resolve(".ticker").toFile()

    fun runMulti(request: MultiBacktestRequest): MultiBacktestResult {
        val fromDate = request.fromDate?.let { LocalDate.parse(it) }
        val toDate = request.toDate?.let { LocalDate.parse(it) } ?: LocalDate.now()
        val effrxSeries = loadEffrxSeries()
        val neededFrom = fromDate ?: LocalDate.of(1990, 1, 1)

        // Step 1: Collect all unique LETF definitions from all portfolio tickers
        val letfDefs = mutableMapOf<String, LETFDefinition>()
        for (pConfig in request.portfolios) {
            for (tw in pConfig.tickers) {
                val def = parseLETFDefinition(tw.ticker) ?: continue
                letfDefs.putIfAbsent(tw.ticker, def)
            }
        }

        // Step 2: Load component ticker series for all LETF definitions
        val seriesCache = mutableMapOf<String, Map<LocalDate, Double>>()
        fun cachedLoad(ticker: String) =
            seriesCache.getOrPut(ticker) { loadNormalizedSeries(ticker, neededFrom) }

        val letfComponentTickers = letfDefs.values
            .flatMap { def -> def.components.map { it.ticker } }
            .toSet()
        for (ticker in letfComponentTickers) cachedLoad(ticker)

        // Step 3: Compute preliminary dates from component series (needed to run LETF simulation)
        val componentSeriesForDates = letfComponentTickers.mapNotNull { seriesCache[it] }
        val letfDates = if (componentSeriesForDates.isNotEmpty())
            intersectDates(componentSeriesForDates, fromDate, toDate)
        else emptyList()

        // Step 4: Compute virtual LETF series. Each LETF string is self-contained (R= default Q,
        // S= default 1.5%), so the string itself is the cache key — no outer portfolio dependency.
        if (letfDates.size >= 2) {
            for ((letfString, def) in letfDefs) {
                if (letfString !in seriesCache) {
                    val componentSeriesMap = def.components.associate { comp ->
                        comp.ticker to (seriesCache[comp.ticker]
                            ?: error("Component ticker ${comp.ticker} was not loaded"))
                    }
                    seriesCache[letfString] = computeLetfSeries(
                        def, componentSeriesMap, letfDates, effrxSeries, def.rebalanceStrategy
                    )
                }
            }
        }

        // Step 5: Load real (non-LETF) ticker series
        val realTickers = request.portfolios
            .flatMap { it.tickers }
            .map { it.ticker }
            .filter { parseLETFDefinition(it) == null }
            .toSet()
        for (ticker in realTickers) cachedLoad(ticker)

        // Step 6: Build allSeriesMaps and compute global date intersection.
        val allSeriesMaps = request.portfolios.map { pConfig ->
            pConfig.tickers.associate { tw ->
                tw.ticker to (seriesCache[tw.ticker]
                    ?: error("Series for '${tw.ticker}' not found in cache"))
            }
        }
        val globalDates = intersectDates(allSeriesMaps.flatMap { it.values }, fromDate, toDate)
        if (globalDates.size < 2) {
            throw IllegalStateException("Not enough overlapping trading dates across all portfolios")
        }

        // Step 7: Compute portfolio results
        val portfolioResults = request.portfolios.mapIndexed { idx, pConfig ->
            val seriesMap = allSeriesMaps[idx]

            val noMarginValues = computeNoMargin(pConfig, seriesMap, globalDates)
            val noMarginPoints =
                globalDates.mapIndexed { i, d -> DataPoint(d.toString(), noMarginValues[i]) }
            val noMarginStats = computeStats(noMarginValues, globalDates, effrxSeries)
            val curves = mutableListOf(CurveResult("No Margin", noMarginPoints, noMarginStats))

            pConfig.marginStrategies.forEachIndexed { mIdx, mc ->
                val marginResult =
                    if (mc.upperRebalanceMode != MarginRebalanceMode.CURRENT_WEIGHT ||
                        mc.lowerRebalanceMode != MarginRebalanceMode.CURRENT_WEIGHT
                    )
                        applyMarginProportional(pConfig, seriesMap, globalDates, effrxSeries, mc)
                    else
                        applyMargin(
                            noMarginValues,
                            globalDates,
                            effrxSeries,
                            mc,
                            pConfig.rebalanceStrategy
                        )
                val marginPoints = globalDates.mapIndexed { i, d ->
                    DataPoint(
                        d.toString(),
                        marginResult.values[i]
                    )
                }
                val marginStats = computeStats(
                    marginResult.values, globalDates, effrxSeries,
                    marginResult.upperTriggers, marginResult.lowerTriggers
                )

                fun modeAbbr(m: MarginRebalanceMode) = when (m) {
                    MarginRebalanceMode.CURRENT_WEIGHT -> "Cur Wt"
                    MarginRebalanceMode.PROPORTIONAL -> "Tgt Wt"
                    MarginRebalanceMode.FULL_REBALANCE -> "Full"
                    MarginRebalanceMode.UNDERVALUED_PRIORITY -> "UVal"
                    MarginRebalanceMode.DAILY -> "Daily"
                }

                val uAbbr = modeAbbr(mc.upperRebalanceMode)
                val lAbbr = modeAbbr(mc.lowerRebalanceMode)
                val label = if (uAbbr == lAbbr) "Margin ${mIdx + 1} ($uAbbr)"
                else "Margin ${mIdx + 1} ($uAbbr↑/$lAbbr↓)"
                curves.add(CurveResult(label, marginPoints, marginStats))
            }
            PortfolioResult(pConfig.label, curves)
        }

        return MultiBacktestResult(portfolioResults)
    }

    // ── Ticker data loading ───────────────────────────────────────────────────


    private fun getResourceFiles(path: String): List<String> {
        val url = object {}.javaClass.classLoader.getResource(path) ?: return emptyList()
        val uri = url.toURI()
        val dirPath: Path = if (uri.scheme == "jar") {
            val fs = try {
                FileSystems.newFileSystem(uri, emptyMap<String, Any>())
            } catch (_: java.nio.file.FileSystemAlreadyExistsException) {
                FileSystems.getFileSystem(uri)
            }
            fs.getPath("/$path")
        } else {
            java.nio.file.Paths.get(uri)
        }
        return Files.list(dirPath).use { stream ->
            stream.map { it.fileName.toString() }.toList()
        }
    }

    /**
     * Loads (or fetches) a normalised series for a ticker.
     * Normalised means the series values are chain-linked returns starting at 10 000.
     */
    private fun loadNormalizedSeries(
        ticker: String,
        neededFromDate: LocalDate
    ): Map<LocalDate, Double> {
        require(!ticker.contains(' ')) {
            "loadNormalizedSeries called with LETF string '$ticker' — LETF series must be pre-computed via computeLetfSeries"
        }
        tickerDir.mkdirs()
        val upperTicker = ticker.uppercase()
        val simPattern = Regex("${upperTicker}-(\\d{4}-\\d{2}-\\d{2})\\.csv")

        // Find latest SIM file
        val existingFiles = findOrCopyFromResources(simPattern)

        if (existingFiles.isNotEmpty()) {
            val latestFile = existingFiles.first()
            val latestDateStr = simPattern.find(latestFile.name)!!.groupValues[1]
            val latestDate = LocalDate.parse(latestDateStr)

            logger.info("Loading SIM file for $upperTicker: ${latestFile.name}")
            val existing = readSimCsv(latestFile)

            val today = LocalDate.now()
            if (latestDate >= today) {
                return existing
            }

            // Extend with Yahoo data
            logger.info("Extending $upperTicker SIM from $latestDate to $today via Yahoo")
            return try {
                val extension = YahooHistoricalFetcher.fetchAdjustedClose(ticker, latestDate, today)
                val extended = chainExtend(existing, extension, latestDate)
                val newFile = File(tickerDir, "${upperTicker}-${today}.csv")
                writeSimCsv(newFile, extended)
                extended
            } catch (e: Exception) {
                logger.warn("Failed to extend $upperTicker via Yahoo: ${e.message}. Using existing SIM.")
                existing
            }

        } else {
            // No SIM file — fetch from Yahoo and create one
            logger.info("No SIM file for $upperTicker, fetching from Yahoo since $neededFromDate")
            val today = LocalDate.now()
            val raw = YahooHistoricalFetcher.fetchAdjustedClose(ticker, neededFromDate, today)
            if (raw.isEmpty()) throw IllegalStateException("No Yahoo data for $ticker from $neededFromDate")
            val normalized = normalizeFromFirst(raw, 10_000.0)
            val newFile = File(tickerDir, "${upperTicker}-${today}.csv")
            writeSimCsv(newFile, normalized)
            return normalized
        }
    }

    private fun findOrCopyFromResources(simPattern: Regex): List<File> =
        findFiles(simPattern).ifEmpty {
            // find and copy from resource folder if missing
            val resourcesFile = getResourceFiles("data/.ticker").firstOrNull {
                simPattern.matches(it)
            } ?: return@ifEmpty emptyList()
            logger.info("ticker {} found in resource", resourcesFile)
            val cl = object {}::class.java.classLoader
            cl.getResourceAsStream("data/.ticker/$resourcesFile")
                ?.use { Files.copy(it, tickerDir.toPath().resolve(resourcesFile)) }
            findFiles(simPattern)
        }

    private fun findFiles(simPattern: Regex): List<File> = (tickerDir.listFiles()
        ?.filter { simPattern.matches(it.name) }
        ?.sortedByDescending { it.name }
        ?: emptyList())

    /** Loads EFFRX from the latest SIM file (no Yahoo extension). Returns empty map if not found. */
    private fun loadEffrxSeries(): Map<LocalDate, Double> {
        tickerDir.mkdirs()
        val simPattern = Regex("EFFRX-(\\d{4}-\\d{2}-\\d{2})\\.csv")
        val latest = findOrCopyFromResources(simPattern)
            .firstOrNull()
            ?: return emptyMap()
        logger.info("Loading EFFRX from ${latest.name}")
        return readSimCsv(latest)
    }

    // ── LETF helpers ──────────────────────────────────────────────────────────

    /**
     * Parses a LETF definition string like "1 KMLM 1 VT S=1.5 R=Q".
     * Returns null for plain tickers (no spaces).
     * Tokens: alternating <multiplier> <TICKER> pairs, plus optional S=<pct> and R=<strategy>.
     * R values: D=Daily, W=Weekly, M=Monthly, Q=Quarterly, Y=Yearly (default: null = inherit from outer portfolio).
     */
    private fun parseLETFDefinition(ticker: String): LETFDefinition? {
        if (!ticker.contains(' ')) return null
        val tokens = ticker.trim().split(Regex("\\s+"))
        val components = mutableListOf<LETFComponent>()
        var spread = 0.015
        var rebalanceStrategy = RebalanceStrategy.QUARTERLY
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            if (token.startsWith("S=", ignoreCase = true)) {
                spread = token.substring(2).toDoubleOrNull()?.div(100.0) ?: 0.0
                i++
            } else if (token.startsWith("R=", ignoreCase = true)) {
                rebalanceStrategy = when (token.substring(2).uppercase()) {
                    "D" -> RebalanceStrategy.DAILY
                    "W" -> RebalanceStrategy.WEEKLY
                    "M" -> RebalanceStrategy.MONTHLY
                    "Q" -> RebalanceStrategy.QUARTERLY
                    "Y" -> RebalanceStrategy.YEARLY
                    else -> rebalanceStrategy
                }
                i++
            } else {
                val multiplier = token.toDoubleOrNull()
                if (multiplier != null && i + 1 < tokens.size) {
                    val tickerName = tokens[i + 1]
                    if (!tickerName.startsWith("S=", ignoreCase = true) &&
                        !tickerName.startsWith("R=", ignoreCase = true)
                    ) {
                        components.add(LETFComponent(tickerName.uppercase(), multiplier))
                        i += 2
                    } else {
                        i++
                    }
                } else {
                    i++
                }
            }
        }
        if (components.isEmpty()) throw IllegalArgumentException("No components found in LETF definition: $ticker")
        return LETFDefinition(components, spread, rebalanceStrategy)
    }

    /**
     * Computes a synthetic equity series for a LETF definition.
     * Simulated as a periodically-rebalanced portfolio of its components
     * with DAILY margin and borrow spread equal to [def.spread].
     * [rebalanceStrategy] is the effective strategy: [def.rebalanceStrategy] if specified via R=,
     * otherwise the outer portfolio's strategy.
     * Returns a Map<LocalDate, Double> parallel to [dates], starting at 10,000.
     */
    private fun computeLetfSeries(
        def: LETFDefinition,
        componentSeriesMap: Map<String, Map<LocalDate, Double>>,
        dates: List<LocalDate>,
        effrx: Map<LocalDate, Double>,
        rebalanceStrategy: RebalanceStrategy
    ): Map<LocalDate, Double> {
        val letfTickers = def.components.map { comp ->
            TickerWeight(comp.ticker, comp.multiplier / def.totalMultiplier * 100.0)
        }
        val letfConfig = PortfolioConfig(
            label = "_letf_virtual",
            tickers = letfTickers,
            rebalanceStrategy = rebalanceStrategy,
            marginStrategies = emptyList()
        )
        val mc = MarginConfig(
            marginRatio = def.borrowedRatio,
            marginSpread = def.spread,
            marginDeviationUpper = 0.0,
            marginDeviationLower = 0.0,
            upperRebalanceMode = MarginRebalanceMode.DAILY,
            lowerRebalanceMode = MarginRebalanceMode.DAILY
        )
        val result = applyMarginProportional(letfConfig, componentSeriesMap, dates, effrx, mc)
        return dates.zip(result.values).associate { (date, value) -> date to value }
    }

    // ── CSV helpers ───────────────────────────────────────────────────────────

    private fun readSimCsv(file: File): Map<LocalDate, Double> {
        val result = mutableMapOf<LocalDate, Double>()
        file.bufferedReader().use { br ->
            br.readLine() // skip header
            br.forEachLine { line ->
                val cols = line.split(",")
                if (cols.size >= 2) {
                    val date = runCatching { LocalDate.parse(cols[0].trim()) }.getOrNull()
                        ?: return@forEachLine
                    val value = cols[1].trim().toDoubleOrNull() ?: return@forEachLine
                    result[date] = value
                }
            }
        }
        return result
    }

    private fun writeSimCsv(file: File, series: Map<LocalDate, Double>) {
        file.bufferedWriter().use { out ->
            out.write("date,value")
            out.newLine()
            series.keys.sorted().forEach { date ->
                out.write("$date,${series[date]}")
                out.newLine()
            }
        }
        logger.info("Saved SIM file: ${file.name} (${series.size} rows)")
    }

    // ── Chain-link extension ──────────────────────────────────────────────────

    /**
     * Extends [existing] (date → normalised value) by chaining returns from [yahoo] (date → raw adj-close).
     * The last date in [existing] is used as the pivot.
     */
    private fun chainExtend(
        existing: Map<LocalDate, Double>,
        yahoo: Map<LocalDate, Double>,
        lastSimDate: LocalDate
    ): Map<LocalDate, Double> {
        val result = existing.toMutableMap()
        val sortedYahooDates = yahoo.keys.filter { it > lastSimDate }.sorted()
        if (sortedYahooDates.isEmpty()) return result

        // Find the pivot yahoo price (last date in sim range that has a yahoo price)
        val pivotDate = yahoo.keys.filter { it <= lastSimDate }.maxOrNull() ?: return result
        val pivotYahooPrice = yahoo[pivotDate] ?: return result

        // Find the last sim value
        val lastSimValue =
            existing[lastSimDate] ?: existing.keys.filter { it <= lastSimDate }.maxOrNull()
                ?.let { existing[it] } ?: return result

        var prevYahoo = pivotYahooPrice
        var prevValue = lastSimValue

        for (date in sortedYahooDates) {
            val currentYahoo = yahoo[date] ?: continue
            if (prevYahoo == 0.0) {
                prevYahoo = currentYahoo; continue
            }
            val ret = currentYahoo / prevYahoo
            val newValue = prevValue * ret
            result[date] = newValue
            prevYahoo = currentYahoo
            prevValue = newValue
        }
        return result
    }

    /** Normalises a raw price series so the first value equals [startValue]. */
    private fun normalizeFromFirst(
        raw: Map<LocalDate, Double>,
        startValue: Double
    ): Map<LocalDate, Double> {
        val sorted = raw.keys.sorted()
        val firstPrice = raw[sorted.first()] ?: return emptyMap()
        return sorted.associateWith { date -> raw[date]!! / firstPrice * startValue }
    }

    // ── Date intersection ─────────────────────────────────────────────────────

    private fun intersectDates(
        series: List<Map<LocalDate, Double>>,
        from: LocalDate?,
        to: LocalDate
    ): List<LocalDate> {
        var common: Set<LocalDate> = series.first().keys.toSet()
        for (s in series.drop(1)) common = common intersect s.keys.toSet()
        return common
            .filter { d -> (from == null || d >= from) && d <= to }
            .sorted()
    }

    // ── Portfolio computation ─────────────────────────────────────────────────

    private fun computeNoMargin(
        pConfig: PortfolioConfig,
        seriesMap: Map<String, Map<LocalDate, Double>>,
        dates: List<LocalDate>
    ): List<Double> {
        val tickers = pConfig.tickers.map { it.ticker }
        val totalWeight = pConfig.tickers.sumOf { it.weight }
        val targetWeights = pConfig.tickers.associate { it.ticker to it.weight / totalWeight }

        // Initial allocation: weights × start value
        val startValue = 10_000.0
        val holdings = tickers.associateWith { ticker ->
            startValue * (targetWeights[ticker] ?: 0.0)
        }.toMutableMap()

        val values = mutableListOf<Double>()
        values.add(startValue)

        for (i in 1 until dates.size) {
            val prevDate = dates[i - 1]
            val curDate = dates[i]

            // Rebalance at the close of the last trading day of each period,
            // BEFORE applying the new period's first daily return.
            if (shouldRebalance(pConfig.rebalanceStrategy, prevDate, curDate)) {
                val total = holdings.values.sum()
                for (ticker in tickers) {
                    holdings[ticker] = total * (targetWeights[ticker] ?: 0.0)
                }
            }

            // Apply daily returns
            for (ticker in tickers) {
                val s = seriesMap[ticker] ?: continue
                val prev = s[prevDate] ?: continue
                val cur = s[curDate] ?: continue
                if (prev == 0.0) continue
                holdings[ticker] = (holdings[ticker] ?: 0.0) * (cur / prev)
            }

            values.add(holdings.values.sum())
        }
        return values
    }

    private fun shouldRebalance(
        strategy: RebalanceStrategy,
        prevDate: LocalDate,
        curDate: LocalDate
    ): Boolean = when (strategy) {
        RebalanceStrategy.NONE -> false
        RebalanceStrategy.DAILY -> true
        RebalanceStrategy.WEEKLY -> curDate.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) != prevDate.get(
            IsoFields.WEEK_OF_WEEK_BASED_YEAR
        )
                || curDate.year != prevDate.year

        RebalanceStrategy.MONTHLY -> curDate.month != prevDate.month
        RebalanceStrategy.QUARTERLY -> ((curDate.monthValue - 1) / 3) != ((prevDate.monthValue - 1) / 3)
        RebalanceStrategy.YEARLY -> curDate.year != prevDate.year
    }

    // ── Margin computation ────────────────────────────────────────────────────

    private data class MarginApplyResult(
        val values: List<Double>,
        val upperTriggers: Int,   // ratio > target + deviation (market fell, forced to de-lever)
        val lowerTriggers: Int    // ratio < target - deviation (market rose, forced to re-lever)
    )

    private fun applyMargin(
        noMargin: List<Double>,
        dates: List<LocalDate>,
        effrx: Map<LocalDate, Double>,
        marginConfig: MarginConfig,
        rebalanceStrategy: RebalanceStrategy
    ): MarginApplyResult {
        val marginTarget = marginConfig.marginRatio
        val spread = marginConfig.marginSpread
        val deviationUpper = marginConfig.marginDeviationUpper
        val deviationLower = marginConfig.marginDeviationLower

        var equity = noMargin[0]
        var borrowed = equity * marginTarget
        var totalExposure = equity + borrowed

        val result = mutableListOf(equity)
        var upperTriggers = 0
        var lowerTriggers = 0

        for (i in 1 until noMargin.size) {
            val prevDate = dates[i - 1]
            val curDate = dates[i]

            // Portfolio return
            val portfolioReturn = if (noMargin[i - 1] != 0.0) noMargin[i] / noMargin[i - 1] else 1.0
            totalExposure *= portfolioReturn

            // Daily loan cost from EFFRX
            val effrxPrev = effrx[prevDate]
            val effrxCur = effrx[curDate]
            val dailyLoanRate = if (effrxPrev != null && effrxCur != null && effrxPrev != 0.0) {
                (effrxCur / effrxPrev - 1) + spread / 252.0
            } else {
                spread / 252.0
            }

            borrowed *= (1.0 + dailyLoanRate)
            equity = totalExposure - borrowed

            // Margin ratio check
            val currentMarginRatio = if (equity != 0.0) borrowed / equity else marginTarget
            val rebalanceDay = shouldRebalance(rebalanceStrategy, prevDate, curDate)
            val upperBreach = currentMarginRatio > marginTarget + deviationUpper
            val lowerBreach = currentMarginRatio < marginTarget - deviationLower
            val deviationBreach = upperBreach || lowerBreach

            if (deviationBreach && !rebalanceDay) {
                if (upperBreach) upperTriggers++ else lowerTriggers++
            }

            if (rebalanceDay || deviationBreach) {
                borrowed = equity * marginTarget
                totalExposure = equity + borrowed
            }

            result.add(equity)
        }
        return MarginApplyResult(result, upperTriggers, lowerTriggers)
    }

    /**
     * Proportional margin rebalance mode: when a margin deviation triggers, only the
     * delta change in total exposure is distributed across tickers by target weight.
     * Individual ticker holdings are tracked; the full rebalance strategy also applies.
     */
    private fun applyMarginProportional(
        pConfig: PortfolioConfig,
        seriesMap: Map<String, Map<LocalDate, Double>>,
        dates: List<LocalDate>,
        effrx: Map<LocalDate, Double>,
        mc: MarginConfig
    ): MarginApplyResult {
        val tickers = pConfig.tickers.map { it.ticker }
        val totalWeight = pConfig.tickers.sumOf { it.weight }
        val targetWeights = pConfig.tickers.associate { it.ticker to it.weight / totalWeight }

        val startEquity = 10_000.0
        var borrowed = startEquity * mc.marginRatio
        val holdings = tickers.associateWith { ticker ->
            (startEquity + borrowed) * (targetWeights[ticker] ?: 0.0)
        }.toMutableMap()

        val result = mutableListOf(startEquity)
        var upperTriggers = 0
        var lowerTriggers = 0

        for (i in 1 until dates.size) {
            val prevDate = dates[i - 1]
            val curDate = dates[i]

            // Scheduled full asset rebalance (before today's return)
            if (shouldRebalance(pConfig.rebalanceStrategy, prevDate, curDate)) {
                val currentEquity = holdings.values.sum() - borrowed
                borrowed = currentEquity * mc.marginRatio
                val newTotalExposure = currentEquity + borrowed
                for (ticker in tickers) {
                    holdings[ticker] = newTotalExposure * (targetWeights[ticker] ?: 0.0)
                }
            }

            // Apply each ticker's daily return
            for (ticker in tickers) {
                val s = seriesMap[ticker] ?: continue
                val prev = s[prevDate] ?: continue
                val cur = s[curDate] ?: continue
                if (prev == 0.0) continue
                holdings[ticker] = (holdings[ticker] ?: 0.0) * (cur / prev)
            }

            // Daily loan cost from EFFRX
            val effrxPrev = effrx[prevDate]
            val effrxCur = effrx[curDate]
            val dailyLoanRate = if (effrxPrev != null && effrxCur != null && effrxPrev != 0.0) {
                (effrxCur / effrxPrev - 1) + mc.marginSpread / 252.0
            } else {
                mc.marginSpread / 252.0
            }
            borrowed *= (1.0 + dailyLoanRate)

            val equity = holdings.values.sum() - borrowed

            val isDailyMode = mc.upperRebalanceMode == MarginRebalanceMode.DAILY

            if (isDailyMode) {
                // Reset margin ratio daily using current weights (no deviation tracking)
                val newBorrowed = equity * mc.marginRatio
                val delta = newBorrowed - borrowed
                val totalHoldings = holdings.values.sum()
                if (totalHoldings != 0.0)
                    for (ticker in tickers)
                        holdings[ticker] = (holdings[ticker] ?: 0.0) * (1.0 + delta / totalHoldings)
                borrowed = newBorrowed
            } else {
                // Margin ratio deviation check
                val currentMarginRatio = if (equity != 0.0) borrowed / equity else mc.marginRatio
                val rebalanceDay = shouldRebalance(pConfig.rebalanceStrategy, prevDate, curDate)
                val upperBreach = currentMarginRatio > mc.marginRatio + mc.marginDeviationUpper
                val lowerBreach = currentMarginRatio < mc.marginRatio - mc.marginDeviationLower
                val deviationBreach = upperBreach || lowerBreach

                if (deviationBreach && !rebalanceDay) {
                    if (upperBreach) upperTriggers++ else lowerTriggers++
                }

                if (deviationBreach) {
                    val newBorrowed = equity * mc.marginRatio
                    val mode = if (upperBreach) mc.upperRebalanceMode else mc.lowerRebalanceMode
                    when (mode) {
                        MarginRebalanceMode.FULL_REBALANCE -> {
                            // Full rebalance: reset all holdings to target weights at new total exposure
                            val newTotalExposure = equity + newBorrowed
                            for (ticker in tickers) {
                                holdings[ticker] = newTotalExposure * (targetWeights[ticker] ?: 0.0)
                            }
                        }

                        MarginRebalanceMode.UNDERVALUED_PRIORITY -> {
                            val delta = newBorrowed - borrowed
                            val totalHoldings = holdings.values.sum()
                            if (delta >= 0) {
                                // Buying: allocate to most undervalued assets first
                                val sortedTickers = tickers.sortedBy {
                                    (holdings[it] ?: 0.0) / totalHoldings - (targetWeights[it]
                                        ?: 0.0)
                                }
                                var remaining = delta
                                for (ticker in sortedTickers) {
                                    if (remaining <= 0.0) break
                                    val cur = holdings[ticker] ?: 0.0
                                    val target = totalHoldings * (targetWeights[ticker] ?: 0.0)
                                    val add = minOf(remaining, maxOf(0.0, target - cur))
                                    holdings[ticker] = cur + add
                                    remaining -= add
                                }
                                if (remaining > 0.0) {
                                    for (ticker in tickers)
                                        holdings[ticker] = (holdings[ticker]
                                            ?: 0.0) + remaining * (targetWeights[ticker] ?: 0.0)
                                }
                            } else {
                                // Selling: trim most overvalued assets first
                                val sortedTickers = tickers.sortedByDescending {
                                    (holdings[it] ?: 0.0) / totalHoldings - (targetWeights[it]
                                        ?: 0.0)
                                }
                                var remaining = -delta
                                for (ticker in sortedTickers) {
                                    if (remaining <= 0.0) break
                                    val cur = holdings[ticker] ?: 0.0
                                    val target = totalHoldings * (targetWeights[ticker] ?: 0.0)
                                    val remove = minOf(remaining, maxOf(0.0, cur - target))
                                    holdings[ticker] = cur - remove
                                    remaining -= remove
                                }
                                if (remaining > 0.0) {
                                    for (ticker in tickers)
                                        holdings[ticker] = (holdings[ticker]
                                            ?: 0.0) - remaining * (targetWeights[ticker] ?: 0.0)
                                }
                            }
                        }

                        MarginRebalanceMode.PROPORTIONAL -> {
                            val delta = newBorrowed - borrowed
                            for (ticker in tickers)
                                holdings[ticker] =
                                    (holdings[ticker] ?: 0.0) + delta * (targetWeights[ticker]
                                        ?: 0.0)
                        }

                        MarginRebalanceMode.CURRENT_WEIGHT -> {
                            // Treat portfolio as a black box: buy/sell proportionally by current value
                            val delta = newBorrowed - borrowed
                            val totalHoldings = holdings.values.sum()
                            if (totalHoldings != 0.0)
                                for (ticker in tickers)
                                    holdings[ticker] =
                                        (holdings[ticker] ?: 0.0) * (1.0 + delta / totalHoldings)
                        }

                        MarginRebalanceMode.DAILY -> { /* handled above */
                        }
                    }
                    borrowed = newBorrowed
                }
            }

            result.add(holdings.values.sum() - borrowed)
        }
        return MarginApplyResult(result, upperTriggers, lowerTriggers)
    }

    // ── Statistics ────────────────────────────────────────────────────────────

    private fun computeStats(
        values: List<Double>,
        dates: List<LocalDate>,
        effrx: Map<LocalDate, Double>,
        marginUpperTriggers: Int? = null,
        marginLowerTriggers: Int? = null
    ): BacktestStats {
        if (values.size < 2) return BacktestStats(0.0, 0.0, 0.0, values.lastOrNull() ?: 0.0)

        val years = (dates.last().toEpochDay() - dates.first().toEpochDay()) / 365.25
        val cagr = if (years > 0) (values.last() / values.first()).pow(1.0 / years) - 1.0 else 0.0

        // Max drawdown
        var peak = values[0]
        var maxDD = 0.0
        for (v in values) {
            if (v > peak) peak = v
            if (peak > 0) maxDD = max(maxDD, 1.0 - v / peak)
        }

        // Sharpe ratio (using daily log returns)
        val logReturns = (1 until values.size).map { i ->
            if (values[i - 1] > 0) ln(values[i] / values[i - 1]) else 0.0
        }

        // Risk-free daily from EFFRX
        val rfDaily = if (effrx.size >= 2) {
            val effrxSorted = effrx.keys.sorted()
            val rfLogReturns = (1 until effrxSorted.size).mapNotNull { i ->
                val prev = effrx[effrxSorted[i - 1]] ?: return@mapNotNull null
                val cur = effrx[effrxSorted[i]] ?: return@mapNotNull null
                if (prev > 0) ln(cur / prev) else null
            }
            if (rfLogReturns.isNotEmpty()) rfLogReturns.average() else 0.0
        } else 0.0

        val meanReturn = logReturns.average()
        val variance = logReturns.map { (it - meanReturn).pow(2) }.average()
        val stdDev = sqrt(variance)
        val sharpe = if (stdDev > 0) (meanReturn - rfDaily) / stdDev * sqrt(252.0) else 0.0

        return BacktestStats(
            cagr = cagr,
            maxDrawdown = maxDD,
            sharpe = sharpe,
            endingValue = values.last(),
            marginUpperTriggers = marginUpperTriggers,
            marginLowerTriggers = marginLowerTriggers
        )
    }
}
