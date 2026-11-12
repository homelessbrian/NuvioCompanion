package com.homelessbrian.nuviocompanion

import android.content.Context

enum class DrawerPosition { BOTTOM, TOP, LEFT, RIGHT, CENTER }
enum class ShortcutMode { SINGLE, DOUBLE, LONG, COMBINATION }

object DrawerSettings {
    private const val PREFS = "companion_preferences"
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun position(context: Context) = runCatching { DrawerPosition.valueOf(prefs(context).getString("position", "BOTTOM")!!) }.getOrDefault(DrawerPosition.BOTTOM)
    fun setPosition(context: Context, value: DrawerPosition) { prefs(context).edit().putString("position", value.name).apply() }
    fun shortcutMode(context: Context) = runCatching { ShortcutMode.valueOf(prefs(context).getString("shortcut_mode", "SINGLE")!!) }.getOrDefault(ShortcutMode.SINGLE)
    fun setShortcutMode(context: Context, value: ShortcutMode) { prefs(context).edit().putString("shortcut_mode", value.name).apply() }
    fun keyCode(context: Context) = prefs(context).getInt("shortcut_key", 0)
    fun setKeyCode(context: Context, value: Int) { prefs(context).edit().putInt("shortcut_key", value).apply() }
    fun secondKeyCode(context: Context) = prefs(context).getInt("shortcut_key_2", 0)
    fun setSecondKeyCode(context: Context, value: Int) { prefs(context).edit().putInt("shortcut_key_2", value).apply() }
    fun recordingTarget(context: Context) = prefs(context).getString("recording_target", "primary") ?: "primary"
    fun setRecordingTarget(context: Context, value: String) { prefs(context).edit().putString("recording_target", value).apply() }
    fun isRecording(context: Context) = prefs(context).getBoolean("recording", false)
    fun setRecording(context: Context, value: Boolean) { prefs(context).edit().putBoolean("recording", value).apply() }
    fun hidden(context: Context) = prefs(context).getStringSet("hidden_packages", emptySet())?.toSet() ?: emptySet()
    fun setHidden(context: Context, value: Set<String>) { prefs(context).edit().putStringSet("hidden_packages", value.toSet()).apply() }
    fun favorites(context: Context) = prefs(context).getStringSet("favorite_packages", emptySet())?.toSet() ?: emptySet()
    fun setFavorites(context: Context, value: Set<String>) { prefs(context).edit().putStringSet("favorite_packages", value.toSet()).apply() }
    fun order(context: Context) = prefs(context).getString("app_order", "")?.split("|")?.filter(String::isNotBlank) ?: emptyList()
    fun setOrder(context: Context, value: List<String>) { prefs(context).edit().putString("app_order", value.joinToString("|")).apply() }
    fun selectedNuvio(context: Context) = prefs(context).getString("selected_nuvio", null)
    fun setSelectedNuvio(context: Context, value: String?) { prefs(context).edit().putString("selected_nuvio", value).apply() }
    fun showNames(context: Context) = prefs(context).getBoolean("drawer_show_names", true)
    fun setShowNames(context: Context, value: Boolean) { prefs(context).edit().putBoolean("drawer_show_names", value).apply() }
    fun showIcons(context: Context) = prefs(context).getBoolean("drawer_show_icons", true)
    fun setShowIcons(context: Context, value: Boolean) { prefs(context).edit().putBoolean("drawer_show_icons", value).apply() }
    fun focusGlow(context: Context) = prefs(context).getBoolean("drawer_focus_glow", true)
    fun setFocusGlow(context: Context, value: Boolean) { prefs(context).edit().putBoolean("drawer_focus_glow", value).apply() }
    fun opacity(context: Context) = prefs(context).getFloat("drawer_opacity", 0.84f)
    fun setOpacity(context: Context, value: Float) { prefs(context).edit().putFloat("drawer_opacity", value.coerceIn(0.5f, 1f)).apply() }
}
