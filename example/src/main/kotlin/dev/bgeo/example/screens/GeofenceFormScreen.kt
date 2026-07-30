package dev.bgeo.example.screens

// Modal form for geofence CRUD. New fence: long-press on the map
// (`GeofenceRequest(identifier = null)`, `MapScreen.kt`'s `onGeofenceRequest`
// seam). Edit/delete: tap an existing fence's pin (`identifier` set). Every
// change goes to the SDK first, then the snapshot is mirrored to the console
// via `Geofences` (`Geofences.add`/`Geofences.remove`, both apply-before-
// persist — see that file's header).
//
// A Kotlin port of `react-native/example/src/screens/GeofenceFormScreen.tsx`;
// `ios/Example/Sources/Screens/GeofenceFormScreen.swift` is the same port for
// iOS. The identifier field is disabled when editing an existing geofence —
// matching both references — which is exactly why `MapRebuild.geofenceKey`
// (Task 5) keys on geometry rather than identifier: an edit can only ever
// change radius / notify flags / loitering delay.
//
// `radius`/`loiteringDelay` both parse through `ConfigCoerce.numberFromText`
// (Task 4's guarded Double coercion, reused rather than reinventing a third
// parser) — `"1e400".toDouble()` silently returns `+Infinity`, not null, and
// that helper already rejects NaN/infinite text. iOS only guarded `radius`;
// an infinite Android `loiteringDelay` reaching `JSONObject.put` would throw
// (`org.json` rejects non-finite doubles), which would otherwise surface as a
// confusing crash instead of the same "not a finite number" field error.

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bgeo.sdk.Geofence
import dev.bgeo.example.AppStore
import dev.bgeo.example.ConfigCoerce
import dev.bgeo.example.Geofences
import dev.bgeo.example.LogLevel
import dev.bgeo.example.LogLine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@Composable
fun GeofenceFormScreen(
    appStore: AppStore,
    geofences: Geofences,
    request: GeofenceRequest,
    onDismiss: () -> Unit,
) {
    val existing = remember(request) {
        request.identifier?.let { id -> appStore.geofences.value.firstOrNull { it.identifier == id } }
    }
    val latitude = existing?.latitude ?: request.latitude
    val longitude = existing?.longitude ?: request.longitude

    var identifier by remember(request) { mutableStateOf(existing?.identifier ?: "") }
    var radiusText by remember(request) { mutableStateOf(displayNumber(existing?.radius ?: 200.0)) }
    var notifyOnEntry by remember(request) { mutableStateOf(existing?.notifyOnEntry ?: true) }
    var notifyOnExit by remember(request) { mutableStateOf(existing?.notifyOnExit ?: true) }
    var notifyOnDwell by remember(request) { mutableStateOf(existing?.notifyOnDwell ?: false) }
    var loiteringDelayText by remember(request) {
        mutableStateOf(existing?.loiteringDelay?.let { displayNumber(it) } ?: "")
    }
    var busy by remember(request) { mutableStateOf(false) }
    var error by remember(request) { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()

    fun onSave() {
        val trimmedIdentifier = identifier.trim()
        val radius = ConfigCoerce.numberFromText(radiusText, isIntKind = false) as? Double
        if (trimmedIdentifier.isEmpty() || radius == null || radius <= 0) {
            error = "identifier and a positive radius are required"
            return
        }
        val trimmedLoitering = loiteringDelayText.trim()
        var loiteringDelay: Double? = null
        if (trimmedLoitering.isNotEmpty()) {
            val parsed = ConfigCoerce.numberFromText(trimmedLoitering, isIntKind = false) as? Double
            if (parsed == null) {
                error = "loitering delay must be a finite number"
                return
            }
            loiteringDelay = parsed
        }

        busy = true
        error = null
        scope.launch {
            try {
                geofences.add(
                    Geofence(
                        identifier = trimmedIdentifier,
                        radius = radius,
                        latitude = latitude,
                        longitude = longitude,
                        notifyOnEntry = notifyOnEntry,
                        notifyOnExit = notifyOnExit,
                        notifyOnDwell = notifyOnDwell,
                        loiteringDelay = loiteringDelay,
                        extras = null,
                    ),
                )
                appStore.appendLog(
                    LogLine(ts = isoNow(), level = LogLevel.INFO, event = "addGeofence", message = "$trimmedIdentifier r=${displayNumber(radius)}m"),
                )
                onDismiss()
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } finally {
                busy = false
            }
        }
    }

    fun onDelete() {
        val fence = existing ?: return
        busy = true
        scope.launch {
            try {
                geofences.remove(fence.identifier)
                appStore.appendLog(LogLine(ts = isoNow(), level = LogLevel.INFO, event = "removeGeofence", message = fence.identifier))
                onDismiss()
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } finally {
                busy = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(if (existing != null) "Edit geofence" else "New geofence", style = MaterialTheme.typography.titleMedium)
        Text(
            String.format(Locale.US, "%.6f, %.6f", latitude, longitude),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )

        Text("Identifier", style = MaterialTheme.typography.labelMedium)
        OutlinedTextField(
            value = identifier,
            onValueChange = { identifier = it },
            enabled = existing == null,
            singleLine = true,
            placeholder = { Text("home") },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp),
        )

        Text("Radius (m)", style = MaterialTheme.typography.labelMedium)
        OutlinedTextField(
            value = radiusText,
            onValueChange = { radiusText = it },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp),
        )

        SwitchRow("Notify on ENTER", notifyOnEntry) { notifyOnEntry = it }
        SwitchRow("Notify on EXIT", notifyOnExit) { notifyOnExit = it }
        SwitchRow("Notify on DWELL", notifyOnDwell) { notifyOnDwell = it }

        if (notifyOnDwell) {
            Text("Loitering delay (ms)", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp))
            OutlinedTextField(
                value = loiteringDelayText,
                onValueChange = { loiteringDelayText = it },
                singleLine = true,
                placeholder = { Text("30000") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }

        Button(
            onClick = ::onSave,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        ) {
            Text(if (busy) "Saving…" else "Save")
        }
        if (existing != null) {
            Button(
                onClick = ::onDelete,
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) {
                Text("Delete")
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

/** `200.0` displays as `"200"`, matching RN's `String(200)`; a genuine fraction keeps its digits. */
private fun displayNumber(value: Double): String =
    if (!value.isInfinite() && !value.isNaN() && value == Math.floor(value)) value.toLong().toString() else value.toString()

private fun isoNow(): String {
    val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    format.timeZone = TimeZone.getTimeZone("UTC")
    return format.format(Date())
}
