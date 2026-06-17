package com.portfoliohelper.service

import com.portfoliohelper.AppDirs
import com.portfoliohelper.service.yahoo.YahooHistoricalDataException
import com.portfoliohelper.service.yahoo.YahooHistoricalFetcher
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import java.time.temporal.IsoFields
import java.util.*
import java.util.concurrent.*
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

// ── Service ───────────────────────────────────────────────────────────────────

object BacktestService {
    private val logger = LoggerFactory.getLogger(BacktestService::class.java)
    const val DATE_RANGE_ERROR_MESSAGE = "From date must be on or before to date."
    private val tickerDir get() = AppDirs.dataDir.resolve(".ticker").toFile()
    private val tickerCacheMaxAge = 15.minutes

    fun validateDateRange(fromDate: LocalDate?, toDate: LocalDate) {
        if (fromDate != null && fromDate > toDate) {
            throw IllegalArgumentException(DATE_RANGE_ERROR_MESSAGE)
        }
    }

    fun runMulti(request: MultiBacktestRequest): MultiBacktestResult {
        val fromDate = request.fromDate?.let { LocalDate.parse(it) }
        val toDate = request.toDate?.let { LocalDate.parse(it) } ?: LocalDate.now()
        validateDateRange(fromDate, toDate)
        val warnings = Collections.synchronizedSet(LinkedHashSet<String>())
        val warningCollector = { _: String, tickerWarnings: List<String> ->
            warnings.addAll(tickerWarnings)
            Unit
        }
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
            seriesCache.getOrPut(ticker) { loadNormalizedSeries(ticker, neededFrom, warningCollector) }

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

        // Step 7: Compute portfolio results (portfolios and strategies run in parallel)
        val portfolioResults = request.portfolios.indices.toList().parallelStream().map { idx ->
            val pConfig = request.portfolios[idx]
            val seriesMap = allSeriesMaps[idx]

            val noMarginValues = computeNoMargin(
                pConfig, seriesMap, globalDates, request.startingBalance, request.cashflow
            )
            val curves = mutableListOf<CurveResult>()
            if (pConfig.includeNoMargin) {
                val noMarginPoints =
                    globalDates.mapIndexed { i, d -> DataPoint(d.toString(), noMarginValues[i]) }
                val noMarginStats = computeBacktestStats(noMarginValues, globalDates, effrxSeries)
                val noMarginActionPoints =
                    scheduledPortfolioRebalanceActionPoints(globalDates, pConfig.rebalanceStrategy)
                curves.add(
                    CurveResult(
                        "No Margin",
                        noMarginPoints,
                        noMarginStats,
                        actionPoints = noMarginActionPoints.takeIf { it.isNotEmpty() },
                    )
                )
            }

            fun modeAbbr(m: String) = HybridAllocStrategyRegistry.modeLabel(m)

            val marginCurves = pConfig.marginStrategies.indices.toList().parallelStream().map { mIdx ->
                val mc = pConfig.marginStrategies[mIdx]
                val marginResult =
                    if (mc.upperRebalanceMode != MarginRebalanceMode.CURRENT_WEIGHT.name ||
                        mc.lowerRebalanceMode != MarginRebalanceMode.CURRENT_WEIGHT.name
                    )
                        applyMarginProportional(
                            pConfig, seriesMap, globalDates, effrxSeries, mc, request.startingBalance, request.cashflow,
                            request.zeroMarginInterest,
                        )
                    else
                        applyMargin(
                            noMarginValues,
                            globalDates,
                            effrxSeries,
                            mc,
                            pConfig.rebalanceStrategy,
                            request.zeroMarginInterest,
                        )
                val marginValuePoints = globalDates.mapIndexed { i, d ->
                    DataPoint(d.toString(), marginResult.values[i])
                }
                val marginUtilPoints = globalDates.mapIndexed { i, d ->
                    DataPoint(d.toString(), marginResult.marginUtilization[i])
                }
                val marginStats = computeBacktestStats(
                    marginResult.values, globalDates, effrxSeries,
                    marginResult.upperTriggers, marginResult.lowerTriggers
                )
                val uAbbr = modeAbbr(mc.upperRebalanceMode)
                val lAbbr = modeAbbr(mc.lowerRebalanceMode)
                val label = if (uAbbr == lAbbr) "Margin ${mIdx + 1} ($uAbbr)"
                else "Margin ${mIdx + 1} ($uAbbr↑/$lAbbr↓)"
                CurveResult(
                    label,
                    marginValuePoints,
                    marginStats,
                    marginUtilPoints,
                    marginResult.actionPoints.takeIf { it.isNotEmpty() },
                )
            }.toList()

            curves.addAll(marginCurves)
            curves.addAll(
                RebalanceStrategyService.runAttachedStrategies(
                    request.fromDate,
                    request.toDate,
                    pConfig,
                    request.cashflow,
                    pConfig.rebalanceStrategies,
                    request.startingBalance,
                    globalDates = globalDates,
                    zeroMarginInterest = request.zeroMarginInterest,
                )
            )
            PortfolioResult(pConfig.label, curves)
        }.toList()

        return MultiBacktestResult(portfolioResults, synchronized(warnings) { warnings.toList() })
    }

