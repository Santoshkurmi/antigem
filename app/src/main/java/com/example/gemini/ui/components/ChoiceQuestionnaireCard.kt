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
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.data.remote.dto.AskQuestionOptionDto
import com.example.gemini.data.remote.dto.AskQuestionResponseItemDto
import com.example.gemini.domain.model.*
import com.example.gemini.theme.ClaudeTerracotta
import com.example.gemini.theme.QuotaGreen

/** True where the chat shows unanswered questions in the docked question panel; the card then stays compact. */
val LocalQuestionPanelActive = staticCompositionLocalOf { false }

@Composable
fun ChoiceQuestionnaireCard(
    toolCall: ToolCall,
    onSubmit: (responses: List<AskQuestionResponseItemDto>, summary: String) -> Unit,
    onSkip: (responses: List<AskQuestionResponseItemDto>) -> Unit,
    onCancel: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val questionnaire = toolCall.questionnaire

    val isCompleted = toolCall.status == "SUCCESS" || toolCall.status == "COMPLETED" || toolCall.status == "DONE"

    if (questionnaire == null || questionnaire.questions.isEmpty()) {
        // Fallback display if unparseable
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF1E1F24),
            border = BorderStroke(1.dp, Color(0xFF2C2D33))
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
                        contentDescription = null,
                        tint = ClaudeTerracotta,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Questionnaire",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFE5E7EB)
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = toolCall.command,
                    fontSize = 12.sp,
                    color = Color(0xFF9CA3AF)
                )
            }
        }
        return
    }

    if (LocalQuestionPanelActive.current && !isCompleted) {
        QuestionPanelStatusRow(toolCall, questionnaire, modifier)
        return
    }

    // State for user selections: Map of QuestionId -> Set of selected OptionIds/Labels
    val selectedOptions = remember { mutableStateMapOf<String, Set<String>>() }
    // State for custom write-in answers: Map of QuestionId -> Custom text
    val customAnswers = remember { mutableStateMapOf<String, String>() }
    // State tracking whether "Other" is checked for a QuestionId
    val otherSelected = remember { mutableStateMapOf<String, Boolean>() }

    val hasSelections = selectedOptions.values.any { it.isNotEmpty() } ||
            customAnswers.values.any { it.isNotBlank() }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF1B1C21),
        border = BorderStroke(
            1.2.dp,
            if (isCompleted) QuotaGreen.copy(alpha = 0.5f) else Color(0xFF2E3038)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            if (isCompleted) {
                // Completed Summary State
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = QuotaGreen,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Preferences Submitted",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFE5E7EB)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                val pairs = remember(toolCall.output, questionnaire) {
                    parseAnswerSummary(toolCall.output, questionnaire)
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF141519))
                        .border(BorderStroke(1.dp, Color(0xFF25272E)), RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    pairs.forEachIndexed { index, item ->
                        Column(modifier = Modifier.fillMaxWidth()) {
                            // Question row with Q prefix
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top
                            ) {
                                Text(
                                    text = "Q:",
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF93C5FD),
                                    modifier = Modifier.padding(end = 6.dp)
                                )
                                Text(
                                    text = item.question,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFFE5E7EB),
                                    lineHeight = 17.sp,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            // Answer row with A / arrow prefix
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top
                            ) {
                                Text(
                                    text = "↳ A:",
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = QuotaGreen,
                                    modifier = Modifier.padding(start = 6.dp, end = 6.dp)
                                )
                                Text(
                                    text = item.answer,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color(0xFFD1D5DB),
                                    lineHeight = 17.sp,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        if (index < pairs.lastIndex) {
                            HorizontalDivider(
                                color = Color(0xFF23242A),
                                thickness = 1.dp,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            } else {
                // Interactive Questionnaire matching IMG20260923211226.jpg design
                questionnaire.questions.forEachIndexed { qIndex, question ->
                    val isOtherChecked = otherSelected[question.id] == true
                    val currentCustomText = customAnswers[question.id] ?: ""

                    QuestionBlockView(
                        question = question,
                        questionNumber = qIndex + 1,
                        totalQuestions = questionnaire.questions.size,
                        selectedOptions = selectedOptions,
                        isOtherChecked = isOtherChecked,
                        customText = currentCustomText,
                        onOptionToggled = { optId ->
                            val current = selectedOptions[question.id] ?: emptySet()
                            if (question.isMultiSelect) {
                                val next = if (current.contains(optId)) current - optId else current + optId
                                selectedOptions[question.id] = next
                            } else {
                                otherSelected[question.id] = false
                                selectedOptions[question.id] = if (current.contains(optId)) emptySet() else setOf(optId)
                            }
                        },
                        onOtherToggled = {
                            val nextChecked = !isOtherChecked
                            otherSelected[question.id] = nextChecked
                            if (!question.isMultiSelect && nextChecked) {
                                selectedOptions[question.id] = emptySet()
                            }
                        },
                        onCustomTextChanged = { text ->
                            customAnswers[question.id] = text
                        }
                    )

                    if (qIndex < questionnaire.questions.lastIndex) {
                        Spacer(modifier = Modifier.height(18.dp))
                        HorizontalDivider(
                            color = Color(0xFF26272E),
                            thickness = 1.dp,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Action Bar matching Screenshot (Cancel on left, Skip and Submit on right)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Left: Cancel Button
                    TextButton(
                        onClick = { onCancel?.invoke() },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text(
                            text = "Cancel",
                            fontSize = 12.5.sp,
                            color = Color(0xFF9CA3AF)
                        )
                    }

                    // Right: Skip and Continue/Submit Buttons
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = {
                                val skipResponses = questionnaire.questions.map { q ->
                                    AskQuestionResponseItemDto(
                                        question = q.prompt,
                                        options = q.options.map { AskQuestionOptionDto(id = it.id, text = it.label) },
                                        isMultiSelect = if (q.isMultiSelect) true else null,
                                        skipped = true
                                    )
                                }
                                onSkip(skipResponses)
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text(
                                text = "Skip",
                                fontSize = 12.5.sp,
                                color = Color(0xFF9CA3AF)
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Button(
                            onClick = {
                                val responses = questionnaire.questions.map { q ->
                                    val opts = q.options.map { AskQuestionOptionDto(id = it.id, text = it.label) }
                                    val selected = selectedOptions[q.id] ?: emptySet()
                                    val isOther = otherSelected[q.id] == true
                                    val customText = customAnswers[q.id]?.trim().orEmpty()

                                    if (selected.isEmpty() && (!isOther || customText.isBlank())) {
                                        AskQuestionResponseItemDto(
                                            question = q.prompt,
                                            options = opts,
                                            isMultiSelect = if (q.isMultiSelect) true else null,
                                            skipped = true
                                        )
                                    } else {
                                        AskQuestionResponseItemDto(
                                            question = q.prompt,
                                            options = opts,
                                            isMultiSelect = if (q.isMultiSelect) true else null,
                                            selectedOptionIds = if (selected.isNotEmpty()) selected.toList() else null,
                                            writeInResponse = if (isOther && customText.isNotBlank()) customText else null
                                        )
                                    }
                                }
                                val summary = formatSelectionsSummary(
                                    questionnaire = questionnaire,
                                    selectedOptions = selectedOptions,
                                    otherSelected = otherSelected,
                                    customAnswers = customAnswers
                                )
                                onSubmit(responses, summary)
                            },
                            enabled = hasSelections,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ClaudeTerracotta,
                                disabledContainerColor = ClaudeTerracotta.copy(alpha = 0.3f),
                                contentColor = Color.White,
                                disabledContentColor = Color.White.copy(alpha = 0.5f)
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.Send,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (questionnaire.questions.size > 1) "Submit All" else "Submit",
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

@Composable
private fun QuestionBlockView(
    question: ChoiceQuestion,
    questionNumber: Int,
    totalQuestions: Int,
    selectedOptions: Map<String, Set<String>>,
    isOtherChecked: Boolean,
    customText: String,
    onOptionToggled: (String) -> Unit,
    onOtherToggled: () -> Unit,
    onCustomTextChanged: (String) -> Unit
) {
    val currentSelected = selectedOptions[question.id] ?: emptySet()

    Column(modifier = Modifier.fillMaxWidth()) {
        // Question Header: Icon + Title + Multi-select Pill
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.ChatBubbleOutline,
                contentDescription = null,
                tint = Color(0xFF9CA3AF),
                modifier = Modifier.size(16.dp)
            )

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = question.prompt,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFE5E7EB),
                modifier = Modifier.weight(1f)
            )

            if (question.isMultiSelect) {
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color(0xFF2C2E36),
                    modifier = Modifier.padding(start = 4.dp)
                ) {
                    Text(
                        text = "Multi-select",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF9CA3AF),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            } else if (totalQuestions > 1) {
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color(0xFF2C2E36),
                    modifier = Modifier.padding(start = 4.dp)
                ) {
                    Text(
                        text = "$questionNumber of $totalQuestions",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF9CA3AF),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }

        if (!question.description.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = question.description,
                fontSize = 11.5.sp,
                color = Color(0xFF9CA3AF),
                modifier = Modifier.padding(start = 24.dp)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Options List
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            question.options.forEachIndexed { optIndex, option ->
                val isSelected = currentSelected.contains(option.id) || currentSelected.contains(option.label)

                OptionRow(
                    index = optIndex + 1,
                    label = option.label,
                    description = option.description,
                    isSelected = isSelected,
                    isMultiSelect = question.isMultiSelect,
                    onClick = { onOptionToggled(option.id) }
                )
            }

            // Always render "Other (write your answer)" option
            val otherIndex = question.options.size + 1
            OptionRow(
                index = otherIndex,
                label = "Other (write your answer)",
                description = null,
                isSelected = isOtherChecked,
                isMultiSelect = question.isMultiSelect,
                onClick = onOtherToggled
            )

            // Inline text field revealed when "Other" is checked
            AnimatedVisibility(
                visible = isOtherChecked,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 28.dp, top = 4.dp, bottom = 4.dp)
                ) {
                    OutlinedTextField(
                        value = customText,
                        onValueChange = onCustomTextChanged,
                        placeholder = {
                            Text(
                                text = "Type your custom answer here...",
                                fontSize = 12.sp,
                                color = Color(0xFF6B7280)
                            )
                        },
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontSize = 12.5.sp,
                            color = Color(0xFFE5E7EB)
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3,
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ClaudeTerracotta,
                            unfocusedBorderColor = Color(0xFF374151),
                            focusedContainerColor = Color(0xFF141519),
                            unfocusedContainerColor = Color(0xFF141519)
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun OptionRow(
    index: Int,
    label: String,
    description: String?,
    isSelected: Boolean,
    isMultiSelect: Boolean,
    onClick: () -> Unit
) {
    val bgColor = if (isSelected) Color(0xFF2C2F38) else Color(0xFF1E2026)
    val borderColor = if (isSelected) ClaudeTerracotta.copy(alpha = 0.8f) else Color(0xFF272931)

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
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Number Box [ 1 ], [ 2 ], etc. matching screenshot
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = if (isSelected) ClaudeTerracotta else Color(0xFF2A2D36),
                modifier = Modifier.size(20.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "$index",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isSelected) Color.White else Color(0xFFD1D5DB)
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            // Option Label
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    fontSize = 12.5.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) Color(0xFFF3F4F6) else Color(0xFFD1D5DB)
                )
                if (!description.isNullOrBlank()) {
                    Text(
                        text = description,
                        fontSize = 10.5.sp,
                        color = Color(0xFF9CA3AF)
                    )
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Checkbox / Radio indicator
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(if (isMultiSelect) RoundedCornerShape(4.dp) else CircleShape)
                    .background(if (isSelected) ClaudeTerracotta else Color.Transparent)
                    .border(
                        1.2.dp,
                        if (isSelected) ClaudeTerracotta else Color(0xFF4B5563),
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
        }
    }
}

internal fun formatSelectionsSummary(
    questionnaire: ChoiceQuestionnaire,
    selectedOptions: Map<String, Set<String>>,
    otherSelected: Map<String, Boolean>,
    customAnswers: Map<String, String>
): String {
    val builder = StringBuilder()
    builder.append("[User Clarification & Preferences]:\n")

    questionnaire.questions.forEach { q ->
        val selectedIds = selectedOptions[q.id] ?: emptySet()
        val selectedOpts = q.options.filter { selectedIds.contains(it.id) || selectedIds.contains(it.label) }
        val optLabels = selectedOpts.map { it.label }.toMutableList()

        if (otherSelected[q.id] == true) {
            val customText = customAnswers[q.id]?.trim()
            if (!customText.isNullOrBlank()) {
                optLabels.add("Other: \"$customText\"")
            } else {
                optLabels.add("Other")
            }
        }

        if (optLabels.isNotEmpty()) {
            builder.append("• ${q.prompt}: ${optLabels.joinToString(", ")}\n")
        }
    }

    return builder.toString().trim()
}

private data class QuestionAnswerSummary(
    val question: String,
    val answer: String
)

private fun parseAnswerSummary(output: String, questionnaire: ChoiceQuestionnaire?): List<QuestionAnswerSummary> {
    if (output.isBlank() && questionnaire == null) return emptyList()

    val results = mutableListOf<QuestionAnswerSummary>()
    val cleanOutput = output
        .replace("[User Clarification & Preferences]:", "")
        .trim()

    // 1. If questionnaire is known, match answers for each question
    if (questionnaire != null && questionnaire.questions.isNotEmpty()) {
        val lines = cleanOutput.lines().map { it.trim().removePrefix("•").trim() }.filter { it.isNotBlank() }

        for (q in questionnaire.questions) {
            val promptClean = q.prompt.trim()
            val matchingLine = lines.find { line ->
                line.startsWith(promptClean, ignoreCase = true) || line.contains(promptClean, ignoreCase = true)
            }
            if (matchingLine != null) {
                val ansPart = matchingLine.substringAfter(":").trim()
                results.add(QuestionAnswerSummary(promptClean, ansPart.ifEmpty { "Selected" }))
            } else {
                val idx = cleanOutput.indexOf(promptClean, ignoreCase = true)
                if (idx >= 0) {
                    val after = cleanOutput.substring(idx + promptClean.length).trimStart()
                    val afterColon = if (after.startsWith(":")) after.substring(1).trimStart() else after
                    val ans = afterColon.lines().firstOrNull()?.trim() ?: ""
                    results.add(QuestionAnswerSummary(promptClean, ans.ifEmpty { "Selected" }))
                }
            }
        }
    }

    // 2. If nothing matched or no questionnaire, parse by lines containing ':' or '•'
    if (results.isEmpty()) {
        val lines = cleanOutput.lines().map { it.trim().removePrefix("•").trim() }.filter { it.isNotBlank() }
        for (line in lines) {
            if (line.contains(":")) {
                val q = line.substringBefore(":").trim()
                val a = line.substringAfter(":").trim()
                if (q.isNotBlank() && a.isNotBlank()) {
                    results.add(QuestionAnswerSummary(q, a))
                }
            }
        }
    }

    // 3. Fallback: single entry
    if (results.isEmpty()) {
        results.add(
            QuestionAnswerSummary(
                question = questionnaire?.questions?.firstOrNull()?.prompt ?: questionnaire?.title ?: "Preferences",
                answer = output.ifBlank { "Answer submitted" }
            )
        )
    }

    return results
}


/** Compact in-chat state of a question answered through the docked panel. */
@Composable
private fun QuestionPanelStatusRow(toolCall: ToolCall, questionnaire: ChoiceQuestionnaire, modifier: Modifier) {
    val waiting = toolCall.status == "AWAITING_CHOICE"
    val (title, tint) = when {
        waiting -> "Waiting for your answer" to ClaudeTerracotta
        toolCall.status == "RUNNING" -> "Answer sent" to QuotaGreen
        else -> "Question dismissed" to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    }
    Surface(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = tint.copy(alpha = 0.06f),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.3f))
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
            Icon(
                if (waiting) Icons.AutoMirrored.Outlined.HelpOutline else Icons.Default.CheckCircle,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(17.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                questionnaire.questions.take(3).forEach { q ->
                    Text(
                        "• ${q.prompt}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
                if (questionnaire.questions.size > 3) {
                    Text("+${questionnaire.questions.size - 3} more", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                }
                if (waiting) {
                    Text("Answer in the panel above the message box.", fontSize = 11.5.sp, color = tint, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}
