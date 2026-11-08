package com.example.gemini.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.example.gemini.R

/**
 * The real launcher icon (Prism), drawn from its adaptive-icon layers: the graphite background and the gem foreground.
 * Both layers are 108 units with the visible part in the middle 72, so they are scaled 1.5x and clipped to this box.
 * Clip the caller's modifier to the shape you want (rounded square, circle).
 */
@Composable
fun AppIconImage(contentDescription: String?, modifier: Modifier = Modifier) {
    Box(modifier.clipToBounds()) {
        val layer = Modifier.matchParentSize().graphicsLayer { scaleX = 1.5f; scaleY = 1.5f }
        Image(
            painter = painterResource(id = R.drawable.ic_launcher_background),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = layer
        )
        Image(
            painter = painterResource(id = R.drawable.ic_launcher_foreground),
            contentDescription = contentDescription,
            contentScale = ContentScale.FillBounds,
            modifier = layer
        )
    }
}
