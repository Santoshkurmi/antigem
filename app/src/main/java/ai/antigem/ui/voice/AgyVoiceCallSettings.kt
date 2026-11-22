package ai.antigem.ui.voice

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BubbleChart
import androidx.compose.material.icons.outlined.HeadsetMic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import ai.antigem.data.voice.AgyVoicePreferences
import ai.antigem.data.voice.VoiceCallLauncher
import ai.antigem.ui.components.AppToastHelper
import ai.antigem.ui.components.ChatToastType

/**
 * Settings → Antigravity → Voice calls: calling from outside the app (the Voice call tile and the "Voice call" app
 * shortcut, both in the last chat that had a call) and the floating call window.
 */
@Composable
fun AgyVoiceCallSettingsCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    remember { AgyVoicePreferences.init(context) }
    val bubbleOn by AgyVoicePreferences.bubble.collectAsState()
    // "Display over other apps" can change in Android's settings: checked again whenever this page comes back
    var canDraw by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) canDraw = Settings.canDrawOverlays(context) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val openOverlaySettings = {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        Unit
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.HeadsetMic, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Call from anywhere", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "The Voice call tile in Quick Settings and the \"Voice call\" shortcut (long-press the AntiGem icon) call " +
                        "Antigravity in the last chat that had a call, or a new chat the first time. When AntiGem is " +
                        "running the call starts without opening it; otherwise the app opens and calls once the server is up. " +
                        "During a call, a tap on the tile ends it.",
                    fontSize = 12.5.sp, color = subtleText()
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (VoiceCallLauncher.canRequestTile) {
                        OutlinedButton(onClick = {
                            if (!VoiceCallLauncher.requestAddTile(context)) {
                                AppToastHelper.showToast("Add it by editing the Quick Settings tiles.", ChatToastType.INFO)
                            }
                        }) { Text("Add tile") }
                    }
                    OutlinedButton(onClick = {
                        if (!VoiceCallLauncher.pinShortcut(context)) {
                            AppToastHelper.showToast("This launcher can't add shortcuts. Long-press the AntiGem icon instead.", ChatToastType.INFO)
                        }
                    }) { Text("Add to home screen") }
                }
                if (!VoiceCallLauncher.canRequestTile) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "To add the tile: pull down Quick Settings, tap the pencil (edit) and drag \"Voice call\" up.",
                        fontSize = 12.sp, color = subtleText()
                    )
                }
            }
        }
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.BubbleChart, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Floating call window", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Switch(checked = bubbleOn, onCheckedChange = { on ->
                        AgyVoicePreferences.setBubble(on)
                        if (on && !canDraw) openOverlaySettings()
                    })
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "While a call runs and AntiGem is not on screen, the call shows over other apps: a panel on the lower half " +
                        "of the screen with the status (and when the agent is working), the voice's orb, live captions and the " +
                        "call's buttons. Minimize it (⌄ or drag it down) to a small circle you can drag anywhere; tap the circle " +
                        "to open the panel again. Calls from the tile or shortcut open as the panel; leaving the app during a " +
                        "call shows the circle.",
                    fontSize = 12.5.sp, color = subtleText()
                )
                if (bubbleOn && !canDraw) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Needs \"Display over other apps\" for AntiGem.",
                        fontSize = 12.5.sp, color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = openOverlaySettings) { Text("Allow") }
                }
            }
        }
    }
}

@Composable
private fun subtleText() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
