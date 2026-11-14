package com.homelessbrian.nuviocompanion

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.composed
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Colors from the face-lift mockups. Dark, neutral, with one highlight color chosen by the user. */
object LaunchColors {
    val Background = Color(0xFF0A0A0D)
    val Rail = Color(0xFF101014)
    val Panel = Color(0xFF15151A)
    val Raised = Color(0xFF1F1F26)
    val Track = Color(0xFF2A2A31)
    val Tile = Color(0xFF24242C)
    val Text = Color(0xFFF4F4F6)
    val TextSoft = Color(0xFFD4D4D8)
    val Muted = Color(0xFFA1A1AA)
    val Faint = Color(0xFF8A8A93)
    val Line = Color(0x14FFFFFF)
    val OnFocus = Color(0xFF0A0A0D)
    val OnFocusMuted = Color(0xFF3F3F46)
    val Good = Color(0xFF4ADE80)
    val Warn = Color(0xFFFBBF24)
    val WarnText = Color(0xFFFCD34D)
    val WarnBg = Color(0x1AFBBF24)
}

/** Highlight colors, named after Nuvio's own themes so they can match the Nuvio app. */
enum class Highlight(val label: String, val color: Color) {
    WHITE("White", Color(0xFFFFFFFF)),
    ARCTIC_BLUE("Arctic Blue", Color(0xFF4DE3FF)),
    OCEAN("Ocean", Color(0xFF42A5F5)),
    VIOLET("Violet", Color(0xFFAB47BC)),
    ROSE("Rose", Color(0xFFEC407A)),
    ROSE_GOLD("Rose Gold", Color(0xFFFFB37A)),
    CRIMSON("Crimson", Color(0xFFFF5252)),
    AMBER("Amber", Color(0xFFFFA726)),
    GOLD("Gold", Color(0xFFFFD45C)),
    EMERALD("Emerald", Color(0xFF66BB6A)),
    JADE("Jade", Color(0xFF7BF08D)),
    GRAPHITE("Graphite", Color(0xFFAAB2BE));

    companion object {
        fun fromName(name: String?): Highlight = entries.firstOrNull { it.name == name } ?: WHITE
    }
}

/** Focus grow / glow timing: a quick snap rather than a slow spring, so it keeps up with the remote. */
val FocusSnap = androidx.compose.animation.core.tween<Float>(durationMillis = 110)

val LocalHighlight = staticCompositionLocalOf { Highlight.WHITE.color }

/** Every screen is laid out on a 1280 × 720 grid (the mockups' size) and scaled to the TV. */
const val DESIGN_WIDTH = 1280f
const val DESIGN_HEIGHT = 720f

/** Pixels per design unit on this screen. Also used for overlay window sizes. */
fun designScale(context: android.content.Context): Float {
    val metrics = context.resources.displayMetrics
    val wide = maxOf(metrics.widthPixels, metrics.heightPixels)
    val tall = minOf(metrics.widthPixels, metrics.heightPixels)
    return minOf(wide / DESIGN_WIDTH, tall / DESIGN_HEIGHT).coerceAtLeast(0.5f)
}

@Composable
fun LaunchTheme(highlight: Color, content: @Composable () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    val density = remember(context) { androidx.compose.ui.unit.Density(designScale(context), fontScale.coerceIn(0.85f, 1.15f)) }
    CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides density) {
        LaunchMaterial(highlight, content)
    }
}

@Composable
private fun LaunchMaterial(highlight: Color, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = LaunchColors.Text,
            onPrimary = LaunchColors.OnFocus,
            background = LaunchColors.Background,
            surface = LaunchColors.Panel,
            onSurface = LaunchColors.Text
        )
    ) {
        CompositionLocalProvider(LocalHighlight provides highlight, content = content)
    }
}