    fun runMarketTiming(request: MarketTimingRequest): MarketTimingMultiResult {
        val fromDate = request.fromDate?.let { LocalDate.parse(it) }
        val toDate = request.toDate?.let { LocalDate.parse(it) } ?: LocalDate.now()
        validateDateRange(fromDate, toDate)
        val effrxSeries = loadEffrxSeries()
        val neededFrom = LocalDate.of(1990, 1, 1)
        val portfolio = request.portfolio.copy(marginStrategies = emptyList(), rebalanceStrategies = emptyList())
        val referenceTicker = request.referenceTicker
            ?.trim()
            ?.uppercase()
            ?.takeIf { it.isNotBlank() && request.referenceSource == MarketTimingReferenceSource.TICKER }

        if (portfolio.tickers.isEmpty()) throw IllegalArgumentException("Portfolio must contain at least one ticker")
        if (request.referenceSource == MarketTimingReferenceSource.TICKER && referenceTicker == null) {
            throw IllegalArgumentException("Reference ticker is required")
        }

        val allTickers = (portfolio.tickers.map { it.ticker } + listOfNotNull(referenceTicker)).distinct()
        val letfDefs = mutableMapOf<String, LETFDefinition>()
        for (ticker in allTickers) {
            val def = parseLETFDefinition(ticker) ?: continue
            letfDefs.putIfAbsent(ticker, def)
        }

        val seriesCache = mutableMapOf<String, Map<LocalDate, Double>>()
        fun cachedLoad(ticker: String) =
            seriesCache.getOrPut(ticker) { loadNormalizedSeries(ticker, neededFrom) }

        val letfComponentTickers = letfDefs.values
            .flatMap { def -> def.components.map { it.ticker } }
            .toSet()
        for (ticker in letfComponentTickers) cachedLoad(ticker)

        val componentSeriesForDates = letfComponentTickers.mapNotNull { seriesCache[it] }
        val letfDates = if (componentSeriesForDates.isNotEmpty())
            intersectDates(componentSeriesForDates, null, toDate)
        else emptyList()

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

        allTickers
            .filter { parseLETFDefinition(it) == null }
            .forEach { cachedLoad(it) }

        val portfolioSeriesMap = portfolio.tickers.associate { tw ->
            tw.ticker to (seriesCache[tw.ticker] ?: error("Series for '${tw.ticker}' not found in cache"))
        }
        val dateSeries = portfolioSeriesMap.values.toMutableList()
        val referenceSeries = referenceTicker?.let {
            seriesCache[it] ?: error("Series for '$it' not found in cache")
        }
        if (referenceSeries != null) dateSeries.add(referenceSeries)

        val dates = intersectDates(dateSeries, fromDate, toDate)
        if (dates.size < 2) throw IllegalStateException("Not enough overlapping trading dates")

        val portfolioValues = computeNoMargin(portfolio, portfolioSeriesMap, dates, request.startingBalance, null)
        val referenceHistory = if (referenceSeries == null) {
            val historyDates = intersectDates(portfolioSeriesMap.values.toList(), null, toDate)
            val historyValues = computeNoMargin(portfolio, portfolioSeriesMap, historyDates, request.startingBalance, null)
            historyDates to historyValues
        } else {
            val historyDates = (referenceSeries.keys + dates)
                .filter { it <= toDate }
                .distinct()
                .sorted()
            val filled = forwardFillSeries(referenceSeries, historyDates)
            val usableDates = historyDates.filter { filled[it] != null }
            usableDates to usableDates.map { filled[it] ?: error("Missing reference value for $it") }
        }
        val (referenceHistoryDates, referenceHistoryValues) = referenceHistory
        val referenceHistoryDrawdowns = computeReferenceDrawdowns(referenceHistoryValues)
        val referenceDrawdownByDate = referenceHistoryDates
            .mapIndexed { i, date -> date to referenceHistoryDrawdowns[i] }
            .toMap()
        val referenceValueByDate = referenceHistoryDates
            .mapIndexed { i, date -> date to referenceHistoryValues[i] }
            .toMap()
        val referenceDrawdowns = dates.map { date ->
            referenceDrawdownByDate[date] ?: error("Missing reference drawdown for $date")
        }
        val referenceRawValues = dates.map { date ->
            referenceValueByDate[date] ?: error("Missing reference value for $date")
        }
        val referenceDisplayStart = referenceRawValues.firstOrNull()?.takeIf { it > 0.0 } ?: request.startingBalance
        val referenceValues = referenceRawValues.map { it / referenceDisplayStart * request.startingBalance }

        val annualRate = when (request.interestMode) {
            MarketTimingInterestMode.SPREAD -> request.annualSpread ?: 0.0
            MarketTimingInterestMode.FIXED -> request.fixedAnnualRate ?: 0.0
        }.coerceAtLeast(0.0)
        val dailyLoanRates = when (request.interestMode) {
            MarketTimingInterestMode.SPREAD -> buildDailyLoanRates(dates, effrxSeries, annualRate / 252.0)
            MarketTimingInterestMode.FIXED -> DoubleArray(dates.size) { i -> if (i == 0) 0.0 else annualRate / 252.0 }
        }
        val debtIndex = DoubleArray(dates.size) { 1.0 }
        for (i in 1 until dates.size) debtIndex[i] = debtIndex[i - 1] * (1.0 + dailyLoanRates[i])

        val thresholds = request.drawdownConfigs
            .mapNotNull { config ->
                val drawdownPct = if (config.drawdownPct > 1.0) config.drawdownPct / 100.0 else config.drawdownPct
                if (drawdownPct > 0.0 && drawdownPct < 1.0) {
                    MarketTimingDrawdownConfig(drawdownPct, config.zeroWindowMonths.coerceAtLeast(0))
                } else {
                    null
                }
            }
            .distinctBy { it.drawdownPct to it.zeroWindowMonths }
            .sortedBy { it.drawdownPct }
            .ifEmpty {
                listOf(0.05, 0.10, 0.15, 0.20, 0.25).map { MarketTimingDrawdownConfig(it) }
            }

        val drawdownIndex = ReferenceRangeIndex(referenceDrawdowns)
        val results = thresholds.map { threshold ->
            val drawdownPct = threshold.drawdownPct
            val firstTriggerDate = referenceHistoryDates
                .zip(referenceHistoryDrawdowns)
                .firstOrNull { (_, drawdown) -> drawdown <= -drawdownPct }
                ?.first
            val zeroedByRecentDrawdown = buildMarketTimingZeroWindow(
                drawdownPct,
                dates,
                referenceHistoryDates,
                referenceHistoryDrawdowns,
                threshold.zeroWindowMonths,
            )
            buildMarketTimingResult(
                drawdownPct,
                dates,
                portfolioValues,
                referenceDrawdowns,
                debtIndex,
                drawdownIndex,
                firstTriggerDate,
                threshold.zeroWindowMonths,
                zeroedByRecentDrawdown,
            )
        }

        return MarketTimingMultiResult(
            referenceLabel = referenceTicker ?: portfolio.label,
            referencePoints = dates.mapIndexed { i, date -> DataPoint(date.toString(), referenceValues[i]) },
            results = results,
        )
    }

    private fun buildMarketTimingResult(
        drawdownPct: Double,
        dates: List<LocalDate>,
        portfolioValues: List<Double>,
        referenceDrawdowns: List<Double>,
        debtIndex: DoubleArray,
        drawdownIndex: ReferenceRangeIndex,
        firstTriggerDate: LocalDate?,
        zeroWindowMonths: Int,
        zeroedByRecentDrawdown: BooleanArray,
    ): MarketTimingResult {
        val n = dates.size
        val triggerIndex = arrayOfNulls<Int>(n)
        for (i in n - 1 downTo 0) {
            triggerIndex[i] = if (firstTriggerDate == null) {
                null
            } else if (zeroedByRecentDrawdown[i]) {
                i
            } else {
                drawdownIndex.firstAtOrBelow(i + 1, -drawdownPct)
            }
        }

        val rawPoints = dates.mapIndexed { i, date ->
            val j = triggerIndex[i]
            if (j == null || portfolioValues[i] <= 0.0 || debtIndex[i] <= 0.0) {
                MarketTimingPoint(date.toString())
            } else if (j == i) {
                MarketTimingPoint(
                    date = date.toString(),
                    value = 0.0,
                    basePortfolioReturn = 1.0,
                    marginExcessReturn = 0.0,
                    triggerDate = date.toString(),
                    daysToTrigger = 0,
                    referenceDrawdown = referenceDrawdowns[i],
                    zeroingWindow = true,
                )
            } else {
                val basePortfolioReturn = portfolioValues[j] / portfolioValues[i]
                val debtReturn = debtIndex[j] / debtIndex[i]
                MarketTimingPoint(
                    date = date.toString(),
                    value = basePortfolioReturn / debtReturn - 1.0,
                    basePortfolioReturn = basePortfolioReturn,
                    marginExcessReturn = basePortfolioReturn - debtReturn,
                    triggerDate = dates[j].toString(),
                    daysToTrigger = (dates[j].toEpochDay() - date.toEpochDay()).toInt(),
                    referenceDrawdown = referenceDrawdowns[j],
                )
            }
        }
        var nextNonZeroWindowId = 0
        var currentNonZeroWindowId: Int? = null
        val points = rawPoints.map { point ->
            if (point.value != null && !point.zeroingWindow) {
                val windowId = currentNonZeroWindowId ?: nextNonZeroWindowId++
                currentNonZeroWindowId = windowId
                point.copy(nonZeroWindowId = windowId)
            } else {
                currentNonZeroWindowId = null
                point
            }
        }

        val triggered = points.filter { it.value != null }
        val values = triggered.mapNotNull { it.value }.sorted()
        val summary = if (values.isEmpty()) {
            MarketTimingSummary(totalPoints = points.size, triggeredPoints = 0)
        } else {
            val median = if (values.size % 2 == 1) {
                values[values.size / 2]
            } else {
                (values[values.size / 2 - 1] + values[values.size / 2]) / 2.0
            }
            val decisiveValues = values.filter { abs(it) > 1e-9 }
            val decisiveMedian = decisiveValues.takeIf { it.isNotEmpty() }?.let { decisive ->
                if (decisive.size % 2 == 1) {
                    decisive[decisive.size / 2]
                } else {
                    (decisive[decisive.size / 2 - 1] + decisive[decisive.size / 2]) / 2.0
                }
            }
            MarketTimingSummary(
                totalPoints = points.size,
                triggeredPoints = triggered.size,
                bestValue = values.last(),
                worstValue = values.first(),
                averageValue = values.average(),
                medianValue = median,
                nonZeroAverageValue = decisiveValues.takeIf { it.isNotEmpty() }?.average(),
                nonZeroMedianValue = decisiveMedian,
                winRate = decisiveValues
                    .takeIf { it.isNotEmpty() }
                    ?.let { decisive -> decisive.count { it > 0.0 }.toDouble() / decisive.size },
                averageDaysToTrigger = triggered.mapNotNull { it.daysToTrigger }.average(),
            )
        }

        return MarketTimingResult(drawdownPct, zeroWindowMonths, points, summary)
    }

    private fun buildMarketTimingZeroWindow(
        drawdownPct: Double,
        dates: List<LocalDate>,
        referenceHistoryDates: List<LocalDate>,
        referenceHistoryDrawdowns: List<Double>,
        zeroWindowMonths: Int,
    ): BooleanArray {
        val flags = BooleanArray(dates.size)
        var historyIndex = 0
        var lastQualifyingDrawdownDate: LocalDate? = null
        for ((dateIndex, date) in dates.withIndex()) {
            while (historyIndex < referenceHistoryDates.size && !referenceHistoryDates[historyIndex].isAfter(date)) {
                if (referenceHistoryDrawdowns[historyIndex] <= -drawdownPct) {
                    lastQualifyingDrawdownDate = referenceHistoryDates[historyIndex]
                }
                historyIndex++
            }
            flags[dateIndex] = lastQualifyingDrawdownDate
                ?.plusMonths(zeroWindowMonths.toLong())
                ?.let { zeroUntil -> !date.isAfter(zeroUntil) }
                ?: false
        }
        return flags
    }

