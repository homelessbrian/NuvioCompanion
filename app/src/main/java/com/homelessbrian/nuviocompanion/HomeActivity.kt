package com.homelessbrian.nuviocompanion

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The device Home screen when Companion is chosen as the Home app.
 *
 * Rules (designed so the user can never be trapped inside Nuvio):
 *  - A real Home press (new HOME intent, or a fresh start such as boot) opens Nuvio.
 *  - Coming back here WITHOUT a Home press (backing out of Nuvio, Nuvio closing or crashing)
 *    shows the Companion home screen instead of reopening Nuvio. This is what prevents the loop.
 *  - If Nuvio closes within a few seconds of opening twice in a row, Home stops opening Nuvio
 *    until the user picks "Open Nuvio" again.
 *  - If Nuvio cannot be opened, or never appears, the home screen is shown.
 *  - The home screen always offers Android Settings, Companion settings and
 *    "Stop using as Home", and the user can switch Home back to Nuvio from here.
 */
class HomeActivity : ComponentActivity() {
    private enum class Mode { Redirecting, Home }

    private var mode by mutableStateOf(Mode.Home)
    private var notice by mutableStateOf<String?>(null)
    private var redirectEnabled by mutableStateOf(true)
    private var refreshKey by mutableIntStateOf(0)

    private var launchedAt = 0L
    private var awaitingReturn = false
    private var leftSinceLaunch = false

    private val handler = Handler(Looper.getMainLooper())
    private val redirectTimeout = Runnable {
        // Nuvio was started but never covered this screen. Never leave the user on a blank page.
        if (mode == Mode.Redirecting) {
            awaitingReturn = false
            showHome(null)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        redirectEnabled = DrawerSettings.homeRedirectEnabled(this)

        // A launcher ignores Back. Finishing here would make Android restart Home and reopen Nuvio.
        onBackPressedDispatcher.addCallback(this) { }

        // savedInstanceState != null means Android recreated this screen (for example after
        // freeing memory while Nuvio was open). That is a return, not a Home press.
        if (savedInstanceState == null && isHomePress(intent)) onHomePressed() else showHome(null)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = HomeColors.Accent, background = HomeColors.Background, surface = HomeColors.Panel)) {
                Surface(Modifier.fillMaxSize(), color = HomeColors.Background) {
                    if (mode == Mode.Home) {
                        HomeScreen(
                            notice = notice,
                            redirectEnabled = redirectEnabled,
                            hasNuvio = DrawerSettings.selectedNuvio(this@HomeActivity) != null,
                            refreshKey = refreshKey,
                            onOpenNuvio = ::openNuvioFromButton,
                            onAndroidSettings = { HomeSetup.openAndroidSettings(this@HomeActivity) },
                            onCompanionSettings = ::openCompanionSettings,
                            onStopUsingAsHome = ::stopUsingAsHome,
                            onSendHomeToNuvio = ::sendHomeToNuvio,
                            onLaunchApp = { pkg -> AppCatalog.launch(this@HomeActivity, pkg) }
                        )
                    }
                    // Mode.Redirecting draws only the dark background so there is no flash of UI
                    // while Nuvio is coming to the front.
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isHomePress(intent)) onHomePressed()
    }

    override fun onResume() {
        super.onResume()
        redirectEnabled = DrawerSettings.homeRedirectEnabled(this)
        if (awaitingReturn && leftSinceLaunch) {
            onReturnedWithoutHomePress()
        } else if (mode == Mode.Redirecting) {
            handler.removeCallbacks(redirectTimeout)
            handler.postDelayed(redirectTimeout, REDIRECT_TIMEOUT_MS)
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(redirectTimeout)
        if (awaitingReturn) leftSinceLaunch = true
    }

    override fun onDestroy() {
        handler.removeCallbacks(redirectTimeout)
        super.onDestroy()
    }

    private fun isHomePress(intent: Intent?): Boolean =
        intent?.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)

    private fun onHomePressed() {
        val nuvio = DrawerSettings.selectedNuvio(this)
        when {
            !DrawerSettings.homeRedirectEnabled(this) -> showHome(null)
            DrawerSettings.homeRedirectPaused(this) -> showHome(PAUSED_NOTICE)
            nuvio == null -> showHome("Choose your Nuvio app in Companion settings so Home can open it.")
            else -> launchNuvio(nuvio)
        }
    }

    private fun launchNuvio(packageName: String) {
        mode = Mode.Redirecting
        if (AppCatalog.launch(this, packageName)) {
            launchedAt = SystemClock.elapsedRealtime()
            awaitingReturn = true
            leftSinceLaunch = false
        } else {
            awaitingReturn = false
            showHome("Couldn't open Nuvio. It may have been uninstalled. Choose it again in Companion settings.")
        }
    }

    private fun onReturnedWithoutHomePress() {
        awaitingReturn = false
        leftSinceLaunch = false
        val closedQuickly = SystemClock.elapsedRealtime() - launchedAt < QUICK_CLOSE_MS
        if (closedQuickly) {
            val count = DrawerSettings.homeQuickReturns(this) + 1
            if (count >= QUICK_CLOSES_BEFORE_PAUSE) {
                DrawerSettings.setHomeQuickReturns(this, 0)
                DrawerSettings.setHomeRedirectPaused(this, true)
                showHome(PAUSED_NOTICE)
                return
            }
            DrawerSettings.setHomeQuickReturns(this, count)
        } else {
            DrawerSettings.setHomeQuickReturns(this, 0)
        }
        showHome(null)
    }

    private fun showHome(message: String?) {
        handler.removeCallbacks(redirectTimeout)
        notice = message
        mode = Mode.Home
        refreshKey++
    }

