package com.homelessbrian.nuviocompanion

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.homelessbrian.nuviocompanion.remote.KeyNames
import com.homelessbrian.nuviocompanion.remote.RemoteKeyAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** First-run setup: one question per page, Back always goes one step back. */
class SetupActivity : ComponentActivity() {
    private var resumeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val startAt = intent.getStringExtra(EXTRA_STEP)?.let { name -> SetupStep.entries.firstOrNull { it.name == name } }
        setContent {
            LaunchTheme(DrawerSettings.highlight(this).color) {
                SetupFlow(
                    startAt = startAt ?: SetupStep.WELCOME,
                    resumeTick = resumeTick,
                    onExit = { finish() },
                    onOpenSettings = {
                        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_FROM_SETUP, true))
                        finish()
                    },
                    onOpenNuvio = {
                        val nuvio = DrawerSettings.selectedNuvio(this)
                        if (nuvio == null || !AppCatalog.launch(this, nuvio)) {
                            startActivity(Intent(this, MainActivity::class.java))
                        }
                        finish()
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        resumeTick++
    }

    companion object {
        const val EXTRA_STEP = "step"

        fun open(context: Context, step: SetupStep? = null) {
            val intent = Intent(context, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (step != null) intent.putExtra(EXTRA_STEP, step.name)
            runCatching { context.startActivity(intent) }
        }
    }
}

enum class SetupStep(val number: Int) { WELCOME(0), NUVIO(1), ACCESS(2), BUTTON(3), STYLE(4), SECOND(4), HOME(5), DRAWER(6), DONE(0) }

private const val TOTAL_STEPS = 6

@Composable
private fun SetupFlow(
    startAt: SetupStep,
    resumeTick: Int,
    onExit: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenNuvio: () -> Unit
) {
    val context = LocalContext.current
    var step by remember { mutableStateOf(startAt) }
    var history by remember { mutableStateOf(listOf<SetupStep>()) }

    fun go(next: SetupStep) {
        history = history + step
        step = next
    }
    fun back() {
        if (history.isEmpty()) onExit() else {
            step = history.last()
            history = history.dropLast(1)
        }
    }
    BackHandler(enabled = step != SetupStep.WELCOME || history.isNotEmpty()) { back() }

    when (step) {
        SetupStep.WELCOME -> WelcomePage(
            onStart = { go(SetupStep.NUVIO) },
            onSkip = {
                DrawerSettings.setSetupDone(context, true)
                onOpenSettings()
            }
        )
        SetupStep.NUVIO -> NuvioPage(onBack = ::back, onContinue = { go(SetupStep.ACCESS) })
        SetupStep.ACCESS -> AccessPage(resumeTick, onBack = ::back, onContinue = { go(SetupStep.BUTTON) })
        SetupStep.BUTTON -> RecordPage(
            target = "primary",
            onBack = ::back,
            onContinue = { go(SetupStep.STYLE) },
            onSkip = { go(SetupStep.HOME) }
        )
        SetupStep.STYLE -> StylePage(onBack = ::back, onPicked = { mode ->
            go(if (mode == ShortcutMode.COMBINATION) SetupStep.SECOND else SetupStep.HOME)
        })
        SetupStep.SECOND -> RecordPage(
            target = "secondary",
            onBack = ::back,
            onContinue = { go(SetupStep.HOME) },
            onSkip = {
                DrawerSettings.setShortcutMode(context, ShortcutMode.SINGLE)
                go(SetupStep.HOME)
            }
        )
        SetupStep.HOME -> HomePage(resumeTick, onBack = ::back, onContinue = { go(SetupStep.DRAWER) })
        SetupStep.DRAWER -> DrawerStylePage(onBack = ::back, onFinish = {
            DrawerSettings.setSetupDone(context, true)
            go(SetupStep.DONE)
        })
        SetupStep.DONE -> DonePage(onOpenNuvio = onOpenNuvio, onSettings = onOpenSettings)
    }
}

// ---------------------------------------------------------------------------------------------
// Page frame
// ---------------------------------------------------------------------------------------------

@Composable
private fun SetupFrame(
    step: SetupStep,
    optional: Boolean = false,
    footer: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val highlight = LocalHighlight.current
    Column(
        Modifier.launchBackground().padding(start = 72.dp, end = 72.dp, top = 40.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            LaunchMark(36.dp)
            Spacer(Modifier.width(12.dp))
            Text("Setup", color = LaunchColors.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                "Step ${step.number} of $TOTAL_STEPS" + if (optional) " · Optional" else "",
                color = LaunchColors.Muted,
                fontSize = 15.sp
            )
            Spacer(Modifier.width(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(TOTAL_STEPS) { index ->
                    Box(
                        Modifier.width(40.dp).height(5.dp).clip(RoundedCornerShape(3.dp))
                            .background(if (index < step.number) highlight else LaunchColors.Track)
                    )
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth(), content = content)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            KeyHints("OK" to "select", "Back" to "previous step")
            Spacer(Modifier.weight(1f))
            footer()
        }
    }
}

/** Left: eyebrow, title, text. Right: the page's controls. */
@Composable
private fun TwoColumns(
    left: @Composable ColumnScope.() -> Unit,
    right: @Composable ColumnScope.() -> Unit,
    leftWeight: Float = 5f,
    rightWeight: Float = 6f
) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(64.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(leftWeight), verticalArrangement = Arrangement.spacedBy(18.dp), content = left)
        Column(Modifier.weight(rightWeight), verticalArrangement = Arrangement.spacedBy(14.dp), content = right)
    }
}

@Composable
private fun PageTitle(text: String, size: Int = 44) {
    Text(text, color = LaunchColors.Text, fontSize = size.sp, fontWeight = FontWeight.Bold, lineHeight = (size * 1.12f).sp)
}

@Composable
private fun PageBody(text: String) {
    Text(text, color = LaunchColors.Muted, fontSize = 20.sp, lineHeight = 30.sp)
}

@Composable
private fun rememberInitialFocus(vararg keys: Any?): FocusRequester {
    val requester = remember { FocusRequester() }
    LaunchedEffect(*keys) {
        withFrameNanos { }
        runCatching { requester.requestFocus() }
    }
    return requester
}

// ---------------------------------------------------------------------------------------------
// Welcome
// ---------------------------------------------------------------------------------------------

@Composable
private fun WelcomePage(onStart: () -> Unit, onSkip: () -> Unit) {
    val focus = rememberInitialFocus(Unit)
    val highlight = LocalHighlight.current
    Column(
        Modifier.fillMaxSize()
            .background(Brush.radialGradient(listOf(Color(0xFF1A1B24), LaunchColors.Background), radius = 1400f))
            .padding(horizontal = 72.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(40.dp, Alignment.CenterVertically)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.dp)) {
            LaunchMark(88.dp)
            Text("Welcome to Nuvio Launch", color = LaunchColors.Text, fontSize = 52.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(
                "Open any app from anywhere with one button on your remote, and make Home take you straight to Nuvio. Setup takes about two minutes.",
                color = LaunchColors.Muted, fontSize = 22.sp, lineHeight = 33.sp, textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 760.dp)
            )
        }
        Row(Modifier.widthIn(max = 1040.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            listOf(
                "One-button app drawer" to "Pick a remote button. Press it in any app to bring up your apps.",
                "Home opens Nuvio" to "Optional. You can always back out to reach your settings.",
                "Your apps, your way" to "Hide apps, pin favorites, use icon packs, choose where the drawer sits."
            ).forEach { (title, body) ->
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(LaunchColors.Panel)
                        .border(1.dp, LaunchColors.Line, RoundedCornerShape(20.dp)).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(highlight))
                    Text(title, color = LaunchColors.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text(body, color = LaunchColors.Muted, fontSize = 16.sp, lineHeight = 23.sp)
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            PillButton("Get started", Modifier.focusRequester(focus), primary = true, onClick = onStart)
            FocusCard(shape = RoundedCornerShape(999.dp), idle = Color.Transparent, onClick = onSkip) { focused ->
                Text(
                    "Skip setup for now",
                    color = if (focused) LaunchColors.OnFocus else LaunchColors.Muted,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Step 1 · Choose Nuvio
// ---------------------------------------------------------------------------------------------

private data class AppChoice(val app: InstalledApp, val version: String?)

private fun loadChoices(context: Context, onlyNuvio: Boolean): List<AppChoice> {
    val pm = context.packageManager
    return AppCatalog.load(context, includeHidden = true)
        .filter { !onlyNuvio || it.label.contains("nuvio", true) || it.packageName.contains("nuvio", true) }
        .sortedBy { it.label.lowercase() }
        .map { app ->
            val version = runCatching { pm.getPackageInfo(app.packageName, 0).versionName }.getOrNull()
            AppChoice(app, version)
        }
}

@Composable
private fun NuvioPage(onBack: () -> Unit, onContinue: () -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(DrawerSettings.selectedNuvio(context)) }
    var browsingAll by remember { mutableStateOf(false) }
    val nuvioApps by produceState<List<AppChoice>?>(null) {
        value = withContext(Dispatchers.IO) { loadChoices(context, onlyNuvio = true) }
    }
    val allApps by produceState<List<AppChoice>?>(null, browsingAll) {
        if (browsingAll) value = withContext(Dispatchers.IO) { loadChoices(context, onlyNuvio = false) }
    }
    // Pre-select the first Nuvio found so Continue just works.
    LaunchedEffect(nuvioApps) {
        val pick = nuvioApps?.firstOrNull()?.app?.packageName
        if (selected == null && pick != null) {
            selected = pick
            DrawerSettings.setSelectedNuvio(context, pick)
        }
    }
    BackHandler(enabled = browsingAll) { browsingAll = false }
    val firstFocus = rememberInitialFocus(nuvioApps, browsingAll, allApps)

    fun pick(pkg: String) {
        selected = pkg
        DrawerSettings.setSelectedNuvio(context, pkg)
        browsingAll = false
    }

    SetupFrame(SetupStep.NUVIO, footer = {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            PillButton("Back", onClick = { if (browsingAll) browsingAll = false else onBack() })
            PillButton("Continue", onClick = onContinue)
        }
    }) {
        TwoColumns(
            left = {
                Eyebrow("Your Nuvio app")
                PageTitle("Which Nuvio should Nuvio Launch open?")
                PageBody("We found these on your TV. This is the app Home opens, and it's pinned first in your drawer. You can change it later in Settings.")
            },
            right = {
                val list = if (browsingAll) allApps else nuvioApps
                when {
                    list == null -> Text("Looking for apps…", color = LaunchColors.Muted, fontSize = 18.sp)
                    else -> LazyColumn(
                        Modifier.fillMaxWidth().height(440.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = PaddingValues(vertical = 10.dp, horizontal = 8.dp)
                    ) {
                        if (!browsingAll && list.isEmpty()) {
                            item { NoteBox("No Nuvio app found on this TV. Install Nuvio, or pick any other app below.", warning = true) }
                        }
                        items(list, key = { it.app.packageName }) { choice ->
                            AppChoiceRow(
                                choice,
                                selected = choice.app.packageName == selected,
                                modifier = if (choice == list.first()) Modifier.focusRequester(firstFocus) else Modifier,
                                onClick = { pick(choice.app.packageName) }
                            )
                        }
                        if (!browsingAll) item {
                            FocusCard(
                                modifier = Modifier.fillMaxWidth().then(if (list.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier),
                                shape = RoundedCornerShape(18.dp),
                                idle = Color.Transparent,
                                border = true,
                                onClick = { browsingAll = true }
                            ) { focused ->
                                Row(Modifier.padding(horizontal = 22.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.width(112.dp).height(63.dp).clip(RoundedCornerShape(10.dp)).background(if (focused) Color(0xFFDCDCE3) else LaunchColors.Panel), contentAlignment = Alignment.Center) {
                                        Text("+", color = if (focused) LaunchColors.OnFocus else LaunchColors.Muted, fontSize = 30.sp)
                                    }
                                    Spacer(Modifier.width(20.dp))
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("Choose a different app", color = if (focused) LaunchColors.OnFocus else LaunchColors.Text, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                                        Text("Pick any installed app to open from Home", color = if (focused) LaunchColors.OnFocusMuted else LaunchColors.Muted, fontSize = 15.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        )
    }
}

@Composable
private fun AppChoiceRow(choice: AppChoice, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    FocusCard(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), border = true, scaleOnFocus = 1.03f, onClick = onClick) { focused ->
        Row(Modifier.padding(horizontal = 22.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            AppArt(choice.app, Modifier.width(112.dp).height(63.dp), corner = 10.dp)
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(choice.app.label, color = if (focused) LaunchColors.OnFocus else LaunchColors.Text, fontSize = 21.sp, fontWeight = if (focused) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    choice.app.packageName + (choice.version?.let { " · Version $it" } ?: ""),
                    color = if (focused) LaunchColors.OnFocusMuted else LaunchColors.Muted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            RadioDot(selected, focused)
        }
    }
}

@Composable
fun RadioDot(selected: Boolean, focused: Boolean) {
    val ink = if (focused) LaunchColors.OnFocus else LaunchColors.Text
    if (selected) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(ink), contentAlignment = Alignment.Center) {
            CheckMark(if (focused) LaunchColors.Text else LaunchColors.OnFocus, 18.dp)
        }
    } else {
        Box(Modifier.size(30.dp).border(2.dp, Color(0xFF52525B), CircleShape))
    }
}

// ---------------------------------------------------------------------------------------------
// Step 2 · Remote access
// ---------------------------------------------------------------------------------------------

@Composable
private fun AccessPage(resumeTick: Int, onBack: () -> Unit, onContinue: () -> Unit) {
    val context = LocalContext.current
    val startedEnabled = remember { RemoteKeyAccessibilityService.isEnabled(context) }
    var enabled by remember { mutableStateOf(startedEnabled) }
    val focus = rememberInitialFocus(Unit)

    // Check every second; move on by ourselves the moment it's turned on.
    LaunchedEffect(resumeTick) {
        while (true) {
            val now = RemoteKeyAccessibilityService.isEnabled(context)
            if (now && !enabled && !startedEnabled) {
                enabled = true
                delay(700)
                onContinue()
                return@LaunchedEffect
            }
            enabled = now
            delay(1000)
        }
    }

    SetupFrame(SetupStep.ACCESS, footer = {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            PillButton("Back", onClick = onBack)
            PillButton(if (enabled) "Continue" else "Skip, set up later", onClick = onContinue)
        }
    }) {
        TwoColumns(
            left = {
                Eyebrow("Remote access")
                PageTitle("Let Nuvio Launch hear your remote")
                PageBody("Android needs your OK before an app can react to remote buttons inside other apps. Nuvio Launch only acts on the one button you choose next.")
                NoteBox("Android will show a standard warning. Nuvio Launch can't see your screen and doesn't collect anything.")
            },
            right = {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(LaunchColors.Panel)
                        .border(1.dp, LaunchColors.Line, RoundedCornerShape(24.dp)).padding(32.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp)
                ) {
                    listOf(
                        "Select Open Accessibility below",
                        "Choose Nuvio Launch Remote Shortcuts",
                        "Turn it on, then press Back to return here"
                    ).forEachIndexed { index, line ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Box(Modifier.size(34.dp).clip(CircleShape).background(LaunchColors.Raised), contentAlignment = Alignment.Center) {
                                Text("${index + 1}", color = LaunchColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            }
                            Text(line, color = LaunchColors.Text, fontSize = 19.sp)
                        }
                    }
                    FocusCard(
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        idle = LaunchColors.Text,
                        onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } }
                    ) {
                        Text(
                            "Open Accessibility  ↗",
                            color = LaunchColors.OnFocus, fontSize = 21.sp, fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(20.dp)
                        )
                    }
                    StatusLine(
                        if (enabled) "On. You're good to go." else "Not on yet. We'll move on by ourselves once it is.",
                        good = enabled
                    )
                }
            }
        )
    }
}

@Composable
fun StatusLine(text: String, good: Boolean) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (good) Color(0x1A4ADE80) else LaunchColors.WarnBg)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (good) LaunchColors.Good else LaunchColors.Warn))
        Text(text, color = if (good) LaunchColors.Good else LaunchColors.WarnText, fontSize = 17.sp)
    }
}

// ---------------------------------------------------------------------------------------------
// Step 3 · Pick your button (also used for the second button of a two-button shortcut)
// ---------------------------------------------------------------------------------------------

enum class RecordState { WAITING, GOT_IT, TIMED_OUT, NO_ACCESS }

/**
 * Records one remote button. Shared by setup and Settings. Waits up to 10 seconds; ignores
 * Back, Home, OK and the arrows (the remote needs them).
 */
@Composable
fun ButtonRecorder(
    target: String,
    onUse: () -> Unit,
    useLabel: (String) -> String = { "Use $it" },
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var state by remember {
        mutableStateOf(if (RemoteKeyAccessibilityService.isEnabled(context)) RecordState.WAITING else RecordState.NO_ACCESS)
    }
    var attempt by remember { mutableIntStateOf(0) }
    var rejected by remember { mutableStateOf<String?>(null) }
    val keyCode = if (target == "secondary") DrawerSettings.secondKeyCode(context) else DrawerSettings.keyCode(context)
    var recordedName by remember { mutableStateOf(KeyNames.name(keyCode)) }

    ShortcutEvents(
        onRecorded = {
            val code = if (target == "secondary") DrawerSettings.secondKeyCode(context) else DrawerSettings.keyCode(context)
            recordedName = KeyNames.name(code)
            rejected = null
            state = RecordState.GOT_IT
        },
        onRejected = { code -> rejected = KeyNames.name(code) }
    )

    LaunchedEffect(state, attempt) {
        if (state == RecordState.WAITING) {
            DrawerSettings.setRecordingTarget(context, target)
            DrawerSettings.setRecording(context, true)
            delay(10_000)
            if (state == RecordState.WAITING) {
                DrawerSettings.setRecording(context, false)
                state = RecordState.TIMED_OUT
            }
        }
    }
    DisposableEffect(Unit) { onDispose { DrawerSettings.setRecording(context, false) } }

    val useFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    LaunchedEffect(state) {
        withFrameNanos { }
        runCatching {
            when (state) {
                RecordState.GOT_IT -> useFocus.requestFocus()
                RecordState.TIMED_OUT, RecordState.NO_ACCESS -> retryFocus.requestFocus()
                RecordState.WAITING -> Unit
            }
        }
    }

    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(LaunchColors.Panel)
            .border(1.dp, LaunchColors.Line, RoundedCornerShape(24.dp)).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        when (state) {
            RecordState.WAITING -> {
                val pulse = rememberInfiniteTransition(label = "pulse")
                val ring by pulse.animateFloat(0.85f, 1.15f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "ring")
                Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(150.dp).scale(ring).clip(CircleShape).background(LocalHighlight.current.copy(alpha = 0.12f)))
                    Box(Modifier.size(96.dp).clip(CircleShape).background(LaunchColors.Raised).border(3.dp, LocalHighlight.current, CircleShape))
                }
                Text("Press a button now…", color = LaunchColors.Text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(
                    rejected?.let { "$it is needed by your remote. Try a different button." } ?: "Waiting for up to 10 seconds",
                    color = if (rejected != null) LaunchColors.WarnText else LaunchColors.Faint,
                    fontSize = 16.sp
                )
            }
            RecordState.GOT_IT -> {
                Box(Modifier.size(150.dp).clip(CircleShape).background(Color(0x1A4ADE80)), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(96.dp).clip(CircleShape).background(LaunchColors.Good), contentAlignment = Alignment.Center) {
                        CheckMark(LaunchColors.OnFocus, 46.dp)
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Got it. Your button is", color = LaunchColors.Muted, fontSize = 17.sp)
                    Text(recordedName, color = LaunchColors.Text, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    WideButton("Try another button", Modifier.weight(1f), primary = false) {
                        attempt++
                        state = RecordState.WAITING
                    }
                    WideButton(useLabel(recordedName), Modifier.weight(1f).focusRequester(useFocus), primary = true, onClick = onUse)
                }
            }
            RecordState.TIMED_OUT -> {
                Text("Didn't catch a button", color = LaunchColors.Text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Some TVs keep certain buttons for themselves. Try a color button, Bookmark, Info, or a spare app button.",
                    color = LaunchColors.Muted, fontSize = 17.sp, lineHeight = 25.sp, textAlign = TextAlign.Center
                )
                WideButton("Try again", Modifier.fillMaxWidth().focusRequester(retryFocus), primary = true) {
                    attempt++
                    state = RecordState.WAITING
                }
            }
            RecordState.NO_ACCESS -> {
                Text("Remote access is off", color = LaunchColors.Text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Turn on Nuvio Launch Remote Shortcuts in Accessibility first, then come back here.",
                    color = LaunchColors.Muted, fontSize = 17.sp, lineHeight = 25.sp, textAlign = TextAlign.Center
                )
                WideButton("Open Accessibility  ↗", Modifier.fillMaxWidth().focusRequester(retryFocus), primary = true) {
                    runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                }
                WideButton("I turned it on", Modifier.fillMaxWidth(), primary = false) {
                    if (RemoteKeyAccessibilityService.isEnabled(context)) state = RecordState.WAITING
                }
            }
        }
    }
}

@Composable
fun WideButton(label: String, modifier: Modifier, primary: Boolean, onClick: () -> Unit) {
    FocusCard(modifier = modifier, idle = if (primary) LaunchColors.Text else LaunchColors.Raised, onClick = onClick) { focused ->
        Text(
            label,
            color = if (focused || primary) LaunchColors.OnFocus else LaunchColors.Text,
            fontSize = 19.sp,
            fontWeight = if (primary) FontWeight.Bold else FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(18.dp)
        )
    }
}

@Composable
private fun RecordPage(target: String, onBack: () -> Unit, onContinue: () -> Unit, onSkip: () -> Unit) {
    val context = LocalContext.current
    val primaryName = KeyNames.name(DrawerSettings.keyCode(context))
    SetupFrame(if (target == "secondary") SetupStep.SECOND else SetupStep.BUTTON, footer = {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            PillButton("Back", onClick = onBack)
            PillButton("Skip", onClick = onSkip)
        }
    }) {
        TwoColumns(
            left = {
                Eyebrow(if (target == "secondary") "Second button" else "Your button")
                PageTitle(if (target == "secondary") "Now press the second button" else "Press the button that opens your apps")
                PageBody(
                    if (target == "secondary") "You'll hold $primaryName, then press this one. Pick a button that's easy to reach together."
                    else "Pick one you don't use much. Good choices are the color buttons, Bookmark, Info, or a spare app button."
                )
                NoteBox("Back, Home, OK and the arrow buttons can't be used. Your remote needs them.")
            },
            right = { ButtonRecorder(target = target, onUse = onContinue) }
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Step 4 · Press style
// ---------------------------------------------------------------------------------------------

@Composable
private fun StylePage(onBack: () -> Unit, onPicked: (ShortcutMode) -> Unit) {
    val context = LocalContext.current
    val buttonName = KeyNames.name(DrawerSettings.keyCode(context)).let { if (it == "Not set") "your button" else it }
    val current = remember { DrawerSettings.shortcutMode(context) }
    val focus = rememberInitialFocus(Unit)
    SetupFrame(SetupStep.STYLE, footer = { PillButton("Back", onClick = onBack) }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Eyebrow("Press style")
            PageTitle("How should $buttonName open the drawer?", 40)
        }
        Spacer(Modifier.height(28.dp))
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            val options = listOf(
                ShortcutMode.SINGLE to "Fastest. $buttonName only opens the drawer from now on.",
                ShortcutMode.DOUBLE to "Press twice quickly. A single press does nothing.",
                ShortcutMode.LONG to "Hold for about half a second. Harder to trigger by accident.",
                ShortcutMode.COMBINATION to "Hold $buttonName, then press a second button. We'll ask for it next."
            )
            options.forEach { (mode, body) ->
                FocusCard(
                    modifier = Modifier.weight(1f).fillMaxHeight().then(if (mode == current) Modifier.focusRequester(focus) else Modifier),
                    shape = RoundedCornerShape(22.dp),
                    border = true,
                    scaleOnFocus = 1.03f,
                    onClick = {
                        DrawerSettings.setShortcutMode(context, mode)
                        onPicked(mode)
                    }
                ) { focused ->
                    val ink = if (focused) LaunchColors.OnFocus else LaunchColors.Text
                    Column(Modifier.fillMaxSize().padding(26.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        PressGlyph(mode, ink)
                        Text(mode.label, color = ink, fontSize = 23.sp, fontWeight = if (focused) FontWeight.Bold else FontWeight.SemiBold)
                        Text(body, color = if (focused) LaunchColors.OnFocusMuted else LaunchColors.Muted, fontSize = 16.sp, lineHeight = 23.sp)
                        if (mode == ShortcutMode.SINGLE) {
                            Spacer(Modifier.weight(1f))
                            Text(
                                "Recommended",
                                color = if (focused) LaunchColors.Text else LaunchColors.OnFocus,
                                fontSize = 14.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (focused) LaunchColors.OnFocus else LaunchColors.Text).padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        NoteBox("Whatever you pick, $buttonName stops doing its usual job. Android doesn't let apps share a button.")
    }
}

@Composable
private fun PressGlyph(mode: ShortcutMode, ink: Color) {
    val highlight = LocalHighlight.current
    Row(Modifier.height(40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (mode) {
            ShortcutMode.SINGLE -> Box(Modifier.size(30.dp).clip(CircleShape).background(ink))
            ShortcutMode.DOUBLE -> repeat(2) { Box(Modifier.size(30.dp).clip(CircleShape).background(ink)) }
            ShortcutMode.LONG -> Box(Modifier.width(86.dp).height(30.dp).clip(CircleShape).background(ink))
            ShortcutMode.COMBINATION -> {
                Box(Modifier.size(30.dp).clip(CircleShape).background(ink))
                Text("+", color = LaunchColors.Muted, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Box(Modifier.size(30.dp).clip(CircleShape).background(highlight))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Step 5 · Home button
// ---------------------------------------------------------------------------------------------

@Composable
private fun HomePage(resumeTick: Int, onBack: () -> Unit, onContinue: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    var asked by remember { mutableStateOf(DrawerSettings.homeTestPending(context) || DrawerSettings.homeTestPassed(context)) }
    var passed by remember { mutableStateOf(DrawerSettings.homeTestPassed(context)) }
    val focus = rememberInitialFocus(asked, passed)
    LaunchedEffect(resumeTick) {
        passed = DrawerSettings.homeTestPassed(context)
        if (passed) asked = true
    }

    SetupFrame(SetupStep.HOME, optional = true, footer = {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            PillButton("Back", onClick = {
                DrawerSettings.setHomeTestPending(context, false)
                onBack()
            })
            if (asked) PillButton(if (passed) "Continue" else "Continue without testing", onClick = {
                DrawerSettings.setHomeTestPending(context, false)
                onContinue()
            })
        }
    }) {
        TwoColumns(
            left = {
                Eyebrow("Home button")
                PageTitle("Should Home take you to Nuvio?")
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(LaunchColors.Panel).padding(horizontal = 22.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("You can never get stuck", color = LaunchColors.Good, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    listOf(
                        "Back out of Nuvio and you land on the Nuvio Launch home screen",
                        "Your drawer always ends with Android Settings",
                        "If Nuvio keeps closing, Home pauses by itself"
                    ).forEach { line ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("•", color = LaunchColors.Good, fontSize = 16.sp)
                            Text(line, color = LaunchColors.TextSoft, fontSize = 16.sp, lineHeight = 22.sp)
                        }
                    }
                }
            },
            right = {
                if (!asked) {
                    HomeChoice(
                        title = "Yes, Home opens Nuvio",
                        body = "Android will ask you to choose a Home app. Pick Nuvio Launch.",
                        modifier = Modifier.focusRequester(focus),
                        onClick = {
                            DrawerSettings.setHomeRedirectEnabled(context, true)
                            DrawerSettings.setHomeRedirectPaused(context, false)
                            DrawerSettings.setHomeTestPassed(context, false)
                            DrawerSettings.setHomeTestPending(context, true)
                            asked = true
                            activity?.let { HomeSetup.openHomePicker(it) }
                        }
                    )
                    HomeChoice(
                        title = "No, keep my current Home",
                        body = "The drawer button still works everywhere.",
                        onClick = {
                            DrawerSettings.setHomeRedirectEnabled(context, false)
                            DrawerSettings.setHomeTestPending(context, false)
                            DrawerSettings.setHomeTestPassed(context, false)
                            onContinue()
                        }
                    )
                    NoteBox("Some Google TV devices keep Home for Google TV. After you pick, press Home once and we'll tell you whether it worked.", warning = true)
                } else if (passed) {
                    StatusLine("Home works. It will open your Nuvio app.", good = true)
                    WideButton("Continue", Modifier.fillMaxWidth().focusRequester(focus), primary = true) {
                        DrawerSettings.setHomeTestPending(context, false)
                        onContinue()
                    }
                } else {
                    Text("Now press Home on your remote", color = LaunchColors.Text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "If Nuvio Launch is your Home app, you'll come right back here with a check mark. If you end up on Google TV's home screen instead, your TV keeps Home for itself. Come back to Nuvio Launch and continue: the drawer button still works.",
                        color = LaunchColors.Muted, fontSize = 18.sp, lineHeight = 27.sp
                    )
                    WideButton("Show the Home app list again", Modifier.fillMaxWidth().focusRequester(focus), primary = false) {
                        activity?.let { HomeSetup.openHomePicker(it) }
                    }
                    StatusLine("Waiting for a Home press", good = false)
                }
            }
        )
    }
}

@Composable
private fun HomeChoice(title: String, body: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusCard(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), border = true, scaleOnFocus = 1.03f, onClick = onClick) { focused ->
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = if (focused) LaunchColors.OnFocus else LaunchColors.Text, fontSize = 22.sp, fontWeight = if (focused) FontWeight.Bold else FontWeight.SemiBold)
            Text(body, color = if (focused) LaunchColors.OnFocusMuted else LaunchColors.Muted, fontSize = 16.sp, lineHeight = 22.sp)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Step 6 · Drawer style
// ---------------------------------------------------------------------------------------------

@Composable
private fun DrawerStylePage(onBack: () -> Unit, onFinish: () -> Unit) {
    val context = LocalContext.current
    var position by remember { mutableStateOf(DrawerSettings.position(context)) }
    var showNames by remember { mutableStateOf(DrawerSettings.showNames(context)) }
    var blur by remember { mutableStateOf(DrawerSettings.blurBehind(context)) }
    val focus = rememberInitialFocus(Unit)
    val options = listOf(
        DrawerPosition.BOTTOM to "Bottom dock",
        DrawerPosition.RIGHT to "Side rail",
        DrawerPosition.CENTER to "Center grid",
        DrawerPosition.TOP to "Top bar"
    )
    SetupFrame(SetupStep.DRAWER, footer = {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            PillButton("Back", onClick = onBack)
            PillButton("Finish", onClick = onFinish)
        }
    }) {
        TwoColumns(
            leftWeight = 4f,
            rightWeight = 7f,
            left = {
                Eyebrow("Drawer style")
                PageTitle("Where should your apps appear?", 40)
                options.forEach { (option, label) ->
                    val chosen = position == option || (option == DrawerPosition.RIGHT && position == DrawerPosition.LEFT)
                    FocusCard(
                        modifier = Modifier.fillMaxWidth().then(if (chosen) Modifier.focusRequester(focus) else Modifier),
                        onFocused = {
                            if (!(option == DrawerPosition.RIGHT && position == DrawerPosition.LEFT)) {
                                position = option
                                DrawerSettings.setPosition(context, option)
                            }
                        },
                        onClick = {
                            position = option
                            DrawerSettings.setPosition(context, option)
                        }
                    ) { focused ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(label, color = if (focused) LaunchColors.OnFocus else LaunchColors.Text, fontSize = 20.sp, fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium, modifier = Modifier.weight(1f))
                            if (chosen) CheckMark(if (focused) LaunchColors.OnFocus else LaunchColors.Text, 22.dp)
                        }
                    }
                }
                Text("The preview changes as you move. Fine-tune names, size and look later in Settings.", color = LaunchColors.Faint, fontSize = 15.sp)
            },
            right = {
                DrawerPreview(position, showNames)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ToggleRow("Show app names", checked = showNames, modifier = Modifier.weight(1f)) {
                        showNames = it
                        DrawerSettings.setShowNames(context, it)
                    }
                    ToggleRow("Blur behind", checked = blur, modifier = Modifier.weight(1f)) {
                        blur = it
                        DrawerSettings.setBlurBehind(context, it)
                    }
                }
            }
        )
    }
}

/** A small picture of the drawer sitting on top of Nuvio, for setup and Settings. */
@Composable
fun DrawerPreview(position: DrawerPosition, showNames: Boolean, modifier: Modifier = Modifier) {
    val highlight = LocalHighlight.current
    val tileColors = listOf(LaunchColors.Text, Color(0xFFC2402F), Color(0xFF2A6FDB), Color(0xFF1E8F5A), Color(0xFF5A3FD1))
    BoxWithConstraints(
        modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF2A2236), Color(0xFF13131A), LaunchColors.Background)))
            .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(18.dp))
    ) {
        val w = maxWidth
        val h = maxHeight
        // Pretend Nuvio screen behind the drawer.
        Column(Modifier.padding(start = w * 0.05f, top = h * 0.1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.width(w * 0.3f).height(18.dp).clip(RoundedCornerShape(5.dp)).background(Color(0x80FFFFFF)))
            Box(Modifier.width(w * 0.4f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0x4DFFFFFF)))
            Box(Modifier.width(w * 0.34f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0x4DFFFFFF)))
            Spacer(Modifier.height(h * 0.08f))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(0xFF3A3550, 0xFF2E3A4E, 0xFF4A3A3A, 0xFF2E4A40, 0xFF3E3E48).forEach {
                    Box(Modifier.width(w * 0.16f).height(h * 0.28f).clip(RoundedCornerShape(8.dp)).background(Color(it).copy(alpha = 0.5f)))
                }
            }
        }
        Box(Modifier.fillMaxSize().background(Color(0x59000000)))

        val tile: @Composable (Int, Boolean) -> Unit = { index, wide ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Box(
                    Modifier.width(if (wide) w * 0.11f else w * 0.06f).height(w * 0.062f).clip(RoundedCornerShape(7.dp))
                        .background(tileColors[index % tileColors.size])
                        .then(if (index == 0) Modifier.border(2.dp, highlight, RoundedCornerShape(7.dp)) else Modifier)
                )
                if (showNames) Box(Modifier.width(w * 0.06f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x66FFFFFF)))
            }
        }
        val panel = Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xDB18181E)).border(1.dp, Color(0x24FFFFFF), RoundedCornerShape(16.dp))
        when (position) {
            DrawerPosition.BOTTOM, DrawerPosition.TOP -> Row(
                Modifier.align(if (position == DrawerPosition.BOTTOM) Alignment.BottomCenter else Alignment.TopCenter)
                    .padding(vertical = h * 0.05f).then(panel).padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) { repeat(5) { tile(it, true) } }
            DrawerPosition.LEFT, DrawerPosition.RIGHT -> Column(
                Modifier.align(if (position == DrawerPosition.RIGHT) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(horizontal = w * 0.03f).fillMaxHeight(0.88f).width(w * 0.24f).then(panel).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                repeat(5) { index ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        tile(index, true)
                        Box(Modifier.width(w * 0.07f).height(5.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x66FFFFFF)))
                    }
                }
            }
            DrawerPosition.CENTER -> Column(
                Modifier.align(Alignment.Center).then(panel).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                repeat(2) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { repeat(5) { tile(row * 5 + it, true) } }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// All set
// ---------------------------------------------------------------------------------------------

@Composable
private fun DonePage(onOpenNuvio: () -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val focus = rememberInitialFocus(Unit)
    val buttonName = KeyNames.name(DrawerSettings.keyCode(context))
    val mode = DrawerSettings.shortcutMode(context)
    val nuvio = DrawerSettings.selectedNuvio(context)?.let { AppCatalog.label(context, it) } ?: "Not chosen"
    val access = RemoteKeyAccessibilityService.isEnabled(context)
    val homeValue = when {
        !DrawerSettings.homeRedirectEnabled(context) -> "Not used"
        DrawerSettings.homeTestPassed(context) -> "Opens Nuvio · tested"
        else -> "Opens Nuvio"
    }
    val buttonValue = when {
        DrawerSettings.keyCode(context) == 0 -> "Not set"
        mode == ShortcutMode.COMBINATION -> "$buttonName + ${KeyNames.name(DrawerSettings.secondKeyCode(context))}"
        else -> "$buttonName · ${mode.label.lowercase()}"
    }
    Row(
        Modifier.fillMaxSize()
            .background(Brush.radialGradient(listOf(Color(0xFF15241C), LaunchColors.Background), radius = 1400f))
            .padding(horizontal = 72.dp, vertical = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(64.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(5f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Box(Modifier.size(84.dp).clip(CircleShape).background(LaunchColors.Good), contentAlignment = Alignment.Center) {
                CheckMark(LaunchColors.OnFocus, 42.dp)
            }
            Text("You're all set", color = LaunchColors.Text, fontSize = 52.sp, fontWeight = FontWeight.Bold)
            Text(
                if (DrawerSettings.keyCode(context) != 0 && access) "Try it now: press $buttonName on your remote to open your apps from right here."
                else "You can finish the remote button any time in Settings → Remote shortcut.",
                color = LaunchColors.Muted, fontSize = 21.sp, lineHeight = 31.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 10.dp)) {
                PillButton("Open Nuvio", Modifier.focusRequester(focus), primary = true, onClick = onOpenNuvio)
                PillButton("Settings", onClick = onSettings)
            }
        }
        Column(
            Modifier.weight(6f).clip(RoundedCornerShape(24.dp)).background(LaunchColors.Panel)
                .border(1.dp, LaunchColors.Line, RoundedCornerShape(24.dp)).padding(horizontal = 28.dp, vertical = 10.dp)
        ) {
            val rows = listOf(
                Triple("Nuvio app", nuvio, null),
                Triple("Drawer button", buttonValue, null),
                Triple("Remote access", if (access) "On" else "Off", access),
                Triple("Home button", homeValue, if (DrawerSettings.homeTestPassed(context)) true else null),
                Triple("Drawer", DrawerSettings.position(context).label, null)
            )
            rows.forEachIndexed { index, (label, value, good) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, color = LaunchColors.Muted, fontSize = 18.sp, modifier = Modifier.weight(1f))
                    Text(
                        value,
                        color = when (good) { true -> LaunchColors.Good; false -> LaunchColors.WarnText; null -> LaunchColors.Text },
                        fontSize = 19.sp, fontWeight = FontWeight.SemiBold
                    )
                }
                if (index < rows.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(LaunchColors.Line))
            }
        }
    }
}
