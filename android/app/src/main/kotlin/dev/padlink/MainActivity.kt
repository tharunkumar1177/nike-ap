// MainActivity.kt: launcher activity hosting the Mouse screen; requests runtime permissions and starts the service.
package dev.padlink

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import dev.padlink.service.ConnectionService
import dev.padlink.transport.bt.BluetoothSupport
import dev.padlink.ui.DisplayTuning
import dev.padlink.ui.MainViewModel
import dev.padlink.ui.MouseScreen
import dev.padlink.ui.theme.PadLinkTheme

/** Shows the Mouse screen and keeps the connection service running. */
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            viewModel.onPermissionsChanged()
        }

    /** Sets up the window, starts the service, asks for missing permissions, and shows the UI. */
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        DisplayTuning.requestHighRefresh(this)
        ConnectionService.start(this)
        if (savedInstanceState == null) requestMissingPermissions()
        setContent {
            PadLinkTheme {
                MouseScreen(
                    viewModel = viewModel,
                    onRequestPermissions = ::requestMissingPermissions,
                    onOpenPenCanvas = { startActivity(Intent(this, PenCanvasActivity::class.java)) },
                )
            }
        }
    }

    /** Refreshes paired hosts when returning from system settings. */
    override fun onResume() {
        super.onResume()
        viewModel.refreshHosts()
    }

    /** Requests Bluetooth and notification permissions that are not yet granted. */
    private fun requestMissingPermissions() {
        val wanted = BluetoothSupport.requiredPermissions().toMutableList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) wanted += Manifest.permission.POST_NOTIFICATIONS
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }
}
