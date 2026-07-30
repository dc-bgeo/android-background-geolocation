package dev.bgeo.example.screens

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

    // 1. Defect 1: a combined snapshot (or a decision that ties both booleans
    // together) rebuilds the geofence layer on every incoming fix too,
    // dismissing an open callout before the user can tap through to edit.
    @Test
    fun `a new location fix rebuilds only the track`() {
        val previousTrack = TrackSnapshot(pointCount = 10, lastPointKey = "p9", trackVisible = true)
        val currentTrack = TrackSnapshot(pointCount = 11, lastPointKey = "p10", trackVisible = true)
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
        val track = TrackSnapshot(pointCount = 5, lastPointKey = "p5", trackVisible = true)
        val previousFences = GeofenceSnapshot(listOf(fenceKey(radius = 100.0)), geofencesVisible = true)
        val currentFences = GeofenceSnapshot(listOf(fenceKey(radius = 150.0)), geofencesVisible = true)

        val decision = MapRebuild.decide(track, track, previousFences, currentFences)

        assertTrue(decision.rebuildGeofences) // fails if the key omits radius
        assertFalse(decision.rebuildTrack)
    }

    // 3. Same class of gap as #2, for position instead of radius.
    @Test
    fun `a geofence that moved rebuilds the geofences`() {
        val track = TrackSnapshot(pointCount = 5, lastPointKey = "p5", trackVisible = true)
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
        val track = TrackSnapshot(pointCount = 5, lastPointKey = "p5", trackVisible = true)
        val previousFences = GeofenceSnapshot(listOf(fenceKey(color = "#f97316")), geofencesVisible = true)
        val currentFences = GeofenceSnapshot(listOf(fenceKey(color = "#22c55e")), geofencesVisible = true)

        val decision = MapRebuild.decide(track, track, previousFences, currentFences)

        assertTrue(decision.rebuildGeofences) // fails if the key omits colour
    }

    // 5. Track and geofences changing in the same tick must be decided
    // independently, not OR'd into a single shared flag.
    @Test
    fun `track and geofences changing together both rebuild independently`() {
        val previousTrack = TrackSnapshot(pointCount = 5, lastPointKey = "p5", trackVisible = true)
        val currentTrack = TrackSnapshot(pointCount = 6, lastPointKey = "p6", trackVisible = true)
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
        val track = TrackSnapshot(pointCount = 5, lastPointKey = "p5", trackVisible = true)
        val fences = GeofenceSnapshot(listOf(fenceKey()), geofencesVisible = true)

        val decision = MapRebuild.decide(track, track, fences, fences)

        assertEquals(RebuildDecision(rebuildTrack = false, rebuildGeofences = false), decision)
    }

    // Bonus (not in the brief's required six): the very first render has no
    // previous snapshot at all — both halves must still rebuild rather than
    // NPE or silently rendering nothing.
    @Test
    fun `a null previous snapshot rebuilds both on first render`() {
        val track = TrackSnapshot(pointCount = 0, lastPointKey = null, trackVisible = true)
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
        val track = TrackSnapshot(pointCount = 5, lastPointKey = "p5", trackVisible = true)
        val previousFences = GeofenceSnapshot(listOf(fenceKey()), geofencesVisible = true)
        val currentFences = GeofenceSnapshot(listOf(fenceKey()), geofencesVisible = false)

        val decision = MapRebuild.decide(track, track, previousFences, currentFences)

        assertTrue(decision.rebuildGeofences)
        assertFalse(decision.rebuildTrack)
    }
}
