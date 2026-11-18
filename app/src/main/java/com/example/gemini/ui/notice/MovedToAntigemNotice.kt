package com.example.gemini.ui.notice

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Construction
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.gemini.theme.AppColors
import com.example.gemini.theme.isAppInDarkTheme
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * AntiGem moved from the package name com.termux to its own, ai.antigem. This (the last com.termux) release shows
 * [MovedToAntigemNotice] on every app launch. The first time it can't be closed for [READ_SECONDS] seconds, so it gets read.
 */
object MoveNotice {
    const val NEW_PACKAGE = "ai.antigem"
    /** The releases page, not /latest: until everyone has moved, the latest release is this com.termux app's update. */
    const val DOWNLOAD_URL = "https://github.com/santoshkurmi/antigem/releases"
    /** The new app's versions start here (its APK is AntiGem-v2.x.x.apk). */
    const val NEW_VERSION = "2.0.0"
    const val ISSUES_URL = "https://github.com/santoshkurmi/antigem/issues"
    const val READ_SECONDS = 60

    private const val PREFS = "antigem_move_notice"
    private const val KEY_READ = "read_v1"

    fun hasBeenRead(context: Context): Boolean = prefs(context).getBoolean(KEY_READ, false)

    fun markRead(context: Context) {
        if (!hasBeenRead(context)) prefs(context).edit().putBoolean(KEY_READ, true).apply()
    }

    fun open(context: Context, url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            com.example.gemini.ui.components.AppToastHelper.showToast("No browser found. Open $url")
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** Full-screen notice: AntiGem has a new package name (ai.antigem), why, how it works, and how to move over. */
@Composable
fun MovedToAntigemNotice(onClose: () -> Unit) {
    val context = LocalContext.current
    val mustRead = remember { !MoveNotice.hasBeenRead(context) }
    var secondsLeft by rememberSaveable { mutableIntStateOf(if (mustRead) MoveNotice.READ_SECONDS else 0) }
    val canClose = secondsLeft <= 0
    val lifecycleOwner = LocalLifecycleOwner.current

    // counts down only while the app is on screen
    LaunchedEffect(Unit) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (secondsLeft > 0) {
                delay(1000)
                secondsLeft -= 1
            }
            MoveNotice.markRead(context)
        }
    }

    Dialog(
        onDismissRequest = { if (canClose) onClose() },
        properties = DialogProperties(
            dismissOnBackPress = canClose,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        val dark = isAppInDarkTheme()
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            AuroraBackground(dark)
            Column(
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Column(
                        Modifier
                            .widthIn(max = 620.dp)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 18.dp, vertical = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Reveal(0) { Hero() }
                        Reveal(1) { WhyCard() }
                        Reveal(2) { HowCard() }
                        Reveal(3) { BenefitsCard() }
                        Reveal(4) { RoughEdgesCard(onReport = { MoveNotice.open(context, MoveNotice.ISSUES_URL) }) }
                        Reveal(5) { StepsCard() }
                        Spacer(Modifier.height(4.dp))
                    }
                }
                Footer(
                    secondsLeft = secondsLeft,
                    mustRead = mustRead,
                    onDownload = { MoveNotice.open(context, MoveNotice.DOWNLOAD_URL) },
                    onClose = { if (canClose) onClose() }
                )
            }
        }
    }
}

// ---------------------------------------------------------------- sections

@Composable
private fun Hero() {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(6.dp))
        Pill("Final update of this app", Icons.Rounded.NewReleases, AppColors.warning)
        Spacer(Modifier.height(22.dp))
        PackageMove()
        Spacer(Modifier.height(24.dp))
        Text(
            "AntiGem has a new home",
            fontSize = 28.sp,
            lineHeight = 34.sp,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(10.dp))
        Text(
            rich(
                "This version won't get any more updates. AntiGem now has its own official package name, " +
                    "**${MoveNotice.NEW_PACKAGE}**, and every new version, from **${MoveNotice.NEW_VERSION}** on, comes out there."
            ),
            fontSize = 15.sp,
            lineHeight = 22.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.78f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 6.dp)
        )
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun WhyCard() {
    NoticeCard(Icons.Rounded.Verified, AppColors.primary, "Why we moved") {
        Body(
            "This app is installed as `com.termux`, the package name of the Termux project. We used it only so " +
                "Termux packages could run at full speed. But shipping AntiGem under another project's name isn't right, " +
                "and it also kept AntiGem from being installed next to the real Termux app."
        )
        Spacer(Modifier.height(10.dp))
        Body(
            "So AntiGem now has a name of its own: **${MoveNotice.NEW_PACKAGE}**. This app keeps working, " +
                "but new features and fixes only come to the new one."
        )
    }
}

