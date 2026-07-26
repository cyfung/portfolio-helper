package com.portfoliohelper

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApplicationArgsTest {
    @Test
    fun `http mode is disabled by default`() {
        assertFalse(httpModeEnabled(emptyArray()))
    }

    @Test
    fun `http mode requires explicit http parameter`() {
        assertTrue(httpModeEnabled(arrayOf("--http")))
        assertFalse(httpModeEnabled(arrayOf("--https")))
    }
}
