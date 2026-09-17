package com.claude.webtoontranslator

import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.claude.webtoontranslator.service.OverlayService

class MainActivity : ComponentActivity() {

    private var pendingStartAfterOverlayPermission = false

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            requestMediaProjection()
        }

    private val mediaProjectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                val intent = Intent(this, OverlayService::class.java).apply {
                    putExtra(OverlayService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(OverlayService.EXTRA_RESULT_DATA, result.data)
                }
                ContextCompat.startForegroundService(this, intent)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFF6750A4),
                secondary = Color(0xFF03DAC5),
                background = Color(0xFF1C1B1F),
                surface = Color(0xFF1C1B1F)
            )) {
                MainScreen(onStartOverlay = { startOverlayFlow() })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (pendingStartAfterOverlayPermission && Settings.canDrawOverlays(this)) {
            pendingStartAfterOverlayPermission = false
            requestNotificationPermissionThenProject()
        }
    }

    private fun startOverlayFlow() {
        if (!Settings.canDrawOverlays(this)) {
            pendingStartAfterOverlayPermission = true
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }
        requestNotificationPermissionThenProject()
    }

    private fun requestNotificationPermissionThenProject() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        requestMediaProjection()
    }

    private fun requestMediaProjection() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(manager.createScreenCaptureIntent())
    }
}

@Composable
fun MainScreen(onStartOverlay: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(Color(0xFF6750A4), RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("訳", fontSize = 32.sp, color = Color.White, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                "Webtoon Translator",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                "Reads Korean, Japanese, Chinese, or Spanish text on screen and " +
                    "overlays an English translation right on top of the original.",
                fontSize = 15.sp,
                color = Color(0xFFB0AAB8),
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))

            InstructionRow(step = "1", text = "Tap \"Start Overlay\" below and grant the permissions.")
            InstructionRow(step = "2", text = "Open your webtoon/manga app or browser.")
            InstructionRow(step = "3", text = "Tap the floating \"訳\" button to translate.")
            InstructionRow(step = "4", text = "Tap it again anytime to clear and keep scrolling.")

            Spacer(modifier = Modifier.height(40.dp))

            Button(
                onClick = onStartOverlay,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4))
            ) {
                Text("Start Overlay", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                "The floating button and translations only appear over other apps " +
                    "after you grant the \"display over other apps\" and screen-capture " +
                    "permissions. Nothing is saved or uploaded — translation runs on-device.",
                fontSize = 12.sp,
                color = Color(0xFF8A8391),
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
    }
}

@Composable
fun InstructionRow(step: String, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .background(Color(0xFF03DAC5), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(step, fontSize = 13.sp, color = Color.Black, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(text, fontSize = 14.sp, color = Color(0xFFCFC9D3))
    }
}
