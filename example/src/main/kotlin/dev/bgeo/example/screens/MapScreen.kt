package dev.bgeo.example.screens

// Map screen — current position, the accumulated track, geofence circles,
// start/stop + get-position controls and the coordinates sheet. A Kotlin
// port of `react-native/example/src/screens/MapScreen.tsx`;
// `ios/Example/Sources/Screens/MapScreen.swift` is the most recently reviewed
// console and the one whose defects in this exact screen were found and
// fixed — see `MapRebuild.kt`'s header for that history, which this file's
// overlay controller exists to not repeat.
//
// **osmdroid, not Google Maps** (see this repo's task brief / the Flutter
// console for why: no API key, so `./gradlew assembleDebug` never breaks for
// someone who just cloned the repo). `MapView` is wrapped once in an
// `AndroidView`; everything osmdroid-specific (tile cache, overlays,
// gestures) lives in [MapOverlayController] and this file, never in
// `MapRebuild.kt` — that file has no Android imports so it stays unit
// testable under this module's `unitTests.isReturnDefaultValues` harness,
// which stubs all of `android.jar` (osmdroid included).
//
// **Split-rebuild property, and how it's verified:** [MapOverlayController]
// keeps track overlays and geofence overlays in two separate mutable lists
// (`trackOverlays`/`geofenceOverlays`) and removes only the list
// `MapRebuild.decide` says changed — never `mapView.overlays.clear()` or any
// other sweep of the combined list. This is NOT unit-tested: it requires a
// real `MapView`/`Overlay`, both stubbed under this module's harness (no
// Robolectric, no instrumentation harness — see the module's build.gradle
// comment). Verified by inspection instead: every removal call below reads
// `mapView.overlays.removeAll(trackOverlays)` or `removeAll(geofenceOverlays)`
// — grep this file for `removeAll` and `overlays.clear` to confirm there is
// no wholesale sweep. `MapRebuildTest` proves the DECISION is correct
// (6 required cases + 2 more); it cannot prove the renderer obeys it, per
// the task brief's own warning that `decide` alone is not enough.
//
// **History/paging deferred:** RN windows the track to the newest 1000
// points (`PAGE_SIZE`) and offers a from/to history range, both backed by
// `history.ts`. That data source is Task 7's ("logs/history/uploader"), not
// this task's — this screen renders the SDK's live `appStore.points`
// directly (already capped at `AppStore.MAX_POINTS` = 2000) with no
// windowing or from/to picker. Flagged as a real, deliberate scope cut in
// the task report, not an oversight.

import android.content.Context
import android.graphics.Color
import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Satellite
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.bgeo.sdk.BackgroundGeolocation
import com.bgeo.sdk.CurrentPositionOptions
import com.bgeo.sdk.Geofence
import com.bgeo.sdk.PermissionRequester
import dev.bgeo.example.AppStore
import dev.bgeo.example.LogLevel
import dev.bgeo.example.LogLine
import dev.bgeo.example.Point
import dev.bgeo.example.components.CoordinatesSheet
import dev.bgeo.example.components.DotMarker
import dev.bgeo.example.components.sheetPeekHeight
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Task 6 seam: what the geofence form needs to open — a long-press (new
 * fence, no [identifier]) or a tap on an existing fence's pin (edit). Kotlin
 * port of RN's two `navigation.navigate('GeofenceForm', {...})` call shapes
 * (`MapScreen.tsx:194-199` / `:238-244`) and iOS's `GeofenceRequest`. Task 6
 * supplies the real navigation callback; this screen is fully functional on
 * its own with the default no-op.
 */
data class GeofenceRequest(val latitude: Double, val longitude: Double, val identifier: String? = null)

private val ENTER_COLOR = Color.parseColor("#22C55E")
private val EXIT_COLOR = Color.parseColor("#EF4444")
private val DWELL_COLOR = Color.parseColor("#F59E0B")
private val FALLBACK_COLOR = Color.parseColor("#F97316")
private val TRACK_COLOR = Color.parseColor("#3A6FF0")
private const val DEFAULT_LAT = 52.52
private const val DEFAULT_LNG = 13.405

