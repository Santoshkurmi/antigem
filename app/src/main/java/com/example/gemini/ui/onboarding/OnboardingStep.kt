package com.example.gemini.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * One full-screen step of the first-launch setup (agents, then the terminal icon): a progress bar with "Step x of y",
 * the step's icon, title and text, its content (scrolls), and the primary button fixed at the bottom.
 * [step] / [total]: 1-based; with one step the progress row is left out.
 */
@Composable
fun OnboardingStepScreen(
    step: Int,
    total: Int,
    accent: Color,
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    primaryLabel: String,
    primaryEnabled: Boolean = true,
    onPrimary: () -> Unit,
    footnote: String? = null,
    onBack: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(
        onDismissRequest = { onBack?.invoke() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = onBack != null,
            dismissOnClickOutside = false
        )
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.10f), Color.Transparent, Color.Transparent)))
                    .statusBarsPadding()
                    .navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // progress
                if (total > 1) {
                    Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            for (i in 1..total) {
                                Box(
                                    Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp))
                                        .background(if (i <= step) accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "STEP $step OF $total", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                } else {
                    Spacer(Modifier.height(18.dp))
                }

                // header + content
                Column(
                    Modifier.weight(1f).widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.height(18.dp))
                    Box(
                        Modifier.size(72.dp).clip(CircleShape)
                            .background(Brush.radialGradient(listOf(accent.copy(alpha = 0.28f), accent.copy(alpha = 0.06f)))),
                        contentAlignment = Alignment.Center
                    ) { icon() }
                    Spacer(Modifier.height(16.dp))
                    Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        subtitle, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                    )
                    Spacer(Modifier.height(22.dp))
                    content()
                    Spacer(Modifier.height(20.dp))
                }

                // bottom action
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                Column(
                    Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Button(
                        onClick = onPrimary,
                        enabled = primaryEnabled,
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White),
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) { Text(primaryLabel, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold) }
                    footnote?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, fontSize = 11.5.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    }
                }
            }
        }
    }
}
