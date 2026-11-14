package com.homelessbrian.nuviocompanion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.homelessbrian.nuviocompanion.remote.RemoteKeyAccessibilityService

/** Listens for the remote-button service while a screen is showing. */
@Composable
fun ShortcutEvents(
    onRecorded: () -> Unit = {},
    onTriggered: () -> Unit = {},
    onRejected: (keyCode: Int) -> Unit = {}
) {
    val context = LocalContext.current
    val recorded by rememberUpdatedState(onRecorded)
    val triggered by rememberUpdatedState(onTriggered)
    val rejected by rememberUpdatedState(onRejected)
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    RemoteKeyAccessibilityService.ACTION_SHORTCUT_RECORDED -> recorded()
                    RemoteKeyAccessibilityService.ACTION_SHORTCUT_TRIGGERED -> triggered()
                    RemoteKeyAccessibilityService.ACTION_SHORTCUT_REJECTED ->
                        rejected(intent.getIntExtra(RemoteKeyAccessibilityService.EXTRA_KEY_CODE, 0))
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(RemoteKeyAccessibilityService.ACTION_SHORTCUT_RECORDED)
            addAction(RemoteKeyAccessibilityService.ACTION_SHORTCUT_TRIGGERED)
            addAction(RemoteKeyAccessibilityService.ACTION_SHORTCUT_REJECTED)
        }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") context.registerReceiver(receiver, filter)
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
            // Never leave the service stuck recording or testing after a screen closes.
            DrawerSettings.setRecording(context, false)
            DrawerSettings.setTestingShortcut(context, false)
        }
    }
}
