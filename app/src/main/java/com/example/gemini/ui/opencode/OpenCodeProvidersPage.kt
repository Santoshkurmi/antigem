package com.example.gemini.ui.opencode

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.data.agent.opencode.OpenCodeAuth
import com.example.gemini.data.agent.opencode.OpenCodeChatBackend
import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.ui.claude.ClaudeCard
import com.example.gemini.ui.components.AppToastHelper
import com.example.gemini.ui.components.ChatToastType
import com.example.gemini.ui.components.OpenCodeAccent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Subscriptions people sign in with most, shown as quick tiles (id → tile title and hint). */
private val QUICK = listOf(
    Triple("openai", "ChatGPT / OpenAI", "ChatGPT Plus / Pro, or an API key"),
    Triple("github-copilot", "GitHub Copilot", "Your Copilot subscription"),
    Triple("claude-agent", "Claude subscription", "Uses Claude Code's login"),
    Triple("xai", "SuperGrok / xAI", "Grok subscription or API key"),
    Triple("opencode", "OpenCode Zen", "OpenCode Console account"),
    Triple("opencode-go", "OpenCode Go", "OpenCode's plan · key"),
    Triple("openrouter", "OpenRouter", "Hundreds of models · key"),
    Triple("anthropic", "Anthropic API", "Claude models · key")
)

private fun toast(msg: String, ok: Boolean = true) = AppToastHelper.showToast(msg, if (ok) ChatToastType.SUCCESS else ChatToastType.ERROR)

private fun methodIcon(m: OpenCodeAuth.Method): ImageVector = when {
    m.isKey -> Icons.Outlined.Key
    m.isExternal -> Icons.Outlined.Link
    m.looksLikeDeviceCode -> Icons.Outlined.Devices
    else -> Icons.Outlined.OpenInBrowser
}

private fun methodHint(m: OpenCodeAuth.Method, remote: Boolean): String = when {
    m.isKey -> "Paste the provider's API key" + (m.envNames.firstOrNull()?.let { " (or set $it on the server)" } ?: "")
    m.isExternal -> "Uses credentials already set up on the server"
    m.looksLikeDeviceCode -> "Enter a short code on the provider's site · works from any device"
    remote -> "Sign in in your browser, then paste its address back here"
    else -> "Sign in in your browser on this phone"
}