@Composable
fun MapScreen(
    appStore: AppStore,
    permissionRequester: PermissionRequester,
    onGeofenceRequest: (GeofenceRequest) -> Unit = {},
) {
    val points by appStore.points.collectAsState()
    val geofences by appStore.geofences.collectAsState()
    val status by appStore.status.collectAsState()
    val link by appStore.link.collectAsState()

    var follow by remember { mutableStateOf(true) }
    var satellite by remember { mutableStateOf(false) }
    var showMarkers by remember { mutableStateOf(true) }
    var showPolylines by remember { mutableStateOf(true) }
    var showGeofences by remember { mutableStateOf(true) }
    var panelOpen by remember { mutableStateOf(true) }

    val scope = rememberCoroutineScope()
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }

    fun log(event: String, message: String, level: LogLevel) {
        appStore.appendLog(LogLine(ts = isoNow(), level = level, event = event, message = message))
    }

    // Mirrors `MapScreen.tsx`'s `onToggleTracking`: `requestPermission` and
    // `start` are attempted independently (a rejected permission prompt must
    // not skip the attempt to start) and each failure is logged, not thrown.
    fun toggleTracking() {
        scope.launch {
            if (status.enabled) {
                try {
                    BackgroundGeolocation.stop()
                } catch (e: Exception) {
                    log("stop", e.message ?: "stop failed", LogLevel.ERROR)
                }
                appStore.setStatus(enabled = false)
                log("stop", "tracking stopped", LogLevel.INFO)
            } else {
                try {
                    BackgroundGeolocation.requestPermission(permissionRequester)
                } catch (e: Exception) {
                    log("requestPermission", e.message ?: "requestPermission failed", LogLevel.ERROR)
                }
                try {
                    BackgroundGeolocation.start()
                    appStore.setStatus(enabled = true)
                    log("start", "tracking started", LogLevel.INFO)
                } catch (e: Exception) {
                    log("start", e.message ?: "start failed", LogLevel.ERROR)
                }
            }
        }
    }

    fun getPosition() {
        scope.launch {
            try {
                val location = BackgroundGeolocation.getCurrentPosition(CurrentPositionOptions(samples = 1, timeout = 30.0))
                log("getCurrentPosition", String.format(Locale.US, "%.6f, %.6f", location.coords.latitude, location.coords.longitude), LogLevel.INFO)
            } catch (e: Exception) {
                log("getCurrentPosition", e.message ?: "getCurrentPosition failed", LogLevel.ERROR)
            }
        }
    }

    val last = points.lastOrNull()

    Box(modifier = Modifier.fillMaxSize()) {
        MapViewContainer(
            points = points,
            geofences = geofences,
            isMoving = status.isMoving,
            follow = follow,
            satellite = satellite,
            showMarkers = showMarkers,
            showPolylines = showPolylines,
            showGeofences = showGeofences,
            onMapViewReady = { mapViewRef = it },
            onUserPan = { follow = false },
            onLongPress = { lat, lng -> onGeofenceRequest(GeofenceRequest(lat, lng)) },
            onGeofenceTap = { fence -> onGeofenceRequest(GeofenceRequest(fence.latitude, fence.longitude, fence.identifier)) },
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(10.dp),
        ) {
            StatusRow(linked = link.linked, deviceId = link.deviceId, isMoving = status.isMoving, batteryLevel = status.batteryLevel, pointCount = points.size)
            Spacer(Modifier.height(8.dp))
            ControlCard(
                enabled = status.enabled,
                panelOpen = panelOpen,
                onToggleTracking = ::toggleTracking,
                onGetPosition = ::getPosition,
                onTogglePanel = { panelOpen = !panelOpen },
                follow = follow,
                onToggleFollow = { follow = !follow },
                showMarkers = showMarkers,
                onToggleMarkers = { showMarkers = !showMarkers },
                showPolylines = showPolylines,
                onTogglePolylines = { showPolylines = !showPolylines },
                showGeofences = showGeofences,
                onToggleGeofences = { showGeofences = !showGeofences },
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = sheetPeekHeight + 16.dp, end = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FloatingActionButton(onClick = { satellite = !satellite }, modifier = Modifier.size(48.dp)) {
                Icon(if (satellite) Icons.Filled.Map else Icons.Filled.Satellite, contentDescription = "toggle satellite")
            }
            Spacer(Modifier.height(12.dp))
            FloatingActionButton(
                onClick = {
                    follow = true
                    last?.let { mapViewRef?.controller?.apply { setZoom(15.0); animateTo(GeoPoint(it.latitude, it.longitude)) } }
                },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(Icons.Filled.MyLocation, contentDescription = "recenter")
            }
        }

        Box(modifier = Modifier.align(Alignment.BottomCenter)) {
            CoordinatesSheet(points = points)
        }
    }
}

