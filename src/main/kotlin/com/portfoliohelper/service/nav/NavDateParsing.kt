package com.portfoliohelper.service.nav

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

private val navDateFormatters: List<DateTimeFormatter> = listOf(
    DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("M/d/uuuu").toFormatter(Locale.US),
    DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("MMM d, uuuu").toFormatter(Locale.US),
    DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("MMMM d, uuuu").toFormatter(Locale.US),
    DateTimeFormatter.ISO_LOCAL_DATE
)

internal fun parseNavDateValue(value: String): LocalDate? =
    navDateFormatters.firstNotNullOfOrNull { formatter ->
        runCatching { LocalDate.parse(value.trim(), formatter) }.getOrNull()
    }
