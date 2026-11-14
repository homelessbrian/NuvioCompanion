package com.homelessbrian.nuviocompanion

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.homelessbrian.nuviocompanion.remote.KeyNames
import com.homelessbrian.nuviocompanion.remote.RemoteKeyAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SettingsSection(val label: String, val subtitle: String) {
    REMOTE("Remote shortcut", "The button that brings up your apps from anywhere."),
    HOME("Home button", "What happens when you press Home."),
    LOOK("Drawer look", "Where your apps appear and how they look."),
    APPS("Apps", ""),
    ICONS("Icon pack", "Swap app icons for a pack's matching set."),
    HELP("Help & recovery", "Ways out, and how to start over."),
    ABOUT("About", "")
}

/**
 * Settings. Opened from the app icon, the Nuvio Launch home screen, or the drawer. Shows
 * first-run setup instead when it hasn't been done yet.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val section = intent.getStringExtra(EXTRA_SECTION)?.let { name -> SettingsSection.entries.firstOrNull { it.name == name } }
        val fromSetup = intent.getBooleanExtra(EXTRA_FROM_SETUP, false)
        if (savedInstanceState == null && section == null && !fromSetup && !DrawerSettings.setupDone(this)) {
            SetupActivity.open(this)
            finish()
            return
        }
        setContent {
            var highlight by remember { mutableStateOf(DrawerSettings.highlight(this)) }
            LaunchTheme(highlight.color) {
                SettingsScreen(
                    startSection = section ?: SettingsSection.REMOTE,
                    focusPackage = intent.getStringExtra(EXTRA_PACKAGE),
                    highlight = highlight,
                    onHighlight = {
                        highlight = it
                        DrawerSettings.setHighlight(this, it)
                    }
                )
            }
        }
    }

    companion object {
        const val EXTRA_SECTION = "section"
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_FROM_SETUP = "from_setup"

        fun open(context: Context, section: SettingsSection? = null, packageName: String? = null) {
            val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (section != null) intent.putExtra(EXTRA_SECTION, section.name)
            if (packageName != null) intent.putExtra(EXTRA_PACKAGE, packageName)
            runCatching { context.startActivity(intent) }
        }
    }
}

private enum class SubPage { RECORD_PRIMARY, RECORD_SECOND, PRESS_STYLE, CHOOSE_NUVIO }

@Composable
private fun SettingsScreen(
    startSection: SettingsSection,
    focusPackage: String?,
    highlight: Highlight,
    onHighlight: (Highlight) -> Unit
) {
    var section by remember { mutableStateOf(startSection) }
    var sub by remember { mutableStateOf<SubPage?>(null) }
    var resumeTick by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val navFocus = remember { SettingsSection.entries.associateWith { FocusRequester() } }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) resumeTick++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    BackHandler(enabled = sub != null) { sub = null }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        if (focusPackage == null) runCatching { navFocus.getValue(section).requestFocus() }
    }

    Row(Modifier.launchBackground()) {
        // ---- Left rail ---------------------------------------------------------------------
        Column(
            Modifier.width(330.dp).fillMaxHeight().background(LaunchColors.Rail)
                .padding(start = 56.dp, end = 24.dp, top = 40.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 22.dp)) {
                LaunchMark(36.dp)
                Spacer(Modifier.width(12.dp))
                Text("Settings", color = LaunchColors.Text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            }
            SettingsSection.entries.forEach { item ->
                NavItem(
                    label = item.label,
                    selected = item == section,
                    modifier = Modifier.focusRequester(navFocus.getValue(item)),
                    onFocused = {
                        if (section != item) {
                            section = item
                            sub = null
                        }
                    }
                )
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(Color(0x0FFFFFFF)))

        // ---- Content -------------------------------------------------------------------------
        Box(Modifier.weight(1f).fillMaxHeight().padding(start = 56.dp, end = 72.dp, top = 40.dp, bottom = 36.dp)) {
            when (section) {
                SettingsSection.REMOTE -> RemoteSection(sub, resumeTick, onSub = { sub = it })
                SettingsSection.HOME -> HomeSection(sub, resumeTick, onSub = { sub = it })
                SettingsSection.LOOK -> LookSection(highlight, onHighlight)
                SettingsSection.APPS -> AppsSection(resumeTick, focusPackage)
                SettingsSection.ICONS -> IconPackSection(onOpenApps = { runCatching { navFocus.getValue(SettingsSection.APPS).requestFocus() } })
                SettingsSection.HELP -> HelpSection()
                SettingsSection.ABOUT -> AboutSection()
            }
        }
    }
}

@Composable
private fun NavItem(label: String, selected: Boolean, modifier: Modifier, onFocused: () -> Unit) {
    val highlight = LocalHighlight.current
    FocusCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        idle = if (selected) LaunchColors.Raised else Color.Transparent,
        scaleOnFocus = 1.02f,
        onFocused = onFocused,
        onClick = onFocused
    ) { focused ->
        Row(Modifier.padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (selected && !focused) highlight else Color.Transparent))
            Spacer(Modifier.width(14.dp))
            Text(
                label,
                color = when {
                    focused -> LaunchColors.OnFocus
                    selected -> LaunchColors.Text
                    else -> LaunchColors.Muted
                },
                fontSize = 18.sp,
                fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Medium
            )
        }
    }
}

/** Title block at the top of each section. */
@Composable
private fun SectionHeader(title: String, subtitle: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 6.dp)) {
        Text(title, color = LaunchColors.Text, fontSize = 36.sp, fontWeight = FontWeight.Bold)
        if (!subtitle.isNullOrBlank()) Text(subtitle, color = LaunchColors.Muted, fontSize = 18.sp)
    }
}