/**
 * Click handling that works the same with a remote and a touch screen. OK/Enter runs [onClick]
 * when released; holding OK runs [onLongClick] once instead.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.tvClick(onClick: () -> Unit, onLongClick: (() -> Unit)? = null): Modifier = composed {
    val state = remember { PressState() }
    val latestClick by rememberUpdatedState(onClick)
    val latestLong by rememberUpdatedState(onLongClick)
    Modifier
        .onPreviewKeyEvent { event ->
            val code = event.nativeKeyEvent.keyCode
            val isOk = code == AndroidKeyEvent.KEYCODE_DPAD_CENTER || code == AndroidKeyEvent.KEYCODE_ENTER ||
                code == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER || code == AndroidKeyEvent.KEYCODE_BUTTON_A
            if (!isOk) return@onPreviewKeyEvent false
            when (event.type) {
                KeyEventType.KeyDown -> {
                    val long = latestLong
                    if (event.nativeKeyEvent.repeatCount == 0) {
                        state.downSeen = true
                        state.longFired = false
                    } else if (long != null && !state.longFired && event.nativeKeyEvent.repeatCount >= LONG_PRESS_REPEATS) {
                        state.longFired = true
                        long()
                    }
                    true
                }
                KeyEventType.KeyUp -> {
                    if (state.downSeen && !state.longFired) latestClick()
                    state.downSeen = false
                    state.longFired = false
                    true
                }
                else -> false
            }
        }
        .combinedClickable(onClick = { latestClick() }, onLongClick = latestLong?.let { long -> { long() } })
}

private class PressState {
    var downSeen = false
    var longFired = false
}

private const val LONG_PRESS_REPEATS = 8

/**
 * The basic focusable card from the mockups: dark panel normally, white with a soft ring and a
 * slight lift when the remote's focus is on it. [content] receives whether it is focused.
 */
@Composable
fun FocusCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    idle: Color = LaunchColors.Panel,
    scaleOnFocus: Float = 1.02f,
    border: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onFocused: (() -> Unit)? = null,
    onClick: () -> Unit,
    content: @Composable BoxScope.(focused: Boolean) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) scaleOnFocus else 1f, FocusSnap, label = "cardFocus")
    val ring = LocalHighlight.current
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged {
                if (it.isFocused && !focused) onFocused?.invoke()
                focused = it.isFocused
            }
            .then(if (focused) Modifier.shadow(18.dp, shape, clip = false) else Modifier)
            .then(if (focused) Modifier.border(3.dp, ring.copy(alpha = 0.55f), shape) else Modifier)
            .clip(shape)
            .background(if (focused) LaunchColors.Text else idle)
            .then(if (border && !focused) Modifier.border(1.dp, Color(0x14FFFFFF), shape) else Modifier)
            .tvClick(onClick, onLongClick)
    ) { content(focused) }
}

/** Round pill button used for Back / Continue and other actions. */
@Composable
fun PillButton(
    label: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    FocusCard(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        idle = if (primary) LaunchColors.Text else LaunchColors.Raised,
        scaleOnFocus = 1.04f,
        onClick = { if (enabled) onClick() }
    ) { focused ->
        Text(
            label,
            color = when {
                !enabled -> LaunchColors.Faint
                focused || primary -> LaunchColors.OnFocus
                else -> LaunchColors.Text
            },
            fontSize = 19.sp,
            fontWeight = if (primary || focused) FontWeight.Bold else FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 36.dp, vertical = 14.dp)
        )
    }
}

/** A settings row: title, optional subtitle, and a value on the right. */
@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    value: String? = null,
    valueColor: Color? = null,
    modifier: Modifier = Modifier,
    chevron: Boolean = true,
    trailing: (@Composable (focused: Boolean) -> Unit)? = null,
    onClick: () -> Unit
) {
    FocusCard(modifier = modifier.fillMaxWidth(), onClick = onClick) { focused ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = if (focused) LaunchColors.OnFocus else LaunchColors.Text, fontSize = 20.sp, fontWeight = if (focused) FontWeight.Bold else FontWeight.SemiBold)
                if (subtitle != null) Text(subtitle, color = if (focused) LaunchColors.OnFocusMuted else LaunchColors.Muted, fontSize = 15.sp)
            }
            if (trailing != null) trailing(focused)
            if (value != null) {
                Text(
                    value,
                    color = if (focused) LaunchColors.OnFocus else (valueColor ?: LaunchColors.TextSoft),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 16.dp)
                )
            }
            if (chevron) {
                Spacer(Modifier.width(10.dp))
                Chevron(if (focused) LaunchColors.OnFocus else LaunchColors.Muted)
            }
        }
    }
}

