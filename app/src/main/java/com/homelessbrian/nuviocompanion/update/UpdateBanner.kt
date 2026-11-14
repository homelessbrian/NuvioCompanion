package com.homelessbrian.nuviocompanion.update

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homelessbrian.nuviocompanion.FocusCard
import com.homelessbrian.nuviocompanion.LaunchColors
import com.homelessbrian.nuviocompanion.LocalHighlight

/**
 * "Update available" banner. Shows itself only when there's a newer Nuvio Launch on GitHub
 * (or an update is downloading / waiting), so it can be placed on any screen unconditionally.
 *
 * [onInstall] starts (or continues) the update; [onLater] hides it for a day.
 * [compact] is the slimmer version used inside the drawer; [stacked] puts the buttons under the
 * text, for the narrow side rail.
 */
@Composable
fun UpdateBanner(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    stacked: Boolean = false,
    onInstall: () -> Unit,
    onLater: () -> Unit = {},
    onOpenPermission: (() -> Unit)? = null
) {
    val context = LocalContext.current
    if (!AppUpdater.bannerVisible(context)) return
    val release = AppUpdater.available ?: return
    val status = AppUpdater.status
    val highlight = LocalHighlight.current

    // The first bullet of the release notes, as a one-line "what's new".
    val headline = release.notes.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith("• ") }
        ?.removePrefix("• ")

    val title: String
    val subtitle: String?
    var primaryLabel: String? = null
    var primaryAction: () -> Unit = onInstall
    var showLater = true
    var progress: Float? = null
    var showProgress = false

    when (status) {
        is AppUpdater.Status.Downloading -> {
            val percent = status.fraction?.let { " ${(it * 100).toInt()}%" }.orEmpty()
            title = "Downloading Nuvio Launch ${release.version}…$percent"
            subtitle = if (compact) null else "You can keep using your TV. Android will ask to install when it's done."
            progress = status.fraction
            showProgress = true
            showLater = false
        }
        AppUpdater.Status.Ready -> {
            title = "Nuvio Launch ${release.version} is ready to install"
            subtitle = if (compact) null else headline
            primaryLabel = "Install now"
        }
        AppUpdater.Status.NeedsPermission -> {
            title = "Allow Nuvio Launch to install its updates"
            subtitle = if (compact) null else "Turn on the switch for Nuvio Launch, then press Back. The update continues by itself."
            primaryLabel = "Open setting"
            primaryAction = onOpenPermission ?: onInstall
        }
        AppUpdater.Status.Installing -> {
            title = "Updating Nuvio Launch ${release.version}"
            subtitle = if (compact) null else "Choose Update (or Install) on the prompt Android shows."
            showLater = false
        }
        is AppUpdater.Status.Failed -> {
            title = "The update didn't finish"
            subtitle = if (compact) null else status.message
            primaryLabel = "Try again"
        }
        else -> {
            title = "Update available: Nuvio Launch ${release.version}"
            subtitle = if (compact) null else (headline ?: "A new version is ready to download.")
            primaryLabel = if (compact) "Install" else "Download & install"
        }
    }

    val shape = RoundedCornerShape(if (compact) 14.dp else 18.dp)
    val frame = modifier.fillMaxWidth()
        .clip(shape)
        .background(LaunchColors.Raised)
        .border(1.dp, highlight.copy(alpha = 0.45f), shape)
        .padding(start = if (compact) 14.dp else 20.dp, end = if (compact) 8.dp else 14.dp, top = if (compact) 8.dp else 14.dp, bottom = if (compact) 8.dp else 14.dp)

    val text: @Composable (Modifier) -> Unit = { textModifier ->
        Column(textModifier, verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp)) {
            Text(
                title,
                color = LaunchColors.Text,
                fontSize = if (compact) 14.sp else 19.sp,
                fontWeight = FontWeight.Bold,
                maxLines = if (compact) 2 else 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(subtitle, color = LaunchColors.Muted, fontSize = 15.sp, lineHeight = 21.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (showProgress) ProgressLine(progress, highlight, if (compact) 4.dp else 6.dp)
        }
    }
    val buttons: @Composable () -> Unit = {
        primaryLabel?.let { BannerButton(it, compact, primary = true, onClick = primaryAction) }
        if (showLater) BannerButton("Later", compact, primary = false, onClick = onLater)
    }
    val dot: @Composable () -> Unit = {
        Box(Modifier.size(if (compact) 10.dp else 12.dp).clip(CircleShape).background(highlight))
    }

    if (stacked) {
        Column(frame, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                dot()
                text(Modifier.weight(1f))
            }
            if (primaryLabel != null || showLater) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { buttons() }
            }
        }
    } else {
        Row(
            frame,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 16.dp)
        ) {
            dot()
            text(Modifier.weight(1f))
            buttons()
        }
    }
}

@Composable
private fun BannerButton(label: String, compact: Boolean, primary: Boolean, onClick: () -> Unit) {
    FocusCard(
        shape = RoundedCornerShape(999.dp),
        idle = if (primary) LaunchColors.Text else LaunchColors.Track,
        scaleOnFocus = 1.06f,
        onClick = onClick
    ) { focused ->
        Text(
            label,
            color = if (focused || primary) LaunchColors.OnFocus else LaunchColors.Text,
            fontSize = if (compact) 13.sp else 16.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = if (compact) 14.dp else 22.dp, vertical = if (compact) 6.dp else 10.dp)
        )
    }
}

/** Thin progress bar; with no known size it shows a full, dimmer bar. */
@Composable
private fun ProgressLine(fraction: Float?, color: Color, height: androidx.compose.ui.unit.Dp) {
    Box(Modifier.fillMaxWidth().height(height).clip(CircleShape).background(LaunchColors.Track)) {
        Box(
            Modifier.fillMaxHeight()
                .fillMaxWidth(fraction?.coerceIn(0.02f, 1f) ?: 1f)
                .clip(CircleShape)
                .background(if (fraction == null) color.copy(alpha = 0.4f) else color)
        )
    }
}

/** Full update details for Settings → Updates. */
@Composable
fun UpdateNotes(notes: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(LaunchColors.Panel).padding(22.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        notes.lines().forEach { line ->
            when {
                line.isBlank() -> Box(Modifier.height(4.dp).width(1.dp))
                line.startsWith("• ") -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("•", color = LaunchColors.Muted, fontSize = 16.sp)
                    Text(line.removePrefix("• "), color = LaunchColors.TextSoft, fontSize = 16.sp, lineHeight = 23.sp)
                }
                else -> Text(line, color = LaunchColors.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
