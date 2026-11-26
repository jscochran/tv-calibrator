package tvcal.android.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import tvcal.android.storage.PanelType
import tvcal.android.ui.theme.Fonts
import tvcal.android.ui.theme.Symbols
import tvcal.android.ui.theme.TVCalTheme
import tvcal.android.ui.theme.Theme
import tvcal.android.ui.theme.fmt
import tvcal.android.ui.theme.monospacedDigit
import tvcal.android.ui.theme.secondary
import tvcal.android.ui.theme.tracking
import tvcal.android.ui.theme.white
import tvcal.kit.Analysis
import tvcal.kit.ColourMath
import tvcal.kit.GreyResult
import tvcal.kit.Point2
import tvcal.kit.Report
import tvcal.kit.SignalMode
import tvcal.kit.clamp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// Port of Views/ReportVisuals.swift: a measurement as a picture (gauges, the white point, the colours).
// Used by the results, the starting point, the screen home and the shared image.

// MARK: - The dE gauge

/** SwiftUI `.spring(response: 0.9, dampingFraction: 0.82)`: stiffness (2π / response)², mass 1. */
private const val GAUGE_STIFFNESS = 48.74f
private const val GAUGE_DAMPING = 0.82f

/**
 * One score on a scale from "off" (left) to "perfect" (right): orange, white and green zones, a hollow
 * marker where it was, a solid one where it is now. The soft band is the phone's [margin] of error (dE).
 * [animated]: the marker springs from [was] to [value] (response 0.9, damping 0.82, delay 0.25 s); false for
 * the shared image. 22 dp tall.
 */
@Composable
fun DeGauge(
    value: Double?,
    modifier: Modifier = Modifier,
    was: Double? = null,
    kind: ScoreTile.Kind = ScoreTile.Kind.GREY,
    margin: Double? = null,
    animated: Boolean = true,
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (animated && progress.value == 0f) {
            delay(250)
            progress.animateTo(1f, spring(dampingRatio = GAUGE_DAMPING, stiffness = GAUGE_STIFFNESS))
        }
    }
    val now = value?.takeIf { it.isFinite() }
    val before = was?.takeIf { it.isFinite() }
    val bands = ScoreTile.bands(kind)
    /** Where the scale ends (its left edge), in dE: a little past "Good". */
    val top = bands[3] * 1.3
    /** 0 (the left edge, far off) to 1 (the right edge, perfect). */
    fun fraction(de: Double): Float = (1 - clamp(de / top, 0.0, 1.0)).toFloat()
    val greenEdge = bands[if (kind == ScoreTile.Kind.GREY) 1 else 2]
    val markerColour = ScoreTile.tint(value, kind)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(22.dp)
            .clearAndSetSemantics {},
    ) {
        val u = density
        val t = if (animated) progress.value.toDouble() else 1.0
        // The marker travels from where it was to where it is now.
        val shown: Double? = now?.let { n -> val b = before ?: n; b + (n - b) * t }
        val r = 9 * u
        val w = size.width
        val cy = size.height / 2
        val span = max(w - 2 * r, 1f)

        val good = fraction(bands[3])
        val green = fraction(greenEdge)
        val zones = Brush.horizontalGradient(
            0f to Theme.limit.copy(alpha = 0.6f),
            good to Theme.limit.copy(alpha = 0.6f),
            good to Color.White.copy(alpha = 0.32f),
            green to Color.White.copy(alpha = 0.32f),
            green to Theme.green.copy(alpha = 0.75f),
            1f to Theme.green.copy(alpha = 0.75f),
            startX = 0f,
            endX = w,
        )
        drawRoundRect(zones, topLeft = Offset(0f, cy - 3 * u), size = Size(w, 6 * u), cornerRadius = CornerRadius(3 * u))

        if (shown != null && margin != null && margin > 0) {
            val half = max(span * (margin / top).toFloat(), r)
            drawRoundRect(
                Color.White.copy(alpha = 0.13f),
                topLeft = Offset(r + span * fraction(shown) - half, cy - 9 * u),
                size = Size(half * 2, 18 * u),
                cornerRadius = CornerRadius(9 * u),
            )
        }
        if (before != null && shown != null) {
            val a = r + span * fraction(before)
            val b = r + span * fraction(shown)
            drawRoundRect(
                Color.White.copy(alpha = 0.55f),
                topLeft = Offset(min(a, b), cy - 1 * u),
                size = Size(abs(b - a), 2 * u),
                cornerRadius = CornerRadius(1 * u),
            )
            drawCircle(Theme.background, radius = 5 * u, center = Offset(a, cy))
            drawCircle(Color.White.copy(alpha = 0.75f), radius = 5 * u - 0.75f * u, center = Offset(a, cy), style = Stroke(1.5f * u))
        }
        if (shown != null) {
            // In the green zone, the marker blooms.
            val landed = shown < greenEdge
            val c = Offset(r + span * fraction(shown), cy)
            if (landed) drawBlurredPath(circlePathAt(c, r), blur = 7 * u, color = Theme.green.copy(alpha = 0.9f))
            drawCircle(markerColour, radius = r, center = c)
            drawCircle(Theme.background, radius = r - 1 * u, center = c, style = Stroke(2 * u))
        }
    }
}

