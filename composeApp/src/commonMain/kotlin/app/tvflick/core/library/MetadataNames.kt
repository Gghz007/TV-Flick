package app.tvflick.core.library

import kotlinx.serialization.json.*

internal fun encodeNames(values: List<String>): String = JsonArray(values.distinct().map(::JsonPrimitive)).toString()
internal fun decodeNames(value: String?): List<String> = value?.let {
    runCatching { Json.parseToJsonElement(it).jsonArray.map { item -> item.jsonPrimitive.content } }.getOrDefault(emptyList())
}.orEmpty()