@Composable
private fun StatusRow(
    linked: Boolean,
    deviceId: String?,
    isMoving: Boolean,
    batteryLevel: Double?,
    pointCount: Int,
) {
    Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 2.dp) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (linked) "linked" else "not linked", style = MaterialTheme.typography.labelLarge)
            if (linked && deviceId != null) {
                Spacer(Modifier.width(8.dp))
                Text(deviceId.take(8), style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.weight(1f))
            Text(if (isMoving) "● moving" else "● stationary", style = MaterialTheme.typography.labelMedium)
            batteryLevel?.let { level ->
                dev.bgeo.example.ConfigCoerce.int(level * 100)?.let { pct ->
                    Spacer(Modifier.width(6.dp))
                    Text("$pct%", style = MaterialTheme.typography.labelMedium)
                }
            }
            Spacer(Modifier.width(6.dp))
            Text("$pointCount pts", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun ControlCard(
    enabled: Boolean,
    panelOpen: Boolean,
    onToggleTracking: () -> Unit,
    onGetPosition: () -> Unit,
    onTogglePanel: () -> Unit,
    follow: Boolean,
    onToggleFollow: () -> Unit,
    showMarkers: Boolean,
    onToggleMarkers: () -> Unit,
    showPolylines: Boolean,
    onTogglePolylines: () -> Unit,
    showGeofences: Boolean,
    onToggleGeofences: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(18.dp), tonalElevation = 2.dp) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onToggleTracking) {
                    Icon(if (enabled) Icons.Filled.Stop else Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (enabled) "Stop" else "Start")
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onGetPosition) {
                    Icon(Icons.Filled.LocationOn, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Get position")
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onTogglePanel) {
                    Icon(if (panelOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = "toggle controls")
                }
            }
            if (panelOpen) {
                Spacer(Modifier.height(8.dp))
                Row {
                    FilterChip(selected = follow, onClick = onToggleFollow, label = { Text("Follow") })
                    Spacer(Modifier.width(4.dp))
                    FilterChip(selected = showMarkers, onClick = onToggleMarkers, label = { Text("Pts") })
                    Spacer(Modifier.width(4.dp))
                    FilterChip(selected = showPolylines, onClick = onTogglePolylines, label = { Text("Line") })
                    Spacer(Modifier.width(4.dp))
                    FilterChip(selected = showGeofences, onClick = onToggleGeofences, label = { Text("Geo") })
                }
            }
        }
    }
}

