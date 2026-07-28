package com.portfoliohelper.service

import java.time.LocalDate
import java.time.temporal.ChronoUnit

private const val MARKET_CALENDAR_GRACE_DAYS = 7L

internal fun analysisDataRange(
    requiredSeriesByIdentifier: Map<String, Map<LocalDate, Double>>,
    dates: List<LocalDate>,
    requestedFrom: LocalDate?,
    effectiveTo: LocalDate,
): AnalysisDataRange {
    require(dates.isNotEmpty()) { "Cannot summarize an empty analysis date range" }

    val firstByIdentifier = requiredSeriesByIdentifier.mapValues { (_, prices) -> prices.keys.minOrNull() }
    val lastByIdentifier = requiredSeriesByIdentifier.mapValues { (_, prices) -> prices.keys.maxOrNull() }
    val actualFrom = dates.first()
    val actualTo = dates.last()

    val startLimiters =
        if (requestedFrom == null) {
            val latestStart = firstByIdentifier.values.filterNotNull().maxOrNull()
            val hasMeaningfullyEarlierSeries = firstByIdentifier.values.filterNotNull().any { first ->
                latestStart != null &&
                    ChronoUnit.DAYS.between(first, latestStart) > MARKET_CALENDAR_GRACE_DAYS
            }
            if (hasMeaningfullyEarlierSeries) {
                firstByIdentifier.filterValues { first ->
                    first != null &&
                        latestStart != null &&
                        ChronoUnit.DAYS.between(first, latestStart) <= MARKET_CALENDAR_GRACE_DAYS
                }.keys
            } else {
                emptySet()
            }
        } else if (actualFrom > requestedFrom) {
            firstByIdentifier.filterValues { first -> first != null && first > requestedFrom }.keys
        } else {
            emptySet()
        }

    val endLimiters =
        if (
            actualTo < effectiveTo &&
            ChronoUnit.DAYS.between(actualTo, effectiveTo) > MARKET_CALENDAR_GRACE_DAYS
        ) {
            // Analysis dates are a union calendar with forward-filled prices, so the
            // freshest required series determines the final usable analysis date.
            val latestEnd = lastByIdentifier.values.filterNotNull().maxOrNull()
            lastByIdentifier.filterValues { it == latestEnd }.keys
        } else {
            emptySet()
        }

    return AnalysisDataRange(
        fromDate = actualFrom.toString(),
        toDate = actualTo.toString(),
        startLimiters = startLimiters.distinct().sorted(),
        endLimiters = endLimiters.distinct().sorted(),
    )
}

internal fun mergeRequiredSeries(
    seriesMaps: List<Map<String, Map<LocalDate, Double>>>,
): Map<String, Map<LocalDate, Double>> =
    seriesMaps.flatMap { it.entries }.associate { it.toPair() }