    private fun openNuvioFromButton() {
        DrawerSettings.setHomeRedirectPaused(this, false)
        DrawerSettings.setHomeQuickReturns(this, 0)
        val nuvio = DrawerSettings.selectedNuvio(this)
        if (nuvio == null) openCompanionSettings() else launchNuvio(nuvio)
    }

    private fun openCompanionSettings() {
        runCatching { startActivity(Intent(this, MainActivity::class.java)) }
    }

    private fun stopUsingAsHome() {
        DrawerSettings.setHomeRedirectEnabled(this, false)
        redirectEnabled = false
        notice = "Home now shows this screen instead of Nuvio. Pick another Home app in the list Android opens."
        HomeSetup.openHomePicker(this)
    }

    private fun sendHomeToNuvio() {
        DrawerSettings.setHomeRedirectEnabled(this, true)
        DrawerSettings.setHomeRedirectPaused(this, false)
        DrawerSettings.setHomeQuickReturns(this, 0)
        redirectEnabled = true
        notice = null
    }

    private companion object {
        const val QUICK_CLOSE_MS = 4_000L
        const val QUICK_CLOSES_BEFORE_PAUSE = 2
        const val REDIRECT_TIMEOUT_MS = 3_000L
        const val PAUSED_NOTICE = "Nuvio closed right after opening, so Home is paused. Choose Open Nuvio to turn it back on."
    }
}

private object HomeColors {
    val Background = Color(0xFF0D0D0D)
    val Panel = Color(0xFF1A1A1A)
    val Raised = Color(0xFF262626)
    val Muted = Color(0xFFB3B3B3)
    val Accent = Color(0xFFF5F5F5)
    val Warning = Color(0xFFFFB74D)
}

private data class HomeTile(val app: InstalledApp, val image: ImageBitmap)

@Composable
private fun HomeScreen(
    notice: String?,
    redirectEnabled: Boolean,
    hasNuvio: Boolean,
    refreshKey: Int,
    onOpenNuvio: () -> Unit,
    onAndroidSettings: () -> Unit,
    onCompanionSettings: () -> Unit,
    onStopUsingAsHome: () -> Unit,
    onSendHomeToNuvio: () -> Unit,
    onLaunchApp: (String) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val firstAction = remember { FocusRequester() }
    var tiles by remember { mutableStateOf<List<HomeTile>>(emptyList()) }

    LaunchedEffect(refreshKey) {
        tiles = withContext(Dispatchers.IO) {
            AppCatalog.load(context).mapNotNull { app ->
                runCatching { HomeTile(app, app.icon.toScaledBitmap(256).asImageBitmap()) }.getOrNull()
            }
        }
    }
    LaunchedEffect(refreshKey) {
        withFrameNanos { }
        runCatching { firstAction.requestFocus() }
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 27.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Text("Nuvio Companion", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)

        val banner = notice ?: if (!redirectEnabled) "Home is showing this screen instead of Nuvio." else null
        if (banner != null) {
            Text(
                banner,
                color = HomeColors.Warning,
                fontSize = 16.sp,
                modifier = Modifier.fillMaxWidth().background(HomeColors.Panel, RoundedCornerShape(12.dp)).padding(14.dp)
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HomeButton(if (hasNuvio) "Open Nuvio" else "Choose Nuvio app", Modifier.focusRequester(firstAction), primary = true, onClick = onOpenNuvio)
            HomeButton("Android Settings", onClick = onAndroidSettings)
            HomeButton("Companion Settings", onClick = onCompanionSettings)
            if (redirectEnabled) HomeButton("Stop using as Home", onClick = onStopUsingAsHome)
            else HomeButton("Send Home to Nuvio", onClick = onSendHomeToNuvio)
        }

        Text("Apps", color = HomeColors.Muted, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        LazyVerticalGrid(
            columns = GridCells.Fixed(6),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(4.dp)
        ) {
            items(tiles, key = { it.app.packageName }) { tile ->
                HomeAppTile(tile) { onLaunchApp(tile.app.packageName) }
            }
        }
    }
}

@Composable
private fun HomeButton(label: String, modifier: Modifier = Modifier, primary: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Text(
        label,
        color = if (focused) Color(0xFF111111) else Color.White,
        fontSize = 17.sp,
        fontWeight = if (primary || focused) FontWeight.Bold else FontWeight.Medium,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .background(if (focused) HomeColors.Accent else HomeColors.Raised, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp)
    )
}

@Composable
private fun HomeAppTile(tile: HomeTile, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .onFocusChanged { focused = it.isFocused }
            .then(if (focused) Modifier.border(2.dp, Color.White, shape) else Modifier)
            .background(if (focused) HomeColors.Raised else Color.Transparent, shape)
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.fillMaxWidth().height(78.dp).background(HomeColors.Panel, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Image(
                tile.image,
                tile.app.label,
                Modifier.fillMaxSize(),
                contentScale = if (tile.app.isTvBanner) ContentScale.Crop else ContentScale.Fit
            )
        }
        Spacer(Modifier.size(6.dp))
        Text(tile.app.label, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun android.graphics.drawable.Drawable.toScaledBitmap(maxSize: Int): Bitmap {
    val sourceWidth = intrinsicWidth.takeIf { it > 0 } ?: maxSize
    val sourceHeight = intrinsicHeight.takeIf { it > 0 } ?: maxSize
    val scale = maxSize.toFloat() / maxOf(sourceWidth, sourceHeight)
    return toBitmap((sourceWidth * scale).toInt().coerceAtLeast(1), (sourceHeight * scale).toInt().coerceAtLeast(1))
}
