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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() { resetGesture() }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        publishInputEvent(event, isMappedKey(event.keyCode))
        if (DrawerSettings.isRecording(this)) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) {
                    if (DrawerSettings.recordingTarget(this) == "secondary") DrawerSettings.setSecondKeyCode(this, event.keyCode)
                    else DrawerSettings.setKeyCode(this, event.keyCode)
                    DrawerSettings.setRecording(this, false)
                    consumedKeys.add(event.keyCode)
                    sendBroadcast(Intent(ACTION_SHORTCUT_RECORDED).setPackage(packageName))
                }
                return true
            }
            if (event.action == KeyEvent.ACTION_UP && consumedKeys.remove(event.keyCode)) return true
            return false
        }

        val primary = DrawerSettings.keyCode(this)
        val mode = DrawerSettings.shortcutMode(this)
        val second = DrawerSettings.secondKeyCode(this)
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

    private fun publishInputEvent(event: KeyEvent, mapped: Boolean) {
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return
        sendBroadcast(Intent(ACTION_INPUT_EVENT).setPackage(packageName)
            .putExtra(EXTRA_KEY_CODE, event.keyCode)
            .putExtra(EXTRA_MAPPED, mapped))
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
        DrawerOverlayService.toggle(this)
    }

    private fun resetGesture() {
        handler.removeCallbacksAndMessages(null)
        pendingDouble = null
        downKeys.clear()
        consumedKeys.clear()
    }

    override fun onDestroy() {
        DrawerOverlayService.close()
        resetGesture()
        super.onDestroy()
    }

    companion object {
        const val ACTION_SHORTCUT_RECORDED = "com.homelessbrian.nuviocompanion.SHORTCUT_RECORDED"
        const val ACTION_INPUT_EVENT = "com.homelessbrian.nuviocompanion.INPUT_EVENT"
        const val ACTION_SHORTCUT_TRIGGERED = "com.homelessbrian.nuviocompanion.SHORTCUT_TRIGGERED"
        const val ACTION_OVERLAY_STATUS = "com.homelessbrian.nuviocompanion.OVERLAY_STATUS"
        const val EXTRA_STATUS = "status"
        const val EXTRA_KEY_CODE = "key_code"
        const val EXTRA_MAPPED = "mapped"
        private const val DOUBLE_PRESS_WINDOW = 320L
        private const val LONG_PRESS_TIMEOUT = 600L
    }
}
