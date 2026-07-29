package com.bgeo.sdk

import org.json.JSONObject

/**
 * `org.json` decoding helpers that avoid three footguns:
 *  - `JSONObject` returns the singleton [JSONObject.NULL] (not Kotlin `null`)
 *    for a JSON `null`, so a plain `opt*` call does not detect it.
 *  - `optDouble` returns `NaN` for a missing key rather than throwing, and
 *    since `org.json` stores whole numbers as `Int`, it must still be able to
 *    read those.
 *  - `optInt`/`optBoolean` *coerce* a present-but-wrong-typed value down to
 *    `0`/`false` instead of signalling a mistype (e.g. `optInt` on a
 *    non-numeric String silently returns `0`). That is worse than a dropped
 *    record: a required field arriving as garbage would decode successfully
 *    with a fabricated value instead of failing the record. `intOrNull` and
 *    `boolOrNull` therefore read the raw value via [JSONObject.opt] and only
 *    accept it when it is genuinely a `Number`/`Boolean` - anything else
 *    (String, JSONObject, JSONArray, ...) is treated the same as absent.
 *    `doubleOrNull` doesn't need the same treatment: `optDouble` already
 *    fails a non-numeric String to `NaN`, which the `isNaN()` check below
 *    already turns into `null` - see `JsonDecodingTest` for the case that
 *    proves all three now behave the same way on a mistyped value.
 *
 * Every nullable read in [Models.kt] goes through these rather than the raw
 * `opt*`/`is*` methods, so a malformed payload degrades to `null` instead of
 * throwing, and a mistyped-but-present value degrades to `null` rather than
 * a silently wrong default.
 */

internal fun JSONObject.doubleOrNull(key: String): Double? =
    if (isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }

internal fun JSONObject.intOrNull(key: String): Int? =
    if (isNull(key)) null else (opt(key) as? Number)?.toInt()

internal fun JSONObject.boolOrNull(key: String): Boolean? =
    if (isNull(key)) null else opt(key) as? Boolean

internal fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
internal fun JSONObject.objectOrNull(key: String): JSONObject? = if (isNull(key)) null else optJSONObject(key)

/**
 * String value for a REQUIRED text field where an empty string is a real,
 * meaningful value the engine legitimately emits - not a sign the field is
 * absent. Unlike [stringOrNull] (which treats `""` the same as a missing
 * key, so a decoder using it as `?: return null` drops the WHOLE record),
 * this never fails: a missing key or a non-`String` value both fall back to
 * `""` rather than aborting the decode.
 *
 * Exists for `HttpEvent.responseText`: the engine emits `""` for both a
 * body-less response (e.g. 204 No Content) and a message-less network
 * error, and either case is an ordinary, successful `http` event that must
 * still reach subscribers - not a malformed payload to drop.
 */
internal fun JSONObject.stringOrEmpty(key: String): String = (opt(key) as? String) ?: ""

/**
 * Raw value for a key whose shape isn't known ahead of time (e.g. `LogEntry.
 * data`, which the engine/`LiveEngine` may hand back as either a parsed
 * `JSONObject` or a plain `String` — see `Engine.kt`'s `logRowToJson`).
 * Guards the same `JSONObject.NULL`-vs-Kotlin-`null` trap as the other
 * helpers: absent key or explicit JSON `null` both read as `null`, not the
 * `JSONObject.NULL` singleton.
 */
internal fun JSONObject.anyOrNull(key: String): Any? = if (isNull(key)) null else opt(key)