    private fun computeReferenceDrawdowns(values: List<Double>): List<Double> {
        var peak = Double.NEGATIVE_INFINITY
        return values.map { value ->
            if (value > peak) peak = value
            if (peak > 0.0) value / peak - 1.0 else 0.0
        }
    }

    private class ReferenceRangeIndex(private val values: List<Double>) {
        private val n = values.size
        private val minTree = DoubleArray(n * 4) { Double.POSITIVE_INFINITY }

        init {
            if (n > 0) build(1, 0, n - 1)
        }

        private fun build(node: Int, left: Int, right: Int) {
            if (left == right) {
                minTree[node] = values[left]
                return
            }
            val mid = (left + right) / 2
            build(node * 2, left, mid)
            build(node * 2 + 1, mid + 1, right)
            minTree[node] = minOf(minTree[node * 2], minTree[node * 2 + 1])
        }

        fun firstAtOrBelow(start: Int, threshold: Double): Int? {
            if (start >= n || minTree[1] > threshold) return null
            return firstAtOrBelow(1, 0, n - 1, start, threshold)
        }

        private fun firstAtOrBelow(node: Int, left: Int, right: Int, start: Int, threshold: Double): Int? {
            if (right < start || minTree[node] > threshold) return null
            if (left == right) return left
            val mid = (left + right) / 2
            return firstAtOrBelow(node * 2, left, mid, start, threshold)
                ?: firstAtOrBelow(node * 2 + 1, mid + 1, right, start, threshold)
        }
    }

    // ── Ticker data loading ───────────────────────────────────────────────────


    internal fun getResourceFiles(path: String): List<String> {
        val url = object {}.javaClass.classLoader.getResource(path)
        if (url == null) {
            logger.warn("Resource directory '$path' not found in classpath — Tier 2 will be skipped")
            return emptyList()
        }
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
        return try {
            Files.list(dirPath).use { stream ->
                stream.map { it.fileName.toString() }.toList()
            }
        } catch (e: Exception) {
            logger.warn("Failed to list resource directory '$path': ${e.message}")
            emptyList()
        }
    }

    /**
     * Loads (or fetches) a normalised series for a ticker.
     * Normalised means the series values are chain-linked returns starting at 10 000.
     *
     * Three-tier fallback:
     *   Tier 1 — local .ticker file: load, sanity-check, extend; delete + fall through on failure.
     *   Tier 2 — resource .ticker file: copy, sanity-check, extend; delete + fall through on failure.
     *   Tier 3 — rebuild from Yahoo from scratch.
     */
    internal fun loadNormalizedSeries(
        ticker: String,
        neededFromDate: LocalDate,
        warningCollector: ((String, List<String>) -> Unit)? = null
    ): Map<LocalDate, Double> {
        require(!ticker.contains(' ')) {
            "loadNormalizedSeries called with LETF string '$ticker' — LETF series must be pre-computed via computeLetfSeries"
        }
        tickerDir.mkdirs()
        val upperTicker = ticker.uppercase()
        val simPattern = Regex("${upperTicker}-(\\d{4}-\\d{2}-\\d{2})\\.csv")
        val today = LocalDate.now()

        val localFiles = findFiles(simPattern)

        // Tier 1 — local file
        if (localFiles.isNotEmpty()) {
            val extended = tryExtendAndValidate(
                ticker,
                upperTicker,
                localFiles,
                today,
                neededFromDate,
                warningCollector
            )
            val extendedFirstDate = extended?.keys?.minOrNull()
            val resourceFirstDate = extendedFirstDate?.let { bundledResourceFirstDate(simPattern) }
            if (
                extended != null &&
                extendedFirstDate != null &&
                extendedFirstDate <= neededFromDate &&
                (resourceFirstDate == null || resourceFirstDate >= extendedFirstDate)
            ) {
                collectTickerWarnings(upperTicker, warningCollector)
                return extended
            }
            if (extended != null) {
                localFiles.forEach { it.delete() }
                if (resourceFirstDate != null && resourceFirstDate < extendedFirstDate) {
                    logger.warn("$upperTicker Tier 1 starts at $extendedFirstDate, after bundled resource $resourceFirstDate — checking bundled resource")
                } else {
                    logger.warn("$upperTicker Tier 1 starts at $extendedFirstDate, after requested $neededFromDate — checking bundled resource")
                }
            } else {
                localFiles.forEach { it.delete() }
                logger.warn("$upperTicker Tier 1 (local file) failed — deleted, falling through to resource")
            }
        }

        // Tier 2 — resource file
        val resourceFiles = copyFromResources(simPattern, forceRefresh = localFiles.isNotEmpty())
        if (resourceFiles.isNotEmpty()) {
            val extended = tryExtendAndValidate(
                ticker,
                upperTicker,
                resourceFiles,
                today,
                neededFromDate,
                warningCollector,
                allowAnchoredForwardExtension = true
            )
            if (extended != null) {
                localFiles.filter { it !in resourceFiles }.forEach { it.delete() }
                collectTickerWarnings(upperTicker, warningCollector)
                return extended
            }
            resourceFiles.forEach { it.delete() }
            logger.warn("$upperTicker Tier 2 (resource file) failed — deleted, falling through to Yahoo")
        } else {
            logger.warn("$upperTicker Tier 2: no matching resource file found for pattern $simPattern")
        }

        // Tier 3 — rebuild from scratch
        logger.info("No valid SIM file for $upperTicker, fetching from Yahoo since $neededFromDate")
        val raw = fetchAdjustedCloseRecordingWarnings(ticker, upperTicker, neededFromDate, today, warningCollector)
        if (raw.isEmpty()) throw IllegalStateException("No Yahoo data for $ticker from $neededFromDate")
        val normalized = normalizeFromFirst(raw, 10_000.0)
        val newFile = File(tickerDir, "${upperTicker}-${today}.csv")
        writeSimCsv(newFile, normalized)
        return normalized
    }

