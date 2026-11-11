package com.homelessbrian.nuviocompanion

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

data class InstalledApp(
    val label: String,
    val packageName: String,
    val icon: Drawable
) {
    val looksLikeNuvio: Boolean
        get() = label.contains("nuvio", ignoreCase = true) ||
            packageName.contains("nuvio", ignoreCase = true)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF10131B)
                ) {
                    CompanionHome()
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun CompanionHome() {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var selectedTab by remember { mutableStateOf("All apps") }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        loading = true
        apps = withContext(Dispatchers.IO) { discoverLaunchableApps(context.packageManager) }
        loading = false
    }

    LaunchedEffect(Unit) { refresh() }

    val visibleApps = when (selectedTab) {
        "Nuvio forks" -> apps.filter { it.looksLikeNuvio }
        else -> apps
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 44.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Nuvio Companion", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text("Standalone Android TV launcher companion", color = Color(0xFFADB5C8), fontSize = 14.sp)
            }
            Text("EARLY PROTOTYPE", color = Color(0xFF9EACFF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("All apps", "Nuvio forks").forEach { tab ->
                Button(onClick = { selectedTab = tab }) { Text(tab) }
            }
            Button(onClick = { scope.launch { refresh() } }) {
                Text("Refresh list")
            }
        }

        Text(
            if (selectedTab == "Nuvio forks") "Detected Nuvio applications" else "Installed launchable applications",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold
        )

        when {
            loading -> Text("Scanning installed apps…", color = Color.White)
            visibleApps.isEmpty() -> Text(
                if (selectedTab == "Nuvio forks") "No Nuvio-labelled apps were detected." else "No launchable apps were detected.",
                color = Color(0xFFADB5C8)
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 150.dp),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(visibleApps, key = { it.packageName }) { app ->
                    Card(
                        onClick = {
                            val launch = context.packageManager.getLaunchIntentForPackage(app.packageName)
                            if (launch != null) {
                                context.startActivity(launch)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(142.dp).focusable(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF202635))
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Image(
                                bitmap = remember(app.packageName) {
                                    app.icon.toBitmap(88, 88).asImageBitmap()
                                },
                                contentDescription = app.label,
                                modifier = Modifier.size(58.dp)
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                app.label,
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1
                            )
                            if (app.looksLikeNuvio) {
                                Text("Nuvio fork", color = Color(0xFF9EACFF), fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun discoverLaunchableApps(packageManager: PackageManager): List<InstalledApp> {
    val intents = listOf(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER),
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    )
    val found = linkedMapOf<String, InstalledApp>()

    intents.forEach { intent ->
        val matches = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        }

        matches.forEach { resolveInfo ->
            val activityInfo = resolveInfo.activityInfo ?: return@forEach
            val packageName = activityInfo.packageName
            if (packageName == "com.homelessbrian.nuviocompanion" ||
                packageName == "com.homelessbrian.nuviocompanion.debug"
            ) return@forEach

            if (!found.containsKey(packageName)) {
                found[packageName] = InstalledApp(
                    label = resolveInfo.loadLabel(packageManager)?.toString() ?: packageName,
                    packageName = packageName,
                    icon = resolveInfo.loadIcon(packageManager)
                )
            }
        }
    }

    return found.values.sortedBy { it.label.lowercase() }
}
