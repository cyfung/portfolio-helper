package com.portfoliohelper.web

import kotlin.test.Test
import kotlin.test.assertEquals

class HttpRequestPolicyTest {
    @Test
    fun `http redirects to https unless explicitly enabled`() {
        assertEquals(
            HttpRequestPolicy.REDIRECT_TO_HTTPS,
            httpRequestPolicy(httpMode = false, requestPort = 9090, httpPort = 9090, path = "/"),
        )
    }

    @Test
    fun `explicit http mode allows ordinary routes`() {
        assertEquals(
            HttpRequestPolicy.ALLOW,
            httpRequestPolicy(httpMode = true, requestPort = 9090, httpPort = 9090, path = "/"),
        )
    }

    @Test
    fun `sync routes still require https in http mode`() {
        assertEquals(
            HttpRequestPolicy.REQUIRE_HTTPS,
            httpRequestPolicy(httpMode = true, requestPort = 9090, httpPort = 9090, path = "/api/sync/pair"),
        )
        assertEquals(
            HttpRequestPolicy.ALLOW,
            httpRequestPolicy(httpMode = true, requestPort = 9093, httpPort = 9090, path = "/api/sync/pair"),
        )
    }
}
