package com.homelessbrian.nuviocompanion

import android.content.Context

enum class DrawerPosition(val label: String) {
    BOTTOM("Bottom dock"),
    TOP("Top bar"),
    LEFT("Side rail · left"),
    RIGHT("Side rail · right"),
    CENTER("Center grid")
}

enum class ShortcutMode(val label: String) {
    SINGLE("Single press"),
    DOUBLE("Double press"),
    LONG("Hold"),
    COMBINATION("Two buttons")
}

object DrawerSettings {
    private const val PREFS = "companion_preferences"

    /** Icon override value meaning "always use the app's own icon, even with a pack selected". */
    const val ICON_APP_OWN = "__app__"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- Setup -------------------------------------------------------------------------------
    fun setupDone(context: Context) = prefs(context).getBoolean("setup_done", false)
    fun setSetupDone(context: Context, value: Boolean) { prefs(context).edit().putBoolean("setup_done", value).apply() }

    // ---- Drawer placement and look -----------------------------------------------------------
    fun position(context: Context) = runCatching { DrawerPosition.valueOf(prefs(context).getString("position", "BOTTOM")!!) }.getOrDefault(DrawerPosition.BOTTOM)
    fun setPosition(context: Context, value: DrawerPosition) { prefs(context).edit().putString("position", value.name).apply() }
    fun showNames(context: Context) = prefs(context).getBoolean("drawer_show_names", false)
    fun setShowNames(context: Context, value: Boolean) { prefs(context).edit().putBoolean("drawer_show_names", value).apply() }
    fun showIcons(context: Context) = prefs(context).getBoolean("drawer_show_icons", true)
    fun setShowIcons(context: Context, value: Boolean) { prefs(context).edit().putBoolean("drawer_show_icons", value).apply() }
    fun focusGlow(context: Context) = prefs(context).getBoolean("drawer_focus_glow", true)
    fun setFocusGlow(context: Context, value: Boolean) { prefs(context).edit().putBoolean("drawer_focus_glow", value).apply() }
    fun blurBehind(context: Context) = prefs(context).getBoolean("drawer_blur_behind", true)
    fun setBlurBehind(context: Context, value: Boolean) { prefs(context).edit().putBoolean("drawer_blur_behind", value).apply() }
    fun opacity(context: Context) = prefs(context).getFloat("drawer_opacity", 0.86f)
    fun setOpacity(context: Context, value: Float) { prefs(context).edit().putFloat("drawer_opacity", value.coerceIn(0.5f, 1f)).apply() }
    fun highlight(context: Context) = Highlight.fromName(prefs(context).getString("highlight", null))
    fun setHighlight(context: Context, value: Highlight) { prefs(context).edit().putString("highlight", value.name).apply() }

    // ---- Remote shortcut ---------------------------------------------------------------------
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
    /** While true, the shortcut only reports that it was received instead of opening the drawer. */
    fun isTestingShortcut(context: Context) = prefs(context).getBoolean("testing_shortcut", false)
    fun setTestingShortcut(context: Context, value: Boolean) { prefs(context).edit().putBoolean("testing_shortcut", value).apply() }

    // ---- Apps --------------------------------------------------------------------------------
    fun hidden(context: Context) = prefs(context).getStringSet("hidden_packages", emptySet())?.toSet() ?: emptySet()
    fun setHidden(context: Context, value: Set<String>) { prefs(context).edit().putStringSet("hidden_packages", value.toSet()).apply() }
    fun favorites(context: Context) = prefs(context).getStringSet("favorite_packages", emptySet())?.toSet() ?: emptySet()
    fun setFavorites(context: Context, value: Set<String>) { prefs(context).edit().putStringSet("favorite_packages", value.toSet()).apply() }
    fun order(context: Context) = prefs(context).getString("app_order", "")?.split("|")?.filter(String::isNotBlank) ?: emptyList()
    fun setOrder(context: Context, value: List<String>) { prefs(context).edit().putString("app_order", value.joinToString("|")).apply() }