    /**
     * Attempts to prepend and/or extend [files] with Yahoo data, validating chain-link consistency
     * in the overlap region. Returns the updated series on success, or null on any failure
     * (caller is responsible for deleting the files in that case).
     *
     * Prepend (if neededFromDate < firstDate): fetches Yahoo from neededFromDate to firstDate+10 days.
     *   If Yahoo has dates before firstDate, calls chainPrepend to backfill; otherwise skips silently.
     * Extend (if lastKnownDate < neededToDate, or today's cache is stale): fetches Yahoo from
     *   lastKnownDate−10 days to neededToDate, then calls chainExtend to replace the cached tail
     *   and append new entries. The last cached date is treated as provisional because it may be
     *   an intraday mark or an incomplete Yahoo historical response.
     * Both operations throw on overlap mismatch (caught here → returns null).
     */
    private fun tryExtendAndValidate(
        ticker: String,
        upperTicker: String,
        files: List<File>,
        neededToDate: LocalDate,
        neededFromDate: LocalDate,
        warningCollector: ((String, List<String>) -> Unit)? = null,
        allowAnchoredForwardExtension: Boolean = false
    ): Map<LocalDate, Double>? {
        val file = files.first()
        logger.info("Loading SIM file for $upperTicker: ${file.name}")
        val existing = readSimCsv(file)
        if (existing.isEmpty()) return null
        val firstDate = existing.firstKey()
        val lastKnownDate = existing.lastKey()
        val fileAge = (System.currentTimeMillis() - file.lastModified()).milliseconds
        if (fileAge <= tickerCacheMaxAge && lastKnownDate >= neededToDate && firstDate <= neededFromDate) {
            return existing
        }
        if (existing.size < 20) return null

        var current: Map<LocalDate, Double> = existing

        // Prepend if needed
        if (neededFromDate < firstDate) {
            val earlyYahoo = try {
                fetchAdjustedCloseRecordingWarnings(
                    ticker,
                    upperTicker,
                    neededFromDate,
                    firstDate.plusDays(10),
                    warningCollector
                )
            } catch (e: YahooHistoricalDataException) {
                throw e
            } catch (e: Exception) {
                if (!allowAnchoredForwardExtension) {
                    logger.warn("$upperTicker early probe fetch failed: ${e.message}")
                    return null
                }
                logger.warn("$upperTicker early probe fetch failed: ${e.message}; keeping cached start $firstDate")
                emptyMap()
            }
            if (earlyYahoo.keys.any { it < firstDate }) {
                current = try {
                    chainPrepend(current, earlyYahoo, firstDate)
                } catch (e: Exception) {
                    logger.warn("Failed to prepend $upperTicker via Yahoo: ${e.message}")
                    return null
                }
            } else {
                logger.info("$upperTicker: Yahoo has no data before $firstDate, skipping prepend")
            }
        }

        // Extend forward if needed. If the cache already has today's row but is older
        // than the TTL, refresh that row because it may be an intraday mark.
        val refreshCurrentDate = shouldRefreshCurrentTickerFile(fileAge, lastKnownDate, neededToDate)
        if (lastKnownDate < neededToDate || refreshCurrentDate) {
            if (refreshCurrentDate) {
                logger.info("Refreshing stale same-day $upperTicker SIM for $neededToDate via Yahoo (age=$fileAge)")
            } else {
                logger.info("Extending $upperTicker SIM from $lastKnownDate to $neededToDate via Yahoo")
            }
            val yahoo = try {
                fetchAdjustedCloseRecordingWarnings(
                    ticker,
                    upperTicker,
                    lastKnownDate.minusDays(10),
                    neededToDate,
                    warningCollector
                )
            } catch (e: YahooHistoricalDataException) {
                throw e
            } catch (e: Exception) {
                logger.warn("$upperTicker extend fetch failed: ${e.message}")
                return null
            }
            current = try {
                chainExtend(current, yahoo, lastKnownDate)
            } catch (e: Exception) {
                if (!allowAnchoredForwardExtension) {
                    logger.warn("Failed to extend $upperTicker via Yahoo: ${e.message}")
                    return null
                }
                try {
                    logger.warn("Failed strict extension for $upperTicker via Yahoo: ${e.message}; anchoring at cached $lastKnownDate")
                    chainExtendFromAnchor(current, yahoo, lastKnownDate)
                } catch (fallback: Exception) {
                    logger.warn("Failed anchored extension for $upperTicker via Yahoo: ${fallback.message}")
                    return null
                }
            }
        }

        val newFile = File(tickerDir, "${upperTicker}-${neededToDate}.csv")
        writeSimCsv(newFile, current)
        files.forEach { it.delete() }
        return current
    }

    private fun fetchAdjustedCloseRecordingWarnings(
        ticker: String,
        upperTicker: String,
        startDate: LocalDate,
        endDate: LocalDate,
        warningCollector: ((String, List<String>) -> Unit)? = null
    ): Map<LocalDate, Double> {
        val result = YahooHistoricalFetcher.fetchAdjustedCloseWithWarnings(ticker, startDate, endDate)
        if (result.warnings.isNotEmpty()) {
            persistTickerWarnings(upperTicker, result.warnings)
            warningCollector?.invoke(upperTicker, result.warnings)
        }
        return result.prices
    }

    private fun collectTickerWarnings(
        upperTicker: String,
        warningCollector: ((String, List<String>) -> Unit)?
    ) {
        val warnings = readTickerWarnings(upperTicker)
        if (warnings.isNotEmpty()) warningCollector?.invoke(upperTicker, warnings)
    }

    private fun persistTickerWarnings(upperTicker: String, warnings: List<String>) {
        if (warnings.isEmpty()) return
        tickerDir.mkdirs()
        val merged = (readTickerWarnings(upperTicker) + warnings)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        tickerWarningsFile(upperTicker).bufferedWriter().use { out ->
            merged.forEach { warning ->
                out.write(warning)
                out.newLine()
            }
        }
    }

    private fun readTickerWarnings(upperTicker: String): List<String> {
        val file = tickerWarningsFile(upperTicker)
        if (!file.exists()) return emptyList()
        return runCatching {
            file.readLines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        }.getOrElse { e ->
            logger.warn("Failed to read warning file for $upperTicker: ${e.message}")
            emptyList()
        }
    }

    private fun tickerWarningsFile(upperTicker: String): File =
        File(tickerDir, "$upperTicker-warnings.txt")

    internal fun shouldRefreshCurrentTickerFile(
        fileAge: kotlin.time.Duration,
        lastKnownDate: LocalDate,
        neededToDate: LocalDate,
        today: LocalDate = LocalDate.now()
    ): Boolean =
        fileAge > tickerCacheMaxAge && lastKnownDate == neededToDate && neededToDate == today

    private fun copyFromResources(simPattern: Regex, forceRefresh: Boolean = false): List<File> {
        val allResourceFiles = getResourceFiles("data/.ticker")
        val resourcesFile = allResourceFiles.firstOrNull {
            simPattern.matches(it)
        } ?: return emptyList()
        val cl = object {}::class.java.classLoader
        val target = tickerDir.toPath().resolve(resourcesFile)
        if (forceRefresh || !Files.exists(target)) {
            cl.getResourceAsStream("data/.ticker/$resourcesFile")
                ?.use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
        }
        return listOf(target.toFile()).filter { it.exists() }
    }

    private fun bundledResourceFirstDate(simPattern: Regex): LocalDate? {
        val resourcesFile = getResourceFiles("data/.ticker").firstOrNull {
            simPattern.matches(it)
        } ?: return null
        val cl = object {}::class.java.classLoader
        return cl.getResourceAsStream("data/.ticker/$resourcesFile")?.bufferedReader()?.use { reader ->
            reader.readLine()
            reader.lineSequence()
                .mapNotNull { line ->
                    runCatching { LocalDate.parse(line.substringBefore(",").trim()) }.getOrNull()
                }
                .firstOrNull()
        }
    }

    internal fun findOrCopyFromResources(simPattern: Regex): List<File> =
        findFiles(simPattern).ifEmpty { copyFromResources(simPattern) }

    internal fun findFiles(simPattern: Regex): List<File> = (tickerDir.listFiles()
        ?.filter { simPattern.matches(it.name) }
        ?.sortedByDescending { it.name }
        ?: emptyList())

    /** Loads EFFRX, extending via FRED EFFR if the file is outdated. Returns empty map if not found. */
    internal fun loadEffrxSeries(): Map<LocalDate, Double> {
        tickerDir.mkdirs()
        val simPattern = Regex("EFFRX-(\\d{4}-\\d{2}-\\d{2})\\.csv")
        val existingFiles = findOrCopyFromResources(simPattern)
        val latest = existingFiles.firstOrNull() ?: return emptyMap()
        logger.info("Loading EFFRX from ${latest.name}")
        val existing = readSimCsv(latest)

        val lastDate = existing.keys.maxOrNull() ?: return existing
        val today = LocalDate.now()
        if (lastDate >= today.minusDays(1)) return existing

        return try {
            val extended = extendEffrxWithFred(existing, lastDate, today)
            if (extended.size > existing.size) {
                val newFile = File(tickerDir, "EFFRX-${today}.csv")
                writeSimCsv(newFile, extended)
                existingFiles.forEach { it.delete() }
            }
            extended
        } catch (e: Exception) {
            logger.warn("Failed to extend EFFRX via FRED: ${e.message}. Using existing file.")
            existing
        }
    }

    /**
     * Extends an EFFRX accumulated series using the FRED EFFR series.
     * FRED EFFR only has entries for actual Fed business days (no weekends, holidays are blank).
     * Convention matches the existing CSV: rate/252 per entry, one entry per Fed business day.
     */
    private fun extendEffrxWithFred(
        existing: Map<LocalDate, Double>,
        lastDate: LocalDate,
        today: LocalDate
    ): Map<LocalDate, Double> {
        val fredRates = fetchFredEffr()
        val newDates = fredRates.keys.filter { it > lastDate && it <= today }.sorted()
        if (newDates.isEmpty()) {
            logger.info("EFFRX already up to date (no new FRED EFFR dates after $lastDate)")
            return existing
        }

        val result = existing.toMutableMap()
        var prevValue = existing[lastDate]
            ?: existing.keys.filter { it <= lastDate }.maxOrNull()?.let { existing[it] }
            ?: return existing

        for (date in newDates) {
            val rate = fredRates[date] ?: continue
            prevValue *= (1.0 + rate / 100.0 / 252.0)
            result[date] = prevValue
        }

        logger.info("Extended EFFRX by ${newDates.size} days (${newDates.first()} to ${newDates.last()})")
        return result
    }

