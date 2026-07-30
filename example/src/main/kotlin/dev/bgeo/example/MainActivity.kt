package dev.bgeo.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.bgeo.sdk.BackgroundGeolocation
import dev.bgeo.example.ui.ExampleScaffold

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Wires the SDK to this process. Task 1 only proves attach() doesn't
        // crash on a real device; ready()/start() and the rest of the console
        // wiring land in later tasks.
        BackgroundGeolocation.attach(this)

        setContent { ExampleScaffold() }
    }
}