/** "Greyscale" and "4.8 → 1.2 dE", the word for it in its colour, and the gauge. */
@Composable
fun ScoreGaugeRow(
    label: String,
    value: Double?,
    modifier: Modifier = Modifier,
    was: Double? = null,
    kind: ScoreTile.Kind = ScoreTile.Kind.GREY,
    margin: Double? = null,
    animated: Boolean = true,
) {
    val hasWas = was?.isFinite() == true
    val numbers = if (hasWas) "${fmt(was, 1)} → ${fmt(value, 1)} dE" else "${fmt(value, 1)} dE"
    val word = ScoreTile.word(value, kind)
    Column(
        modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = label
                stateDescription = "$word, $numbers"
            },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, Modifier.alignByBaseline(), style = Fonts.captionBrand.secondary())
            Box(Modifier.weight(1f))
            Text(numbers, Modifier.alignByBaseline(), style = Fonts.small.monospacedDigit().secondary())
        }
        ShrinkToFitText(
            word,
            Fonts.brand(26f, FontWeight.SemiBold).tracking(-0.5f),
            ScoreTile.tint(value, kind),
        )
        DeGauge(value = value, was = was, kind = kind, margin = margin, animated = animated)
    }
}

/** Under the gauges: "Off", the before / after markers, "Perfect". */
@Composable
private fun GaugeLegend(hasBefore: Boolean) {
    val style = Fonts.micro.secondary()
    Row(
        Modifier.fillMaxWidth().clearAndSetSemantics {},
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Off", style = style)
        Box(Modifier.weight(1f))
        if (hasBefore) {
            Box(
                Modifier
                    .size(7.dp)
                    .border(1.2.dp, Color.White.copy(alpha = 0.75f), CircleShape),
            )
            Text("before", style = style)
            Box(
                Modifier
                    .padding(start = 6.dp)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(Color.White),
            )
            Text("after", style = style)
            Box(Modifier.weight(1f))
        }
        Text("Perfect", style = style)
    }
}

// MARK: - The report

private fun brightest(r: Report?): GreyResult? = r?.greys?.maxByOrNull { it.level }

private fun greyUV(g: GreyResult): Point2 = ColourMath.upvp(ColourMath.xyzToXY(g.xyz))

/**
 * A measurement at a glance: the greyscale and colour gauges, the legend, the white point (WhiteTarget)
 * beside the colours (GamutChart), and "Gamma 2.41 · target 2.40" for SDR.
 * [margin]: the phone's margin of error in dE (ProfileService.marginDE); null hides it.
 */
@Composable
fun ReportHero(
    report: Report,
    hdr: Boolean,
    modifier: Modifier = Modifier,
    before: Report? = null,
    margin: Double? = null,
    animated: Boolean = true,
) {
    val colourWas = if (before == null || before.colours.isEmpty()) null else Analysis.colourAverage(before)
    val coloursCaption = "${report.colours.count { it.de < 3 }} of ${report.colours.size} colours on target"
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ScoreGaugeRow(
            label = "Greyscale",
            value = Analysis.greyAverage(report, hdr),
            was = before?.let { Analysis.greyAverage(it, hdr) },
            kind = ScoreTile.Kind.GREY,
            margin = margin,
            animated = animated,
        )
        if (report.colours.isNotEmpty()) {
            ScoreGaugeRow(
                label = "Colours",
                value = Analysis.colourAverage(report),
                was = colourWas,
                kind = ScoreTile.Kind.COLOUR,
                margin = margin,
                animated = animated,
            )
        }
        GaugeLegend(hasBefore = before != null)

        // the charts
        val white = brightest(report)
        if (white != null || report.colours.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (white != null) {
                    Column(
                        Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        WhiteTarget(
                            uv = greyUV(white),
                            d65 = ColourMath.upvp(ColourMath.d65),
                            ok = white.de < 2,
                            modifier = Modifier.widthIn(max = 132.dp),
                            was = brightest(before)?.let { greyUV(it) },
                        )
                        Text("White point", style = Fonts.tiny.secondary())
                    }
                }
                if (report.colours.isNotEmpty()) {
                    Column(
                        Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        GamutChart(
                            dots = GamutChart.dots(report),
                            hdr = hdr,
                            before = before?.let { GamutChart.dots(it) } ?: emptyList(),
                        )
                        Text(coloursCaption, style = Fonts.tiny.secondary())
                    }
                }
            }
        }

        val gamma = if (hdr) null else Analysis.gamma(report)
        if (gamma != null) {
            val text = buildAnnotatedString {
                withStyle(SpanStyle(color = Theme.textSecondary)) { append("Gamma ") }
                withStyle(SpanStyle(color = Color.White, fontFeatureSettings = "tnum")) { append(fmt(gamma, 2)) }
                withStyle(SpanStyle(color = Theme.textSecondary)) { append(" · target 2.40") }
            }
            Text(text, Modifier.semantics(mergeDescendants = true) {}, style = Fonts.small)
        }
    }
}

