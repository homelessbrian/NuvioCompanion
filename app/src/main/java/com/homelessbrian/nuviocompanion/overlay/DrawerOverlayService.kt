package com.homelessbrian.nuviocompanion.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as columnItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
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
import com.homelessbrian.nuviocompanion.AppCatalog
import com.homelessbrian.nuviocompanion.DrawerPosition
import com.homelessbrian.nuviocompanion.DrawerSettings

/** Owns the system overlay from the AccessibilityService context, which is required for ACCESSIBILITY_OVERLAY. */
object DrawerOverlayService {
    private var root: ComposeView? = null
    private var owner: OverlayLifecycleOwner? = null
    private var windowManager: WindowManager? = null

    fun toggle(service: AccessibilityService) {
        if (root == null) open(service) else close()
    }

    /** Back/Escape dismisses the modal drawer before the underlying app receives input. */
    fun handleBackKey(event: KeyEvent): Boolean {
        if (root == null) return false
        if (event.keyCode != KeyEvent.KEYCODE_BACK && event.keyCode != KeyEvent.KEYCODE_ESCAPE) return false
        if (event.action == KeyEvent.ACTION_UP) close()
        return event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP
    }

    fun isOpen(): Boolean = root != null

    private fun report(service: AccessibilityService, message: String) {
        service.sendBroadcast(
            Intent(com.homelessbrian.nuviocompanion.remote.RemoteKeyAccessibilityService.ACTION_OVERLAY_STATUS)
                .setPackage(service.packageName)
                .putExtra(com.homelessbrian.nuviocompanion.remote.RemoteKeyAccessibilityService.EXTRA_STATUS, message)
        )
    }
    fun close() {
        root?.let { runCatching { windowManager?.removeView(it) } }
        owner?.destroy()
        root = null; owner = null; windowManager = null
    }

    private fun updateSettingsWindow(context: Context) {
        val view = root ?: return
        val wm = windowManager ?: return
        val metrics = context.resources.displayMetrics
        val params = (view.layoutParams as? WindowManager.LayoutParams) ?: return
        params.width = (metrics.widthPixels * .78f).toInt()
        params.height = (metrics.heightPixels * .78f).toInt()
        params.gravity = Gravity.CENTER
        runCatching { wm.updateViewLayout(view, params) }
    }

