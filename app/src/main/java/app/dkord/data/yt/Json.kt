package app.dkord.data.yt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** Navigate by object keys (String) and array indices (Int). Null-safe: YouTube changes shapes often. */
fun JsonElement?.at(vararg keys: Any): JsonElement? {
    var cur: JsonElement? = this
    for (k in keys) {
        cur = when (k) {
            is String -> (cur as? JsonObject)?.get(k)
            is Int -> (cur as? JsonArray)?.getOrNull(k)
            else -> null
        } ?: return null
    }
    return cur
}

fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
fun JsonElement?.list(): List<JsonElement> = (this as? JsonArray) ?: emptyList()
fun JsonElement?.entries(): Map<String, JsonElement> = (this as? JsonObject) ?: emptyMap()

/** Depth-first search for the first value stored under [key]. */
fun JsonElement?.find(key: String): JsonElement? = when (this) {
    is JsonObject -> this[key] ?: values.firstNotNullOfOrNull { it.find(key) }
    is JsonArray -> firstNotNullOfOrNull { it.find(key) }
    else -> null
}

fun JsonElement?.runs(): List<JsonElement> = this.at("runs").list()
fun JsonElement?.runsText(): String = runs().joinToString("") { it.at("text").str().orEmpty() }

fun JsonElement?.lastThumbnail(): String? =
    find("thumbnails").list().lastOrNull().at("url").str()?.let(::fixUrl)

private fun fixUrl(u: String) = if (u.startsWith("//")) "https:$u" else u
