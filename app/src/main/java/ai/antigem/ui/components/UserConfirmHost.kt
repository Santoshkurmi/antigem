package ai.antigem.ui.components

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import ai.antigem.data.security.UserConfirm
import ai.antigem.i18n.L

/**
 * Shows the questions of [UserConfirm] (one at a time) on this screen. Put it once in every activity that can be in
 * front while an agent, Jarvis or a mini app works (MainActivity, MiniAppActivity).
 */
@Composable
fun UserConfirmHost() {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        var shown = false
        fun set(on: Boolean) { if (on != shown) { shown = on; UserConfirm.hostShown(on) } }
        val obs = LifecycleEventObserver { _, _ -> set(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs); set(false) }
    }
    val list by UserConfirm.requests.collectAsState()
    val r = list.firstOrNull() ?: return
    AlertDialog(
        onDismissRequest = { UserConfirm.answer(r, false) },
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { FilterObscuredTouches(); Text(r.title) },
        text = { Text(r.message, modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = {
            TextButton(onClick = { UserConfirm.answer(r, true) }) {
                // fixed icons: a language can't make the buttons look swapped
                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(androidx.compose.ui.unit.Dp(16f)))
                androidx.compose.foundation.layout.Spacer(Modifier.width(androidx.compose.ui.unit.Dp(4f)))
                Text(r.confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = { UserConfirm.answer(r, false) }) {
                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Outlined.Close, null, modifier = Modifier.size(androidx.compose.ui.unit.Dp(16f)))
                androidx.compose.foundation.layout.Spacer(Modifier.width(androidx.compose.ui.unit.Dp(4f)))
                Text(L.components.userConfirmHost.dontAllow)
            }
        }
    )
}
