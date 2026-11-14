package com.homelessbrian.nuviocompanion

import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Escape-route tiles shown at the end of the drawer. They are not real packages, cannot be
 * hidden in Settings → Apps, and always give a way to reach Android Settings or Nuvio Launch
 * settings from inside any app, including Nuvio.
 */
object SystemShortcuts {
    const val ANDROID_SETTINGS = "nuviocompanion.shortcut.android_settings"
    const val COMPANION_SETTINGS = "nuviocompanion.shortcut.companion_settings"

    fun isShortcut(id: String): Boolean = id == ANDROID_SETTINGS || id == COMPANION_SETTINGS

    /** Reads icons and icon packs; call off the main thread where possible. */
    fun tiles(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        val pack = DrawerSettings.iconPack(context)
        val settingsActivity = runCatching { pm.resolveActivity(Intent(Settings.ACTION_SETTINGS), 0) }.getOrNull()
        val settingsOwn = runCatching { settingsActivity?.loadIcon(pm) }.getOrNull()
            ?: context.getDrawable(android.R.drawable.ic_menu_preferences)
        // With an icon pack on, these tiles match it like every other app.
        val settingsIcon = settingsActivity?.activityInfo?.packageName?.let { settingsPackage ->
            IconPackManager.iconFor(context, pack, settingsPackage, null, "Settings") { settingsOwn }
        } ?: settingsOwn
        val launchOwn = runCatching { context.applicationInfo.loadIcon(pm) }.getOrNull()
            ?: context.getDrawable(android.R.drawable.sym_def_app_icon)
        val launchIcon = IconPackManager.iconFor(context, pack, context.packageName, null, "Nuvio Launch") { launchOwn } ?: launchOwn

        return listOfNotNull(
            settingsIcon?.let { InstalledApp("Android Settings", ANDROID_SETTINGS, it) },
            launchIcon?.let { InstalledApp("Nuvio Launch", COMPANION_SETTINGS, it) }
        )
    }

    /** Returns true when [id] was a shortcut and it was opened. */
    fun launch(context: Context, id: String): Boolean {
        val intent = when (id) {
            ANDROID_SETTINGS -> Intent(Settings.ACTION_SETTINGS)
            COMPANION_SETTINGS -> Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_SECTION, SettingsSection.REMOTE.name)
            else -> return false
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent); true }.getOrDefault(false)
    }
}
