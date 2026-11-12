package com.homelessbrian.nuviocompanion.overlay

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewTreeLifecycleOwner
import com.homelessbrian.nuviocompanion.AppCatalog
import com.homelessbrian.nuviocompanion.DrawerPosition
import com.homelessbrian.nuviocompanion.DrawerSettings

class DrawerOverlayService : Service(), LifecycleOwner {
    private val lifecycleRegistry by lazy { LifecycleRegistry(this) }
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    private var view: ComposeView? = null
    private var visible = false
    private lateinit var windowManager: WindowManager

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CLOSE) close() else if (visible) close() else open()
        return START_NOT_STICKY
    }

    private fun open() {
        if (!android.provider.Settings.canDrawOverlays(this) &&
            !isAccessibilityOverlayAvailable()) return
        val position = DrawerSettings.position(this)
        val (width, height, gravity) = when (position) {
            DrawerPosition.TOP -> Triple(WindowManager.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * .34f).toInt(), Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            DrawerPosition.BOTTOM -> Triple(WindowManager.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * .34f).toInt(), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            DrawerPosition.LEFT -> Triple((resources.displayMetrics.widthPixels * .38f).toInt(), WindowManager.LayoutParams.MATCH_PARENT, Gravity.START or Gravity.CENTER_VERTICAL)
            DrawerPosition.RIGHT -> Triple((resources.displayMetrics.widthPixels * .38f).toInt(), WindowManager.LayoutParams.MATCH_PARENT, Gravity.END or Gravity.CENTER_VERTICAL)
            DrawerPosition.CENTER -> Triple((resources.displayMetrics.widthPixels * .84f).toInt(), (resources.displayMetrics.heightPixels * .78f).toInt(), Gravity.CENTER)
        }
        val params = WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT).apply { this.gravity = gravity; dimAmount = .22f }
        val root = ComposeView(this).apply {
            ViewTreeLifecycleOwner.set(this, this@DrawerOverlayService)
            isFocusable = true
            isFocusableInTouchMode = true
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) { close(); true } else false
            }
            setContent { MaterialTheme { DrawerContent(onClose = ::close, onLaunch = { packageName -> AppCatalog.launch(this@DrawerOverlayService, packageName); close() }) } }
        }
        view = root
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        runCatching { windowManager.addView(root, params); visible = true; root.requestFocus() }
            .onFailure { view = null; stopSelf() }
    }

    private fun isAccessibilityOverlayAvailable() = true

    private fun close() {
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
        visible = false
        stopSelf()
    }

    override fun onDestroy() {
        close()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    @Composable
    private fun DrawerContent(onClose: () -> Unit, onLaunch: (String) -> Unit) {
        val apps = remember { AppCatalog.load(this) }
        Surface(Modifier.fillMaxSize().padding(10.dp), shape = RoundedCornerShape(24.dp), color = Color(0xF2191E2A)) {
            Column(Modifier.fillMaxSize().padding(22.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("YOUR APPS", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text("${apps.size} installed apps", color = Color(0xFFABB6CC), fontSize = 13.sp)
                    }
                    Text("BACK  ·  CLOSE", color = Color(0xFFABB6CC), fontSize = 12.sp)
                }
                Spacer(Modifier.height(18.dp))
                LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 116.dp), modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(apps, key = { it.packageName }) { app ->
                        Column(Modifier.height(116.dp).background(Color(0xFF252D3D), RoundedCornerShape(16.dp))
                            .clickable { onLaunch(app.packageName) }.focusable().padding(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Image(app.icon.toBitmap(96, 96).asImageBitmap(), app.label, Modifier.size(54.dp))
                            Spacer(Modifier.height(8.dp))
                            Text(app.label, color = Color.White, fontSize = 13.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.homelessbrian.nuviocompanion.action.TOGGLE_DRAWER"
        const val ACTION_CLOSE = "com.homelessbrian.nuviocompanion.action.CLOSE_DRAWER"
    }
}
