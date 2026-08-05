package dev.bgeo.example.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.bgeo.example.AppStore
import dev.bgeo.example.ConfigCoerce
import dev.bgeo.example.ConfigField
import dev.bgeo.example.ConfigFieldOption
import dev.bgeo.example.ConfigFieldType
import dev.bgeo.example.ConfigSchema
import dev.bgeo.example.ConfigStore
import dev.bgeo.example.DeviceLink
import dev.bgeo.example.LinkState
import dev.bgeo.example.LogLevel
import com.bgeo.sdk.BackgroundGeolocation
import com.bgeo.sdk.State
import com.bgeo.sdk.destroyLocations
import com.bgeo.sdk.destroyLog
import com.bgeo.sdk.getCount
import com.bgeo.sdk.getLog
import com.bgeo.sdk.sync
import com.bgeo.sdk.uploadLog
import dev.bgeo.example.LogUploader
import dev.bgeo.example.ui.Mono
import kotlinx.coroutines.launch

/**
 * Settings — device link (registration code) and every working SDK config
 * key (applied immediately via [ConfigStore], persisted as overrides).
 * Section/field order mirrors `react-native/example/src/screens/
 * SettingsScreen.tsx` / `ios/Example/Sources/Screens/SettingsScreen.swift`.
 *
 * Logic lives in [ConfigSchema]/[ConfigStore] (both unit-tested); this file
 * stays thin by design — Compose composables are not unit-tested in this
 * module. Two rules this screen exists to enforce (see
 * `ConfigStore.setOverride`'s doc comment for why they matter):
 *  - A rejected value's error renders directly under the field that failed
 *    (never at the bottom of the scroll, where a rejection in an early
 *    section would be invisible) and is logged.
 *  - On rejection the field resyncs to the still-current stored value
 *    instead of continuing to show the rejected input — done here by
 *    keying the field's composable on [fieldErrorToken], which tears down
 *    and recreates its local draft state on every rejection of that key.
 */