@Composable
private fun SectionList(content: LazyListScope.() -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(vertical = 6.dp, horizontal = 6.dp),
        content = content
    )
}

@Composable
private fun SmallLabel(text: String) {
    Text(text.uppercase(), color = LaunchColors.Faint, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, modifier = Modifier.padding(top = 10.dp))
}

// ---------------------------------------------------------------------------------------------
// Remote shortcut
// ---------------------------------------------------------------------------------------------

private enum class TestState { IDLE, LISTENING, RECEIVED, NOTHING }

@Composable
private fun RemoteSection(sub: SubPage?, resumeTick: Int, onSub: (SubPage?) -> Unit) {
    val context = LocalContext.current
    var access by remember { mutableStateOf(RemoteKeyAccessibilityService.isEnabled(context)) }
    var mode by remember { mutableStateOf(DrawerSettings.shortcutMode(context)) }
    var key by remember { mutableStateOf(DrawerSettings.keyCode(context)) }
    var second by remember { mutableStateOf(DrawerSettings.secondKeyCode(context)) }
    var test by remember { mutableStateOf(TestState.IDLE) }
    var testRun by remember { mutableStateOf(0) }

    LaunchedEffect(resumeTick, sub) {
        key = DrawerSettings.keyCode(context)
        second = DrawerSettings.secondKeyCode(context)
        mode = DrawerSettings.shortcutMode(context)
        while (true) {
            access = RemoteKeyAccessibilityService.isEnabled(context)
            delay(1500)
        }
    }
    ShortcutEvents(onTriggered = { if (test == TestState.LISTENING) test = TestState.RECEIVED })
    LaunchedEffect(test, testRun) {
        if (test == TestState.LISTENING) {
            DrawerSettings.setTestingShortcut(context, true)
            delay(10_000)
            if (test == TestState.LISTENING) test = TestState.NOTHING
        }
        if (test != TestState.LISTENING) DrawerSettings.setTestingShortcut(context, false)
    }

    when (sub) {
        SubPage.RECORD_PRIMARY, SubPage.RECORD_SECOND -> {
            val secondary = sub == SubPage.RECORD_SECOND
            Column(verticalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.widthIn(max = 760.dp)) {
                SectionHeader(
                    if (secondary) "Second button" else "Drawer button",
                    if (secondary) "Hold ${KeyNames.name(key)}, then press this one." else "Press the button you want to open your apps. Back cancels."
                )
                ButtonRecorder(target = if (secondary) "secondary" else "primary", onUse = { onSub(null) }, useLabel = { "Use $it" })
            }
            return
        }
        SubPage.PRESS_STYLE -> {
            val first = remember { FocusRequester() }
            LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
            SectionList {
                item { SectionHeader("Press style", "How ${KeyNames.name(key)} opens the drawer.") }
                items(ShortcutMode.entries) { option ->
                    val body = when (option) {
                        ShortcutMode.SINGLE -> "Fastest. The button only opens the drawer."
                        ShortcutMode.DOUBLE -> "Press twice quickly. A single press does nothing."
                        ShortcutMode.LONG -> "Hold for about half a second."
                        ShortcutMode.COMBINATION -> "Hold your button, then press a second one."
                    }
                    SettingRow(
                        title = option.label,
                        subtitle = body,
                        chevron = false,
                        modifier = if (option == mode) Modifier.focusRequester(first) else Modifier,
                        trailing = { focused -> RadioDot(option == mode, focused) },
                        onClick = {
                            mode = option
                            DrawerSettings.setShortcutMode(context, option)
                            onSub(if (option == ShortcutMode.COMBINATION && second == 0) SubPage.RECORD_SECOND else null)
                        }
                    )
                }
            }
            return
        }
        else -> Unit
    }

    SectionList {
        item { SectionHeader(SettingsSection.REMOTE.label, SettingsSection.REMOTE.subtitle) }
        item {
            SettingRow(
                title = "Remote access",
                subtitle = "Needed so the button works inside other apps",
                value = if (access) "On" else "Off · turn on",
                valueColor = if (access) LaunchColors.Good else LaunchColors.WarnText,
                onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } }
            )
        }
        item {
            SettingRow(
                title = "Drawer button",
                subtitle = if (access) "Select to record a different button" else "Turn on remote access first",
                value = KeyNames.name(key),
                onClick = { if (access) onSub(SubPage.RECORD_PRIMARY) else runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } }
            )
        }
        item {
            SettingRow(title = "Press style", subtitle = "Single, double, hold, or two buttons", value = mode.label, onClick = { onSub(SubPage.PRESS_STYLE) })
        }
        if (mode == ShortcutMode.COMBINATION) item {
            SettingRow(title = "Second button", subtitle = "Pressed while holding ${KeyNames.name(key)}", value = KeyNames.name(second), onClick = { onSub(SubPage.RECORD_SECOND) })
        }
        item {
            val (subtitle, value, color) = when (test) {
                TestState.IDLE -> Triple("Press it now. We'll show whether Nuvio Launch received it.", "Start", null)
                TestState.LISTENING -> Triple("Press ${KeyNames.name(key)} now…", "Listening", LaunchColors.WarnText)
                TestState.RECEIVED -> Triple("Your button works. The drawer will open normally again.", "Received", LaunchColors.Good)
                TestState.NOTHING -> Triple("Nothing arrived. Your TV may keep this button for itself. Try another button.", "Nothing yet", LaunchColors.WarnText)
            }
            SettingRow(
                title = "Test your button",
                subtitle = subtitle,
                value = value,
                valueColor = color,
                onClick = {
                    if (key != 0 && access) {
                        testRun++
                        test = TestState.LISTENING
                    }
                }
            )
        }
        item {
            Text(
                "Some TVs keep certain buttons for themselves. If the test shows nothing, try a different button.",
                color = LaunchColors.Faint, fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Home button
// ---------------------------------------------------------------------------------------------

@Composable
private fun HomeSection(sub: SubPage?, resumeTick: Int, onSub: (SubPage?) -> Unit) {
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    var nuvio by remember { mutableStateOf(DrawerSettings.selectedNuvio(context)) }
    var redirect by remember { mutableStateOf(DrawerSettings.homeRedirectEnabled(context)) }
    var paused by remember { mutableStateOf(DrawerSettings.homeRedirectPaused(context)) }
    LaunchedEffect(resumeTick) {
        nuvio = DrawerSettings.selectedNuvio(context)
        redirect = DrawerSettings.homeRedirectEnabled(context)
        paused = DrawerSettings.homeRedirectPaused(context)
    }

    if (sub == SubPage.CHOOSE_NUVIO) {
        val apps by produceState<List<InstalledApp>?>(null) {
            value = withContext(Dispatchers.IO) {
                AppCatalog.load(context, includeHidden = true).sortedWith(
                    compareByDescending<InstalledApp> { it.label.contains("nuvio", true) || it.packageName.contains("nuvio", true) }
                        .thenBy { it.label.lowercase() }
                )
            }
        }
        val first = remember { FocusRequester() }
        LaunchedEffect(apps) { withFrameNanos { }; runCatching { first.requestFocus() } }
        SectionList {
            item { SectionHeader("Your Nuvio app", "Home opens this app, and it's pinned first in your drawer.") }
            val list = apps
            if (list == null) item { Text("Looking for apps…", color = LaunchColors.Muted, fontSize = 18.sp) }
            else items(list, key = { it.packageName }) { app ->
                AppRow(
                    app = app,
                    subtitle = app.packageName,
                    modifier = if (app == list.first()) Modifier.focusRequester(first) else Modifier,
                    trailing = { focused -> RadioDot(app.packageName == nuvio, focused) },
                    onClick = {
                        nuvio = app.packageName
                        DrawerSettings.setSelectedNuvio(context, app.packageName)
                        onSub(null)
                    }
                )
            }
        }
        return
    }

    SectionList {
        item { SectionHeader(SettingsSection.HOME.label, SettingsSection.HOME.subtitle) }
        item {
            SettingRow(
                title = "Your Nuvio app",
                subtitle = "The app Home opens",
                value = nuvio?.let { AppCatalog.label(context, it) } ?: "Not chosen",
                onClick = { onSub(SubPage.CHOOSE_NUVIO) }
            )
        }
        item {
            ToggleRow(
                title = "Home opens Nuvio",
                subtitle = "Only works when Nuvio Launch is your TV's Home app",
                checked = redirect
            ) {
                redirect = it
                DrawerSettings.setHomeRedirectEnabled(context, it)
                if (it) {
                    DrawerSettings.setHomeRedirectPaused(context, false)
                    DrawerSettings.setHomeQuickReturns(context, 0)
                    paused = false
                }
            }
        }
        if (paused && redirect) item {
            SettingRow(
                title = "Home is paused",
                subtitle = "Nuvio closed right after opening twice in a row. Select to turn Home back on.",
                value = "Resume",
                valueColor = LaunchColors.WarnText,
                onClick = {
                    DrawerSettings.setHomeRedirectPaused(context, false)
                    DrawerSettings.setHomeQuickReturns(context, 0)
                    paused = false
                }
            )
        }
        item {
            SettingRow(
                title = "Choose Home app",
                subtitle = "Opens Android's list of Home apps. Pick Nuvio Launch to use it, or another to stop.",
                onClick = { activity?.let { HomeSetup.openHomePicker(it) } }
            )
        }
        item {
            NoteBox("You can never get stuck: backing out of Nuvio shows the Nuvio Launch home screen, and your drawer always ends with Android Settings.")
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Drawer look
// ---------------------------------------------------------------------------------------------

@Composable
private fun LookSection(highlight: Highlight, onHighlight: (Highlight) -> Unit) {
    val context = LocalContext.current
    var position by remember { mutableStateOf(DrawerSettings.position(context)) }
    var showNames by remember { mutableStateOf(DrawerSettings.showNames(context)) }
    var showIcons by remember { mutableStateOf(DrawerSettings.showIcons(context)) }
    var glow by remember { mutableStateOf(DrawerSettings.focusGlow(context)) }
    var blur by remember { mutableStateOf(DrawerSettings.blurBehind(context)) }
    var opacity by remember { mutableStateOf(DrawerSettings.opacity(context)) }

    SectionList {
        item { SectionHeader(SettingsSection.LOOK.label, SettingsSection.LOOK.subtitle) }
        item { SmallLabel("Position") }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp, horizontal = 2.dp)) {
                items(DrawerPosition.entries) { option ->
                    FilterChip(option.label, position == option) {
                        position = option
                        DrawerSettings.setPosition(context, option)
                    }
                }
            }
        }
        item { DrawerPreview(position, showNames, Modifier.widthIn(max = 620.dp)) }
        item { ToggleRow("Show app names", "Names under every app, not just the one you're on", showNames) { showNames = it; DrawerSettings.setShowNames(context, it) } }
        item { ToggleRow("Show app artwork", "Turn off for a names-only list", showIcons) { showIcons = it; DrawerSettings.setShowIcons(context, it) } }
        item { ToggleRow("Glow on the focused app", null, glow) { glow = it; DrawerSettings.setFocusGlow(context, it) } }
        item { ToggleRow("Blur behind", "Softens whatever is on screen behind the drawer (Android 12 and newer)", blur) { blur = it; DrawerSettings.setBlurBehind(context, it) } }
        item { SmallLabel("Panel opacity") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.72f to "Light", 0.86f to "Medium", 0.95f to "Solid").forEach { (value, label) ->
                    FilterChip(label, kotlin.math.abs(opacity - value) < 0.02f) {
                        opacity = value
                        DrawerSettings.setOpacity(context, value)
                    }
                }
            }
        }
        item { SmallLabel("Highlight color · matches Nuvio's themes") }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Highlight.entries.chunked(6).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { option -> Swatch(option, option == highlight) { onHighlight(option) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun Swatch(option: Highlight, selected: Boolean, onClick: () -> Unit) {
    FocusCard(modifier = Modifier.width(132.dp), shape = RoundedCornerShape(16.dp), scaleOnFocus = 1.05f, onClick = onClick) { focused ->
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(option.color)
                    .border(if (selected) 3.dp else 1.dp, if (focused) LaunchColors.OnFocus else if (selected) LaunchColors.Text else Color(0x33FFFFFF), CircleShape),
                contentAlignment = Alignment.Center
            ) { if (selected) CheckMark(LaunchColors.OnFocus, 20.dp) }
            Text(option.label, color = if (focused) LaunchColors.OnFocus else if (selected) LaunchColors.Text else LaunchColors.Muted, fontSize = 14.sp, maxLines = 1)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Apps
// ---------------------------------------------------------------------------------------------

private enum class AppFilter(val label: String) { ALL("All"), FAVORITES("Favorites"), HIDDEN("Hidden") }

@Composable
private fun AppsSection(resumeTick: Int, focusPackage: String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
    var hidden by remember { mutableStateOf(DrawerSettings.hidden(context)) }
    var favorites by remember { mutableStateOf(DrawerSettings.favorites(context)) }
    var filter by remember { mutableStateOf(AppFilter.ALL) }
    var expanded by remember { mutableStateOf(focusPackage) }
    var moving by remember { mutableStateOf<String?>(null) }
    val nuvio = remember(resumeTick) { DrawerSettings.selectedNuvio(context) }
    val listState = rememberLazyListState()
    val rowFocus = remember { mutableMapOf<String, FocusRequester>() }
    fun focusFor(pkg: String) = rowFocus.getOrPut(pkg) { FocusRequester() }

    LaunchedEffect(resumeTick) {
        apps = withContext(Dispatchers.IO) { AppCatalog.load(context, includeHidden = true) }
        hidden = DrawerSettings.hidden(context)
        favorites = DrawerSettings.favorites(context)
    }
    // Opened from the drawer for one app: jump to it.
    LaunchedEffect(apps != null) {
        val list = apps ?: return@LaunchedEffect
        val target = focusPackage ?: return@LaunchedEffect
        val index = list.indexOfFirst { it.packageName == target }
        if (index >= 0) {
            listState.scrollToItem(index + 1)
            withFrameNanos { }
            runCatching { focusFor(target).requestFocus() }
        }
    }

    fun resort() {
        apps = apps?.let { AppCatalog.sort(context, it) }
    }
    fun move(pkg: String, delta: Int) {
        val list = apps ?: return
        val index = list.indexOfFirst { it.packageName == pkg }
        val target = index + delta
        if (index < 0 || target !in list.indices) return
        val reordered = list.toMutableList().apply { add(target, removeAt(index)) }
        DrawerSettings.setOrder(context, reordered.map { it.packageName })
        apps = reordered
        scope.launch {
            withFrameNanos { }
            runCatching { focusFor(pkg).requestFocus() }
        }
    }

    BackHandler(enabled = moving != null || expanded != null) {
        if (moving != null) moving = null else expanded = null
    }

    val all = apps
    val shown = when (filter) {
        AppFilter.ALL -> all.orEmpty()
        AppFilter.FAVORITES -> all.orEmpty().filter { it.packageName in favorites }
        AppFilter.HIDDEN -> all.orEmpty().filter { it.packageName in hidden }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 6.dp, horizontal = 6.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Apps", color = LaunchColors.Text, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                    val total = all?.size ?: 0
                    val hiddenCount = all.orEmpty().count { it.packageName in hidden }
                    Text(
                        if (all == null) "Loading your apps…" else "${total - hiddenCount} in your drawer · $hiddenCount hidden",
                        color = LaunchColors.Muted, fontSize = 18.sp
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppFilter.entries.forEach { option -> FilterChip(option.label, filter == option) { filter = option } }
                }
            }
        }
        if (all != null && shown.isEmpty()) item {
            Text(
                when (filter) {
                    AppFilter.FAVORITES -> "No favorites yet. Open an app below and choose Add to favorites."
                    AppFilter.HIDDEN -> "Nothing is hidden."
                    AppFilter.ALL -> "No apps found."
                },
                color = LaunchColors.Muted, fontSize = 17.sp, modifier = Modifier.padding(top = 12.dp)
            )
        }
        items(shown, key = { it.packageName }) { app ->
            val pkg = app.packageName
            val isHidden = pkg in hidden
            val isFavorite = pkg in favorites
            val status = buildList {
                if (pkg == nuvio) add("Pinned first · opened by Home")
                add(if (isHidden) "Hidden" else "Shown")
                if (isFavorite) add("Favorite")
                if (app.iconSource.isNotBlank()) add(app.iconSource)
            }.joinToString(" · ")

            if (moving == pkg) {
                MovingRow(app, Modifier.focusRequester(focusFor(pkg)), onUp = { move(pkg, -1) }, onDown = { move(pkg, 1) }, onDone = { moving = null })
            } else if (expanded == pkg) {
                ExpandedAppRow(
                    app = app,
                    status = status,
                    isFavorite = isFavorite,
                    isHidden = isHidden,
                    firstFocus = focusFor(pkg),
                    onFavorite = {
                        favorites = if (isFavorite) favorites - pkg else favorites + pkg
                        DrawerSettings.setFavorites(context, favorites)
                        resort()
                    },
                    onHide = {
                        hidden = if (isHidden) hidden - pkg else hidden + pkg
                        DrawerSettings.setHidden(context, hidden)
                    },
                    onIcon = { IconPickerActivity.open(context, pkg) },
                    onMove = {
                        filter = AppFilter.ALL
                        moving = pkg
                        expanded = null
                    }
                )
            } else {
                AppRow(
                    app = app,
                    subtitle = status,
                    dimmed = isHidden,
                    modifier = Modifier.focusRequester(focusFor(pkg)),
                    trailing = { focused ->
                        if (isFavorite) Text("★", color = if (focused) LaunchColors.OnFocus else LaunchColors.Warn, fontSize = 22.sp)
                    },
                    onClick = { expanded = pkg }
                )
            }
        }
    }
}

@Composable
fun AppRow(
    app: InstalledApp,
    subtitle: String,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
    trailing: (@Composable (Boolean) -> Unit)? = null,
    onClick: () -> Unit
) {
    FocusCard(modifier = modifier.fillMaxWidth(), onClick = onClick) { focused ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.then(if (dimmed && !focused) Modifier.background(Color.Transparent) else Modifier)) {
                AppArt(app, Modifier.width(96.dp).height(54.dp), corner = 9.dp)
                if (dimmed && !focused) Box(Modifier.width(96.dp).height(54.dp).clip(RoundedCornerShape(9.dp)).background(Color(0x99000000)))
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    app.label,
                    color = when { focused -> LaunchColors.OnFocus; dimmed -> LaunchColors.Muted; else -> LaunchColors.Text },
                    fontSize = 19.sp, fontWeight = if (focused) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(subtitle, color = if (focused) LaunchColors.OnFocusMuted else LaunchColors.Muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (trailing != null) trailing(focused)
        }
    }
}

@Composable
private fun ExpandedAppRow(
    app: InstalledApp,
    status: String,
    isFavorite: Boolean,
    isHidden: Boolean,
    firstFocus: FocusRequester,
    onFavorite: () -> Unit,
    onHide: () -> Unit,
    onIcon: () -> Unit,
    onMove: () -> Unit
) {
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { firstFocus.requestFocus() } }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(LaunchColors.Text).padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppArt(app, Modifier.width(96.dp).height(54.dp), corner = 9.dp)
            Spacer(Modifier.width(18.dp))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(app.label, color = LaunchColors.OnFocus, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text(status, color = LaunchColors.OnFocusMuted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RowAction(if (isFavorite) "Remove from favorites" else "Add to favorites", primary = true, modifier = Modifier.focusRequester(firstFocus), onClick = onFavorite)
            RowAction(if (isHidden) "Show" else "Hide", onClick = onHide)
            RowAction("Change icon", onClick = onIcon)
            RowAction("Move", onClick = onMove)
        }
    }
}

@Composable
private fun RowAction(label: String, primary: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val highlight = LocalHighlight.current
    var focused by remember { mutableStateOf(false) }
    Text(
        label,
        color = if (primary || focused) LaunchColors.Text else LaunchColors.OnFocus,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(12.dp))
            .background(if (primary || focused) LaunchColors.OnFocus else Color(0xFFDCDCE3))
            .then(if (focused) Modifier.border(3.dp, highlight.copy(alpha = 0.8f), RoundedCornerShape(12.dp)) else Modifier)
            .tvClick(onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp)
    )
}

@Composable
private fun MovingRow(app: InstalledApp, modifier: Modifier, onUp: () -> Unit, onDown: () -> Unit, onDone: () -> Unit) {
    val highlight = LocalHighlight.current
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { requester.requestFocus() } }
    Row(
        modifier.focusRequester(requester).fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(LaunchColors.Raised)
            .border(3.dp, highlight, RoundedCornerShape(16.dp))
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> { onUp(); true }
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> { onDown(); true }
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> true
                    else -> false
                }
            }
            .tvClick(onDone)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppArt(app, Modifier.width(96.dp).height(54.dp), corner = 9.dp)
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(app.label, color = LaunchColors.Text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text("Moving · Up and Down to move, OK when done", color = highlight, fontSize = 14.sp)
        }
        Text("↕", color = highlight, fontSize = 26.sp)
    }
}

