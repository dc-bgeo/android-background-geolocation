package dev.bgeo.example.screens

// Logs screen — the same event stream and formatting as the web console's
// LogStream (ts / [LEVEL] / event / message / data), with a level filter,
// follow-tail and clear. A Kotlin port of
// `react-native/example/src/screens/LogsScreen.tsx`;
// `ios/Example/Sources/Screens/LogsScreen.swift` is the same port for iOS.
//
// JS/app lines stream live via `AppStore.logs` (fed by `LogUploader.logEvent`,
// wired to real call sites in a later task — see `LogUploader.kt`'s header).
// Native engine lines (dot-namespaced diagnostics, persisted by `logLevel`)
// are polled from the SDK's `getLog()` history and merged in by timestamp.
// All decision logic (the native-vs-app-authored filter, the numeric
// LogEntry.level -> LogLevel map, the merge+filter, the bounded time slice)
// lives in `LogsScreenLogic.kt` — see that file's header for why, and
// `LogsScreenLogicTest` for the coverage. This file is rendering only.

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bgeo.sdk.BackgroundGeolocation
import com.bgeo.sdk.getLog
import dev.bgeo.example.AppStore
import dev.bgeo.example.LogLevel
import dev.bgeo.example.LogLine
import dev.bgeo.example.ui.Mono
import dev.bgeo.example.ui.Palette
import dev.bgeo.example.ui.Scheme
import dev.bgeo.example.ui.ThemeColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** `LogsScreen.tsx`'s `NATIVE_POLL_MS`. */
private const val NATIVE_POLL_MS = 3000L

/** `LogsScreen.tsx`'s `NATIVE_FETCH_LIMIT`. */
private const val NATIVE_FETCH_LIMIT = 300

/** `LogsScreen.tsx`'s `LEVELS` chip row: `null` stands in for `'all'` — see `mergeAndFilterLogs`. */
private val LEVEL_OPTIONS: List<LogLevel?> = listOf(null) + LogLevel.entries

@Composable
fun LogsScreen(appStore: AppStore) {
    val colors = Palette.getValue(if (isSystemInDarkTheme()) Scheme.DARK else Scheme.LIGHT)
    val appLogs by appStore.logs.collectAsState()
    var level by remember { mutableStateOf<LogLevel?>(null) }
    var follow by remember { mutableStateOf(true) }
    var nativeLines by remember { mutableStateOf<List<LogLine>>(emptyList()) }
    val listState = rememberLazyListState()

    // `LogsScreen.tsx`'s native poll loop; cancelled automatically when this
    // composable leaves composition (LaunchedEffect's coroutine scope).
    LaunchedEffect(Unit) {
        while (true) {
            try {
                val entries = BackgroundGeolocation.getLog(NATIVE_FETCH_LIMIT)
                nativeLines = nativeLogEntries(entries).map(::logLineFromEntry)
            } catch (e: CancellationException) {
                // The composable left composition (or the coroutine scope
                // was otherwise cancelled) — let cancellation propagate
                // instead of swallowing it as a "fetch failed, retry next
                // tick" case.
                throw e
            } catch (e: Exception) {
                // Native log fetch failed this tick — keep the previous
                // lines and retry on the next poll, same as RN's
                // `.catch(() => null)`.
            }
            delay(NATIVE_POLL_MS)
        }
    }

    // `onScrollBeginDrag`'s Compose equivalent: a real user drag (not a
    // programmatic `animateScrollToItem`) disengages follow.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) follow = false
        }
    }

    val filtered = remember(appLogs, nativeLines, level) { mergeAndFilterLogs(appLogs, nativeLines, level) }

    LaunchedEffect(filtered.size, follow) {
        if (follow && filtered.isNotEmpty()) {
            listState.animateScrollToItem(filtered.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(colors.background)) {
        Header(
            level = level,
            onLevelSelected = { level = it },
            follow = follow,
            onToggleFollow = { follow = !follow },
            onClear = { appStore.clearLogs() },
            colors = colors,
        )
        Surface(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            shape = RoundedCornerShape(8.dp),
            color = colors.field,
        ) {
            if (filtered.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                    Text("waiting for events…", color = colors.placeholder, fontFamily = Mono, fontSize = MaterialTheme.typography.bodySmall.fontSize)
                }
            } else {
                LazyColumn(state = listState, contentPadding = PaddingValues(8.dp)) {
                    items(filtered.size) { index -> LogRow(filtered[index], colors) }
                }
            }
        }
    }
}

@Composable
private fun Header(
    level: LogLevel?,
    onLevelSelected: (LogLevel?) -> Unit,
    follow: Boolean,
    onToggleFollow: () -> Unit,
    onClear: () -> Unit,
    colors: ThemeColors,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // Scrollable, and weighted so it yields space rather than taking it:
        // the seven level chips plus follow/clear do not fit one row on a
        // phone. Found on the emulator — the unweighted row pushed "follow"
        // and "clear" off-screen entirely (both unreachable) and squashed the
        // last chip into a one-letter-per-line column.
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LEVEL_OPTIONS.forEach { option ->
                FilterChip(
                    selected = level == option,
                    onClick = { onLevelSelected(option) },
                    label = { Text(option?.name?.lowercase() ?: "all") },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = follow, onClick = onToggleFollow, label = { Text("follow") })
            Button(onClick = onClear, colors = ButtonDefaults.buttonColors(containerColor = colors.surfaceRaised, contentColor = colors.text)) {
                Text("clear")
            }
        }
    }
}

@Composable
private fun LogRow(line: LogLine, colors: ThemeColors) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            buildString {
                append(logTimeSlice(line.ts))
                append(" [")
                append(line.level.name)
                append("] ")
                append(line.event)
                line.message?.let { append(' '); append(it) }
                line.data?.let { append(' '); append(it.toString()) }
            },
            fontFamily = Mono,
            fontSize = MaterialTheme.typography.bodySmall.fontSize,
            color = levelColor(line.level, colors),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * `LogsScreen.tsx`'s `levelColor`, sourced from the palette so both themes
 * stay readable — `info` takes the accent (not the web console's green) so
 * the level and the event ink stay distinguishable, matching the RN
 * comment. Kept in this file (not `LogsScreenLogic.kt`): it returns a
 * Compose `Color`, which this module's harness can't exercise in a JVM unit
 * test — same reasoning `MapScreen.kt` gives for keeping its own
 * `geofenceColor`/`colorHex` colour lookups out of `MapRebuild.kt`.
 */
private fun levelColor(level: LogLevel, colors: ThemeColors): Color = when (level) {
    LogLevel.VERBOSE -> colors.placeholder
    LogLevel.DEBUG -> colors.textDim
    LogLevel.INFO -> colors.accentText
    LogLevel.WARN -> colors.warningText
    LogLevel.ERROR -> colors.dangerText
}
