package com.homelessbrian.nuviocompanion.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed as listItemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.homelessbrian.nuviocompanion.AppArt
import com.homelessbrian.nuviocompanion.AppArtCache
import com.homelessbrian.nuviocompanion.FocusSnap
import com.homelessbrian.nuviocompanion.AppCatalog
import com.homelessbrian.nuviocompanion.DrawerPosition
import com.homelessbrian.nuviocompanion.DrawerSettings
import com.homelessbrian.nuviocompanion.FilterChip
import com.homelessbrian.nuviocompanion.FocusCard
import com.homelessbrian.nuviocompanion.IconPickerActivity
import com.homelessbrian.nuviocompanion.InstalledApp
import com.homelessbrian.nuviocompanion.KeyHints
import com.homelessbrian.nuviocompanion.LaunchColors
import com.homelessbrian.nuviocompanion.LaunchTheme
import com.homelessbrian.nuviocompanion.LocalHighlight
import com.homelessbrian.nuviocompanion.MainActivity
import com.homelessbrian.nuviocompanion.SettingsSection
import com.homelessbrian.nuviocompanion.SystemShortcuts
import com.homelessbrian.nuviocompanion.designScale
import com.homelessbrian.nuviocompanion.remote.RemoteKeyAccessibilityService
import com.homelessbrian.nuviocompanion.tvClick
import com.homelessbrian.nuviocompanion.update.AppUpdater
import com.homelessbrian.nuviocompanion.update.UpdateBanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Owns the app drawer window. It lives in the AccessibilityService, which is what lets it appear over any app. */
object DrawerOverlayService {
    private var root: ComposeView? = null
    private var owner: OverlayLifecycleOwner? = null
    private var windowManager: WindowManager? = null

    /** Set by the drawer while an inner panel (app options) is open, so Back closes that first. */
    private var backInterceptor: (() -> Boolean)? = null

    fun toggle(service: AccessibilityService) {
        if (root == null) open(service) else close()
    }

    /** Back/Escape dismisses the drawer (or its options panel) before the app underneath sees it. */
    fun handleBackKey(event: KeyEvent): Boolean {
        if (root == null) return false
        if (event.keyCode != KeyEvent.KEYCODE_BACK && event.keyCode != KeyEvent.KEYCODE_ESCAPE) return false
        if (event.action == KeyEvent.ACTION_UP) {
            if (backInterceptor?.invoke() != true) close()
        }
        return event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP
    }

    fun isOpen(): Boolean = root != null

    private fun report(service: AccessibilityService, message: String) {
        service.sendBroadcast(
            Intent(RemoteKeyAccessibilityService.ACTION_OVERLAY_STATUS)
                .setPackage(service.packageName)
                .putExtra(RemoteKeyAccessibilityService.EXTRA_STATUS, message)
        )
    }

    fun close() {
        root?.let { runCatching { windowManager?.removeView(it) } }
        owner?.destroy()
        root = null
        owner = null
        windowManager = null
        backInterceptor = null
    }

    /** Window size and place for each drawer style, in pixels. Sizes follow the 1280 × 720 mockups. */
    private fun layoutParams(context: Context, position: DrawerPosition): WindowManager.LayoutParams {
        val s = designScale(context)
        fun px(units: Float) = (units * s).toInt()
        val wrap = WindowManager.LayoutParams.WRAP_CONTENT
        val (width, height, gravity) = when (position) {
            DrawerPosition.BOTTOM -> Triple(px(1280f - 144f), wrap, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            DrawerPosition.TOP -> Triple(px(1280f - 144f), wrap, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            DrawerPosition.RIGHT -> Triple(px(360f), px(720f - 72f), Gravity.END or Gravity.CENTER_VERTICAL)
            DrawerPosition.LEFT -> Triple(px(360f), px(720f - 72f), Gravity.START or Gravity.CENTER_VERTICAL)
            DrawerPosition.CENTER -> Triple(px(960f), wrap, Gravity.CENTER)
        }
        return WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT
        ).apply {
            this.gravity = gravity
            when (position) {
                DrawerPosition.BOTTOM, DrawerPosition.TOP -> y = px(36f)
                DrawerPosition.LEFT, DrawerPosition.RIGHT -> x = px(36f)
                DrawerPosition.CENTER -> Unit
            }
            // Darker behind the drawer so the app underneath (and its own highlight) fades back.
            dimAmount = if (position == DrawerPosition.CENTER) 0.72f else 0.62f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && DrawerSettings.blurBehind(context)) {
                flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                blurBehindRadius = px(28f)
            }
        }
    }

