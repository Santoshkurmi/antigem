package com.example.gemini.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Functions
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Paid
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.domain.model.TokenUsage

/** The model name as the usage pill shows it (short); the sheet shows the full id. */
fun shortModelName(model: String): String = model.substringAfterLast('/').removePrefix("claude-")

private fun grouped(n: Int): String = String.format(java.util.Locale.US, "%,d", n)

private fun durationText(ms: Long): String = when {
    ms < 1000 -> "$ms ms"
    ms < 60_000 -> String.format(java.util.Locale.US, "%.1f s", ms / 1000.0)
    else -> "${ms / 60_000} min ${(ms % 60_000) / 1000} s"
}

/**
 * Details of one reply's usage (tap the token pill under a reply): the full model id, input / output / cache tokens,
 * time and cost. Same for every agent; values an agent does not report are left out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TokenUsageDetailsSheet(usage: TokenUsage, onViewPayload: (() -> Unit)?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val muted = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    val c = com.example.gemini.theme.AppColors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 22.dp).navigationBarsPadding()) {
            Text("Reply details", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(if (usage.isEstimated) "Token counts are estimates" else "What this reply used", fontSize = 12.sp, color = muted)
            Spacer(Modifier.height(14.dp))

            // model: full id, selectable, with a copy button
            usage.model?.takeIf { it.isNotBlank() }?.let { model ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(c.accentAlt.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Memory, null, tint = c.accentAlt, modifier = Modifier.size(19.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Model", fontSize = 11.sp, color = muted)
                            SelectionContainer {
                                Text(model.substringAfter('/').takeIf { model.contains('/') } ?: model, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                            }
                            if (model.contains('/')) Text("Provider: ${model.substringBefore('/')}", fontSize = 11.5.sp, color = muted)
                        }
                        IconButton(onClick = {
                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Model", model))
                            Toast.makeText(context, "Model name copied", Toast.LENGTH_SHORT).show()
                        }) { Icon(Icons.Outlined.ContentCopy, "Copy model name", tint = muted, modifier = Modifier.size(18.dp)) }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                    val rows = buildList<Triple<ImageVector, Pair<String, String>, Color>> {
                        add(Triple(Icons.Outlined.Download, "Input tokens" to grouped(usage.promptTokens), c.info))
                        add(Triple(Icons.Outlined.Upload, "Output tokens" to grouped(usage.outputTokens), c.success))
                        if (usage.cachedTokens > 0) {
                            val pct = if (usage.promptTokens + usage.cachedTokens > 0)
                                (usage.cachedTokens * 100.0 / (usage.promptTokens + usage.cachedTokens)).toInt() else 0
                            add(Triple(Icons.Outlined.Bolt, "Cache read" to "${grouped(usage.cachedTokens)}  ($pct% of the input)", c.warning))
                        }
                        if (usage.cacheCreationTokens > 0) add(Triple(Icons.Outlined.Save, "Cache write" to grouped(usage.cacheCreationTokens), c.warning))
                        val total = usage.totalTokens.takeIf { it > 0 }
                            ?: (usage.promptTokens + usage.outputTokens + usage.cachedTokens + usage.cacheCreationTokens)
                        add(Triple(Icons.Outlined.Functions, "Total" to grouped(total), MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)))
                        if (usage.durationMs > 0) add(Triple(Icons.Outlined.Timer, "Time" to durationText(usage.durationMs), MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)))
                        usage.costUsd?.takeIf { it > 0 }?.let { add(Triple(Icons.Outlined.Paid, "Cost" to String.format(java.util.Locale.US, "$%.4f", it), c.accentTeal)) }
                    }
                    rows.forEachIndexed { i, (icon, kv, color) ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                        Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(kv.first, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
                            Text(kv.second, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }

            if (onViewPayload != null) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { onDismiss(); onViewPayload() }, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.DataObject, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("View raw payload")
                }
            }
        }
    }
}
