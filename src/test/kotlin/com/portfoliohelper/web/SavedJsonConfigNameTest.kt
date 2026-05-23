package com.portfoliohelper.web

import kotlin.test.Test
import kotlin.test.assertEquals

class SavedJsonConfigNameTest {
    @Test
    fun `saving a plain duplicate appends the first available numeric suffix`() {
        assertEquals(
            "abc (3)",
            uniqueSavedJsonConfigName("abc", setOf("abc", "abc (2)")),
        )
    }

    @Test
    fun `saving a suffixed duplicate increments the suffix instead of nesting it`() {
        assertEquals(
            "abc (3)",
            uniqueSavedJsonConfigName("abc (2)", setOf("abc", "abc (2)")),
        )
    }

    @Test
    fun `saving a non-conflicting suffixed name keeps the requested name`() {
        assertEquals(
            "abc (2)",
            uniqueSavedJsonConfigName("abc (2)", setOf("abc")),
        )
    }

    @Test
    fun `saving a suffixed duplicate skips taken later suffixes`() {
        assertEquals(
            "abc (5)",
            uniqueSavedJsonConfigName("abc (2)", setOf("abc", "abc (2)", "abc (3)", "abc (4)")),
        )
    }
}
