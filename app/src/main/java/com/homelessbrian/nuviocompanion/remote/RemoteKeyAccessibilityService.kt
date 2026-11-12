package com.homelessbrian.nuviocompanion.remote

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.homelessbrian.nuviocompanion.DrawerSettings
import com.homelessbrian.nuviocompanion.ShortcutMode
import com.homelessbrian.nuviocompanion.overlay.DrawerOverlayService

class RemoteKeyAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var pendingDouble: Runnable? = null
    private var longPressFired = false
    private val downKeys = mutableSetOf<Int>()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() { resetGesture() }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (DrawerSettings.isRecording(this)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (DrawerSettings.recordingTarget(this) == "secondary") DrawerSettings.setSecondKeyCode(this, event.keyCode)
                else DrawerSettings.setKeyCode(this, event.keyCode)
                DrawerSettings.setRecording(this, false)
                sendBroadcast(Intent(ACTION_SHORTCUT_RECORDED).setPackage(packageName))
                return true
            }
            return false
        }
        val primary = DrawerSettings.keyCode(this)
        if (primary == 0) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                downKeys.add(event.keyCode)
                if (event.keyCode == primary && event.repeatCount == 0) {
                    longPressFired = false
                    when (DrawerSettings.shortcutMode(this)) {
                        ShortcutMode.SINGLE -> trigger()
                        ShortcutMode.DOUBLE -> {
                            val waiting = pendingDouble
                            if (waiting != null) { handler.removeCallbacks(waiting); pendingDouble = null; trigger() }
                            else { val task = Runnable { pendingDouble = null }; pendingDouble = task; handler.postDelayed(task, DOUBLE_PRESS_WINDOW) }
                        }
                        ShortcutMode.LONG -> handler.postDelayed({ if (primary in downKeys) { longPressFired = true; trigger() } }, LONG_PRESS_TIMEOUT)
                        ShortcutMode.COMBINATION -> {
                            val second = DrawerSettings.secondKeyCode(this)
                            if (second != 0 && second in downKeys) trigger()
                        }
                    }
                } else if (DrawerSettings.shortcutMode(this) == ShortcutMode.COMBINATION &&
                    event.keyCode == DrawerSettings.secondKeyCode(this) && primary in downKeys) trigger()
            }
            KeyEvent.ACTION_UP -> {
                downKeys.remove(event.keyCode)
                if (event.keyCode == primary && DrawerSettings.shortcutMode(this) == ShortcutMode.LONG && !longPressFired) handler.removeCallbacksAndMessages(null)
            }
        }
        return false
    }

    private fun trigger() = DrawerOverlayService.toggle(this)
    private fun resetGesture() { handler.removeCallbacksAndMessages(null); pendingDouble = null; downKeys.clear() }
    override fun onDestroy() { DrawerOverlayService.close(); resetGesture(); super.onDestroy() }

    companion object {
        const val ACTION_SHORTCUT_RECORDED = "com.homelessbrian.nuviocompanion.SHORTCUT_RECORDED"
        private const val DOUBLE_PRESS_WINDOW = 320L
        private const val LONG_PRESS_TIMEOUT = 600L
    }
}
