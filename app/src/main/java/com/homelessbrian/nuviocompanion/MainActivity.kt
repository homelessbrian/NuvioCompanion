package com.homelessbrian.nuviocompanion

import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isHomeLaunch() && launchSelectedNuvio()) { finish(); return }
        showSettings()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isHomeLaunch() && launchSelectedNuvio()) finish() else showSettings()
    }

    private fun isHomeLaunch() = intent?.categories?.contains(Intent.CATEGORY_HOME) == true
    private fun launchSelectedNuvio(): Boolean {
        val selected = DrawerSettings.selectedNuvio(this) ?: return false
        return AppCatalog.launch(this, selected)
    }
    private fun showSettings() {
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Ink, surface = Panel)) { CompanionSettings(::openAccessibilitySettings, ::requestHomeRole) } }
    }
    private fun openAccessibilitySettings() = startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    private fun requestHomeRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = getSystemService(RoleManager::class.java)
            if (roles.isRoleAvailable(RoleManager.ROLE_HOME) && !roles.isRoleHeld(RoleManager.ROLE_HOME))
                startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_HOME), 410)
        } else startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
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
    var recording by remember { mutableStateOf(DrawerSettings.isRecording(context)) }
    var refresh by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { keyCode = DrawerSettings.keyCode(context); recording = false }
        }
        val filter = IntentFilter(RemoteKeyAccessibilityService.ACTION_SHORTCUT_RECORDED)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED) else @Suppress("DEPRECATION") context.registerReceiver(receiver, filter)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    LaunchedEffect(refresh) { apps = AppCatalog.load(context, true); hidden = DrawerSettings.hidden(context); favorites = DrawerSettings.favorites(context) }
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
                    nuvioApps.forEach { app ->
                        SelectableRow(app.label, selectedNuvio == app.packageName) {
                            selectedNuvio = app.packageName; DrawerSettings.setSelectedNuvio(context, app.packageName)
                        }
                    }
                    Button(onClick = { refresh++ }) { Text("Refresh installed apps") }
                }
            }
            item {
                PanelCard("Remote shortcut", "The shortcut runs above other apps. Android may restrict which remote keys can be intercepted.") {
                    Text(if (keyCode == 0) "No remote button recorded" else "Button recorded · key code $keyCode", color = if (keyCode == 0) Muted else Accent)
                    Button(onClick = onAccessibility) { Text("Open accessibility settings") }
                    Button(onClick = { DrawerSettings.setRecording(context, true); recording = true }) { Text(if (recording) "Listening for button…" else "Record remote button") }
                    if (recording) Text("Press the remote button you want to use.", color = Accent)
                    Text("Shortcut type", color = Muted, fontWeight = FontWeight.SemiBold)
                    ShortcutMode.entries.forEach { option ->
                        SelectableRow(when(option) { ShortcutMode.SINGLE -> "Single press"; ShortcutMode.DOUBLE -> "Double press"; ShortcutMode.LONG -> "Long press"; ShortcutMode.COMBINATION -> "Button combination" }, mode == option) {
                            mode = option; DrawerSettings.setShortcutMode(context, option)
                        }
                    }
                    if (mode == ShortcutMode.COMBINATION) Text("Combination mode requires two separately recorded keys; that setup is not enabled in this build yet.", color = Muted)
                }
            }
            item {
                PanelCard("Drawer placement", "Choose where the app drawer appears. Every layout uses the same installed-app library.") {
                    DrawerPosition.entries.forEach { option ->
                        SelectableRow(option.name.lowercase().replaceFirstChar { it.uppercase() }, position == option) {
                            position = option; DrawerSettings.setPosition(context, option)
                        }
                    }
                }
            }
            item {
                PanelCard("App library", "Choose what appears in the drawer. Favorites are shown first.") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("${apps.size} installed apps", color = Muted)
                        TextButton(onClick = { refresh++ }) { Text("Refresh") }
                    }
                }
            }
            items(apps, key = { it.packageName }) { app ->
                Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(app.icon.toBitmap(72, 72).asImageBitmap(), app.label, Modifier.size(42.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) { Text(app.label, color = Color.White, fontWeight = FontWeight.Medium); Text(app.packageName, color = Muted, fontSize = 11.sp) }
                    TextButton(onClick = {
                        favorites = if (app.packageName in favorites) favorites - app.packageName else favorites + app.packageName
                        DrawerSettings.setFavorites(context, favorites)
                    }) { Text(if (app.packageName in favorites) "★" else "☆", color = Accent, fontSize = 20.sp) }
                    Switch(checked = app.packageName !in hidden, onCheckedChange = { show ->
                        hidden = if (show) hidden - app.packageName else hidden + app.packageName
                        DrawerSettings.setHidden(context, hidden)
                    })
                }
            }
            item { Spacer(Modifier.height(20.dp)); Text("Nuvio Companion · Settings", color = Muted, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun PanelCard(title: String, description: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(18.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(description, color = Muted, fontSize = 13.sp)
        content()
    }
}
@Composable
private fun SelectableRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(if (selected) Color(0xFF29365A) else Color.Transparent, RoundedCornerShape(10.dp))
        .clickable(onClick = onClick).focusable().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick); Spacer(Modifier.width(10.dp)); Text(label, color = Color.White)
    }
}
