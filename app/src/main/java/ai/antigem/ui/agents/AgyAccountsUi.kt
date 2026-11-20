package ai.antigem.ui.agents

import ai.antigem.data.remote.AgyAccounts
import ai.antigem.data.remote.AgyHubClient
import ai.antigem.ui.components.AppToastHelper
import ai.antigem.ui.components.ChatToastType
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A question before an action: what is asked and what each answer does. */
private class AccountsAsk(val title: String, val text: String, val confirm: String, val destructive: Boolean = false,
                          val other: String? = null, val onOther: () -> Unit = {}, val onConfirm: () -> Unit)

/**
 * Settings > Antigravity > Account & CLI, under the account card: saved Antigravity logins and switching between them.
 * The bridge keeps the copies (~/.gemini/antigem-accounts) and swaps agy's login file; after a switch the app re-checks
 * the sign-in at once, so the new account is used without restarting anything.
 */
@Composable
fun AgyAccountsCard(
    entry: AgentStatusEntry?,
    auth: AgyHubClient.AgyAuthInfo,
    modifier: Modifier = Modifier
) {
    val state by AgyAccounts.state.collectAsState()
    val error by AgyAccounts.error.collectAsState()
    val busy by AgyAccounts.busy.collectAsState()
    val subtle = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    var ask by remember { mutableStateOf<AccountsAsk?>(null) }
    // Add account: once agy shows signed out, Google sign-in starts on its own
    var signInWhenSignedOut by remember { mutableStateOf(false) }

    // the list follows the signed-in account (switches from here, the mini app, a terminal or a new sign-in)
    LaunchedEffect(auth.isLoggedIn, auth.email) {
        AgyAccounts.refresh()
        if (auth.isLoggedIn && AgyAccounts.saveNextSignIn) {
            AgyAccounts.saveNextSignIn = false
            AgyAccounts.saveCurrent { err ->
                if (err == null) AppToastHelper.showToast("Saved ${auth.email.ifBlank { "this account" }}", ChatToastType.SUCCESS)
            }
        }
    }
    LaunchedEffect(entry?.isSignedOut, signInWhenSignedOut) {
        if (signInWhenSignedOut && entry?.isSignedOut == true) {
            signInWhenSignedOut = false
            entry.action?.onClick?.invoke()
        }
    }

    fun failed(err: Throwable) = AppToastHelper.showToast(err.message ?: "That didn't work", ChatToastType.ERROR)

    // The signed-in login would be replaced or removed while it has no saved copy: save it first, or lose it.
    fun withCurrentKept(action: String, op: (saveCurrent: Boolean, force: Boolean, done: (Throwable?) -> Unit) -> Unit, onOk: () -> Unit) {
        op(false, false) { err ->
            when (err) {
                null -> onOk()
                is AgyAccounts.NotSavedException -> ask = AccountsAsk(
                    title = "${err.email.ifBlank { "This account" }} isn't saved",
                    text = "Save it first to switch back to it later. Without saving, you will have to sign in again to use it.",
                    confirm = "Save and ${action.lowercase()}",
                    other = "$action without saving",
                    onOther = { op(false, true) { e -> if (e == null) onOk() else failed(e) } },
                    onConfirm = { op(true, false) { e -> if (e == null) onOk() else failed(e) } }
                )
                else -> failed(err)
            }
        }
    }

    fun switchTo(acc: AgyAccounts.Account) {
        val go = {
            AgyAccounts.saveNextSignIn = false
            withCurrentKept("Switch", { save, force, done -> AgyAccounts.switchTo(acc.email, save, force, done) }) {
                AppToastHelper.showToast("Switched to ${acc.name}", ChatToastType.SUCCESS)
            }
        }
        if (AgyAccounts.replyRunning()) {
            ask = AccountsAsk("A reply is running", "Switching the account now may stop it.", "Switch anyway", onConfirm = go)
        } else go()
    }

    fun addAccount() {
        val go = {
            withCurrentKept("Sign out", { save, force, done -> AgyAccounts.clearForNewSignIn(save, force, done) }) {
                signInWhenSignedOut = true
                AppToastHelper.showToast("Sign in with the other Google account", ChatToastType.INFO)
            }
        }
        if (AgyAccounts.replyRunning()) {
            ask = AccountsAsk("A reply is running", "Signing out to add an account now may stop it.", "Go on", onConfirm = go)
        } else go()
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Saved accounts", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            }
            Spacer(Modifier.height(6.dp))
            val st = state
            when {
                st == null && error != null -> {
                    Text(error.orEmpty(), fontSize = 12.5.sp, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { AgyAccounts.refresh() }) { Text("Retry") }
                }
                st == null -> Text("Loading…", fontSize = 12.5.sp, color = subtle)
                !st.supported -> Text(
                    "Agy on this computer keeps its login in the system keychain, so accounts can't be switched here.",
                    fontSize = 12.5.sp, color = subtle
                )
                else -> {
                    Text(
                        "Keep several Google accounts and switch between them. Antigravity uses the new one right away.",
                        fontSize = 12.sp, color = subtle
                    )
                    // signed in with a login that has no saved copy
                    if (st.current.signedIn && !st.current.saved) {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(st.current.email.ifBlank { "Signed-in account" }, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("Signed in now, not saved", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.tertiary)
                            }
                            TextButton(enabled = !busy, onClick = {
                                AgyAccounts.saveCurrent { err ->
                                    if (err == null) AppToastHelper.showToast("Saved ${st.current.email}", ChatToastType.SUCCESS) else failed(err)
                                }
                            }) { Text("Save") }
                        }
                    }
                    st.accounts.forEach { acc ->
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), modifier = Modifier.size(30.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(acc.name.trim().take(1).uppercase().ifBlank { "?" }, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(acc.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    if (acc.active) "Signed in now" else "Last used ${formatAccountTime(acc.usedAt.takeIf { it > 0 } ?: acc.savedAt)}",
                                    fontSize = 11.5.sp, color = if (acc.active) MaterialTheme.colorScheme.primary else subtle
                                )
                            }
                            if (acc.active) {
                                Text("Active", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 8.dp))
                            } else {
                                TextButton(enabled = !busy, onClick = { switchTo(acc) }) { Text("Switch") }
                            }
                            IconButton(enabled = !busy, onClick = {
                                ask = AccountsAsk(
                                    title = "Delete the saved copy of ${acc.name}?",
                                    text = if (acc.active) "It stays signed in; only the saved copy is deleted."
                                    else "To use this account again you will have to sign in again.",
                                    confirm = "Delete", destructive = true,
                                    onConfirm = { AgyAccounts.delete(acc.email) { err -> if (err != null) failed(err) } }
                                )
                            }, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Outlined.Delete, contentDescription = "Delete saved copy", tint = subtle, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    if (st.accounts.isEmpty() && !(st.current.signedIn && !st.current.saved)) {
                        Spacer(Modifier.height(8.dp))
                        Text("No saved accounts yet.", fontSize = 12.5.sp, color = subtle)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(enabled = !busy, onClick = { addAccount() }) { Text("Add account") }
                        if (error != null) Text(error.orEmpty(), fontSize = 11.5.sp, color = MaterialTheme.colorScheme.error, maxLines = 2)
                    }
                }
            }
        }
    }

    ask?.let { a ->
        AlertDialog(
            onDismissRequest = { ask = null },
            title = { Text(a.title) },
            text = { Text(a.text) },
            confirmButton = {
                TextButton(onClick = { ask = null; a.onConfirm() }) {
                    Text(a.confirm, color = if (a.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                Row {
                    a.other?.let { label ->
                        TextButton(onClick = { ask = null; a.onOther() }) { Text(label, color = MaterialTheme.colorScheme.error) }
                    }
                    TextButton(onClick = { ask = null }) { Text("Cancel") }
                }
            }
        )
    }
}

private fun formatAccountTime(ms: Long): String =
    if (ms <= 0) "—" else java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))
