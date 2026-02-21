package com.portfoliohelper.service

/**
 * Global registry of all portfolios discovered at startup.
 * Insertion-ordered: Main first, then alphabetical by name.
 */
object PortfolioRegistry {
    private val _entries = LinkedHashMap<String, ManagedPortfolio>()

    val entries: Collection<ManagedPortfolio> get() = _entries.values

    fun register(p: ManagedPortfolio) {
        _entries[p.id] = p
    }

    fun get(id: String): ManagedPortfolio? = _entries[id]

    fun main(): ManagedPortfolio = _entries["main"]!!

    fun hasMultiple(): Boolean = _entries.size > 1
}
