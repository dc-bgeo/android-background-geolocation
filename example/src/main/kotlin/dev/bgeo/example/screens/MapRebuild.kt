package dev.bgeo.example.screens

import com.bgeo.sdk.Geofence
import dev.bgeo.example.Point

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

/**
 * Everything that legitimately changes on (almost) every incoming location
 * fix, plus every rendered input that does NOT show up in [pointCount] or
 * [lastPointKey] but still needs to force a rebuild on its own.
 *
 * Fix round 1 found two: [showMarkers]/[showPolylines] used to be collapsed
 * into one `trackVisible = showMarkers || showPolylines` bit, so toggling
 * either layer off while the other stayed on left `trackVisible` unchanged
 * and the layer never disappeared (the toggle only looked like it worked
 * once an unrelated location fix forced a rebuild anyway). And [isMoving]
 * — which colours the last-position dot — wasn't in the key at all, so a
 * `motionchange` with no new accepted fix (e.g. the device just parked)
 * produced no rebuild and the dot stayed the wrong colour for the entire
 * stationary period. Both are now their own fields so `decide`'s plain
 * equality comparison catches them independently.
 */
data class TrackSnapshot(
    val pointCount: Int,
    val lastPointKey: String?,
    val showMarkers: Boolean,
    val showPolylines: Boolean,
    val isMoving: Boolean,
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

    /**
     * Builds [TrackSnapshot] from the screen's live inputs. Fix round 1:
     * pulled out of `MapOverlayController.apply()` (a Compose/osmdroid file
     * with no unit-test seam) precisely because that inline construction is
     * where the `trackVisible` bug lived — a class of bug that only shows up
     * again if the key-construction logic is testable in isolation. See
     * `MapRebuildTest` for the regression coverage this enables.
     */
    fun buildTrackSnapshot(points: List<Point>, showMarkers: Boolean, showPolylines: Boolean, isMoving: Boolean): TrackSnapshot =
        TrackSnapshot(
            pointCount = points.size,
            lastPointKey = points.lastOrNull()?.let { it.uuid ?: it.timestamp },
            showMarkers = showMarkers,
            showPolylines = showPolylines,
            isMoving = isMoving,
        )

    /**
     * Builds [GeofenceSnapshot] from the live `[Geofence]` list — same
     * reasoning as [buildTrackSnapshot]. [colorHexFor] stays a caller-supplied
     * function rather than this file computing the transition colour itself:
     * that lookup needs to scan `Point` event history, and doing it here
     * would tempt an Android-colour dependency into a file that must stay
     * import-free from `android.*` to keep running under this module's
     * `unitTests.isReturnDefaultValues` harness.
     */
    fun buildGeofenceSnapshot(geofences: List<Geofence>, showGeofences: Boolean, colorHexFor: (Geofence) -> String): GeofenceSnapshot =
        GeofenceSnapshot(
            geofenceKeys = geofences.map { fence -> geofenceKey(fence.identifier, fence.latitude, fence.longitude, fence.radius, colorHexFor(fence)) },
            geofencesVisible = showGeofences,
        )
}
