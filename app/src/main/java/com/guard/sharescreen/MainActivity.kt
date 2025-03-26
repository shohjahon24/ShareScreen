package com.guard.sharescreen

import android.annotation.SuppressLint
import android.app.Activity
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
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.app.ActivityCompat.startActivityForResult
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.getSystemService
import androidx.lifecycle.lifecycleScope
import com.guard.sharescreen.ui.theme.ShareScreenTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var screenCaptureLauncher: ActivityResultLauncher<Intent>

    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        screenCaptureLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                val data = result.data
                if (data != null) {
                    stopSharingService()
                    val intent = Intent(this, UniversalSharingService::class.java)
                    intent.putExtra("mode", "screen")
                    intent.putExtra("resultCode", result.resultCode)
                    intent.putExtra("data", data)
                    CoroutineScope(Dispatchers.IO).launch {
                        delay(1000)
                        ContextCompat.startForegroundService(this@MainActivity, intent)
                    }
                }
            }
        }

        setContent {
            ShareScreenTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) {
                    SharingControlUI(onStartFrontCamera = {
                        startCameraSharing("front")
                    }, onStartBackCamera = {
                        startCameraSharing("back")
                    }, onStartScreen = {
                        val captureIntent = projectionManager.createScreenCaptureIntent()
                        screenCaptureLauncher.launch(captureIntent)
                    }, onStopSharing = {
                        stopSharingService()
                    })
                }
            }
        }
    }

    private fun stopSharingService() {
        Intent(this, UniversalSharingService::class.java).apply {
            putExtra("mode", "stop")
            stopService(this)
        }
    }

    private fun startCameraSharing(mode: String) {
        stopSharingService()

        val intent = Intent(this, UniversalSharingService::class.java)
        intent.putExtra("mode", mode)

        CoroutineScope(Dispatchers.IO).launch {
            delay(1000)
            ContextCompat.startForegroundService(this@MainActivity, intent)
        }
    }
}

@Composable
fun SharingControlUI(
    onStartFrontCamera: () -> Unit,
    onStartBackCamera: () -> Unit,
    onStartScreen: () -> Unit,
    onStopSharing: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Button(onClick = onStartFrontCamera) {
            Text("Start Front Camera")
        }
        Spacer(modifier = Modifier.height(12.dp))
        Button(onClick = onStartBackCamera) {
            Text("Start Back Camera")
        }
        Spacer(modifier = Modifier.height(12.dp))
        Button(onClick = onStartScreen) {
            Text("Start Screen Sharing")
        }
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onStopSharing,
            colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
        ) {
            Text("Stop Sharing", color = Color.White)
        }
    }
}

