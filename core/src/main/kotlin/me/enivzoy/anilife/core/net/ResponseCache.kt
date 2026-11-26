package me.enivzoy.anilife.core.net

import kotlinx.serialization.json.JsonObject

data class CachedResponse(val data: JsonObject, val savedAtMillis: Long)

interface ResponseCache {
    fun get(key: String): CachedResponse?
    fun put(key: String, data: JsonObject)
}