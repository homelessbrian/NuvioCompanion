package com.homelessbrian.nuviocompanion

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

data class InstalledIconPack(val packageName: String, val label: String)

object IconPackManager {
    private const val THEME_ACTION = "org.adw.ActivityStarter.THEMES"

    fun installed(context: Context): List<InstalledIconPack> {
        val pm = context.packageManager
        val intent = Intent(THEME_ACTION)
        val matches = if (android.os.Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION") pm.queryIntentActivities(intent, 0)
        }
        return matches.mapNotNull { info ->
            val activity = info.activityInfo ?: return@mapNotNull null
            InstalledIconPack(activity.packageName, info.loadLabel(pm).toString())
        }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
    }

    fun iconFor(context: Context, packPackage: String?, appPackage: String): Drawable? {
        if (packPackage.isNullOrBlank()) return null
        return runCatching {
            val packContext = context.createPackageContext(packPackage, Context.CONTEXT_IGNORE_SECURITY)
            val drawableName = packContext.assets.open("appfilter.xml").use { input ->
                findDrawableName(input, appPackage)
            } ?: return@runCatching null
            val id = packContext.resources.getIdentifier(drawableName, "drawable", packPackage)
                .takeIf { it != 0 }
                ?: packContext.resources.getIdentifier(drawableName, "mipmap", packPackage)
                    .takeIf { it != 0 }
                ?: return@runCatching null
            packContext.getDrawable(id)
        }.getOrNull()
    }

    private fun findDrawableName(input: InputStream, appPackage: String): String? {
        val parser = Xml.newPullParser()
        parser.setInput(input, "UTF-8")
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "item") {
                val component = parser.getAttributeValue(null, "component").orEmpty()
                if (component.startsWith("ComponentInfo{$appPackage/")) {
                    return parser.getAttributeValue(null, "drawable")
                }
            }
            event = parser.next()
        }
        return null
    }
}
