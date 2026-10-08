package com.example.gemini.ui.claude

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.data.agent.claude.ClaudeAccountManager
import com.example.gemini.ui.components.ClaudeAccent

/**
 * Claude sign-in: the bridge starts the CLI's OAuth flow. The browser link redirects to the CLI's local callback
 * (works when the bridge runs on this phone); the code option works everywhere (paste the code Claude shows).
 */
@Composable
fun ClaudeLoginDialog(account: ClaudeAccountManager, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val login by account.login.collectAsState()
    val busy by account.isBusy.collectAsState()
    val status by account.status.collectAsState()
    var useCode by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var openedFor by remember { mutableStateOf<String?>(null) }

    fun open(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    // close once signed in
    LaunchedEffect(status?.auth?.loggedIn, login) {
        if (login == null && status?.auth?.loggedIn == true && openedFor != null) onDismiss()
    }
    // open the browser automatically when the flow starts
    LaunchedEffect(login?.login_id) {
        val l = login ?: return@LaunchedEffect
        if (openedFor != l.login_id && l.automatic_url.isNotBlank()) {
            openedFor = l.login_id
            open(l.automatic_url)
        }
    }

    AlertDialog(
        onDismissRequest = {
            if (login != null) account.cancelLogin()
            onDismiss()
        },
        shape = RoundedCornerShape(18.dp),
        title = { Text("Sign in to Claude Code", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val l = login
                if (l == null) {
                    Text(
                        "Choose how you pay for Claude. You'll finish signing in on claude.com.",
                        fontSize = 13.5.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                    )
                    Button(
                        onClick = { account.startLogin("claudeai") },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = ClaudeAccent)
                    ) {
                        androidx.compose.material3.Icon(Icons.Outlined.WorkspacePremium, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Claude subscription (Pro / Max)")
                    }
                    OutlinedButton(onClick = { account.startLogin("console") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        androidx.compose.material3.Icon(Icons.Outlined.Key, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Anthropic Console (API billing)")
                    }
                    if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Starting…", fontSize = 12.5.sp)
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = ClaudeAccent)
                        Spacer(Modifier.width(10.dp))
                        Text("Waiting for you to approve in the browser…", fontSize = 13.sp)
                    }
                    OutlinedButton(onClick = { open(l.automatic_url) }, modifier = Modifier.fillMaxWidth()) {
                        androidx.compose.material3.Icon(Icons.Outlined.OpenInBrowser, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Open the sign-in page again")
                    }
                    Spacer(Modifier.height(2.dp))
                    if (!useCode) {
                        TextButton(onClick = {
                            useCode = true
                            open(l.manual_url)
                        }) { Text("Browser can't return to the app? Sign in with a code", fontSize = 12.5.sp) }
                    } else {
                        Text(
                            "After approving, Claude shows a code. Paste it here:",
                            fontSize = 12.5.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                        )
                        OutlinedTextField(
                            value = code,
                            onValueChange = { code = it },
                            singleLine = true,
                            placeholder = { Text("code#state") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { open(l.manual_url) }) { Text("Open code page", fontSize = 12.sp) }
                            Button(
                                onClick = { account.submitCode(code.trim()) },
                                enabled = code.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(containerColor = ClaudeAccent)
                            ) { Text("Submit code", fontSize = 12.sp) }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = {
                if (login != null) account.cancelLogin()
                onDismiss()
            }) { Text(if (login != null) "Cancel sign-in" else "Close") }
        }
    )
}
