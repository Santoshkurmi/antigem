package com.example.gemini.ui.ide

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder

@Composable
fun ImageAssetViewer(
    filePath: String,
    fileName: String,
    modifier: Modifier = Modifier,
    onToggleXmlSource: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    // Background mode: 0 = Checkerboard, 1 = White, 2 = Dark
    var bgMode by remember { mutableIntStateOf(0) }

    val fileObj = remember(filePath) { File(filePath) }
    val encodedPath = remember(filePath) { URLEncoder.encode(filePath, "UTF-8") }
    val imageUrl = remember(encodedPath) { "http://127.0.0.1:8080/api/file/read?path=$encodedPath" }

    // Direct local file if readable, else HTTP daemon endpoint
    val imageSource = remember(fileObj, imageUrl) {
        if (fileObj.exists() && fileObj.canRead()) fileObj else imageUrl
    }

    val ext = remember(fileName) { fileName.substringAfterLast('.', "").lowercase() }
    val isSvg = ext == "svg"

    var imageDimensions by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var fileSizeText by remember { mutableStateOf<String?>(null) }

    // Read dimensions & file size in background
    LaunchedEffect(filePath) {
        withContext(Dispatchers.IO) {
            try {
                if (fileObj.exists()) {
                    val bytes = fileObj.length()
                    fileSizeText = when {
                        bytes < 1024 -> "$bytes B"
                        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
                        else -> String.format("%.2f MB", bytes.toDouble() / (1024 * 1024))
                    }
                    if (!isSvg) {
                        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(filePath, options)
                        if (options.outWidth > 0 && options.outHeight > 0) {
                            imageDimensions = Pair(options.outWidth, options.outHeight)
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    val imageRequest = remember(imageSource, isSvg) {
        ImageRequest.Builder(context)
            .data(imageSource)
            .apply {
                if (isSvg) {
                    decoderFactory(SvgDecoder.Factory())
                }
            }
            .crossfade(true)
            .build()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                when (bgMode) {
                    1 -> Color.White
                    2 -> Color(0xFF181818)
                    else -> Color(0xFF1E1E1E)
                }
            )
    ) {
        // 1. Checkerboard Canvas for transparency
        if (bgMode == 0) {
            CheckerboardCanvas(modifier = Modifier.fillMaxSize())
        }

        // 2. Zoomable & Pannable Image Area
        var imageState by remember { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(0.2f, 10f)
                        offsetX += pan.x
                        offsetY += pan.y
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = imageRequest,
                contentDescription = fileName,
                onState = { imageState = it },
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY
                    )
            )

            // Loading / Error Overlay
            when (imageState) {
                is AsyncImagePainter.State.Loading -> {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(40.dp)
                    )
                }
                is AsyncImagePainter.State.Error -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.BrokenImage,
                            contentDescription = "Failed to load image",
                            tint = Color.Gray,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Could not preview image asset",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.LightGray
                        )
                    }
                }
                else -> {}
            }
        }

        // 3. Floating Control & Info Overlay Bar
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(12.dp)
                .fillMaxWidth(0.92f),
            shape = RoundedCornerShape(10.dp),
            color = Color(0xEE252526),
            tonalElevation = 6.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Info Section
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = fileName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        val dimText = imageDimensions?.let { "${it.first} × ${it.second} px" } ?: if (isSvg) "Vector SVG" else ""
                        val sizeText = fileSizeText?.let { " • $it" } ?: ""
                        val zoomText = " • ${(scale * 100).toInt()}%"
                        Text(
                            text = "$dimText$sizeText$zoomText",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.LightGray,
                            fontSize = 11.sp
                        )
                    }
                }

                // Controls Section
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Background Canvas Switcher
                    IconButton(
                        onClick = { bgMode = (bgMode + 1) % 3 },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Contrast,
                            contentDescription = "Toggle Background",
                            tint = when (bgMode) {
                                1 -> Color.White
                                2 -> Color.DarkGray
                                else -> MaterialTheme.colorScheme.primary
                            },
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = { scale = (scale - 0.25f).coerceAtLeast(0.25f) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ZoomOut,
                            contentDescription = "Zoom Out",
                            tint = Color.LightGray,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = {
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Text(
                            text = "1:1",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(
                        onClick = { scale = (scale + 0.25f).coerceAtMost(10f) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ZoomIn,
                            contentDescription = "Zoom In",
                            tint = Color.LightGray,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    if (isSvg && onToggleXmlSource != null) {
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            onClick = onToggleXmlSource,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Code,
                                contentDescription = "View SVG XML Source",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckerboardCanvas(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val squareSize = 16.dp.toPx()
        val cols = (size.width / squareSize).toInt() + 1
        val rows = (size.height / squareSize).toInt() + 1
        val dark = Color(0xFF222222)
        val light = Color(0xFF2C2C2C)

        for (c in 0 until cols) {
            for (r in 0 until rows) {
                val color = if ((c + r) % 2 == 0) dark else light
                drawRect(
                    color = color,
                    topLeft = Offset(c * squareSize, r * squareSize),
                    size = Size(squareSize, squareSize)
                )
            }
        }
    }
}
