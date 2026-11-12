package com.homelessbrian.nuviocompanion

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.content.res.XmlResourceParser
import android.graphics.drawable.Drawable
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.InputStream

data class InstalledIconPack(val packageName: String, val label: String)
data class IconPackEntry(val component: String, val drawableName: String, val category: String = "")
data class IconPackCatalog(val entries: List<IconPackEntry>, val error: String? = null)

object IconPackManager {
    private val THEME_ACTIONS = listOf(
        "app.lawnchair.icons.THEMED_ICON",
        "org.adw.ActivityStarter.THEMES",
        "com.novalauncher.THEME",
        "com.anddoes.launcher.THEME",
        "com.teslacoilsw.launcher.THEME",
        "org.adw.launcher.THEMES"
    )

    fun installed(context: Context): List<InstalledIconPack> {
        val pm = context.packageManager
        val matches = THEME_ACTIONS.flatMap { action ->
            val intent = Intent(action)
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, 0)
            }
        }
        return matches.mapNotNull { info ->
            val activity = info.activityInfo ?: return@mapNotNull null
            InstalledIconPack(activity.packageName, info.loadLabel(pm).toString())
        }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
    }

    /**
     * Follows the lookup strategy used by Lawnchair and Kvaesitso:
     * res/xml first, then res/raw, then assets. drawable.xml is the
     * browsable catalogue; appfilter.xml remains the automatic mapping.
     */
    fun catalog(context: Context, packPackage: String?): IconPackCatalog {
        if (packPackage.isNullOrBlank()) return IconPackCatalog(emptyList(), "No icon pack is selected.")
        return try {
            val resources = context.packageManager.getResourcesForApplication(packPackage)
            val parser = openPackXml(context, packPackage, resources, "drawable")
                ?: return IconPackCatalog(
                    emptyList(),
                    "This pack does not expose drawable.xml in res/xml, res/raw, or assets."
                )

            val parsed = try {
                readCatalog(parser)
            } finally {
                closeParser(parser)
            }

            val distinct = parsed.distinctBy { it.drawableName }
            if (distinct.isEmpty()) {
                return IconPackCatalog(emptyList(), "drawable.xml was found, but it contains no drawable entries.")
            }

            val available = distinct.filter { resolveDrawable(resources, packPackage, it.drawableName) != null }
                .sortedWith(compareBy<IconPackEntry> { it.category.lowercase() }.thenBy { it.drawableName.lowercase() })

            if (available.isEmpty()) {
                IconPackCatalog(
                    emptyList(),
                    "The catalogue contains ${distinct.size} icons, but none resolve to drawable or mipmap resources."
                )
            } else {
                IconPackCatalog(available)
            }
        } catch (error: Exception) {
            IconPackCatalog(
                emptyList(),
                "Could not read this icon pack: ${error.javaClass.simpleName}: ${error.message ?: "unknown error"}"
            )
        }
    }

    fun entries(context: Context, packPackage: String?): List<IconPackEntry> =
        catalog(context, packPackage).entries

    private fun openPackXml(
        context: Context,
        packPackage: String,
        resources: Resources,
        name: String
    ): XmlPullParser? {
        val xmlId = resources.getIdentifier(name, "xml", packPackage)
        if (xmlId != 0) return resources.getXml(xmlId)

        val rawId = resources.getIdentifier(name, "raw", packPackage)
        if (rawId != 0) {
            val stream = resources.openRawResource(rawId)
            return parserFor(stream)
        }

        val packContext = context.createPackageContext(packPackage, Context.CONTEXT_IGNORE_SECURITY)
        return try {
            parserFor(packContext.assets.open("$name.xml"))
        } catch (_: IOException) {
            null
        }
    }

    private fun parserFor(stream: InputStream): XmlPullParser {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(stream, "UTF-8")
        return parser
    }

    private fun closeParser(parser: XmlPullParser) {
        when (parser) {
            is XmlResourceParser -> parser.close()
            else -> runCatching { (parser as? AutoCloseable)?.close() }
        }
    }

    private fun readCatalog(parser: XmlPullParser): List<IconPackEntry> {
        val result = mutableListOf<IconPackEntry>()
        var category = ""
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "category" -> {
                    // Blueprint categories are self-closing markers. The category
                    // remains active until the next category marker.
                    category = parser.getAttributeValue(null, "title")
                        ?: parser.getAttributeValue(null, "name")
                        ?: ""
                }
                "item" -> {
                    val drawableName = parser.getAttributeValue(null, "drawable").orEmpty()
                    if (drawableName.isNotBlank()) {
                        result += IconPackEntry("", drawableName, category)
                    }
                }
            }
        }
        return result
    }

    fun iconFor(context: Context, packPackage: String?, appPackage: String, overrideName: String? = null): Drawable? {
        if (packPackage.isNullOrBlank()) return null
        return runCatching {
            val resources = context.packageManager.getResourcesForApplication(packPackage)
            val drawableName = overrideName ?: appMappings(context, packPackage)
                .firstOrNull { it.component.startsWith("ComponentInfo{$appPackage/") }?.drawableName
                ?: return@runCatching null
            resolveDrawable(resources, packPackage, drawableName)
        }.getOrNull()
    }

    fun drawable(context: Context, packPackage: String, drawableName: String): Drawable? =
        runCatching {
            val resources = context.packageManager.getResourcesForApplication(packPackage)
            resolveDrawable(resources, packPackage, drawableName)
        }.getOrNull()

    private fun resolveDrawable(resources: Resources, packPackage: String, drawableName: String): Drawable? {
        val id = resources.getIdentifier(drawableName, "drawable", packPackage)
            .takeIf { it != 0 }
            ?: resources.getIdentifier(drawableName, "mipmap", packPackage).takeIf { it != 0 }
            ?: return null
        return resources.getDrawable(id, null)
    }

    private fun appMappings(context: Context, packPackage: String): List<IconPackEntry> =
        runCatching {
            val resources = context.packageManager.getResourcesForApplication(packPackage)
            val parser = openPackXml(context, packPackage, resources, "appfilter") ?: return@runCatching emptyList()
            try {
                readEntries(parser)
            } finally {
                closeParser(parser)
            }
        }.getOrDefault(emptyList())

    private fun readEntries(parser: XmlPullParser): List<IconPackEntry> {
        val result = mutableListOf<IconPackEntry>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG || parser.name != "item") continue
            val component = parser.getAttributeValue(null, "component").orEmpty()
            val drawable = parser.getAttributeValue(null, "drawable").orEmpty()
            if (component.isNotBlank() && drawable.isNotBlank()) {
                result += IconPackEntry(component, drawable)
            }
        }
        return result
    }
}
