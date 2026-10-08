package com.example.gemini.ui.agents

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.data.remote.dto.AskQuestionOptionDto
import com.example.gemini.data.remote.dto.AskQuestionResponseItemDto
import com.example.gemini.domain.model.AgentKind
import com.example.gemini.domain.model.ChoiceQuestion
import com.example.gemini.domain.model.ToolCall
import com.example.gemini.domain.model.ToolType
import com.example.gemini.domain.model.ChatMessage
import com.example.gemini.ui.components.accent
import com.example.gemini.ui.components.formatSelectionsSummary
import com.example.gemini.ui.components.label

/** A question an agent is waiting on (Claude's AskUserQuestion, Antigravity's ask-choice step). */
class PendingQuestion(val agent: AgentKind, val toolCall: ToolCall, val messageId: String)

/** The newest unanswered question in [messages], if any. */
fun findPendingQuestion(agent: AgentKind, messages: List<ChatMessage>): PendingQuestion? {
    for (msg in messages.asReversed()) {
        val call = msg.toolCalls.lastOrNull { it.toolType == ToolType.ASK_CHOICE && it.status == "AWAITING_CHOICE" && it.questionnaire?.questions?.isNotEmpty() == true }
        if (call != null) return PendingQuestion(agent, call, msg.id)
    }
    return null
}

/**
 * Question panel docked above the input: one question per page with option cards (single or multi select),
 * an "Other" answer, and skip / back / next / submit. Answers use the same format as the in-chat card.
 */
