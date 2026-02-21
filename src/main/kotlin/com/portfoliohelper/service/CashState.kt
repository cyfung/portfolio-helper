package com.portfoliohelper.service

import com.portfoliohelper.model.CashEntry
import java.util.concurrent.atomic.AtomicReference

/**
 * Thread-safe holder for the current cash entries.
 * Updated when cash.txt file changes.
 */
object CashState {
    private val currentEntries = AtomicReference<List<CashEntry>>(emptyList())

    fun getEntries(): List<CashEntry> = currentEntries.get()

    fun update(entries: List<CashEntry>) {
        currentEntries.set(entries)
    }
}
