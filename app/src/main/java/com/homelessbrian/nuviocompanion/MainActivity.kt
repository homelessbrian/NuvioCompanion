package com.homelessbrian.nuviocompanion

import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { CompanionSettings(
            onEnableAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            onSetLauncher = { requestLauncherRole() }
        ) } }
    }
    private fun requestLauncherRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = getSystemService(RoleManager::class.java)
            if (roles.isRoleAvailable(RoleManager.ROLE_HOME) && !roles.isRoleHeld(RoleManager.ROLE_HOME))
                startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_HOME), 410)
        } else startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
    }
}

@Composable
private fun CompanionSettings(onEnableAccessibility: () -> Unit, onSetLauncher: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var recording by remember { mutableStateOf(DrawerSettings.isRecording(context)) }
    var keyCode by remember { mutableIntStateOf(DrawerSettings.keyCode(context)) }
    var position by remember { mutableStateOf(DrawerSettings.position(context)) }
    var apps by remember { mutableStateOf(AppCatalog.load(context)) }
    var hidden by remember { mutableStateOf(DrawerSettings.hidden(context)) }
    DisposableEffect(Unit) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, i: Intent?) {
                keyCode = DrawerSettings.keyCode(context); recording = false
            }
        }
        val filter = android.content.IntentFilter(RemoteKeyAccessibilityService.ACTION_SHORTCUT_RECORDED)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") context.registerReceiver(receiver, filter)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    LaunchedEffect(refresh) { apps = AppCatalog.load(context); hidden = DrawerSettings.hidden(context) }
    BackHandler(enabled = recording) { DrawerSettings.setRecording(context, false); recording = false }
    Surface(Modifier.fillMaxSize(), color = Color(0xFF10131B)) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 44.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text("Nuvio Companion", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text("App drawer and remote controls", color = Color(0xFFADB5C8))
            }
            item { SettingsCard("Set as device launcher", "Make Nuvio Companion the Android TV Home app.") { Button(onClick = onSetLauncher) { Text("Set as launcher") } } }
            item {
                SettingsCard("Remote shortcut", "Enable the accessibility service, then record a button from your remote.") {
                    Text(if (keyCode == 0) "No shortcut configured" else "Configured key: $keyCode", color = Color.White)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = onEnableAccessibility) { Text("Accessibility settings") }
                        Button(onClick = { DrawerSettings.setRecording(context, true); recording = true }) { Text(if (recording) "Listening…" else "Record remote button") }
                    }
                    if (recording) Text("Press the remote button you want to use.", color = Color(0xFF9EACFF))
                }
            }
            item {
                Text("Drawer position", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("All five positions are available.", color = Color(0xFFADB5C8))
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("BOTTOM" to "Bottom", "TOP" to "Top", "LEFT" to "Left", "RIGHT" to "Right", "CENTER" to "Center").forEach { (value, label) ->
                        Row(Modifier.fillMaxWidth().background(if (position == value) Color(0xFF263B68) else Color(0xFF202635), RoundedCornerShape(12.dp)).clickable { position = value; DrawerSettings.setPosition(context, value) }.focusable().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = position == value, onClick = { position = value; DrawerSettings.setPosition(context, value) })
                            Spacer(Modifier.width(8.dp)); Text(label, color = Color.White)
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column { Text("Apps in drawer", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold); Text("Choose which installed apps appear.", color = Color(0xFFADB5C8)) }
                    Button(onClick = { refresh++ }) { Text("Refresh") }
                }
            }
            items(apps, key = { it.packageName }) { app ->
                Row(Modifier.fillMaxWidth().background(Color(0xFF202635), RoundedCornerShape(12.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(app.icon.toBitmap(72, 72).asImageBitmap(), app.label, Modifier.size(42.dp))
                    Spacer(Modifier.width(14.dp)); Text(app.label, color = Color.White, modifier = Modifier.weight(1f))
                    Switch(checked = app.packageName !in hidden, onCheckedChange = { show ->
                        hidden = if (show) hidden - app.packageName else hidden + app.packageName
                        DrawerSettings.setHidden(context, hidden)
                    })
                }
            }
        }
    }
}
@Composable
private fun SettingsCard(title: String, description: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color(0xFF202635), RoundedCornerShape(16.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        Text(description, color = Color(0xFFADB5C8), fontSize = 13.sp); content()
    }
}
