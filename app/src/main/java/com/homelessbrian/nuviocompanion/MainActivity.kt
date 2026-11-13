package com.homelessbrian.nuviocompanion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.homelessbrian.nuviocompanion.remote.RemoteKeyAccessibilityService

private val Ink = Color(0xFF0D0D0D)
private val Panel = Color(0xFF1A1A1A)
private val Muted = Color(0xFFB3B3B3)
private val Accent = Color(0xFFF5F5F5)

/**
 * Companion settings. Opened from the app icon, the Companion home screen, or the drawer's
 * "Companion Settings" tile. Home handling lives in [HomeActivity].
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Ink, surface = Panel)) {
                CompanionSettings(
                    onAccessibility = ::openAccessibilitySettings,
                    onSetLauncher = { HomeSetup.openHomePicker(this) },
                    onAndroidSettings = { HomeSetup.openAndroidSettings(this) }
                )
            }
        }
    }

    private fun openAccessibilitySettings() {
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    }
}

@Composable
private fun CompanionSettings(onAccessibility: () -> Unit, onSetLauncher: () -> Unit, onAndroidSettings: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var homeRedirect by remember { mutableStateOf(DrawerSettings.homeRedirectEnabled(context)) }
    var apps by remember { mutableStateOf(AppCatalog.load(context, true)) }
    var selectedNuvio by remember { mutableStateOf(DrawerSettings.selectedNuvio(context)) }
    var mode by remember { mutableStateOf(DrawerSettings.shortcutMode(context)) }
    var keyCode by remember { mutableIntStateOf(DrawerSettings.keyCode(context)) }
    var secondKeyCode by remember { mutableIntStateOf(DrawerSettings.secondKeyCode(context)) }
    var recording by remember { mutableStateOf(DrawerSettings.isRecording(context)) }
    var inputStatus by remember { mutableStateOf("Shortcut not configured") }
    var accessibilityEnabled by remember { mutableStateOf(isCompanionAccessibilityEnabled(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val nuvioApps = apps.filter { it.label.contains("nuvio", true) || it.packageName.contains("nuvio", true) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                apps = AppCatalog.load(context, true)
                selectedNuvio = DrawerSettings.selectedNuvio(context)
                homeRedirect = DrawerSettings.homeRedirectEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                when (i?.action) {
                    RemoteKeyAccessibilityService.ACTION_SHORTCUT_RECORDED -> {
                        keyCode = DrawerSettings.keyCode(context)
                        secondKeyCode = DrawerSettings.secondKeyCode(context)
                        recording = false
                    }
                    RemoteKeyAccessibilityService.ACTION_SHORTCUT_TRIGGERED -> inputStatus = "Shortcut activated · opening drawer"
                    RemoteKeyAccessibilityService.ACTION_OVERLAY_STATUS -> inputStatus = i.getStringExtra(RemoteKeyAccessibilityService.EXTRA_STATUS) ?: "Drawer status unavailable"
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(RemoteKeyAccessibilityService.ACTION_SHORTCUT_RECORDED)
            addAction(RemoteKeyAccessibilityService.ACTION_SHORTCUT_TRIGGERED)
            addAction(RemoteKeyAccessibilityService.ACTION_OVERLAY_STATUS)
        }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") context.registerReceiver(receiver, filter)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    LaunchedEffect(Unit) {
        while (true) {
            accessibilityEnabled = isCompanionAccessibilityEnabled(context)
            kotlinx.coroutines.delay(1500)
        }
    }
    BackHandler(enabled = recording) {
        DrawerSettings.setRecording(context, false)
        recording = false
    }

    val bg = Color(0xFF0D0D0D)
    val panel = Color(0xFF1A1A1A)
    val panel2 = Color(0xFF222222)
    val accent = Color(0xFFF5F5F5)
    val muted = Color(0xFFB3B3B3)

    Surface(Modifier.fillMaxSize(), color = bg) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Your TV, your way.", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                        Text("Manage your remote shortcut and launcher behavior.", color = muted, fontSize = 12.sp)
                    }
                }

                Row(Modifier.fillMaxWidth().heightIn(min = 390.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(
                        Modifier.weight(1.15f).heightIn(min = 390.dp).background(panel, RoundedCornerShape(18.dp)).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("REMOTE SHORTCUT", color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("Control your drawer from the remote", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text("1. ENABLE ACCESSIBILITY", color = muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (accessibilityEnabled) "Remote access is enabled" else "Required before you can record a remote button",
                            color = if (accessibilityEnabled) Color(0xFF4CAF50) else Color(0xFFFFB74D),
                            fontSize = 11.sp
                        )
                        OutlinedButton(onClick = onAccessibility) {
                            Text(if (accessibilityEnabled) "Accessibility settings" else "Enable accessibility")
                        }
                        Text("2. CONFIGURE YOUR SHORTCUT", color = muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text("Shortcut type", color = muted, fontSize = 11.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ShortcutMode.entries.forEach { option ->
                                Text(
                                    when (option) { ShortcutMode.SINGLE -> "Single"; ShortcutMode.DOUBLE -> "Double"; ShortcutMode.LONG -> "Long"; ShortcutMode.COMBINATION -> "Combo" },
                                    color = if (mode == option) Color.White else muted,
                                    fontSize = 10.sp,
                                    modifier = Modifier.background(if (mode == option) Color(0xFF2D2D2D) else panel2, RoundedCornerShape(8.dp))
                                        .clickable { mode = option; DrawerSettings.setShortcutMode(context, option) }
                                        .focusable().padding(horizontal = 9.dp, vertical = 9.dp)
                                )
                            }
                        }
                        Text(if (keyCode == 0) "Primary button · Not assigned" else "Primary button · Button $keyCode", color = Color.White, fontSize = 12.sp)
                        if (mode == ShortcutMode.COMBINATION) Text(if (secondKeyCode == 0) "Second button · Not assigned" else "Second button · Button $secondKeyCode", color = muted, fontSize = 11.sp)
                        Button(
                            enabled = accessibilityEnabled && !recording,
                            onClick = { DrawerSettings.setRecordingTarget(context, "primary"); DrawerSettings.setRecording(context, true); recording = true },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp), colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color(0xFF111111), disabledContainerColor = panel2, disabledContentColor = muted)
                        ) { Text(if (recording) "Press a button on your remote…" else if (accessibilityEnabled) "Record remote button" else "Enable accessibility first", maxLines = 1, fontSize = 13.sp) }
                        if (mode == ShortcutMode.COMBINATION) {
                            TextButton(
                                enabled = accessibilityEnabled && !recording,
                                onClick = { DrawerSettings.setRecordingTarget(context, "secondary"); DrawerSettings.setRecording(context, true); recording = true }
                            ) { Text("Record second button") }
                        }
                        Text(inputStatus, color = Color(0xFFB3B3B3), fontSize = 11.sp)
                        Text("Some device firmware intercepts remote keys before Android accessibility can receive them.", color = muted, fontSize = 10.sp)
                    }

                    Column(
                        Modifier.weight(0.85f).heightIn(min = 390.dp).background(panel, RoundedCornerShape(18.dp)).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("HOME EXPERIENCE", color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("Optional Home behavior", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text("If you choose Companion as your device's Home app, Home opens your selected Nuvio app. Backing out of Nuvio shows the Companion home screen, so you can always reach settings. This is optional; the remote shortcut opens the drawer without changing your Home app.", color = muted, fontSize = 11.sp)
                        OutlinedButton(onClick = onSetLauncher, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                            Text("Choose Home app", maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = {
                                homeRedirect = !homeRedirect
                                DrawerSettings.setHomeRedirectEnabled(context, homeRedirect)
                                if (homeRedirect) {
                                    DrawerSettings.setHomeRedirectPaused(context, false)
                                    DrawerSettings.setHomeQuickReturns(context, 0)
                                }
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                        ) {
                            Text(if (homeRedirect) "Home opens Nuvio · On" else "Home opens Nuvio · Off", maxLines = 1)
                        }
                        Text("SELECTED APP", color = muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        if (nuvioApps.isEmpty()) {
                            Text("No Nuvio app detected. Install one, then reopen Companion.", color = muted, fontSize = 11.sp)
                        } else {
                            nuvioApps.take(4).forEach { app ->
                                Row(
                                    Modifier.fillMaxWidth().background(panel2, RoundedCornerShape(10.dp)).clickable {
                                        selectedNuvio = app.packageName
                                        DrawerSettings.setSelectedNuvio(context, app.packageName)
                                    }.focusable().padding(9.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Image(app.icon.toBitmap(64,64).asImageBitmap(), app.label, Modifier.size(30.dp))
                                    Text(app.label, color = Color.White, modifier = Modifier.weight(1f).padding(start = 9.dp), fontSize = 11.sp)
                                    Text(if (selectedNuvio == app.packageName) "✓" else "○", color = accent)
                                }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Text("LAUNCHER RECOVERY", color = muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text("Back out of Nuvio to reach the Companion home screen, or use the Android Settings tile in the app drawer. On many Google TV remotes, holding Home opens the system panel with Settings. You can also go to Android Settings → Apps → Nuvio Companion.", color = muted, fontSize = 10.sp)
                        OutlinedButton(onClick = onAndroidSettings, modifier = Modifier.fillMaxWidth()) {
                            Text("Open Android Settings", maxLines = 1)
                        }
                    }
                }

            }
        }
    }
}

@Composable
private fun PanelCard(title: String, description: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(18.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold); Text(description, color = Muted, fontSize = 13.sp); content()
    }
}
private fun isCompanionAccessibilityEnabled(context: Context): Boolean {
    val expected = ComponentName(context, RemoteKeyAccessibilityService::class.java).flattenToString()
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
}

@Composable
private fun SelectableRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(if (selected) Color(0xFF303030) else Color.Transparent, RoundedCornerShape(10.dp)).clickable(onClick = onClick).focusable().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick); Spacer(Modifier.width(10.dp)); Text(label, color = Color.White)
    }
}