    private fun open(service: AccessibilityService) {
        var wm: WindowManager? = null
        var lifecycleOwner: OverlayLifecycleOwner? = null
        var composeView: ComposeView? = null

        try {
            report(service, "Shortcut received. Opening app drawer…")
            wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val position = DrawerSettings.position(service)

            lifecycleOwner = OverlayLifecycleOwner().also { it.attach() }
            val ownerRef = lifecycleOwner
            composeView = ComposeView(service).apply {
                setViewTreeLifecycleOwner(ownerRef)
                setViewTreeSavedStateRegistryOwner(ownerRef)
                setViewTreeViewModelStoreOwner(ownerRef)
                isFocusable = true
                isFocusableInTouchMode = true
                setContent {
                    LaunchTheme(DrawerSettings.highlight(service).color) {
                        DrawerContent(
                            context = service,
                            position = position,
                            setBackInterceptor = { backInterceptor = it },
                            onClose = ::close,
                            onLaunch = { pkg ->
                                val opened = if (SystemShortcuts.isShortcut(pkg)) SystemShortcuts.launch(service, pkg) else AppCatalog.launch(service, pkg)
                                if (opened) close() else report(service, "Couldn't open this app. It may no longer be installed.")
                            },
                            onChangeIcon = { pkg ->
                                close()
                                IconPickerActivity.open(service, pkg)
                            },
                            onSettings = {
                                close()
                                MainActivity.open(service, SettingsSection.LOOK)
                            },
                            onUpdate = {
                                close()
                                MainActivity.openUpdate(service)
                            }
                        )
                    }
                }
            }

            lifecycleOwner.start()
            wm.addView(composeView, layoutParams(service, position))
            root = composeView
            owner = lifecycleOwner
            windowManager = wm
            composeView.requestFocus()
            // Leave "touch mode" so the remote can focus the drawer's tiles on the very first open.
            val addedView = composeView
            addedView.post { runCatching { addedView.requestFocusFromTouch() } }
            report(service, "Drawer opened.")
            AppUpdater.maybeCheck(service)
        } catch (error: Throwable) {
            composeView?.let { view -> runCatching { wm?.removeView(view) } }
            lifecycleOwner?.destroy()
            root = null
            owner = null
            windowManager = null
            backInterceptor = null
            runCatching {
                report(service, "Drawer couldn't open: ${error.javaClass.simpleName}: ${error.message ?: "unknown error"}")
            }
        }
    }

    private enum class GridFilter(val label: String) { ALL("All"), FAVORITES("Favorites"), RECENT("Recent") }

