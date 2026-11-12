package com.homelessbrian.nuviocompanion

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build

data class InstalledApp(
    val label: String,
    val packageName: String,
    val icon: Drawable,
    val isTvBanner: Boolean = false
)

object AppCatalog {
    fun load(context: Context, includeHidden: Boolean = false): List<InstalledApp> {
        val pm = context.packageManager
        val leanbackIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        fun query(intent: Intent) =
            if (Build.VERSION.SDK_INT >= 33) pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            else @Suppress("DEPRECATION") pm.queryIntentActivities(intent, 0)

        val leanbackMatches = query(leanbackIntent)
        val launcherMatches = query(launcherIntent)
        val allMatches = (leanbackMatches + launcherMatches)
        val found = linkedMapOf<String, InstalledApp>()
        val selectedPack = DrawerSettings.iconPack(context)

        allMatches.forEach { info ->
            val activity = info.activityInfo ?: return@forEach
            val packageName = activity.packageName
            if (packageName == context.packageName || found.containsKey(packageName)) return@forEach

            val isLeanback = leanbackMatches.any { it.activityInfo?.packageName == packageName }
            val packIcon = IconPackManager.iconFor(
                context,
                selectedPack,
                packageName,
                DrawerSettings.appIconOverride(context, packageName)
            )

            // TV banners are landscape artwork intended for ten-foot launcher interfaces.
            val banner = if (isLeanback) {
                runCatching {
                    if (activity.banner != 0) {
                        pm.getDrawable(packageName, activity.banner, activity.applicationInfo)
                    } else {
                        null
                    }
                }.getOrNull() ?: runCatching {
                    val bannerId = activity.applicationInfo.banner
                    if (bannerId != 0) pm.getDrawable(packageName, bannerId, activity.applicationInfo) else null
                }.getOrNull()
            } else null

            val launcherIcon = runCatching { info.loadIcon(pm) }.getOrNull()
            val chosen = packIcon ?: banner ?: launcherIcon ?: return@forEach
            found[packageName] = InstalledApp(
                label = info.loadLabel(pm).toString(),
                packageName = packageName,
                icon = chosen,
                isTvBanner = packIcon == null && banner != null
            )
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
