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
    val isTvBanner: Boolean = false,
    /** Where the icon came from, shown in Settings → Apps. */
    val iconSource: String = "",
    /** Changes only when the app's artwork could change (app updated, icon pack or icon choice changed). */
    val artKey: String = ""
)

object AppCatalog {
    /** The most recent full list, so the drawer can appear instantly while it refreshes. */
    @Volatile
    private var lastLoaded: List<InstalledApp> = emptyList()

    fun cached(): List<InstalledApp> = lastLoaded

    /**
     * Every launchable app, already sorted the way the drawer shows them. Reads icons and icon
     * packs, so call it off the main thread where possible.
     */
    fun load(context: Context, includeHidden: Boolean = false): List<InstalledApp> {
        val pm = context.packageManager
        val leanbackIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        fun query(intent: Intent) = runCatching {
            if (Build.VERSION.SDK_INT >= 33) pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            else @Suppress("DEPRECATION") pm.queryIntentActivities(intent, 0)
        }.getOrDefault(emptyList())

        val leanbackMatches = query(leanbackIntent)
        val launcherMatches = query(launcherIntent)
        val leanbackPackages = leanbackMatches.mapNotNull { it.activityInfo?.packageName }.toSet()
        val found = linkedMapOf<String, InstalledApp>()
        val selectedPack = DrawerSettings.iconPack(context)
        val overrides = DrawerSettings.appIconOverrides(context)
        val packUpdated = selectedPack?.let { pack ->
            runCatching { pm.getPackageInfo(pack, 0).lastUpdateTime }.getOrNull()
        } ?: 0L
        val packLabel = selectedPack?.let { pack ->
            runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pack, 0)).toString() }.getOrNull()
        }

        (leanbackMatches + launcherMatches).forEach { info ->
            val activity = info.activityInfo ?: return@forEach
            val packageName = activity.packageName
            if (packageName == context.packageName || found.containsKey(packageName)) return@forEach

            val override = overrides[packageName]
            val label = info.loadLabel(pm).toString()
            val packIcon = IconPackManager.iconFor(
                context, selectedPack, packageName, override,
                appLabel = label,
                original = { runCatching { info.loadIcon(pm) }.getOrNull() }
            )

            // TV banners are landscape artwork made for ten-foot launchers.
            val banner = if (packageName in leanbackPackages) {
                runCatching {
                    if (activity.banner != 0) pm.getDrawable(packageName, activity.banner, activity.applicationInfo) else null
                }.getOrNull() ?: runCatching {
                    val bannerId = activity.applicationInfo.banner
                    if (bannerId != 0) pm.getDrawable(packageName, bannerId, activity.applicationInfo) else null
                }.getOrNull()
            } else null

            val launcherIcon = runCatching { info.loadIcon(pm) }.getOrNull()
            val chosen = packIcon ?: banner ?: launcherIcon ?: return@forEach
            val source = when {
                packIcon != null && override != null && override != DrawerSettings.ICON_APP_OWN -> "Icon you picked"
                packIcon != null -> "Icon from ${packLabel ?: "your icon pack"}"
                override == DrawerSettings.ICON_APP_OWN -> "App's own icon"
                else -> ""
            }
            val updated = runCatching { pm.getPackageInfo(packageName, 0).lastUpdateTime }.getOrDefault(0L)
            found[packageName] = InstalledApp(
                label = label,
                packageName = packageName,
                icon = chosen,
                isTvBanner = packIcon == null && banner != null,
                iconSource = source,
                artKey = "$updated|${selectedPack.orEmpty()}@$packUpdated|${override.orEmpty()}|${DrawerSettings.matchAllIcons(context)}"
            )
        }
        // Get every tile's image ready here, in the background, so the drawer never waits on it.
        found.values.forEach { AppArtCache.prepare(it) }

        val sorted = sort(context, found.values.toList())
        lastLoaded = sorted
        val hidden = if (includeHidden) emptySet() else DrawerSettings.hidden(context)
        return sorted.filterNot { it.packageName in hidden }
    }

    /** Your Nuvio app first, then favorites, then your own order, then A–Z. */
    fun sort(context: Context, apps: List<InstalledApp>): List<InstalledApp> {
        val nuvio = DrawerSettings.selectedNuvio(context)
        val order = DrawerSettings.order(context)
        val favorites = DrawerSettings.favorites(context)
        return apps.sortedWith(
            compareByDescending<InstalledApp> { it.packageName == nuvio }
                .thenByDescending { it.packageName in favorites }
                .thenBy { order.indexOf(it.packageName).let { index -> if (index < 0) Int.MAX_VALUE else index } }
                .thenBy { it.label.lowercase() }
        )
    }

    fun launch(context: Context, packageName: String): Boolean {
        val pm = context.packageManager
        val launchIntent = pm.getLeanbackLaunchIntentForPackage(packageName) ?: pm.getLaunchIntentForPackage(packageName)
        val candidates = if (launchIntent != null) {
            listOf(launchIntent)
        } else {
            listOf(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER),
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            ).flatMap { query ->
                val matches = runCatching {
                    if (Build.VERSION.SDK_INT >= 33) {
                        pm.queryIntentActivities(query, PackageManager.ResolveInfoFlags.of(0))
                    } else {
                        @Suppress("DEPRECATION")
                        pm.queryIntentActivities(query, 0)
                    }
                }.getOrDefault(emptyList())
                matches.filter { it.activityInfo?.packageName == packageName }.mapNotNull { info ->
                    info.activityInfo?.let { activity ->
                        Intent(Intent.ACTION_MAIN).setClassName(activity.packageName, activity.name)
                    }
                }
            }
        }
        val opened = candidates.any { intent ->
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent); true }.getOrDefault(false)
        }
        if (opened) DrawerSettings.recordLaunch(context, packageName)
        return opened
    }

    /** Label for a package, or the package name if it can't be read. */
    fun label(context: Context, packageName: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)
}