    @Composable
    private fun DrawerContent(
        context: Context,
        position: DrawerPosition,
        setBackInterceptor: ((() -> Boolean)?) -> Unit,
        onClose: () -> Unit,
        onLaunch: (String) -> Unit,
        onChangeIcon: (String) -> Unit,
        onSettings: () -> Unit,
        onUpdate: () -> Unit
    ) {
        val hiddenAtOpen = remember { DrawerSettings.hidden(context) }
        var apps by remember { mutableStateOf(AppCatalog.cached().filterNot { it.packageName in hiddenAtOpen }) }
        var shortcuts by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
        var favorites by remember { mutableStateOf(DrawerSettings.favorites(context)) }
        var options by remember { mutableStateOf<InstalledApp?>(null) }
        var gridFilter by remember { mutableStateOf(GridFilter.ALL) }
        val showNames = remember { DrawerSettings.showNames(context) }
        val showIcons = remember { DrawerSettings.showIcons(context) }
        val glow = remember { DrawerSettings.focusGlow(context) }
        val opacity = remember { DrawerSettings.opacity(context) }
        val firstTile = remember { FocusRequester() }
        val firstOption = remember { FocusRequester() }
        val view = LocalView.current
        var focusedLabel by remember { mutableStateOf<String?>(null) }
        var drawerHasFocus by remember { mutableStateOf(false) }

        // Show the last list straight away, then refresh it (new installs, icon changes).
        LaunchedEffect(Unit) {
            val (fresh, tiles) = withContext(Dispatchers.IO) {
                val list = AppCatalog.load(context)
                val extra = SystemShortcuts.tiles(context)
                extra.forEach { AppArtCache.prepare(it) }
                list to extra
            }
            // Only redraw the tiles when something actually changed (new app, new icon, new name).
            fun fingerprint(list: List<InstalledApp>) = list.map { Triple(it.packageName, it.label, it.artKey) }
            if (fingerprint(fresh) != fingerprint(apps)) apps = fresh
            if (shortcuts.isEmpty() || fingerprint(tiles) != fingerprint(shortcuts)) shortcuts = tiles
        }
        // Take the remote's focus. A freshly added window can start in "touch mode", where TV
        // tiles can't be focused, so leave touch mode first and keep trying for a few frames.
        LaunchedEffect(apps.isNotEmpty() || shortcuts.isNotEmpty()) {
            runCatching { view.requestFocusFromTouch() }
            repeat(12) {
                withFrameNanos { }
                if (drawerHasFocus || options != null) return@LaunchedEffect
                runCatching { firstTile.requestFocus() }
            }
        }
        LaunchedEffect(options) {
            setBackInterceptor(if (options != null) ({ options = null; true }) else null)
            withFrameNanos { }
            runCatching { if (options != null) firstOption.requestFocus() else firstTile.requestFocus() }
        }

        val recents = remember { DrawerSettings.recents(context) }
        val visibleApps = when (gridFilter) {
            GridFilter.ALL -> apps
            GridFilter.FAVORITES -> apps.filter { it.packageName in favorites }
            GridFilter.RECENT -> apps.filter { it.packageName in recents }.sortedByDescending { recents[it.packageName] ?: 0L }
        }
        val items = visibleApps + shortcuts
        val shape = RoundedCornerShape(28.dp)
        // Slim "Update available" strip at the top of the drawer. Shows nothing when up to date.
        val updateBanner: @Composable (Modifier) -> Unit = { mod ->
            UpdateBanner(
                modifier = mod,
                compact = true,
                stacked = position == DrawerPosition.LEFT || position == DrawerPosition.RIGHT,
                onInstall = onUpdate,
                onLater = { AppUpdater.snooze(context) },
                onOpenPermission = onUpdate
            )
        }

        val tileFor: @Composable (Int, InstalledApp, TileStyle) -> Unit = { index, app, style ->
            DrawerTile(
                app = app,
                style = style,
                showName = showNames,
                showArt = showIcons,
                glow = glow,
                modifier = if (index == 0) Modifier.focusRequester(firstTile) else Modifier,
                onFocused = { focusedLabel = app.label },
                onClick = { onLaunch(app.packageName) },
                onLongClick = if (SystemShortcuts.isShortcut(app.packageName)) null else ({ options = app })
            )
        }

        Box(
            Modifier.fillMaxWidth()
                .then(if (position == DrawerPosition.LEFT || position == DrawerPosition.RIGHT) Modifier.fillMaxHeight() else Modifier)
                .shadow(24.dp, shape, clip = false)
                .clip(shape)
                .background(Color(0xFF16161C).copy(alpha = opacity))
                .border(1.dp, Color(0x1FFFFFFF), shape)
                .onFocusChanged { drawerHasFocus = it.hasFocus }
                // If nothing in the drawer has focus yet, the first remote press lands on the first app.
                .onPreviewKeyEvent { event ->
                    val code = event.nativeKeyEvent.keyCode
                    val navKey = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT ||
                        code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN ||
                        code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                    if (!drawerHasFocus && navKey && options == null) {
                        if (event.type == KeyEventType.KeyDown) runCatching { firstTile.requestFocus() }
                        true
                    } else false
                }
        ) {
            val current = options
            when (position) {
                DrawerPosition.BOTTOM, DrawerPosition.TOP -> Column(
                    Modifier.fillMaxWidth().padding(start = 14.dp, end = 18.dp, top = 12.dp, bottom = 4.dp)
                ) {
                    if (current != null) {
                        OptionsBar(current, favorites, firstOption, onFavorite = {
                            favorites = toggleFavorite(context, current.packageName, favorites)
                            options = null
                        }, onHide = {
                            hide(context, current.packageName)
                            apps = apps.filterNot { it.packageName == current.packageName }
                            options = null
                        }, onIcon = { onChangeIcon(current.packageName) }, onCancel = { options = null })
                    } else {
                        updateBanner(Modifier.padding(start = 6.dp, end = 2.dp, top = 2.dp, bottom = 8.dp))
                        // The app you're on, small key hints, and a way to Settings (press Up).
                        Row(Modifier.fillMaxWidth().padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                // Only with "Show app names" on.
                                if (showNames) focusedLabel.orEmpty() else "",
                                color = LaunchColors.Text,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            KeyHints("Back" to "close", "Hold OK" to "options", small = true)
                            Spacer(Modifier.width(14.dp))
                            HeaderButton("Settings", onSettings)
                        }
                    }
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        // Room around the tiles for the focused one to grow and glow.
                        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 18.dp)
                    ) {
                        listItemsIndexed(items, key = { _, app -> app.packageName }) { index, app ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (app.packageName == shortcuts.firstOrNull()?.packageName && visibleApps.isNotEmpty()) {
                                    Box(Modifier.padding(end = 18.dp).width(1.dp).height(60.dp).background(Color(0x24FFFFFF)))
                                }
                                tileFor(index, app, if (SystemShortcuts.isShortcut(app.packageName)) TileStyle.DOCK_SQUARE else TileStyle.DOCK)
                            }
                        }
                    }
                }
                DrawerPosition.LEFT, DrawerPosition.RIGHT -> Column(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (current != null) {
                        OptionsColumn(current, favorites, firstOption, onFavorite = {
                            favorites = toggleFavorite(context, current.packageName, favorites)
                            options = null
                        }, onHide = {
                            hide(context, current.packageName)
                            apps = apps.filterNot { it.packageName == current.packageName }
                            options = null
                        }, onIcon = { onChangeIcon(current.packageName) }, onCancel = { options = null })
                    } else {
                        updateBanner(Modifier.padding(bottom = 8.dp))
                        LazyColumn(
                            Modifier.weight(1f).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(if (showNames) 6.dp else 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            // Without names the tiles grow and glow, so leave room around them.
                            contentPadding = if (showNames) PaddingValues(vertical = 4.dp, horizontal = 4.dp) else PaddingValues(vertical = 18.dp, horizontal = 4.dp)
                        ) {
                            listItemsIndexed(visibleApps, key = { _, app -> app.packageName }) { index, app ->
                                tileFor(index, app, TileStyle.RAIL)
                            }
                        }
                        Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).background(Color(0x1AFFFFFF)))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            shortcuts.forEach { shortcut ->
                                RailButton(
                                    if (shortcut.packageName == SystemShortcuts.ANDROID_SETTINGS) "Android" else "Nuvio Launch",
                                    Modifier.weight(1f).then(if (visibleApps.isEmpty() && shortcut == shortcuts.first()) Modifier.focusRequester(firstTile) else Modifier)
                                ) { onLaunch(shortcut.packageName) }
                            }
                        }
                    }
                }
                DrawerPosition.CENTER -> Column(
                    Modifier.fillMaxWidth().heightIn(max = 600.dp).padding(start = 32.dp, end = 32.dp, top = 28.dp, bottom = 30.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp)
                ) {
                    if (current != null) {
                        OptionsBar(current, favorites, firstOption, onFavorite = {
                            favorites = toggleFavorite(context, current.packageName, favorites)
                            options = null
                        }, onHide = {
                            hide(context, current.packageName)
                            apps = apps.filterNot { it.packageName == current.packageName }
                            options = null
                        }, onIcon = { onChangeIcon(current.packageName) }, onCancel = { options = null })
                    } else {
                        updateBanner(Modifier)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Apps", color = LaunchColors.Text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(22.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                GridFilter.entries.forEach { option -> FilterChip(option.label, gridFilter == option) { gridFilter = option } }
                            }
                            Spacer(Modifier.weight(1f))
                            FilterChip("Drawer settings", false, onSettings)
                        }
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(5),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                        contentPadding = PaddingValues(18.dp)
                    ) {
                        itemsIndexed(items, key = { _, app -> app.packageName }) { index, app ->
                            tileFor(index, app, TileStyle.GRID)
                        }
                    }
                }
            }
            if (items.isEmpty()) {
                Text("Loading your apps…", color = LaunchColors.Muted, fontSize = 17.sp, modifier = Modifier.align(Alignment.Center).padding(30.dp))
            }
        }
    }

    private fun toggleFavorite(context: Context, pkg: String, current: Set<String>): Set<String> {
        val updated = if (pkg in current) current - pkg else current + pkg
        DrawerSettings.setFavorites(context, updated)
        return updated
    }

    private fun hide(context: Context, pkg: String) {
        DrawerSettings.setHidden(context, DrawerSettings.hidden(context) + pkg)
    }

    private enum class TileStyle { DOCK, DOCK_SQUARE, RAIL, GRID }

    @Composable
    private fun DrawerTile(
        app: InstalledApp,
        style: TileStyle,
        showName: Boolean,
        showArt: Boolean,
        glow: Boolean,
        modifier: Modifier,
        onFocused: () -> Unit,
        onClick: () -> Unit,
        onLongClick: (() -> Unit)?
    ) {
        var focused by remember { mutableStateOf(false) }
        val highlight = LocalHighlight.current
        // The focused app grows a little and glows in the chosen highlight color.
        // The side rail with names off is just artwork, so it highlights like the other styles.
        val railArtOnly = style == TileStyle.RAIL && !showName
        val listRow = style == TileStyle.RAIL && showName
        val scale by animateFloatAsState(if (focused && !listRow) 1.12f else 1f, FocusSnap, label = "tile")
        val glowAmount by animateFloatAsState(if (focused) 1f else 0f, FocusSnap, label = "glow")
        val (artWidth, artHeight) = if (railArtOnly) 176.dp to 99.dp else when (style) {
            TileStyle.DOCK -> 150.dp to 84.dp
            TileStyle.DOCK_SQUARE -> 84.dp to 84.dp
            TileStyle.RAIL -> 96.dp to 54.dp
            TileStyle.GRID -> 164.dp to 92.dp
        }
        val corner = if (listRow) 9.dp else 14.dp
        val art: @Composable () -> Unit = {
            Box(
                Modifier.graphicsLayer { scaleX = scale; scaleY = scale }
                    .then(if (glow && !listRow) Modifier.focusGlow(highlight, corner, glowAmount) else Modifier)
                    .then(if (focused && !listRow) Modifier.border(3.dp, highlight, RoundedCornerShape(corner)) else Modifier)
            ) {
                if (showArt || SystemShortcuts.isShortcut(app.packageName)) {
                    AppArt(app, Modifier.width(artWidth).height(artHeight), corner)
                } else {
                    NameOnlyArt(app.label, artWidth, artHeight, corner)
                }
            }
        }
        val label: @Composable (Boolean) -> Unit = { bold ->
            Text(
                app.label,
                color = if (style == TileStyle.RAIL && focused) LaunchColors.OnFocus else if (focused) LaunchColors.Text else LaunchColors.Muted,
                fontSize = if (style == TileStyle.RAIL) 18.sp else 13.sp,
                fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (style == TileStyle.RAIL) TextAlign.Start else TextAlign.Center
            )
        }
        val clickModifier = modifier
            .onFocusChanged {
                if (it.isFocused && !focused) onFocused()
                focused = it.isFocused
            }
            .tvClick(onClick, onLongClick)

        when {
            listRow -> Row(
                clickModifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(if (focused) LaunchColors.Text else Color.Transparent)
                    .then(if (focused) Modifier.border(3.dp, highlight, RoundedCornerShape(16.dp)) else Modifier)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                art()
                label(focused)
            }
            else -> Column(
                clickModifier.width(artWidth),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                art()
                // Names only with "Show app names" on. No empty space is kept for them otherwise.
                if (showName) {
                    Box(Modifier.padding(top = 10.dp).height(18.dp), contentAlignment = Alignment.Center) { label(focused) }
                }
            }
        }
    }

    /** A soft halo in the highlight color around the focused tile, fading out from its edge. */
    private fun Modifier.focusGlow(color: Color, corner: Dp, amount: Float): Modifier = drawBehind {
        if (amount <= 0f) return@drawBehind
        val steps = 6
        val reach = 12.dp.toPx()
        for (i in steps downTo 1) {
            val grow = reach * i / steps
            drawRoundRect(
                color = color.copy(alpha = 0.085f * amount),
                topLeft = Offset(-grow, -grow),
                size = Size(size.width + grow * 2, size.height + grow * 2),
                cornerRadius = CornerRadius(corner.toPx() + grow)
            )
        }
    }

    /** Small pill in the drawer header ("Settings"). */
    @Composable
    private fun HeaderButton(label: String, onClick: () -> Unit) {
        FocusCard(shape = RoundedCornerShape(999.dp), idle = LaunchColors.Raised, scaleOnFocus = 1.06f, onClick = onClick) { focused ->
            Text(
                label,
                color = if (focused) LaunchColors.OnFocus else LaunchColors.TextSoft,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp)
            )
        }
    }

    @Composable
    private fun NameOnlyArt(label: String, width: Dp, height: Dp, corner: Dp) {
        Box(
            Modifier.width(width).height(height).clip(RoundedCornerShape(corner)).background(LaunchColors.Tile),
            contentAlignment = Alignment.Center
        ) {
            Text(label, color = LaunchColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2, textAlign = TextAlign.Center, modifier = Modifier.padding(8.dp))
        }
    }

    @Composable
    private fun RailButton(label: String, modifier: Modifier, onClick: () -> Unit) {
        FocusCard(modifier = modifier, shape = RoundedCornerShape(14.dp), idle = LaunchColors.Tile, onClick = onClick) { focused ->
            Text(
                label,
                color = if (focused) LaunchColors.OnFocus else LaunchColors.Text,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth().padding(12.dp)
            )
        }
    }

    /** Options for one app, shown after holding OK on it. Back closes them. */
    @Composable
    private fun OptionsBar(
        app: InstalledApp,
        favorites: Set<String>,
        firstOption: FocusRequester,
        onFavorite: () -> Unit,
        onHide: () -> Unit,
        onIcon: () -> Unit,
        onCancel: () -> Unit
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(app.label, color = LaunchColors.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            OptionButton(if (app.packageName in favorites) "Remove favorite" else "Add to favorites", Modifier.focusRequester(firstOption), onFavorite)
            OptionButton("Hide", Modifier, onHide)
            OptionButton("Change icon", Modifier, onIcon)
            OptionButton("Cancel", Modifier, onCancel)
        }
    }

    @Composable
    private fun OptionsColumn(
        app: InstalledApp,
        favorites: Set<String>,
        firstOption: FocusRequester,
        onFavorite: () -> Unit,
        onHide: () -> Unit,
        onIcon: () -> Unit,
        onCancel: () -> Unit
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AppArt(app, Modifier.width(160.dp).height(90.dp), 12.dp)
            Text(app.label, color = LaunchColors.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            OptionButton(if (app.packageName in favorites) "Remove favorite" else "Add to favorites", Modifier.fillMaxWidth().focusRequester(firstOption), onFavorite)
            OptionButton("Hide", Modifier.fillMaxWidth(), onHide)
            OptionButton("Change icon", Modifier.fillMaxWidth(), onIcon)
            OptionButton("Cancel", Modifier.fillMaxWidth(), onCancel)
        }
    }

    @Composable
    private fun OptionButton(label: String, modifier: Modifier, onClick: () -> Unit) {
        FocusCard(modifier = modifier, shape = RoundedCornerShape(12.dp), idle = LaunchColors.Tile, scaleOnFocus = 1.04f, onClick = onClick) { focused ->
            Text(
                label,
                color = if (focused) LaunchColors.OnFocus else LaunchColors.Text,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
            )
        }
    }

    private class OverlayLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
        private val registry = LifecycleRegistry(this)
        private val savedStateController = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry get() = savedStateController.savedStateRegistry
        override val viewModelStore = ViewModelStore()

        fun attach() {
            savedStateController.performAttach()
            savedStateController.performRestore(null)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        }

        fun start() {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        fun destroy() {
            if (registry.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            }
            if (registry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            }
            if (registry.currentState != Lifecycle.State.DESTROYED) {
                registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            }
            viewModelStore.clear()
        }
    }
}