@Composable
private fun HowCard() {
    NoticeCard(Icons.Rounded.Build, AppColors.info, "How we did it") {
        Body(
            "Termux packages are built for one fixed folder, and Android names every app's folder after its " +
                "package. So for AntiGem, that folder had to change:"
        )
        Spacer(Modifier.height(12.dp))
        PathSwap()
        Spacer(Modifier.height(12.dp))
        Bullet(
            "Instead of faking the old folder with **proot** (which slows down every program) or rebuilding thousands " +
                "of packages, AntiGem rewrites `com.termux` to `ai.antigem` inside each package as it installs: " +
                "file paths, file contents, links and install scripts.",
            AppColors.info
        )
        Bullet(
            "Both names are exactly **10 characters** long, so the rewrite swaps bytes in place and every program " +
                "keeps its exact layout.",
            AppColors.info
        )
        Bullet(
            "It happens by itself: for the base system, for restored backups, for `pkg install` / `apt install` " +
                "(a fast rewriter built into the IDE bridge) and for `dpkg -i`.",
            AppColors.info
        )
        Bullet(
            "The `am` command, which Termux builds for its own name, is built from Termux's own source code for " +
                "`ai.antigem`.",
            AppColors.info
        )
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(AppColors.success.copy(alpha = 0.12f))
                .border(1.dp, AppColors.success.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Speed, null, tint = AppColors.success, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                rich("**Full native speed**, straight from the official Termux repositories. No proot."),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun BenefitsCard() {
    NoticeCard(Icons.Rounded.AutoAwesome, AppColors.accentAlt, "What the new app gives you") {
        Check("Installs next to the official Termux app, no conflict")
        Check("Every new feature and fix from now on")
        Check("The same Termux packages, at the same native speed")
        Check("Can run next to this app while you move over")
    }
}

@Composable
private fun RoughEdgesCard(onReport: () -> Unit) {
    NoticeCard(
        Icons.Rounded.Construction,
        AppColors.warning,
        "A few rough edges",
        container = lerp(AppColors.card, AppColors.warning, if (isAppInDarkTheme()) 0.10f else 0.07f),
        borderColor = AppColors.warning.copy(alpha = 0.4f)
    ) {
        Body(
            "The new app is brand new. Most things work just like here, but some may not work yet: " +
                "a few packages, the Linux desktop (X11) and some `termux-*` commands are still being tested."
        )
        Spacer(Modifier.height(10.dp))
        Body(
            "You can keep this app installed until the new one does everything you need. If something breaks, " +
                "please tell us so we can fix it."
        )
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, AppColors.warning.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                .clickable(onClick = onReport)
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.BugReport, null, tint = AppColors.warning, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "Report a problem on GitHub",
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun StepsCard() {
    NoticeCard(Icons.Rounded.SwapHoriz, AppColors.primary, "Move your data in 4 steps") {
        Body(
            "Android sees `ai.antigem` as a different app, so your files don't move over by themselves. " +
                "It only takes a few minutes:"
        )
        Spacer(Modifier.height(16.dp))
        Step(
            1, "Back up here",
            "Open **Settings → Backups & Restore → Back up environment**. Tick **Include SSH keys and sign-ins** " +
                "to keep your logins (never share that file). It is saved to `Download/Antigem/backups`."
        )
        Step(
            2, "Install the new AntiGem",
            "Tap **Get the new AntiGem** below to open the releases page. Pick version **${MoveNotice.NEW_VERSION} or " +
                "newer** (2.x.x) and install its `AntiGem-v2.x.x.apk`; versions below ${MoveNotice.NEW_VERSION} are this " +
                "old app. It installs next to this app; nothing here is touched."
        )
        Step(
            3, "Restore your backup",
            "On its setup screen choose **From a ZIP file** and pick your backup. Every `com.termux` path is " +
                "rewritten for `ai.antigem` while it restores."
        )
        Step(
            4, "Clean up",
            "When everything works, uninstall this app, and delete the backup if it has your sign-ins.",
            last = true
        )
    }
}

@Composable
private fun Footer(secondsLeft: Int, mustRead: Boolean, onDownload: () -> Unit, onClose: () -> Unit) {
    val canClose = secondsLeft <= 0
    val total = MoveNotice.READ_SECONDS.toFloat()
    val progress by animateFloatAsState(
        targetValue = 1f - secondsLeft / total,
        animationSpec = tween(950, easing = LinearEasing),
        label = "readProgress"
    )
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.96f))
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(if (mustRead) 3.dp else 1.dp)
                .background(AppColors.border.copy(alpha = 0.7f))
        ) {
            if (mustRead) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .background(Brush.horizontalGradient(listOf(AppColors.primary, AppColors.accentAlt)))
                )
            }
        }
        Column(
            Modifier
                .widthIn(max = 620.dp)
                .fillMaxWidth()
                .align(Alignment.CenterHorizontally)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            DownloadButton(onDownload)
            CloseButton(secondsLeft, progress, onClose)
            if (canClose) {
                Text(
                    "This screen shows each time you open this app.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

// ---------------------------------------------------------------- pieces

/** `com.termux` (old, crossed out) → animated arrows → `ai.antigem` (glowing). */
@Composable
private fun PackageMove() {
    val transition = rememberInfiniteTransition(label = "packageMove")
    val flow by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "flow"
    )
    val glow by transition.animateFloat(
        0.45f, 1f, infiniteRepeatable(tween(1700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "glow"
    )
    val primary = AppColors.primary
    val chipBrush = Brush.horizontalGradient(listOf(primary, lerp(primary, AppColors.accentAlt, 0.5f)))
    val onPrimary = MaterialTheme.colorScheme.onPrimary

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                    .border(1.dp, AppColors.border, RoundedCornerShape(14.dp))
                    .padding(horizontal = 13.dp, vertical = 10.dp)
            ) {
                Text(
                    "com.termux",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textDecoration = TextDecoration.LineThrough
                )
            }
            Spacer(Modifier.height(7.dp))
            Text("old", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
        }

        Box(
            Modifier
                .padding(start = 6.dp, end = 4.dp, bottom = 20.dp)
                .width(46.dp)
                .height(24.dp)
        ) {
            repeat(3) { i ->
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = primary,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = (i * 11).dp)
                        .size(24.dp)
                        .graphicsLayer {
                            var d = flow - i / 3f
                            d -= d.roundToInt()
                            alpha = 0.2f + 0.8f * (1f - abs(d) * 3f).coerceAtLeast(0f)
                        }
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .drawBehind {
                        val r = size.width * 0.85f
                        drawCircle(
                            Brush.radialGradient(
                                listOf(primary.copy(alpha = 0.42f * glow), Color.Transparent),
                                center = center,
                                radius = r
                            ),
                            radius = r,
                            center = center
                        )
                    }
                    .clip(RoundedCornerShape(14.dp))
                    .background(chipBrush)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(
                    MoveNotice.NEW_PACKAGE,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = onPrimary
                )
            }
            Spacer(Modifier.height(7.dp))
            Text("new · official", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = primary)
        }
    }
}

/** The prefix path, before and after: only the package name changes, and both are 10 characters. */
@Composable
private fun PathSwap() {
    val text = AppColors.codeText
    @Composable
    fun line(sign: String, signColor: Color, pkg: String, pkgColor: Color, old: Boolean) = Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = signColor, fontWeight = FontWeight.Bold)) { append("$sign ") }
            withStyle(SpanStyle(color = text.copy(alpha = 0.72f))) { append("/data/data/") }
            withStyle(
                SpanStyle(
                    color = pkgColor,
                    fontWeight = FontWeight.Bold,
                    textDecoration = if (old) TextDecoration.LineThrough else null
                )
            ) { append(pkg) }
            withStyle(SpanStyle(color = text.copy(alpha = 0.72f))) { append("/files/usr") }
        },
        fontFamily = FontFamily.Monospace,
        fontSize = 12.5.sp,
        softWrap = false
    )
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AppColors.codeBg)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        line("−", AppColors.danger, "com.termux", text.copy(alpha = 0.55f), old = true)
        line("+", AppColors.success, MoveNotice.NEW_PACKAGE, AppColors.syntaxString, old = false)
        Spacer(Modifier.height(2.dp))
        Text(
            "10 characters → 10 characters, swapped byte for byte",
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = text.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun DownloadButton(onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "shine")
    val shine by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(2400, delayMillis = 1400, easing = FastOutSlowInEasing)), label = "shineX"
    )
    val primary = AppColors.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    Box(
        Modifier
            .fillMaxWidth()
            .height(60.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.horizontalGradient(listOf(primary, lerp(primary, AppColors.accentAlt, 0.5f))))
            .drawWithContent {
                drawContent()
                val x = size.width * (shine * 1.6f - 0.3f)
                drawRect(
                    Brush.linearGradient(
                        listOf(Color.Transparent, Color.White.copy(alpha = 0.22f), Color.Transparent),
                        start = Offset(x - 140f, 0f),
                        end = Offset(x + 140f, size.height)
                    )
                )
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Download, null, tint = onPrimary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Get the new AntiGem", color = onPrimary, fontSize = 16.5.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Releases page · download version ${MoveNotice.NEW_VERSION}+",
                    color = onPrimary.copy(alpha = 0.85f),
                    fontSize = 11.5.sp
                )
            }
        }
    }
}