@Composable
private fun MapViewContainer(
    points: List<Point>,
    geofences: List<Geofence>,
    isMoving: Boolean,
    follow: Boolean,
    satellite: Boolean,
    showMarkers: Boolean,
    showPolylines: Boolean,
    showGeofences: Boolean,
    onMapViewReady: (MapView) -> Unit,
    onUserPan: () -> Unit,
    onLongPress: (Double, Double) -> Unit,
    onGeofenceTap: (Geofence) -> Unit,
) {
    val overlayController = remember { MapOverlayController() }
    // Kept current on every recomposition (same pattern as iOS's Coordinator
    // assignment in `updateUIView`) so callbacks fired from a long-lived
    // click/touch listener never call through a stale closure.
    overlayController.onUserPan = onUserPan
    overlayController.onLongPress = onLongPress
    overlayController.onGeofenceTap = onGeofenceTap

    val last = points.lastOrNull()

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            configureOsmdroid(context)
            val mapView = MapView(context.applicationContext).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                setBuiltInZoomControls(false)
                val initial = last?.let { GeoPoint(it.latitude, it.longitude) } ?: GeoPoint(DEFAULT_LAT, DEFAULT_LNG)
                controller.setZoom(15.0)
                controller.setCenter(initial)

                val eventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false
                    override fun longPressHelper(p: GeoPoint?): Boolean {
                        val point = p ?: return false
                        overlayController.onLongPress(point.latitude, point.longitude)
                        return true
                    }
                })
                overlays.add(eventsOverlay)

                // Disengages Follow on a real drag, without relying on
                // `MapListener.onScroll` — that callback fires for BOTH user
                // drags and our own `controller.animateTo` calls, and telling
                // them apart needs a flag whose lifecycle is exactly the
                // class of bug this screen's whole task is about avoiding
                // (see this file's header). A touch event is unambiguous:
                // only a real finger on the glass produces one, so this
                // can't misfire on a programmatic camera move.
                //
                // Fix round 1 (F5): firing on raw ACTION_DOWN disengaged
                // Follow on ANY touch, including a tap on a geofence pin or a
                // long-press to add one — neither of which pans the camera.
                // Only call [onUserPan] once the finger has actually moved
                // past the platform's touch-slop threshold, i.e. a real drag,
                // not a tap-in-place.
                val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
                var downX = 0f
                var downY = 0f
                var dragged = false
                setOnTouchListener { _, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            downX = event.x
                            downY = event.y
                            dragged = false
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (!dragged) {
                                val dx = event.x - downX
                                val dy = event.y - downY
                                if (dx * dx + dy * dy > touchSlop * touchSlop) {
                                    dragged = true
                                    overlayController.onUserPan()
                                }
                            }
                        }
                    }
                    false // never consume — let the map handle the gesture normally
                }
            }
            onMapViewReady(mapView)
            mapView
        },
        update = { mapView ->
            overlayController.applyTileSource(mapView, satellite)
            overlayController.apply(mapView, points, showMarkers, showPolylines, geofences, showGeofences, isMoving)
            if (follow && last != null) {
                overlayController.applyFollowCamera(mapView, GeoPoint(last.latitude, last.longitude))
            } else {
                overlayController.onFollowInactive()
            }
        },
        onRelease = { mapView -> mapView.onDetach() },
    )
}

/** Once per process is enough (idempotent to call again): app-private tile cache, no storage permission needed, and a distinct user agent — osmdroid's default is rejected by the OSM tile servers. */
private fun configureOsmdroid(context: Context) {
    val appContext = context.applicationContext
    val base = File(appContext.cacheDir, "osmdroid")
    val config = Configuration.getInstance()
    config.userAgentValue = "dev.bgeo.example"
    config.osmdroidBasePath = base
    config.osmdroidTileCache = File(base, "tiles").apply { mkdirs() }
}

/**
 * Owns every osmdroid `Overlay` this screen draws, split into two mutable
 * collections — [trackOverlays] and [geofenceOverlays] — so a rebuild of one
 * never touches the other. See this file's header for why (`MapRebuild.kt`'s
 * defect history) and for exactly how this was verified (inspection, not a
 * test — no seam exists to unit-test a real `MapView`).
 */
private class MapOverlayController {
    var onUserPan: () -> Unit = {}
    var onLongPress: (Double, Double) -> Unit = { _, _ -> }
    var onGeofenceTap: (Geofence) -> Unit = {}

    private var lastTrackSnapshot: TrackSnapshot? = null
    private var lastGeofenceSnapshot: GeofenceSnapshot? = null
    private val trackOverlays = mutableListOf<Overlay>()
    private val geofenceOverlays = mutableListOf<Overlay>()
    private var lastTileSatellite: Boolean? = null
    private var lastFollowTarget: GeoPoint? = null
    private var followWasActive = false

