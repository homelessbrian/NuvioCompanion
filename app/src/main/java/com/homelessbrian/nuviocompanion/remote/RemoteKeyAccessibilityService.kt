package com.homelessbrian.nuviocompanion.remote

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Initial key-event research scaffold.
 *
 * Android only delivers hardware key events here when the user explicitly enables this
 * accessibility service and the device/remote exposes the event to Android's input pipeline.
 * Combination recording, conflict handling and drawer activation are intentionally not wired yet.
 */
class RemoteKeyAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // Returning false lets the key continue to the foreground application.
        // A future shortcut engine will consume only a user-configured matching sequence.
        return false
    }
}