// ---------------------------------------------------------------------------------------------
// Icon pack
// ---------------------------------------------------------------------------------------------

@Composable
private fun IconPackSection(onOpenApps: () -> Unit) {
    val context = LocalContext.current
    val packs = remember { IconPackManager.installed(context) }
    var selected by remember { mutableStateOf(DrawerSettings.iconPack(context)) }
    var matchAll by remember { mutableStateOf(DrawerSettings.matchAllIcons(context)) }
    var resetDone by remember { mutableStateOf(false) }
    val counts by produceState<Map<String, Int>>(emptyMap()) {
        packs.forEach { pack ->
            val count = withContext(Dispatchers.IO) { IconPackManager.iconCount(context, pack.packageName) }
            value = value + (pack.packageName to count)
        }
    }
    SectionList {
        item { SectionHeader(SettingsSection.ICONS.label, SettingsSection.ICONS.subtitle) }
        item {
            SettingRow(
                title = "No pack",
                subtitle = "Use each app's own artwork",
                chevron = false,
                trailing = { focused -> RadioDot(selected == null, focused) },
                onClick = {
                    selected = null
                    DrawerSettings.setIconPack(context, null)
                }
            )
        }
        items(packs, key = { it.packageName }) { pack ->
            val count = counts[pack.packageName]
            SettingRow(
                title = pack.label,
                subtitle = when (count) {
                    null -> "Counting icons…"
                    0 -> "Nuvio Launch couldn't read this pack's icons"
                    else -> "%,d icons".format(count)
                },
                chevron = false,
                trailing = { focused -> RadioDot(selected == pack.packageName, focused) },
                onClick = {
                    selected = pack.packageName
                    DrawerSettings.setIconPack(context, pack.packageName)
                }
            )
        }
        if (selected != null) item {
            ToggleRow(
                title = "Match every app",
                subtitle = "Apps the pack doesn't include get a pack-style icon too",
                checked = matchAll
            ) {
                matchAll = it
                DrawerSettings.setMatchAllIcons(context, it)
            }
        }
        if (selected != null) item {
            SettingRow(
                title = "Put every app back on this pack",
                subtitle = if (resetDone) "Done. Every app now follows the pack." else "Clears icons you picked one by one, so everything matches",
                chevron = false,
                onClick = {
                    DrawerSettings.clearAppIconOverrides(context)
                    resetDone = true
                }
            )
        }
        if (packs.isEmpty()) item {
            NoteBox("No icon packs installed. Any pack made for Nova, Lawnchair or ADW works. Install one from the Play Store, then come back here.")
        }
        item {
            SettingRow(
                title = "Change one app's icon",
                subtitle = "Go to Apps, select an app, then Change icon. You can pick from any installed pack.",
                onClick = onOpenApps
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Help & recovery, About
// ---------------------------------------------------------------------------------------------

@Composable
private fun HelpSection() {
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    SectionList {
        item { SectionHeader(SettingsSection.HELP.label, SettingsSection.HELP.subtitle) }
        item { SettingRow("Open Android Settings", "Your TV's own settings", onClick = { activity?.let { HomeSetup.openAndroidSettings(it) } }) }
        item { SettingRow("Choose Home app", "Switch Home to Nuvio Launch, or back to your TV's launcher", onClick = { activity?.let { HomeSetup.openHomePicker(it) } }) }
        item {
            SettingRow("Stop Home opening Nuvio", "Home shows the Nuvio Launch home screen instead", chevron = false, onClick = {
                DrawerSettings.setHomeRedirectEnabled(context, false)
            })
        }
        item { SettingRow("Run setup again", "Walk through the first-run steps one more time", onClick = { SetupActivity.open(context) }) }
        item {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(LaunchColors.Panel).padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("If you're ever stuck in Nuvio", color = LaunchColors.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                listOf(
                    "Press Back until you leave Nuvio. You'll land on the Nuvio Launch home screen.",
                    "Press your drawer button: Android Settings is always the last tile.",
                    "On many Google TV remotes, holding Home opens a panel with Settings.",
                    "Android Settings → Apps → Nuvio Launch → Clear defaults also stops it being Home."
                ).forEach { line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("•", color = LaunchColors.Muted, fontSize = 16.sp)
                        Text(line, color = LaunchColors.Muted, fontSize = 16.sp, lineHeight = 23.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutSection() {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            LaunchMark(88.dp)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Nuvio Launch", color = LaunchColors.Text, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                Text("Version $version", color = LaunchColors.Muted, fontSize = 18.sp)
            }
        }
        Text(
            "A companion for Nuvio on Android TV: one remote button opens your apps from anywhere, and Home can take you straight to Nuvio.",
            color = LaunchColors.Muted, fontSize = 18.sp, lineHeight = 27.sp, modifier = Modifier.widthIn(max = 720.dp)
        )
        Text("github.com/homelessbrian/NuvioLaunch", color = LaunchColors.TextSoft, fontSize = 17.sp)
        Spacer(Modifier.height(8.dp))
        // A focusable element so the remote can land in this section.
        SettingRow("Run setup again", chevron = true, onClick = { SetupActivity.open(context) })
    }
}
