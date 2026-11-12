package com.homelessbrian.nuviocompanion.overlay

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
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
import com.homelessbrian.nuviocompanion.AppCatalog
import com.homelessbrian.nuviocompanion.DrawerSettings

class DrawerOverlayService : Service() {
    private var view: ComposeView? = null
    private var visible = false
    private lateinit var wm: WindowManager
    override fun onCreate() { super.onCreate(); wm = getSystemService(WINDOW_SERVICE) as WindowManager }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (visible) close() else open()
        return START_NOT_STICKY
    }
    private fun open() {
        val position = DrawerSettings.position(this)
        val horizontal = position == "TOP" || position == "BOTTOM"
        val gravity = when (position) {
            "TOP" -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
            "LEFT" -> Gravity.START or Gravity.CENTER_VERTICAL
            "RIGHT" -> Gravity.END or Gravity.CENTER_VERTICAL
            "CENTER" -> Gravity.CENTER
            else -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }
        val params = WindowManager.LayoutParams(
            when (position) { "LEFT", "RIGHT" -> (resources.displayMetrics.widthPixels * .34f).toInt(); "CENTER" -> (resources.displayMetrics.widthPixels * .82f).toInt(); else -> WindowManager.LayoutParams.MATCH_PARENT },
            when (position) { "TOP", "BOTTOM" -> (resources.displayMetrics.heightPixels * .36f).toInt(); "CENTER" -> (resources.displayMetrics.heightPixels * .76f).toInt(); else -> WindowManager.LayoutParams.MATCH_PARENT },
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { this.gravity = gravity }
        view = ComposeView(this).apply { setContent { MaterialTheme { DrawerContent(horizontal) } } }
        wm.addView(view, params); visible = true
    }
    private fun close() { view?.let { runCatching { wm.removeView(it) } }; view = null; visible = false; stopSelf() }
    override fun onDestroy() { if (visible) close(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    @Composable private fun DrawerContent(horizontal: Boolean) {
        BackHandler { close() }
        val apps = remember { AppCatalog.load(this) }
        Surface(Modifier.fillMaxSize().padding(8.dp), shape = RoundedCornerShape(20.dp), color = Color(0xF21A1D24)) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("APPS", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("Press BACK to close", color = Color(0xFFADB5C8), fontSize = 12.sp)
                }
                Spacer(Modifier.height(12.dp))
                LazyVerticalGrid(columns = GridCells.Fixed(if (horizontal) apps.size.coerceIn(1, 8) else 2), modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(apps, key = { it.packageName }) { app ->
                        Card(onClick = {
                            packageManager.getLaunchIntentForPackage(app.packageName)?.let { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(it) }
                            close()
                        }, modifier = Modifier.height(108.dp).focusable(), shape = RoundedCornerShape(14.dp)) {
                            Column(Modifier.fillMaxSize().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                Image(app.icon.toBitmap(80, 80).asImageBitmap(), app.label, Modifier.size(48.dp))
                                Spacer(Modifier.height(6.dp)); Text(app.label, color = Color.White, fontSize = 12.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}
