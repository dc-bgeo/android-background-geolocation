package dev.bgeo.example.screens

/**
 * Pure change-detection for the Map screen's osmdroid overlays — no Android
 * imports, so it stays unit-testable under this module's
 * `unitTests.isReturnDefaultValues` harness (see `MapScreen.kt`'s header for
 * why the rendering code that consumes this must live in a separate file).
 *
 * This is a Kotlin port of the fix (not the original bug) documented in
 * `ios/Example/Sources/Screens/MapScreen.swift`'s `TrackSnapshot`/
 * `GeofenceSnapshot`/`MapRebuild`: that console originally keyed ALL map
 * overlays on one combined snapshot that included the last track point, so
 * every incoming location fix — roughly one per second while driving — tore
 * down and rebuilt every annotation, including the geofence pin the user had
 * just tapped, dismissing its callout before they could reach the edit form.
 * The fix split the decision into two independent snapshots so a location
 * fix only ever invalidates the track; a SECOND defect then shipped in that
 * same fix because the geofence key covered only identifier + colour — the
 * edit form disables the identifier field, so an edit can only change
 * radius/notify flags/loitering delay, none of which touched that key, and
 * an edited radius never repainted. [geofenceKey] is written to include
 * geometry for exactly that reason; see `MapRebuildTest` for the six cases
 * this history requires.
 *
 * `decide` alone does not prove the fix, though: it is two equality
 * comparisons, and a renderer that ignores [RebuildDecision] entirely (or
 * rebuilds by sweeping every overlay off the map regardless of which half
 * changed) would still pass every test here. `MapScreen.kt`'s overlay
 * controller keeps track overlays and geofence overlays in two separate
 * mutable collections and removes only the collection [RebuildDecision]
 * says changed — see that file's header for how that was verified, since it
 * has no equivalent unit-test seam (it touches osmdroid's `MapView`/
 * `Overlay`, which this module's harness stubs).
 */

/** Everything that legitimately changes on (almost) every incoming location fix. */
data class TrackSnapshot(
    val pointCount: Int,
    val lastPointKey: String?,
    val trackVisible: Boolean,
)

/**
 * Change-detection snapshot for the geofence circles/pins. One key per
 * geofence, built by [MapRebuild.geofenceKey] — identifier, geometry AND
 * display colour, deliberately NOT identifier alone (see this file's header
 * for why an identifier-only key is the second defect, not a fix).
 */
data class GeofenceSnapshot(
    val geofenceKeys: List<String>,
    val geofencesVisible: Boolean,
)

/** Which half of the map actually needs to be torn down and redrawn. */
data class RebuildDecision(
    val rebuildTrack: Boolean,
    val rebuildGeofences: Boolean,
)

object MapRebuild {

    /** Plain structural-equality comparison — the track and geofence halves are decided completely independently. */
    fun decide(
        previousTrack: TrackSnapshot?,
        currentTrack: TrackSnapshot,
        previousGeofences: GeofenceSnapshot?,
        currentGeofences: GeofenceSnapshot,
    ): RebuildDecision = RebuildDecision(
        rebuildTrack = currentTrack != previousTrack,
        rebuildGeofences = currentGeofences != previousGeofences,
    )

    /**
     * The one geofence key builder `MapScreen.kt` and `MapRebuildTest` both
     * call, so the two can never drift apart. MUST include latitude,
     * longitude and radius, not just [identifier] and [colorHex] — that
     * narrower key is the exact regression this file's header describes.
     */
    fun geofenceKey(identifier: String, latitude: Double, longitude: Double, radius: Double, colorHex: String): String =
        "$identifier|$latitude|$longitude|$radius|$colorHex"
}
