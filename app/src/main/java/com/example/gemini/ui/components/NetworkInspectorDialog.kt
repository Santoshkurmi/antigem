package com.example.gemini.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.gemini.data.remote.inspector.NetworkInspectorManager
import com.example.gemini.data.remote.inspector.NetworkLogEntry

enum class NetworkFilterType(val label: String) {
    ALL("All"),
    GRPC("gRPC Daemon"),
    BRIDGE("IDE Bridge"),
    STREAMS("Streams"),
    ERRORS("Errors")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkInspectorDialog(
    onDismiss: () -> Unit
) {
    val logs by NetworkInspectorManager.logs.collectAsState()
    val activeStreams by NetworkInspectorManager.activeStreamsCount.collectAsState()
    var selectedFilter by remember { mutableStateOf(NetworkFilterType.ALL) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedEntry by remember { mutableStateOf<NetworkLogEntry?>(null) }
    val context = LocalContext.current

    val filteredLogs = remember(logs, selectedFilter, searchQuery) {
        logs.filter { entry ->
            val matchesFilter = when (selectedFilter) {
                NetworkFilterType.ALL -> true
                NetworkFilterType.GRPC -> entry.serviceType.contains("Daemon", ignoreCase = true) || entry.method == "gRPC"
                NetworkFilterType.BRIDGE -> entry.serviceType.contains("Bridge", ignoreCase = true) || entry.method != "gRPC"
                NetworkFilterType.STREAMS -> entry.isConnected || entry.durationMs >= 2000L || entry.isStreaming
                NetworkFilterType.ERRORS -> !entry.isSuccess
            }
            val matchesSearch = if (searchQuery.isBlank()) true else {
                entry.path.contains(searchQuery, ignoreCase = true) ||
                        entry.url.contains(searchQuery, ignoreCase = true) ||
                        entry.statusCode.toString().contains(searchQuery) ||
                        (entry.grpcStatus?.contains(searchQuery, ignoreCase = true) == true)
            }
            matchesFilter && matchesSearch
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 24.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Top Header Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF0284C7).copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Language,
                                contentDescription = null,
                                tint = Color(0xFF0284C7),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Network Inspector",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${logs.size} calls recorded · ${activeStreams} active stream${if (activeStreams == 1) "" else "s"}",
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { NetworkInspectorManager.clearLogs() },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "Clear logs",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                // Search & Filter Row
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        placeholder = { Text("Filter by endpoint, route, or status...", fontSize = 12.5.sp) },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(20.dp)) {
                                    Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(14.dp))
                                }
                            }
                        },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontSize = 12.5.sp),
                        shape = RoundedCornerShape(10.dp)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Filter Chips Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        NetworkFilterType.values().forEach { filter ->
                            val isSelected = selectedFilter == filter
                            FilterChip(
                                selected = isSelected,
                                onClick = { selectedFilter = filter },
                                label = { Text(filter.label, fontSize = 11.5.sp) },
                                shape = RoundedCornerShape(8.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            )
                        }
                    }
                }

                Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))

                // Network Logs List
                if (filteredLogs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Outlined.NetworkCheck,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.size(44.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = if (logs.isEmpty()) "No network calls captured yet" else "No matching calls found",
                                fontSize = 13.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        state = rememberLazyListState(),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 6.dp)
                    ) {
                        items(filteredLogs, key = { it.id }) { entry ->
                            NetworkLogItemRow(
                                entry = entry,
                                onClick = { selectedEntry = entry }
                            )
                            Divider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f),
                                modifier = Modifier.padding(horizontal = 12.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // Detail Inspector Sub-Dialog
    if (selectedEntry != null) {
        NetworkLogDetailDialog(
            entry = selectedEntry!!,
            onDismiss = { selectedEntry = null }
        )
    }
}

@Composable
private fun NetworkLogItemRow(
    entry: NetworkLogEntry,
    onClick: () -> Unit
) {
    val methodColor = when (entry.method.uppercase()) {
        "GRPC" -> Color(0xFF8B5CF6) // Purple
        "GET" -> Color(0xFF10B981) // Green
        "POST" -> Color(0xFF3B82F6) // Blue
        "PUT", "PATCH" -> Color(0xFFF59E0B) // Amber
        "DELETE" -> Color(0xFFEF4444) // Red
        else -> Color(0xFF6B7280)
    }

    val isConnected = entry.isConnected
    val now by produceState(initialValue = System.currentTimeMillis(), key1 = entry.id, key2 = isConnected) {
        if (isConnected) {
            while (true) {
                kotlinx.coroutines.delay(250)
                value = System.currentTimeMillis()
            }
        }
    }

    val elapsed = if (isConnected) maxOf(0L, now - entry.startTimeMs) else maxOf(0L, entry.durationMs)
    val isLongLived = isConnected || elapsed >= 2000L || entry.isStreaming

    val liveDurationText = remember(elapsed, isConnected) {
        if (elapsed < 1000L) {
            "${elapsed}ms"
        } else if (elapsed < 60_000L) {
            String.format(java.util.Locale.US, "%.1fs", elapsed / 1000f)
        } else {
            val mins = elapsed / 60_000L
            val secs = (elapsed % 60_000L) / 1000L
            "${mins}m ${secs}s"
        }
    }

    val statusColor = when {
        isConnected -> Color(0xFF10B981)
        !entry.isSuccess -> Color(0xFFEF4444)
        else -> Color(0xFF10B981)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Method Badge
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = methodColor.copy(alpha = 0.14f),
            border = BorderStroke(0.8.dp, methodColor.copy(alpha = 0.4f))
        ) {
            Text(
                text = entry.method,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                color = methodColor,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        // Route and Details
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.displayName,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (isLongLived) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(3.dp),
                        color = Color(0xFF10B981).copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = if (isConnected) "STREAMING" else "STREAM",
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF10B981),
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.formattedTime,
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = entry.serviceType,
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Status and Duration
        Column(horizontalAlignment = Alignment.End) {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = if (isConnected) Color(0xFF10B981).copy(alpha = 0.15f) else statusColor.copy(alpha = 0.12f)
            ) {
                Text(
                    text = when {
                        isConnected && elapsed >= 2000L -> "Live Stream"
                        isConnected -> "Connected"
                        entry.error != null -> "Err"
                        entry.grpcStatus != null && entry.grpcStatus != "0" -> "gRPC ${entry.grpcStatus}"
                        entry.statusCode > 0 -> "${entry.statusCode}"
                        else -> "OK"
                    },
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isConnected) Color(0xFF10B981) else statusColor,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp)
                )
            }

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = liveDurationText,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Medium,
                color = if (isConnected) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun NetworkLogDetailDialog(
    entry: NetworkLogEntry,
    onDismiss: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Overview", "Headers")
    val context = LocalContext.current

    fun copyToClipboard(label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Copied $label to clipboard", Toast.LENGTH_SHORT).show()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = entry.displayName,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = entry.url,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", modifier = Modifier.size(18.dp))
                    }
                }

                // Tabs
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title, fontSize = 12.sp, fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal) }
                        )
                    }
                }

                // Content
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    SelectionContainer {
                        when (selectedTab) {
                            0 -> OverviewTab(entry, onCopy = { l, t -> copyToClipboard(l, t) })
                            1 -> HeadersTab(entry, onCopy = { l, t -> copyToClipboard(l, t) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OverviewTab(
    entry: NetworkLogEntry,
    onCopy: (String, String) -> Unit
) {
    val isConnected = entry.isConnected
    val now by produceState(initialValue = System.currentTimeMillis(), key1 = entry.id, key2 = isConnected) {
        if (isConnected) {
            while (true) {
                kotlinx.coroutines.delay(250)
                value = System.currentTimeMillis()
            }
        }
    }
    val durationStr = if (isConnected) {
        val elapsed = maxOf(0L, now - entry.startTimeMs)
        if (elapsed < 1000L) "${elapsed} ms (Active)" else String.format(java.util.Locale.US, "%.1f s (Active)", elapsed / 1000f)
    } else {
        "${entry.durationMs} ms"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        DetailRow("Full URL", entry.url, isCopyable = true, onCopy = onCopy)
        DetailRow("Method", entry.method)
        DetailRow("Service Target", entry.serviceType)
        DetailRow("Timestamp", entry.formattedTime)
        DetailRow("Duration", durationStr)
        DetailRow("HTTP Status", if (entry.statusCode > 0) "${entry.statusCode}" else if (isConnected) "Connected / Streaming" else "Pending")
        if (entry.grpcStatus != null) {
            DetailRow("gRPC Status", entry.grpcStatus)
        }
        if (entry.error != null) {
            DetailRow("Error Message", entry.error, isError = true, isCopyable = true, onCopy = onCopy)
        }
    }
}

@Composable
private fun HeadersTab(
    entry: NetworkLogEntry,
    onCopy: (String, String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Request Headers (${entry.requestHeaders.size})", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        if (entry.requestHeaders.isEmpty()) {
            Text("No request headers recorded.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            entry.requestHeaders.forEach { (k, v) ->
                HeaderItem(k, v, onCopy)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text("Response Headers (${entry.responseHeaders.size})", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        if (entry.responseHeaders.isEmpty()) {
            Text("No response headers recorded.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            entry.responseHeaders.forEach { (k, v) ->
                HeaderItem(k, v, onCopy)
            }
        }
    }
}

@Composable
private fun HeaderItem(key: String, value: String, onCopy: (String, String) -> Unit) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCopy(key, "$key: $value") }
            .padding(vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = key,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.primary,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.widthIn(max = 140.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = value,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f)
            )
        }
    }
}


@Composable
private fun DetailRow(
    label: String,
    value: String,
    isError: Boolean = false,
    isCopyable: Boolean = false,
    onCopy: ((String, String) -> Unit)? = null
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        modifier = Modifier
            .fillMaxWidth()
            .let { if (isCopyable && onCopy != null) it.clickable { onCopy(label, value) } else it }
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
