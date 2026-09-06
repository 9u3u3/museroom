package com.museroom.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.museroom.app.ui.Archivo
import com.museroom.app.ui.Neo
import com.museroom.app.ui.bangers
import com.museroom.app.ui.kit.Kicker
import com.museroom.app.ui.kit.NeoRound
import com.museroom.app.ui.kit.NeoIcons

/**
 * The three bars the design uses, and no others.
 *
 * Every artboard opens with one of them: a tab's own bar with a wordmark or a
 * crumb on the left, or a pushed page's bar with a way back on the left and a
 * label in the middle. Drawing them once is what stops fourteen screens each
 * arriving at a slightly different top inset.
 */

/** A tab's bar: something on the left, controls on the right. */
@Composable
fun TabBar(
    modifier: Modifier = Modifier,
    left: @Composable () -> Unit,
    right: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.weight(1f)) { left() }
        right()
    }
}

/**
 * The wordmark, set over a hard violet copy of itself.
 *
 * Drawn twice rather than with a shadow, because a blur under a Bangers word in
 * a kit built on flat offsets reads as a different app.
 */
@Composable
fun Wordmark(text: String = "Museroom", size: Int = 30) {
    val c = Neo.colors
    Box {
        Text(
            text.uppercase(),
            style = bangers(size).copy(color = c.violet),
            modifier = Modifier.padding(start = 3.dp, top = 3.dp),
        )
        Text(text.uppercase(), style = bangers(size).copy(color = c.ink))
    }
}

/** The quiet uppercase line that says where you are without shouting it. */
@Composable
fun Crumb(text: String, modifier: Modifier = Modifier) = Text(
    text.uppercase(),
    modifier = modifier,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
    style = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.W900,
        fontSize = 11.sp,
        letterSpacing = 1.7.sp,
        color = Neo.colors.ink.copy(alpha = 0.55f),
    ),
)

/**
 * A pushed page's bar: a way back, what this page is, and one thing to do to
 * the whole of it.
 */
@Composable
fun PageBar(
    crumb: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    backIcon: String = NeoIcons.Back,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        NeoRound(backIcon, "Back", onBack)
        Crumb(crumb, Modifier.weight(1f))
        if (action != null) action() else Box(Modifier.size(42.dp))
    }
}

/**
 * The bar the player surfaces share: a way out, what is being shown and where
 * it came from, and one more thing to do.
 *
 * The middle is two lines because "playing from" is the useful half — the same
 * song reached from an album, a room and a search is three different sessions,
 * and only this says which one you are in.
 */
@Composable
fun SheetBar(
    kicker: String,
    value: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    closeIcon: String = NeoIcons.Chevron,
    action: (@Composable () -> Unit)? = null,
) {
    val c = Neo.colors
    Row(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        NeoRound(closeIcon, "Close", onClose)
        Column(
            Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Kicker(kicker)
            Text(
                value,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    fontFamily = Archivo,
                    fontWeight = FontWeight.W900,
                    fontSize = 13.sp,
                    color = c.ink,
                ),
            )
        }
        if (action != null) action() else Box(Modifier.size(42.dp))
    }
}
