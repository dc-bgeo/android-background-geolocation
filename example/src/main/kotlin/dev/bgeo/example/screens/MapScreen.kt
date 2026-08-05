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
// keeps the track half (`trackMarkers`/`trackPolyline`/`lastMarker`) and the
// geofence half (`geofenceOverlays`) in separate state and touches only the
// half `MapRebuild.decide` says changed — never `mapView.overlays.clear()` or
// any other sweep of the combined list. This is NOT unit-tested: it requires
// a real `MapView`/`Overlay`, both stubbed under this module's harness (no
// Robolectric, no instrumentation harness — see the module's build.gradle
// comment). Verified by inspection instead: grep this file for
// `overlays.clear` (there is none) and read the four `apply*` helpers below.
// `MapRebuildTest` proves the DECISION and the DIFF are correct; it cannot
// prove the renderer obeys them, per the task brief's own warning that
// `decide` alone is not enough.
//
// **The track half is diffed, not rebuilt.** It used to be: every accepted
// fix removed every track overlay and built a fresh `Marker` — with its own
// `DotMarker.drawable` bitmap — for every point in the window. iOS had the
// identical defect and there it visibly blinked the whole track (MapKit
// renders overlays asynchronously); osmdroid draws synchronously so nothing
// blinked here, which is exactly why it survived: the cost was invisible,
// up to `MapPaging.PAGE_SIZE` `Marker`s and bitmaps allocated per second
// while driving. `applyTrackDots` now adds/removes only what changed and
// shares one icon instance; `applyLastMarker` moves a single marker.
//
// **Range and paging.** The from/to history range ([RangeBar]) reads the
// same hybrid source as the other consoles (`History.load` -> server history
// when linked, the local buffer otherwise), and the DRAWN track is windowed
// to `MapPaging.PAGE_SIZE` points behind a [Pager] — both live and range
// tracks, exactly as RN/iOS/Flutter do. The window, not the whole list, is
// what reaches the map; the coordinates sheet still lists everything.

