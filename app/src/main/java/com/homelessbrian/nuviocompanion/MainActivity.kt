package com.homelessbrian.nuviocompanion

import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.homelessbrian.nuviocompanion.remote.RemoteKeyAccessibilityService

private val Ink = Color(0xFF0D111B)
private val Panel = Color(0xFF1A2232)
private val Muted = Color(0xFF9BA9C2)
private val Accent = Color(0xFF8798FF)

class MainActivity : ComponentActivity() {
    private val homeRedirectPrefs by lazy { getSharedPreferences("companion_home_state", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleEntry(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleEntry(intent)
    }

    private fun handleEntry(launchIntent: Intent?) {
        val fromHome = launchIntent?.categories?.contains(Intent.CATEGORY_HOME) == true
        if (!fromHome) {
            // Opening the app icon is always an escape hatch back to settings.
            homeRedirectPrefs.edit().putBoolean("redirected_once", false).apply()
            showSettings()
            return
        }

        // Redirect to Nuvio only once. The next Home press opens Companion settings
        // instead of launching Nuvio again and creating an endless Home loop.
        val alreadyRedirected = homeRedirectPrefs.getBoolean("redirected_once", false)
        if (!alreadyRedirected && launchSelectedNuvio()) {
            homeRedirectPrefs.edit().putBoolean("redirected_once", true).apply()
            finish()
            return
        }

        homeRedirectPrefs.edit().putBoolean("redirected_once", false).apply()
        showSettings()
    }

    private fun launchSelectedNuvio(): Boolean =
        DrawerSettings.selectedNuvio(this)?.let { AppCatalog.launch(this, it) } ?: false
    private fun showSettings() {
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Ink, surface = Panel)) { CompanionSettings(::openAccessibilitySettings, ::requestHomeRole) } }
    }
    private fun openAccessibilitySettings() = startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    private fun requestHomeRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = getSystemService(RoleManager::class.java)
            if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME) && !roles.isRoleHeld(RoleManager.ROLE_HOME)) {
                try {
                    startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_HOME), 410)
                    return
                } catch (_: Exception) {
                    // Some TV firmware advertises the Home role but cannot open its chooser.
                }
            }
        }

        // Android TV and vendor firmware do not always expose ROLE_HOME.
        // Fall back to the system Home-app settings page instead of silently doing nothing.
        val settingsIntents = listOf(
            Intent(Settings.ACTION_HOME_SETTINGS),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        val settingsIntent = settingsIntents.firstOrNull { it.resolveActivity(packageManager) != null }
        if (settingsIntent != null) {
            try {
                startActivity(settingsIntent)
            } catch (_: Exception) {
                Toast.makeText(this, "Open Android Settings and look for Home app or Default launcher.", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(this, "Open Android Settings and look for Home app or Default launcher.", Toast.LENGTH_LONG).show()
        }
    }
}

@Composable
private fun CompanionSettings(onAccessibility: () -> Unit, onSetLauncher: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var apps by remember { mutableStateOf(AppCatalog.load(context, true)) }
    var hidden by remember { mutableStateOf(DrawerSettings.hidden(context)) }
    var favorites by remember { mutableStateOf(DrawerSettings.favorites(context)) }
    var selectedNuvio by remember { mutableStateOf(DrawerSettings.selectedNuvio(context)) }
    var position by remember { mutableStateOf(DrawerSettings.position(context)) }
    var mode by remember { mutableStateOf(DrawerSettings.shortcutMode(context)) }
    var keyCode by remember { mutableIntStateOf(DrawerSettings.keyCode(context)) }
    var secondKeyCode by remember { mutableIntStateOf(DrawerSettings.secondKeyCode(context)) }
    var recording by remember { mutableStateOf(DrawerSettings.isRecording(context)) }
    var refresh by remember { mutableIntStateOf(0) }
    var inputStatus by remember { mutableStateOf("Press a remote button here to test whether Android delivers it to Companion.") }
    var accessibilityEnabled by remember { mutableStateOf(isCompanionAccessibilityEnabled(context)) }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (i?.action == RemoteKeyAccessibilityService.ACTION_SHORTCUT_RECORDED) {
                    keyCode = DrawerSettings.keyCode(context); secondKeyCode = DrawerSettings.secondKeyCode(context); recording = false
                } else if (i?.action == RemoteKeyAccessibilityService.ACTION_INPUT_EVENT) {
                    val received = i.getIntExtra(RemoteKeyAccessibilityService.EXTRA_KEY_CODE, 0)
                    val mapped = i.getBooleanExtra(RemoteKeyAccessibilityService.EXTRA_MAPPED, false)
                    inputStatus = if (mapped) "Received key $received · assigned shortcut · Companion consumes it" else "Received key $received · not assigned · original action is left alone"
                }
            }
        }
        val filter = IntentFilter().apply { addAction(RemoteKeyAccessibilityService.ACTION_SHORTCUT_RECORDED); addAction(RemoteKeyAccessibilityService.ACTION_INPUT_EVENT) }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED) else @Suppress("DEPRECATION") context.registerReceiver(receiver, filter)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    LaunchedEffect(refresh) { apps = AppCatalog.load(context, true); hidden = DrawerSettings.hidden(context); favorites = DrawerSettings.favorites(context) }
    LaunchedEffect(Unit) {
        while (true) {
            accessibilityEnabled = isCompanionAccessibilityEnabled(context)
            kotlinx.coroutines.delay(1000)
        }
    }
    BackHandler(enabled = recording) { DrawerSettings.setRecording(context, false); recording = false }

    Surface(Modifier.fillMaxSize(), color = Ink) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 42.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item {
                Text("Nuvio Companion", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Text("A better way to move around your Android TV.", color = Muted, fontSize = 15.sp)
            }
            item {
                PanelCard("Home button behavior", "Set Companion as your device launcher. Home will then open your selected Nuvio app.") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onSetLauncher) { Text("Set as device launcher") }
                        Text("Nuvio-first mode", color = Accent, fontWeight = FontWeight.SemiBold)
                    }
                    Text("Choose the Nuvio installation Home should launch:", color = Muted)
                    val nuvioApps = apps.filter { it.label.contains("nuvio", true) || it.packageName.contains("nuvio", true) }
                    if (nuvioApps.isEmpty()) Text("No Nuvio app found yet. Install your Nuvio fork, then refresh this list.", color = Muted)
                    nuvioApps.forEach { app -> SelectableRow(app.label, selectedNuvio == app.packageName) { selectedNuvio = app.packageName; DrawerSettings.setSelectedNuvio(context, app.packageName) } }
                    Button(onClick = { refresh++ }) { Text("Refresh installed apps") }
                }
            }
            item {
                PanelCard("Remote shortcut & input handling", "Companion uses Android's accessibility key-event filter to take ownership of assigned buttons. Assigned key-down, repeat, and key-up events are consumed so the original action should not also fire when Android delivers them to the service.") {
                    Text(if (accessibilityEnabled) "SHORTCUT SERVICE · ENABLED" else "SHORTCUT SERVICE · NOT ENABLED", color = if (accessibilityEnabled) Color(0xFF78D6A0) else Color(0xFFFFB36B), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    if (!accessibilityEnabled) Text("The shortcut cannot work until Nuvio Companion's accessibility service is enabled in Android Settings.", color = Color(0xFFFFB36B), fontSize = 13.sp)
                    Text("INPUT TEST", color = Accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text(inputStatus, color = Color.White, fontSize = 13.sp)
                    Text("Try the button you plan to assign. If nothing appears, the device may be intercepting that key before Android exposes it to accessibility services.", color = Muted, fontSize = 12.sp)
                    Text(if (keyCode == 0) "No primary button recorded" else "Primary button · key code $keyCode", color = if (keyCode == 0) Muted else Accent)
                    Button(onClick = onAccessibility) { Text("Open accessibility settings") }
                    Text("Alternative handling is built into Companion's event-consumption path; there is no separate Android API that can universally override firmware-handled buttons. Some manufacturers expose their own alternative-input options, which Companion cannot switch on for other apps.", color = Muted, fontSize = 12.sp)
                    Button(onClick = { DrawerSettings.setRecordingTarget(context, "primary"); DrawerSettings.setRecording(context, true); recording = true }) { Text(if (recording && DrawerSettings.recordingTarget(context) == "primary") "Listening for primary…" else "Record primary button") }
                    if (mode == ShortcutMode.COMBINATION) {
                        Text(if (secondKeyCode == 0) "No second button recorded" else "Second button · key code $secondKeyCode", color = Muted)
                        Button(onClick = { DrawerSettings.setRecordingTarget(context, "secondary"); DrawerSettings.setRecording(context, true); recording = true }) { Text(if (recording && DrawerSettings.recordingTarget(context) == "secondary") "Listening for second…" else "Record second button") }
                    }
                    if (recording) Text("Press the remote button you want to use.", color = Accent)
                    Text("Shortcut type", color = Muted, fontWeight = FontWeight.SemiBold)
                    ShortcutMode.entries.forEach { option ->
                        SelectableRow(when(option) { ShortcutMode.SINGLE -> "Single press"; ShortcutMode.DOUBLE -> "Double press"; ShortcutMode.LONG -> "Long press"; ShortcutMode.COMBINATION -> "Button combination" }, mode == option) {
                            mode = option; DrawerSettings.setShortcutMode(context, option)
                        }
                    }
                }
            }
            item {
                PanelCard("Drawer placement", "Choose where the app drawer appears. Every layout uses the same installed-app library.") {
                    DrawerPosition.entries.forEach { option -> SelectableRow(option.name.lowercase().replaceFirstChar { it.uppercase() }, position == option) { position = option; DrawerSettings.setPosition(context, option) } }
                }
            }
            item {
                PanelCard("App library", "Choose what appears in the drawer. Favorites are shown first.") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("${apps.size} installed apps", color = Muted); TextButton(onClick = { refresh++ }) { Text("Refresh") }
                    }
                }
            }
            items(apps, key = { it.packageName }) { app ->
                Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(app.icon.toBitmap(72, 72).asImageBitmap(), app.label, Modifier.size(42.dp)); Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) { Text(app.label, color = Color.White, fontWeight = FontWeight.Medium); Text(app.packageName, color = Muted, fontSize = 11.sp) }
                    TextButton(onClick = { favorites = if (app.packageName in favorites) favorites - app.packageName else favorites + app.packageName; DrawerSettings.setFavorites(context, favorites) }) { Text(if (app.packageName in favorites) "★" else "☆", color = Accent, fontSize = 20.sp) }
                    Switch(checked = app.packageName !in hidden, onCheckedChange = { show -> hidden = if (show) hidden - app.packageName else hidden + app.packageName; DrawerSettings.setHidden(context, hidden) })
                }
            }
            item { Spacer(Modifier.height(20.dp)); Text("Nuvio Companion · Settings", color = Muted, fontSize = 12.sp) }
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
    Row(Modifier.fillMaxWidth().background(if (selected) Color(0xFF29365A) else Color.Transparent, RoundedCornerShape(10.dp)).clickable(onClick = onClick).focusable().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick); Spacer(Modifier.width(10.dp)); Text(label, color = Color.White)
    }
}
