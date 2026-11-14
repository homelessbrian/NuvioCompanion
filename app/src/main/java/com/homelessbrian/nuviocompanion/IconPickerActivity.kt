package com.homelessbrian.nuviocompanion

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Full-screen icon picker for one app, opened from Settings → Apps or by holding OK on an app
 * in the drawer. Works with any installed icon pack, not only the one selected for all apps.
 */
class IconPickerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val packageName = intent.getStringExtra(EXTRA_PACKAGE)
        if (packageName.isNullOrBlank()) {
            finish()
            return
        }
        setContent {
            LaunchTheme(DrawerSettings.highlight(this).color) {
                IconPickerScreen(packageName, onDone = { finish() })
            }
        }
    }

    companion object {
        const val EXTRA_PACKAGE = "package"

        fun open(context: Context, packageName: String) {
            val intent = Intent(context, IconPickerActivity::class.java)
                .putExtra(EXTRA_PACKAGE, packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }
    }
}

private sealed interface IconFilter {
    data object Suggested : IconFilter
    data object All : IconFilter
    data class Category(val name: String) : IconFilter
    data object Search : IconFilter
}

private data class PickerApp(val label: String, val now: ImageBitmap?)

@Composable
private fun IconPickerScreen(appPackage: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val packs = remember { IconPackManager.installed(context) }
    val globalPack = remember { DrawerSettings.iconPack(context) }
    var pack by remember { mutableStateOf(globalPack?.takeIf { selected -> packs.any { it.packageName == selected } } ?: packs.firstOrNull()?.packageName) }
    var choosingPack by remember { mutableStateOf(pack == null && packs.isNotEmpty()) }
    var filter by remember { mutableStateOf<IconFilter>(IconFilter.Suggested) }
    var query by remember { mutableStateOf("") }
    var previewEntry by remember { mutableStateOf<IconPackEntry?>(null) }
    val gridFocus = remember { FocusRequester() }
    val packListFocus = remember { FocusRequester() }

    BackHandler(enabled = choosingPack && pack != null) { choosingPack = false }

    val app by produceState(PickerApp(AppCatalog.label(context, appPackage), null), appPackage) {
        value = withContext(Dispatchers.IO) {
            val found = AppCatalog.load(context, includeHidden = true).firstOrNull { it.packageName == appPackage }
            PickerApp(found?.label ?: AppCatalog.label(context, appPackage), found?.icon?.toImageBitmapSafe(256))
        }
    }

    // Reading a pack can take a moment for big packs, so it happens off the main thread.
    val catalog by produceState<IconPackCatalog?>(null, pack) {
        value = null
        val current = pack
        value = if (current == null) null else withContext(Dispatchers.IO) { IconPackManager.catalog(context, current) }
    }
    val suggestions by produceState<List<IconPackEntry>>(emptyList(), pack, catalog, app.label) {
        val current = pack
        value = if (current == null || catalog == null) emptyList()
        else withContext(Dispatchers.IO) { IconPackManager.suggestions(context, current, appPackage, app.label) }
    }

    val entries = catalog?.entries.orEmpty()
    val shown: List<IconPackEntry> = remember(entries, suggestions, filter, query) {
        when (val f = filter) {
            IconFilter.Suggested -> suggestions
            IconFilter.All -> entries.sortedBy { it.searchName }
            is IconFilter.Category -> entries.filter { it.category == f.name }
            IconFilter.Search -> {
                val q = query.trim().lowercase()
                if (q.isEmpty()) emptyList() else entries.filter { it.searchName.contains(q) }.sortedBy { it.searchName.length }
            }
        }
    }

    // When a pack has nothing that looks like this app, start on the full list instead.
    LaunchedEffect(catalog, suggestions) {
        if (catalog != null && catalog!!.entries.isNotEmpty() && suggestions.isEmpty() && filter == IconFilter.Suggested) {
            filter = IconFilter.All
        }
    }
    LaunchedEffect(choosingPack) {
        withFrameNanos { }
        if (choosingPack) runCatching { packListFocus.requestFocus() }
    }
    LaunchedEffect(shown.firstOrNull()?.drawableName, choosingPack) {
        if (!choosingPack && shown.isNotEmpty() && filter != IconFilter.Search) {
            withFrameNanos { }
            runCatching { gridFocus.requestFocus() }
        }
    }

    fun choose(value: String?) {
        DrawerSettings.setAppIconOverride(context, appPackage, value)
        onDone()
    }

    Row(
        Modifier.launchBackground().padding(start = 72.dp, end = 72.dp, top = 40.dp, bottom = 36.dp),
        horizontalArrangement = Arrangement.spacedBy(48.dp)
    ) {
        // ---- Left: what's changing and the quick options ----------------------------------
        Column(Modifier.width(300.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("‹", color = LaunchColors.Muted, fontSize = 22.sp)
                Text("Back to apps", color = LaunchColors.Muted, fontSize = 16.sp)
            }
            Text("Icon for ${app.label}", color = LaunchColors.Text, fontSize = 32.sp, fontWeight = FontWeight.Bold, lineHeight = 37.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBox("Now", app.now, false)
                Text("→", color = Color(0xFF52525B), fontSize = 24.sp)
                val previewBitmap by produceState<ImageBitmap?>(null, pack, previewEntry) {
                    val current = pack
                    val entry = previewEntry
                    value = if (current == null || entry == null) null
                    else withContext(Dispatchers.IO) { IconPackManager.previewBitmap(context, current, entry, 256) }
                }
                IconBox("Preview", previewBitmap, true)
            }
            Spacer(Modifier.height(4.dp))
            val packLabel = packs.firstOrNull { it.packageName == pack }?.label ?: "None installed"
            SettingRow("Pack", value = packLabel, onClick = { if (packs.isNotEmpty()) choosingPack = true })
            SettingRow("Use pack's default", chevron = false, onClick = { choose(null) })
            SettingRow("Use app's own icon", chevron = false, onClick = { choose(DrawerSettings.ICON_APP_OWN) })
        }

        // ---- Right: browse --------------------------------------------------------------------
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when {
                packs.isEmpty() -> EmptyMessage(
                    "No icon packs installed",
                    "Install an icon pack from the Play Store (any pack that works with Nova, Lawnchair or ADW), then come back here."
                )
                choosingPack -> {
                    Text("Choose a pack to browse", color = LaunchColors.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("This only changes where you're picking from. Your icon pack for all apps stays the same.", color = LaunchColors.Muted, fontSize = 16.sp)
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                        items(packs, key = { it.packageName }) { item ->
                            val first = item == packs.first()
                            SettingRow(
                                title = item.label,
                                value = if (item.packageName == pack) "Browsing" else null,
                                modifier = if (first) Modifier.focusRequester(packListFocus) else Modifier,
                                onClick = {
                                    pack = item.packageName
                                    filter = IconFilter.Suggested
                                    choosingPack = false
                                }
                            )
                        }
                    }
                }
                else -> {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp)) {
                        item { FilterChip("Suggested", filter == IconFilter.Suggested) { filter = IconFilter.Suggested } }
                        item { FilterChip("A–Z", filter == IconFilter.All) { filter = IconFilter.All } }
                        items(catalog?.categories.orEmpty(), key = { "cat-$it" }) { name ->
                            FilterChip(name, filter == IconFilter.Category(name)) { filter = IconFilter.Category(name) }
                        }
                        item { FilterChip("Search", filter == IconFilter.Search) { filter = IconFilter.Search } }
                    }
                    if (filter == IconFilter.Search) {
                        SearchField(query, onQuery = { query = it })
                    }
                    val total = entries.size
                    val status = when {
                        catalog == null -> "Loading icons…"
                        catalog!!.entries.isEmpty() -> catalog!!.error ?: "No icons found in this pack."
                        filter == IconFilter.Suggested -> "%,d icons · Suggested ones match “%s”".format(total, app.label)
                        filter == IconFilter.Search && query.isBlank() -> "%,d icons · Type to search by name".format(total)
                        filter == IconFilter.Search -> "%,d matches for “%s”".format(shown.size, query.trim())
                        else -> "%,d icons".format(shown.size)
                    }
                    Text(status, color = LaunchColors.Faint, fontSize = 15.sp)
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(6),
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = PaddingValues(6.dp)
                    ) {
                        itemsIndexed(shown, key = { _, entry -> entry.drawableName }) { index, entry ->
                            IconCell(
                                pack = pack!!,
                                entry = entry,
                                modifier = if (index == 0) Modifier.focusRequester(gridFocus) else Modifier,
                                onFocused = { previewEntry = entry },
                                onClick = { choose("${pack!!}:${entry.drawableName}") }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IconBox(label: String, bitmap: ImageBitmap?, highlighted: Boolean) {
    val highlight = LocalHighlight.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.size(120.dp).clip(RoundedCornerShape(28.dp)).background(LaunchColors.Panel)
                .then(if (highlighted) Modifier.border(2.dp, highlight, RoundedCornerShape(28.dp)) else Modifier),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) Image(bitmap, label, Modifier.size(80.dp), contentScale = ContentScale.Fit)
            else Text("—", color = LaunchColors.Faint, fontSize = 22.sp)
        }
        Text(label, color = LaunchColors.Muted, fontSize = 14.sp)
    }
}

@Composable
private fun IconCell(pack: String, entry: IconPackEntry, modifier: Modifier, onFocused: () -> Unit, onClick: () -> Unit) {
    val context = LocalContext.current
    val bitmap by produceState(IconPackManager.previewBitmapCachedOnly(pack, entry), pack, entry.drawableName) {
        value = IconPackManager.previewBitmapCachedOnly(pack, entry)
            ?: withContext(Dispatchers.IO) { IconPackManager.previewBitmap(context, pack, entry) }
    }
    FocusCard(modifier = modifier.fillMaxWidth(), scaleOnFocus = 1.06f, onFocused = onFocused, onClick = onClick) { focused ->
        Column(
            Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 14.dp, bottom = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(Modifier.size(58.dp), contentAlignment = Alignment.Center) {
                val image = bitmap
                if (image != null) Image(image, entry.drawableName, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                else Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(LaunchColors.Raised))
            }
            Text(
                entry.drawableName,
                color = if (focused) LaunchColors.OnFocus else LaunchColors.Muted,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FocusCard(shape = RoundedCornerShape(999.dp), idle = if (selected) LaunchColors.Raised else Color.Transparent, scaleOnFocus = 1.04f, onClick = onClick) { focused ->
        Text(
            label,
            color = when {
                focused -> LaunchColors.OnFocus
                selected -> LaunchColors.Text
                else -> LaunchColors.Muted
            },
            fontSize = 16.sp,
            fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp)
        )
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val highlight = LocalHighlight.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }
    BasicTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        textStyle = TextStyle(color = LaunchColors.Text, fontSize = 20.sp),
        cursorBrush = SolidColor(highlight),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { }),
        modifier = Modifier.fillMaxWidth()
            .focusRequester(focus)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(14.dp))
            .background(LaunchColors.Panel)
            .border(2.dp, if (focused) highlight else Color.Transparent, RoundedCornerShape(14.dp))
            .padding(horizontal = 20.dp, vertical = 14.dp),
        decorationBox = { inner ->
            Box {
                if (query.isEmpty()) Text("Type an app or icon name, like “netflix”", color = LaunchColors.Faint, fontSize = 20.sp)
                inner()
            }
        }
    )
}

@Composable
fun EmptyMessage(title: String, body: String) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            Modifier.widthIn(max = 640.dp).clip(RoundedCornerShape(20.dp)).background(LaunchColors.Panel).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, color = LaunchColors.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(body, color = LaunchColors.Muted, fontSize = 17.sp, lineHeight = 25.sp)
        }
    }
}
