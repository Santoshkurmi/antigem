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
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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

    val textPrimary = if (isDark) TextPrimaryDark else TextPrimaryLight
    val textSecondary = if (isDark) TextSecondaryDark else TextSecondaryLight
    val allowedCount = listOf(hasNotification, hasStorage, hasAudio).count { it }
    val progress by animateFloatAsState(allowedCount / 3f, label = "permProgress")

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = AppColors.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(48.dp))

                // app mark
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(ClaudeTerracotta, ClaudeTerracotta.copy(alpha = 0.65f))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Shield,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(34.dp)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "Welcome to AntiGem",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = textPrimary,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Allow a few things so AntiGem can run in the background and work with your files.",
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )

                Spacer(modifier = Modifier.height(32.dp))

                // progress
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Permissions",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = textPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "$allowedCount of 3 allowed",
                        fontSize = 12.5.sp,
                        color = textSecondary
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = AppColors.success,
                    trackColor = AppColors.fill,
                    drawStopIndicator = {}
                )

                Spacer(modifier = Modifier.height(16.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = AppColors.card,
                    border = BorderStroke(1.dp, AppColors.border.copy(alpha = if (isDark) 0.35f else 0.8f))
                ) {
                    Column {
                        PermissionRow(
                            icon = Icons.Outlined.NotificationsActive,
                            accent = ClaudeTerracotta,
                            title = "Notifications",
                            subtitle = "Keeps the background service running and shows progress",
                            required = true,
                            isGranted = hasNotification,
                            onAllow = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    hasNotification = PermissionUtils.hasNotificationPermission(context)
                                }
                            }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 68.dp),
                            color = AppColors.border.copy(alpha = if (isDark) 0.25f else 0.6f)
                        )
                        PermissionRow(
                            icon = Icons.Outlined.Folder,
                            accent = GeminiBlue,
                            title = "Files",
                            subtitle = "Open projects and files on your storage",
                            required = false,
                            isGranted = hasStorage,
                            onAllow = { requestStorage() }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 68.dp),
                            color = AppColors.border.copy(alpha = if (isDark) 0.25f else 0.6f)
                        )
                        PermissionRow(
                            icon = Icons.Outlined.Mic,
                            accent = GeminiPurple,
                            title = "Microphone",
                            subtitle = "Dictate messages and voice notes",
                            required = false,
                            isGranted = hasAudio,
                            onAllow = { audioLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "You can change these anytime in Android settings.",
                    fontSize = 12.sp,
                    color = textSecondary.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))
            }

            // continue
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AnimatedVisibility(visible = !hasNotification) {
                    Text(
                        text = "Allow notifications to continue",
                        fontSize = 12.5.sp,
                        color = textSecondary,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )
                }
                Button(
                    onClick = onDismissOrCompleted,
                    enabled = hasNotification,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ClaudeTerracotta,
                        contentColor = Color.White,
                        disabledContainerColor = AppColors.fill,
                        disabledContentColor = AppColors.textSecondary
                    )
                ) {
                    Text(text = "Continue", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                }
            }
        }
    }
}

/** One permission: icon, title, a short line, and an Allow button (a check once it is allowed). */
@Composable
private fun PermissionRow(
    icon: ImageVector,
    accent: Color,
    title: String,
    subtitle: String,
    required: Boolean,
    isGranted: Boolean,
    onAllow: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val tint by animateColorAsState(if (isGranted) AppColors.success else accent, label = "permTint")
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.onBackground
                )
                if (required && !isGranted) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Required",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = ClaudeTerracotta
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
                color = AppColors.textSecondary
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        AnimatedContent(targetState = isGranted, label = "permState") { granted ->
            if (granted) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(AppColors.success.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Allowed",
                        tint = AppColors.success,
                        modifier = Modifier.size(18.dp)
                    )
                }
            } else {
                FilledTonalButton(
                    onClick = onAllow,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = accent.copy(alpha = 0.14f),
                        contentColor = accent
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Text(text = "Allow", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