/** The teal panel a report sits on: tealGradient, 22 dp corners, hairline, 16 dp padding, 14 dp spacing. */
@Composable
fun ReportPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Theme.tealGradient)
            .border(1.dp, Theme.hairline, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

/** "What the numbers mean": closed by default; opens to ReportSummary.deExplainer and the MarginNote. */
@Composable
fun ScoreInfo(modifier: Modifier = Modifier, margin: PanelType? = null) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier
                .heightIn(min = 36.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                ) { open = !open },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Swift: info.circle at the 12 pt small font (~14 pt circle); Material's circle is 20/24 of the box.
            Icon(Symbols.info, contentDescription = null, tint = Theme.tealBright, modifier = Modifier.size(16.dp))
            Text("What the numbers mean", style = Fonts.small.copy(color = Theme.tealBright))
            // Swift: chevron at 10 pt semibold (~9.5 pt wide); Material's chevron is half its box wide.
            Icon(
                if (open) Symbols.chevronUp else Symbols.chevronDown,
                contentDescription = null,
                tint = Theme.tealBright,
                modifier = Modifier.size(18.dp),
            )
        }
        AnimatedVisibility(
            visible = open,
            enter = fadeIn(tween(200)) + expandVertically(tween(200)),
            exit = fadeOut(tween(200)) + shrinkVertically(tween(200)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(ReportSummary.deExplainer, style = Fonts.small.secondary())
                if (margin != null) MarginNote(panel = margin)
            }
        }
    }
}

// MARK: - Small pictures for the steps

/** A little TV with a play button, in a format's colour: "play this on the TV". Fills its frame. */
@Composable
fun TVPlayArt(modifier: Modifier = Modifier, tint: Color = Theme.tealBright) {
    Box(
        modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(Theme.surface)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            val screen = RoundedCornerShape(5.dp)
            Box(
                Modifier
                    .size(width = 64.dp, height = 38.dp)
                    .clip(screen)
                    .background(Theme.background)
                    .border(1.5.dp, tint, screen),
                contentAlignment = Alignment.Center,
            ) {
                // Swift: play.fill at 15 pt (~12 pt glyph); Material's triangle is 14/24 of its box.
                Icon(Symbols.playFill, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            }
            Box(
                Modifier
                    .size(width = 20.dp, height = 2.dp)
                    .clip(CircleShape)
                    .background(tint),
            )
        }
    }
}

/** What to do, in order, as numbered tiles: "1 Tap Measure → 2 Start the video". */
@Composable
fun OrderStrip(steps: List<String>, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        steps.forEachIndexed { i, text ->
            if (i > 0) {
                Icon(
                    Symbols.arrowRight,
                    contentDescription = null,
                    tint = Theme.textSecondary,
                    // Swift: arrow.right at 11 pt semibold (~11 pt wide); Material's arrow is 16/24 of its box.
                    modifier = Modifier.size(16.dp),
                )
            }
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = 52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Theme.card)
                    .padding(10.dp)
                    .semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(Theme.accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${i + 1}", style = Fonts.badge.copy(color = Theme.background))
                }
                Text(text, Modifier.weight(1f), style = Fonts.captionStrong.white())
            }
        }
    }
}

// MARK: - Previews

@Preview(widthDp = 380, backgroundColor = 0xFF05171A, showBackground = true)
@Composable
private fun ReportHeroPreview() {
    TVCalTheme {
        ReportPanel {
            ReportHero(report = previewReport(0.3), hdr = false, before = previewReport(1.0), margin = 0.8, animated = false)
        }
    }
}

@Preview(widthDp = 380, backgroundColor = 0xFF05171A, showBackground = true)
@Composable
private fun StepArtPreview() {
    TVCalTheme {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.width(92.dp).height(64.dp)) { TVPlayArt(tint = Theme.accent) }
            OrderStrip(listOf("Tap Measure", "Start the video"))
            ModeBadge(SignalMode.SDR)
        }
    }
}
