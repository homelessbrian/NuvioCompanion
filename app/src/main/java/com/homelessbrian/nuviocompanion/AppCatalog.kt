package com.homelessbrian.nuviocompanion

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build

data class InstalledApp(val label: String, val packageName: String, val icon: Drawable)

object AppCatalog {
    fun load(context: Context, includeHidden: Boolean = false): List<InstalledApp> {
        val pm = context.packageManager
        val intents = listOf(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        )
        val found = linkedMapOf<String, InstalledApp>()
        intents.forEach { intent ->
            val matches = if (Build.VERSION.SDK_INT >= 33) pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
                else @Suppress("DEPRECATION") pm.queryIntentActivities(intent, 0)
            matches.forEach { info ->
                val activity = info.activityInfo ?: return@forEach
                if (activity.packageName == context.packageName || found.containsKey(activity.packageName)) return@forEach
                found[activity.packageName] = InstalledApp(info.loadLabel(pm).toString(), activity.packageName, IconPackManager.iconFor(context, DrawerSettings.iconPack(context), activity.packageName) ?: info.loadIcon(pm))
            }
        }
        val hidden = if (includeHidden) emptySet() else DrawerSettings.hidden(context)
        val order = DrawerSettings.order(context)
        val favorites = DrawerSettings.favorites(context)
        return found.values.filterNot { it.packageName in hidden }.sortedWith(
            compareByDescending<InstalledApp> { it.packageName in favorites }
                .thenBy { order.indexOf(it.packageName).let { index -> if (index < 0) Int.MAX_VALUE else index } }
                .thenBy { it.label.lowercase() }
        )
    }

    fun launch(context: Context, packageName: String): Boolean {
        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(packageName)
        val candidates = if (launchIntent != null) {
            listOf(launchIntent)
        } else {
            listOf(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER),
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            ).flatMap { query ->
                val matches = if (Build.VERSION.SDK_INT >= 33) {
                    pm.queryIntentActivities(query, PackageManager.ResolveInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.queryIntentActivities(query, 0)
                }
                matches.filter { it.activityInfo?.packageName == packageName }.mapNotNull { info ->
                    info.activityInfo?.let { activity ->
                        Intent(Intent.ACTION_MAIN).setClassName(activity.packageName, activity.name)
                    }
                }
            }
        }
        return candidates.any { intent ->
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent); true }.getOrDefault(false)
        }
    }
}
