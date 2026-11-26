package tvcal.android.ui.theme

import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import tvcal.android.profiles.onYouTube
import tvcal.android.storage.colourSwatch
import tvcal.kit.SignalMode

// Port of Views/DesignSystem.swift (colour part), from the Figma file "Calibrator" (02 Components,
// 03 Colour and type styles). Dark theme only.

object Theme {
    /** Screens: near-black with a teal cast. #05171A */
    val background = Color(0xFF05171A)
    /** Hero cards and illustration panels. #0D3B42 */
    val surface = Color(0xFF0D3B42)
    val teal = surface
    /** Links, back arrows, icons, eyebrows, the SDR format. #40B8B0 */
    val tealBright = Color(0xFF40B8B0)
    /** The one main action per screen, the current step, the HDR10 format. #FFD133 */
    val accent = Color(0xFFFFD133)
    val accentPressed = Color(0xFFDAB025)
    /** On target, done. #4DCC73 */
    val green = Color(0xFF4DCC73)
    /** At the TV's limit, warnings. #FFB36B */
    val limit = Color(0xFFFFB36B)
    val red = Color(0xFFF2544D)
    val blue = Color(0xFF548CFA)
    val hdr10Plus = Color(0xFF9E85FA)
    val dolbyVision = Color(0xFFED73BF)
    /** Card fill: white 6%. */
    val card = Color.White.copy(alpha = 0.06f)
    /** Hairline borders and empty tracks: white 9%. */
    val hairline = Color.White.copy(alpha = 0.09f)
    val textSecondary = Color.White.copy(alpha = 0.6f)
    /** The camera preview's empty fill. #30383A */
    val previewFill = Color(0xFF30383A)
    /** Swift tealGradient: (0.07, 0.30, 0.33) -> surface, top-leading to bottom-trailing. */
    val tealGradient: Brush = Brush.linearGradient(
        colors = listOf(Color(0.07f, 0.30f, 0.33f), surface),
        start = Offset.Zero,
        end = Offset.Infinite,
    )

    fun channel(c: String): Color = if (c == "R") red else if (c == "G") green else blue

    fun swatch(name: String): Color {
        val s = colourSwatch[name] ?: Triple(0.8, 0.8, 0.8)
        return Color(s.first.toFloat(), s.second.toFloat(), s.third.toFloat())
    }

    fun deColour(de: Double?): Color {
        if (de == null || de.isNaN()) return textSecondary
        return if (de < 1) green else if (de < 3) Color.White else limit
    }
}

/** Each format has its own colour, so it's always clear which videos belong to which. */
val SignalMode.tint: Color
    get() = when (this) {
        SignalMode.SDR -> Theme.tealBright
        SignalMode.HDR10 -> Theme.accent
        SignalMode.HDR10P -> Theme.hdr10Plus
        SignalMode.DV -> Theme.dolbyVision
    }

/** SDR and HDR10 come first (YouTube); HDR10+ and Dolby Vision are advanced (files played on the TV). */
val SignalMode.isAdvanced: Boolean get() = !onYouTube

/** Recommended order, from 1. */
val SignalMode.order: Int get() = SignalMode.entries.indexOf(this) + 1

val SignalMode.blurb: String
    get() = when (this) {
        SignalMode.SDR -> "Everyday TV. Start here."
        SignalMode.HDR10 -> "HDR films. After SDR."
        SignalMode.HDR10P -> "Pattern files on a USB stick. After HDR10."
        SignalMode.DV -> "Pattern files on a USB stick. After HDR10."
    }

/**
 * The app theme: dark only (the room should be dark while measuring), teal tint for links and controls
 * (Swift `.tint(Theme.tealBright)`), white body text in the brand body style.
 */
@Composable
fun TVCalTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = Theme.tealBright,
        onPrimary = Theme.background,
        secondary = Theme.accent,
        onSecondary = Theme.background,
        background = Theme.background,
        onBackground = Color.White,
        surface = Theme.background,
        onSurface = Color.White,
        surfaceVariant = Theme.surface,
        onSurfaceVariant = Theme.textSecondary,
        surfaceContainer = Theme.surface,
        surfaceContainerHigh = Theme.surface,
        surfaceContainerHighest = Theme.surface,
        surfaceContainerLow = Theme.background,
        outline = Theme.hairline,
        outlineVariant = Theme.hairline,
        error = Theme.red,
    )
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(
            LocalContentColor provides Color.White,
            LocalTextStyle provides Fonts.bodyBrand.copy(color = Color.White),
            LocalTextSelectionColors provides TextSelectionColors(
                handleColor = Theme.tealBright,
                backgroundColor = Theme.tealBright.copy(alpha = 0.35f),
            ),
            content = content,
        )
    }
}