@Composable
private fun CloseButton(secondsLeft: Int, progress: Float, onClose: () -> Unit) {
    val canClose = secondsLeft <= 0
    val onBg = MaterialTheme.colorScheme.onBackground
    Box(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (canClose) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f) else Color.Transparent)
            .border(1.dp, if (canClose) onBg.copy(alpha = 0.22f) else AppColors.border, RoundedCornerShape(16.dp))
            .clickable(enabled = canClose, onClick = onClose),
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = canClose,
            transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) },
            label = "closeState"
        ) { ready ->
            if (ready) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Continue to this app", color = onBg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = onBg, modifier = Modifier.size(18.dp))
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            progress = { progress.coerceIn(0f, 1f) },
                            modifier = Modifier.size(30.dp),
                            color = AppColors.primary,
                            trackColor = AppColors.border,
                            strokeWidth = 2.5.dp
                        )
                        Text("$secondsLeft", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = onBg)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Please read first · you can close this in ${secondsLeft}s",
                        color = onBg.copy(alpha = 0.8f),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun NoticeCard(
    icon: ImageVector,
    accent: Color,
    title: String,
    container: Color = AppColors.card,
    borderColor: Color = AppColors.border,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(container.copy(alpha = 0.95f))
            .border(1.dp, borderColor, shape)
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(accent.copy(alpha = 0.16f))
                    .border(1.dp, accent.copy(alpha = 0.3f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = accent, modifier = Modifier.size(21.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.height(14.dp))
        content()
    }
}

@Composable
private fun Body(text: String, modifier: Modifier = Modifier) {
    Text(
        rich(text),
        modifier = modifier,
        fontSize = 14.5.sp,
        lineHeight = 21.sp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f)
    )
}

