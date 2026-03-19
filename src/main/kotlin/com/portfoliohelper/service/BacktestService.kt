package com.portfoliohelper.service

import com.portfoliohelper.AppDirs
import com.portfoliohelper.service.yahoo.YahooHistoricalFetcher
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
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
            val curves = mutableListOf<CurveResult>()
            if (pConfig.includeNoMargin) {
                val noMarginPoints =
                    globalDates.mapIndexed { i, d -> DataPoint(d.toString(), noMarginValues[i]) }
                val noMarginStats = computeBacktestStats(noMarginValues, globalDates, effrxSeries)
                curves.add(CurveResult("No Margin", noMarginPoints, noMarginStats))
            }

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
                val marginStats = computeBacktestStats(
                    marginResult.values, globalDates, effrxSeries,
                    marginResult.upperTriggers, marginResult.lowerTriggers
                )

                fun modeAbbr(m: MarginRebalanceMode) = when (m) {
                    MarginRebalanceMode.CURRENT_WEIGHT -> "Cur Wt"
                    MarginRebalanceMode.PROPORTIONAL -> "Tgt Wt"
                    MarginRebalanceMode.FULL_REBALANCE -> "Full"
                    MarginRebalanceMode.UNDERVALUED_PRIORITY -> "UVal"
                    MarginRebalanceMode.WATERFALL -> "WaterFall"
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


    internal fun getResourceFiles(path: String): List<String> {
        val url = object {}.javaClass.classLoader.getResource(path)
        if (url == null) return emptyList()
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
     *
     * Three-tier fallback:
     *   Tier 1 — local .ticker file: load, sanity-check, extend; delete + fall through on failure.
     *   Tier 2 — resource .ticker file: copy, sanity-check, extend; delete + fall through on failure.
     *   Tier 3 — rebuild from Yahoo from scratch.
     */
    internal fun loadNormalizedSeries(
        ticker: String,
        neededFromDate: LocalDate
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
                neededFromDate
            )
            if (extended != null) return extended
            localFiles.forEach { it.delete() }
            logger.warn("$upperTicker Tier 1 (local file) failed — deleted, falling through to resource")
        }

        // Tier 2 — resource file
        val resourceFiles = copyFromResources(simPattern)
        if (resourceFiles.isNotEmpty()) {
            val extended = tryExtendAndValidate(
                ticker,
                upperTicker,
                resourceFiles,
                today,
                neededFromDate
            )
            if (extended != null) return extended
            resourceFiles.forEach { it.delete() }
            logger.warn("$upperTicker Tier 2 (resource file) failed — deleted, rebuilding from scratch")
        }

        // Tier 3 — rebuild from scratch
        logger.info("No valid SIM file for $upperTicker, fetching from Yahoo since $neededFromDate")
        val raw = YahooHistoricalFetcher.fetchAdjustedClose(ticker, neededFromDate, today)
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
     * Extend (if lastKnownDate < neededToDate): fetches Yahoo from lastKnownDate−10 days to neededToDate,
     *   then calls chainExtend to append new entries.
     * Both operations throw on overlap mismatch (caught here → returns null).
     */
    private fun tryExtendAndValidate(
        ticker: String,
        upperTicker: String,
        files: List<File>,
        neededToDate: LocalDate,
        neededFromDate: LocalDate
    ): Map<LocalDate, Double>? {
        val file = files.first()
        logger.info("Loading SIM file for $upperTicker: ${file.name}")
        val existing = readSimCsv(file)
        if (existing.isEmpty()) return null
        val firstDate = existing.firstKey()
        val lastKnownDate = existing.lastKey()
        val fileAge = (System.currentTimeMillis() - file.lastModified()).milliseconds
        if (fileAge <= 5.minutes && lastKnownDate >= neededToDate && firstDate <= neededFromDate) {
            return existing
        }
        if (existing.size < 20) return null

        var current: Map<LocalDate, Double> = existing

        // Prepend if needed
        if (neededFromDate < firstDate) {
            val earlyYahoo = try {
                YahooHistoricalFetcher.fetchAdjustedClose(ticker, neededFromDate, firstDate.plusDays(10))
            } catch (e: Exception) {
                logger.warn("$upperTicker early probe fetch failed: ${e.message}")
                return null
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

        // Extend forward if needed
        if (lastKnownDate < neededToDate) {
            logger.info("Extending $upperTicker SIM from $lastKnownDate to $neededToDate via Yahoo")
            val yahoo = try {
                YahooHistoricalFetcher.fetchAdjustedClose(ticker, lastKnownDate.minusDays(10), neededToDate)
            } catch (e: Exception) {
                logger.warn("$upperTicker extend fetch failed: ${e.message}")
                return null
            }
            current = try {
                chainExtend(current, yahoo, lastKnownDate)
            } catch (e: Exception) {
                logger.warn("Failed to extend $upperTicker via Yahoo: ${e.message}")
                return null
            }
        }

        val newFile = File(tickerDir, "${upperTicker}-${neededToDate}.csv")
        writeSimCsv(newFile, current)
        files.forEach { it.delete() }
        return current
    }

    private fun copyFromResources(simPattern: Regex): List<File> {
        val allResourceFiles = getResourceFiles("data/.ticker")
        val resourcesFile = allResourceFiles.firstOrNull {
            simPattern.matches(it)
        } ?: return emptyList()
        val cl = object {}::class.java.classLoader
        cl.getResourceAsStream("data/.ticker/$resourcesFile")
            ?.use { Files.copy(it, tickerDir.toPath().resolve(resourcesFile)) }
        return findFiles(simPattern)
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
            upperRebalanceMode = MarginRebalanceMode.DAILY,
            lowerRebalanceMode = MarginRebalanceMode.DAILY
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
     * Extends [existing] (date → normalised value) forward by chaining returns from [yahoo] (date → raw adj-close).
     * Anchors at yahoo's first date (must exist in [existing]), validates the overlap region (< [lastSimDate]),
     * and writes new entries only for dates >= [lastSimDate].
     */
    internal fun chainExtend(
        existing: Map<LocalDate, Double>,
        yahoo: Map<LocalDate, Double>,
        lastSimDate: LocalDate
    ): Map<LocalDate, Double> {
        val sortedYahooDates = yahoo.keys.sorted()
        require(sortedYahooDates.isNotEmpty()) { "Yahoo data is empty" }

        val firstYahooDate = sortedYahooDates.first()
        val startExistingValue = existing[firstYahooDate]
            ?: throw IllegalStateException("No overlap: existing has no entry for yahoo's first date $firstYahooDate")

        val result = existing.toMutableMap()
        var prevYahoo = yahoo[firstYahooDate]!!
        var prevValue = startExistingValue

        for (date in sortedYahooDates.drop(1)) {
            val currentYahoo = yahoo[date] ?: continue
            if (prevYahoo == 0.0) { prevYahoo = currentYahoo; continue }
            val newValue = prevValue * (currentYahoo / prevYahoo)

            if (date < lastSimDate) {
                val existingValue = existing[date]
                    ?: throw IllegalStateException("Overlap date $date missing from existing series")
                if (existingValue != 0.0 && abs(newValue - existingValue) / existingValue > 1e-6)
                    throw IllegalStateException(
                        "Chain-link mismatch at $date: computed $newValue but existing has $existingValue"
                    )
            } else {
                // >= lastSimDate: write unconditionally. lastSimDate itself is included because the
                // SIM file's last entry may be a partial trading day; Yahoo's adj-close for that same
                // date is the authoritative final close, so we let it overwrite.
                result[date] = newValue
            }

            prevYahoo = currentYahoo
            prevValue = newValue
        }
        return result
    }

    /**
     * Extends [existing] (date → normalised value) backward by chaining returns from [yahoo] (date → raw adj-close).
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
                if (existingValue != 0.0 && abs(newValue - existingValue) / existingValue > 1e-6)
                    throw IllegalStateException(
                        "Chain-link mismatch at $date: computed $newValue but existing has $existingValue"
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
        val (tickers, targetWeights) = pConfig.mergeWeights()

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

            applyDailyReturns(tickers, holdings, seriesMap, prevDate, curDate)

            values.add(holdings.values.sum())
        }
        return values
    }

    private fun applyDailyReturns(
        tickers: List<String>,
        holdings: MutableMap<String, Double>,
        seriesMap: Map<String, Map<LocalDate, Double>>,
        prevDate: LocalDate,
        curDate: LocalDate
    ) {
        for (ticker in tickers) {
            val s = seriesMap[ticker] ?: continue
            val prev = s[prevDate] ?: continue
            val cur = s[curDate] ?: continue
            if (prev == 0.0) continue
            holdings[ticker] = (holdings[ticker] ?: 0.0) * (cur / prev)
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

    fun computeWaterfall(
        tickers: List<String>,
        holdings: MutableMap<String, Double>,
        targetWeights: Map<String, Double>,
        delta: Double
    ) {
        val totalHoldings = holdings.values.sum()
        val finalTotal = totalHoldings + delta
        val sign = if (delta >= 0) 1.0 else -1.0

        val currentDev = mutableMapOf<String, Double>()
        for (ticker in tickers) {
            currentDev[ticker] =
                (holdings[ticker] ?: 0.0) / finalTotal - (targetWeights[ticker] ?: 0.0)
        }

        val sorted = if (delta >= 0)
            tickers.sortedBy { currentDev[it] ?: 0.0 }
        else
            tickers.sortedByDescending { currentDev[it] ?: 0.0 }

        var remaining = abs(delta)

        for (i in sorted.indices) {
            if (remaining <= 0.0) break
            val groupDev = currentDev[sorted[0]] ?: 0.0
            val nextDev = if (i + 1 < sorted.size) currentDev[sorted[i + 1]]
                ?: 0.0 else sign * Double.POSITIVE_INFINITY
            val groupSize = i + 1

            val costToLevel = (nextDev - groupDev) * sign * finalTotal * groupSize

            if (remaining >= costToLevel) {
                for (j in 0..i) {
                    holdings[sorted[j]] =
                        (holdings[sorted[j]] ?: 0.0) + (nextDev - groupDev) * finalTotal
                    currentDev[sorted[j]] = nextDev
                }
                remaining -= costToLevel
            } else {
                val perStock = remaining / groupSize
                for (j in 0..i) {
                    holdings[sorted[j]] = (holdings[sorted[j]] ?: 0.0) + perStock * sign
                    currentDev[sorted[j]] =
                        (currentDev[sorted[j]] ?: 0.0) + (perStock / finalTotal) * sign
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
        val (tickers, targetWeights) = pConfig.mergeWeights()

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

            applyDailyReturns(tickers, holdings, seriesMap, prevDate, curDate)

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

                        MarginRebalanceMode.WATERFALL -> {
                            val delta = newBorrowed - borrowed
                            computeWaterfall(tickers, holdings, targetWeights, delta)
                        }

                        MarginRebalanceMode.UNDERVALUED_PRIORITY -> {
                            val delta = newBorrowed - borrowed
                            computeUndervalueFirst(tickers, holdings, targetWeights, delta)
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

    private fun computeBacktestStats(
        values: List<Double>,
        dates: List<LocalDate>,
        effrx: Map<LocalDate, Double>,
        marginUpperTriggers: Int? = null,
        marginLowerTriggers: Int? = null
    ): BacktestStats {
        if (values.size < 2) return BacktestStats(
            0.0,
            0.0,
            0.0,
            0.0,
            0.0,
            values.lastOrNull() ?: 0.0
        )
        val years = (dates.last().toEpochDay() - dates.first().toEpochDay()) / 365.25
        val stats = computeStats(values, years, computeRfAnnualized(effrx))
        return BacktestStats(
            stats.cagr, stats.maxDrawdown, stats.sharpe, stats.ulcerIndex, stats.upi,
            values.last(), marginUpperTriggers, marginLowerTriggers
        )
    }

    private fun computeRfAnnualized(effrx: Map<LocalDate, Double>): Double {
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