    fun applyTileSource(mapView: MapView, satellite: Boolean) {
        if (lastTileSatellite == satellite) return
        lastTileSatellite = satellite
        // USGS_SAT is US-only imagery — osmdroid ships no keyless satellite
        // source with global coverage (checked the whole `TileSourceFactory`
        // surface), so outside the US this toggle yields blank tiles. No
        // in-app labelling exists for this FAB; flagged in fix round 1's
        // report as an accepted, honestly-documented limitation rather than
        // silently pretending it's global.
        mapView.setTileSource(if (satellite) TileSourceFactory.USGS_SAT else TileSourceFactory.MAPNIK)
    }

    /**
     * Passive re-center while Follow is on — deduped so an unrelated
     * recomposition (e.g. a layer toggle) doesn't re-animate the camera to
     * the same spot.
     *
     * Fix round 1 (F4): the dedupe used to fire even on Follow's re-enable
     * edge. Panning away auto-disengages Follow (`onUserPan`) without
     * clearing [lastFollowTarget]; tapping the Follow chip back on then
     * compared the (unchanged) last-known point against that stale target,
     * found them equal, and skipped `animateTo` — the chip lit up but the
     * map stayed wherever the user had panned it until the next fix.
     * [followWasActive] tracks the edge itself so a re-enable always
     * recenters at least once, regardless of whether the target moved.
     */
    fun applyFollowCamera(mapView: MapView, target: GeoPoint) {
        val justEnabled = !followWasActive
        followWasActive = true
        if (!justEnabled && target == lastFollowTarget) return
        lastFollowTarget = target
        mapView.controller.animateTo(target)
    }

    /** Follow is off this tick — reset the re-enable edge so the next [applyFollowCamera] call is treated as a fresh engagement. */
    fun onFollowInactive() {
        followWasActive = false
    }

    fun apply(
        mapView: MapView,
        points: List<Point>,
        showMarkers: Boolean,
        showPolylines: Boolean,
        geofences: List<Geofence>,
        showGeofences: Boolean,
        isMoving: Boolean,
    ) {
        val context = mapView.context
        val trackSnapshot = MapRebuild.buildTrackSnapshot(points, showMarkers, showPolylines, isMoving)
        val geofenceSnapshot = MapRebuild.buildGeofenceSnapshot(geofences, showGeofences) { fence ->
            colorHex(geofenceColor(points, fence.identifier))
        }

        val decision = MapRebuild.decide(lastTrackSnapshot, trackSnapshot, lastGeofenceSnapshot, geofenceSnapshot)
        if (!decision.rebuildTrack && !decision.rebuildGeofences) return

        // Each branch below removes ONLY its own collection — never
        // `mapView.overlays.clear()`/a combined sweep — so an in-flight
        // geofence info-window (the "tap again to edit" callout) survives a
        // location-only rebuild untouched. This is the one property this
        // task is about; see this file's header for how it's verified.
        if (decision.rebuildGeofences) {
            lastGeofenceSnapshot = geofenceSnapshot
            if (geofenceOverlays.isNotEmpty()) {
                mapView.overlays.removeAll(geofenceOverlays)
                geofenceOverlays.clear()
            }
            if (showGeofences) {
                geofences.forEach { fence -> geofenceOverlays += buildGeofenceOverlays(mapView, fence, geofenceColor(points, fence.identifier)) }
                mapView.overlays.addAll(geofenceOverlays)
            }
        }

        if (decision.rebuildTrack) {
            lastTrackSnapshot = trackSnapshot
            if (trackOverlays.isNotEmpty()) {
                mapView.overlays.removeAll(trackOverlays)
                trackOverlays.clear()
            }
            if (showPolylines && points.size > 1) {
                trackOverlays += Polyline().apply {
                    setPoints(points.map { GeoPoint(it.latitude, it.longitude) })
                    outlinePaint.color = TRACK_COLOR
                    outlinePaint.strokeWidth = 6f
                    setInfoWindow(null)
                }
            }
            if (showMarkers) {
                points.forEach { p ->
                    trackOverlays += Marker(mapView).apply {
                        position = GeoPoint(p.latitude, p.longitude)
                        icon = DotMarker.drawable(context, diameterDp = 8f, fillColor = withAlpha(TRACK_COLOR, 0xCC), borderColor = Color.WHITE, borderWidthDp = 1f)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        setInfoWindow(null)
                        // Fix round 1 (F3): a track dot has no click listener
                        // of its own, so osmdroid runs `onMarkerClickDefault`
                        // — show info window (a no-op here), pan the camera,
                        // and consume the tap (return true) — on whichever
                        // marker is hit first. The track rebuilds on ~every
                        // fix and is re-added to the end of `mapView.overlays`
                        // each time, so it's always hit-tested before an
                        // older geofence pin underneath it, making that pin
                        // untappable. Returning false here means "not
                        // handled", so osmdroid's `OverlayManager` keeps
                        // walking down to the next overlay instead of
                        // stopping on this dot.
                        setOnMarkerClickListener { _, _ -> false }
                    }
                }
            }
            points.lastOrNull()?.let { lastPoint ->
                trackOverlays += Marker(mapView).apply {
                    position = GeoPoint(lastPoint.latitude, lastPoint.longitude)
                    val fill = if (isMoving) ENTER_COLOR else TRACK_COLOR
                    icon = DotMarker.drawable(context, diameterDp = 16f, fillColor = fill, borderColor = Color.WHITE, borderWidthDp = 2f)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    setInfoWindow(null)
                    setOnMarkerClickListener { _, _ -> false } // see the comment above
                }
            }
            mapView.overlays.addAll(trackOverlays)
        }

        mapView.invalidate()
    }