@Composable
private fun Bullet(text: String, accent: Color) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
    ) {
        Box(
            Modifier
                .padding(top = 8.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(accent)
        )
        Spacer(Modifier.width(12.dp))
        Body(text, Modifier.weight(1f))
    }
}

@Composable
private fun Check(text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.CheckCircle, null, tint = AppColors.success, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Body(text, Modifier.weight(1f))
    }
}

@Composable
private fun Step(number: Int, title: String, body: String, last: Boolean = false) {
    val primary = AppColors.primary
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
    ) {
        Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(primary),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "$number",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            if (!last) {
                Box(
                    Modifier
                        .padding(vertical = 4.dp)
                        .width(2.dp)
                        .weight(1f)
                        .clip(RoundedCornerShape(1.dp))
                        .background(primary.copy(alpha = 0.3f))
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(
            Modifier
                .weight(1f)
                .padding(top = 4.dp, bottom = if (last) 0.dp else 18.dp)
        ) {
            Text(title, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(4.dp))
            Body(body)
        }
    }
}

@Composable
private fun Pill(text: String, icon: ImageVector, accent: Color) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(accent.copy(alpha = 0.14f))
            .border(1.dp, accent.copy(alpha = 0.4f), CircleShape)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(7.dp))
        Text(
            text.uppercase(),
            fontSize = 11.5.sp,
            letterSpacing = 0.8.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

/** Slow drifting colour glows behind everything (kept faint so text stays easy to read). */
@Composable
private fun AuroraBackground(dark: Boolean) {
    val transition = rememberInfiniteTransition(label = "aurora")
    val phase by transition.animateFloat(
        0f, (2 * PI).toFloat(), infiniteRepeatable(tween(18000, easing = LinearEasing)), label = "phase"
    )
    val strength = if (dark) 0.24f else 0.15f
    val c1 = AppColors.primary
    val c2 = AppColors.accentAlt
    val c3 = AppColors.info
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                val w = size.width
                val h = size.height
                val r = maxOf(w, h) * 0.5f
                fun blob(color: Color, x: Float, y: Float, radius: Float) {
                    val c = Offset(x, y)
                    drawCircle(
                        Brush.radialGradient(listOf(color.copy(alpha = strength), Color.Transparent), center = c, radius = radius),
                        radius = radius,
                        center = c
                    )
                }
                blob(c1, w * (0.2f + 0.15f * cos(phase)), h * (0.1f + 0.05f * sin(phase)), r)
                blob(c2, w * (0.85f + 0.12f * sin(phase)), h * (0.4f + 0.08f * cos(phase)), r * 0.85f)
                blob(c3, w * (0.3f + 0.18f * sin(phase + 1.3f)), h * (0.92f + 0.05f * cos(phase)), r * 0.8f)
            }
    )
}

