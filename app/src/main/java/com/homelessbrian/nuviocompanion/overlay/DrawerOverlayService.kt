package com.homelessbrian.nuviocompanion.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
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

    private fun updatePosition(context: Context, position: DrawerPosition) {
        val view = root ?: return
        val wm = windowManager ?: return
        val metrics = context.resources.displayMetrics
        val params = (view.layoutParams as? WindowManager.LayoutParams) ?: return
        when (position) {
            DrawerPosition.TOP -> {
                params.width = WindowManager.LayoutParams.MATCH_PARENT
                params.height = (metrics.heightPixels * .36f).toInt()
                params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            }
            DrawerPosition.BOTTOM -> {
                params.width = WindowManager.LayoutParams.MATCH_PARENT
                params.height = (metrics.heightPixels * .36f).toInt()
                params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            }
            DrawerPosition.LEFT -> {
                params.width = (metrics.widthPixels * .38f).toInt()
                params.height = WindowManager.LayoutParams.MATCH_PARENT
                params.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            }
            DrawerPosition.RIGHT -> {
                params.width = (metrics.widthPixels * .38f).toInt()
                params.height = WindowManager.LayoutParams.MATCH_PARENT
                params.gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
            DrawerPosition.CENTER -> {
                params.width = (metrics.widthPixels * .84f).toInt()
                params.height = (metrics.heightPixels * .78f).toInt()
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
                DrawerPosition.TOP -> Triple(WindowManager.LayoutParams.MATCH_PARENT, (service.resources.displayMetrics.heightPixels * .36f).toInt(), Gravity.TOP or Gravity.CENTER_HORIZONTAL)
                DrawerPosition.BOTTOM -> Triple(WindowManager.LayoutParams.MATCH_PARENT, (service.resources.displayMetrics.heightPixels * .36f).toInt(), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                DrawerPosition.LEFT -> Triple((service.resources.displayMetrics.widthPixels * .38f).toInt(), WindowManager.LayoutParams.MATCH_PARENT, Gravity.START or Gravity.CENTER_VERTICAL)
                DrawerPosition.RIGHT -> Triple((service.resources.displayMetrics.widthPixels * .38f).toInt(), WindowManager.LayoutParams.MATCH_PARENT, Gravity.END or Gravity.CENTER_VERTICAL)
                DrawerPosition.CENTER -> Triple((service.resources.displayMetrics.widthPixels * .84f).toInt(), (service.resources.displayMetrics.heightPixels * .78f).toInt(), Gravity.CENTER)
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
                dimAmount = .22f
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
        val isSide = position == DrawerPosition.LEFT || position == DrawerPosition.RIGHT
        val isCenter = position == DrawerPosition.CENTER
        val visibleApps = allApps.filter { it.packageName !in hidden }
            .sortedByDescending { it.packageName in favorites }
        val bg = Color(0xF20C1220)
        val tile = Color(0xFF182338)
        val accent = Color(0xFF9B8CFF)

        Surface(
            Modifier.fillMaxSize()
                .onPreviewKeyEvent { event ->
                    val isBack = event.key == Key.Back || event.key == Key.Escape
                    if (isBack && event.type == KeyEventType.KeyUp) {
                        onClose()
                        true
                    } else {
                        isBack
                    }
                }
                .padding(8.dp),
            shape = RoundedCornerShape(22.dp),
            color = bg
        ) {
            Column(Modifier.fillMaxSize().padding(if (isSide) 14.dp else 18.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(if (showingSettings) "DRAWER SETTINGS" else "YOUR APPS", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (showingSettings) "Customize this drawer without leaving your app" else "${visibleApps.size} apps · Select an app to open",
                            color = Color(0xFF9EABC3),
                            fontSize = 11.sp
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        DrawerAction("Apps", !showingSettings) { showingSettings = false }
                        DrawerAction("Settings", showingSettings) { showingSettings = true }
                        DrawerAction("×", false, onClose)
                    }
                }
                Spacer(Modifier.height(12.dp))

                if (showingSettings) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("POSITION", color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            DrawerPosition.entries.forEach { option ->
                                DrawerAction(option.name.lowercase().replaceFirstChar { it.uppercase() }, position == option) {
                                    position = option
                                    DrawerSettings.setPosition(context, option)
                                    updatePosition(context, option)
                                }
                            }
                        }
                        Text("VISIBLE APPS", color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            columnItems(allApps, key = { it.packageName }) { app ->
                                Row(
                                    Modifier.fillMaxWidth().background(tile, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Image(app.icon.toBitmap(72, 72).asImageBitmap(), app.label, Modifier.size(30.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Text(app.label, color = Color.White, modifier = Modifier.weight(1f), maxLines = 1, fontSize = 12.sp)
                                    Text(
                                        if (app.packageName in favorites) "★" else "☆",
                                        color = accent,
                                        modifier = Modifier.clickable {
                                            favorites = if (app.packageName in favorites) favorites - app.packageName else favorites + app.packageName
                                            DrawerSettings.setFavorites(context, favorites)
                                        }.padding(8.dp)
                                    )
                                    Switch(
                                        checked = app.packageName !in hidden,
                                        onCheckedChange = { show ->
                                            hidden = if (show) hidden - app.packageName else hidden + app.packageName
                                            DrawerSettings.setHidden(context, hidden)
                                        }
                                    )
                                }
                            }
                        }
                    }
                } else {
                    if (isSide) {
                        LazyColumn(
                            Modifier.weight(1f).fillMaxWidth().focusGroup(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            columnItems(visibleApps, key = { it.packageName }) { app ->
                                DrawerAppTile(app, tile, accent, compact = true) { onLaunch(app.packageName) }
                            }
                        }
                    } else if (isCenter) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 190.dp),
                            modifier = Modifier.weight(1f).fillMaxWidth().focusGroup(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(visibleApps, key = { it.packageName }) { app ->
                                DrawerAppTile(app, tile, accent, compact = false) { onLaunch(app.packageName) }
                            }
                        }
                    } else {
                        LazyRow(
                            Modifier.weight(1f).fillMaxWidth().focusGroup(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            rowItems(visibleApps, key = { it.packageName }) { app ->
                                DrawerAppTile(app, tile, accent, compact = false) { onLaunch(app.packageName) }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun DrawerAction(label: String, selected: Boolean, onClick: () -> Unit) {
        Text(
            label,
            color = if (selected) Color.White else Color(0xFFB7C2D8),
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier
                .background(if (selected) Color(0xFF5545A8) else Color(0xFF202B40), RoundedCornerShape(10.dp))
                .clickable(onClick = onClick)
                .focusable()
                .padding(horizontal = 11.dp, vertical = 9.dp)
        )
    }

    @Composable
    private fun DrawerAppTile(
        app: com.homelessbrian.nuviocompanion.InstalledApp,
        tileColor: Color,
        accent: Color,
        compact: Boolean,
        onClick: () -> Unit
    ) {
        var focused by remember(app.packageName) { mutableStateOf(false) }
        val cardShape = RoundedCornerShape(18.dp)
        Row(
            Modifier
                .then(if (compact) Modifier.fillMaxWidth() else Modifier.width(190.dp))
                .height(84.dp)
                .onFocusChanged { focused = it.isFocused }
                .background(if (focused) Color(0xFF34305C) else tileColor, cardShape)
                .then(if (focused) Modifier.border(2.dp, accent, cardShape) else Modifier)
                .clickable(onClick = onClick)
                .focusable()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(58.dp)
                    .background(if (focused) Color(0xFF51449B) else Color(0xFF263650), RoundedCornerShape(15.dp)),
                contentAlignment = Alignment.Center
            ) {
                Image(app.icon.toBitmap(128, 128).asImageBitmap(), app.label, Modifier.size(42.dp))
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(app.label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Spacer(Modifier.height(4.dp))
                Text("OPEN APP", color = if (focused) Color(0xFFD7D0FF) else Color(0xFF91A1BD), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
            Text("›", color = if (focused) Color.White else Color(0xFF7385A3), fontSize = 25.sp, fontWeight = FontWeight.Light)
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
