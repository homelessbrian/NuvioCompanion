package com.homelessbrian.nuviocompanion

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

data class InstalledIconPack(val packageName: String, val label: String)
data class IconPackEntry(val component: String, val drawableName: String, val category: String = "")

object IconPackManager {
    private val THEME_ACTIONS = listOf("org.adw.ActivityStarter.THEMES", "com.novalauncher.THEME", "com.anddoes.launcher.THEME", "com.teslacoilsw.launcher.THEME")

    fun installed(context: Context): List<InstalledIconPack> {
        val pm = context.packageManager
        val matches = THEME_ACTIONS.flatMap { action ->
            val intent = Intent(action)
            if (android.os.Build.VERSION.SDK_INT >= 33) pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            else @Suppress("DEPRECATION") pm.queryIntentActivities(intent, 0)
        }
        return matches.mapNotNull { info ->
            val activity = info.activityInfo ?: return@mapNotNull null
            InstalledIconPack(activity.packageName, info.loadLabel(pm).toString())
        }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
    }

    /**
     * Returns the pack's complete browsable icon catalogue. Blueprint-based packs expose
     * this through drawable.xml; appfilter.xml is only the automatic app-to-icon mapping
     * and must not be used as the picker catalogue.
     */
    fun entries(context: Context, packPackage: String?): List<IconPackEntry> {
        if (packPackage.isNullOrBlank()) return emptyList()
        return runCatching {
            val packContext = context.createPackageContext(packPackage, Context.CONTEXT_IGNORE_SECURITY)
            val catalog = runCatching {
                packContext.assets.open("drawable.xml").use(::readCatalog)
            }.getOrNull() ?: runCatching {
                val xmlId = packContext.resources.getIdentifier("drawable", "xml", packPackage)
                if (xmlId == 0) emptyList() else packContext.resources.getXml(xmlId).use(::readCatalog)
            }.getOrNull().orEmpty()

            catalog.distinctBy { it.drawableName }
                .filter { drawable(packContext, packPackage, it.drawableName) != null }
                .sortedWith(compareBy<IconPackEntry> { it.category.lowercase() }.thenBy { it.drawableName.lowercase() })
        }.getOrDefault(emptyList())
    }

    private fun readCatalog(input: InputStream): List<IconPackEntry> {
        val parser = Xml.newPullParser()
        parser.setInput(input, "UTF-8")
        return readCatalog(parser)
    }

    private fun readCatalog(parser: XmlPullParser): List<IconPackEntry> {
        val result = mutableListOf<IconPackEntry>()
        var category = ""
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "category" -> category = parser.getAttributeValue(null, "title")
                        ?: parser.getAttributeValue(null, "name")
                        ?: ""
                    "item" -> {
                        val drawable = parser.getAttributeValue(null, "drawable").orEmpty()
                        if (drawable.isNotBlank()) {
                            result += IconPackEntry(
                                component = "",
                                drawableName = drawable,
                                category = category
                            )
                        }
                    }
                }
            } else if (event == XmlPullParser.END_TAG && parser.name == "category") {
                category = ""
            }
            event = parser.next()
        }
        return result
    }

    fun iconFor(context: Context, packPackage: String?, appPackage: String, overrideName: String? = null): Drawable? {
        if (packPackage.isNullOrBlank()) return null
        return runCatching {
            val packContext = context.createPackageContext(packPackage, Context.CONTEXT_IGNORE_SECURITY)
            val drawableName = overrideName ?: entries(context, packPackage)
                .firstOrNull { it.component.startsWith("ComponentInfo{$appPackage/") }?.drawableName
                ?: return@runCatching null
            drawable(packContext, packPackage, drawableName)
        }.getOrNull()
    }

    fun drawable(context: Context, packPackage: String, drawableName: String): Drawable? =
        runCatching {
            val packContext = context.createPackageContext(packPackage, Context.CONTEXT_IGNORE_SECURITY)
            drawable(packContext, packPackage, drawableName)
        }.getOrNull()

    private fun drawable(packContext: Context, packPackage: String, drawableName: String): Drawable? =
        runCatching {
            val id = packContext.resources.getIdentifier(drawableName, "drawable", packPackage)
                .takeIf { it != 0 }
                ?: packContext.resources.getIdentifier(drawableName, "mipmap", packPackage)
                    .takeIf { it != 0 }
                ?: return@runCatching null
            packContext.getDrawable(id)
        }.getOrNull()

    private fun readEntries(input: InputStream): List<IconPackEntry> {
        val parser = Xml.newPullParser()
        parser.setInput(input, "UTF-8")
        return readEntries(parser)
    }

    private fun readEntries(parser: XmlPullParser): List<IconPackEntry> {
        val result = mutableListOf<IconPackEntry>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "item") {
                val component = parser.getAttributeValue(null, "component").orEmpty()
                val drawable = parser.getAttributeValue(null, "drawable").orEmpty()
                if (component.isNotBlank() && drawable.isNotBlank()) result += IconPackEntry(component, drawable)
            }
            event = parser.next()
        }
        return result
    }
}
