package ai.antigem.ui.updater

import ai.antigem.i18n.L
import ai.antigem.i18n.LanguageRepo
import ai.antigem.i18n.LanguageUpdateCheck
import ai.antigem.ui.settings.CodeBadge
import ai.antigem.ui.settings.byLine
import ai.antigem.ui.settings.repoErrorText
import ai.antigem.ui.settings.shortDate
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Step { ASK, UPDATING, DONE, FAILED }

/**
 * On launch: a newer version of the community language on screen ([LanguageUpdateCheck]). "Update" downloads it here
 * and the app redraws in the new texts right away; "Not now" skips this version. Closes itself a moment after the
 * update is in.
 */
@Composable
fun LanguageUpdateDialog(offer: LanguageUpdateCheck.Offer, onNotNow: () -> Unit, onDone: () -> Unit) {
    // updated from the Language page, or switched away, while the app's own update dialog was open: nothing to ask
    val wanted = remember(offer) { LanguageUpdateCheck.stillWanted(offer) }
    if (!wanted) {
        LaunchedEffect(offer) { onDone() }
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pack by remember(offer) { mutableStateOf(offer.pack) }
    var step by remember(offer) { mutableStateOf(Step.ASK) }
    var error by remember(offer) { mutableStateOf<Throwable?>(null) }
    val busy = step == Step.UPDATING

    LaunchedEffect(step) {
        if (step == Step.DONE) { delay(1400); onDone() }
    }

    fun update() {
        step = Step.UPDATING
        scope.launch {
            // the file changed on GitHub since the list was read: read the list again, then download what it has now
            if ((error as? LanguageRepo.RepoException)?.kind == LanguageRepo.Kind.CHANGED) {
                LanguageRepo.refresh()
                LanguageRepo.packs.firstOrNull { it.id == pack.id }?.let { pack = it }
            }
            LanguageRepo.install(pack)
                .onSuccess { error = null; step = Step.DONE }
                .onFailure { error = it; step = Step.FAILED }
        }
    }

    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val success = ai.antigem.theme.AppColors.success
    val danger = ai.antigem.theme.AppColors.danger

    Dialog(
        onDismissRequest = { if (!busy) { if (step == Step.DONE) onDone() else onNotNow() } },
        properties = DialogProperties(dismissOnBackPress = !busy, dismissOnClickOutside = !busy)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
        ) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).background(primary.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.Translate, null, tint = primary, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            L.updater.languageUpdate.title, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.semantics { heading() }
                        )
                        Text(L.settings.language.originCommunity, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = primary)
                    }
                }

                Spacer(Modifier.height(16.dp))

                // the language: names, who made it, how much is translated now
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CodeBadge(pack.code, 44.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(pack.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val second = listOf(pack.nativeName.takeIf { it.isNotBlank() && it != pack.name }, pack.variant.takeIf { it.isNotBlank() })
                                    .filterNotNull().joinToString(" · ")
                                if (second.isNotBlank()) Text(second, fontSize = 13.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                byLine(pack.authors)?.let { Text(it, fontSize = 11.5.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        val from = offer.fromPercent?.takeIf { it < pack.percent }
                        Text(
                            if (from != null) L.updater.languageUpdate.percentChange(from, pack.percent)
                            else L.settings.language.percentAndSize(pack.percent, android.text.format.Formatter.formatShortFileSize(context, pack.size)),
                            fontSize = 12.5.sp, fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(6.dp))
                        // fills from the installed version's share to the new one's once it's in
                        val shown by animateFloatAsState(
                            targetValue = (if (step == Step.DONE || from == null) pack.percent else from) / 100f,
                            animationSpec = tween(700), label = "languageUpdateProgress"
                        )
                        LinearProgressIndicator(
                            progress = { shown },
                            modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                            color = if (pack.percent >= 100) success else primary,
                            trackColor = primary.copy(alpha = 0.12f),
                            drawStopIndicator = {}
                        )
                        val facts = listOfNotNull(
                            pack.updated.takeIf { it.isNotBlank() }?.let { L.settings.language.updatedOn(shortDate(it)) },
                            if (from != null) android.text.format.Formatter.formatShortFileSize(context, pack.size) else null
                        )
                        if (facts.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(facts.joinToString("  ·  "), fontSize = 11.5.sp, color = muted)
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                AnimatedContent(
                    targetState = step,
                    transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                    label = "languageUpdateStep"
                ) { s ->
                    when (s) {
                        Step.DONE -> StatusLine(Icons.Rounded.CheckCircle, L.settings.language.updatedToast(pack.label), success)
                        Step.FAILED -> StatusLine(Icons.Rounded.ErrorOutline, repoErrorText(error), danger)
                        else -> Text(L.updater.languageUpdate.about(pack.label), fontSize = 13.sp, lineHeight = 18.sp, color = muted)
                    }
                }

                Spacer(Modifier.height(18.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    if (step == Step.ASK || step == Step.FAILED) {
                        TextButton(onClick = onNotNow, colors = ButtonDefaults.textButtonColors(contentColor = muted)) {
                            Text(L.updater.languageUpdate.notNow, fontSize = 13.5.sp)
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                    Button(
                        onClick = { if (step == Step.DONE) onDone() else update() },
                        enabled = !busy,
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        colors = if (step == Step.DONE) ButtonDefaults.buttonColors(containerColor = success, contentColor = androidx.compose.ui.graphics.Color.White)
                        else ButtonDefaults.buttonColors()
                    ) {
                        when (step) {
                            Step.UPDATING -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                            Step.DONE -> Icon(Icons.Rounded.CheckCircle, null, Modifier.size(17.dp))
                            else -> Icon(Icons.Rounded.Download, null, Modifier.size(17.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when (step) {
                                Step.UPDATING -> L.updater.languageUpdate.updating
                                Step.DONE -> L.common.ok
                                Step.FAILED -> L.common.retry
                                Step.ASK -> L.common.update
                            },
                            fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: androidx.compose.ui.graphics.Color) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.12f)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text, fontSize = 12.5.sp, lineHeight = 17.sp, color = color,
            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }
        )
    }
}
