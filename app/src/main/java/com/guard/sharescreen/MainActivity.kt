package com.guard.sharescreen

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.app.ActivityCompat.startActivityForResult
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.getSystemService
import com.guard.sharescreen.ui.theme.ShareScreenTheme

class MainActivity : ComponentActivity() {

    private lateinit var screenCaptureLauncher: ActivityResultLauncher<Intent>
    private lateinit var mediaProjectionManager: MediaProjectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // requestOverlayPermission(this)

        mediaProjectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        screenCaptureLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            Log.d("MainActivity", "onCreate: ${result.data?.data}")
            if (result.resultCode == RESULT_OK && result.data != null) {
                Log.d("MainActivity", "Screen capture permission granted ${result.data!!.data}")
                val intent = Intent(this, ScreenSharingService::class.java)
                intent.putExtra("resultCode", result.resultCode)
                intent.putExtra("data", result.data)
                ContextCompat.startForegroundService(this, intent)
            } else {
                Log.e("MainActivity", "Screen capture permission denied or cancelled")
            }
        }

        setContent {
            ShareScreenTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ScreenSharingControl({ startScreenCapture() }, { stopScreenCapture() })
                }
            }
        }
    }

    private fun startScreenCapture() {
        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
        screenCaptureLauncher.launch(captureIntent)
    }

    private fun stopScreenCapture() {
        val intent = Intent(this, ScreenSharingService::class.java)
        stopService(intent)
    }

    private fun requestOverlayPermission(context: Context) {
        if (!Settings.canDrawOverlays(context)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            startActivity(intent)
        }
    }
}


@Composable
fun ScreenSharingControl(
    onStartScreenSharing: () -> Unit, onStopScreenSharing: () -> Unit
) {
    var isSharing = remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (isSharing.value) "Screen Sharing Active" else "Screen Sharing Inactive",
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = {
            if (isSharing.value) {
                onStopScreenSharing()
            } else {
                onStartScreenSharing()
            }
            isSharing.value = !isSharing.value
        }) {
            Text(text = if (isSharing.value) "Stop Sharing" else "Start Sharing")
        }
    }
}