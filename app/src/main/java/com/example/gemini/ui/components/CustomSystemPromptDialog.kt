package com.example.gemini.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.gemini.theme.ClaudeTerracotta

@Composable
fun CustomSystemPromptDialog(
    initialPrompt: String?,
    onSavePrompt: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    var promptText by remember { mutableStateOf(initialPrompt ?: "") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.72f)
                .padding(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Psychology,
                        contentDescription = null,
                        tint = ClaudeTerracotta,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Chat System Prompt",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Custom system instructions injected for this conversation only:",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = promptText,
                    onValueChange = { promptText = it },
                    placeholder = {
                        Text(
                            text = "e.g. You are an expert Android Kotlin engineer. Write clean Compose code with 0 bugs...",
                            fontSize = 12.5.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    textStyle = LocalTextStyle.current.copy(
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ClaudeTerracotta,
                        unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (promptText.isNotBlank()) {
                        TextButton(
                            onClick = { promptText = "" }
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.RestartAlt,
                                contentDescription = "Clear",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Reset", fontSize = 12.5.sp, color = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        Spacer(modifier = Modifier.width(8.dp))
                    }

                    Row {
                        TextButton(onClick = onDismiss) {
                            Text("Cancel", fontSize = 13.sp)
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Button(
                            onClick = {
                                onSavePrompt(promptText.ifBlank { null })
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Save Prompt", fontSize = 13.sp, color = Color.White)
                        }
                    }
                }
            }
        }
    }
}