/** On/off pill drawn like the mockup switches. */
@Composable
fun TvSwitch(checked: Boolean, focused: Boolean) {
    val highlight = LocalHighlight.current
    val track = when {
        checked && focused -> LaunchColors.OnFocus
        checked -> highlight
        focused -> Color(0xFFDCDCE3)
        else -> Color(0xFF3F3F46)
    }
    val knob = when {
        checked && focused -> LaunchColors.Text
        checked -> LaunchColors.OnFocus
        else -> LaunchColors.Muted
    }
    Box(Modifier.padding(start = 16.dp).width(46.dp).height(26.dp).clip(CircleShape).background(track)) {
        Box(
            Modifier.align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(3.dp).size(20.dp).clip(CircleShape).background(knob)
        )
    }
}

@Composable
fun ToggleRow(title: String, subtitle: String? = null, checked: Boolean, modifier: Modifier = Modifier, onToggle: (Boolean) -> Unit) {
    SettingRow(title = title, subtitle = subtitle, chevron = false, modifier = modifier, trailing = { focused -> TvSwitch(checked, focused) }) {
        onToggle(!checked)
    }
}

@Composable
fun Chevron(color: Color, size: Dp = 20.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val path = Path().apply {
            moveTo(w * 0.38f, w * 0.25f)
            lineTo(w * 0.62f, w * 0.5f)
            lineTo(w * 0.38f, w * 0.75f)
        }
        drawPath(path, color, style = Stroke(width = w * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
fun CheckMark(color: Color, size: Dp = 20.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val path = Path().apply {
            moveTo(w * 0.2f, w * 0.52f)
            lineTo(w * 0.42f, w * 0.72f)
            lineTo(w * 0.8f, w * 0.3f)
        }
        drawPath(path, color, style = Stroke(width = w * 0.13f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/**
 * The in-app Nuvio Launch mark: a dark tile with a white "N" whose last stroke becomes an
 * arrow drawn in the highlight color.
 */
@Composable
fun LaunchMark(size: Dp = 36.dp, arrowColor: Color = LocalHighlight.current) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(LaunchColors.OnFocus)
            .border(1.dp, Color(0x29FFFFFF), RoundedCornerShape(size * 0.28f))
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // Same geometry as the launcher icon's vector (drawn on a 108-unit grid).
            val s = this.size.width / 108f
            fun p(x: Float, y: Float) = Offset((x * 0.72f + 15.8f) * s, (y * 0.72f + 17.3f) * s)
            val stroke = Stroke(width = 11f * 0.72f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val n = Path().apply {
                val a = p(28f, 78f); moveTo(a.x, a.y)
                val b = p(28f, 24f); lineTo(b.x, b.y)
                val c = p(66f, 72f); lineTo(c.x, c.y)
                val d = p(66f, 26f); lineTo(d.x, d.y)
            }
            drawPath(n, Color.White, style = stroke)
            val arrow = Path().apply {
                val a = p(54f, 37f); moveTo(a.x, a.y)
                val b = p(66f, 24f); lineTo(b.x, b.y)
                val c = p(78f, 37f); lineTo(c.x, c.y)
            }
            drawPath(arrow, arrowColor, style = stroke)
        }
    }
}

/** "OK select · Back previous step" hints at the bottom of pages. */
@Composable
fun KeyHints(vararg hints: Pair<String, String>, small: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (small) 6.dp else 10.dp)) {
        hints.forEachIndexed { index, (key, action) ->
            if (index > 0) Spacer(Modifier.width(if (small) 4.dp else 6.dp))
            Text(
                key,
                color = LaunchColors.TextSoft,
                fontSize = if (small) 11.sp else 14.sp,
                modifier = Modifier.border(1.dp, Color(0xFF3F3F46), RoundedCornerShape(if (small) 4.dp else 6.dp))
                    .padding(horizontal = if (small) 6.dp else 9.dp, vertical = if (small) 1.dp else 3.dp)
            )
            Text(action, color = LaunchColors.Faint, fontSize = if (small) 12.sp else 15.sp)
        }
    }
}

/** Small uppercase label in the highlight color ("YOUR NUVIO APP"). */
@Composable
fun Eyebrow(text: String) {
    Text(text.uppercase(), color = LocalHighlight.current, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
}

/** Info / warning note boxes. */
@Composable
fun NoteBox(text: String, warning: Boolean = false, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (warning) LaunchColors.WarnBg else LaunchColors.Panel)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (warning) LaunchColors.Warn else LaunchColors.Faint))
        Text(text, color = if (warning) LaunchColors.WarnText else LaunchColors.Muted, fontSize = 16.sp, lineHeight = 23.sp)
    }
}

