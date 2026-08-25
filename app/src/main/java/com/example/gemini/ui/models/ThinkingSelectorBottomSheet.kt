package com.example.gemini.ui.models

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.domain.model.ThinkingLevel
import com.example.gemini.domain.model.ThinkingPreference
import com.example.gemini.theme.ClaudeTerracotta

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThinkingSelectorBottomSheet(
    currentPreference: ThinkingPreference,
    onPreferenceSelected: (ThinkingPreference) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedLevel by remember { mutableStateOf(currentPreference.level) }
    var customTokensText by remember { mutableStateOf(currentPreference.customTokens.toString()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Psychology,
                    contentDescription = null,
                    tint = ClaudeTerracotta,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Thinking & Reasoning Level",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Configure reasoning depth and token budget",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Level Options
            ThinkingLevel.values().forEach { level ->
                val isSelected = selectedLevel == level
                val borderColor = if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                val bgColor = if (isSelected) ClaudeTerracotta.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable {
                            selectedLevel = level
                            if (level != ThinkingLevel.CUSTOM) {
                                onPreferenceSelected(ThinkingPreference(level = level))
                                onDismiss()
                            }
                        },
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(if (isSelected) 1.5.dp else 1.dp, borderColor),
                    colors = CardDefaults.cardColors(containerColor = bgColor)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = level.label,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface
                            )

                            if (level == ThinkingLevel.MEDIUM) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(ClaudeTerracotta.copy(alpha = 0.15f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "DEFAULT",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = ClaudeTerracotta
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.weight(1f))

                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Outlined.Check,
                                    contentDescription = "Selected",
                                    tint = ClaudeTerracotta,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(2.dp))

                        Text(
                            text = level.description,
                            fontSize = 12.5.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                        )

                        // Custom Token Input Field
                        if (level == ThinkingLevel.CUSTOM && isSelected) {
                            Spacer(modifier = Modifier.height(10.dp))
                            OutlinedTextField(
                                value = customTokensText,
                                onValueChange = { customTokensText = it.filter { char -> char.isDigit() } },
                                label = { Text("Token Budget (1 - 32,768)") },
                                placeholder = { Text("e.g. 2048") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                supportingText = {
                                    val tokens = customTokensText.toIntOrNull() ?: 0
                                    val chars = tokens * 4
                                    Text("Allocates ~$chars characters of reasoning capacity")
                                }
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Button(
                                onClick = {
                                    val tokens = (customTokensText.toIntOrNull() ?: 2048).coerceIn(1, 32768)
                                    onPreferenceSelected(ThinkingPreference(level = ThinkingLevel.CUSTOM, customTokens = tokens))
                                    onDismiss()
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Apply Custom Budget", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}
