package com.example.gemini.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.data.remote.AgyActiveInstance
import com.example.gemini.data.remote.BridgeConnectionState
import com.example.gemini.theme.ClaudeTerracotta

@Composable
fun ConnectionStatusBadge(
    state: BridgeConnectionState,
    bridgeUrl: String,
    activeInstances: List<AgyActiveInstance>,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showDialog by remember { mutableStateOf(false) }

    val infiniteTransition = rememberInfiniteTransition(label = "badgePulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val (dotColor, isPulsing, labelText) = when (state) {
        BridgeConnectionState.CONNECTED_READY -> Triple(Color(0xFF4CAF50), false, "Bridge Ready")
        BridgeConnectionState.STREAMING -> Triple(Color(0xFF4CAF50), true, "Generating...")
        BridgeConnectionState.SPAWNING_INSTANCE -> Triple(Color(0xFFFFB300), true, "Warming Instance...")
        BridgeConnectionState.CONNECTING -> Triple(Color(0xFF2196F3), true, "Connecting...")
        BridgeConnectionState.RECONNECTING -> Triple(Color(0xFF2196F3), true, "Reconnecting...")
        BridgeConnectionState.OFFLINE_ERROR -> Triple(Color(0xFFF44336), false, "Bridge Offline")
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable { showDialog = true }
            .padding(horizontal = 6.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (isPulsing) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .scale(pulseScale)
                        .background(dotColor.copy(alpha = pulseAlpha), CircleShape)
                )
            }
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .background(dotColor, CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.surface, CircleShape)
            )
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(dotColor, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Bridge Status", style = MaterialTheme.typography.titleMedium)
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Status: $labelText",
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Endpoint: $bridgeUrl",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "Active Worker Instances (${activeInstances.size}):",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    if (activeInstances.isEmpty()) {
                        Text(
                            text = "No active warm processes in RAM.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        activeInstances.forEach { inst ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = inst.model,
                                            fontWeight = FontWeight.Medium,
                                            fontSize = 12.5.sp,
                                            color = ClaudeTerracotta
                                        )
                                        Text(
                                            text = "PID: ${inst.pid}",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "Chat: ${inst.conversationId.take(12)}... | Uptime: ${inst.uptimeSeconds}s",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDialog = false
                        onReconnect()
                    }
                ) {
                    Icon(imageVector = Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Reconnect")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}
