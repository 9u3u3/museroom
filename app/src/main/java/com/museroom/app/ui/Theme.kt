package com.museroom.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.museroom.app.R

/**
 * Comic neobrutalism: a thick ink stroke on everything, a hard offset shadow
 * instead of a blur, and Ben-Day dots on the paper.
 *
 * Surfaces invert between the two themes; accents never do. A lime sticker is
 * the same lime on paper or on ink, carrying its own dark ink for text, border
 * and shadow. That is what keeps dark reading as the same design rather than a
 * photo negative of it.
 */
@Immutable
data class NeoColors(
    val paper: Color,
    val card: Color,
    val ink: Color,
    val violet: Color,
    val lime: Color,
    val pink: Color,
    val sky: Color,
    /** Ink that always sits on an accent fill, in either theme. */
    val onAccent: Color,
    val dark: Boolean,
)

private val LightNeo = NeoColors(
    paper = Color(0xFFFBF6EA),
    card = Color(0xFFFFFFFF),
    ink = Color(0xFF14110D),
    violet = Color(0xFF7B4BFF),
    lime = Color(0xFFCDFF3E),
    pink = Color(0xFFFF4D8D),
    sky = Color(0xFF54D6F5),
    onAccent = Color(0xFF14110D),
    dark = false,
)

private val DarkNeo = LightNeo.copy(
    paper = Color(0xFF17140F),
    card = Color(0xFF241F18),
    ink = Color(0xFFF6F0E2),
    dark = true,
)

val LocalNeo = staticCompositionLocalOf { LightNeo }

object Neo {
    val colors: NeoColors
        @Composable @ReadOnlyComposable get() = LocalNeo.current
}

// ------------------------------------------------------------------ type ----

@OptIn(ExperimentalTextApi::class)
private fun archivo(weight: Int) = Font(
    R.font.archivo,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Archivo = FontFamily(archivo(400), archivo(600), archivo(700), archivo(800), archivo(900))
val Bangers = FontFamily(Font(R.font.bangers))
val Mono = FontFamily(
    Font(R.font.space_mono, FontWeight.Normal),
    Font(R.font.space_mono_bold, FontWeight.Bold),
)

/** The display face, used for the wordmark, big numbers and screen titles. */
fun bangers(size: Int) = TextStyle(
    fontFamily = Bangers,
    fontSize = size.sp,
    lineHeight = (size * 0.92f).sp,
    letterSpacing = (size * 0.03f).sp,
)

private val NeoType = Typography(
    displayLarge = bangers(56),
    displayMedium = bangers(40),
    headlineLarge = TextStyle(fontFamily = Archivo, fontWeight = FontWeight.W900, fontSize = 29.sp, lineHeight = 31.sp, letterSpacing = (-0.6).sp),
    titleLarge = TextStyle(fontFamily = Archivo, fontWeight = FontWeight.W900, fontSize = 19.sp, lineHeight = 23.sp),
    titleMedium = TextStyle(fontFamily = Archivo, fontWeight = FontWeight.W900, fontSize = 16.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Archivo, fontWeight = FontWeight.W600, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = Archivo, fontWeight = FontWeight.W600, fontSize = 13.5.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontFamily = Archivo, fontWeight = FontWeight.W600, fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = Archivo, fontWeight = FontWeight.W900, fontSize = 14.sp, letterSpacing = 0.9.sp),
    labelMedium = TextStyle(fontFamily = Archivo, fontWeight = FontWeight.W900, fontSize = 11.sp, letterSpacing = 1.6.sp),
    labelSmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.6.sp),
)

/**
 * [dark] is passed in rather than read from the system on purpose: this design
 * has a light theme it was drawn in, and dark is a choice the person makes.
 */
@Composable
fun MuseroomTheme(
    dark: Boolean = false,
    content: @Composable () -> Unit,
) {
    val neo = if (dark) DarkNeo else LightNeo
    // Material's scheme is kept in step so stray platform widgets do not arrive
    // in colours the design never chose.
    val scheme = if (dark) {
        darkColorScheme(
            primary = neo.violet, onPrimary = Color.White,
            background = neo.paper, onBackground = neo.ink,
            surface = neo.card, onSurface = neo.ink,
            error = neo.pink, onError = neo.onAccent,
        )
    } else {
        lightColorScheme(
            primary = neo.violet, onPrimary = Color.White,
            background = neo.paper, onBackground = neo.ink,
            surface = neo.card, onSurface = neo.ink,
            error = neo.pink, onError = neo.onAccent,
        )
    }

    CompositionLocalProvider(LocalNeo provides neo) {
        MaterialTheme(colorScheme = scheme, typography = NeoType, content = content)
    }
}
