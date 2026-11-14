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

/**
 * Handles configured remote shortcuts.
 *
 * Returning true from onKeyEvent is intentional: it consumes the mapped key event so the
 * device/app underneath does not also execute the remote's default action.
 */
class RemoteKeyAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var pendingDouble: Runnable? = null
    private var longPressFired = false
    private val downKeys = mutableSetOf<Int>()
    private val consumedKeys = mutableSetOf<Int>()

    // Every button press on the TV passes through here, so the button settings are kept in
    // memory and refreshed only when a setting changes, instead of being looked up each press.
    private var recording = false
    private var recordingSecond = false
    private var primaryKey = 0
    private var secondKey = 0
    private var mode = ShortcutMode.SINGLE
    private var settingsListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    private fun reloadSettings() {
        recording = DrawerSettings.isRecording(this)
        recordingSecond = DrawerSettings.recordingTarget(this) == "secondary"
        primaryKey = DrawerSettings.keyCode(this)
        secondKey = DrawerSettings.secondKeyCode(this)
        mode = DrawerSettings.shortcutMode(this)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        reloadSettings()
        settingsListener = DrawerSettings.listen(this) { reloadSettings() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() { resetGesture() }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // Handle dismissal before shortcut matching so Back cannot be swallowed by a
        // configured shortcut or left for the overlay's focused child to ignore.
        if (DrawerOverlayService.handleBackKey(event)) return true

        if (recording) {
            // Back, Home, OK and the arrows keep working so the remote can still be used.
            if (event.keyCode in KeyNames.blocked) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 &&
                    event.keyCode != KeyEvent.KEYCODE_BACK && event.keyCode != KeyEvent.KEYCODE_HOME
                ) {
                    sendBroadcast(Intent(ACTION_SHORTCUT_REJECTED).setPackage(packageName).putExtra(EXTRA_KEY_CODE, event.keyCode))
                }
                return false
            }
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) {
                    if (recordingSecond) DrawerSettings.setSecondKeyCode(this, event.keyCode)
                    else DrawerSettings.setKeyCode(this, event.keyCode)
                    DrawerSettings.setRecording(this, false)
                    // Don't wait for the change notice: the next press must already use the new button.
                    recording = false
                    consumedKeys.add(event.keyCode)
                    sendBroadcast(Intent(ACTION_SHORTCUT_RECORDED).setPackage(packageName))
                }
                return true
            }
            if (event.action == KeyEvent.ACTION_UP && consumedKeys.remove(event.keyCode)) return true
            return false
        }

        val primary = primaryKey
        val mode = this.mode
        val second = secondKey
        val isMappedKey = event.keyCode == primary ||
            (mode == ShortcutMode.COMBINATION && second != 0 && event.keyCode == second)

        if (event.action == KeyEvent.ACTION_DOWN) {
            downKeys.add(event.keyCode)
            if (!isMappedKey) return false

            // Consume every phase (including repeats) of configured keys, not only the
            // event that activates the gesture. This prevents the remote's native action.
            consumedKeys.add(event.keyCode)
            if (event.repeatCount == 0) {
                when (mode) {
                    ShortcutMode.SINGLE -> if (event.keyCode == primary) trigger()
                    ShortcutMode.DOUBLE -> if (event.keyCode == primary) handleDoublePress()
                    ShortcutMode.LONG -> if (event.keyCode == primary) {
                        longPressFired = false
                        handler.postDelayed({
                            if (primary in downKeys) {
                                longPressFired = true
                                trigger()
                            }
                        }, LONG_PRESS_TIMEOUT)
                    }
                    ShortcutMode.COMBINATION -> {
                        if (primary != 0 && second != 0 && primary in downKeys && second in downKeys) trigger()
                    }
                }
            }
            return true
        }

        if (event.action == KeyEvent.ACTION_UP) {
            downKeys.remove(event.keyCode)
            if (consumedKeys.remove(event.keyCode)) {
                if (event.keyCode == primary && mode == ShortcutMode.LONG && !longPressFired) {
                    handler.removeCallbacksAndMessages(null)
                }
                return true
            }
        }
        return false
    }

    private fun isMappedKey(keyCode: Int): Boolean {
        val primary = DrawerSettings.keyCode(this)
        val mode = DrawerSettings.shortcutMode(this)
        val second = DrawerSettings.secondKeyCode(this)
        return keyCode == primary || (mode == ShortcutMode.COMBINATION && second != 0 && keyCode == second)
    }

    private fun handleDoublePress() {
        val waiting = pendingDouble
        if (waiting != null) {
            handler.removeCallbacks(waiting)
            pendingDouble = null
            trigger()
        } else {
            val task = Runnable { pendingDouble = null }
            pendingDouble = task
            handler.postDelayed(task, DOUBLE_PRESS_WINDOW)
        }
    }

    private fun trigger() {
        sendBroadcast(Intent(ACTION_SHORTCUT_TRIGGERED).setPackage(packageName))
        // "Test your button" in Settings only wants to know the press arrived.
        if (DrawerSettings.isTestingShortcut(this)) return
        // WindowManager and Compose view creation must run on the main thread.
        handler.post {
            runCatching { DrawerOverlayService.toggle(this) }
                .onFailure { error ->
                    sendBroadcast(
                        Intent(ACTION_OVERLAY_STATUS)
                            .setPackage(packageName)
                            .putExtra(
                                EXTRA_STATUS,
                                "Drawer trigger failed: ${error.javaClass.simpleName}: ${error.message ?: "unknown error"}"
                            )
                    )
                }
        }
    }

    private fun resetGesture() {
        handler.removeCallbacksAndMessages(null)
        pendingDouble = null
        downKeys.clear()
        consumedKeys.clear()
    }

    override fun onDestroy() {
        settingsListener?.let { DrawerSettings.stopListening(this, it) }
        settingsListener = null
        DrawerOverlayService.close()
        resetGesture()
        super.onDestroy()
    }

    companion object {
        const val ACTION_SHORTCUT_RECORDED = "com.homelessbrian.nuviocompanion.SHORTCUT_RECORDED"
        const val ACTION_SHORTCUT_TRIGGERED = "com.homelessbrian.nuviocompanion.SHORTCUT_TRIGGERED"
        const val ACTION_OVERLAY_STATUS = "com.homelessbrian.nuviocompanion.OVERLAY_STATUS"
        const val ACTION_SHORTCUT_REJECTED = "com.homelessbrian.nuviocompanion.SHORTCUT_REJECTED"
        const val EXTRA_STATUS = "status"
        const val EXTRA_KEY_CODE = "key_code"

        /** Whether the user has turned on this service in Android's Accessibility settings. */
        fun isEnabled(context: android.content.Context): Boolean {
            val expected = android.content.ComponentName(context, RemoteKeyAccessibilityService::class.java).flattenToString()
            val enabled = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        }
        private const val DOUBLE_PRESS_WINDOW = 320L
        private const val LONG_PRESS_TIMEOUT = 600L
    }
}
