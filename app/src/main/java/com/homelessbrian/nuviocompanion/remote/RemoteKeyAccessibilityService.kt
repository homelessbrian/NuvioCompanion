package com.homelessbrian.nuviocompanion.remote

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.homelessbrian.nuviocompanion.DrawerSettings
import com.homelessbrian.nuviocompanion.overlay.DrawerOverlayService

class RemoteKeyAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return false
        if (DrawerSettings.isRecording(this)) {
            DrawerSettings.setKeyCode(this, event.keyCode)
            DrawerSettings.setRecording(this, false)
            sendBroadcast(Intent(ACTION_SHORTCUT_RECORDED).setPackage(packageName))
            return true
        }
        val configured = DrawerSettings.keyCode(this)
        if (configured != 0 && event.keyCode == configured) {
            startService(Intent(this, DrawerOverlayService::class.java).setAction(DrawerOverlayService.ACTION_TOGGLE))
            return true
        }
        return false
    }

    companion object {
        const val ACTION_SHORTCUT_RECORDED = "com.homelessbrian.nuviocompanion.SHORTCUT_RECORDED"
    }
}
