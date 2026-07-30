package dev.bgeo.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.bgeo.sdk.PermissionRequester

/**
 * The console's only Activity. It owns no SDK state: `attach()` and the
 * bootstrap run in `ExampleApplication` (see that file for why), and every
 * long-lived object comes from its [AppContainer].
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Must be constructed before this Activity reaches STARTED —
        // `registerForActivityResult` requires it (see PermissionRequester's
        // KDoc), which is why this is here and not inside a composable.
        val permissionRequester = PermissionRequester(this)
        val container = (application as ExampleApplication).container

        setContent { ExampleApp(container = container, permissionRequester = permissionRequester) }
    }
}