/**
 * Shows an app's artwork. TV banners fill the 16:9 box; square icons (including icon pack
 * icons) sit centered on a dark tile so they never stretch.
 */
@Composable
fun AppArt(app: InstalledApp, modifier: Modifier, corner: Dp = 12.dp) {
    // Prepared images are kept, so reopening the drawer doesn't redo them. A missing one is
    // made in the background instead of holding up the remote.
    val artState = produceState(AppArtCache.cached(app), AppArtCache.keyFor(app)) {
        value = AppArtCache.cached(app) ?: withContext(Dispatchers.Default) { AppArtCache.prepare(app) }
    }
    val bitmap = artState.value
    Box(modifier.clip(RoundedCornerShape(corner)).background(if (app.isTvBanner) LaunchColors.Tile else Color(0xFF1C1C22)), contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(
                bitmap,
                app.label,
                if (app.isTvBanner) Modifier.fillMaxSize() else Modifier.fillMaxSize().padding(10.dp),
                contentScale = if (app.isTvBanner) ContentScale.Crop else ContentScale.Fit
            )
        } else {
            Text(app.label.take(1), color = LaunchColors.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * App artwork already turned into images, by app and icon. [AppCatalog.load] fills it in the
 * background so the drawer can draw every tile straight away.
 */
object AppArtCache {
    private val cache = object : android.util.LruCache<String, ImageBitmap>(400) {}

    fun keyFor(app: InstalledApp): String =
        if (app.artKey.isNotEmpty()) "${app.packageName}|${app.artKey}"
        else "${app.packageName}|id${System.identityHashCode(app.icon)}"

    fun cached(app: InstalledApp): ImageBitmap? = cache.get(keyFor(app))

    /** Makes (or returns) the image for [app]. Call off the main thread. */
    fun prepare(app: InstalledApp): ImageBitmap? {
        val key = keyFor(app)
        cache.get(key)?.let { return it }
        val made = synchronized(app.icon) { app.icon.toImageBitmapSafe(if (app.isTvBanner) 480 else 192) } ?: return null
        cache.put(key, made)
        return made
    }

    fun clear() = cache.evictAll()
}

/** Converts a drawable to a bitmap no larger than [maxSize] on its long side, keeping its shape. */
fun Drawable.toBitmapPreservingAspect(maxSize: Int): Bitmap {
    val sourceWidth = intrinsicWidth.takeIf { it > 0 } ?: maxSize
    val sourceHeight = intrinsicHeight.takeIf { it > 0 } ?: maxSize
    val scale = maxSize.toFloat() / maxOf(sourceWidth, sourceHeight)
    val width = (sourceWidth * scale).toInt().coerceAtLeast(1)
    val height = (sourceHeight * scale).toInt().coerceAtLeast(1)
    return toBitmap(width, height)
}

fun Drawable.toImageBitmapSafe(maxSize: Int): ImageBitmap? =
    runCatching { toBitmapPreservingAspect(maxSize).asImageBitmap() }.getOrNull()

/** Page background used by every full screen. */
fun Modifier.launchBackground(): Modifier = this.fillMaxSize().background(LaunchColors.Background)