import android.content.Context
import android.graphics.Color
import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Satellite
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.bgeo.sdk.BackgroundGeolocation
import com.bgeo.sdk.CurrentPositionOptions
import com.bgeo.sdk.Geofence
import com.bgeo.sdk.PermissionRequester
import dev.bgeo.example.AppStore
import dev.bgeo.example.DeviceLink
import dev.bgeo.example.History
import dev.bgeo.example.LogLevel
import dev.bgeo.example.LogUploader
import dev.bgeo.example.Point
import dev.bgeo.example.components.CoordinatesSheet
import dev.bgeo.example.components.DotMarker
import dev.bgeo.example.components.sheetPeekHeight
import dev.bgeo.example.ui.Mono
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
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
    logUploader: LogUploader,
    deviceLink: DeviceLink,
    permissionRequester: PermissionRequester,
    onGeofenceRequest: (GeofenceRequest) -> Unit = {},
) {
    val points by appStore.points.collectAsState()
    val geofences by appStore.geofences.collectAsState()
    val status by appStore.status.collectAsState()
    val link by appStore.link.collectAsState()

    // `rememberSaveable`, not `remember`: `ExampleScaffold` composes only the
    // selected tab, so a trip to Logs and back tears this screen down — every
    // one of these toggles used to silently snap back to its default.
    var follow by rememberSaveable { mutableStateOf(true) }
    var satellite by rememberSaveable { mutableStateOf(false) }
    var showMarkers by rememberSaveable { mutableStateOf(true) }
    var showPolylines by rememberSaveable { mutableStateOf(true) }
    var showGeofences by rememberSaveable { mutableStateOf(true) }
    var panelOpen by rememberSaveable { mutableStateOf(true) }

    // From/to history range (`History.load`'s only consumer, same as
    // `MapScreen.tsx`/`.swift`): while `historyPoints` is non-null the screen
    // draws that range instead of the live buffer, and Follow stays off.
    var fromMillis by rememberSaveable { mutableStateOf<Long?>(null) }
    var toMillis by rememberSaveable { mutableStateOf<Long?>(null) }
    // The loaded range itself stays in `remember` on purpose: it is up to
    // 2000 points, and `rememberSaveable` values also travel through the
    // saved-instance-state Bundle, whose ~1 MB transaction limit this would
    // flirt with. The from/to bounds survive instead, so re-applying is one
    // tap — the data is never the thing worth risking a
    // TransactionTooLargeException for.
    var historyPoints by remember { mutableStateOf<List<Point>?>(null) }
    var loadingRange by remember { mutableStateOf(false) }
    val rangeActive = historyPoints != null
    val displayPoints = historyPoints ?: points

    // Paged window over the track (`MapPaging`): page 0 is the newest
    // PAGE_SIZE points and follows live, higher pages step back through
    // history. Only the window is drawn — every console does this, because
    // native map markers get slow in the thousands.
    var page by rememberSaveable { mutableIntStateOf(0) }
    val window = MapPaging.window(displayPoints.size, page)
    val windowPoints = remember(displayPoints, window) {
        if (window.windowStart < window.windowEnd) displayPoints.subList(window.windowStart, window.windowEnd) else emptyList()
    }

    val scope = rememberCoroutineScope()
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }

    // Through `LogUploader`, not `appStore.appendLog` directly: that is what
    // also persists the line to the SDK's own log queue (surviving app kills)
    // and uploads it to `/device/logs` once linked — and what applies the
    // credential scrub to every line by construction.
    fun log(event: String, message: String, level: LogLevel) {
        logUploader.logEvent(event, level, message)
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

    /**
     * `MapScreen.tsx`'s `applyRange`: load the range (server history when
     * linked, the local buffer otherwise — [History.load] decides), stop
     * following live, and frame what was loaded. A range that comes back
     * empty still takes effect, exactly like the reference clients: the map
     * goes blank, which IS the answer for "no points in that window", rather
     * than silently leaving the live track on screen.
     */
    fun applyRange() {
        if (fromMillis == null && toMillis == null) return
        loadingRange = true
        follow = false
        scope.launch {
            val loaded = History.load(
                deviceLink = deviceLink,
                linked = link.linked,
                localPoints = points,
                from = fromMillis?.let(History::isoUtc),
                to = toMillis?.let(History::isoUtc),
            )
            historyPoints = loaded
            loadingRange = false
            page = 0
            log("history", "range loaded: ${loaded.size} points", LogLevel.INFO)
            // Frame the newest window of the range — that is what the map will
            // draw, not the whole range.
            fitCamera(mapViewRef, loaded.takeLast(MapPaging.PAGE_SIZE))
        }
    }

    fun resetRange() {
        historyPoints = null
        fromMillis = null
        toMillis = null
        page = 0
    }

    val last = displayPoints.lastOrNull()

    // Fit the camera to the window whenever the user pages through history —
    // `MapScreen.tsx`'s `fittedPage` effect.
    var fittedPage by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(window.effPage, windowPoints.size) {
        if (fittedPage != window.effPage) {
            fittedPage = window.effPage
            if (windowPoints.size > 1) fitCamera(mapViewRef, windowPoints)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        MapViewContainer(
            points = windowPoints,
            geofences = geofences,
            isMoving = status.isMoving,
            // Following live only makes sense on the page that HAS the live
            // tail; browsing history must not be yanked back by the next fix.
            follow = follow && !rangeActive && window.onNewestPage,
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
            // NO `statusBarsPadding()`: `ExampleScaffold`'s `Scaffold` already
            // insets its content by the status bar, so adding it here again
            // pushed these two cards a second bar-height (144px on a Pixel 7
            // Pro, cutout included) down the screen for no reason.
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
        ) {
            StatusRow(linked = link.linked, deviceId = link.deviceId, isMoving = status.isMoving, batteryLevel = status.batteryLevel, pointCount = displayPoints.size, rangeActive = rangeActive)
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
                fromMillis = fromMillis,
                onPickFrom = { fromMillis = it },
                toMillis = toMillis,
                onPickTo = { toMillis = it },
                loadingRange = loadingRange,
                rangeActive = rangeActive,
                onApplyRange = ::applyRange,
                onResetRange = ::resetRange,
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

        if (window.pageCount > 1) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(bottom = sheetPeekHeight + 16.dp, start = 14.dp),
            ) {
                Pager(
                    window = window,
                    totalCount = displayPoints.size,
                    rangeActive = rangeActive,
                    onOlder = { page = window.effPage + 1 },
                    onNewer = { page = maxOf(0, window.effPage - 1) },
                )
            }
        }

        Box(modifier = Modifier.align(Alignment.BottomCenter)) {
            CoordinatesSheet(points = displayPoints)
        }
    }
}

