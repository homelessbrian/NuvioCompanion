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

private val Ink = Color(0xFF0D0D0D)
private val Panel = Color(0xFF1A1A1A)
private val Muted = Color(0xFFB3B3B3)
private val Accent = Color(0xFFF5F5F5)

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
    var selectedNuvio by remember { mutableStateOf(DrawerSettings.selectedNuvio(context)) }
    var mode by remember { mutableStateOf(DrawerSettings.shortcutMode(context)) }
    var keyCode by remember { mutableIntStateOf(DrawerSettings.keyCode(context)) }
    var secondKeyCode by remember { mutableIntStateOf(DrawerSettings.secondKeyCode(context)) }
    var recording by remember { mutableStateOf(DrawerSettings.isRecording(context)) }
    var inputStatus by remember { mutableStateOf("Shortcut not configured") }
    var accessibilityEnabled by remember { mutableStateOf(isCompanionAccessibilityEnabled(context)) }
    var refresh by remember { mutableIntStateOf(0) }
    val nuvioApps = apps.filter { it.label.contains("nuvio", true) || it.packageName.contains("nuvio", true) }

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
    LaunchedEffect(refresh) { apps = AppCatalog.load(context, true) }
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
    val muted = Color(0xFF9EABC3)

    Surface(Modifier.fillMaxSize(), color = bg) {
        Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Your TV, your way.", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                        Text("Manage your remote shortcut and launcher behavior.", color = muted, fontSize = 12.sp)
                    }
                    Button(onClick = onSetLauncher, colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White)) {
                        Text("Set as Home launcher")
                    }
                }

                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(
                        Modifier.weight(1.15f).fillMaxHeight().background(panel, RoundedCornerShape(18.dp)).padding(16.dp),
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
                            colors = ButtonDefaults.buttonColors(containerColor = accent, disabledContainerColor = panel2)
                        ) { Text(if (recording) "Press a button on your remote…" else if (accessibilityEnabled) "Record remote button" else "Enable accessibility first") }
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
                        Modifier.weight(0.85f).fillMaxHeight().background(panel, RoundedCornerShape(18.dp)).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("HOME EXPERIENCE", color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("Nuvio-first mode", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text("Home opens your selected Nuvio app. The remote shortcut opens the app drawer over your current screen.", color = muted, fontSize = 11.sp)
                        Text("SELECTED APP", color = muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        if (nuvioApps.isEmpty()) {
                            Text("No Nuvio app detected. Install it, then refresh.", color = muted, fontSize = 11.sp)
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
                        Text("Open Companion normally from its app icon to return to settings. You can change the default Home app in Android settings.", color = muted, fontSize = 10.sp)
                    }
                }

                Row(
                    Modifier.fillMaxWidth().height(112.dp).background(panel, RoundedCornerShape(18.dp)).padding(15.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("APP DRAWER", color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text("Your apps and appearance are managed directly inside the drawer.", color = Color.White, fontSize = 13.sp)
                        Text("Open the drawer with your assigned remote shortcut to change position, visibility, favorites and layout.", color = muted, fontSize = 10.sp)
                    }
                    Button(onClick = { refresh++ }, colors = ButtonDefaults.buttonColors(containerColor = panel2)) { Text("Refresh apps") }
                    Text("${apps.size} apps found", color = muted, fontSize = 10.sp)
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
