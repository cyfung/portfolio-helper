package com.portfoliohelper.util

import kotlinx.serialization.json.Json

/** Shared Json instance for all server-side encode/decode operations. */
val appJson = Json {
    ignoreUnknownKeys = true
    prettyPrint = false
}
