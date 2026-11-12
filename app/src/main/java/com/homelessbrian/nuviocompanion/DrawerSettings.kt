package com.homelessbrian.nuviocompanion

import android.content.Context
import android.content.SharedPreferences

object DrawerSettings {
    private const val PREFS = "drawer_settings"
    private const val KEY_CODE = "shortcut_key_code"
    private const val POSITION = "drawer_position"
    private const val HIDDEN = "hidden_packages"
    private const val ORDER = "app_order"
    private const val RECORDING = "recording_shortcut"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun keyCode(context: Context) = prefs(context).getInt(KEY_CODE, 0)
    fun setKeyCode(context: Context, value: Int) { prefs(context).edit().putInt(KEY_CODE, value).apply() }
    fun position(context: Context) = prefs(context).getString(POSITION, "BOTTOM") ?: "BOTTOM"
    fun setPosition(context: Context, value: String) { prefs(context).edit().putString(POSITION, value).apply() }
    fun hidden(context: Context) = prefs(context).getStringSet(HIDDEN, emptySet())?.toSet() ?: emptySet()
    fun setHidden(context: Context, value: Set<String>) { prefs(context).edit().putStringSet(HIDDEN, value).apply() }
    fun order(context: Context) = prefs(context).getString(ORDER, "") ?: ""
    fun setOrder(context: Context, value: String) { prefs(context).edit().putString(ORDER, value).apply() }
    fun isRecording(context: Context) = prefs(context).getBoolean(RECORDING, false)
    fun setRecording(context: Context, value: Boolean) { prefs(context).edit().putBoolean(RECORDING, value).apply() }
}
