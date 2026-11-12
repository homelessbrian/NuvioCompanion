package com.homelessbrian.nuviocompanion.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.KeyEvent
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
import androidx.lifecycle.setViewTreeLifecycleOwner
import com.homelessbrian.nuviocompanion.AppCatalog
import com.homelessbrian.nuviocompanion.DrawerPosition
import com.homelessbrian.nuviocompanion.DrawerSettings

/** Owns the system overlay from the AccessibilityService context, which is required for ACCESSIBILITY_OVERLAY. */
object DrawerOverlayService {
    private var root: ComposeView? = null
    private var owner: OverlayLifecycleOwner? = null
    private var windowManager: WindowManager? = null

    fun toggle(service: AccessibilityService) { if (root == null) open(service) else close() }
    fun close() {
        root?.let { runCatching { windowManager?.removeView(it) } }
        owner?.destroy()
        root = null; owner = null; windowManager = null
    }

    private fun open(service: AccessibilityService) {
        val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val position = DrawerSettings.position(service)
        val (width, height, gravity) = when (position) {
            DrawerPosition.TOP -> Triple(WindowManager.LayoutParams.MATCH_PARENT, (service.resources.displayMetrics.heightPixels * .36f).toInt(), Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            DrawerPosition.BOTTOM -> Triple(WindowManager.LayoutParams.MATCH_PARENT, (service.resources.displayMetrics.heightPixels * .36f).toInt(), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            DrawerPosition.LEFT -> Triple((service.resources.displayMetrics.widthPixels * .38f).toInt(), WindowManager.LayoutParams.MATCH_PARENT, Gravity.START or Gravity.CENTER_VERTICAL)
            DrawerPosition.RIGHT -> Triple((service.resources.displayMetrics.widthPixels * .38f).toInt(), WindowManager.LayoutParams.MATCH_PARENT, Gravity.END or Gravity.CENTER_VERTICAL)
            DrawerPosition.CENTER -> Triple((service.resources.displayMetrics.widthPixels * .84f).toInt(), (service.resources.displayMetrics.heightPixels * .78f).toInt(), Gravity.CENTER)
        }
        val lifecycleOwner = OverlayLifecycleOwner()
        val composeView = ComposeView(service).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            isFocusable = true; isFocusableInTouchMode = true
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) { close(); true } else false
            }
            setContent { MaterialTheme { DrawerContent(service, onClose = ::close, onLaunch = { pkg -> AppCatalog.launch(service, pkg); close() }) } }
        }
        val params = WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT).apply { this.gravity = gravity; dimAmount = .22f }
        lifecycleOwner.start()
        try {
            wm.addView(composeView, params)
            root = composeView; owner = lifecycleOwner; windowManager = wm
            composeView.requestFocus()
        } catch (_: SecurityException) {
            lifecycleOwner.destroy()
        } catch (_: RuntimeException) {
            lifecycleOwner.destroy()
        }
    }

    @Composable
    private fun DrawerContent(context: Context, onClose: () -> Unit, onLaunch: (String) -> Unit) {
        val apps = remember { AppCatalog.load(context) }
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

    private class OverlayLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
        fun start() { registry.currentState = Lifecycle.State.RESUMED }
        fun destroy() { registry.currentState = Lifecycle.State.DESTROYED }
    }
}
