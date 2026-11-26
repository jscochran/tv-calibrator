package tvcal.android.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tvcal.android.ui.theme.Fonts
import tvcal.android.ui.theme.Symbols
import tvcal.android.ui.theme.Theme
import tvcal.android.ui.theme.tint
import tvcal.kit.SignalMode

// Port of the parts in Views/DesignSystem.swift: buttons, Card, IconBubble, ModeBadge, Eyebrow, TipRow,
// PageDots, ProgressTrack. SwiftUI ButtonStyles become button composables.

// MARK: - Buttons

/** The main action (PrimaryButtonStyle): a yellow capsule, dark label, at least 52 dp tall. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fill = if (!enabled) Theme.accent.copy(alpha = 0.2f) else if (pressed) Theme.accentPressed else Theme.accent
    val fg = if (enabled) Theme.background else Theme.textSecondary
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(CircleShape)
            .background(fill)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
        Text(text, style = Fonts.bodyBrand, color = fg, textAlign = TextAlign.Center)
    }
}

/** A secondary action (SecondaryButtonStyle): a teal-outlined capsule. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fg = if (enabled) Color.White else Theme.textSecondary
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(CircleShape)
            .background(if (pressed) Theme.card else Color.Transparent)
            .border(1.dp, if (enabled) Theme.tealBright else Theme.hairline, CircleShape)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
        Text(text, style = Fonts.bodyBrand, color = fg, textAlign = TextAlign.Center)
    }
}

/** A text link (LinkButtonStyle): teal, semibold by default, at least 44 dp tall. */
@Composable
fun LinkButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = Fonts.bodyStrong,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val colour = when {
        !enabled -> Theme.textSecondary
        pressed -> Theme.tealBright.copy(alpha = 0.6f)
        else -> Theme.tealBright
    }
    val iconSize = (style.fontSize.value * 1.05f).dp
    Row(
        modifier
            .heightIn(min = 44.dp)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) Icon(leadingIcon, contentDescription = null, tint = colour, modifier = Modifier.size(iconSize))
        Text(text, style = style, color = colour)
        if (trailingIcon != null) Icon(trailingIcon, contentDescription = null, tint = colour, modifier = Modifier.size(iconSize * 0.8f))
    }
}

// MARK: - Surfaces

/**
 * A card: white 6% (or the teal surface when [hero]), hairline border, 18 dp corners, 16 dp padding.
 * Import it explicitly (`tvcal.android.ui.components.Card`): material3 has a Card too.
 */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    spacing: Dp = 12.dp,
    hero: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (hero) Theme.surface else Theme.card)
            .border(1.dp, Theme.hairline, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

/** An icon in a round bubble (36, 44 or 64 dp). */
@Composable
fun IconBubble(
    symbol: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = Theme.tealBright,
    size: Dp = 44.dp,
) {
    Box(
        modifier.size(size).clip(CircleShape).background(tint.copy(alpha = 0.1f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(symbol, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

/** "SDR" / "HDR10" ... in the format's colour on a tint of it (Swift ModeBadge, alias FormatBadge). */
@Composable
fun ModeBadge(mode: SignalMode, modifier: Modifier = Modifier, big: Boolean = false) {
    Text(
        mode.label,
        style = Fonts.badge,
        color = mode.tint,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(mode.tint.copy(alpha = 0.13f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/** Swift `typealias FormatBadge = ModeBadge`. */
@Composable
fun FormatBadge(mode: SignalMode, modifier: Modifier = Modifier, big: Boolean = false) =
    ModeBadge(mode, modifier, big)

/** A small eyebrow line above a title: "Set up · 5 of 6", "Before → after". */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, colour: Color = Theme.tealBright) {
    Text(text, style = Fonts.small, color = colour, modifier = modifier)
}

/** TipRow.Tone. Swift `.todo` is [ACTION] (a TODO entry would shadow Kotlin's TODO function). */
enum class TipTone { ACTION, OK, LIMIT }

/**
 * One tip: to do (teal, lightbulb), on target (green, check) or at the TV's limit (orange, minus).
 * `**bold**` in [text] is drawn bold, like the Swift markdown string.
 */
@Composable
fun TipRow(tone: TipTone, text: String, modifier: Modifier = Modifier) {
    val colour = when (tone) {
        TipTone.OK -> Theme.green
        TipTone.LIMIT -> Theme.limit
        TipTone.ACTION -> Theme.tealBright
    }
    val symbol = when (tone) {
        TipTone.OK -> Symbols.checkmarkCircle
        TipTone.LIMIT -> Symbols.minusCircle
        TipTone.ACTION -> Symbols.lightbulb
    }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colour.copy(alpha = 0.07f))
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(symbol, contentDescription = null, tint = colour, modifier = Modifier.size(20.dp))
        Text(
            boldMarkdown(text),
            style = Fonts.captionBrand,
            color = if (tone == TipTone.ACTION) Color.White else colour,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Page dots: 6 dp, the current one 8 dp and yellow. */
@Composable
fun PageDots(count: Int, index: Int, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Page ${index + 1} of $count" },
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until count) {
            val d by animateDpAsState(if (i == index) 8.dp else 6.dp, tween(200), label = "dot")
            Box(
                Modifier
                    .size(d)
                    .clip(CircleShape)
                    .background(if (i == index) Theme.accent else Color.White.copy(alpha = 0.25f)),
            )
        }
    }
}

/** A thin progress track (4 dp) in a colour. [value] 0...1. */
@Composable
fun ProgressTrack(value: Double, modifier: Modifier = Modifier, colour: Color = Theme.tealBright) {
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(CircleShape)
            .background(Theme.hairline),
    ) {
        val f = value.coerceIn(0.0, 1.0).toFloat()
        Box(
            Modifier
                .width(maxWidth * f)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(colour),
        )
    }
}