    private fun updatePosition(context: Context, position: DrawerPosition) {
        val view = root ?: return
        val wm = windowManager ?: return
        val metrics = context.resources.displayMetrics
        val params = (view.layoutParams as? WindowManager.LayoutParams) ?: return
        when (position) {
            DrawerPosition.TOP -> {
                params.width = (metrics.widthPixels * .68f).toInt()
                params.height = (metrics.heightPixels * .18f).toInt()
                params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            }
            DrawerPosition.BOTTOM -> {
                params.width = (metrics.widthPixels * .68f).toInt()
                params.height = (metrics.heightPixels * .18f).toInt()
                params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            }
            DrawerPosition.LEFT -> {
                params.width = (metrics.widthPixels * .085f).toInt()
                params.height = (metrics.heightPixels * .82f).toInt()
                params.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            }
            DrawerPosition.RIGHT -> {
                params.width = (metrics.widthPixels * .085f).toInt()
                params.height = (metrics.heightPixels * .82f).toInt()
                params.gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
            DrawerPosition.CENTER -> {
                params.width = (metrics.widthPixels * .72f).toInt()
                params.height = (metrics.heightPixels * .68f).toInt()
                params.gravity = Gravity.CENTER
            }
        }
        runCatching { wm.updateViewLayout(view, params) }
    }

    private fun open(service: AccessibilityService) {
        var wm: WindowManager? = null
        var lifecycleOwner: OverlayLifecycleOwner? = null
        var composeView: ComposeView? = null

        try {
            report(service, "Shortcut received. Creating app drawer…")
            wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val position = DrawerSettings.position(service)
            val (width, height, gravity) = when (position) {
                DrawerPosition.TOP -> Triple((service.resources.displayMetrics.widthPixels * .68f).toInt(), (service.resources.displayMetrics.heightPixels * .18f).toInt(), Gravity.TOP or Gravity.CENTER_HORIZONTAL)
                DrawerPosition.BOTTOM -> Triple((service.resources.displayMetrics.widthPixels * .68f).toInt(), (service.resources.displayMetrics.heightPixels * .18f).toInt(), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                DrawerPosition.LEFT -> Triple((service.resources.displayMetrics.widthPixels * .085f).toInt(), (service.resources.displayMetrics.heightPixels * .82f).toInt(), Gravity.START or Gravity.CENTER_VERTICAL)
                DrawerPosition.RIGHT -> Triple((service.resources.displayMetrics.widthPixels * .085f).toInt(), (service.resources.displayMetrics.heightPixels * .82f).toInt(), Gravity.END or Gravity.CENTER_VERTICAL)
                DrawerPosition.CENTER -> Triple((service.resources.displayMetrics.widthPixels * .72f).toInt(), (service.resources.displayMetrics.heightPixels * .68f).toInt(), Gravity.CENTER)
            }

            lifecycleOwner = OverlayLifecycleOwner().also { it.attach() }
            composeView = ComposeView(service).apply {
                setViewTreeLifecycleOwner(lifecycleOwner!!)
                setViewTreeSavedStateRegistryOwner(lifecycleOwner!!)
                setViewTreeViewModelStoreOwner(lifecycleOwner!!)
                isFocusable = true
                isFocusableInTouchMode = true
                setOnKeyListener { _, keyCode, event ->
                    if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                        close()
                        true
                    } else false
                }
                setContent {
                    MaterialTheme {
                        DrawerContent(
                            service,
                            onClose = ::close,
                            onLaunch = { pkg -> if (AppCatalog.launch(service, pkg)) close() else report(service, "Could not open this app. It may no longer be installed.") }
                        )
                    }
                }
            }

            val params = WindowManager.LayoutParams(
                width,
                height,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_DIM_BEHIND,
                PixelFormat.TRANSLUCENT
            ).apply {
                this.gravity = gravity
                dimAmount = .34f
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                    blurBehindRadius = 28
                }
            }

            lifecycleOwner!!.start()
            wm.addView(composeView, params)
            root = composeView
            owner = lifecycleOwner
            windowManager = wm
            composeView.requestFocus()
            report(service, "Drawer opened successfully.")
        } catch (error: Throwable) {
            composeView?.let { view -> runCatching { wm?.removeView(view) } }
            lifecycleOwner?.destroy()
            root = null
            owner = null
            windowManager = null
            runCatching {
                report(service, "Drawer startup failed: ${error.javaClass.simpleName}: ${error.message ?: "unknown error"}")
            }
        }
    }