@Composable
fun SettingsScreen(appStore: AppStore, configStore: ConfigStore, deviceLink: DeviceLink, logUploader: LogUploader) {
    val overrides by configStore.overrides.collectAsState()
    val link by appStore.link.collectAsState()
    val scope = rememberCoroutineScope()

    var fieldErrorKey by remember { mutableStateOf<String?>(null) }
    var fieldError by remember { mutableStateOf<String?>(null) }
    var fieldErrorToken by remember { mutableIntStateOf(0) }
    var resetError by remember { mutableStateOf<String?>(null) }

    // Through `LogUploader` (see `MapScreen`'s identical note): the SDK's
    // persisted log queue and `/device/logs` get these lines too, and the
    // credential scrub applies to every one of them.
    fun log(event: String, message: String, level: LogLevel) {
        logUploader.logEvent(event, level, message)
    }

    // Shared by an engine rejection (setValue's catch below) and a
    // client-side parse failure (ConfigFieldRow's onRejectParse) — both are
    // "this field's draft did not become the new value" and both need the
    // exact same treatment: error next to the field, logged, draft resynced
    // via rejectionToken. See this file's header for why that machinery
    // exists.
    fun rejectField(field: ConfigField, message: String) {
        fieldErrorToken += 1
        fieldErrorKey = field.key
        fieldError = message
        // Logged on rejection too — a dropped config change must be
        // visible in the log, not just next to the field.
        log("setConfig", "${field.key}: $message", LogLevel.ERROR)
    }

    fun setValue(field: ConfigField, raw: Any) {
        scope.launch {
            try {
                configStore.setOverride(field.key, raw)
                if (fieldErrorKey == field.key) {
                    fieldErrorKey = null
                    fieldError = null
                }
                log("setConfig", "${field.key}=$raw", LogLevel.INFO)
            } catch (e: Exception) {
                rejectField(field, e.message ?: "rejected")
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        LinkSection(
            link = link,
            deviceLink = deviceLink,
            onLinked = { message -> log("link", message, LogLevel.INFO) },
        )

        ConfigSchema.sections.forEach { section ->
            Text(
                section.title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
            )
            section.fields.forEach { field ->
                ConfigFieldRow(
                    field = field,
                    value = overrides[field.key] ?: field.default,
                    error = if (fieldErrorKey == field.key) fieldError else null,
                    rejectionToken = if (fieldErrorKey == field.key) fieldErrorToken else 0,
                    onChange = { setValue(field, it) },
                    onRejectParse = { message -> rejectField(field, message) },
                )
            }
        }

        resetError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        }
        Button(
            onClick = {
                scope.launch {
                    try {
                        configStore.reset()
                        resetError = null
                        log("setConfig", "reset to defaults", LogLevel.INFO)
                    } catch (e: Exception) {
                        resetError = e.message ?: "reset failed"
                        // Same rule as a rejected field (see rejectField): a
                        // dropped config change must show up in the Logs tab,
                        // not just next to the button that triggered it.
                        log("setConfig", "reset failed: ${e.message}", LogLevel.ERROR)
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 24.dp),
        ) {
            Text("Reset config to defaults")
        }

        StateSection(appStore = appStore, log = ::log)
    }
}

/**
 * The engine's state snapshot, the queue/log counters, and the SDK actions
 * that have no other home — a Kotlin port of
 * `ios/Example/Sources/Screens/SettingsScreen.swift`'s `StateSection`.
 *
 * `requestPermission`/`getCurrentPosition`/`start`/`stop` are deliberately NOT
 * here: they live on the Map screen on every console, so each call has exactly
 * one button in the app.
 *
 * `requestTemporaryFullAccuracy` is on the iOS screen and not here for a
 * harder reason — reduced accuracy is a CoreLocation concept, and the Android
 * SDK exposes no counterpart to ask about it. This is a platform difference,
 * not a missing port.
 */
@Composable
private fun StateSection(appStore: AppStore, log: (String, String, LogLevel) -> Unit) {
    val points by appStore.points.collectAsState()
    val scope = rememberCoroutineScope()

    var engineState by remember { mutableStateOf<State?>(null) }
    var queueCount by remember { mutableStateOf<Int?>(null) }
    var logCount by remember { mutableStateOf<Int?>(null) }
    var isMovingDraft by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<Map<String, ActionOutcome>>(emptyMap()) }
    var busy by remember { mutableStateOf<Set<String>>(emptySet()) }

    suspend fun refresh() {
        engineState = try {
            BackgroundGeolocation.getState()
        } catch (e: Exception) {
            null
        }
        queueCount = try {
            BackgroundGeolocation.getCount()
        } catch (e: Exception) {
            null
        }
        logCount = try {
            BackgroundGeolocation.getLog(LOG_COUNT_LIMIT).size
        } catch (e: Exception) {
            null
        }
    }

    // Every action reports its outcome in the same three places: inline under
    // its own button, in the Logs tab, and (on failure) as an error rather
    // than a success message — the same rule the config fields follow above.
    fun runAction(key: String, action: suspend () -> String) {
        if (key in busy) return
        busy = busy + key
        scope.launch {
            try {
                val message = action()
                results = results + (key to ActionOutcome(message, isError = false))
                log(key, message, LogLevel.INFO)
            } catch (e: Exception) {
                val message = e.message ?: e.toString()
                results = results + (key to ActionOutcome(message, isError = true))
                log(key, message, LogLevel.ERROR)
            }
            busy = busy - key
            refresh()
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(modifier = Modifier.padding(bottom = 24.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Engine state", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { scope.launch { refresh() } }) { Text("Refresh") }
        }

        engineState?.let { state ->
            // Absent keys are dropped rather than shown as "—": the two lists
            // this and the iOS screen render from are a documented SUPERSET of
            // what either engine may populate (see the State table in the
            // reference docs), so a missing row means "this engine doesn't
            // report that", not "the value is unknown".
            STATE_FIELDS.forEach { key ->
                state[key]?.let { value -> StateRow(key, describeStateValue(value)) }
            }
        }
        StateRow("upload queue", "${queueCount?.toString() ?: "—"} records")
        StateRow("log history", if (logCount != null && logCount!! >= LOG_COUNT_LIMIT) "$LOG_COUNT_LIMIT+ rows" else "${logCount?.toString() ?: "—"} rows")

        Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = isMovingDraft, onCheckedChange = { isMovingDraft = it })
            Spacer(Modifier.width(12.dp))
            Text("Moving (for Change pace)", style = MaterialTheme.typography.bodyMedium)
        }
        ActionButton("Change pace", "changePace", busy, results, ::runAction) {
            BackgroundGeolocation.changePace(isMovingDraft)
            "isMoving=$isMovingDraft"
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.weight(1f)) {
                ActionButton("Sync now", "sync", busy, results, ::runAction) {
                    "${BackgroundGeolocation.sync().size} records"
                }
            }
            Spacer(Modifier.width(12.dp))
            Box(modifier = Modifier.weight(1f)) {
                ActionButton("Destroy queue", "destroyLocations", busy, results, ::runAction, danger = true) {
                    "${BackgroundGeolocation.destroyLocations()} records"
                }
            }
        }

        ActionButton("Upload logs", "uploadLog", busy, results, ::runAction) {
            "${BackgroundGeolocation.uploadLog()} rows handed to the flusher"
        }
        ActionButton("Destroy log", "destroyLog", busy, results, ::runAction, danger = true) {
            "${BackgroundGeolocation.destroyLog()} rows"
        }
        ActionButton("Reset odometer", "resetOdometer", busy, results, ::runAction) {
            BackgroundGeolocation.resetOdometer()
            "odometer=0"
        }

        Row(modifier = Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Track buffer: ${points.size} pts ·", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { appStore.clearTrack() }) {
                Text("clear track", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private data class ActionOutcome(val message: String, val isError: Boolean)

/**
 * The state keys this screen renders, in display order.
 *
 * A superset of what any one engine populates — absent keys are dropped, not
 * shown as "—" (see the call site). The first block is the iOS console's list
 * minus its two CoreLocation-only entries (`sessionEngineActive`,
 * `serviceSessionActive`); the second is what the Android engine reports and
 * iOS does not (`BGGeoEngine.stateMap`). Of the first block, today's Android
 * engine populates only `enabled`/`isMoving`/`odometer`/`geofenceCount` — the
 * rest are listed so they appear on their own if the engine ever starts
 * reporting them.
 */
private val STATE_FIELDS = listOf(
    "enabled", "trackingActive", "isMoving", "odometer", "geofenceCount",
    "authorization", "lastRawFixAge", "lastAcceptedFixAge", "watchdogRecoveryCount",
    "trackingMode", "connected", "lastGeofenceError",
)

/** `getLog`'s ceiling for the "log history" counter — a count past this reads as "N+". */
private const val LOG_COUNT_LIMIT = 5000

/** Renders a raw `State` value; booleans as true/false, everything else via its own `toString`. */
private fun describeStateValue(value: Any): String = when (value) {
    is Boolean -> if (value) "true" else "false"
    else -> value.toString()
}

@Composable
private fun StateRow(key: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(key, style = MaterialTheme.typography.bodySmall, fontFamily = Mono, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = Mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ActionButton(
    title: String,
    key: String,
    busy: Set<String>,
    results: Map<String, ActionOutcome>,
    runAction: (String, suspend () -> String) -> Unit,
    danger: Boolean = false,
    action: suspend () -> String,
) {
    val running = key in busy
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Button(
            onClick = { runAction(key, action) },
            enabled = !running,
            colors = if (danger) {
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                )
            } else {
                ButtonDefaults.buttonColors()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (running) "$title…" else title)
        }
        results[key]?.let { outcome ->
            Text(
                outcome.message,
                style = MaterialTheme.typography.bodySmall,
                color = if (outcome.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LinkSection(link: LinkState, deviceLink: DeviceLink, onLinked: (String) -> Unit) {
    var serverUrl by remember(link.linked) { mutableStateOf(link.serverUrl) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.padding(bottom = 24.dp)) {
        Text("Debug console", style = MaterialTheme.typography.titleMedium)
        Text(
            "Create a registration code in the BGeo web console (Dashboard → Registration codes) and enter it here.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            enabled = !link.linked,
            singleLine = true,
            label = { Text("Server") },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        if (!link.linked) {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                singleLine = true,
                label = { Text("Registration code") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Button(
                enabled = !busy && code.replace("-", "").length >= 8,
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            var trimmed = serverUrl
                            while (trimmed.endsWith("/")) trimmed = trimmed.dropLast(1)
                            val result = deviceLink.link(serverUrl = trimmed, code = code)
                            onLinked("linked to console as ${result.deviceId}")
                            code = ""
                        } catch (e: Exception) {
                            error = e.message
                        }
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text(if (busy) "Linking…" else "Link device")
            }
        } else {
            Text(
                "🟢 Linked — device ${link.deviceId.orEmpty().take(8)}",
                modifier = Modifier.padding(top = 8.dp),
            )
            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        deviceLink.unlink()
                        onLinked("unlinked from console")
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text("Unlink")
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp)) }
    }
}

@Composable
private fun ConfigFieldRow(
    field: ConfigField,
    value: Any,
    error: String?,
    rejectionToken: Int,
    onChange: (Any) -> Unit,
    onRejectParse: (String) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(field.unit?.let { "${field.label} ($it)" } ?: field.label, style = MaterialTheme.typography.bodyMedium)
                field.hint?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            Spacer(modifier = Modifier.width(8.dp))
            when (field.type) {
                ConfigFieldType.BOOL -> Switch(checked = ConfigCoerce.bool(value) ?: false, onCheckedChange = { onChange(it) })
                ConfigFieldType.NUMBER -> key(rejectionToken) {
                    CommitField(
                        value = displayString(value),
                        keyboardType = KeyboardType.Number,
                        onCommit = { text ->
                            when (val decision = decideNumberCommit(text, isIntKind = field.default is Int)) {
                                is NumberCommitDecision.Accept -> onChange(decision.value)
                                is NumberCommitDecision.Reject -> onRejectParse(decision.message)
                            }
                        },
                    )
                }
                ConfigFieldType.STRING -> key(rejectionToken) {
                    CommitField(value = displayString(value), keyboardType = KeyboardType.Text, onCommit = { onChange(it) })
                }
                // ENUM renders BELOW this row instead — see [EnumSegmentedRow].
                ConfigFieldType.ENUM -> Unit
            }
        }
        if (field.type == ConfigFieldType.ENUM) {
            EnumSegmentedRow(
                options = field.options,
                isSelected = { optionValue -> matches(optionValue, value) },
                onSelect = { onChange(it) },
            )
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Commits on blur / submit — never per keystroke, since a change is a round trip through [ConfigStore] to the live engine. */
@Composable
private fun CommitField(value: String, keyboardType: KeyboardType, onCommit: (String) -> Unit) {
    var draft by remember { mutableStateOf(value) }
    // Tracks the draft as of the last commit — NOT [value]. [value] is a
    // parameter driven by the parent's `overrides` state and only catches up
    // to a just-committed change once the parent recomposes; `onDone` below
    // calls `focusManager.clearFocus()` right after committing, which fires
    // `onFocusChanged` SYNCHRONOUSLY, before that recomposition happens.
    // Comparing against `value` there would still see the pre-change
    // parameter and fire a second, duplicate `onCommit` for the same edit —
    // two `setConfig` round trips and two identical log lines for one
    // keyboard "Done". Comparing against `lastCommitted`, which is updated
    // immediately (not waiting on recomposition), makes the second check a
    // no-op.
    var lastCommitted by remember { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    fun commitIfChanged() {
        if (draft != lastCommitted) {
            lastCommitted = draft
            onCommit(draft)
        }
    }

    // Resync when [value] changes from OUTSIDE this field — the Reset button
    // is the case that matters: it drops every override, so the parent
    // re-renders with the schema default, but a `remember`ed draft would keep
    // displaying the old text forever. Found on the emulator: reset cleared
    // storage and the engine while "Distance filter" still read 50.
    //
    // Guarded on focus so it can never yank text out from under an edit in
    // progress: a live `setConfig` round trip completing mid-typing must not
    // rewrite the user's draft.
    LaunchedEffect(value) {
        if (!focused && value != lastCommitted) {
            draft = value
            lastCommitted = value
        }
    }

    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        keyboardActions = KeyboardActions(onDone = {
            commitIfChanged()
            focusManager.clearFocus()
        }),
        modifier = Modifier
            .width(140.dp)
            .onFocusChanged { state ->
                focused = state.isFocused
                if (!state.isFocused) commitIfChanged()
            },
    )
}

/**
 * Decision for a NUMBER field's committed draft text, pulled out of the
 * composable so it is unit-testable — this module has no Compose
 * instrumentation harness (see this file's header). Either an accepted
 * numeric value to push through [ConfigStore.setOverride], or an error to
 * show/log in place of that call — the parse-failure counterpart to an
 * engine rejection. `"1e400"` is the concrete case this exists for: it
 * parses as a Double, comes out infinite, and [ConfigCoerce.numberFromText]
 * correctly returns null for it — this turns that null into a decision
 * instead of it being silently dropped by the caller.
 */
internal sealed class NumberCommitDecision {
    data class Accept(val value: Any) : NumberCommitDecision()
    data class Reject(val message: String) : NumberCommitDecision()
}

internal fun decideNumberCommit(text: String, isIntKind: Boolean): NumberCommitDecision {
    val parsed = ConfigCoerce.numberFromText(text, isIntKind)
    return if (parsed != null) {
        NumberCommitDecision.Accept(parsed)
    } else {
        NumberCommitDecision.Reject("not a valid number: \"$text\"")
    }
}

/**
 * An enum field's options, as a full-width segmented control on its own row
 * under the label.
 *
 * Was a `Row` of `FilterChip`s sharing the label's row: with six options
 * (`logLevel`) the chips got whatever width the label left over — a few dp
 * each — so the last one wrapped a letter per line ("V/E/R/B", four lines
 * tall) and stretched the whole row with it, which is where the stray vertical
 * gap around that field came from. A segmented row splits the width evenly
 * between its buttons by construction, so no option can be squeezed out no
 * matter how many there are; `maxLines = 1` + `softWrap = false` is the
 * belt-and-braces guard that a label can never stack vertically again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EnumSegmentedRow(options: List<ConfigFieldOption>, isSelected: (Any) -> Boolean, onSelect: (Any) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = isSelected(option.value),
                onClick = { onSelect(option.value) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                // The default selected-state checkmark eats the width six
                // options can least afford; the fill already says which is on.
                icon = {},
            ) {
                Text(
                    option.label,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun matches(optionValue: Any, current: Any): Boolean = when (optionValue) {
    is String -> ConfigCoerce.string(current) == optionValue
    is Int -> ConfigCoerce.int(current) == optionValue
    is Double -> ConfigCoerce.double(current) == optionValue
    is Boolean -> ConfigCoerce.bool(current) == optionValue
    else -> false
}

/** Whole-number Doubles render without a trailing ".0" (e.g. distanceFilter's 10.0 shows as "10"). */
private fun displayString(value: Any): String = when (value) {
    is Double -> if (!value.isInfinite() && !value.isNaN() && value == Math.floor(value)) value.toLong().toString() else value.toString()
    else -> value.toString()
}
