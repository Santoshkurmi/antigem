package ai.antigem.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.domain.model.QueuedMessage
import ai.antigem.theme.AppColors
import ai.antigem.i18n.L
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics

/**
 * Messages sent while the agent was working, waiting to be delivered (above the input box).
 * Each row offers "Send now" (interrupts the current work and delivers it) and "Delete" when the agent allows them.
 */
@Composable
fun QueuedMessagesCard(
    messages: List<QueuedMessage>,
    onSendNow: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val talkBackOn = rememberTalkBackOn()
    AnimatedVisibility(
        visible = messages.isNotEmpty(),
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = AppColors.card,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
        ) {
            Column(modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Schedule,
                        contentDescription = null,
                        tint = AppColors.textSecondary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (messages.size == 1) L.components.queuedMessagesCard.n1QueuedMessage else L.components.queuedMessagesCard.messagescountQueuedMessages(messages.size),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppColors.onBackground,
                        modifier = Modifier.semantics {
                            heading()
                            liveRegion = LiveRegionMode.Polite
                        }
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = L.components.queuedMessagesCard.sendsWhenTheAgentIsDone,
                        fontSize = 12.sp,
                        color = AppColors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                messages.forEach { msg -> QueuedRow(msg, talkBackOn, onSendNow, onDelete) }
            }
        }
    }
}

@Composable
private fun QueuedRow(msg: QueuedMessage, talkBackOn: Boolean, onSendNow: (String) -> Unit, onDelete: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // hidden instructions (Jarvis' note, expanded commands) are not part of what the user wrote
        val label = ai.antigem.data.remote.PromptExpansion.collapse(msg.text).trim().ifBlank {
            L.components.queuedMessagesCard.attachmentsCount(msg.attachmentCount)
        }
        val cleanLabel = label.replace(Regex("\\s+"), " ").trim()
        Text(
            text = cleanLabel,
            fontSize = 13.sp,
            color = if (msg.text.isBlank()) AppColors.textSecondary else AppColors.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 4.dp)
        )
        if (msg.isPending) {
            // sent from here, not confirmed by the agent yet
            CircularProgressIndicator(
                strokeWidth = 1.5.dp,
                color = AppColors.textSecondary,
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .size(14.dp)
                    .clearAndSetSemantics { contentDescription = L.common.loading }
            )
        }
        if (msg.canSendNow) {
            IconButton(
                onClick = { onSendNow(msg.id) },
                modifier = Modifier.a11yMinTouchTarget(talkBackOn).size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.ArrowUpward,
                    contentDescription = L.components.queuedMessagesCard.sendNowValue(cleanLabel.take(60)),
                    tint = AppColors.onBackground,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        if (msg.canDelete) {
            IconButton(
                onClick = { onDelete(msg.id) },
                modifier = Modifier.a11yMinTouchTarget(talkBackOn).size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = L.components.queuedMessagesCard.deleteValue(cleanLabel.take(60)),
                    tint = AppColors.textSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