    @Composable
    private fun DrawerContent(context: Context, onClose: () -> Unit, onLaunch: (String) -> Unit) {
        val allApps = remember { AppCatalog.load(context, true) }
        var showingSettings by remember { mutableStateOf(false) }
        var hidden by remember { mutableStateOf(DrawerSettings.hidden(context)) }
        var favorites by remember { mutableStateOf(DrawerSettings.favorites(context)) }
        var position by remember { mutableStateOf(DrawerSettings.position(context)) }
        var showNames by remember { mutableStateOf(DrawerSettings.showNames(context)) }
        var showIcons by remember { mutableStateOf(DrawerSettings.showIcons(context)) }
        var focusGlow by remember { mutableStateOf(DrawerSettings.focusGlow(context)) }
        var opacity by remember { mutableStateOf(DrawerSettings.opacity(context)) }
        val isSide = position == DrawerPosition.LEFT || position == DrawerPosition.RIGHT
        val isCenter = position == DrawerPosition.CENTER
        val visibleApps = allApps.filter { it.packageName !in hidden }
            .sortedWith(compareByDescending<com.homelessbrian.nuviocompanion.InstalledApp> { it.packageName in favorites }
                .thenBy { it.label.lowercase() })
        val panel = Color(0xFF1A1A1A).copy(alpha = opacity)
        LaunchedEffect(showingSettings, position) {
            if (showingSettings) updateSettingsWindow(context) else updatePosition(context, position)
        }
        val tile = Color(0xFF222222)
        val accent = Color(0xFFF5F5F5)

        Surface(
            Modifier.fillMaxSize()
                .onPreviewKeyEvent { event ->
                    val back = event.key == Key.Back || event.key == Key.Escape
                    if (back && event.type == KeyEventType.KeyUp) { onClose(); true } else back
                }
                .shadow(18.dp, RoundedCornerShape(24.dp), clip = false),
            shape = RoundedCornerShape(24.dp),
            color = panel,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.20f))
        ) {
            if (showingSettings) {
                LazyColumn(
                    Modifier.fillMaxSize().focusGroup().padding(horizontal = 22.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("APP DRAWER SETTINGS", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                Text("Personalize your app drawer", color = Color(0xFFB3B3B3), fontSize = 12.sp)
                            }
                            DrawerAction("DONE", true) { showingSettings = false }
                        }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Text("POSITION", color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                DrawerPosition.entries.forEach { option ->
                                    DrawerAction(option.name, position == option, Modifier.weight(1f)) {
                                        position = option
                                        DrawerSettings.setPosition(context, option)
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Text("APPEARANCE", color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                DrawerAction(if (showNames) "APP NAMES  ✓" else "APP NAMES", showNames) {
                                    showNames = !showNames
                                    DrawerSettings.setShowNames(context, showNames)
                                }
                                DrawerAction(if (showIcons) "APP ICONS  ✓" else "APP ICONS", showIcons) {
                                    showIcons = !showIcons
                                    DrawerSettings.setShowIcons(context, showIcons)
                                }
                                DrawerAction(if (focusGlow) "FOCUS GLOW  ✓" else "FOCUS GLOW", focusGlow) {
                                    focusGlow = !focusGlow
                                    DrawerSettings.setFocusGlow(context, focusGlow)
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("PANEL OPACITY", color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                listOf(0.72f, 0.84f, 0.94f).forEachIndexed { index, value ->
                                    DrawerAction(listOf("70%", "85%", "95%")[index], opacity == value) {
                                        opacity = value
                                        DrawerSettings.setOpacity(context, value)
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            DividerLine()
                            Text("MANAGE APPS", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text("Choose which apps appear. Pin favorites to keep them first.", color = Color(0xFFB3B3B3), fontSize = 11.sp)
                            Text("${visibleApps.size} visible · ${allApps.size} installed", color = accent, fontSize = 11.sp)
                        }
                    }
                    columnItems(allApps, key = { it.packageName }) { app ->
                        Row(
                            Modifier.fillMaxWidth().background(tile, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Image(app.icon.toBitmap(96, 96).asImageBitmap(), app.label, Modifier.size(46.dp))
                            Spacer(Modifier.width(14.dp))
                            Text(app.label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1)
                            DrawerAction(if (app.packageName in favorites) "★" else "☆", app.packageName in favorites) {
                                favorites = if (app.packageName in favorites) favorites - app.packageName else favorites + app.packageName
                                DrawerSettings.setFavorites(context, favorites)
                            }
                            Spacer(Modifier.width(10.dp))
                            Switch(
                                checked = app.packageName !in hidden,
                                onCheckedChange = { visible ->
                                    hidden = if (visible) hidden - app.packageName else hidden + app.packageName
                                    DrawerSettings.setHidden(context, hidden)
                                }
                            )
                        }
                    }
                }
            } else if (isCenter) {
                Column(Modifier.fillMaxSize().padding(22.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("▦", color = accent, fontSize = 22.sp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Apps", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                            Text("${visibleApps.size} apps installed", color = Color(0xFFB3B3B3), fontSize = 11.sp)
                        }
                        DrawerAction("⚙  SETTINGS", false) { showingSettings = true }
                        Spacer(Modifier.width(8.dp))
                        DrawerAction("×", false, onClick = onClose)
                    }
                    Spacer(Modifier.height(18.dp))
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(5),
                        modifier = Modifier.fillMaxSize().focusGroup(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = PaddingValues(6.dp)
                    ) {
                        items(visibleApps, key = { it.packageName }) { app ->
                            CenterAppTile(app, showNames, showIcons, focusGlow) { onLaunch(app.packageName) }
                        }
                    }
                }
            } else if (isSide) {
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("⠿", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    LazyColumn(
                        Modifier.weight(1f).fillMaxWidth().focusGroup(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        columnItems(visibleApps, key = { it.packageName }) { app ->
                            SideAppTile(app, showNames, showIcons, focusGlow) { onLaunch(app.packageName) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    DrawerAction("⚙", false) { showingSettings = true }
                }
            } else {
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 13.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("⠿", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        DrawerAction("⠿", false) { showingSettings = false }
                        Spacer(Modifier.width(8.dp))
                        DrawerAction("⚙", false) { showingSettings = true }
                        Spacer(Modifier.width(8.dp))
                        DrawerAction("×", false, onClick = onClose)
                    }
                    Spacer(Modifier.height(8.dp))
                    LazyRow(
                        Modifier.fillMaxWidth().focusGroup(),
                        horizontalArrangement = Arrangement.spacedBy(13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        contentPadding = PaddingValues(vertical = 9.dp, horizontal = 4.dp)
                    ) {
                        rowItems(visibleApps, key = { it.packageName }) { app ->
                            DockAppTile(app, showNames, showIcons, focusGlow) { onLaunch(app.packageName) }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun DrawerAction(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
        val shape = RoundedCornerShape(12.dp)
        Text(
            label,
            color = if (selected) Color(0xFF111111) else Color(0xFFE0E0E0),
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            modifier = modifier
                .background(if (selected) Color(0xFFF5F5F5) else Color(0xFF2D2D2D), shape)
                .then(if (selected) Modifier.border(1.dp, Color(0xFFFFFFFF), shape) else Modifier)
                .clickable(onClick = onClick)
                .focusable()
                .padding(horizontal = 13.dp, vertical = 10.dp)
        )
    }

    @Composable
    private fun DockAppTile(
        app: com.homelessbrian.nuviocompanion.InstalledApp,
        showNames: Boolean,
        showIcons: Boolean,
        focusGlow: Boolean,
        onClick: () -> Unit
    ) {
        var focused by remember(app.packageName) { mutableStateOf(false) }
        val scale by animateFloatAsState(if (focused) 1.07f else 1f, label = "dockFocus")
        val shape = RoundedCornerShape(17.dp)
        Column(
            Modifier.width(74.dp).height(88.dp).graphicsLayer { scaleX = scale; scaleY = scale }
                .onFocusChanged { focused = it.isFocused }
                .shadow(if (focused && focusGlow) 22.dp else 5.dp, shape, clip = false)
                .background(if (focused) Color(0xFF303030) else Color(0xFF1E1E1E), shape)
                .then(if (focused && focusGlow) Modifier.border(2.dp, Color(0xFFFFFFFF), shape) else Modifier)
                .clickable(onClick = onClick).focusable().padding(vertical = 8.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (showIcons) Image(app.icon.toBitmap(128, 128).asImageBitmap(), app.label, Modifier.size(52.dp))
            if (showNames) Text(app.label, color = Color.White, fontSize = 9.sp, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
        }
    }

    @Composable
    private fun SideAppTile(
        app: com.homelessbrian.nuviocompanion.InstalledApp,
        showNames: Boolean,
        showIcons: Boolean,
        focusGlow: Boolean,
        onClick: () -> Unit
    ) {
        var focused by remember(app.packageName) { mutableStateOf(false) }
        val shape = RoundedCornerShape(15.dp)
        Column(
            Modifier.width(52.dp).height(68.dp).onFocusChanged { focused = it.isFocused }
                .shadow(if (focused && focusGlow) 22.dp else 5.dp, shape, clip = false)
                .background(if (focused) Color(0xFF303030) else Color(0xFF1E1E1E), shape)
                .then(if (focused && focusGlow) Modifier.border(2.dp, Color(0xFFFFFFFF), shape) else Modifier)
                .clickable(onClick = onClick).focusable().padding(vertical = 8.dp, horizontal = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (showIcons) Image(app.icon.toBitmap(112, 112).asImageBitmap(), app.label, Modifier.size(40.dp))
            if (showNames) Text(app.label, color = Color.White, fontSize = 9.sp, maxLines = 1)
        }
    }

    @Composable
    private fun CenterAppTile(
        app: com.homelessbrian.nuviocompanion.InstalledApp,
        showNames: Boolean,
        showIcons: Boolean,
        focusGlow: Boolean,
        onClick: () -> Unit
    ) {
        var focused by remember(app.packageName) { mutableStateOf(false) }
        val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "gridFocus")
        val shape = RoundedCornerShape(17.dp)
        Column(
            Modifier.fillMaxWidth().height(96.dp).graphicsLayer { scaleX = scale; scaleY = scale }
                .onFocusChanged { focused = it.isFocused }
                .shadow(if (focused && focusGlow) 22.dp else 5.dp, shape, clip = false)
                .background(if (focused) Color(0xFF303030) else Color(0xFF1E1E1E), shape)
                .then(if (focused && focusGlow) Modifier.border(2.dp, Color(0xFFFFFFFF), shape) else Modifier)
                .clickable(onClick = onClick).focusable().padding(9.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (showIcons) Image(app.icon.toBitmap(128, 128).asImageBitmap(), app.label, Modifier.size(58.dp))
            if (showNames) Text(app.label, color = Color.White, fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(top = 7.dp))
        }
    }

    @Composable
    private fun DividerLine() {
        Spacer(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF333333)))
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