    /** Last time each app was opened from the drawer or Home screen, for the drawer's Recent filter. */
    fun recents(context: Context): Map<String, Long> =
        prefs(context).getStringSet("recent_launches", emptySet())?.mapNotNull { entry ->
            val split = entry.split("=", limit = 2)
            val time = split.getOrNull(1)?.toLongOrNull()
            if (split.size == 2 && time != null) split[0] to time else null
        }?.toMap() ?: emptyMap()
    fun recordLaunch(context: Context, packageName: String) {
        val updated = recents(context).toMutableMap()
        updated[packageName] = System.currentTimeMillis()
        val kept = updated.entries.sortedByDescending { it.value }.take(20)
        prefs(context).edit().putStringSet("recent_launches", kept.map { "${it.key}=${it.value}" }.toSet()).apply()
    }

    // ---- Icons -------------------------------------------------------------------------------
    fun iconPack(context: Context) = prefs(context).getString("icon_pack", null)
    fun setIconPack(context: Context, value: String?) { prefs(context).edit().putString("icon_pack", value).apply() }
    /** With a pack on, give apps the pack doesn't include a pack-style icon too. */
    fun matchAllIcons(context: Context) = prefs(context).getBoolean("icon_match_all", true)
    fun setMatchAllIcons(context: Context, value: Boolean) { prefs(context).edit().putBoolean("icon_match_all", value).apply() }
    /** Forget every per-app icon choice, so all apps follow the selected pack again. */
    fun clearAppIconOverrides(context: Context) { prefs(context).edit().remove("app_icon_overrides").apply() }

    /**
     * Per-app icon choices. Values are either [ICON_APP_OWN], "packPackage:drawableName", or
     * (older builds) a bare drawable name from the currently selected pack.
     */
    fun appIconOverrides(context: Context): Map<String, String> =
        prefs(context).getStringSet("app_icon_overrides", emptySet())?.mapNotNull { entry ->
            val split = entry.split("=", limit = 2)
            if (split.size == 2) split[0] to split[1] else null
        }?.toMap() ?: emptyMap()
    fun appIconOverride(context: Context, packageName: String): String? = appIconOverrides(context)[packageName]
    fun setAppIconOverride(context: Context, packageName: String, value: String?) {
        val updated = appIconOverrides(context).toMutableMap()
        if (value.isNullOrBlank()) updated.remove(packageName) else updated[packageName] = value
        prefs(context).edit().putStringSet("app_icon_overrides", updated.map { "${it.key}=${it.value}" }.toSet()).apply()
    }

    // ---- Home --------------------------------------------------------------------------------
    /** When false, Home shows the Nuvio Launch home screen instead of opening Nuvio. */
    fun homeRedirectEnabled(context: Context) = prefs(context).getBoolean("home_redirect_enabled", true)
    fun setHomeRedirectEnabled(context: Context, value: Boolean) { prefs(context).edit().putBoolean("home_redirect_enabled", value).apply() }
    /** Set automatically when Nuvio keeps closing right after Home opens it. Cleared by "Open Nuvio". */
    fun homeRedirectPaused(context: Context) = prefs(context).getBoolean("home_redirect_paused", false)
    fun setHomeRedirectPaused(context: Context, value: Boolean) { prefs(context).edit().putBoolean("home_redirect_paused", value).apply() }
    fun homeQuickReturns(context: Context) = prefs(context).getInt("home_quick_returns", 0)
    fun setHomeQuickReturns(context: Context, value: Int) { prefs(context).edit().putInt("home_quick_returns", value).apply() }
    fun selectedNuvio(context: Context) = prefs(context).getString("selected_nuvio", null)
    fun setSelectedNuvio(context: Context, value: String?) { prefs(context).edit().putString("selected_nuvio", value).apply() }
    /** Setup asked the user to press Home once; the next Home press reports back instead of opening Nuvio. */
    fun homeTestPending(context: Context) = prefs(context).getBoolean("home_test_pending", false)
    fun setHomeTestPending(context: Context, value: Boolean) { prefs(context).edit().putBoolean("home_test_pending", value).apply() }
    fun homeTestPassed(context: Context) = prefs(context).getBoolean("home_test_passed", false)
    fun setHomeTestPassed(context: Context, value: Boolean) { prefs(context).edit().putBoolean("home_test_passed", value).apply() }
}
