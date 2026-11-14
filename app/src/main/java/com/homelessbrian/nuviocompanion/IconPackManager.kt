package com.homelessbrian.nuviocompanion

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.content.res.XmlResourceParser
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

data class InstalledIconPack(val packageName: String, val label: String)

/** One selectable icon in a pack. [resId] is resolved up front so drawing it later is cheap. */
data class IconPackEntry(val drawableName: String, val category: String, val resId: Int) {
    /** "netflix_alt" -> "netflix alt", used for search and display. */
    val searchName: String = drawableName.replace('_', ' ').replace('-', ' ').lowercase()
}

/** The "make any icon fit" parts of a pack, from appfilter.xml: backgrounds, mask, overlay, scale. */
data class PackTheme(val backs: List<String>, val mask: String?, val upon: String?, val scale: Float) {
    val usable: Boolean get() = backs.isNotEmpty() || mask != null || upon != null
}

data class IconPackCatalog(val entries: List<IconPackEntry>, val categories: List<String>, val error: String? = null)

/**
 * Reads ADW/Nova/Lawnchair-style icon packs.
 *
 * - The browsable list comes from drawable.xml. Many packs don't ship one, so when it is missing
 *   (or empty) the icons named in appfilter.xml are used instead.
 * - Each file is looked for in res/xml, then res/raw, then assets, like Lawnchair and Kvaesitso.
 * - Everything is cached per pack and is meant to be called off the main thread; packs can hold
 *   many thousands of icons.
 */
object IconPackManager {
    private val THEME_ACTIONS = listOf(
        "app.lawnchair.icons.THEMED_ICON",
        "org.adw.ActivityStarter.THEMES",
        "com.novalauncher.THEME",
        "com.anddoes.launcher.THEME",
        "com.teslacoilsw.launcher.THEME",
        "org.adw.launcher.THEMES"
    )

    private val resourcesCache = ConcurrentHashMap<String, Resources>()
    private val mappingCache = ConcurrentHashMap<String, Map<String, String>>()
    private val catalogCache = ConcurrentHashMap<String, IconPackCatalog>()
    private val themeCache = ConcurrentHashMap<String, PackTheme>()
    private val nameIndexCache = ConcurrentHashMap<String, Map<String, String>>()
    private val bitmapCache = object : LruCache<String, ImageBitmap>(600) {}