@Composable
fun AgentQuestionPanel(
    question: PendingQuestion,
    onSubmit: (responses: List<AskQuestionResponseItemDto>, summary: String) -> Unit,
    onSkip: (responses: List<AskQuestionResponseItemDto>) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val questionnaire = question.toolCall.questionnaire ?: return
    val questions = questionnaire.questions
    if (questions.isEmpty()) return
    val accent = question.agent.accent()

    // state survives recomposition but resets for the next question set
    val selected = remember(question.toolCall.id) { mutableStateMapOf<String, Set<String>>() }
    val otherOn = remember(question.toolCall.id) { mutableStateMapOf<String, Boolean>() }
    val otherText = remember(question.toolCall.id) { mutableStateMapOf<String, String>() }
    var page by remember(question.toolCall.id) { mutableIntStateOf(0) }
    val current = questions[page.coerceIn(0, questions.lastIndex)]

    fun answered(q: ChoiceQuestion) = selected[q.id].orEmpty().isNotEmpty() || (otherOn[q.id] == true && otherText[q.id].orEmpty().isNotBlank())

    fun responses(skipAll: Boolean) = questions.map { q ->
        val options = q.options.map { AskQuestionOptionDto(id = it.id, text = it.label) }
        val picked = selected[q.id].orEmpty()
        val write = otherText[q.id]?.trim().orEmpty().takeIf { otherOn[q.id] == true && it.isNotBlank() }
        if (skipAll || (picked.isEmpty() && write == null)) {
            AskQuestionResponseItemDto(question = q.prompt, options = options, isMultiSelect = if (q.isMultiSelect) true else null, skipped = true)
        } else {
            AskQuestionResponseItemDto(
                question = q.prompt,
                options = options,
                isMultiSelect = if (q.isMultiSelect) true else null,
                selectedOptionIds = picked.toList().ifEmpty { null },
                writeInResponse = write
            )
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.38f)),
        shadowElevation = 6.dp
    ) {
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 10.dp)) {
            // header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.AutoMirrored.Outlined.HelpOutline, null, tint = accent, modifier = Modifier.size(19.dp)) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("${question.agent.label()} has ${if (questions.size > 1) "${questions.size} questions" else "a question"}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    if (questions.size > 1) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
                            questions.forEachIndexed { i, q ->
                                Box(
                                    Modifier
                                        .padding(end = 4.dp)
                                        .size(width = if (i == page) 16.dp else 6.dp, height = 6.dp)
                                        .clip(CircleShape)
                                        .background(
                                            when {
                                                i == page -> accent
                                                answered(q) -> accent.copy(alpha = 0.45f)
                                                else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
                                            }
                                        )
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                            Text("${page + 1} of ${questions.size}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                        }
                    }
                }
                IconButton(onClick = onCancel, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Outlined.Close, "Dismiss question", tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f), modifier = Modifier.size(18.dp))
                }
            }

            Spacer(Modifier.height(10.dp))
            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    val forward = targetState > initialState
                    (slideInHorizontally { if (forward) it / 3 else -it / 3 } + fadeIn()) togetherWith
                        (slideOutHorizontally { if (forward) -it / 3 else it / 3 } + fadeOut())
                },
                label = "question_page"
            ) { index ->
                val q = questions[index.coerceIn(0, questions.lastIndex)]
                Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                    q.description?.takeIf { it.isNotBlank() }?.let { d ->
                        if (d.length <= 28) {
                            Text(
                                d.uppercase(),
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp,
                                color = accent,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                        }
                    }
                    Text(q.prompt, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp)
                    q.description?.takeIf { it.length > 28 }?.let {
                        Text(it, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.padding(top = 2.dp))
                    }
                    Text(
                        if (q.isMultiSelect) "Choose any" else "Choose one",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        q.options.forEach { opt ->
                            val isOn = opt.id in selected[q.id].orEmpty()
                            OptionCard(opt.label, opt.description, isOn, q.isMultiSelect, accent) {
                                val cur = selected[q.id].orEmpty()
                                if (q.isMultiSelect) {
                                    selected[q.id] = if (isOn) cur - opt.id else cur + opt.id
                                } else {
                                    selected[q.id] = if (isOn) emptySet() else setOf(opt.id)
                                    otherOn[q.id] = false
                                }
                            }
                        }
                        if (q.allowCustomAnswer) {
                            val isOn = otherOn[q.id] == true
                            OptionCard("Other…", null, isOn, q.isMultiSelect, accent) {
                                otherOn[q.id] = !isOn
                                if (!q.isMultiSelect && !isOn) selected[q.id] = emptySet()
                            }
                            AnimatedVisibility(isOn, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                                OutlinedTextField(
                                    value = otherText[q.id].orEmpty(),
                                    onValueChange = { otherText[q.id] = it },
                                    placeholder = { Text("Your answer", fontSize = 13.sp) },
                                    shape = RoundedCornerShape(12.dp),
                                    textStyle = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.fillMaxWidth(),
                                    maxLines = 4
                                )
                            }
                        }
                    }
                }
            }

            // footer
            Spacer(Modifier.height(8.dp))
            val isLast = page >= questions.lastIndex
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onSkip(responses(skipAll = true)) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(if (questions.size > 1) "Skip all" else "Skip", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
                Spacer(Modifier.weight(1f))
                if (page > 0) {
                    TextButton(onClick = { page-- }) { Text("Back", fontSize = 13.sp) }
                    Spacer(Modifier.width(4.dp))
                }
                val canSubmit = questions.any { answered(it) }
                Button(
                    onClick = {
                        if (!isLast) {
                            page++
                        } else {
                            onSubmit(responses(skipAll = false), formatSelectionsSummary(questionnaire, selected, otherOn, otherText))
                        }
                    },
                    enabled = !isLast || canSubmit,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White),
                    contentPadding = PaddingValues(horizontal = 18.dp)
                ) {
                    Text(
                        when {
                            !isLast -> if (answered(current)) "Next" else "Skip question"
                            else -> "Submit"
                        },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun OptionCard(label: String, description: String?, selected: Boolean, multi: Boolean, accent: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Surface(
        onClick = onClick,
        shape = shape,
        color = if (selected) accent.copy(alpha = 0.1f) else Color.Transparent,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) accent.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            // radio / checkbox
            val indicatorShape = if (multi) RoundedCornerShape(5.dp) else CircleShape
            Box(
                Modifier
                    .size(18.dp)
                    .clip(indicatorShape)
                    .background(if (selected) accent else Color.Transparent)
                    .border(1.5.dp, if (selected) accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f), indicatorShape),
                contentAlignment = Alignment.Center
            ) {
                if (selected) {
                    if (multi) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(13.dp))
                    else Box(Modifier.size(7.dp).clip(CircleShape).background(Color.White))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 13.5.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
                description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
        }
    }
}
