package dev.bgeo.example.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.bgeo.example.ConfigCoerce
import dev.bgeo.example.Point
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Collapsible bottom sheet with the collected-coordinates table (newest
 * first) — a Kotlin port of `components/CoordinatesSheet.tsx`;
 * `Components/CoordinatesSheet.swift` (iOS) is the same port and made the
 * same simplification this file makes: a tap-to-toggle between a peek and an
 * expanded height instead of RN's free-drag `PanResponder` gesture. Same two
 * end states, far less gesture code to get right, no Compose instrumentation
 * harness in this module to prove a drag gesture correct anyway.
 *
 * Row cell formatting ([PointFormat]) is pulled out as pure, independently
 * testable functions, same reasoning as the iOS port's own `PointFormat` —
 * this is the "coordinate formatting" logic that can be unit-tested even
 * though the view around it cannot.
 */

/** Collapsed (peek) height — public so `MapScreen.kt` can position its FABs/panels above the sheet without duplicating the constant. */
val sheetPeekHeight = 70.dp
private val expandedHeight = 320.dp

@Composable
fun CoordinatesSheet(points: List<Point>) {
    var expanded by remember { mutableStateOf(false) }
    val height by animateDpAsState(if (expanded) expandedHeight else sheetPeekHeight, label = "sheetHeight")
    val rows = remember(points) { points.asReversed() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Collected coordinates", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text("${points.size} pts", style = MaterialTheme.typography.labelMedium)
            IconButton(onClick = { expanded = !expanded }) {
                Icon(if (expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp, contentDescription = null)
            }
        }

        Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Column {
                HeaderRow()
                if (rows.isEmpty()) {
                    Text("no points yet", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
                } else {
                    LazyColumn {
                        items(rows.size) { i -> PointRow(rows[i], number = rows.size - i) }
                    }
                }
            }
        }
    }
}

private val columnWidths = listOf(
    32.dp, 76.dp, 90.dp, 90.dp, 90.dp, 100.dp, 90.dp, 80.dp, 70.dp, 90.dp, 150.dp,
)
private val headers = listOf(
    "#", "TIME", "LAT", "LNG", "ACC (m)", "SPEED (m/s)", "ODO (m)", "HEADING", "MOVING", "ACTIVITY", "EVENT",
)

@Composable
private fun HeaderRow() {
    Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        headers.forEachIndexed { i, label ->
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.width(columnWidths[i]),
                textAlign = if (i == 0 || i == headers.lastIndex) TextAlign.Start else TextAlign.End,
            )
        }
    }
}

@Composable
private fun PointRow(point: Point, number: Int) {
    val cells = listOf(
        "%02d".format(number),
        PointFormat.time(point.timestamp),
        PointFormat.coordinate(point.latitude),
        PointFormat.coordinate(point.longitude),
        PointFormat.roundedOrDash(point.accuracy),
        PointFormat.nonNegative(point.speed, digits = 1),
        PointFormat.roundedOrDash(point.odometer),
        PointFormat.heading(point.heading),
        PointFormat.isMoving(point.isMoving),
        point.activity ?: "–",
        PointFormat.eventLabel(point),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cells.forEachIndexed { i, text ->
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.width(columnWidths[i]),
                textAlign = if (i == 0 || i == cells.lastIndex) TextAlign.Start else TextAlign.End,
                maxLines = 1,
            )
        }
    }
}

/**
 * Swift port's `PointFormat` equivalent: pure, no Android/Compose imports in
 * the functions themselves (this file only imports Compose for the `@Composable`
 * view above), so directly unit-testable — see `CoordinatesSheetLogicTest`.
 */
object PointFormat {
    private val isoPatterns = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
    )

    /** Parses either fractional- or whole-second ISO 8601 (the two shapes a `Point.timestamp` arrives in); null for anything else. */
    fun parseIso(iso: String): java.util.Date? {
        for (pattern in isoPatterns) {
            val format = SimpleDateFormat(pattern, Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
                isLenient = false
            }
            try {
                return format.parse(iso)
            } catch (e: ParseException) {
                // try the next pattern
            }
        }
        return null
    }

    /** Local wall-clock `HH:mm:ss` — matches RN's `new Date(iso)` + `getHours`/`getMinutes`/`getSeconds` (device-local, not UTC). */
    fun time(iso: String): String {
        val date = parseIso(iso) ?: return "--:--:--"
        val calendar = Calendar.getInstance().apply { time = date }
        return "%02d:%02d:%02d".format(
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            calendar.get(Calendar.SECOND),
        )
    }

    // `Locale.US` pinned explicitly (fix round 1, F7): plain `.format(...)`
    // uses the JVM default locale, which renders the decimal separator as
    // `,` on comma-decimal locales — the LAT/LNG columns would then read
    // e.g. `52,52000`. `MapScreen.kt` already pins `Locale.US` for its own
    // formatting; this brings these two sites in line with that convention.
    fun coordinate(value: Double, digits: Int = 5): String = String.format(Locale.US, "%.${digits}f", value)

    /** RN's `num(v, digits)`: dash for missing or negative values (used for speed, which is never legitimately negative). */
    fun nonNegative(value: Double?, digits: Int): String {
        if (value == null || value < 0) return "–"
        return String.format(Locale.US, "%.${digits}f", value)
    }

    /**
     * Rounds to the nearest whole number for display, guarded via
     * [ConfigCoerce.int] (Task 4's guarded Double->Int helper: rejects NaN/
     * infinite/out-of-Int-range rather than `Double.toInt()`'s silent
     * saturation to 0/Int.MAX_VALUE) — reused here rather than reinventing a
     * second guard, per this task's brief.
     */
    fun roundedOrDash(value: Double?): String {
        value ?: return "–"
        return ConfigCoerce.int(value)?.toString() ?: "–"
    }

    /** RN: `heading != null && heading >= 0 ? Math.round(heading)+'°' : '–'`. */
    fun heading(value: Double?): String {
        if (value == null || value < 0) return "–"
        val rounded = roundedOrDash(value)
        return if (rounded == "–") "–" else "$rounded°"
    }

    fun isMoving(value: Boolean?): String = when (value) {
        null -> "–"
        true -> "yes"
        false -> "no"
    }

    /** RN's `EventCell`: `"<identifier> · <ACTION>"` for a geofence point, else the raw event name (or a dash). */
    fun eventLabel(point: Point): String {
        if (point.event != "geofence") return point.event ?: "-"
        val identifier = point.geofence?.identifier ?: "geofence"
        val action = point.geofence?.action?.uppercase() ?: return identifier
        return "$identifier · $action"
    }
}
