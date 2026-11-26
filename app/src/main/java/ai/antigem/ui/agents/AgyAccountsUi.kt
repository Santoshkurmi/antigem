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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.i18n.L

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
                if (err == null) AppToastHelper.showToast(L.agents.agyAccountsUi.savedValue(auth.email.ifBlank { L.agents.agyAccountsUi.thisAccount }), ChatToastType.SUCCESS)
            }
        }
    }
    LaunchedEffect(entry?.isSignedOut, signInWhenSignedOut) {
        if (signInWhenSignedOut && entry?.isSignedOut == true) {
            signInWhenSignedOut = false
            entry.action?.onClick?.invoke()
        }
    }

    fun failed(err: Throwable) = AppToastHelper.showToast(err.message ?: L.agents.agyAccountsUi.thatDidntWork, ChatToastType.ERROR)

    // The signed-in login would be replaced or removed while it has no saved copy: save it first, or lose it.
    fun withCurrentKept(action: String, op: (saveCurrent: Boolean, force: Boolean, done: (Throwable?) -> Unit) -> Unit, onOk: () -> Unit) {
        op(false, false) { err ->
            when (err) {
                null -> onOk()
                is AgyAccounts.NotSavedException -> ask = AccountsAsk(
                    title = L.agents.agyAccountsUi.valueIsntSaved(err.email.ifBlank { L.agents.agyAccountsUi.thisAccount2 }),
                    text = L.agents.agyAccountsUi.saveItFirstToSwitchBack,
                    confirm = L.agents.agyAccountsUi.saveAndAction(action.lowercase()),
                    other = L.agents.agyAccountsUi.actionWithoutSaving(action),
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
            withCurrentKept(L.agents.agyAccountsUi.switch, { save, force, done -> AgyAccounts.switchTo(acc.email, save, force, done) }) {
                AppToastHelper.showToast(L.agents.agyAccountsUi.switchedToName(acc.name), ChatToastType.SUCCESS)
            }
        }
        if (AgyAccounts.replyRunning()) {
            ask = AccountsAsk(L.agents.agyAccountsUi.aReplyIsRunning, L.agents.agyAccountsUi.switchingTheAccountNowMayStop, L.agents.agyAccountsUi.switchAnyway, onConfirm = go)
        } else go()
    }

    fun addAccount() {
        val go = {
            withCurrentKept(L.common.signOut, { save, force, done -> AgyAccounts.clearForNewSignIn(save, force, done) }) {
                signInWhenSignedOut = true
                AppToastHelper.showToast(L.agents.agyAccountsUi.signInWithTheOtherGoogle, ChatToastType.INFO)
            }
        }
        if (AgyAccounts.replyRunning()) {
            ask = AccountsAsk(L.agents.agyAccountsUi.aReplyIsRunning, L.agents.agyAccountsUi.signingOutToAddAnAccount, L.agents.agyAccountsUi.goOn, onConfirm = go)
        } else go()
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(L.agents.agyAccountsUi.savedAccounts, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f).semantics { heading() })
                if (busy) CircularProgressIndicator(Modifier.size(16.dp).semantics { contentDescription = L.common.loading }, strokeWidth = 2.dp)
            }
            Spacer(Modifier.height(6.dp))
            val st = state
            when {
                st == null && error != null -> {
                    Text(error.orEmpty(), fontSize = 12.5.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    TextButton(onClick = { AgyAccounts.refresh() }) { Text(L.common.retry) }
                }
                st == null -> Text(L.common.loading, fontSize = 12.5.sp, color = subtle)
                !st.supported -> Text(
                    L.agents.agyAccountsUi.agyOnThisComputerKeepsIts,
                    fontSize = 12.5.sp, color = subtle
                )
                else -> {
                    Text(
                        L.agents.agyAccountsUi.keepSeveralGoogleAccountsAndSwitch,
                        fontSize = 12.sp, color = subtle
                    )
                    // signed in with a login that has no saved copy
                    if (st.current.signedIn && !st.current.saved) {
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.semantics(mergeDescendants = true) { }, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(st.current.email.ifBlank { L.agents.agyAccountsUi.signedInAccount }, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(L.agents.agyAccountsUi.signedInNowNotSaved, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.tertiary)
                            }
                            TextButton(enabled = !busy, onClick = {
                                AgyAccounts.saveCurrent { err ->
                                    if (err == null) AppToastHelper.showToast(L.agents.agyAccountsUi.savedEmail(st.current.email), ChatToastType.SUCCESS) else failed(err)
                                }
                            }) { Text(L.common.save) }
                        }
                    }
                    st.accounts.forEach { acc ->
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.semantics(mergeDescendants = true) { }, verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), modifier = Modifier.size(30.dp).clearAndSetSemantics { }) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(acc.name.trim().take(1).uppercase().ifBlank { "?" }, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(acc.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    if (acc.active) L.agents.agyAccountsUi.signedInNow else L.agents.agyAccountsUi.lastUsedFormataccounttime(formatAccountTime(acc.usedAt.takeIf { it > 0 } ?: acc.savedAt)),
                                    fontSize = 11.5.sp, color = if (acc.active) MaterialTheme.colorScheme.primary else subtle
                                )
                            }
                            if (acc.active) {
                                Text(L.common.active, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 8.dp))
                            } else {
                                TextButton(enabled = !busy, onClick = { switchTo(acc) }) { Text(L.agents.agyAccountsUi.switch) }
                            }
                            IconButton(enabled = !busy, onClick = {
                                ask = AccountsAsk(
                                    title = L.agents.agyAccountsUi.deleteTheSavedCopyOfName(acc.name),
                                    text = if (acc.active) L.agents.agyAccountsUi.itStaysSignedInOnlyThe
                                    else L.agents.agyAccountsUi.toUseThisAccountAgainYou,
                                    confirm = L.common.delete, destructive = true,
                                    onConfirm = { AgyAccounts.delete(acc.email) { err -> if (err != null) failed(err) } }
                                )
                            }, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Outlined.Delete, contentDescription = L.agents.agyAccountsUi.deleteSavedCopy, tint = subtle, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    if (st.accounts.isEmpty() && !(st.current.signedIn && !st.current.saved)) {
                        Spacer(Modifier.height(8.dp))
                        Text(L.agents.agyAccountsUi.noSavedAccountsYet, fontSize = 12.5.sp, color = subtle)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(enabled = !busy, onClick = { addAccount() }) { Text(L.agents.agyAccountsUi.addAccount) }
                        if (error != null) Text(error.orEmpty(), fontSize = 11.5.sp, color = MaterialTheme.colorScheme.error, maxLines = 2, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                }
            }
        }
    }

    ask?.let { a ->
        AlertDialog(
            onDismissRequest = { ask = null },
            title = { Text(a.title, modifier = Modifier.semantics { heading() }) },
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
                    TextButton(onClick = { ask = null }) { Text(L.common.cancel) }
                }
            }
        )
    }
}

private fun formatAccountTime(ms: Long): String =
    if (ms <= 0) "—" else java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))
