package com.homelessbrian.nuviocompanion

import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.provider.Settings

/** Shared ways to leave Companion's Home behaviour: the system Home picker and Android Settings. */
object HomeSetup {
    /**
     * Opens the device's Home-app selector. RoleManager is only a last resort because on some
     * TV firmware it grants the role immediately instead of showing the launcher list.
     */
    fun openHomePicker(activity: Activity) {
        val candidates = listOf(
            Settings.ACTION_HOME_SETTINGS,
            Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
            Settings.ACTION_SETTINGS
        ).distinct()

        for (action in candidates) {
            try {
                activity.startActivity(Intent(action))
                return
            } catch (_: Exception) {
                // Try the next system settings entry point.
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = activity.getSystemService(RoleManager::class.java)
            if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME) &&
                !roles.isRoleHeld(RoleManager.ROLE_HOME)
            ) {
                runCatching { activity.startActivity(roles.createRequestRoleIntent(RoleManager.ROLE_HOME)) }
            }
        }
    }

    fun openAndroidSettings(activity: Activity): Boolean =
        runCatching { activity.startActivity(Intent(Settings.ACTION_SETTINGS)); true }.getOrDefault(false)
}
