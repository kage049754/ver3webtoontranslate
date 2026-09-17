package com.claude.webtoontranslator

import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.claude.webtoontranslator.ocr.OnlineTranslationManager
import com.claude.webtoontranslator.service.OverlayService
import com.claude.webtoontranslator.util.SettingsDataStore
import kotlinx.coroutines.launch

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
                try {
                    ContextCompat.startForegroundService(this, intent)
                    Toast.makeText(this, "Overlay starting…", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(
                        this,
                        "Couldn't start the overlay service: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                Toast.makeText(
                    this,
                    "Screen capture permission wasn't granted, so the overlay can't start.",
                    Toast.LENGTH_LONG
                ).show()
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
                MainScreen(
                    onStartOverlay = { startOverlayFlow() },
                    settingsDataStore = SettingsDataStore(this)
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (pendingStartAfterOverlayPermission) {
            // Some OEMs (Samsung/Xiaomi in particular) don't commit the overlay
            // permission instantly - canDrawOverlays() can briefly still report
            // false right as this screen resumes. Give it a moment before
            // giving up, instead of silently stalling here.
            checkOverlayPermissionWithRetry(attemptsLeft = 5)
        }
    }

    private fun checkOverlayPermissionWithRetry(attemptsLeft: Int) {
        if (Settings.canDrawOverlays(this)) {
            pendingStartAfterOverlayPermission = false
            requestNotificationPermissionThenProject()
            return
        }
        if (attemptsLeft <= 0) {
            pendingStartAfterOverlayPermission = false
            Toast.makeText(
                this,
                "\"Display over other apps\" isn't granted yet. Please enable it and tap Start Overlay again.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            checkOverlayPermissionWithRetry(attemptsLeft - 1)
        }, 400)
    }

    private fun startOverlayFlow() {
        Toast.makeText(this, "Checking permissions…", Toast.LENGTH_SHORT).show()
        if (!Settings.canDrawOverlays(this)) {
            pendingStartAfterOverlayPermission = true
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } catch (e: Exception) {
                pendingStartAfterOverlayPermission = false
                Toast.makeText(
                    this,
                    "Couldn't open overlay permission settings on this device: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
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
        try {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjectionLauncher.launch(manager.createScreenCaptureIntent())
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Couldn't start screen capture permission dialog: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}

@Composable
fun MainScreen(onStartOverlay: () -> Unit, settingsDataStore: SettingsDataStore) {
    val scope = rememberCoroutineScope()
    val mode by settingsDataStore.translationMode.collectAsState(initial = "offline")
    val targetLang by settingsDataStore.onlineTargetLanguage.collectAsState(initial = "en")
    var langMenuExpanded by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
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
                if (mode == "online")
                    "Auto-detects any language on screen and overlays a translation into your chosen language."
                else
                    "Reads Korean, Japanese, Chinese, or Spanish text on screen and overlays an English translation right on top of the original.",
                fontSize = 15.sp,
                color = Color(0xFFB0AAB8),
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            Spacer(modifier = Modifier.height(28.dp))

            // ---------- Mode toggle card ----------
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF2A2830), RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (mode == "online") "Online mode" else "Offline mode",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            if (mode == "online")
                                "Any language \u2192 your chosen target. Needs internet."
                            else
                                "Korean/Japanese/Chinese/Spanish \u2192 English. Works offline.",
                            fontSize = 12.sp,
                            color = Color(0xFF8A8391)
                        )
                    }
                    Switch(
                        checked = mode == "online",
                        onCheckedChange = { isOnline ->
                            scope.launch {
                                settingsDataStore.setTranslationMode(if (isOnline) "online" else "offline")
                            }
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF03DAC5))
                    )
                }

                if (mode == "online") {
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        "Translate to:",
                        fontSize = 13.sp,
                        color = Color(0xFFB0AAB8)
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    Box {
                        val currentLabel = OnlineTranslationManager.SUPPORTED_TARGET_LANGUAGES
                            .firstOrNull { it.first == targetLang }?.second ?: "English"

                        OutlinedButton(
                            onClick = { langMenuExpanded = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(currentLabel)
                        }

                        DropdownMenu(
                            expanded = langMenuExpanded,
                            onDismissRequest = { langMenuExpanded = false }
                        ) {
                            OnlineTranslationManager.SUPPORTED_TARGET_LANGUAGES.forEach { (code, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        langMenuExpanded = false
                                        scope.launch { settingsDataStore.setOnlineTargetLanguage(code) }
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        "Uses a free public translation service - may be slower or " +
                            "briefly unavailable compared to offline mode.",
                        fontSize = 11.sp,
                        color = Color(0xFF8A8391)
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

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
                    "permissions. Offline mode translates fully on-device; online mode " +
                    "sends the recognized text to a translation service over the internet.",
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