    /** Circle + pin for one geofence. First tap shows the "tap again to edit" callout; a second tap on an already-shown callout invokes [onGeofenceTap]. */
    private fun buildGeofenceOverlays(mapView: MapView, fence: Geofence, color: Int): List<Overlay> {
        val center = GeoPoint(fence.latitude, fence.longitude)
        val circle = Polygon(mapView).apply {
            setPoints(Polygon.pointsAsCircle(center, fence.radius))
            fillColor = withAlpha(color, 0x1F)
            strokeColor = color
            strokeWidth = 2f
            setInfoWindow(null)
        }
        val pin = Marker(mapView).apply {
            position = center
            title = fence.identifier
            snippet = "tap again to edit"
            icon = DotMarker.drawable(mapView.context, diameterDp = 22f, fillColor = color, borderColor = Color.WHITE, borderWidthDp = 2f)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            setOnMarkerClickListener { clickedMarker, _ ->
                if (clickedMarker.isInfoWindowShown) {
                    clickedMarker.closeInfoWindow()
                    onGeofenceTap(fence)
                } else {
                    clickedMarker.showInfoWindow()
                }
                true
            }
        }
        return listOf(circle, pin)
    }
}

/** Latest ENTER/EXIT/DWELL transition for [identifier] found in [points] (newest first), else the fallback colour — parity with the web console's `TrackMap` / RN's `GEOFENCE_ACTION_COLOR`. */
private fun geofenceColor(points: List<Point>, identifier: String): Int {
    for (i in points.indices.reversed()) {
        val p = points[i]
        if (p.event == "geofence" && p.geofence?.identifier == identifier) {
            return when (p.geofence.action?.uppercase()) {
                "ENTER" -> ENTER_COLOR
                "EXIT" -> EXIT_COLOR
                "DWELL" -> DWELL_COLOR
                else -> FALLBACK_COLOR
            }
        }
    }
    return FALLBACK_COLOR
}

private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

/** Deterministic string form of an ARGB int for [MapRebuild.geofenceKey] — a change-detection key component, never rendered as text. */
private fun colorHex(color: Int): String = String.format(Locale.US, "#%08X", color)

private fun isoNow(): String {
    val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    format.timeZone = TimeZone.getTimeZone("UTC")
    return format.format(Date())
}
