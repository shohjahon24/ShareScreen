package com.guard.sharescreen

import android.annotation.SuppressLint
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.app.ActivityCompat.startActivityForResult
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.getSystemService
import com.guard.sharescreen.ui.theme.ShareScreenTheme

class MainActivity : ComponentActivity() {

    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            ShareScreenTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) {
                    CameraSharingControl(
                        onStartCameraSharing = { startCameraSharing() },
                        onStopCameraSharing = { stopCameraSharing() }
                    )
                }
            }
        }
    }

    private fun startCameraSharing() {
        val intent = Intent(this, CameraSharingService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopCameraSharing() {
        val intent = Intent(this, CameraSharingService::class.java)
        stopService(intent)
    }
}

@Composable
fun CameraSharingControl(
    onStartCameraSharing: () -> Unit,
    onStopCameraSharing: () -> Unit
) {
    var isSharing by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (isSharing) "Camera Sharing Active" else "Camera Sharing Inactive",
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = {
            if (isSharing) onStopCameraSharing() else onStartCameraSharing()
            isSharing = !isSharing
        }) {
            Text(text = if (isSharing) "Stop Sharing" else "Start Sharing")
        }
    }
}