/**
 * Steps through the drawn window when the track is longer than
 * [MapPaging.PAGE_SIZE] — `‹` goes back in time, `›` returns toward live.
 * Same control, same label shape, as the other three consoles; it only
 * appears when there is more than one page, so a short track never sees it.
 */
@Composable
private fun Pager(
    window: MapWindow,
    totalCount: Int,
    rangeActive: Boolean,
    onOlder: () -> Unit,
    onNewer: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(12.dp), tonalElevation = 2.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            IconButton(onClick = onOlder, enabled = window.effPage < window.pageCount - 1, modifier = Modifier.size(36.dp)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "older points")
            }
            Text(
                "${window.windowStart + 1}–${window.windowEnd} / $totalCount" +
                    if (window.onNewestPage && !rangeActive) " · live" else "",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = Mono,
                maxLines = 1,
            )
            IconButton(onClick = onNewer, enabled = !window.onNewestPage, modifier = Modifier.size(36.dp)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "newer points")
            }
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
    rangeActive: Boolean,
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
            // `>= 0`, not just non-null: an unknown battery level arrives as
            // -1 from the engine and would render as "-100%" (the same guard
            // the iOS console needed).
            batteryLevel?.takeIf { it >= 0 }?.let { level ->
                dev.bgeo.example.ConfigCoerce.int(level * 100)?.let { pct ->
                    Spacer(Modifier.width(6.dp))
                    Text("$pct%", style = MaterialTheme.typography.labelMedium)
                }
            }
            Spacer(Modifier.width(6.dp))
            Text("$pointCount pts${if (rangeActive) " (hist)" else ""}", style = MaterialTheme.typography.labelMedium)
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
    fromMillis: Long?,
    onPickFrom: (Long?) -> Unit,
    toMillis: Long?,
    onPickTo: (Long?) -> Unit,
    loadingRange: Boolean,
    rangeActive: Boolean,
    onApplyRange: () -> Unit,
    onResetRange: () -> Unit,
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
                Spacer(Modifier.height(8.dp))
                RangeBar(
                    fromMillis = fromMillis,
                    onPickFrom = onPickFrom,
                    toMillis = toMillis,
                    onPickTo = onPickTo,
                    loading = loadingRange,
                    rangeActive = rangeActive,
                    onApply = onApplyRange,
                    onReset = onResetRange,
                )
            }
        }
    }
}

/**
 * The from/to history range bar — the last piece of `MapScreen.tsx`'s control
 * panel this console was missing (`History.load` shipped with no caller; see
 * that file's header, which this change makes obsolete).
 *
 * Two rows, not one: a picked date reads as "07-30 18:42", and two of those
 * plus Apply plus Live do not fit a phone width side by side — the row that
 * squeezed six chips into nothing on the Settings screen is the same failure
 * mode.
 */
@Composable
private fun RangeBar(
    fromMillis: Long?,
    onPickFrom: (Long?) -> Unit,
    toMillis: Long?,
    onPickTo: (Long?) -> Unit,
    loading: Boolean,
    rangeActive: Boolean,
    onApply: () -> Unit,
    onReset: () -> Unit,
) {
    var picking by remember { mutableStateOf<RangeBound?>(null) }

    picking?.let { bound ->
        val current = if (bound == RangeBound.FROM) fromMillis else toMillis
        DateTimePickerDialog(
            initial = current,
            onDismiss = { picking = null },
            onPicked = { millis ->
                if (bound == RangeBound.FROM) onPickFrom(millis) else onPickTo(millis)
                picking = null
            },
        )
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RangeField(
                label = "From",
                millis = fromMillis,
                placeholder = "—",
                modifier = Modifier.weight(1f),
                onPick = { picking = RangeBound.FROM },
                onClear = { onPickFrom(null) },
            )
            Spacer(Modifier.width(8.dp))
            RangeField(
                label = "To",
                millis = toMillis,
                placeholder = "now",
                modifier = Modifier.weight(1f),
                onPick = { picking = RangeBound.TO },
                onClear = { onPickTo(null) },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = onApply,
                enabled = !loading && (fromMillis != null || toMillis != null),
                contentPadding = PaddingValues(horizontal = 16.dp),
            ) {
                Text("Apply")
            }
            if (loading) {
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            if (rangeActive) {
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onReset, contentPadding = PaddingValues(horizontal = 16.dp)) {
                    Text("Live")
                }
            }
        }
    }
}

