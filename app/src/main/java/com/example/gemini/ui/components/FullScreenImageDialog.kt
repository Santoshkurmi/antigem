package com.example.gemini.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.io.File

/**
 * Fullscreen modal image viewer with smooth pinch-to-zoom, panning, double-tap zoom reset,
 * and native image sharing.
 */
@Composable
fun FullScreenImageDialog(
    imageUrl: String,
    title: String = "",
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val snackbarHostState = LocalSnackbarHostState.current
    val coroutineScope = rememberCoroutineScope()

    val memKey = remember(imageUrl) { com.example.gemini.data.remote.HubMediaResolver.normalizeKey(imageUrl) }
    var resolvedUri by remember(imageUrl) {
        mutableStateOf(if (imageUrl.isNotBlank()) com.example.gemini.data.remote.HubMediaResolver.getResolvedUriSync(context, imageUrl) else "")
    }

    LaunchedEffect(imageUrl) {
        if (imageUrl.isNotBlank() && !com.example.gemini.data.remote.HubMediaResolver.isLocalOrCached(context, imageUrl)) {
            val res = com.example.gemini.data.remote.HubMediaResolver.resolveMediaUri(context, imageUrl)
            if (res.isNotBlank()) {
                resolvedUri = res
            }
        }
    }

    val finalUri = resolvedUri.ifBlank { imageUrl }
    val coilData: Any? = remember(finalUri, memKey) {
        val cachedBytes = com.example.gemini.data.remote.HubMediaResolver.getImageBytes(memKey)
        when {
            cachedBytes != null -> cachedBytes
            finalUri.startsWith("data:image/") -> {
                try {
                    val b64 = finalUri.substringAfter("base64,")
                    android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                } catch (_: Exception) {
                    finalUri
                }
            }
            finalUri.isNotBlank() -> finalUri
            else -> null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF090A0F).copy(alpha = 0.96f))
        ) {
            // Main Zoomable & Pannable Image Area
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (scale > 1.2f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = 2.5f
                                }
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(0.8f, 5f)
                            if (scale <= 1f) {
                                offset = Offset.Zero
                            } else {
                                offset = Offset(
                                    x = offset.x + pan.x,
                                    y = offset.y + pan.y
                                )
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (coilData != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(coilData)
                            .memoryCacheKey(memKey.ifBlank { null })
                            .crossfade(false)
                            .build(),
                        contentDescription = title,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offset.x,
                                translationY = offset.y
                            )
                    )
                }
            }

            // Top Overlay Bar (Title, Zoom Reset, Share, Close)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .align(Alignment.TopCenter),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.15f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Text(
                    text = title.ifBlank { "Image Viewer" },
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                if (scale != 1f) {
                    IconButton(
                        onClick = {
                            scale = 1f
                            offset = Offset.Zero
                        },
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.12f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Reset Zoom",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }

                IconButton(
                    onClick = {
                        ImageDownloadHelper.downloadImage(
                            context = context,
                            imageSource = finalUri,
                            title = title,
                            coroutineScope = coroutineScope,
                            snackbarHostState = snackbarHostState
                        )
                    },
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.15f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = "Download",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

