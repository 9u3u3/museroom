package com.museroom.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.museroom.app.media.Artwork
import com.museroom.app.ui.Neo
import com.museroom.app.ui.kit.halftone
import com.museroom.app.ui.kit.hardShadow
import kotlin.math.abs

/**
 * A cover, and something to look at until it arrives.
 *
 * The placeholder is not grey. Every track gets a gradient derived from its own
 * id, so a list of six results is six different squares rather than six holes,
 * and the one you were looking at does not move when the pictures land. The
 * halftone screen goes over both, which is what makes a 46-pixel thumbnail and
 * a full-bleed cover read as the same object.
 */
@Composable
fun TrackCover(
    id: String,
    url: String?,
    modifier: Modifier = Modifier,
    radius: Dp = 10.dp,
    shadow: Dp = 0.dp,
    stroke: Dp = 2.5.dp,
    dot: Dp = 8.dp,
) {
    val c = Neo.colors
    val shape = RoundedCornerShape(radius)
    var bitmap by remember(id) { mutableStateOf(Artwork.cachedUrl(url.orEmpty())) }

    LaunchedEffect(url) {
        if (bitmap == null && !url.isNullOrBlank()) bitmap = Artwork.fromUrl(url)
    }

    // Fading in rather than snapping, because a grid of covers all appearing at
    // once on different network timings is a flicker, not an arrival.
    val fade by animateFloatAsState(
        if (bitmap != null) 1f else 0f,
        tween(320),
        label = "cover",
    )

    Box(
        modifier
            .hardShadow(shadow, c.ink, shape)
            .clip(shape)
            .background(gradientFor(id))
            .border(stroke, c.ink, shape),
    ) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().alpha(fade),
            )
        }
        Box(Modifier.fillMaxSize().halftone(Color(0xFF14110D), alpha = 0.20f, step = dot))
    }
}

/**
 * The same id always gets the same two colours, from the kit's four accents.
 *
 * Deterministic on purpose: a track keeps its colour between the search result,
 * the queue and the player, so it stays the same object as you move through the
 * app rather than becoming a new one on every screen.
 */
private fun gradientFor(id: String): Brush {
    val accents = listOf(
        Color(0xFF7B4BFF), Color(0xFFFF4D8D), Color(0xFF54D6F5),
        Color(0xFFCDFF3E), Color(0xFFFF9E2B),
    )
    val seed = abs(id.hashCode())
    val a = accents[seed % accents.size]
    val b = accents[(seed / accents.size + 1) % accents.size]
    return Brush.linearGradient(listOf(a, b))
}