@Composable
private fun RangeField(
    label: String,
    millis: Long?,
    placeholder: String,
    modifier: Modifier,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            onClick = onPick,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 10.dp),
        ) {
            Text(
                "$label ${millis?.let(::formatRangeStamp) ?: placeholder}",
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (millis != null) {
            IconButton(onClick = onClear, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "clear $label", modifier = Modifier.size(18.dp))
            }
        }
    }
}

private enum class RangeBound { FROM, TO }

/**
 * Date, then time — the same two-step flow Flutter's console uses
 * (`showDatePicker` then `showTimePicker`).
 *
 * Compose's own pickers rather than `android.app.DatePickerDialog`: the
 * platform dialog is styled by the Activity's XML theme
 * (`android:Theme.Material.Light`), which knows nothing about this app's
 * palette and drew a teal 2014-era dialog over an otherwise blue Material 3
 * console. These follow [dev.bgeo.example.ui.ExampleTheme] like everything
 * else.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimePickerDialog(initial: Long?, onDismiss: () -> Unit, onPicked: (Long) -> Unit) {
    val start = remember(initial) { Calendar.getInstance().apply { initial?.let { timeInMillis = it } } }
    var pickingTime by remember { mutableStateOf(false) }
    val dateState = rememberDatePickerState(initialSelectedDateMillis = start.timeInMillis)
    val timeState = rememberTimePickerState(
        initialHour = start.get(Calendar.HOUR_OF_DAY),
        initialMinute = start.get(Calendar.MINUTE),
        is24Hour = true,
    )

    if (!pickingTime) {
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = { pickingTime = true }) { Text("Next") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        ) {
            DatePicker(state = dateState)
        }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = { onPicked(combine(dateState.selectedDateMillis, timeState.hour, timeState.minute, start)) }) {
                    Text("OK")
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
            text = { TimeInput(state = timeState) },
        )
    }
}

/**
 * The picked calendar day + the picked wall-clock time, as local-time epoch
 * millis.
 *
 * `DatePickerState.selectedDateMillis` is UTC midnight of the chosen day, NOT
 * a local timestamp — reading its fields with a default-zone `Calendar` lands
 * on the previous or next day for anyone far enough east or west, which is the
 * whole reason this is a named function with this comment rather than four
 * inline `Calendar` calls.
 */
