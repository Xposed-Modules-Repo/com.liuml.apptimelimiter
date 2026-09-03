package com.liuml.apptimelimiter.migration

import org.json.JSONArray
import org.json.JSONObject

internal object PreferenceMapCodec {
    fun encode(values: Map<String, *>): JSONObject = JSONObject().apply {
        values.toSortedMap().forEach { (key, value) ->
            val encoded = JSONObject()
            when (value) {
                is Boolean -> encoded.put("type", "boolean").put("value", value)
                is Int -> encoded.put("type", "int").put("value", value)
                is Long -> encoded.put("type", "long").put("value", value)
                is Float -> encoded.put("type", "float").put("value", value.toDouble())
                is String -> encoded.put("type", "string").put("value", value)
                is Set<*> -> encoded.put("type", "string_set").put(
                    "value",
                    JSONArray(value.filterIsInstance<String>().sorted()),
                )
                else -> return@forEach
            }
            put(key, encoded)
        }
    }

    fun decode(value: JSONObject): Map<String, *> = buildMap {
        value.keys().forEach { key ->
            require(key.length <= MAX_KEY_LENGTH) { "migration_key_too_long" }
            val encoded = value.getJSONObject(key)
            val decoded: Any = when (encoded.getString("type")) {
                "boolean" -> encoded.getBoolean("value")
                "int" -> encoded.getInt("value")
                "long" -> encoded.getLong("value")
                "float" -> encoded.getDouble("value").toFloat()
                "string" -> encoded.getString("value").also {
                    require(it.length <= MAX_STRING_LENGTH) { "migration_value_too_long" }
                }
                "string_set" -> encoded.getJSONArray("value").let { array ->
                    require(array.length() <= MAX_SET_SIZE) { "migration_set_too_large" }
                    (0 until array.length()).map { index ->
                        array.getString(index).also {
                            require(it.length <= MAX_STRING_LENGTH) { "migration_value_too_long" }
                        }
                    }.toSet()
                }
                else -> error("unsupported_migration_type")
            }
            put(key, decoded)
        }
    }

    private const val MAX_KEY_LENGTH = 300
    private const val MAX_STRING_LENGTH = 64 * 1024
    private const val MAX_SET_SIZE = 2_000
}
