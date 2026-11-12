package com.homelessbrian.nuviocompanion

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.graphics.drawable.Drawable

data class InstalledApp(val label: String, val packageName: String, val icon: Drawable)

object AppCatalog {
    fun load(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        val queries = listOf(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        )
        val found = linkedMapOf<String, InstalledApp>()
        queries.forEach { query ->
            val matches = if (Build.VERSION.SDK_INT >= 33) {
                pm.queryIntentActivities(query, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION") pm.queryIntentActivities(query, 0)
            }
            matches.forEach { info ->
                val activity = info.activityInfo ?: return@forEach
                if (activity.packageName == context.packageName) return@forEach
                if (!found.containsKey(activity.packageName)) found[activity.packageName] = InstalledApp(
                    info.loadLabel(pm).toString(), activity.packageName, info.loadIcon(pm)
                )
            }
        }
        val hidden = DrawerSettings.hidden(context)
        val customOrder = DrawerSettings.order(context).split("|").filter { it.isNotBlank() }
        val visible = found.values.filterNot { it.packageName in hidden }
        return visible.sortedWith(compareBy<InstalledApp> {
            val index = customOrder.indexOf(it.packageName)
            if (index < 0) Int.MAX_VALUE else index
        }.thenBy { it.label.lowercase() })
    }
}
