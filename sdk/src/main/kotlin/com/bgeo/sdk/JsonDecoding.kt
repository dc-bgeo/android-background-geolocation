package com.bgeo.sdk

import org.json.JSONObject

/**
 * `org.json` decoding helpers that avoid two footguns:
 *  - `JSONObject` returns the singleton [JSONObject.NULL] (not Kotlin `null`)
 *    for a JSON `null`, so a plain `opt*` call does not detect it.
 *  - `optDouble` returns `NaN` for a missing key rather than throwing, and
 *    since `org.json` stores whole numbers as `Int`, it must still be able to
 *    read those.
 *
 * Every nullable read in [Models.kt] goes through these rather than the raw
 * `opt*`/`is*` methods, so a malformed payload degrades to `null` instead of
 * throwing.
 */

internal fun JSONObject.doubleOrNull(key: String): Double? =
    if (isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }

internal fun JSONObject.intOrNull(key: String): Int? = if (isNull(key)) null else optInt(key)
internal fun JSONObject.boolOrNull(key: String): Boolean? = if (isNull(key)) null else optBoolean(key)
internal fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
internal fun JSONObject.objectOrNull(key: String): JSONObject? = if (isNull(key)) null else optJSONObject(key)
