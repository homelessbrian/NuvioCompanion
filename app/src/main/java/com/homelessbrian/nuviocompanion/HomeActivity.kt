package com.homelessbrian.nuviocompanion

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The device Home screen when Nuvio Launch is chosen as the Home app.
 *
 * Rules (designed so the user can never be trapped inside Nuvio):
 *  - A real Home press (new HOME intent, or a fresh start such as boot) opens Nuvio.
 *  - Coming back here WITHOUT a Home press (backing out of Nuvio, Nuvio closing or crashing)
 *    shows the Nuvio Launch home screen instead of reopening Nuvio. This is what prevents the loop.
 *  - If Nuvio closes within a few seconds of opening twice in a row, Home stops opening Nuvio
 *    until the user picks "Open Nuvio" again.
 *  - If Nuvio cannot be opened, or never appears, the home screen is shown.
 *  - The home screen always offers Android Settings, Nuvio Launch settings and
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
            LaunchTheme(DrawerSettings.highlight(this@HomeActivity).color) {
                Surface(Modifier.fillMaxSize(), color = LaunchColors.Background) {
                    if (mode == Mode.Home) {
                        HomeScreen(
                            notice = notice,
                            redirectEnabled = redirectEnabled,
                            nuvioLabel = DrawerSettings.selectedNuvio(this@HomeActivity)?.let { AppCatalog.label(this@HomeActivity, it) },
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
        // Setup asked the user to press Home once to prove it reaches us. Report back to setup
        // instead of opening Nuvio.
        if (DrawerSettings.homeTestPending(this)) {
            DrawerSettings.setHomeTestPending(this, false)
            DrawerSettings.setHomeTestPassed(this, true)
            showHome(null)
            SetupActivity.open(this, SetupStep.HOME)
            return
        }
        val nuvio = DrawerSettings.selectedNuvio(this)
        when {
            !DrawerSettings.homeRedirectEnabled(this) -> showHome(null)
            DrawerSettings.homeRedirectPaused(this) -> showHome(PAUSED_NOTICE)
            nuvio == null -> showHome("Choose your Nuvio app in Nuvio Launch settings so Home can open it.")
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
            showHome("Couldn't open Nuvio. It may have been uninstalled. Choose it again in Nuvio Launch settings.")
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
        showHome(BACKED_OUT_NOTICE)
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
        const val BACKED_OUT_NOTICE = "You backed out of Nuvio. Press Home or choose Open Nuvio to go back."
        const val PAUSED_NOTICE = "Nuvio closed right after opening, so Home is paused. Choose Open Nuvio to turn it back on."
    }
}

@Composable
private fun HomeScreen(
    notice: String?,
    redirectEnabled: Boolean,
    nuvioLabel: String?,
    refreshKey: Int,
    onOpenNuvio: () -> Unit,
    onAndroidSettings: () -> Unit,
    onCompanionSettings: () -> Unit,
    onStopUsingAsHome: () -> Unit,
    onSendHomeToNuvio: () -> Unit,
    onLaunchApp: (String) -> Unit
) {
    val context = LocalContext.current
    val firstAction = remember { FocusRequester() }
    var apps by remember { mutableStateOf(AppCatalog.cached().filterNot { it.packageName in DrawerSettings.hidden(context) }) }
    val clock by produceState(currentTime(context)) {
        while (true) {
            value = currentTime(context)
            delay(15_000)
        }
    }

    LaunchedEffect(refreshKey) {
        apps = withContext(Dispatchers.IO) { AppCatalog.load(context) }
    }
    LaunchedEffect(refreshKey) {
        withFrameNanos { }
        runCatching { firstAction.requestFocus() }
    }

    Column(
        Modifier.fillMaxSize()
            .background(Brush.radialGradient(listOf(Color(0xFF1A1B24), LaunchColors.Background), center = Offset(200f, 0f), radius = 1500f))
            .padding(start = 72.dp, end = 72.dp, top = 40.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            LaunchMark(36.dp)
            Spacer(Modifier.width(12.dp))
            Text("Nuvio Launch", color = LaunchColors.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(clock, color = LaunchColors.TextSoft, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        }

        val banner = notice ?: if (!redirectEnabled) "Home is showing this screen instead of Nuvio." else null
        if (banner != null) NoteBox(banner, warning = true)

        Row(Modifier.fillMaxWidth().height(220.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            FocusCard(
                modifier = Modifier.weight(2f).fillMaxHeight().focusRequester(firstAction),
                shape = RoundedCornerShape(22.dp),
                idle = LaunchColors.Raised,
                onClick = onOpenNuvio
            ) { focused ->
                Column(Modifier.fillMaxSize().padding(26.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Box(
                        Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)).background(if (focused) LaunchColors.OnFocus else LaunchColors.Text),
                        contentAlignment = Alignment.Center
                    ) { PlayGlyph(if (focused) LaunchColors.Text else LaunchColors.OnFocus) }
                    Column {
                        Text(if (nuvioLabel != null) "Open Nuvio" else "Choose your Nuvio app", color = if (focused) LaunchColors.OnFocus else LaunchColors.Text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        Text(nuvioLabel ?: "Pick it in Nuvio Launch settings", color = if (focused) LaunchColors.OnFocusMuted else LaunchColors.Muted, fontSize = 17.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            HomeActionTile("Android Settings", Modifier.weight(1f), onAndroidSettings)
            HomeActionTile("Nuvio Launch Settings", Modifier.weight(1f), onCompanionSettings)
            if (redirectEnabled) HomeActionTile("Stop using as Home", Modifier.weight(1f), onStopUsingAsHome)
            else HomeActionTile("Send Home to Nuvio", Modifier.weight(1f), onSendHomeToNuvio)
        }

        Text("Your apps", color = LaunchColors.Muted, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp)
        ) {
            items(apps, key = { it.packageName }) { app ->
                FocusCard(
                    modifier = Modifier.width(176.dp),
                    shape = RoundedCornerShape(16.dp),
                    idle = Color.Transparent,
                    scaleOnFocus = 1.06f,
                    onClick = { onLaunchApp(app.packageName) }
                ) { focused ->
                    Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        AppArt(app, Modifier.fillMaxWidth().height(93.dp), corner = 12.dp)
                        Text(
                            app.label,
                            color = if (focused) LaunchColors.OnFocus else LaunchColors.Muted,
                            fontSize = 15.sp,
                            fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeActionTile(label: String, modifier: Modifier, onClick: () -> Unit) {
    FocusCard(modifier = modifier.fillMaxHeight(), shape = RoundedCornerShape(22.dp), border = true, onClick = onClick) { focused ->
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Box(Modifier.size(34.dp).clip(CircleShape).border(2.dp, if (focused) LaunchColors.OnFocus else LaunchColors.TextSoft, CircleShape))
            Text(label, color = if (focused) LaunchColors.OnFocus else LaunchColors.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 25.sp)
        }
    }
}

@Composable
private fun PlayGlyph(color: Color) {
    Canvas(Modifier.size(30.dp)) {
        val w = size.width
        val path = Path().apply {
            moveTo(w * 0.3f, w * 0.18f)
            lineTo(w * 0.82f, w * 0.5f)
            lineTo(w * 0.3f, w * 0.82f)
            close()
        }
        drawPath(path, color)
    }
}

private fun currentTime(context: android.content.Context): String =
    android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date())
