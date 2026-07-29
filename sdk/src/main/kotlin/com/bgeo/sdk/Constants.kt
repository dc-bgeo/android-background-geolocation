package com.bgeo.sdk

/**
 * Cross-SDK numeric/string constants. Values MUST match
 * `react-native/src/constants.ts` exactly — apps compare
 * `onProviderChange.status` against `AuthorizationStatus.ALWAYS.value`, etc.
 */

enum class DesiredAccuracy(val value: Int) {
    NAVIGATION(-2),
    HIGH(-1),
    MEDIUM(10),
    LOW(100),
    VERY_LOW(1000),
    LOWEST(3000),
}

enum class LogLevel(val value: Int) {
    OFF(0),
    ERROR(1),
    WARNING(2),
    INFO(3),
    DEBUG(4),
    VERBOSE(5),
}

enum class AuthorizationStatus(val value: Int) {
    NOT_DETERMINED(0),
    RESTRICTED(1),
    DENIED(2),
    ALWAYS(3),
    WHEN_IN_USE(4),
    ;

    companion object {
        // No "unknown" case exists on this enum, so an unrecognised value
        // (which the engine should never emit) falls back to NOT_DETERMINED
        // rather than throwing.
        fun from(value: Int): AuthorizationStatus = values().firstOrNull { it.value == value } ?: NOT_DETERMINED
    }
}

enum class AccuracyAuthorization(val value: Int) {
    FULL(0),
    REDUCED(1),
}

enum class ActivityType(val wire: String) {
    STILL("still"),
    ON_FOOT("on_foot"),
    WALKING("walking"),
    RUNNING("running"),
    ON_BICYCLE("on_bicycle"),
    IN_VEHICLE("in_vehicle"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun from(wire: String?): ActivityType = values().firstOrNull { it.wire == wire } ?: UNKNOWN
    }
}
