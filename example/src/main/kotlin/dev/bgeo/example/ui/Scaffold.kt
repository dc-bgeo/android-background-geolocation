package dev.bgeo.example.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import dev.bgeo.example.R

enum class ExampleTab(val labelRes: Int, val icon: ImageVector) {
    MAP(R.string.tab_map, Icons.Filled.Map),
    LOGS(R.string.tab_logs, Icons.AutoMirrored.Filled.List),
    SETTINGS(R.string.tab_settings, Icons.Filled.Settings),
}

/**
 * Three-tab console shell: Map, Logs, Settings (order/labels match the other
 * consoles). The chrome only — [content] renders the selected tab's screen,
 * wired in `ExampleApp.kt`.
 */
@Composable
fun ExampleScaffold(content: @Composable (ExampleTab) -> Unit) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val stateHolder = rememberSaveableStateHolder()
    val tabs = ExampleTab.entries

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selected == index,
                        onClick = { selected = index },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.labelRes)) },
                    )
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Only the selected tab is composed, so leaving a tab tears its
            // screen down completely. Without this holder every piece of Map
            // state — the applied history range, Follow, the layer toggles,
            // the drawn page — silently reset on a trip to Logs and back.
            // `SaveableStateProvider` keeps each tab's `rememberSaveable`
            // values keyed by tab while it is off screen.
            stateHolder.SaveableStateProvider(selected) {
                content(tabs[selected])
            }
        }
    }
}
