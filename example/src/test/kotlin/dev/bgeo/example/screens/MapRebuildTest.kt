package dev.bgeo.example.screens

import com.bgeo.sdk.Geofence
import dev.bgeo.example.Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The six cases the task brief calls "load-bearing" — each one maps directly
 * onto one of the two defects the iOS console shipped in this exact screen
 * (see `MapRebuild.kt`'s header for the full history). Every test below is
 * annotated with what it would catch against a naive implementation.
 */
class MapRebuildTest {

    private fun fenceKey(id: String = "home", lat: Double = 52.52, lng: Double = 13.405, radius: Double = 100.0, color: String = "#f97316") =
        MapRebuild.geofenceKey(id, lat, lng, radius, color)

    private fun point(lat: Double = 1.0, lng: Double = 2.0, ts: String = "2026-07-29T00:00:00Z") =
        Point(latitude = lat, longitude = lng, timestamp = ts)

    private fun fence(id: String = "home", lat: Double = 52.52, lng: Double = 13.405, radius: Double = 100.0) =
        Geofence(identifier = id, radius = radius, latitude = lat, longitude = lng, notifyOnEntry = null, notifyOnExit = null, notifyOnDwell = null, loiteringDelay = null, extras = null)

    // 1. Defect 1: a combined snapshot (or a decision that ties both booleans
    // together) rebuilds the geofence layer on every incoming fix too,
    // dismissing an open callout before the user can tap through to edit.
    @Test
    fun `a new location fix rebuilds only the track`() {
        val previousTrack = TrackSnapshot(pointCount = 10, lastPointKey = "p9", showMarkers = true, showPolylines = true, isMoving = false)
        val currentTrack = TrackSnapshot(pointCount = 11, lastPointKey = "p10", showMarkers = true, showPolylines = true, isMoving = false)
        val fences = GeofenceSnapshot(listOf(fenceKey()), geofencesVisible = true)

        val decision = MapRebuild.decide(previousTrack, currentTrack, fences, fences)

        assertTrue(decision.rebuildTrack)
        assertFalse(decision.rebuildGeofences) // fails against any "one combined flag" implementation
    }

    // 2. Defect 2: a geofence key of identifier+colour alone is unchanged
    // when only the radius moves (the edit form disables the identifier
    // field, so this is the ONLY kind of change a real edit ever makes to a
    // fence that keeps its position and hasn't just fired ENTER/EXIT).
    @Test
    fun `a geofence radius change rebuilds the geofences`() {
        val track = TrackSnapshot(pointCount = 5, lastPointKey = "p5", showMarkers = true, showPolylines = true, isMoving = false)
        val previousFences = GeofenceSnapshot(listOf(fenceKey(radius = 100.0)), geofencesVisible = true)
        val currentFences = GeofenceSnapshot(listOf(fenceKey(radius = 150.0)), geofencesVisible = true)

        val decision = MapRebuild.decide(track, track, previousFences, currentFences)

        assertTrue(decision.rebuildGeofences) // fails if the key omits radius
        assertFalse(decision.rebuildTrack)
    }

    // 3. Same class of gap as #2, for position instead of radius.
    @Test
    fun `a geofence that moved rebuilds the geofences`() {
        val track = TrackSnapshot(pointCount = 5, lastPointKey = "p5", showMarkers = true, showPolylines = true, isMoving = false)
        val previousFences = GeofenceSnapshot(listOf(fenceKey(lat = 52.52, lng = 13.405)), geofencesVisible = true)
        val currentFences = GeofenceSnapshot(listOf(fenceKey(lat = 52.60, lng = 13.405)), geofencesVisible = true)

        val decision = MapRebuild.decide(track, track, previousFences, currentFences)

        assertTrue(decision.rebuildGeofences) // fails if the key omits lat/lng
    }

    // 4. The one field a naive identifier-only key (the ORIGINAL, pre-defect-2
    // shape) would also miss: an ENTER/EXIT/DWELL transition repaints the
    // fence a different colour with geometry and identifier both unchanged.
    @Test
    fun `a geofence colour change rebuilds the geofences`() {
        val track = TrackSnapshot(pointCount = 5, lastPointKey = "p5", showMarkers = true, showPolylines = true, isMoving = false)
        val previousFences = GeofenceSnapshot(listOf(fenceKey(color = "#f97316")), geofencesVisible = true)
        val currentFences = GeofenceSnapshot(listOf(fenceKey(color = "#22c55e")), geofencesVisible = true)

        val decision = MapRebuild.decide(track, track, previousFences, currentFences)

        assertTrue(decision.rebuildGeofences) // fails if the key omits colour
    }

    // 5. Track and geofences changing in the same tick must be decided
    // independently, not OR'd into a single shared flag.
    @Test
    fun `track and geofences changing together both rebuild independently`() {
        val previousTrack = TrackSnapshot(pointCount = 5, lastPointKey = "p5", showMarkers = true, showPolylines = true, isMoving = false)
        val currentTrack = TrackSnapshot(pointCount = 6, lastPointKey = "p6", showMarkers = true, showPolylines = true, isMoving = false)
        val previousFences = GeofenceSnapshot(listOf(fenceKey(radius = 100.0)), geofencesVisible = true)
        val currentFences = GeofenceSnapshot(listOf(fenceKey(radius = 150.0)), geofencesVisible = true)

        val decision = MapRebuild.decide(previousTrack, currentTrack, previousFences, currentFences)

        assertTrue(decision.rebuildTrack)
        assertTrue(decision.rebuildGeofences)
    }

    // 6. The base case: nothing changed, nothing should rebuild — guards
    // against an "always rebuild" implementation that would otherwise pass
    // every test above trivially.
    @Test
    fun `nothing changed rebuilds nothing`() {
        val track = TrackSnapshot(pointCount = 5, lastPointKey = "p5", showMarkers = true, showPolylines = true, isMoving = false)
        val fences = GeofenceSnapshot(listOf(fenceKey()), geofencesVisible = true)

        val decision = MapRebuild.decide(track, track, fences, fences)

        assertEquals(RebuildDecision(rebuildTrack = false, rebuildGeofences = false), decision)
    }

    // Bonus (not in the brief's required six): the very first render has no
    // previous snapshot at all — both halves must still rebuild rather than
    // NPE or silently rendering nothing.
    @Test
    fun `a null previous snapshot rebuilds both on first render`() {
        val track = TrackSnapshot(pointCount = 0, lastPointKey = null, showMarkers = true, showPolylines = true, isMoving = false)
        val fences = GeofenceSnapshot(emptyList(), geofencesVisible = true)

        val decision = MapRebuild.decide(null, track, null, fences)

        assertTrue(decision.rebuildTrack)
        assertTrue(decision.rebuildGeofences)
    }

    // Bonus: toggling a layer's visibility off changes nothing about the
    // points/keys, but must still trigger a rebuild (so the overlays
    // actually disappear) — a key/list-only comparison that dropped the
    // *Visible fields from the data class would miss this.
    @Test
    fun `toggling geofence layer visibility rebuilds geofences`() {
        val track = TrackSnapshot(pointCount = 5, lastPointKey = "p5", showMarkers = true, showPolylines = true, isMoving = false)
        val previousFences = GeofenceSnapshot(listOf(fenceKey()), geofencesVisible = true)
        val currentFences = GeofenceSnapshot(listOf(fenceKey()), geofencesVisible = false)

        val decision = MapRebuild.decide(track, track, previousFences, currentFences)

        assertTrue(decision.rebuildGeofences)
        assertFalse(decision.rebuildTrack)
    }

    // ---- Fix round 1 ----
    //
    // F1: `TrackSnapshot` used to collapse `showMarkers`/`showPolylines` into
    // one `trackVisible = showMarkers || showPolylines` bit, so flipping
    // either toggle off while the other stayed on left `trackVisible`
    // unchanged and the layer never disappeared. F2: `isMoving` (the
    // last-position dot's colour) wasn't in the key at all, so a
    // `motionchange` with no new fix produced no rebuild. Both builder
    // functions below are new — extracted from `MapOverlayController.apply()`
    // per the review's diagnosis that inline snapshot construction in an
    // untestable Compose file is exactly the seam these bugs lived in.

    // F1. Verified to fail: mutated `buildTrackSnapshot` to fold
    // `showMarkers`/`showPolylines` into one derived `combinedVisible` bit
    // before storing (functionally the pre-fix `trackVisible = showMarkers ||
    // showPolylines`, kept type-compatible with the current `TrackSnapshot`
    // shape so only this one function's body changed) — with both layers on
    // beforehand, turning markers off alone leaves `combinedVisible` at
    // `true` both times, so `decide` sees no change. This test and the next
    // one both failed (`./gradlew :example:testDebugUnitTest --tests
    // '*MapRebuildTest*'`); reverted back after confirming, diffed clean.
    @Test
    fun `toggling markers off rebuilds the track even with polylines still on`() {
        val points = listOf(point())
        val previous = MapRebuild.buildTrackSnapshot(points, showMarkers = true, showPolylines = true, isMoving = false)
        val current = MapRebuild.buildTrackSnapshot(points, showMarkers = false, showPolylines = true, isMoving = false)
        val fences = GeofenceSnapshot(listOf(fenceKey()), geofencesVisible = true)

        val decision = MapRebuild.decide(previous, current, fences, fences)

        assertTrue(decision.rebuildTrack) // fails against a combined `trackVisible` bit
    }

    // F1, other direction: polylines off, markers still on.
    @Test
    fun `toggling polylines off rebuilds the track even with markers still on`() {
        val points = listOf(point())
        val previous = MapRebuild.buildTrackSnapshot(points, showMarkers = true, showPolylines = true, isMoving = false)
        val current = MapRebuild.buildTrackSnapshot(points, showMarkers = true, showPolylines = false, isMoving = false)
        val fences = GeofenceSnapshot(listOf(fenceKey()), geofencesVisible = true)

        val decision = MapRebuild.decide(previous, current, fences, fences)

        assertTrue(decision.rebuildTrack) // fails against a combined `trackVisible` bit
    }

    // F2. Verified to fail: mutated `buildTrackSnapshot` to hardcode
    // `isMoving = false` regardless of the argument (functionally the
    // pre-fix omission) — with no new point and no other field changing,
    // `decide` saw two structurally identical snapshots and reported no
    // rebuild. Reverted back after confirming, diffed clean.
    @Test
    fun `isMoving changing alone rebuilds the track`() {
        val points = listOf(point())
        val previous = MapRebuild.buildTrackSnapshot(points, showMarkers = true, showPolylines = true, isMoving = true)
        val current = MapRebuild.buildTrackSnapshot(points, showMarkers = true, showPolylines = true, isMoving = false)
        val fences = GeofenceSnapshot(listOf(fenceKey()), geofencesVisible = true)

        val decision = MapRebuild.decide(previous, current, fences, fences)

        assertTrue(decision.rebuildTrack) // fails if `isMoving` isn't part of the key
    }

    // Coverage for the other new builder, `buildGeofenceSnapshot`: confirms
    // it delegates to `geofenceKey` per fence (not a defect-catching test —
    // `geofenceKey`'s own correctness is already covered by tests 2-4 above —
    // but the builder function itself is new code with no prior test).
    @Test
    fun `buildGeofenceSnapshot builds one key per fence via geofenceKey`() {
        val snapshot = MapRebuild.buildGeofenceSnapshot(listOf(fence()), showGeofences = true) { "#f97316" }

        assertEquals(listOf(fenceKey(color = "#f97316")), snapshot.geofenceKeys)
        assertTrue(snapshot.geofencesVisible)
    }
}

/**
 * The paged window and the track-dot diff — the two pieces of pure logic the
 * renderer was missing.
 *
 * `MapPaging` is a port of the same math RN/iOS/Flutter run (`PAGE_SIZE`,
 * `effPage`, `windowStart/End`); `diffTrackDots` exists because the renderer
 * used to remove and rebuild every dot on every accepted fix.
 */
class MapPagingTest {

    private fun point(uuid: String? = null, ts: String = "2026-07-29T00:00:00Z") =
        Point(uuid = uuid, latitude = 1.0, longitude = 2.0, timestamp = ts)

    // ---- MapPaging.window ----

    @Test
    fun `a track shorter than one page is a single page showing everything`() {
        val window = MapPaging.window(totalCount = 5, page = 0, pageSize = 10)

        assertEquals(MapWindow(effPage = 0, pageCount = 1, windowStart = 0, windowEnd = 5, onNewestPage = true), window)
    }

    @Test
    fun `page 0 is the NEWEST points, not the oldest`() {
        // 2500 points / 1000 -> 3 pages. Page 0 must be the tail (the live
        // end); an implementation that counted from the start would return
        // 0..1000 here and the map would silently show yesterday's track.
        val page0 = MapPaging.window(totalCount = 2500, page = 0, pageSize = 1000)
        assertEquals(MapWindow(0, 3, 1500, 2500, true), page0)

        val page1 = MapPaging.window(totalCount = 2500, page = 1, pageSize = 1000)
        assertEquals(MapWindow(1, 3, 500, 1500, false), page1)

        val page2 = MapPaging.window(totalCount = 2500, page = 2, pageSize = 1000)
        assertEquals(MapWindow(2, 3, 0, 500, false), page2)
    }

    @Test
    fun `a page past the oldest one clamps instead of showing nothing`() {
        // The buffer shrank (or a range returned fewer points) while the user
        // was on an old page: clamp, never return an empty window.
        val window = MapPaging.window(totalCount = 1500, page = 9, pageSize = 1000)

        assertEquals(1, window.effPage)
        assertEquals(0, window.windowStart)
        assertEquals(500, window.windowEnd)
    }

    @Test
    fun `an empty track is still one page`() {
        assertEquals(MapWindow(0, 1, 0, 0, true), MapPaging.window(totalCount = 0, page = 0, pageSize = 1000))
    }

    @Test
    fun `an exact multiple of the page size does not produce a trailing empty page`() {
        val window = MapPaging.window(totalCount = 2000, page = 0, pageSize = 1000)

        assertEquals(2, window.pageCount)
        assertEquals(1000, window.windowStart)
        assertEquals(2000, window.windowEnd)
    }

    @Test
    fun `a negative page clamps to the live page`() {
        assertEquals(0, MapPaging.window(totalCount = 50, page = -3, pageSize = 10).effPage)
    }

    // ---- MapRebuild.trackDotKeys / diffTrackDots ----

    @Test
    fun `a window that slides by one point costs one add and one remove`() {
        // This is the whole point of the diff: 1000 dots on screen, one new
        // fix, one marker created — not 1000 destroyed and 1000 rebuilt.
        val before = MapRebuild.trackDotKeys(listOf(point("a"), point("b"), point("c")))
        val after = MapRebuild.trackDotKeys(listOf(point("b"), point("c"), point("d")))

        val diff = MapRebuild.diffTrackDots(before, after)

        assertEquals(listOf("d"), diff.added)
        assertEquals(listOf("a"), diff.removed)
    }

    @Test
    fun `nothing changes when the drawn points are unchanged`() {
        val keys = MapRebuild.trackDotKeys(listOf(point("a"), point("b")))

        val diff = MapRebuild.diffTrackDots(keys, keys)

        assertTrue(diff.added.isEmpty())
        assertTrue(diff.removed.isEmpty())
    }

    @Test
    fun `points sharing a key still get one dot each`() {
        // `uuid` is optional and the `timestamp` fallback can repeat; collapsing
        // two points onto one key would leave a permanent hole in the track.
        val keys = MapRebuild.trackDotKeys(listOf(point(ts = "same"), point(ts = "same"), point(ts = "same")))

        assertEquals(3, keys.size)
        assertEquals(3, keys.toSet().size)
        assertEquals("same", keys.first())
    }

    @Test
    fun `hiding the markers layer removes every dot`() {
        val keys = MapRebuild.trackDotKeys(listOf(point("a"), point("b")))

        val diff = MapRebuild.diffTrackDots(keys, emptyList())

        assertEquals(listOf("a", "b"), diff.removed)
        assertTrue(diff.added.isEmpty())
    }
}
