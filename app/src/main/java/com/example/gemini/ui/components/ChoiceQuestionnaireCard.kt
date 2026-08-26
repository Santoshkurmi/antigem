package com.example.gemini.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.domain.model.*
import com.example.gemini.theme.ClaudeTerracotta
import com.example.gemini.theme.QuotaGreen

@Composable
fun ChoiceQuestionnaireCard(
    toolCall: ToolCall,
    onSubmit: (summary: String) -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
    val questionnaire = remember(toolCall.command) {
        ChoiceQuestionnaire.parse(toolCall.command)
    }

    val isCompleted = toolCall.status == "SUCCESS" || toolCall.status == "COMPLETED"

    if (questionnaire == null || questionnaire.questions.isEmpty()) {
        // Fallback simple display if unparseable
        Surface(
            modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
        ) {
            Text(
                text = toolCall.command,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp)
            )
        }
        return
    }

    // State for user selections: Map of QuestionId -> Set of selected OptionIds
    val selectedOptions = remember { mutableStateMapOf<String, Set<String>>() }
    var customNote by remember { mutableStateOf("") }
    var isExpanded by remember { mutableStateOf(true) }

    val hasSelections = selectedOptions.values.any { it.isNotEmpty() } || customNote.isNotBlank()

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        shape = RoundedCornerShape(14.dp),
        color = if (isCompleted) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(
            1.2.dp,
            if (isCompleted) QuotaGreen.copy(alpha = 0.4f) else ClaudeTerracotta.copy(alpha = 0.65f)
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                    .clickable { isExpanded = !isExpanded }
                    .background(if (isCompleted) QuotaGreen.copy(alpha = 0.12f) else ClaudeTerracotta.copy(alpha = 0.12f))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isCompleted) Icons.Default.CheckCircle else Icons.Outlined.HelpOutline,
                    contentDescription = null,
                    tint = if (isCompleted) QuotaGreen else ClaudeTerracotta,
                    modifier = Modifier.size(18.dp)
                )

                Spacer(modifier = Modifier.width(8.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isCompleted) "Preferences Provided" else questionnaire.title,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (!isCompleted && !questionnaire.description.isNullOrBlank()) {
                        Text(
                            text = questionnaire.description,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (!isCompleted) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(ClaudeTerracotta.copy(alpha = 0.18f))
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "${questionnaire.questions.size} Questions",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = ClaudeTerracotta
                        )
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))

                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }

            // Body
            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                if (isCompleted) {
                    // Show completed summary
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = toolCall.output.ifEmpty { "Selection submitted to AI." },
                            fontSize = 12.5.sp,
                            lineHeight = 18.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                } else {
                    // Interactive Questionnaire Form
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp)
                    ) {
                        questionnaire.questions.forEachIndexed { index, question ->
                            QuestionBlock(
                                question = question,
                                questionIndex = index + 1,
                                selectedOptions = selectedOptions,
                                depth = 1
                            )
                            if (index < questionnaire.questions.lastIndex) {
                                Spacer(modifier = Modifier.height(14.dp))
                            }
                        }

                        // Custom Instruction Note
                        if (questionnaire.allowCustomNote) {
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = "Additional Instructions (Optional):",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedTextField(
                                value = customNote,
                                onValueChange = { customNote = it },
                                placeholder = { Text("e.g., Use Kotlin Compose, include dark theme...", fontSize = 12.sp) },
                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.5.sp),
                                modifier = Modifier.fillMaxWidth(),
                                maxLines = 2,
                                shape = RoundedCornerShape(8.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Action Buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = onSkip,
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text(text = "Skip / Free Form", fontSize = 11.5.sp)
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            Button(
                                onClick = {
                                    val summary = formatSelectionsSummary(questionnaire, selectedOptions, customNote)
                                    onSubmit(summary)
                                },
                                enabled = hasSelections,
                                colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Icon(imageVector = Icons.Outlined.Send, contentDescription = null, modifier = Modifier.size(13.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Submit Choices",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuestionBlock(
    question: ChoiceQuestion,
    questionIndex: Int,
    selectedOptions: MutableMap<String, Set<String>>,
    depth: Int
) {
    val currentSelected = selectedOptions[question.id] ?: emptySet()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (depth > 1) 12.dp else 0.dp)
    ) {
        // Question Title
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (depth == 1) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(ClaudeTerracotta.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "$questionIndex",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = ClaudeTerracotta
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
            } else {
                Icon(
                    imageVector = Icons.Default.SubdirectoryArrowRight,
                    contentDescription = null,
                    tint = ClaudeTerracotta.copy(alpha = 0.7f),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
            }

            Text(
                text = question.prompt,
                fontSize = if (depth == 1) 13.sp else 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        if (!question.description.isNullOrBlank()) {
            Text(
                text = question.description,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = if (depth == 1) 24.dp else 18.dp, top = 2.dp)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Options List / Grid
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = if (depth == 1) 8.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            question.options.forEach { option ->
                val isSelected = currentSelected.contains(option.id)

                OptionCard(
                    option = option,
                    isSelected = isSelected,
                    isMultiSelect = question.isMultiSelect,
                    onClick = {
                        val newSet = if (question.isMultiSelect) {
                            if (isSelected) currentSelected - option.id else currentSelected + option.id
                        } else {
                            if (isSelected) emptySet() else setOf(option.id)
                        }
                        selectedOptions[question.id] = newSet
                    }
                )

                // Render Nested Questions if option is selected and has nested branch
                if (isSelected && option.nestedQuestions.isNotEmpty() && depth < 4) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp, bottom = 4.dp)
                            .border(
                                BorderStroke(1.dp, ClaudeTerracotta.copy(alpha = 0.3f)),
                                RoundedCornerShape(8.dp)
                            )
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.35f))
                            .padding(8.dp)
                    ) {
                        option.nestedQuestions.forEachIndexed { nestedIdx, nestedQ ->
                            QuestionBlock(
                                question = nestedQ,
                                questionIndex = nestedIdx + 1,
                                selectedOptions = selectedOptions,
                                depth = depth + 1
                            )
                            if (nestedIdx < option.nestedQuestions.lastIndex) {
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OptionCard(
    option: ChoiceOption,
    isSelected: Boolean,
    isMultiSelect: Boolean,
    onClick: () -> Unit
) {
    val bgColor = if (isSelected) ClaudeTerracotta.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    val borderColor = if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(8.dp),
        color = bgColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Radio or Checkbox indicator
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(if (isMultiSelect) RoundedCornerShape(4.dp) else CircleShape)
                    .background(if (isSelected) ClaudeTerracotta else Color.Transparent)
                    .border(
                        1.2.dp,
                        if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        if (isMultiSelect) RoundedCornerShape(4.dp) else CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(11.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = option.label,
                    fontSize = 12.5.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface
                )
                if (!option.description.isNullOrBlank()) {
                    Text(
                        text = option.description,
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (option.nestedQuestions.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(ClaudeTerracotta.copy(alpha = 0.12f))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "+${option.nestedQuestions.size} details",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = ClaudeTerracotta
                    )
                }
            }
        }
    }
}

private fun formatSelectionsSummary(
    questionnaire: ChoiceQuestionnaire,
    selectedOptions: Map<String, Set<String>>,
    customNote: String
): String {
    val builder = StringBuilder()
    builder.append("[User Clarification & Preferences for \"${questionnaire.title}\"]:\n")

    fun formatQuestion(question: ChoiceQuestion, indent: String) {
        val selectedIds = selectedOptions[question.id] ?: emptySet()
        if (selectedIds.isEmpty()) return

        val selectedOpts = question.options.filter { selectedIds.contains(it.id) }
        val labels = selectedOpts.map { it.label }.joinToString(", ")
        builder.append("$indent• ${question.prompt}: $labels\n")

        selectedOpts.forEach { opt ->
            opt.nestedQuestions.forEach { nestedQ ->
                formatQuestion(nestedQ, "$indent  └ ")
            }
        }
    }

    questionnaire.questions.forEach { q ->
        formatQuestion(q, "")
    }

    if (customNote.isNotBlank()) {
        builder.append("• Additional User Guidance: \"$customNote\"\n")
    }

    return builder.toString().trim()
}
