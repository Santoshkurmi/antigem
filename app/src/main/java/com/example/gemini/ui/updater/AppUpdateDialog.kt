package com.example.gemini.ui.updater

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.gemini.data.updater.AppUpdateInfo
import com.example.gemini.data.updater.AppUpdateManager
import com.example.gemini.data.updater.DownloadState
import com.example.gemini.theme.QuotaGreen
import com.example.gemini.theme.QuotaRed
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

@Composable
fun AppUpdateDialog(
    updateInfo: AppUpdateInfo,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val updateManager = remember { AppUpdateManager(context) }

    var downloadState by remember { mutableStateOf<DownloadState>(DownloadState.Idle) }

    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceColor = MaterialTheme.colorScheme.surface
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariantColor = MaterialTheme.colorScheme.onSurfaceVariant
    val surfaceVariantColor = MaterialTheme.colorScheme.surfaceVariant

    Dialog(
        onDismissRequest = {
            if (!updateInfo.isCritical && downloadState !is DownloadState.Downloading) {
                onDismiss()
            }
        },
        properties = DialogProperties(
            dismissOnBackPress = !updateInfo.isCritical && downloadState !is DownloadState.Downloading,
            dismissOnClickOutside = !updateInfo.isCritical && downloadState !is DownloadState.Downloading
        )
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = surfaceColor,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
                .border(
                    BorderStroke(1.dp, onSurfaceColor.copy(alpha = 0.12f)),
                    shape = RoundedCornerShape(20.dp)
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // Header with themed icon
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(primaryColor.copy(alpha = 0.15f))
                            .border(1.dp, primaryColor.copy(alpha = 0.3f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.RocketLaunch,
                            contentDescription = "Update",
                            tint = primaryColor,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "New Update Available",
                            color = onSurfaceColor,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (updateInfo.isCritical) "Critical Release" else "Release Update",
                            color = if (updateInfo.isCritical) QuotaRed else primaryColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Version & Package Badge Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(surfaceVariantColor.copy(alpha = 0.5f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "v${updateInfo.currentVersionName}",
                            color = onSurfaceVariantColor,
                            fontSize = 12.5.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                            contentDescription = null,
                            tint = primaryColor,
                            modifier = Modifier
                                .padding(horizontal = 6.dp)
                                .size(13.dp)
                        )
                        Text(
                            text = "v${updateInfo.versionName}",
                            color = primaryColor,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Surface(
                        color = surfaceColor,
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, onSurfaceColor.copy(alpha = 0.1f))
                    ) {
                        Text(
                            text = updateInfo.packageName,
                            color = onSurfaceColor,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Changelog Card
                Text(
                    text = "WHAT'S NEW",
                    color = onSurfaceVariantColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )

                Spacer(modifier = Modifier.height(6.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 160.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(surfaceVariantColor.copy(alpha = 0.35f))
                        .border(1.dp, onSurfaceColor.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                        .padding(10.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = updateInfo.changelog.ifBlank { "• Performance optimizations\n• Stability and UI improvements" },
                            color = onSurfaceColor,
                            fontSize = 12.5.sp,
                            lineHeight = 17.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Download Progress & States
                when (val state = downloadState) {
                    is DownloadState.Downloading -> {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Downloading...",
                                    color = onSurfaceVariantColor,
                                    fontSize = 12.sp
                                )
                                Text(
                                    text = "${(state.progress * 100).toInt()}%",
                                    color = primaryColor,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { state.progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(CircleShape),
                                color = primaryColor,
                                trackColor = surfaceVariantColor
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${formatSize(state.downloadedBytes)} / ${formatSize(state.totalBytes)}",
                                color = onSurfaceVariantColor,
                                fontSize = 10.5.sp,
                                modifier = Modifier.align(Alignment.End)
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                    is DownloadState.Completed -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(QuotaGreen.copy(alpha = 0.12f))
                                .border(1.dp, QuotaGreen.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
                                .padding(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = QuotaGreen,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Downloaded! Launching installer...",
                                color = QuotaGreen,
                                fontSize = 12.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                    is DownloadState.Error -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(QuotaRed.copy(alpha = 0.12f))
                                .border(1.dp, QuotaRed.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
                                .padding(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ErrorOutline,
                                contentDescription = null,
                                tint = QuotaRed,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Download failed: ${state.message}",
                                color = QuotaRed,
                                fontSize = 11.5.sp,
                                maxLines = 2
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                    else -> {}
                }

                // Action Buttons Row with no-overflow arrangement
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Visit GitHub Repository
                    TextButton(
                        onClick = {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://github.com/santoshkurmi/antigem")
                            ).apply {
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = primaryColor)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                            contentDescription = "GitHub",
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("GitHub", fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (!updateInfo.isCritical && downloadState !is DownloadState.Downloading) {
                            TextButton(
                                onClick = onDismiss,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                colors = ButtonDefaults.textButtonColors(contentColor = onSurfaceVariantColor)
                            ) {
                                Text("Later", fontSize = 13.sp)
                            }
                        }

                        Button(
                            onClick = {
                                if (downloadState is DownloadState.Completed) {
                                    val file = (downloadState as DownloadState.Completed).file
                                    updateManager.installApk(file)
                                } else if (downloadState !is DownloadState.Downloading) {
                                    coroutineScope.launch {
                                        downloadState = DownloadState.Downloading(0f, 0L, 0L)
                                        val result = updateManager.downloadApk(updateInfo.downloadUrl) { progress, down, total ->
                                            downloadState = DownloadState.Downloading(progress, down, total)
                                        }
                                        result.onSuccess { apkFile ->
                                            downloadState = DownloadState.Completed(apkFile)
                                            updateManager.installApk(apkFile)
                                        }.onFailure { err ->
                                            downloadState = DownloadState.Error(err.message ?: "Unknown error")
                                        }
                                    }
                                }
                            },
                            enabled = downloadState !is DownloadState.Downloading,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = primaryColor,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.height(38.dp)
                        ) {
                            val btnText = when (downloadState) {
                                is DownloadState.Downloading -> "Downloading..."
                                is DownloadState.Completed -> "Install"
                                is DownloadState.Error -> "Retry"
                                else -> "Update"
                            }
                            Icon(
                                imageVector = if (downloadState is DownloadState.Completed) Icons.Rounded.InstallMobile else Icons.Rounded.Download,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = btnText,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    val mb = bytes.toDouble() / (1024.0 * 1024.0)
    return String.format(Locale.US, "%.1f MB", mb)
}
