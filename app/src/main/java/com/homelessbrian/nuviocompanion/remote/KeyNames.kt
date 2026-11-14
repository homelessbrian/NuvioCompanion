package com.homelessbrian.nuviocompanion.remote

import android.view.KeyEvent

/** Friendly names for remote buttons, and the buttons that can't be used as a shortcut. */
object KeyNames {
    private val friendly = mapOf(
        KeyEvent.KEYCODE_BOOKMARK to "Bookmark",
        KeyEvent.KEYCODE_INFO to "Info",
        KeyEvent.KEYCODE_GUIDE to "Guide",
        KeyEvent.KEYCODE_MENU to "Menu",
        KeyEvent.KEYCODE_SETTINGS to "Settings",
        KeyEvent.KEYCODE_PROG_RED to "Red",
        KeyEvent.KEYCODE_PROG_GREEN to "Green",
        KeyEvent.KEYCODE_PROG_YELLOW to "Yellow",
        KeyEvent.KEYCODE_PROG_BLUE to "Blue",
        KeyEvent.KEYCODE_TV_INPUT to "Input",
        KeyEvent.KEYCODE_SEARCH to "Search",
        KeyEvent.KEYCODE_ASSIST to "Assistant",
        KeyEvent.KEYCODE_VOICE_ASSIST to "Voice",
        KeyEvent.KEYCODE_ALL_APPS to "All apps",
        KeyEvent.KEYCODE_CHANNEL_UP to "Channel up",
        KeyEvent.KEYCODE_CHANNEL_DOWN to "Channel down",
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to "Play/Pause",
        KeyEvent.KEYCODE_MEDIA_REWIND to "Rewind",
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD to "Fast forward",
        KeyEvent.KEYCODE_MEDIA_RECORD to "Record",
        KeyEvent.KEYCODE_CAPTIONS to "Captions",
        KeyEvent.KEYCODE_MUTE to "Mute",
        KeyEvent.KEYCODE_VOLUME_MUTE to "Mute"
    )

    /** Buttons the remote needs for normal use. Recording ignores them. */
    val blocked = setOf(
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_HOME,
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN
    )

    fun name(keyCode: Int): String {
        if (keyCode == 0) return "Not set"
        friendly[keyCode]?.let { return it }
        // KEYCODE_BUTTON_1 -> "Button 1", KEYCODE_F5 -> "F5"
        val raw = KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
        if (raw.all { it.isDigit() }) return "Button $raw"
        return raw.split('_').joinToString(" ") { part ->
            if (part.length <= 2) part else part.lowercase().replaceFirstChar { it.uppercase() }
        }
    }
}