    fun installed(context: Context): List<InstalledIconPack> {
        val pm = context.packageManager
        val matches = THEME_ACTIONS.flatMap { action ->
            val intent = Intent(action)
            runCatching {
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.queryIntentActivities(intent, 0)
                }
            }.getOrDefault(emptyList())
        }
        return matches.mapNotNull { info ->
            val activity = info.activityInfo ?: return@mapNotNull null
            InstalledIconPack(activity.packageName, info.loadLabel(pm).toString())
        }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
    }

    /** Forget everything read from packs (for example after a pack was updated or removed). */
    fun clearCaches() {
        resourcesCache.clear()
        mappingCache.clear()
        catalogCache.clear()
        themeCache.clear()
        nameIndexCache.clear()
        bitmapCache.evictAll()
    }

    private fun resources(context: Context, pack: String): Resources? =
        resourcesCache[pack] ?: runCatching { context.packageManager.getResourcesForApplication(pack) }
            .getOrNull()?.also { resourcesCache[pack] = it }

    /** Every icon in [pack] that can actually be drawn, with the pack's categories. */
    fun catalog(context: Context, pack: String?): IconPackCatalog {
        if (pack.isNullOrBlank()) return IconPackCatalog(emptyList(), emptyList(), "No icon pack is selected.")
        catalogCache[pack]?.let { return it }
        val result = try {
            val res = resources(context, pack)
                ?: return IconPackCatalog(emptyList(), emptyList(), "This icon pack is no longer installed.")

            var raw = openPackXml(context, pack, res, "drawable")?.let { parser ->
                try { readDrawableList(parser) } finally { closeParser(parser) }
            }.orEmpty()

            // Plenty of packs only ship appfilter.xml. Use the icons it names instead.
            if (raw.isEmpty()) {
                raw = appMappings(context, pack).values.distinct().map { it to "" }
            }
            if (raw.isEmpty()) {
                IconPackCatalog(emptyList(), emptyList(), "This icon pack doesn't list its icons in a way Nuvio Launch can read.")
            } else {
                val entries = raw.distinctBy { it.first }.mapNotNull { (name, category) ->
                    val id = resolveId(res, pack, name)
                    if (id == 0) null else IconPackEntry(name, category, id)
                }
                if (entries.isEmpty()) {
                    IconPackCatalog(emptyList(), emptyList(), "This icon pack lists ${raw.size} icons, but none of them could be loaded.")
                } else {
                    val categories = entries.map { it.category }.filter { it.isNotBlank() }.distinct()
                    IconPackCatalog(entries, categories)
                }
            }
        } catch (error: Exception) {
            IconPackCatalog(emptyList(), emptyList(), "Couldn't read this icon pack (${error.javaClass.simpleName}).")
        }
        if (result.entries.isNotEmpty()) catalogCache[pack] = result
        return result
    }

    /** Number of icons in a pack, for the pack list. Reads the catalog, so call off the main thread. */
    fun iconCount(context: Context, pack: String): Int = catalog(context, pack).entries.size

    /**
     * Icons that probably belong to the app: the pack's own match first, then icons whose name
     * contains the app's name, then icons matching a word from its name or package.
     */
    fun suggestions(context: Context, pack: String, appPackage: String, appLabel: String): List<IconPackEntry> {
        val entries = catalog(context, pack).entries
        if (entries.isEmpty()) return emptyList()
        val byName = entries.associateBy { it.drawableName }
        val mapped = appMappings(context, pack)[appPackage]?.let { byName[it] }

        val label = appLabel.lowercase().filter { it.isLetterOrDigit() }
        val ignore = setOf("com", "org", "net", "app", "apps", "android", "tv", "mobile", "the", "and", "for", "free", "plus", "player", "launcher", "google")
        val words = (appLabel.lowercase().split(Regex("[^a-z0-9]+")) + appPackage.lowercase().split('.', '_'))
            .filter { it.length >= 3 && it !in ignore }
            .distinct()

        val strong = mutableListOf<IconPackEntry>()
        val weak = mutableListOf<IconPackEntry>()
        entries.forEach { entry ->
            if (entry == mapped) return@forEach
            val compact = entry.drawableName.lowercase().filter { it.isLetterOrDigit() }
            when {
                label.length >= 3 && compact.contains(label) -> strong += entry
                words.any { compact.contains(it) } -> weak += entry
            }
        }
        return (listOfNotNull(mapped) + strong.sortedBy { it.drawableName.length } + weak.sortedBy { it.drawableName.length })
            .distinctBy { it.drawableName }
            .take(90)
    }

    /** The icon a pack assigns to [appPackage] through appfilter.xml, if any. */
    fun mappedIcon(context: Context, pack: String, appPackage: String): Drawable? {
        val name = appMappings(context, pack)[appPackage] ?: return null
        return drawable(context, pack, name)
    }

    /**
     * Resolves the icon an app should show, following the user's choice in [override]:
     * the app's own icon, a specific icon from any pack, or the selected pack's icon.
     *
     * With a pack selected, every app gets a pack-style icon:
     *  1. the pack's own match for the app (appfilter.xml),
     *  2. otherwise a pack icon named after the app ("netflix" for Netflix),
     *  3. otherwise the app's own icon redrawn in the pack's style (its background, mask and
     *     overlay), the same way Nova and Lawnchair handle apps a pack doesn't cover.
     * Returns null when the app's own artwork should be used.
     */
    fun iconFor(
        context: Context,
        selectedPack: String?,
        appPackage: String,
        override: String?,
        appLabel: String? = null,
        original: (() -> Drawable?)? = null
    ): Drawable? {
        if (override == DrawerSettings.ICON_APP_OWN) return null
        if (!override.isNullOrBlank()) {
            val split = override.indexOf(':')
            val (pack, name) = if (split > 0) override.substring(0, split) to override.substring(split + 1)
            else (selectedPack ?: "") to override
            if (pack.isNotBlank()) drawable(context, pack, name)?.let { return it }
        }
        if (selectedPack.isNullOrBlank()) return null
        mappedIcon(context, selectedPack, appPackage)?.let { return it }
        if (!DrawerSettings.matchAllIcons(context)) return null
        if (appLabel != null) nameMatch(context, selectedPack, appLabel)?.let { name ->
            drawable(context, selectedPack, name)?.let { return it }
        }
        val source = original?.invoke() ?: return null
        return themed(context, selectedPack, source)
    }

    /** A pack icon whose name is exactly the app's name, ignoring spaces and symbols. */
    private fun nameMatch(context: Context, pack: String, appLabel: String): String? {
        val key = appLabel.lowercase().filter { it.isLetterOrDigit() }
        if (key.length < 3) return null
        val index = nameIndexCache[pack] ?: catalog(context, pack).entries
            .associate { it.drawableName.lowercase().filter { c -> c.isLetterOrDigit() } to it.drawableName }
            .also { nameIndexCache[pack] = it }
        return index[key]
    }

    /** The pack's background / mask / overlay / scale, used to restyle icons it doesn't include. */
    fun theme(context: Context, pack: String): PackTheme {
        themeCache[pack]?.let { return it }
        val theme = runCatching<PackTheme> {
            val res = resources(context, pack) ?: return@runCatching PackTheme(emptyList(), null, null, 1f)
            val parser = openPackXml(context, pack, res, "appfilter") ?: return@runCatching PackTheme(emptyList(), null, null, 1f)
            try { readTheme(parser) } finally { closeParser(parser) }
        }.getOrDefault(PackTheme(emptyList(), null, null, 1f))
        themeCache[pack] = theme
        return theme
    }

    /**
     * Redraws [source] the way the pack would: pack background, the app's icon scaled down on
     * top, the pack's mask cut out of it, then the pack's overlay. Packs without these parts
     * get the icon on a rounded tile in the pack's most common background instead, so it still
     * sits in line with the rest.
     */
    fun themed(context: Context, pack: String, source: Drawable): Drawable? = runCatching {
        val theme = theme(context, pack)
        val size = 192
        val output = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(output)
        val bounds = android.graphics.Rect(0, 0, size, size)

        // Same background for the same app every time.
        val back = theme.backs.takeIf { it.isNotEmpty() }?.let { backs ->
            drawable(context, pack, backs[Math.floorMod(source.hashCode(), backs.size)])
        }
        if (back != null) {
            back.bounds = bounds
            back.draw(canvas)
        } else if (!theme.usable) {
            // Pack has no styling parts: a plain rounded tile keeps every icon the same shape.
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF24242C.toInt() }
            canvas.drawRoundRect(android.graphics.RectF(0f, 0f, size.toFloat(), size.toFloat()), size * 0.22f, size * 0.22f, paint)
        }

        val scale = (if (theme.usable) theme.scale else 0.68f).coerceIn(0.3f, 1f)
        val inner = (size * scale).toInt()
        val offset = (size - inner) / 2
        val layer = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val layerCanvas = android.graphics.Canvas(layer)
        val icon = source.toBitmapPreservingAspect(inner)
        layerCanvas.drawBitmap(icon, (offset + (inner - icon.width) / 2).toFloat(), (offset + (inner - icon.height) / 2).toFloat(), null)
        theme.mask?.let { name ->
            drawable(context, pack, name)?.let { mask ->
                val maskBitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
                mask.bounds = bounds
                mask.draw(android.graphics.Canvas(maskBitmap))
                val paint = android.graphics.Paint().apply {
                    xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT)
                }
                layerCanvas.drawBitmap(maskBitmap, 0f, 0f, paint)
            }
        }
        canvas.drawBitmap(layer, 0f, 0f, null)
        theme.upon?.let { name -> drawable(context, pack, name)?.let { it.bounds = bounds; it.draw(canvas) } }
        android.graphics.drawable.BitmapDrawable(context.resources, output)
    }.getOrNull()

    fun drawable(context: Context, pack: String, drawableName: String): Drawable? = runCatching {
        val res = resources(context, pack) ?: return null
        val id = resolveId(res, pack, drawableName)
        if (id == 0) null else res.getDrawable(id, null)
    }.getOrNull()

    /** The cached preview, if it has already been drawn once. Safe on the main thread. */
    fun previewBitmapCachedOnly(pack: String, entry: IconPackEntry, size: Int = 144): ImageBitmap? =
        bitmapCache.get("$pack/${entry.drawableName}/$size")

    /** A small bitmap for the picker grid, cached so scrolling back and forth stays smooth. */
    fun previewBitmap(context: Context, pack: String, entry: IconPackEntry, size: Int = 144): ImageBitmap? {
        val key = "$pack/${entry.drawableName}/$size"
        bitmapCache.get(key)?.let { return it }
        val bitmap = runCatching {
            val res = resources(context, pack) ?: return null
            res.getDrawable(entry.resId, null).toImageBitmapSafe(size)
        }.getOrNull() ?: return null
        bitmapCache.put(key, bitmap)
        return bitmap
    }

    private fun resolveId(res: Resources, pack: String, name: String): Int =
        res.getIdentifier(name, "drawable", pack).takeIf { it != 0 }
            ?: res.getIdentifier(name, "mipmap", pack)

    /** appPackage -> drawable name, from appfilter.xml. The first entry for a package wins. */
    private fun appMappings(context: Context, pack: String): Map<String, String> {
        mappingCache[pack]?.let { return it }
        val map: Map<String, String> = runCatching<Map<String, String>> {
            val res = resources(context, pack) ?: return@runCatching emptyMap()
            val parser = openPackXml(context, pack, res, "appfilter") ?: return@runCatching emptyMap()
            try { readAppFilter(parser) } finally { closeParser(parser) }
        }.getOrDefault(emptyMap())
        mappingCache[pack] = map
        return map
    }

    private fun openPackXml(context: Context, pack: String, res: Resources, name: String): XmlPullParser? {
        val xmlId = res.getIdentifier(name, "xml", pack)
        if (xmlId != 0) return res.getXml(xmlId)

        val rawId = res.getIdentifier(name, "raw", pack)
        if (rawId != 0) return parserFor(res.openRawResource(rawId))

        return try {
            val packContext = context.createPackageContext(pack, Context.CONTEXT_IGNORE_SECURITY)
            parserFor(packContext.assets.open("$name.xml"))
        } catch (_: IOException) {
            null
        } catch (_: PackageManager.NameNotFoundException) {
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

    /** drawable.xml: <category title="..."/> markers followed by <item drawable="..."/> rows. */
    private fun readDrawableList(parser: XmlPullParser): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        var category = ""
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "category" -> category = parser.getAttributeValue(null, "title")
                    ?: parser.getAttributeValue(null, "name")
                    ?: ""
                "item" -> {
                    val name = parser.getAttributeValue(null, "drawable").orEmpty()
                    if (name.isNotBlank()) result += name to category
                }
            }
        }
        return result
    }

    /** appfilter.xml: <iconback img1=".."/>, <iconmask img1=".."/>, <iconupon img1=".."/>, <scale factor=".."/>. */
    private fun readTheme(parser: XmlPullParser): PackTheme {
        val backs = mutableListOf<String>()
        var mask: String? = null
        var upon: String? = null
        var scale = 1f
        fun images(): List<String> = (0 until parser.attributeCount)
            .filter { parser.getAttributeName(it).startsWith("img") }
            .mapNotNull { parser.getAttributeValue(it)?.takeIf(String::isNotBlank) }
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "iconback" -> backs += images()
                "iconmask" -> mask = images().firstOrNull() ?: mask
                "iconupon" -> upon = images().firstOrNull() ?: upon
                "scale" -> scale = parser.getAttributeValue(null, "factor")?.toFloatOrNull() ?: scale
            }
        }
        return PackTheme(backs, mask, upon, scale)
    }

    /** appfilter.xml: <item component="ComponentInfo{pkg/cls}" drawable="..."/>. */
    private fun readAppFilter(parser: XmlPullParser): Map<String, String> {
        val result = linkedMapOf<String, String>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG || parser.name != "item") continue
            val component = parser.getAttributeValue(null, "component").orEmpty()
            val drawable = parser.getAttributeValue(null, "drawable").orEmpty()
            if (drawable.isBlank()) continue
            val start = component.indexOf('{')
            val slash = component.indexOf('/', start + 1)
            if (start < 0 || slash < 0) continue
            val appPackage = component.substring(start + 1, slash).trim()
            if (appPackage.isNotEmpty() && appPackage !in result) result[appPackage] = drawable
        }
        return result
    }
}