/** Settings → OpenCode → Providers: connected accounts, quick tiles, every provider, and signing in. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OpenCodeProvidersPage(backend: OpenCodeChatBackend) {
    val scope = rememberCoroutineScope()
    val models by backend.availableModels.collectAsState()
    var integrations by remember { mutableStateOf<List<OpenCodeAuth.Integration>>(emptyList()) }
    var credentials by remember { mutableStateOf<List<OpenCodeAuth.Credential>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var signingIn by remember { mutableStateOf<OpenCodeAuth.Integration?>(null) }
    var showAll by remember { mutableStateOf(false) }

    suspend fun reload() {
        loading = true
        backend.client.integrations().onSuccess { integrations = it; error = null }.onFailure { error = it.message }
        backend.client.credentials().onSuccess { credentials = it }
        loading = false
    }
    LaunchedEffect(Unit) { reload() }
    val byId = integrations.associateBy { it.id }

    ClaudeCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Your providers", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    "Sign in with a subscription (ChatGPT, GitHub Copilot, SuperGrok, Claude…) or an API key. " +
                        "Their models then show in the model picker; ${models.size} are available now.",
                    fontSize = 11.5.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = OpenCodeAccent)
            else IconButton(onClick = { scope.launch { reload() } }) { Icon(Icons.Outlined.Refresh, "Refresh") }
        }
        error?.let { Text(it, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp)) }

        val connected = integrations.filter { it.connected }
        if (connected.isEmpty() && error == null && !loading) {
            Text("Nothing connected yet: OpenCode's free models work without an account.", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.padding(top = 8.dp))
        }
        connected.forEach { integ ->
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(integ.name, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { signingIn = integ }) { Text("Add account", fontSize = 12.sp) }
            }
            val creds = credentials.filter { it.integrationId == integ.id }
            integ.connections.forEach { conn ->
                val cred = creds.firstOrNull { it.id == conn.id }
                val active = cred?.active ?: (integ.connections.size == 1)
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (active) Icons.Outlined.CheckCircle else Icons.AutoMirrored.Outlined.Login, null,
                        tint = if (active) OpenCodeAccent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(conn.label, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(
                                when (cred?.type ?: conn.method) { "key" -> "API key"; "oauth" -> "Signed in"; else -> cred?.type ?: conn.method },
                                if (active && integ.connections.size > 1) "in use" else null
                            ).joinToString(" · "),
                            fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                    if (!active && conn.id.startsWith("cred")) {
                        TextButton(onClick = {
                            scope.launch {
                                backend.client.activateCredential(conn.id)
                                    .onSuccess { toast("Now using ${conn.label}"); backend.refreshModels() }
                                    .onFailure { toast("Could not switch: ${it.message}", ok = false) }
                                reload()
                            }
                        }) { Text("Use this", fontSize = 12.sp) }
                    }
                    if (conn.id.startsWith("cred")) {
                        TextButton(onClick = {
                            scope.launch {
                                backend.client.removeCredential(conn.id)
                                    .onSuccess { toast("${integ.name} disconnected"); backend.refreshModels() }
                                    .onFailure { toast("Could not disconnect: ${it.message}", ok = false) }
                                reload()
                            }
                        }) { Text("Disconnect", fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }

    ClaudeCard {
        Text("Connect", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        val tiles = QUICK.filter { byId.containsKey(it.first) }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tiles.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (id, title, hint) ->
                        val integ = byId.getValue(id)
                        com.example.gemini.ui.settings.McpPresetTile(
                            name = title, subtitle = hint, icon = Icons.AutoMirrored.Outlined.Login, accent = OpenCodeAccent,
                            added = integ.connected, addedLabel = "Connected · tap to add another",
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        ) { signingIn = integ }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { showAll = true },
            enabled = integrations.isNotEmpty(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = OpenCodeAccent, contentColor = Color.White),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Outlined.Search, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
            Text("All ${integrations.size.takeIf { it > 0 } ?: ""} providers")
        }
    }

    if (showAll) {
        AllProvidersSheet(integrations.filter { it.signInMethods.isNotEmpty() }, onDismiss = { showAll = false }) {
            showAll = false
            signingIn = it
        }
    }
    signingIn?.let { integ ->
        ProviderSignInSheet(backend, integ, onDismiss = { signingIn = null }) {
            signingIn = null
            backend.refreshModels()
            scope.launch { reload() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AllProvidersSheet(list: List<OpenCodeAuth.Integration>, onDismiss: () -> Unit, onPick: (OpenCodeAuth.Integration) -> Unit) {
    var query by remember { mutableStateOf("") }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 16.dp).navigationBarsPadding()) {
            Text("Connect a provider", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true, shape = RoundedCornerShape(14.dp),
                leadingIcon = { Icon(Icons.Outlined.Search, null) }, placeholder = { Text("Search ${list.size} providers") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            val shown = list.filter { query.isBlank() || it.name.contains(query, true) || it.id.contains(query, true) }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                items(shown, key = { it.id }) { integ ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(integ) }.padding(horizontal = 8.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(integ.name, fontSize = 14.sp)
                            Text(
                                integ.signInMethods.joinToString(" · ") { m ->
                                    when { m.isKey -> "key"; m.isExternal -> "server login"; else -> "sign in" }
                                }.let { s -> s.split(" · ").distinct().joinToString(" · ") },
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                        if (integ.connected) Text("connected", fontSize = 11.sp, color = OpenCodeAccent)
                    }
                }
            }
        }
    }
}

/**
 * Signing in to one provider: pick the method (when it has several), answer its questions, then the key, the
 * device code, the browser (with the server-mode address step) or the server's own login.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ProviderSignInSheet(backend: OpenCodeChatBackend, integ: OpenCodeAuth.Integration, onDismiss: () -> Unit, onConnected: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val remote = AuthPreferences.currentRemoteMode
    val methods = integ.signInMethods.sortedBy { m ->
        // in server mode the device code works best; on the phone the browser sign-in is as good
        when { m.isOAuth && m.looksLikeDeviceCode -> 0; m.isOAuth -> if (remote) 2 else 0; m.isExternal -> 1; else -> 3 }
    }
    var method by remember { mutableStateOf(methods.singleOrNull()) }
    val answers = remember { mutableStateMapOf<String, String>() }
    var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf<OpenCodeAuth.Attempt?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var pasted by remember { mutableStateOf("") }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    fun finish(msg: String) {
        toast(msg)
        attempt = null
        onConnected()
    }
    // a sign-in left open is cancelled
    DisposableEffect(Unit) {
        onDispose {
            attempt?.let { a -> backend.cancelSignIn(integ.id, a.id) }
        }
    }
    // follow the attempt until OpenCode says it is done
    LaunchedEffect(attempt?.id) {
        val a = attempt ?: return@LaunchedEffect
        while (attempt?.id == a.id) {
            delay(2_000)
            now = System.currentTimeMillis()
            when (val st = backend.client.oauthStatus(integ.id, a.id).getOrNull()) {
                is OpenCodeAuth.AttemptStatus.Complete -> { finish("${integ.name} connected"); return@LaunchedEffect }
                is OpenCodeAuth.AttemptStatus.Failed -> { failed = st.message; attempt = null; return@LaunchedEffect }
                else -> Unit
            }
            if (a.expiresAt != null && System.currentTimeMillis() > a.expiresAt) {
                failed = "The sign-in expired. Start it again."
                attempt = null
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 22.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                com.example.gemini.ui.agents.AgentMark(com.example.gemini.domain.model.AgentKind.OPENCODE, size = 36.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(integ.name, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text(if (integ.connected) "Add another account" else "Connect your account", fontSize = 12.sp, color = OpenCodeAccent)
                }
            }
            Spacer(Modifier.height(14.dp))

            val m = method
            when {
                // ---- pick a method
                m == null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (methods.isEmpty()) Text("This provider has no way to sign in from the app.", fontSize = 13.sp)
                    methods.forEach { opt ->
                        Surface(
                            onClick = { method = opt; failed = null },
                            shape = RoundedCornerShape(14.dp),
                            color = com.example.gemini.ui.claude.claudeCardColor(),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(OpenCodeAccent.copy(alpha = 0.13f)), contentAlignment = Alignment.Center) {
                                    Icon(methodIcon(opt), null, tint = OpenCodeAccent, modifier = Modifier.size(19.dp))
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(OpenCodeAuth.methodTitle(opt), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                    Text(methodHint(opt, remote), fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                }
                            }
                        }
                    }
                }

                // ---- an attempt is running
                attempt != null -> {
                    val a = attempt!!
                    val code = a.userCode
                    Text(OpenCodeAuth.methodTitle(m), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Spacer(Modifier.height(10.dp))
                    if (code != null) {
                        Text("Enter this code on the sign-in page:", fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                        Spacer(Modifier.height(6.dp))
                        Surface(
                            shape = RoundedCornerShape(14.dp), color = OpenCodeAccent.copy(alpha = 0.1f),
                            border = BorderStroke(1.dp, OpenCodeAccent.copy(alpha = 0.4f)), modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(code, fontSize = 26.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp,
                                    modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                                IconButton(onClick = {
                                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Code", code))
                                    toast("Code copied")
                                }) { Icon(Icons.Outlined.ContentCopy, "Copy code", tint = OpenCodeAccent) }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    } else if (a.instructions.isNotBlank()) {
                        Text(a.instructions, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                        Spacer(Modifier.height(10.dp))
                    }
                    Button(
                        onClick = {
                            if (code != null) (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Code", code))
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(a.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                .onFailure { toast("No browser to open the page", ok = false) }
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = OpenCodeAccent, contentColor = Color.White),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp))
                        Text(if (code != null) "Copy code & open sign-in page" else "Open sign-in page")
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = OpenCodeAccent)
                        Spacer(Modifier.width(8.dp))
                        val left = a.expiresAt?.let { ((it - now) / 1000).coerceAtLeast(0) }
                        Text(
                            "Waiting for you to finish signing in" + (left?.let { " · ${it / 60}:${String.format("%02d", it % 60)} left" } ?: ""),
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                        )
                    }
                    // the provider shows a code to paste back
                    if (a.mode == "code" || a.paste) {
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(pasted, { pasted = it }, label = { Text("Code from the provider") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(onClick = {
                            busy = true
                            scope.launch {
                                backend.client.completeOAuth(integ.id, a.id, pasted.trim()).onFailure { failed = it.message }
                                busy = false
                            }
                        }, enabled = pasted.isNotBlank() && !busy, shape = RoundedCornerShape(12.dp)) { Text("Submit code") }
                    } else if (a.returnsToLoopback && remote) {
                        // server mode: the browser lands on this phone's 127.0.0.1; its address goes to the server
                        Spacer(Modifier.height(12.dp))
                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text("After you sign in", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text(
                                    "The browser ends on a page that can't load (an address starting with http://127.0.0.1 or http://localhost). " +
                                        "That's expected with a server: copy that whole address from the address bar and paste it here.",
                                    fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                                )
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(pasted, { pasted = it }, placeholder = { Text("http://127.0.0.1:…/callback?code=…") }, singleLine = true,
                                    modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                                Spacer(Modifier.height(6.dp))
                                OutlinedButton(onClick = {
                                    busy = true
                                    scope.launch {
                                        backend.client.forwardOAuthCallback(pasted.trim())
                                            .onSuccess { toast("Sent to the server, finishing…") }
                                            .onFailure { failed = it.message }
                                        busy = false
                                    }
                                }, enabled = pasted.isNotBlank() && !busy, shape = RoundedCornerShape(12.dp)) { Text("Finish sign-in") }
                            }
                        }
                    }
                    failed?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
                    TextButton(onClick = {
                        scope.launch { backend.client.cancelOAuth(integ.id, a.id) }
                        attempt = null
                    }) { Text("Cancel") }
                }

                // ---- the chosen method: its form, then the action
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(methodIcon(m), null, tint = OpenCodeAccent, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(OpenCodeAuth.methodTitle(m), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        if (methods.size > 1) TextButton(onClick = { method = null; failed = null }) { Text("Other ways") }
                    }
                    Text(methodHint(m, remote), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    Spacer(Modifier.height(10.dp))
                    AuthForm(m.form, answers)
                    if (m.isKey) {
                        OutlinedTextField(
                            key, { key = it }, label = { Text("API key") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (integ.connections.any { it.method == "key" }) "Saved by OpenCode on the server. It replaces the key saved for ${integ.name} now (one key per provider)."
                            else "Saved by OpenCode on the server.",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                    failed?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
                    Spacer(Modifier.height(12.dp))
                    val missing = OpenCodeAuth.missing(m.form, answers)
                    Button(
                        onClick = {
                            busy = true
                            failed = null
                            scope.launch {
                                val answer = OpenCodeAuth.answer(m.form, answers)
                                when {
                                    m.isKey -> backend.client.connectKey(integ.id, key.trim())
                                        .onSuccess { finish("${integ.name} connected") }.onFailure { failed = it.message }
                                    m.isExternal -> backend.client.connectExternal(integ.id, m.id.orEmpty(), answer)
                                        .onSuccess { finish("${integ.name} connected") }.onFailure { failed = it.message }
                                    else -> backend.client.startOAuth(integ.id, m.id.orEmpty(), answer)
                                        .onSuccess { a ->
                                            attempt = a
                                            now = System.currentTimeMillis()
                                            // a device code or a browser page: open it right away (the code is copied first)
                                            a.userCode?.let { c -> (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Code", c)) }
                                            if (a.url.isNotBlank() && a.userCode == null) {
                                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(a.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                            }
                                        }
                                        .onFailure { failed = it.message }
                                }
                                busy = false
                            }
                        },
                        enabled = !busy && missing == null && (!m.isKey || key.isNotBlank()),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = OpenCodeAccent, contentColor = Color.White),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                        else Text(when { m.isKey -> "Connect"; m.isExternal -> "Use the server's login"; else -> "Start sign-in" })
                    }
                    if (missing != null) Text("Fill in: $missing", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

/** A sign-in method's questions (choices as chips, typed answers as text fields); hidden ones keep their default. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AuthForm(form: List<OpenCodeAuth.Field>, answers: MutableMap<String, String>) {
    val visible = OpenCodeAuth.visibleFields(form, answers)
    visible.forEach { f ->
        val value = answers[f.key] ?: f.default.orEmpty()
        Text(f.title + if (f.required) "" else " (optional)", fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
        f.description?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)) }
        Spacer(Modifier.height(4.dp))
        if (f.options.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                f.options.forEach { (v, label, _) ->
                    FilterChip(selected = value == v, onClick = { answers[f.key] = v }, label = { Text(label) })
                }
            }
            if (f.custom) {
                OutlinedTextField(value, { answers[f.key] = it }, placeholder = { Text(f.placeholder ?: "Or type it") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        } else if (f.type == "boolean") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = value == "true", onClick = { answers[f.key] = "true" }, label = { Text("Yes") })
                FilterChip(selected = value == "false", onClick = { answers[f.key] = "false" }, label = { Text("No") })
            }
        } else {
            OutlinedTextField(
                value, { answers[f.key] = it }, placeholder = { f.placeholder?.let { Text(it) } }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = if (f.type == "number" || f.type == "integer") KeyboardType.Number else KeyboardType.Text)
            )
        }
        Spacer(Modifier.height(10.dp))
    }
}
