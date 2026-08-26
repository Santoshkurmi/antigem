package com.example.gemini.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.gemini.domain.model.AiModel
import com.example.gemini.domain.model.ModelQuota
import com.example.gemini.theme.*

@Composable
fun SettingsDialog(
    userEmail: String?,
    projectId: String,
    tier: String,
    availableModels: List<AiModel> = AiModel.DEFAULT_MODELS,
    enabledModelIds: Set<String>?,
    quotas: List<ModelQuota>,
    isServerListening: Boolean = false,
    isServerLoading: Boolean = false,
    contextWindowLimit: Int = 10,
    summaryModelId: String = "always_ask",
    isDevModeEnabled: Boolean = false,
    onLoginWithGoogle: () -> Unit,
    onToggleServer: (Boolean) -> Unit = {},
    onLogout: () -> Unit = {},
    onManualTokenEntered: (String) -> Unit,
    onToggleModelEnabled: (String, Boolean) -> Unit,
    onEnableAllModels: () -> Unit,
    onRefreshQuotas: () -> Unit,
    onSetContextWindowLimit: (Int) -> Unit = {},
    onSetSummaryModelId: (String) -> Unit = {},
    onToggleDevMode: (Boolean) -> Unit = {},
    onDismiss: () -> Unit
) {
    var manualToken by remember { mutableStateOf("") }
    var showManualInput by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.88f)
                .padding(vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                Text(
                    text = "Settings & Preferences",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(14.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    // Account info card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (userEmail != null) Icons.Outlined.CheckCircle else Icons.AutoMirrored.Outlined.Login,
                                    contentDescription = null,
                                    tint = if (userEmail != null) QuotaGreen else ClaudeTerracotta,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = userEmail ?: "Not logged in",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.5.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Project: $projectId • Tier: ${tier.uppercase()}",
                                        fontSize = 11.5.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }

                                if (userEmail != null) {
                                    OutlinedButton(
                                        onClick = onLogout,
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = MaterialTheme.colorScheme.error
                                        ),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                                    ) {
                                        Text("Log Out", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // If logged in
                    if (userEmail != null) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = QuotaGreen.copy(alpha = 0.1f)
                        ) {
                            Text(
                                text = "✓ Active session authorized. Log out to connect another account.",
                                fontSize = 11.5.sp,
                                color = QuotaGreen,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    } else {
                        // Login Buttons (Google OAuth & Paste Token)
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = onLoginWithGoogle,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                            ) {
                                Text("Google OAuth", fontSize = 13.sp, color = Color.White)
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            OutlinedButton(
                                onClick = { showManualInput = !showManualInput },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(if (showManualInput) "Close Input" else "Paste URL / Code", fontSize = 13.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Localhost Server Checkbox Card
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isServerListening) QuotaGreen.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !isServerLoading) {
                                        onToggleServer(!isServerListening)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "Localhost Server (:51121)",
                                            fontSize = 13.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(
                                                    if (isServerListening) QuotaGreen.copy(alpha = 0.15f)
                                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                                                )
                                                .padding(horizontal = 6.dp, vertical = 1.dp)
                                        ) {
                                            Text(
                                                text = if (isServerListening) "Listening" else "Stopped",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isServerListening) QuotaGreen else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "Listens for automatic browser callback on port 51121",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }

                                if (isServerLoading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = ClaudeTerracotta
                                    )
                                } else {
                                    Checkbox(
                                        checked = isServerListening,
                                        onCheckedChange = { onToggleServer(it) },
                                        colors = CheckboxDefaults.colors(
                                            checkedColor = QuotaGreen,
                                            checkmarkColor = Color.White
                                        )
                                    )
                                }
                            }
                        }

                        if (showManualInput) {
                            Spacer(modifier = Modifier.height(10.dp))
                            OutlinedTextField(
                                value = manualToken,
                                onValueChange = { manualToken = it },
                                placeholder = { Text("Paste Callback URL (http://...) or Code (4/0...)", fontSize = 12.sp) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                singleLine = true
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Button(
                                onClick = {
                                    if (manualToken.isNotBlank()) {
                                        onManualTokenEntered(manualToken.trim())
                                        manualToken = ""
                                        showManualInput = false
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Apply URL / Code / Token", fontSize = 13.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Context & Memory Management Card
                    Text(
                        text = "Context & Memory Window",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Configure how many recent messages are kept verbatim and which model summarizes older context",
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "Recent Messages Kept Verbatim ($contextWindowLimit msgs)",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(6.dp))

                            val limits = listOf(6, 10, 15, 20, 30)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                limits.forEach { limitVal ->
                                    val isSelected = contextWindowLimit == limitVal
                                    Surface(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { onSetContextWindowLimit(limitVal) },
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.surface,
                                        border = BorderStroke(1.dp, if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f))
                                    ) {
                                        Box(
                                            modifier = Modifier.padding(vertical = 6.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "$limitVal",
                                                fontSize = 12.5.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            Text(
                                text = "Default Model for Summaries",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(6.dp))

                            val summaryOptions = listOf("always_ask" to "Always Ask when Summarizing") + availableModels.map { it.id to it.displayName }
                            var showSummaryDropdown by remember { mutableStateOf(false) }
                            val currentSelectedName = summaryOptions.find { it.first == summaryModelId }?.second ?: "Always Ask when Summarizing"

                            Box(modifier = Modifier.fillMaxWidth()) {
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { showSummaryDropdown = true },
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = currentSelectedName,
                                            fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Icon(
                                            imageVector = Icons.Default.KeyboardArrowDown,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }

                                DropdownMenu(
                                    expanded = showSummaryDropdown,
                                    onDismissRequest = { showSummaryDropdown = false }
                                ) {
                                    summaryOptions.forEach { (id, name) ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = name,
                                                    fontWeight = if (id == summaryModelId) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (id == summaryModelId) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface
                                                )
                                            },
                                            onClick = {
                                                onSetSummaryModelId(id)
                                                showSummaryDropdown = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Enabled Models Selection
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Models in Chat",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Select models you want to use in the chat selector",
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                            )
                        }
                        TextButton(
                            onClick = onEnableAllModels,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("Select All", fontSize = 12.sp, color = ClaudeTerracotta)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            if (availableModels.isEmpty()) {
                                Text(
                                    text = "No models available yet. Connect your Google account above to load your models.",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            } else {
                                availableModels.forEach { model ->
                                    val isEnabled = enabledModelIds == null || model.id in enabledModelIds
                                    val quota = quotas.find { it.modelId == model.id }
                                    val pct = quota?.percentage

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { onToggleModelEnabled(model.id, !isEnabled) }
                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = isEnabled,
                                            onCheckedChange = { checked -> onToggleModelEnabled(model.id, checked) },
                                            colors = CheckboxDefaults.colors(checkedColor = ClaudeTerracotta)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = model.displayName,
                                                fontSize = 13.5.sp,
                                                fontWeight = if (isEnabled) FontWeight.SemiBold else FontWeight.Normal,
                                                color = if (isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                            )
                                            if (pct != null) {
                                                val badgeColor = if (pct > 50) QuotaGreen else if (pct > 20) QuotaAmber else QuotaRed
                                                Text(
                                                    text = "Remaining Quota: $pct%",
                                                    fontSize = 11.sp,
                                                    color = badgeColor
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // Developer Mode & Telemetry Card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.DataObject,
                                    contentDescription = null,
                                    tint = ClaudeTerracotta,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Developer Mode",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Live token telemetry, cache hit ratios, raw JSON payload inspector & prompt overrides",
                                        fontSize = 11.5.sp,
                                        lineHeight = 15.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Switch(
                                    checked = isDevModeEnabled,
                                    onCheckedChange = onToggleDevMode,
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = ClaudeTerracotta
                                    )
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // Quotas List Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Live Model Quotas",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        IconButton(onClick = onRefreshQuotas, modifier = Modifier.size(28.dp)) {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = "Refresh",
                                tint = ClaudeTerracotta,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    if (availableModels.isEmpty()) {
                        Text(
                            text = "No quota information. Log in to view live model quotas.",
                            fontSize = 12.5.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.padding(vertical = 6.dp)
                        )
                    } else {
                        availableModels.forEach { model ->
                            val quota = quotas.find { it.modelId == model.id }
                            val pct = quota?.percentage ?: 0
                            val barColor = if (pct > 50) QuotaGreen else if (pct > 20) QuotaAmber else QuotaRed

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = model.displayName,
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = if (quota?.remainingFraction != null) "$pct%" else "N/A",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = barColor
                                    )
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                LinearProgressIndicator(
                                    progress = { (pct / 100f).coerceIn(0f, 1f) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp)),
                                    color = barColor,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                ) {
                    Text("Done", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