    /** Fetches the FRED EFFR daily CSV. Returns date -> annualised rate (%). Skips blank holiday rows. */
    private fun fetchFredEffr(): Map<LocalDate, Double> {
        val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url("https://fred.stlouisfed.org/graph/fredgraph.csv?id=EFFR")
            .header("User-Agent", "Mozilla/5.0")
            .build()
        val body = http.newCall(request).execute().use { resp ->
            check(resp.isSuccessful) { "FRED HTTP ${resp.code}" }
            resp.body!!.string()
        }
        val result = mutableMapOf<LocalDate, Double>()
        body.lineSequence().drop(1).forEach { line ->
            val cols = line.split(",")
            if (cols.size < 2) return@forEach
            val date = runCatching { LocalDate.parse(cols[0].trim()) }.getOrNull() ?: return@forEach
            val rate = cols[1].trim().toDoubleOrNull() ?: return@forEach  // blank on holidays
            result[date] = rate
        }
        logger.info("Fetched ${result.size} FRED EFFR entries (latest: ${result.keys.maxOrNull()})")
        return result
    }

    // ── LETF helpers ──────────────────────────────────────────────────────────

    /**
     * Parses a LETF definition string like "1 KMLM 1 VT S=1.5 R=Q".
     * Returns null for plain tickers (no spaces).
     * Tokens: alternating <multiplier> <TICKER> pairs, plus optional S=<pct> and R=<strategy>.
     * R values: D=Daily, W=Weekly, M=Monthly, Q=Quarterly, Y=Yearly (default: null = inherit from outer portfolio).
     */
    internal fun parseLETFDefinition(ticker: String): LETFDefinition? {
        if (!ticker.contains(' ')) return null
        val tokens = ticker.trim().split(Regex("\\s+"))
        val components = mutableListOf<LETFComponent>()
        var spread = 0.015
        var rebalanceStrategy = RebalanceStrategy.QUARTERLY
        var expenseRatio = 0.0
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
            } else if (token.startsWith("E=", ignoreCase = true)) {
                expenseRatio = token.substring(2).toDoubleOrNull()?.div(100.0) ?: 0.0
                i++
            } else {
                val multiplier = token.toDoubleOrNull()
                if (multiplier != null && i + 1 < tokens.size) {
                    val tickerName = tokens[i + 1]
                    if (!tickerName.startsWith("S=", ignoreCase = true) &&
                        !tickerName.startsWith("R=", ignoreCase = true) &&
                        !tickerName.startsWith("E=", ignoreCase = true)
                    ) {
                        components.add(LETFComponent(tickerName.uppercase(), multiplier))
                        i += 2
                    } else {
                        i++
                    }
                } else if (multiplier == null) {
                    // Bare ticker name with no leading multiplier → treat as 1x (e.g. "CTA E=2.0")
                    components.add(LETFComponent(token.uppercase(), 1.0))
                    i++
                } else {
                    i++
                }
            }
        }
        if (components.isEmpty()) throw IllegalArgumentException("No components found in LETF definition: $ticker")
        return LETFDefinition(components, spread, rebalanceStrategy, expenseRatio)
    }

    /**
     * Computes a synthetic equity series for a LETF definition.
     * Simulated as a periodically-rebalanced portfolio of its components
     * with DAILY margin and borrow spread equal to [LETFDefinition.spread].
     * [rebalanceStrategy] is the effective strategy: [LETFDefinition.rebalanceStrategy] if specified via R=,
     * otherwise the outer portfolio's strategy.
     * Returns a Map<LocalDate, Double> parallel to [dates], starting at 10,000.
     */
    internal fun computeLetfSeries(
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
            upperRebalanceMode = MarginRebalanceMode.DAILY.name,
            lowerRebalanceMode = MarginRebalanceMode.DAILY.name
        )
        val result = applyMarginProportional(letfConfig, componentSeriesMap, dates, effrx, mc)
        val rawSeries = dates.zip(result.values).associate { (date, value) -> date to value }
        if (def.expenseRatio <= 0.0) return rawSeries

        // Apply expense ratio as a daily drag on NAV: each day's return is multiplied by (1 - er/252)
        val adjusted = mutableMapOf<LocalDate, Double>()
        var prevRaw = rawSeries[dates[0]]!!
        var prevAdj = prevRaw
        adjusted[dates[0]] = prevAdj
        for (i in 1 until dates.size) {
            val curRaw = rawSeries[dates[i]]!!
            prevAdj *= (curRaw / prevRaw) * (1.0 - def.expenseRatio / 252.0)
            adjusted[dates[i]] = prevAdj
            prevRaw = curRaw
        }
        return adjusted
    }

    // ── CSV helpers ───────────────────────────────────────────────────────────

    internal fun readSimCsv(file: File): TreeMap<LocalDate, Double> {
        val result = TreeMap<LocalDate, Double>()
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

    internal fun writeSimCsv(file: File, series: Map<LocalDate, Double>) {
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

    // ── Chain-link extension / prepend ───────────────────────────────────────

    /**
     * Extends [existing] (date -> normalised value) forward by chaining returns from [yahoo] (date -> raw adj-close).
     * Anchors before [lastSimDate], validates existing overlapping dates before that provisional tail,
     * then replaces every cached date after the anchor. This intentionally drops [lastSimDate] even if it exists
     * because the cached tail may be an intraday mark or may have been built from incomplete historical data.
     */
    internal fun chainExtend(
        existing: Map<LocalDate, Double>,
        yahoo: Map<LocalDate, Double>,
        lastSimDate: LocalDate
    ): Map<LocalDate, Double> {
        val sortedYahooDates = yahoo.keys.sorted()
        require(sortedYahooDates.isNotEmpty()) { "Yahoo data is empty" }

        val anchorDate = sortedYahooDates
            .filter { it < lastSimDate && existing.containsKey(it) }
            .maxOrNull()
            ?: throw IllegalStateException("No overlap before cached last date $lastSimDate")

        val result = existing.filterKeys { it <= anchorDate }.toMutableMap()
        var prevYahoo = yahoo[anchorDate]!!
        var prevValue = existing[anchorDate]!!

        for (date in sortedYahooDates.filter { it > anchorDate }) {
            val currentYahoo = yahoo[date] ?: continue
            if (prevYahoo == 0.0) { prevYahoo = currentYahoo; continue }
            val newValue = prevValue * (currentYahoo / prevYahoo)

            if (date < lastSimDate) {
                existing[date]?.let { existingValue ->
                    val relErr = if (existingValue != 0.0) abs(newValue - existingValue) / existingValue else 0.0
                    if (relErr > 1e-4)
                        throw IllegalStateException(
                            "Chain-link mismatch at $date: computed $newValue but existing has $existingValue (rel err ${String.format("%.2e", relErr)})"
                        )
                }
            }
            result[date] = newValue

            prevYahoo = currentYahoo
            prevValue = newValue
        }
        return result
    }

    /**
     * Extends [existing] forward from the latest pre-tail Yahoo date that already exists in the cached series.
     * This is used for bundled synthetic resources whose historical overlap may not exactly match
     * current Yahoo data. It preserves history through the anchor, then rebuilds the cached tail from Yahoo.
     */
    internal fun chainExtendFromAnchor(
        existing: Map<LocalDate, Double>,
        yahoo: Map<LocalDate, Double>,
        lastSimDate: LocalDate
    ): Map<LocalDate, Double> {
        val sortedYahooDates = yahoo.keys.sorted()
        require(sortedYahooDates.isNotEmpty()) { "Yahoo data is empty" }

        val anchorDate = sortedYahooDates
            .filter { it < lastSimDate && existing.containsKey(it) }
            .maxOrNull()
            ?: throw IllegalStateException("No overlap before cached last date $lastSimDate")

        val result = existing.filterKeys { it <= anchorDate }.toMutableMap()
        var prevYahoo = yahoo[anchorDate]!!
        var prevValue = existing[anchorDate]!!

        for (date in sortedYahooDates.filter { it > anchorDate }) {
            val currentYahoo = yahoo[date] ?: continue
            if (prevYahoo == 0.0) { prevYahoo = currentYahoo; continue }
            val newValue = prevValue * (currentYahoo / prevYahoo)
            result[date] = newValue
            prevYahoo = currentYahoo
            prevValue = newValue
        }

        return result
    }

    /**
     * Extends [existing] (date -> normalised value) backward by chaining returns from [yahoo] (date -> raw adj-close).
     * Anchors at yahoo's last date (must exist in [existing]), validates the overlap region (>= [firstSimDate]),
     * and writes new entries only for dates strictly before [firstSimDate].
     */
    internal fun chainPrepend(
        existing: Map<LocalDate, Double>,
        yahoo: Map<LocalDate, Double>,
        firstSimDate: LocalDate
    ): Map<LocalDate, Double> {
        val sortedYahooDates = yahoo.keys.sorted()
        require(sortedYahooDates.isNotEmpty()) { "Yahoo data is empty" }

        val lastYahooDate = sortedYahooDates.last()
        val startExistingValue = existing[lastYahooDate]
            ?: throw IllegalStateException("No overlap: existing has no entry for yahoo's last date $lastYahooDate")

        val result = existing.toMutableMap()
        var nextYahoo = yahoo[lastYahooDate]!!
        var nextValue = startExistingValue

        for (date in sortedYahooDates.dropLast(1).reversed()) {
            val currentYahoo = yahoo[date] ?: continue
            if (nextYahoo == 0.0) { nextYahoo = currentYahoo; continue }
            val newValue = nextValue * (currentYahoo / nextYahoo)

            if (date >= firstSimDate) {
                val existingValue = existing[date]
                    ?: throw IllegalStateException("Overlap date $date missing from existing series")
                val relErr = if (existingValue != 0.0) abs(newValue - existingValue) / existingValue else 0.0
                if (relErr > 1e-4)
                    throw IllegalStateException(
                        "Chain-link mismatch at $date: computed $newValue but existing has $existingValue (rel err ${String.format("%.2e", relErr)})"
                    )
            } else {
                result[date] = newValue
            }

            nextYahoo = currentYahoo
            nextValue = newValue
        }
        return result
    }

    /** Normalises a raw price series so the first value equals [startValue]. */
    internal fun normalizeFromFirst(
        raw: Map<LocalDate, Double>,
        startValue: Double
    ): Map<LocalDate, Double> {
        val sorted = raw.keys.sorted()
        val firstPrice = raw[sorted.first()] ?: return emptyMap()
        return sorted.associateWith { date -> raw[date]!! / firstPrice * startValue }
    }

    // ── Date intersection ─────────────────────────────────────────────────────

    internal fun intersectDates(
        series: List<Map<LocalDate, Double>>,
        from: LocalDate?,
        to: LocalDate
    ): List<LocalDate> {
        val latestStart = series.mapNotNull { it.keys.minOrNull() }.maxOrNull()
            ?: return emptyList()
        val start = listOfNotNull(from, latestStart).maxOrNull() ?: latestStart
        return series
            .flatMap { it.keys }
            .asSequence()
            .filter { d -> d >= start && d <= to }
            .distinct()
            .sorted()
            .toList()
    }

    // ── Portfolio computation ─────────────────────────────────────────────────

    private fun computeNoMargin(
        pConfig: PortfolioConfig,
        seriesMap: Map<String, Map<LocalDate, Double>>,
        dates: List<LocalDate>,
        startingBalance: Double = 10_000.0,
        cashflow: CashflowConfig? = null
    ): List<Double> {
        val (tickers, targetWeights) = pConfig.mergeWeights()

        // Initial allocation: weights × start value
        val startValue = startingBalance
        val holdings = tickers.associateWith { ticker ->
            startValue * (targetWeights[ticker] ?: 0.0)
        }.toMutableMap()

        val values = mutableListOf<Double>()
        values.add(startValue)

        val returnRatios = buildReturnRatios(tickers, seriesMap, dates)

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

            applyDailyReturns(tickers, holdings, returnRatios, i)

            if (cashflow != null && isCashflowDate(cashflow.frequency, curDate)) {
                for (ticker in tickers) {
                    holdings[ticker] = (holdings[ticker] ?: 0.0) + cashflow.amount * (targetWeights[ticker] ?: 0.0)
                }
            }

            values.add(holdings.values.sum())
        }
        return values
    }

    private fun scheduledPortfolioRebalanceActionPoints(
        dates: List<LocalDate>,
        rebalanceStrategy: RebalanceStrategy
    ): List<ActionPoint> =
        (1 until dates.size)
            .filter { i -> shouldRebalance(rebalanceStrategy, dates[i - 1], dates[i]) }
            .map { i -> ActionPoint(dates[i].toString(), "PORTFOLIO_REBALANCE") }

    private fun applyDailyReturns(
        tickers: List<String>,
        holdings: MutableMap<String, Double>,
        returnRatios: Map<String, DoubleArray>,
        i: Int
    ) {
        for (ticker in tickers)
            holdings[ticker] = (holdings[ticker] ?: 0.0) * (returnRatios[ticker]?.get(i) ?: 1.0)
    }

    internal fun buildReturnRatios(
        tickers: List<String>,
        seriesMap: Map<String, Map<LocalDate, Double>>,
        dates: List<LocalDate>
    ): Map<String, DoubleArray> {
        val n = dates.size
        return tickers.associateWith { ticker ->
            val s = seriesMap[ticker] ?: return@associateWith DoubleArray(n) { 1.0 }
            val filled = forwardFillSeries(s, dates)
            DoubleArray(n) { i ->
                if (i == 0) 1.0
                else {
                    val prev = filled[dates[i - 1]] ?: 1.0
                    val cur = filled[dates[i]] ?: prev
                    if (prev == 0.0) 1.0 else cur / prev
                }
            }
        }
    }

    internal fun forwardFillSeries(
        series: Map<LocalDate, Double>,
        dates: List<LocalDate>
    ): Map<LocalDate, Double> {
        if (series.isEmpty() || dates.isEmpty()) return emptyMap()
        val sortedEntries = series.entries.sortedBy { it.key }
        val filled = LinkedHashMap<LocalDate, Double>(dates.size)
        var idx = 0
        var lastValue: Double? = null

        for (date in dates) {
            while (idx < sortedEntries.size && !sortedEntries[idx].key.isAfter(date)) {
                lastValue = sortedEntries[idx].value
                idx++
            }
            if (lastValue != null) filled[date] = lastValue
        }
        return filled
    }

    internal fun buildDailyLoanRates(
        dates: List<LocalDate>,
        effrx: Map<LocalDate, Double>,
        dailySpread: Double
    ): DoubleArray = DoubleArray(dates.size) { i ->
        if (i == 0) dailySpread
        else {
            val prev = effrx[dates[i - 1]]
            val cur = effrx[dates[i]]
            if (prev != null && cur != null && prev != 0.0) (cur / prev - 1.0) + dailySpread
            else dailySpread
        }
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

        RebalanceStrategy.BI_WEEKLY ->
            java.time.temporal.ChronoUnit.WEEKS.between(LocalDate.of(1970, 1, 5), curDate) / 2 !=
                    java.time.temporal.ChronoUnit.WEEKS.between(LocalDate.of(1970, 1, 5), prevDate) / 2

        RebalanceStrategy.MONTHLY -> curDate.month != prevDate.month
        RebalanceStrategy.BI_MONTHLY -> curDate.year * 12 + (curDate.monthValue - 1) / 2 !=
                prevDate.year * 12 + (prevDate.monthValue - 1) / 2

        RebalanceStrategy.QUARTERLY -> ((curDate.monthValue - 1) / 3) != ((prevDate.monthValue - 1) / 3)
        RebalanceStrategy.EVERY_4_MONTHS -> curDate.year * 12 + (curDate.monthValue - 1) / 4 !=
                prevDate.year * 12 + (prevDate.monthValue - 1) / 4

        RebalanceStrategy.HALF_YEARLY -> curDate.year * 12 + (curDate.monthValue - 1) / 6 !=
                prevDate.year * 12 + (prevDate.monthValue - 1) / 6

        RebalanceStrategy.YEARLY -> curDate.year != prevDate.year
    }

    // ── Margin computation ────────────────────────────────────────────────────

    private fun isCashflowDate(frequency: CashflowFrequency, date: LocalDate): Boolean =
        when (frequency) {
            CashflowFrequency.NONE -> false
            CashflowFrequency.MONTHLY -> date.dayOfMonth == 1
            CashflowFrequency.QUARTERLY -> date.dayOfMonth == 1 && date.monthValue in listOf(1, 4, 7, 10)
            CashflowFrequency.YEARLY -> date.dayOfMonth == 1 && date.monthValue == 1
        }

    private data class MarginApplyResult(
        val values: List<Double>,
        val marginUtilization: List<Double>,
        val upperTriggers: Int,   // ratio > target + deviation (market fell, forced to de-lever)
        val lowerTriggers: Int,   // ratio < target - deviation (market rose, forced to re-lever)
        val actionPoints: List<ActionPoint> = emptyList()
    )

    private fun applyMargin(
        noMargin: List<Double>,
        dates: List<LocalDate>,
        effrx: Map<LocalDate, Double>,
        marginConfig: MarginConfig,
        rebalanceStrategy: RebalanceStrategy,
        zeroMarginInterest: Boolean = false,
    ): MarginApplyResult {
        val marginTarget = marginConfig.marginRatio
        val spread = marginConfig.marginSpread
        val deviationUpper = marginConfig.marginDeviationUpper
        val deviationLower = marginConfig.marginDeviationLower

        var equity = noMargin[0]
        var borrowed = equity * marginTarget
        var totalExposure = equity + borrowed

        val result = mutableListOf(equity)
        val marginUtilization = mutableListOf(marginTarget)
        val actionPoints = mutableListOf<ActionPoint>()
        var upperTriggers = 0
        var lowerTriggers = 0

        val dailySpread = spread / 252.0
        val dailyLoanRates =
            if (zeroMarginInterest) DoubleArray(dates.size)
            else buildDailyLoanRates(dates, effrx, dailySpread)

        for (i in 1 until noMargin.size) {
            val prevDate = dates[i - 1]
            val curDate = dates[i]

            // Portfolio return
            val portfolioReturn = if (noMargin[i - 1] != 0.0) noMargin[i] / noMargin[i - 1] else 1.0
            totalExposure *= portfolioReturn

            borrowed *= (1.0 + dailyLoanRates[i])
            equity = totalExposure - borrowed

            // Margin ratio check
            val currentMarginRatio = if (equity != 0.0) borrowed / equity else marginTarget
            val rebalanceDay = shouldRebalance(rebalanceStrategy, prevDate, curDate)
            val upperBreach = currentMarginRatio > marginTarget + deviationUpper
            val lowerBreach = currentMarginRatio < marginTarget - deviationLower
            val deviationBreach = upperBreach || lowerBreach

            if (deviationBreach && !rebalanceDay) {
                if (upperBreach) {
                    upperTriggers++
                    actionPoints.add(ActionPoint(curDate.toString(), "SELL_HIGH"))
                } else {
                    lowerTriggers++
                    actionPoints.add(ActionPoint(curDate.toString(), "BUY_LOW"))
                }
            }

            if (rebalanceDay || deviationBreach) {
                if (rebalanceDay) actionPoints.add(ActionPoint(curDate.toString(), "PORTFOLIO_REBALANCE"))
                borrowed = equity * marginTarget
                totalExposure = equity + borrowed
            }

            result.add(equity)
            marginUtilization.add(if (equity > 0.0) borrowed.coerceAtLeast(0.0) / equity else 0.0)
        }
        return MarginApplyResult(result, marginUtilization, upperTriggers, lowerTriggers, actionPoints)
    }

    fun computeWaterfall(
        tickers: List<String>,
        holdings: MutableMap<String, Double>,
        targetWeights: Map<String, Double>,
        delta: Double
    ) {
        val totalHoldings = holdings.values.sum()
        val finalTotal = totalHoldings + delta
        if (finalTotal == 0.0 || delta == 0.0) return
        val sign = if (delta >= 0) 1.0 else -1.0
        val deviations = tickers.associateWith { ticker ->
            (holdings[ticker] ?: 0.0) / finalTotal - (targetWeights[ticker] ?: 0.0)
        }

        val sorted = if (delta >= 0)
            tickers.sortedBy { deviations[it] ?: 0.0 }
        else
            tickers.sortedByDescending { deviations[it] ?: 0.0 }

        var remaining = abs(delta)
        var groupLevel = deviations[sorted.firstOrNull()] ?: return
        for (i in sorted.indices) {
            if (remaining <= 0.0) break
            val nextRawLevel = if (i + 1 < sorted.size) deviations[sorted[i + 1]] ?: 0.0 else 0.0
            val nextLevel = if (delta >= 0) minOf(nextRawLevel, 0.0) else maxOf(nextRawLevel, 0.0)
            val levelDistance = (nextLevel - groupLevel) * sign
            if (levelDistance <= 0.0) continue

            val groupSize = i + 1
            val costToLevel = levelDistance * finalTotal * groupSize
            if (remaining >= costToLevel) {
                val amountPerTicker = (nextLevel - groupLevel) * finalTotal
                for (j in 0..i) {
                    holdings[sorted[j]] = (holdings[sorted[j]] ?: 0.0) + amountPerTicker
                }
                remaining -= costToLevel
                groupLevel = nextLevel
            } else {
                val amountPerTicker = remaining / groupSize * sign
                for (j in 0..i) {
                    holdings[sorted[j]] = (holdings[sorted[j]] ?: 0.0) + amountPerTicker
                }
                remaining = 0.0
            }
        }

        if (remaining > 0.0) {
            for (ticker in tickers)
                holdings[ticker] =
                    (holdings[ticker] ?: 0.0) + remaining * sign * (targetWeights[ticker] ?: 0.0)
        }
    }

    fun computeAllocationDeltas(
        tickers: List<String>,
        holdings: Map<String, Double>,
        targetWeights: Map<String, Double>,
        delta: Double,
        mode: String
    ): Map<String, Double> {
        val hybrid = HybridAllocStrategyRegistry.find(mode)
        if (hybrid != null) {
            return weightedAverageAllocationDeltas(
                computeAllocationDeltas(tickers, holdings, targetWeights, delta, hybrid.first),
                computeAllocationDeltas(tickers, holdings, targetWeights, delta, hybrid.second),
                tickers,
                hybrid.firstRatio,
                hybrid.secondRatio,
            )
        }
        val baseMode = HybridAllocStrategyRegistry.baseMode(mode) ?: MarginRebalanceMode.PROPORTIONAL
        return computeBaseAllocationDeltas(tickers, holdings, targetWeights, delta, baseMode)
    }

    private fun computeBaseAllocationDeltas(
        tickers: List<String>,
        holdings: Map<String, Double>,
        targetWeights: Map<String, Double>,
        delta: Double,
        mode: MarginRebalanceMode
    ): Map<String, Double> =
        when (mode) {
            MarginRebalanceMode.PROPORTIONAL,
            MarginRebalanceMode.DAILY ->
                proportionalAllocationDeltas(tickers, targetWeights, delta)

            MarginRebalanceMode.CURRENT_WEIGHT -> {
                val total = tickers.sumOf { holdings[it] ?: 0.0 }
                if (total == 0.0) tickers.associateWith { 0.0 }
                else tickers.associateWith { delta * ((holdings[it] ?: 0.0) / total) }
            }

            MarginRebalanceMode.FULL_REBALANCE ->
                fullRebalanceAllocationDeltas(tickers, holdings, targetWeights, delta)

            MarginRebalanceMode.UNDERVALUED_PRIORITY ->
                allocationDeltasViaMutable(tickers, holdings, targetWeights, delta, ::computeUndervalueFirst)

            MarginRebalanceMode.WATERFALL ->
                allocationDeltasViaMutable(tickers, holdings, targetWeights, delta, ::computeWaterfall)

            MarginRebalanceMode.HYBRID_WATERFALL_FULL_REBALANCE ->
                error("Hybrid allocation mode must be resolved before base allocation")
        }

    fun applyAllocationMode(
        tickers: List<String>,
        holdings: MutableMap<String, Double>,
        targetWeights: Map<String, Double>,
        delta: Double,
        mode: String
    ) {
        val deltas = computeAllocationDeltas(tickers, holdings, targetWeights, delta, mode)
        for (ticker in tickers) {
            holdings[ticker] = (holdings[ticker] ?: 0.0) + (deltas[ticker] ?: 0.0)
        }
    }

    private fun proportionalAllocationDeltas(
        tickers: List<String>,
        targetWeights: Map<String, Double>,
        delta: Double
    ): Map<String, Double> =
        tickers.associateWith { delta * (targetWeights[it] ?: 0.0) }

    private fun fullRebalanceAllocationDeltas(
        tickers: List<String>,
        holdings: Map<String, Double>,
        targetWeights: Map<String, Double>,
        delta: Double
    ): Map<String, Double> {
        val finalTotal = tickers.sumOf { holdings[it] ?: 0.0 } + delta
        return tickers.associateWith { finalTotal * (targetWeights[it] ?: 0.0) - (holdings[it] ?: 0.0) }
    }

    private fun weightedAverageAllocationDeltas(
        a: Map<String, Double>,
        b: Map<String, Double>,
        tickers: List<String>,
        aRatio: Double,
        bRatio: Double
    ): Map<String, Double> {
        val total = aRatio + bRatio
        val safeA = if (total > 0.0) aRatio else 1.0
        val safeB = if (total > 0.0) bRatio else 1.0
        val safeTotal = safeA + safeB
        return tickers.associateWith { ((a[it] ?: 0.0) * safeA + (b[it] ?: 0.0) * safeB) / safeTotal }
    }

    private fun allocationDeltasViaMutable(
        tickers: List<String>,
        holdings: Map<String, Double>,
        targetWeights: Map<String, Double>,
        delta: Double,
        allocator: (List<String>, MutableMap<String, Double>, Map<String, Double>, Double) -> Unit
    ): Map<String, Double> {
        val temp = tickers.associateWith { holdings[it] ?: 0.0 }.toMutableMap()
        allocator(tickers, temp, targetWeights, delta)
        return tickers.associateWith { (temp[it] ?: 0.0) - (holdings[it] ?: 0.0) }
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
        mc: MarginConfig,
        startingBalance: Double = 10_000.0,
        cashflow: CashflowConfig? = null,
        zeroMarginInterest: Boolean = false,
    ): MarginApplyResult {
        val (tickers, targetWeights) = pConfig.mergeWeights()

        val startEquity = startingBalance
        var borrowed = startEquity * mc.marginRatio
        val holdings = tickers.associateWith { ticker ->
            (startEquity + borrowed) * (targetWeights[ticker] ?: 0.0)
        }.toMutableMap()

        val dailySpread = mc.marginSpread / 252.0
        val targetRatio = mc.marginRatio
        val result = mutableListOf(startEquity)
        val marginUtilization = mutableListOf(targetRatio)
        val actionPoints = mutableListOf<ActionPoint>()
        var upperTriggers = 0
        var lowerTriggers = 0

        val upperThreshold = mc.marginRatio + mc.marginDeviationUpper
        val lowerThreshold = mc.marginRatio - mc.marginDeviationLower
        val isDailyMode = mc.upperRebalanceMode == MarginRebalanceMode.DAILY.name
        val returnRatios = buildReturnRatios(tickers, seriesMap, dates)
        val dailyLoanRates =
            if (zeroMarginInterest) DoubleArray(dates.size)
            else buildDailyLoanRates(dates, effrx, dailySpread)
        var previousMarginRatio = targetRatio

        for (i in 1 until dates.size) {
            val prevDate = dates[i - 1]
            val curDate = dates[i]
            val rebalanceDay = shouldRebalance(pConfig.rebalanceStrategy, prevDate, curDate)

            applyDailyReturns(tickers, holdings, returnRatios, i)

            if (cashflow != null && isCashflowDate(cashflow.frequency, curDate)) {
                val contributionExposure = cashflow.amount * (1.0 + targetRatio)
                borrowed += cashflow.amount * targetRatio
                for (ticker in tickers) {
                    holdings[ticker] =
                        (holdings[ticker] ?: 0.0) + contributionExposure * (targetWeights[ticker] ?: 0.0)
                }
            }

            borrowed *= (1.0 + dailyLoanRates[i])

            val equity = holdings.values.sum() - borrowed

            if (isDailyMode) {
                // Reset margin ratio daily using current weights (no deviation tracking)
                val newBorrowed = equity * targetRatio
                val delta = newBorrowed - borrowed
                val totalHoldings = holdings.values.sum()
                if (totalHoldings != 0.0)
                    for (ticker in tickers)
                        holdings[ticker] = (holdings[ticker] ?: 0.0) * (1.0 + delta / totalHoldings)
                borrowed = newBorrowed
            } else if (rebalanceDay) {
                // Scheduled full asset rebalance at today's close.
                actionPoints.add(ActionPoint(curDate.toString(), "PORTFOLIO_REBALANCE"))
                borrowed = equity * targetRatio
                val newTotalExposure = equity + borrowed
                for (ticker in tickers) {
                    holdings[ticker] = newTotalExposure * (targetWeights[ticker] ?: 0.0)
                }
            } else {
                // Margin deviation actions execute on the next trading day using yesterday's margin ratio.
                val upperBreach = previousMarginRatio > upperThreshold
                val lowerBreach = previousMarginRatio < lowerThreshold
                val deviationBreach = upperBreach || lowerBreach

                if (deviationBreach) {
                    if (upperBreach) {
                        upperTriggers++
                        actionPoints.add(ActionPoint(curDate.toString(), "SELL_HIGH"))
                    } else {
                        lowerTriggers++
                        actionPoints.add(ActionPoint(curDate.toString(), "BUY_LOW"))
                    }
                }

                if (deviationBreach) {
                    val newBorrowed = equity * targetRatio
                    val mode = if (upperBreach) mc.upperRebalanceMode else mc.lowerRebalanceMode
                    applyAllocationMode(tickers, holdings, targetWeights, newBorrowed - borrowed, mode)
                    borrowed = newBorrowed
                }
            }

            val endEquity = holdings.values.sum() - borrowed
            result.add(endEquity)
            previousMarginRatio = if (endEquity > 0.0) borrowed.coerceAtLeast(0.0) / endEquity else 0.0
            marginUtilization.add(previousMarginRatio)
        }
        return MarginApplyResult(result, marginUtilization, upperTriggers, lowerTriggers, actionPoints)
    }

    fun computeUndervalueFirst(
        tickers: List<String>,
        holdings: MutableMap<String, Double>,
        targetWeights: Map<String, Double>,
        delta: Double
    ) {
        val totalHoldings = holdings.values.sum()
        val finalTotal = totalHoldings + delta
        val sign = if (delta >= 0) 1.0 else -1.0

        val sorted = if (delta >= 0)
            tickers.sortedBy { (holdings[it] ?: 0.0) / finalTotal - (targetWeights[it] ?: 0.0) }
        else
            tickers.sortedByDescending {
                (holdings[it] ?: 0.0) / finalTotal - (targetWeights[it] ?: 0.0)
            }

        var remaining = abs(delta)
        for (ticker in sorted) {
            if (remaining <= 0.0) break
            val cur = holdings[ticker] ?: 0.0
            val target = finalTotal * (targetWeights[ticker] ?: 0.0)
            val amount = minOf(remaining, maxOf(0.0, (target - cur) * sign))
            holdings[ticker] = cur + amount * sign
            remaining -= amount
        }
        if (remaining > 0.0) {
            for (ticker in tickers)
                holdings[ticker] =
                    (holdings[ticker] ?: 0.0) + remaining * sign * (targetWeights[ticker] ?: 0.0)
        }
    }

    // ── Statistics ────────────────────────────────────────────────────────────

    internal fun computeBacktestStats(
        values: List<Double>,
        dates: List<LocalDate>,
        effrx: Map<LocalDate, Double>,
        marginUpperTriggers: Int? = null,
        marginLowerTriggers: Int? = null
    ): BacktestStats {
        if (values.size < 2) return BacktestStats(
            0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0,
            values.lastOrNull() ?: 0.0
        )
        val years = (dates.last().toEpochDay() - dates.first().toEpochDay()) / 365.25
        val stats = computeStats(values, years, computeRfAnnualized(effrx))
        return BacktestStats(
            stats.cagr, stats.maxDrawdown, stats.sharpe, stats.ulcerIndex, stats.upi,
            stats.annualVolatility, stats.longestDrawdownDays,
            values.last(), marginUpperTriggers, marginLowerTriggers
        )
    }

    internal fun computeRfAnnualized(effrx: Map<LocalDate, Double>): Double {
        if (effrx.size < 2) return 0.0
        val sorted = effrx.keys.sorted()
        val rfLogReturns = (1 until sorted.size).mapNotNull { i ->
            val prev = effrx[sorted[i - 1]] ?: return@mapNotNull null
            val cur = effrx[sorted[i]] ?: return@mapNotNull null
            if (prev > 0) ln(cur / prev) else null
        }
        val rfDaily = if (rfLogReturns.isNotEmpty()) rfLogReturns.average() else 0.0
        return (1.0 + rfDaily).pow(252.0) - 1.0
    }
}
