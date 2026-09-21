package com.example.gemini.ui.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.gemini.theme.*

object PermissionUtils {

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }

    fun hasStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasAudioPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }
}

@Composable
fun AppPermissionsDialog(
    onDismissOrCompleted: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isDark = isAppInDarkTheme() || isSystemInDarkTheme()

    var hasNotification by remember { mutableStateOf(PermissionUtils.hasNotificationPermission(context)) }
    var hasStorage by remember { mutableStateOf(PermissionUtils.hasStoragePermission(context)) }
    var hasAudio by remember { mutableStateOf(PermissionUtils.hasAudioPermission(context)) }

    BackHandler(enabled = hasNotification) {
        onDismissOrCompleted()
    }

    // Re-check permissions when returning from system settings
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasNotification = PermissionUtils.hasNotificationPermission(context)
                hasStorage = PermissionUtils.hasStoragePermission(context)
                hasAudio = PermissionUtils.hasAudioPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Permission launchers
    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotification = granted || PermissionUtils.hasNotificationPermission(context)
        if (!granted && !PermissionUtils.hasNotificationPermission(context)) {
            try {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (_: Exception) {}
            }
        }
    }

    val audioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasAudio = granted || PermissionUtils.hasAudioPermission(context)
    }

    val storageLegacyLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasStorage = PermissionUtils.hasStoragePermission(context)
    }

    fun requestStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (_: Exception) {}
            }
        } else {
            storageLegacyLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
    }

    val screenBg = if (isDark) ClaudeDarkBg else ClaudeCream
    val textPrimary = if (isDark) TextPrimaryDark else TextPrimaryLight
    val textSecondary = if (isDark) TextSecondaryDark else TextSecondaryLight

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(screenBg),
        color = screenBg
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 20.dp)
        ) {
            // Welcoming Header
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(ClaudeTerracotta.copy(alpha = if (isDark) 0.16f else 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Shield,
                        contentDescription = null,
                        tint = ClaudeTerracotta,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Welcome to AntiGem",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = textPrimary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Configure your permissions to enable background execution, terminal tools, and local project storage.",
                    fontSize = 13.5.sp,
                    lineHeight = 19.sp,
                    color = textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }

            // Scrollable List of Permission Cards
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 1. NOTIFICATIONS (Compulsory)
                PermissionCard(
                    isDark = isDark,
                    icon = Icons.Outlined.NotificationsActive,
                    title = "Notifications & Service",
                    badgeText = "Required",
                    badgeColor = ClaudeTerracotta,
                    accentColor = ClaudeTerracotta,
                    description = "Required for persistent background terminal service, live status alerts, updates, and rootfs package installer progress.",
                    isGranted = hasNotification,
                    onGrantClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            hasNotification = PermissionUtils.hasNotificationPermission(context)
                        }
                    }
                )

                // 2. EXTERNAL STORAGE (/sdcard) (Optional)
                PermissionCard(
                    isDark = isDark,
                    icon = Icons.Outlined.Folder,
                    title = "Storage & /sdcard",
                    badgeText = "Optional",
                    badgeColor = GeminiBlue,
                    accentColor = GeminiBlue,
                    description = "Allows AntiGem to access /sdcard to import local bootstrap packages, inspect files, and manage external code workspaces.",
                    isGranted = hasStorage,
                    onGrantClick = { requestStorage() }
                )

                // 3. MICROPHONE (Optional)
                PermissionCard(
                    isDark = isDark,
                    icon = Icons.Outlined.Mic,
                    title = "Microphone & Voice",
                    badgeText = "Optional",
                    badgeColor = GeminiPurple,
                    accentColor = GeminiPurple,
                    description = "Enables voice-to-text dictation and voice commands directly in chat.",
                    isGranted = hasAudio,
                    onGrantClick = { audioLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Bottom Action Section
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (!hasNotification) {
                    Text(
                        text = "👉 Please enable Notifications above to proceed",
                        fontSize = 12.5.sp,
                        color = Color(0xFFEF4444),
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }

                Button(
                    onClick = onDismissOrCompleted,
                    enabled = hasNotification,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ClaudeTerracotta,
                        contentColor = Color.White,
                        disabledContainerColor = if (isDark) Color(0xFF282724) else Color(0xFFE5E7EB),
                        disabledContentColor = if (isDark) Color(0xFF6B7280) else Color(0xFF9CA3AF)
                    )
                ) {
                    Text(
                        text = if (hasNotification) "Continue to AntiGem 🚀" else "Notifications Required to Proceed",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(
    isDark: Boolean,
    icon: ImageVector,
    title: String,
    badgeText: String,
    badgeColor: Color,
    accentColor: Color,
    description: String,
    isGranted: Boolean,
    onGrantClick: () -> Unit
) {
    val cardBg = if (isDark) {
        if (isGranted) Color(0xFF232320) else Color(0xFF272623)
    } else {
        if (isGranted) Color(0xFFF9FAFB) else Color(0xFFFFFFFF)
    }

    val cardBorder = if (isGranted) {
        BorderStroke(1.dp, Color(0xFF22C55E).copy(alpha = if (isDark) 0.5f else 0.4f))
    } else {
        BorderStroke(1.dp, if (isDark) Color(0xFF383733) else Color(0xFFE5E7EB))
    }

    val textPrimary = if (isDark) TextPrimaryDark else TextPrimaryLight
    val textSecondary = if (isDark) TextSecondaryDark else TextSecondaryLight

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = cardBg,
        border = cardBorder
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isGranted) Color(0xFF22C55E).copy(alpha = if (isDark) 0.16f else 0.12f)
                        else accentColor.copy(alpha = if (isDark) 0.16f else 0.12f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isGranted) Icons.Default.CheckCircle else icon,
                    contentDescription = null,
                    tint = if (isGranted) Color(0xFF22C55E) else accentColor,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = title,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = textPrimary,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (isGranted) {
                            Color(0xFF22C55E).copy(alpha = if (isDark) 0.18f else 0.12f)
                        } else {
                            badgeColor.copy(alpha = if (isDark) 0.18f else 0.12f)
                        }
                    ) {
                        Text(
                            text = if (isGranted) "Enabled ✓" else badgeText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                            color = if (isGranted) Color(0xFF22C55E) else badgeColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = description,
                    fontSize = 12.sp,
                    color = textSecondary,
                    lineHeight = 16.5.sp
                )

                Spacer(modifier = Modifier.height(10.dp))

                if (isGranted) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = Color(0xFF22C55E),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Permission active",
                            fontSize = 11.5.sp,
                            color = Color(0xFF22C55E),
                            fontWeight = FontWeight.Medium
                        )
                    }
                } else {
                    FilledTonalButton(
                        onClick = onGrantClick,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = if (isDark) Color(0xFF383733) else Color(0xFFE5E7EB),
                            contentColor = textPrimary
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text(
                            text = "Grant Access",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}
