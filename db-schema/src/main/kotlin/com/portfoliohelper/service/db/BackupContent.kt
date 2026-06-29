package com.portfoliohelper.service.db

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest

object BackupContent {
    private val json = Json { ignoreUnknownKeys = true }

    fun canonicalJson(data: String): String =
        try {
            normalizeRoot(json.parseToJsonElement(data)).toString()
        } catch (_: Exception) {
            data.trim()
        }

    fun contentHash(data: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(canonicalJson(data).toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun normalizeRoot(root: JsonElement): JsonElement {
        if (root !is JsonObject) return normalize(root)
        val normalized = root.entries.associate { (key, value) ->
            key to if (key == "cash" && value is JsonArray) {
                JsonArray(value.map { cashEntry ->
                    if (cashEntry is JsonObject) normalizeObject(cashEntry, dropSnapshotUsd = true) else normalize(cashEntry)
                })
            } else {
                normalize(value)
            }
        }.toSortedMap()
        return JsonObject(normalized)
    }

    private fun normalize(value: JsonElement): JsonElement =
        when (value) {
            is JsonObject -> normalizeObject(value, dropSnapshotUsd = false)
            is JsonArray -> JsonArray(value.map { normalize(it) })
            else -> value
        }

    private fun normalizeObject(obj: JsonObject, dropSnapshotUsd: Boolean): JsonObject {
        val normalized = obj.entries
            .asSequence()
            .filterNot { dropSnapshotUsd && it.key == "snapshotUsd" }
            .associate { (key, value) -> key to normalize(value) }
            .toSortedMap()
        return JsonObject(normalized)
    }
}