/** Fades and lifts each section in, one after another. */
@Composable
private fun Reveal(index: Int, content: @Composable () -> Unit) {
    val state = remember { MutableTransitionState(false).apply { targetState = true } }
    val delay = 80 + index * 90
    AnimatedVisibility(
        visibleState = state,
        enter = fadeIn(tween(520, delayMillis = delay)) +
            slideInVertically(tween(560, delayMillis = delay, easing = FastOutSlowInEasing)) { it / 5 }
    ) {
        content()
    }
}

/** `**bold**` and `` `code` `` in the notice text. */
@Composable
private fun rich(text: String): AnnotatedString {
    val codeBg = AppColors.primary.copy(alpha = 0.13f)
    val onSurface = MaterialTheme.colorScheme.onSurface
    return remember(text, codeBg, onSurface) {
        buildAnnotatedString {
            var i = 0
            while (i < text.length) {
                if (text.startsWith("**", i)) {
                    val end = text.indexOf("**", i + 2)
                    if (end < 0) {
                        append(text.substring(i)); break
                    }
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, end)) }
                    i = end + 2
                } else if (text[i] == '`') {
                    val end = text.indexOf('`', i + 1)
                    if (end < 0) {
                        append(text.substring(i)); break
                    }
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 0.92.em,
                            color = onSurface,
                            background = codeBg
                        )
                    ) { append("\u2009${text.substring(i + 1, end)}\u2009") }
                    i = end + 1
                } else {
                    append(text[i]); i++
                }
            }
        }
    }
}
