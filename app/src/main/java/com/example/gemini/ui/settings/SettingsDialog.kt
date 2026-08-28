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
    chatFontScale: Float = 1.0f,
    onLoginWithGoogle: () -> Unit,
    onToggleServer: (Boolean) -> Unit = {},
    onLogout: () -> Unit = {},
    onManualTokenEntered: (String) -> Unit,
    onToggleModelEnabled: (String, Boolean) -> Unit,
    onEnableAllModels: () -> Unit,
    onRefreshQuotas: () -> Unit,
    onSetContextWindowLimit: (Int) -> Unit = {},
    onSetSummaryModelId: (String) -> Unit = {},
    onSetChatFontScale: (Float) -> Unit = {},
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

                    // Chat Font Size Section
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Chat Font Size (${(chatFontScale * 100).toInt()}%)",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Scale all chat text, code blocks, and markdown proportionally",
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                            )
                        }
                        if (chatFontScale != 1.0f) {
                            TextButton(
                                onClick = { onSetChatFontScale(1.0f) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("Reset (1.0x)", fontSize = 12.sp, color = ClaudeTerracotta)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("A", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.width(10.dp))
                                Slider(
                                    value = chatFontScale,
                                    onValueChange = { onSetChatFontScale(it) },
                                    valueRange = 0.75f..1.60f,
                                    steps = 16,
                                    modifier = Modifier.weight(1f),
                                    colors = SliderDefaults.colors(
                                        thumbColor = ClaudeTerracotta,
                                        activeTrackColor = ClaudeTerracotta
                                    )
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("A", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Preset Buttons
                            val scalePresets = listOf(0.85f to "Small", 1.00f to "Normal", 1.15f to "Medium", 1.30f to "Large", 1.50f to "Huge")
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                scalePresets.forEach { (presetVal, label) ->
                                    val isSelected = kotlin.math.abs(chatFontScale - presetVal) < 0.04f
                                    Surface(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { onSetChatFontScale(presetVal) },
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.surface,
                                        border = BorderStroke(1.dp, if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f))
                                    ) {
                                        Box(
                                            modifier = Modifier.padding(vertical = 6.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = label,
                                                fontSize = 11.5.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface
                                            )
                                        }
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
