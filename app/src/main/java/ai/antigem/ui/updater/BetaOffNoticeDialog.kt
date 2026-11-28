package ai.antigem.ui.updater

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import ai.antigem.BuildConfig
import ai.antigem.theme.ClaudeTerracotta
import ai.antigem.i18n.L

/**
 * Shown once, on the first start of the stable version that turned beta updates off by itself
 * ([ai.antigem.data.updater.BetaUpdates.offNoticeDue]): what happened, why, and how to get betas again.
 */
@Composable
fun BetaOffNoticeDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(L.updater.betaOffNotice.betaUpdatesTurnedOff) },
        text = { Text(L.updater.betaOffNotice.youreNowOnTheStableVersion(BuildConfig.VERSION_NAME)) },
        confirmButton = {
            Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)) {
                Text(L.common.ok, fontWeight = FontWeight.Bold)
            }
        }
    )
}
