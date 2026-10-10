package com.example.gemini.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.data.remote.SlashCommandItem
import com.example.gemini.theme.QuotaGreen
import com.example.gemini.ui.components.AgyAccent
import com.example.gemini.theme.ClaudeTerracotta

private data class SlashGroup(val title: String, val icon: ImageVector, val color: Color)

private fun groupOf(type: String): SlashGroup = when (type) {
    "app" -> SlashGroup("Opens in the app", Icons.AutoMirrored.Outlined.OpenInNew, AgyAccent)
    "command" -> SlashGroup("Commands", Icons.Outlined.Terminal, ClaudeTerracotta)
    else -> SlashGroup("Skills & plugins", Icons.Outlined.Extension, QuotaGreen)
}

/**
 * The `/` command picker above the input: commands grouped by kind, each with its argument hint and description.
 * [runsOnTap] tells whether a tap runs the command right away (shown as "runs") or puts it in the input.
 */
@Composable
fun SlashCommandOverlay(
    items: List<SlashCommandItem>,
    query: String,
    runsOnTap: (SlashCommandItem) -> Boolean,
    onPick: (SlashCommandItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val order = listOf("app", "command")
    val grouped = items.groupBy { if (it.type in order) it.type else "skill" }
        .toList()
        .sortedBy { (type, _) -> order.indexOf(type).let { if (it < 0) order.size else it } }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
        shadowElevation = 10.dp,
        tonalElevation = 2.dp
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
            contentPadding = PaddingValues(vertical = 6.dp)
        ) {
            grouped.forEach { (type, list) ->
                val group = groupOf(type)
                item(key = "h_$type") {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            group.title.uppercase(),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.weight(1f)
                        )
                        Text("${list.size}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                    }
                }
                items(list, key = { "${it.type}_${it.command}" }) { item ->
                    SlashRow(item, group, query, runsOnTap(item)) { onPick(item) }
                }
            }
        }
    }
}

@Composable
private fun SlashRow(item: SlashCommandItem, group: SlashGroup, query: String, runs: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(group.color.copy(alpha = 0.13f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(if (item.type == "skill" && item.pluginName == null) Icons.Outlined.AutoAwesome else group.icon, null, tint = group.color, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                buildAnnotatedString {
                    val name = item.command
                    val i = if (query.isBlank()) -1 else name.indexOf(query, ignoreCase = true)
                    if (i < 0) append(name) else {
                        append(name.substring(0, i))
                        withStyle(SpanStyle(color = group.color)) { append(name.substring(i, i + query.length)) }
                        append(name.substring(i + query.length))
                    }
                    if (item.argumentHint.isNotBlank()) {
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f), fontWeight = FontWeight.Normal)) {
                            append(" ${item.argumentHint}")
                        }
                    }
                },
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (item.description.isNotBlank()) {
                Text(
                    item.description,
                    fontSize = 11.5.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        val tag = item.pluginName ?: when {
            item.type == "app" -> "opens"
            runs -> "runs"
            else -> null
        }
        tag?.let {
            Spacer(Modifier.width(8.dp))
            Surface(shape = RoundedCornerShape(6.dp), color = group.color.copy(alpha = 0.12f)) {
                Text(
                    it,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = group.color,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}
