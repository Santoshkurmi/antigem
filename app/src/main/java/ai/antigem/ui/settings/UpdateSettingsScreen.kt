package ai.antigem.ui.settings

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.BuildConfig
import ai.antigem.data.updater.AppUpdateInfo
import ai.antigem.data.updater.AppUpdateManager
import ai.antigem.data.updater.BackgroundUpdateCheck
import ai.antigem.data.updater.BetaUpdates
import ai.antigem.theme.AppColors
import ai.antigem.theme.ClaudeTerracotta
import ai.antigem.theme.QuotaGreen
import ai.antigem.ui.components.bouncyVerticalScroll
import ai.antigem.ui.updater.AppUpdateDialog
import kotlinx.coroutines.launch
import ai.antigem.i18n.L

/**
 * Settings > Updates: beta updates ([BetaUpdates]) and how often AntiGem checks for a new version in the background
 * ([BackgroundUpdateCheck]). The check when the app opens is always on and not set here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UpdateSettingsScreen(cardBg: Color, cardBorder: BorderStroke) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var updateInfo by remember { mutableStateOf<AppUpdateInfo?>(null) }

    var betaUpdates by remember { mutableStateOf(BetaUpdates.isEnabled(context)) }
    var showBetaConfirm by remember { mutableStateOf(false) }

    val saved = remember { BackgroundUpdateCheck.get(context) }
    var backgroundOn by remember { mutableStateOf(saved.enabled) }
    var everyText by remember { mutableStateOf(saved.every.toString()) }
    var unit by remember { mutableStateOf(saved.unit) }
    // the last valid number, saved while the field is empty or out of range
    var every by remember { mutableStateOf(saved.every) }
    val everyValid = everyText.toIntOrNull()?.takeIf { it in 1..BackgroundUpdateCheck.MAX_EVERY } != null

    fun saveInterval() = BackgroundUpdateCheck.set(context, BackgroundUpdateCheck.Interval(backgroundOn, every, unit))

    updateInfo?.let { info -> AppUpdateDialog(updateInfo = info, onDismiss = { updateInfo = null }) }

    if (showBetaConfirm) {
        AlertDialog(
            onDismissRequest = { showBetaConfirm = false },
            title = { Text(L.settings.updateSettings.getBetaUpdates) },
            text = {
                Text(
                    L.settings.updateSettings.betasAreEarlyVersionsForTesting
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        BetaUpdates.setEnabled(context, true)
                        betaUpdates = true
                        showBetaConfirm = false
                        // look for a beta right away instead of at the next check
                        scope.launch {
                            AppUpdateManager(context).checkForUpdates(forceCheck = true).onSuccess { info ->
                                if (info != null) updateInfo = info
                                else Toast.makeText(context, L.settings.updateSettings.betaUpdatesOnNothingNewerRight, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                ) {
                    Text(L.settings.updateSettings.turnOn, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBetaConfirm = false }) {
                    Text(L.common.cancel)
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .bouncyVerticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Beta updates
        UpdatesCard(cardBg, cardBorder) {
            CardTitle(Icons.Outlined.NewReleases, L.settings.updateSettings.betaUpdates)
            SwitchRow(
                title = L.settings.updateSettings.getBetaUpdates2,
                subtitle = L.settings.updateSettings.earlyVersionsWithNewFeaturesBefore,
                checked = betaUpdates,
                onCheckedChange = { on ->
                    if (on) {
                        // already on a beta: nothing new to warn about
                        if (BuildConfig.IS_BETA) {
                            BetaUpdates.setEnabled(context, true)
                            betaUpdates = true
                        } else {
                            showBetaConfirm = true
                        }
                    } else {
                        BetaUpdates.setEnabled(context, false)
                        betaUpdates = false
                        if (BuildConfig.IS_BETA) Toast.makeText(
                            context,
                            L.settings.updateSettings.noMoreBetasYouStayOn,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            )
            Text(
                text = when {
                    BuildConfig.IS_BETA && betaUpdates -> L.settings.updateSettings.youreOnABetaVVersion(BuildConfig.VERSION_NAME)
                    BuildConfig.IS_BETA -> L.settings.updateSettings.youreOnABetaVVersion2(BuildConfig.VERSION_NAME)
                    betaUpdates -> L.settings.updateSettings.youGetEachNewBetaAs
                    else -> L.settings.updateSettings.youGetTheStableReleasesOnly
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp
            )
        }

        // Background checks
        UpdatesCard(cardBg, cardBorder) {
            CardTitle(Icons.Outlined.Schedule, L.settings.updateSettings.backgroundChecks)
            SwitchRow(
                title = L.settings.updateSettings.checkInTheBackground,
                subtitle = L.settings.updateSettings.notifiesYouWhenANewVersion,
                checked = backgroundOn,
                onCheckedChange = { on ->
                    backgroundOn = on
                    saveInterval()
                }
            )
            if (backgroundOn) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(L.settings.updateSettings.every, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                    OutlinedTextField(
                        value = everyText,
                        onValueChange = { text ->
                            everyText = text.filter(Char::isDigit).take(3)
                            everyText.toIntOrNull()?.takeIf { it in 1..BackgroundUpdateCheck.MAX_EVERY }?.let {
                                every = it
                                saveInterval()
                            }
                        },
                        singleLine = true,
                        isError = !everyValid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(76.dp)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BackgroundUpdateCheck.IntervalUnit.entries.forEach { u ->
                        FilterChip(
                            selected = unit == u,
                            onClick = {
                                unit = u
                                saveInterval()
                            },
                            label = { Text(u.label) }
                        )
                    }
                }
                if (!everyValid) Text(
                    text = L.settings.updateSettings.enterANumberFrom1To(BackgroundUpdateCheck.MAX_EVERY),
                    fontSize = 12.sp,
                    color = AppColors.danger
                )
            }
            Text(
                text = if (backgroundOn) L.settings.updateSettings.antigemChecksDescribeInTheBackground(describe(every, unit))
                else L.settings.updateSettings.noBackgroundChecksAntigemStillChecks,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp
            )
        }
    }
}

/** "every hour", "every 12 hours", "every 2 weeks". */
private fun describe(every: Int, unit: BackgroundUpdateCheck.IntervalUnit): String {
    val name = unit.label.lowercase()
    return if (every == 1) "every ${name.removeSuffix("s")}" else "every $every $name"
}

@Composable
private fun UpdatesCard(cardBg: Color, cardBorder: BorderStroke, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        border = cardBorder
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            content()
        }
    }
}

@Composable
private fun CardTitle(icon: ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(imageVector = icon, contentDescription = null, tint = ClaudeTerracotta, modifier = Modifier.size(20.dp))
        Text(text = title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                text = subtitle,
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                lineHeight = 15.sp
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = QuotaGreen)
        )
    }
}