private fun combine(selectedDateMillis: Long?, hour: Int, minute: Int, fallback: Calendar): Long {
    val day = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        timeInMillis = selectedDateMillis ?: return fallback.timeInMillis
    }
    return Calendar.getInstance().apply {
        set(day.get(Calendar.YEAR), day.get(Calendar.MONTH), day.get(Calendar.DAY_OF_MONTH), hour, minute, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

/** Local wall-clock `MM-dd HH:mm` for a picked bound — short enough to fit the field. */
private fun formatRangeStamp(millis: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(millis))

/**
 * Frames [points] after a range load, so applying a range that is nowhere near
 * the current camera actually shows something (`MapScreen.tsx` calls
 * `fitToCoordinates` at the same spot). A single point — or several at the
 * same coordinate, which `BoundingBox` reports as zero-span and osmdroid
 * cannot compute a zoom for — is centred instead.
 */
private fun fitCamera(mapView: MapView?, points: List<Point>) {
    val map = mapView ?: return
    val geoPoints = points.map { GeoPoint(it.latitude, it.longitude) }
    val first = geoPoints.firstOrNull() ?: return
    val box = BoundingBox.fromGeoPoints(geoPoints)
    if (geoPoints.size < 2 || box.latitudeSpan <= 0.0 || box.longitudeSpanWithDateLine <= 0.0) {
        map.controller.animateTo(first)
        return
    }
    map.zoomToBoundingBox(box, true, 80)
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
    // The track half is keyed, not a flat list, so it can be DIFFED — see
    // `MapRebuild.diffTrackDots`. The polyline and the current-position marker
    // are single long-lived objects that get updated in place.
    private val trackMarkers = LinkedHashMap<String, Marker>()
    private var trackPolyline: Polyline? = null
    private var lastMarker: Marker? = null
    private var lastMarkerMoving: Boolean? = null
    private var trackDotIcon: android.graphics.drawable.Drawable? = null
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
            applyPolyline(mapView, points, showPolylines)
            applyTrackDots(mapView, context, points, showMarkers)
            applyLastMarker(mapView, context, points.lastOrNull(), isMoving)
        }

        mapView.invalidate()
    }

    /** One long-lived [Polyline] whose points are replaced in place — nothing is torn off the map to redraw the track. */
    private fun applyPolyline(mapView: MapView, points: List<Point>, showPolylines: Boolean) {
        if (!showPolylines || points.size <= 1) {
            trackPolyline?.let { mapView.overlays.remove(it) }
            trackPolyline = null
            return
        }
        val polyline = trackPolyline ?: Polyline().also { fresh ->
            fresh.outlinePaint.color = TRACK_COLOR
            fresh.outlinePaint.strokeWidth = 6f
            fresh.setInfoWindow(null)
            trackPolyline = fresh
            mapView.overlays.add(fresh)
        }
        polyline.setPoints(points.map { GeoPoint(it.latitude, it.longitude) })
    }

    /**
     * Adds and removes ONLY the dots that actually changed
     * ([MapRebuild.diffTrackDots]), and gives every one of them the SAME icon
     * instance.
     *
     * Both halves matter. The old code removed every dot and built a fresh
     * `Marker` per point on each accepted fix, each with its own
     * `DotMarker.drawable` — a `Bitmap` allocation per point per fix, up to
     * [MapPaging.PAGE_SIZE] of them a second while driving. A window that
     * slides by one point now costs one `Marker` and zero bitmaps.
     */
    private fun applyTrackDots(mapView: MapView, context: Context, points: List<Point>, showMarkers: Boolean) {
        val desired = if (showMarkers) MapRebuild.trackDotKeys(points) else emptyList()
        val diff = MapRebuild.diffTrackDots(trackMarkers.keys.toList(), desired)
        if (diff.added.isEmpty() && diff.removed.isEmpty()) return

        diff.removed.forEach { key ->
            trackMarkers.remove(key)?.let { mapView.overlays.remove(it) }
        }
        if (diff.added.isNotEmpty()) {
            // Shared across every dot added in this pass: identical size and
            // colour, and osmdroid re-sets the icon's bounds per draw, so one
            // bitmap serves the whole track.
            val icon = trackDotIcon ?: DotMarker.drawable(
                context,
                diameterDp = 8f,
                fillColor = withAlpha(TRACK_COLOR, 0xCC),
                borderColor = Color.WHITE,
                borderWidthDp = 1f,
            ).also { trackDotIcon = it }
            val pointByKey = desired.zip(points).toMap()
            diff.added.forEach { key ->
                val point = pointByKey[key] ?: return@forEach
                val marker = Marker(mapView).apply {
                    position = GeoPoint(point.latitude, point.longitude)
                    this.icon = icon
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    setInfoWindow(null)
                    // Fix round 1 (F3): a track dot has no click listener of
                    // its own, so osmdroid runs `onMarkerClickDefault` — show
                    // info window (a no-op here), pan the camera, and consume
                    // the tap (return true) — on whichever marker is hit
                    // first, which would make a geofence pin underneath a dot
                    // untappable. Returning false means "not handled", so
                    // osmdroid's `OverlayManager` keeps walking down to the
                    // next overlay instead of stopping on this dot.
                    setOnMarkerClickListener { _, _ -> false }
                }
                trackMarkers[key] = marker
                mapView.overlays.add(marker)
            }
        }
    }

    /**
     * ONE current-position marker for the life of the map: it is moved, not
     * replaced, and its icon is rebuilt only when the moving/stationary colour
     * actually changes.
     */
    private fun applyLastMarker(mapView: MapView, context: Context, lastPoint: Point?, isMoving: Boolean) {
        if (lastPoint == null) {
            lastMarker?.let { mapView.overlays.remove(it) }
            lastMarker = null
            lastMarkerMoving = null
            return
        }
        val marker = lastMarker ?: Marker(mapView).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            setInfoWindow(null)
            setOnMarkerClickListener { _, _ -> false } // see applyTrackDots
            lastMarker = this
            mapView.overlays.add(this)
        }
        marker.position = GeoPoint(lastPoint.latitude, lastPoint.longitude)
        if (lastMarkerMoving != isMoving) {
            lastMarkerMoving = isMoving
            marker.icon = DotMarker.drawable(
                context,
                diameterDp = 16f,
                fillColor = if (isMoving) ENTER_COLOR else TRACK_COLOR,
                borderColor = Color.WHITE,
                borderWidthDp = 2f,
            )
        }
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
