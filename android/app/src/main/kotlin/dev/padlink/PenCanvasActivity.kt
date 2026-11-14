// PenCanvasActivity.kt: landscape, immersive, always-on S Pen tablet canvas.
package dev.padlink

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import dev.padlink.service.ConnectionService
import dev.padlink.ui.DisplayTuning
import dev.padlink.ui.MainViewModel
import dev.padlink.ui.PenCanvasScreen
import dev.padlink.ui.theme.PadLinkTheme

/** Full-screen pen surface; orientation and theme come from the manifest. */
class PenCanvasActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    /** Applies immersive mode and the highest refresh rate, then shows the canvas. */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DisplayTuning.enterImmersive(this)
        DisplayTuning.requestHighRefresh(this)
        ConnectionService.start(this)
        setContent {
            PadLinkTheme {
                PenCanvasScreen(viewModel = viewModel, onClose = ::finish)
            }
        }
    }

    /** Re-hides system bars after they were revealed by a swipe or a dialog. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) DisplayTuning.enterImmersive(this)
    }
}
