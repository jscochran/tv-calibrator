package tvcal.android.ui.components

import android.graphics.BlurMaskFilter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tvcal.android.LocalApp
import tvcal.android.storage.PanelType
import tvcal.android.ui.theme.Fonts
import tvcal.android.ui.theme.Symbols
import tvcal.android.ui.theme.TVCalTheme
import tvcal.android.ui.theme.Theme
import tvcal.android.ui.theme.fmt
import tvcal.android.ui.theme.secondary
import tvcal.android.ui.theme.signed
import tvcal.android.ui.theme.tracking
import tvcal.android.ui.theme.typographicMinus
import tvcal.android.ui.theme.white
import tvcal.kit.Analysis
import tvcal.kit.ColourMath
import tvcal.kit.ColourResult
import tvcal.kit.GreyResult
import tvcal.kit.Point2
import tvcal.kit.Report
import tvcal.kit.Vec3
import tvcal.kit.clamp
import tvcal.kit.swiftRounded
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sqrt

// Port of Views/Components.swift: score tiles, centred balance bars, the white-point target, the colour
// chart and the greyscale chart (Swift Charts and Canvas → Compose Canvas), and the results blocks.
// Numbers in text: fmt/signed (cfmt). Swift .rounded() → swiftRounded.
// Swift sizes are points; inside a Canvas they are multiplied by the density (px per dp).

// MARK: - Shared drawing and text helpers (used by the other files in this package)

/** Swift `.lineLimit(1).minimumScaleFactor(minScale)`: one line, shrunk (down to [minScale]) to fit. */
@Composable
internal fun ShrinkToFitText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    minScale: Float = 0.7f,
    textAlign: TextAlign? = null,
) {
    val full = style.fontSize
    Text(
        text,
        modifier,
        color = color,
        style = style,
        maxLines = 1,
        textAlign = textAlign,
        autoSize = TextAutoSize.StepBased(minFontSize = full * minScale, maxFontSize = full, stepSize = 0.5.sp),
    )
}

/** A circle as a path. */
internal fun circlePathAt(c: Offset, r: Float): Path =
    Path().apply { addOval(Rect(c.x - r, c.y - r, c.x + r, c.y + r)) }

/**
 * Swift `ctx.drawLayer { l in l.addFilter(.blur(radius:)); l.fill(...) }`: fills [path] blurred by [blur] px
 * (BlurMaskFilter, hardware-accelerated from API 28), in [color] or [brush], at [alpha].
 */
internal fun DrawScope.drawBlurredPath(
    path: Path,
    blur: Float,
    color: Color = Color.White,
    brush: Brush? = null,
    alpha: Float = 1f,
) {
    val paint = Paint()
    if (brush != null) brush.applyTo(size, paint, alpha) else paint.color = color.copy(alpha = color.alpha * alpha)
    if (blur > 0f) paint.asFrameworkPaint().maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
    drawContext.canvas.drawPath(path, paint)
}

/** Swift `ctx.drawLayer { l in l.opacity = ... }`: draws [block] into a layer composited at [layerAlpha]. */
internal inline fun DrawScope.withLayerAlpha(layerAlpha: Float, block: DrawScope.() -> Unit) {
    val canvas = drawContext.canvas
    canvas.saveLayer(Rect(Offset.Zero, size), Paint().apply { this.alpha = layerAlpha })
    block()
    canvas.restore()
}

/** Swift clamp for Float that never throws (coerceIn throws when lo > hi). */
internal fun clampF(x: Float, lo: Float, hi: Float): Float = min(max(x, lo), hi)

// MARK: - Score tile

/** ScoreTile's static helpers (Swift `static func bands/word/tint`, `enum Kind`). */
object ScoreTile {
    /** Greys and colours. Colours get wider bands. */
    enum class Kind { GREY, COLOUR }

    /** Upper limits for Perfect, Excellent, Very good and Good: grey [1, 2, 4, 7], colour [1, 3, 6, 10]. */
    fun bands(kind: Kind): List<Double> =
        if (kind == Kind.GREY) listOf(1.0, 2.0, 4.0, 7.0) else listOf(1.0, 3.0, 6.0, 10.0)

    /** "Perfect" / "Excellent" / "Very good" / "Good" / "Noticeably off", "–" for nil or non-finite. */
    fun word(de: Double?, kind: Kind = Kind.GREY): String {
        if (de == null || !de.isFinite()) return "–"
        val b = bands(kind)
        return if (de < b[0]) "Perfect" else if (de < b[1]) "Excellent" else if (de < b[2]) "Very good"
        else if (de < b[3]) "Good" else "Noticeably off"
    }

    /** Green up to Excellent (greys) / Very good (colours), white for Good, Theme.limit beyond. */
    fun tint(de: Double?, kind: Kind = Kind.GREY): Color {
        if (de == null || !de.isFinite()) return Theme.textSecondary
        val b = bands(kind)
        return if (de < b[if (kind == Kind.GREY) 1 else 2]) Theme.green else if (de < b[3]) Color.White else Theme.limit
    }
}

/**
 * A result tile. dE scores ([digits] 1) show a word with the dE beside it and "was 4.8" at the top
 * right; other numbers (gamma) show the number big with "target 2.40 · was 2.18" beside it.
 */
@Composable
fun ScoreTile(
    label: String,
    value: Double?,
    modifier: Modifier = Modifier,
    was: Double? = null,
    digits: Int = 1,
    /** dE tiles: replaces the score word. Number tiles: the line beside the number ("target 2.40"). */
    caption: String? = null,
    /** Number tiles: appended to the number and to "was", e.g. "%". */
    unit: String = "",
    kind: ScoreTile.Kind = ScoreTile.Kind.GREY,
) {
    val hasValue = value?.isFinite() == true
    val hasWas = was?.isFinite() == true
    Card(modifier.semantics(mergeDescendants = true) {}, spacing = 12.dp) {
        if (digits == 1) {
            // scoreContent
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, Modifier.alignByBaseline().weight(1f), style = Fonts.captionBrand.secondary())
                if (hasWas) {
                    Text("was " + fmt(was, digits), Modifier.alignByBaseline(), style = Fonts.small.secondary())
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ShrinkToFitText(
                    caption ?: ScoreTile.word(value, kind),
                    Fonts.brand(27f).tracking(-0.7f),
                    ScoreTile.tint(value, kind),
                    Modifier.alignByBaseline().weight(1f, fill = false),
                )
                if (hasValue) {
                    Text("${fmt(value, digits)} dE", Modifier.alignByBaseline(), style = Fonts.callout14.secondary())
                }
            }
        } else {
            // numberContent
            val parts = buildList {
                if (caption != null) add(caption)
                if (hasWas) add("was " + fmt(was, digits) + unit)
            }
            val detail = parts.joinToString(" · ")
            Text(label, style = Fonts.captionBrand.secondary())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShrinkToFitText(
                    fmt(value, digits) + (if (hasValue) unit else ""),
                    Fonts.reading(34f),
                    Color.White,
                    Modifier.alignByBaseline(),
                )
                Box(Modifier.weight(1f))
                if (detail.isNotEmpty()) {
                    Text(
                        detail,
                        Modifier.alignByBaseline(),
                        style = Fonts.captionBrand.secondary(),
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
    }
}

// MARK: - Balance bar

/**
 * One channel: its name and a big reading over a centred track (left too little, right too much, the green
 * zone the tolerance). [value] is a fraction (0.03 = 3% high). Reading "+3.0%" with a real minus sign.
 */
@Composable
fun BalanceBar(
    label: String,
    value: Double?,
    colour: Color,
    modifier: Modifier = Modifier,
    fullScale: Double = 0.05,
    tolerance: Double = 0.012,
    valueText: String? = null,
    /** The reading's size in sp (the design uses 34 and 38). */
    readingSize: Float = 34f,
) {
    val v = value?.takeIf { it.isFinite() }
    val onTarget = v != null && abs(v) <= tolerance
    val reading = valueText ?: if (v == null) "–" else typographicMinus(signed(v * 100, 1) + "%")
    val fg = if (onTarget) Theme.green else Color.White
    val description = "$label: $reading" + if (onTarget) ", on target" else ""
    Column(
        modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                label,
                Modifier.weight(1f),
                style = Fonts.headlineBrand.white(),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (onTarget) {
                    // Swift: checkmark at readingSize * 0.62 pt semibold; Material's tick is only ~0.73 of its box
                    // wide, so the box is a third larger to give the same glyph.
                    Icon(Symbols.checkmark, contentDescription = null, tint = fg, modifier = Modifier.size((readingSize * 0.62f * 1.3f).dp))
                }
                ShrinkToFitText(reading, Fonts.reading(readingSize).tracking(-1f), fg, minScale = 0.5f)
            }
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(20.dp)
                .clip(RoundedCornerShape(6.dp)),
        ) {
            val u = density
            val w = size.width
            val h = size.height
            val mid = w / 2
            val off = clamp((v ?: 0.0) / fullScale, -1.0, 1.0).toFloat() * mid
            val tol = min(max((tolerance / fullScale).toFloat() * mid, 6 * u), mid)
            val len = if (onTarget) 8 * u else max(abs(off), 4 * u)
            drawRoundRect(Color.White.copy(alpha = 0.07f), cornerRadius = CornerRadius(6 * u))
            drawRect(Theme.green.copy(alpha = 0.16f), topLeft = Offset(mid - tol, 0f), size = Size(tol * 2, h))
            if (v != null) {
                val x = if (onTarget) mid - len / 2 else if (off < 0) mid - len else mid
                drawRoundRect(
                    if (onTarget) Theme.green else colour,
                    topLeft = Offset(x, (h - 10 * u) / 2),
                    size = Size(len, 10 * u),
                    cornerRadius = CornerRadius(4 * u),
                )
            }
            drawRect(Color.White, topLeft = Offset(mid - 1 * u, 0f), size = Size(2 * u, h))
        }
    }
}

/** "Too little · On target · Too much" under a set of balance bars. */
@Composable
fun BalanceScaleLabels(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().clearAndSetSemantics {}) {
        Text("Too little", style = Fonts.tiny.secondary())
        Box(Modifier.weight(1f))
        Text("On target", style = Fonts.tiny.copy(color = Theme.green))
        Box(Modifier.weight(1f))
        Text("Too much", style = Fonts.tiny.secondary())
    }
}

// MARK: - White-point target

/**
 * The app icon's colours as a wheel around white (Swift HueWheel.shading, a conic gradient). Compose:
 * Brush.sweepGradient(stops, center = white), drawn rotated by the first hue's angle.
 */
object HueWheel {
    private val red = Color(1f, 0.10f, 0.10f)
    private val green = Color(0.13f, 1f, 0.24f)
    private val blue = Color(0.23f, 0.17f, 1f)
    private val cyan = Color(0f, 0.88f, 1f)
    private val magenta = Color(1f, 0.06f, 0.48f)
    private val yellow = Color(1f, 0.93f, 0f)

    /** corners: the red, green and blue primaries on screen; white: the white point on screen. */
    fun shading(corners: List<Offset>, white: Offset): Brush {
        if (corners.size != 3) return SolidColor(Color.Transparent)
        val twoPi = 2 * PI
        fun angle(p: Offset): Double {
            val a = atan2((p.y - white.y).toDouble(), (p.x - white.x).toDouble())
            return if (a < 0) a + twoPi else a
        }
        fun across(a: Double): Double = if (a + PI >= twoPi) a - PI else a + PI
        val r = angle(corners[0])
        val g = angle(corners[1])
        val b = angle(corners[2])
        val hues = listOf(
            r to red, across(r) to cyan, g to green,
            across(g) to magenta, b to blue, across(b) to yellow,
        ).sortedBy { it.first }
        // Start the wheel at its first colour, and come back round to it.
        val first = hues[0]
        val stops = hues.map { ((it.first - first.first) / twoPi).toFloat() to it.second } + (1f to first.second)
        return RotatedSweep(white, Math.toDegrees(first.first).toFloat(), stops)
    }
}

/** A sweep (conic) gradient around [center], its start turned [degrees] clockwise from 3 o'clock. */
private class RotatedSweep(
    private val center: Offset,
    private val degrees: Float,
    private val stops: List<Pair<Float, Color>>,
) : ShaderBrush() {
    override fun createShader(size: Size): Shader {
        val shader = android.graphics.SweepGradient(
            center.x,
            center.y,
            IntArray(stops.size) { stops[it].second.toArgb() },
            FloatArray(stops.size) { stops[it].first },
        )
        shader.setLocalMatrix(android.graphics.Matrix().apply { setRotate(degrees, center.x, center.y) })
        return shader
    }
}

/** Inner ring radius in u'v' (about dE 2); the outer ring is the design's 2x. */
private const val WT_INNER = 0.0045
private const val WT_OUTER = WT_INNER * 130 / 64.24

/**
 * The white point against D65, in u'v': the teal inner ring is the target zone; the dot is the TV's white,
 * yellow while off, green with a tick once inside ([ok]). [was]: where it started (a hollow dot). Square.
 */
@Composable
fun WhiteTarget(
    uv: Point2,
    d65: Point2,
    ok: Boolean,
    modifier: Modifier = Modifier,
    was: Point2? = null,
) {
    val description = if (ok) "White is on target" else "White is off target"
    Box(modifier.aspectRatio(1f).clearAndSetSemantics { contentDescription = description }) {
        Canvas(Modifier.fillMaxSize()) {
            val u = density
            val side = min(size.width, size.height)
            val c = center
            val outerR = side / 2 * (130f / 146f)
            val sc = outerR / WT_OUTER.toFloat()
            val innerR = WT_INNER.toFloat() * sc

            // Between the rings, a faint tint of the colour that lies each way from white; at the centre,
            // the icon's white hot spot.
            val primaries = ColourMath.Gamut.REC709.primaries.map { ColourMath.upvp(it) }.map {
                Offset(c.x + ((it.x - d65.x) * sc).toFloat(), c.y - ((it.y - d65.y) * sc).toFloat())
            }
            val band = Path().apply {
                fillType = PathFillType.EvenOdd
                addOval(Rect(c, outerR - 2 * u))
                addOval(Rect(c, innerR + 2 * u))
            }
            drawBlurredPath(band, blur = max(3 * u, side * 0.04f), brush = HueWheel.shading(primaries, c), alpha = 0.24f)
            val hot = max(innerR * 1.5f, 0.01f)
            drawCircle(
                Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.6f), Color.White.copy(alpha = 0.18f), Color.White.copy(alpha = 0f)),
                    center = c,
                    radius = hot,
                ),
                radius = hot,
                center = c,
            )

            drawCircle(Color.White.copy(alpha = 0.2f), radius = outerR, center = c, style = Stroke(1 * u))
            drawCircle(Theme.tealBright, radius = innerR, center = c, style = Stroke(1.5f * u))

            val crossColour = Color.White.copy(alpha = 0.6f)
            drawLine(crossColour, Offset(c.x - 10 * u, c.y), Offset(c.x + 10 * u, c.y), strokeWidth = 1 * u)
            drawLine(crossColour, Offset(c.x, c.y - 10 * u), Offset(c.x, c.y + 10 * u), strokeWidth = 1 * u)

            // a white, kept inside the outer ring so it never leaves the picture
            fun place(p: Point2): Offset {
                var dx = ((p.x - d65.x) * sc).toFloat()
                var dy = (-(p.y - d65.y) * sc).toFloat()
                val dist = sqrt(dx * dx + dy * dy)
                if (dist > outerR && dist > 0) {
                    dx *= outerR / dist
                    dy *= outerR / dist
                }
                return Offset(c.x + dx, c.y + dy)
            }
            val m = place(uv)
            val dotR = max(5 * u, side * 5 / 146)
            if (was != null) {
                val b = place(was)
                drawLine(Color.White.copy(alpha = 0.45f), b, m, strokeWidth = 1.2f * u)
                drawCircle(Theme.background, radius = dotR * 0.8f, center = b)
                drawCircle(Color.White.copy(alpha = 0.75f), radius = dotR * 0.8f, center = b, style = Stroke(1.3f * u))
            }
            if (ok) {
                // on target: the dot blooms
                drawBlurredPath(circlePathAt(m, dotR * 1.8f), blur = dotR, color = Theme.green.copy(alpha = 0.85f))
            }
            drawCircle(if (ok) Theme.green else Theme.accent, radius = dotR, center = m)

            if (ok) {
                val t = Offset(m.x + dotR + 4 * u, m.y - 2 * u)
                val tick = Path().apply {
                    moveTo(t.x, t.y)
                    lineTo(t.x + 3 * u, t.y + 3 * u)
                    lineTo(t.x + 9 * u, t.y - 4 * u)
                }
                drawPath(tick, Theme.green, style = Stroke(1.6f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        Text(
            "D65",
            Modifier.align(Alignment.BottomCenter).padding(bottom = 1.dp),
            style = Fonts.micro.secondary(),
        )
    }
}

// MARK: - Colour chart

/** GamutChart's helpers (Swift `struct Dot`, `static func colour`, `static func dots`). */
object GamutChart {
    data class Dot(
        val id: String,
        val uv: Point2,
        val target: Point2,
        /** How far off (dE), when known. */
        val de: Double? = null,
    ) {
        /** de < 3 when known, else within 0.004 u'v' of the target. */
        val onTarget: Boolean
            get() {
                if (de != null && de.isFinite()) return de < 3
                val du = uv.x - target.x
                val dv = uv.y - target.y
                return sqrt(du * du + dv * dv) < 0.004
            }
    }

    /** The design's colour for each patch (RED red, GREEN green, BLUE blue, YELLOW accent, MAGENTA dolbyVision, else swatch). */
    fun colour(id: String): Color = when (id.uppercase()) {
        "RED" -> Theme.red
        "GREEN" -> Theme.green
        "BLUE" -> Theme.blue
        "YELLOW" -> Theme.accent
        "MAGENTA" -> Theme.dolbyVision
        else -> Theme.swatch(id.uppercase())
    }

    fun dots(rep: Report): List<Dot> =
        rep.colours.map { Dot(id = it.name, uv = ColourMath.upvp(it.xy), target = ColourMath.upvp(it.txy), de = it.de) }
}

/** Swift `String.capitalized` for the colour ids ("RED" -> "Red"). */
private fun capitalizedWords(s: String): String =
    s.split(" ").joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercaseChar() } }

/**
 * The colours in CIE 1976 u'v' over the Rec.709 (SDR) or P3 (HDR) triangle: rings are targets, dots the
 * TV, a line joins each pair, a green tick marks a colour on target. [highlight] (a colour id) brings one
 * forward. [before]: hollow dots where each started. Aspect ratio 1.4.
 */
@Composable
fun GamutChart(
    dots: List<GamutChart.Dot>,
    hdr: Boolean,
    modifier: Modifier = Modifier,
    highlight: String? = null,
    before: List<GamutChart.Dot> = emptyList(),
) {
    val measurer = rememberTextMeasurer()
    val active = highlight ?: if (dots.size == 1) dots.first().id else null
    val summary = when {
        dots.isEmpty() -> "Colour chart"
        dots.size == 1 -> {
            val d = dots.first()
            "Colour chart: ${capitalizedWords(d.id)} is ${if (d.onTarget) "on target" else "off target"}"
        }
        else -> "Colour chart: ${dots.count { it.onTarget }} of ${dots.size} colours on target"
    }
    val labelStyle = Fonts.micro.secondary()
    val gamutLabel = if (hdr) "P3 (HDR)" else "Rec. 709 (SDR)"
    Canvas(modifier.aspectRatio(1.4f).clearAndSetSemantics { contentDescription = summary }) {
        val u = density
        val w = size.width
        val h = size.height
        val gamut = (if (hdr) ColourMath.Gamut.P3 else ColourMath.Gamut.REC709).primaries.map { ColourMath.upvp(it) }
        val fit = gamut + dots.map { it.target }
        val uMin = fit.minOf { it.x }
        val uMax = fit.maxOf { it.x }
        val vMin = fit.minOf { it.y }
        val vMax = fit.maxOf { it.y }
        val pad = 16 * u
        // one scale for both axes, so the triangle keeps its true shape
        val sc = min(
            (w - pad * 2) / max(uMax - uMin, 0.01).toFloat(),
            (h - pad * 2) / max(vMax - vMin, 0.01).toFloat(),
        )
        val ox = (w - (uMax - uMin).toFloat() * sc) / 2
        val oy = (h - (vMax - vMin).toFloat() * sc) / 2

        fun at(p: Point2): Offset = Offset(ox + ((p.x - uMin) * sc).toFloat(), h - oy - ((p.y - vMin) * sc).toFloat())
        fun inside(p: Offset): Offset = Offset(clampF(p.x, 6 * u, w - 6 * u), clampF(p.y, 6 * u, h - 6 * u))
        /** Where the line from `v` through `wt` meets the line through `a` and `b`. */
        fun hit(v: Offset, wt: Offset, a: Offset, b: Offset): Offset {
            val d1 = Offset(wt.x - v.x, wt.y - v.y)
            val d2 = Offset(b.x - a.x, b.y - a.y)
            val den = d1.x * d2.y - d1.y * d2.x
            if (abs(den) <= 0.000001f) return wt
            val t = ((a.x - v.x) * d2.y - (a.y - v.y) * d2.x) / den
            return Offset(v.x + d1.x * t, v.y + d1.y * t)
        }

        // the gamut triangle, and a line from each primary through white to the opposite side
        val corners = gamut.map { at(it) }
        val tri = Path().apply {
            moveTo(corners[0].x, corners[0].y)
            for (i in 1 until corners.size) lineTo(corners[i].x, corners[i].y)
            close()
        }
        val wp = at(ColourMath.upvp(ColourMath.d65))
        // Inside the triangle, the icon's colours: each hue toward its own corner, white in the middle.
        if (corners.size == 3) {
            val wheel = HueWheel.shading(corners, wp)
            withLayerAlpha(if (active == null) 0.3f else 0.2f) {
                clipPath(tri) {
                    drawPath(tri, wheel)
                    drawPath(
                        tri,
                        Brush.radialGradient(
                            listOf(Color.White.copy(alpha = 0.9f), Color.White.copy(alpha = 0f)),
                            center = wp,
                            radius = max(min(w, h) * 0.3f, 0.01f),
                        ),
                    )
                }
            }
        }
        // which triangle this is
        val label = measurer.measure(gamutLabel, labelStyle)
        drawText(label, topLeft = Offset(w - 2 * u - label.size.width, h - 2 * u - label.size.height))
        if (corners.size == 3) {
            val medians = Path()
            for (i in 0 until 3) {
                medians.moveTo(corners[i].x, corners[i].y)
                val e = hit(corners[i], wp, corners[(i + 1) % 3], corners[(i + 2) % 3])
                medians.lineTo(e.x, e.y)
            }
            drawPath(medians, Color.White.copy(alpha = 0.13f), style = Stroke(1 * u))
        }
        drawPath(tri, Color.White.copy(alpha = 0.45f), style = Stroke(1 * u))

        // the current colour last, on top
        val order = dots.filter { it.id != active } + dots.filter { it.id == active }
        for (d in order) {
            val col = GamutChart.colour(d.id)
            val isActive = d.id == active
            val dim = active != null && !isActive
            val t = at(d.target)
            val m = inside(at(d.uv))
            val ringR = (if (isActive) 9f else 8f) * u
            if (d.onTarget && !dim) {
                // on target: the ring blooms in its colour
                drawBlurredPath(circlePathAt(t, ringR + 3 * u), blur = 6 * u, color = col.copy(alpha = 0.8f))
                drawCircle(col.copy(alpha = 0.2f), radius = ringR, center = t)
            }
            drawCircle(
                col.copy(alpha = if (dim) 0.45f else 0.9f),
                radius = ringR,
                center = t,
                style = Stroke((if (isActive) 2f else 1.2f) * u),
            )
            drawLine(col.copy(alpha = if (dim) 0.5f else 1f), t, m, strokeWidth = (if (isActive) 1.5f else 1f) * u)
            val was = before.firstOrNull { it.id == d.id }
            if (was != null) {
                val b = inside(at(was.uv))
                drawLine(
                    col.copy(alpha = 0.45f),
                    b,
                    m,
                    strokeWidth = 1 * u,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(3 * u, 3 * u)),
                )
                drawCircle(Theme.background, radius = 3 * u, center = b)
                drawCircle(col.copy(alpha = 0.8f), radius = 3 * u, center = b, style = Stroke(1.2f * u))
            }
            drawCircle(col.copy(alpha = if (dim) 0.65f else 1f), radius = (if (isActive) 5f else 4f) * u, center = m)
            if (d.onTarget) {
                val k = Offset(t.x + ringR + 3 * u, t.y - 3 * u)
                val tick = Path().apply {
                    moveTo(k.x, k.y)
                    lineTo(k.x + 2.5f * u, k.y + 2.5f * u)
                    lineTo(k.x + 7.5f * u, k.y - 3 * u)
                }
                drawPath(tick, Theme.green, style = Stroke(1.4f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

// MARK: - Greyscale chart

/** One point of the greyscale chart (Swift GreyBalanceChart.Point). */
private data class GreyPoint(val level: Double, val value: Double, val channel: String, val series: String)

/**
 * Swift Charts' `.interpolationMethod(.monotone)`: a monotone cubic through the points (d3's monotoneX
 * slopes), so a curve never overshoots between two readings.
 */
private fun monotonePath(xs: List<Float>, ys: List<Float>): Path {
    val path = Path()
    val n = xs.size
    if (n == 0) return path
    path.moveTo(xs[0], ys[0])
    if (n == 1) return path
    if (n == 2) {
        path.lineTo(xs[1], ys[1])
        return path
    }
    val s = FloatArray(n - 1) { i ->
        val dx = xs[i + 1] - xs[i]
        if (dx != 0f) (ys[i + 1] - ys[i]) / dx else 0f
    }
    val m = FloatArray(n)
    for (i in 1 until n - 1) {
        val h0 = xs[i] - xs[i - 1]
        val h1 = xs[i + 1] - xs[i]
        val s0 = s[i - 1]
        val s1 = s[i]
        val p = if (h0 + h1 != 0f) (s0 * h1 + s1 * h0) / (h0 + h1) else 0f
        val v = (sign(s0) + sign(s1)) * minOf(abs(s0), abs(s1), 0.5f * abs(p))
        m[i] = if (v.isFinite()) v else 0f
    }
    m[0] = (3 * s[0] - m[1]) / 2
    m[n - 1] = (3 * s[n - 2] - m[n - 2]) / 2
    for (i in 0 until n - 1) {
        val dx = (xs[i + 1] - xs[i]) / 3
        path.cubicTo(xs[i] + dx, ys[i] + dx * m[i], xs[i + 1] - dx, ys[i + 1] - dx * m[i + 1], xs[i + 1], ys[i + 1])
    }
    return path
}

/**
 * Red, green and blue in each grey (100 = on target, the white line); 160 dp tall. With [before], its
 * traces sit faded behind. SDR greys below 0.099 are left out. Monotone curves, X labels "10%"...
 */
@Composable
fun GreyBalanceChart(
    report: Report,
    hdr: Boolean,
    modifier: Modifier = Modifier,
    before: Report? = null,
) {
    val measurer = rememberTextMeasurer()
    fun pts(rep: Report, series: String): List<GreyPoint> =
        rep.greys.filter { hdr || it.level >= 0.099 }.flatMap { g ->
            listOf(
                GreyPoint(g.level * 100, g.bal.x * 100, "R", series + "R"),
                GreyPoint(g.level * 100, g.bal.y * 100, "G", series + "G"),
                GreyPoint(g.level * 100, g.bal.z * 100, "B", series + "B"),
            )
        }
    // before first, so the after traces draw on top
    val points = ((before?.let { pts(it, "was") } ?: emptyList()) + pts(report, "now"))
        .filter { it.value.isFinite() && it.level.isFinite() }
    val dev = points.maxOfOrNull { abs(it.value - 100) } ?: 0.0
    val grid = max(5.0, ceil(dev / 5) * 5) // the faint lines: ±grid %
    val span = grid * 1.45
    val lo = points.minOfOrNull { it.level } ?: 0.0
    val hi = max(points.maxOfOrNull { it.level } ?: 100.0, lo + 1)
    val ticks = (if (hdr) listOf(0.0, 25.0, 50.0, 75.0, 100.0) else listOf(10.0, 30.0, 50.0, 70.0, 100.0))
        .filter { it >= lo - 0.5 && it <= hi + 0.5 }
    val series = points.groupBy { it.series }
    val labelStyle = Fonts.tiny.secondary()

    Canvas(
        modifier
            .fillMaxWidth()
            .height(160.dp)
            .clearAndSetSemantics { contentDescription = "Greyscale chart: red, green and blue against the target line" },
    ) {
        val u = density
        val w = size.width
        val labels = ticks.map { measurer.measure("${swiftRounded(it).toInt()}%", labelStyle) }
        val labelH = (labels.maxOfOrNull { it.size.height } ?: 0).toFloat()
        val plotH = max(size.height - labelH - 4 * u, 1f)
        fun xOf(level: Double): Float = ((level - lo) / (hi - lo)).toFloat() * w
        fun yOf(v: Double): Float = plotH * (1 - ((v - (100 - span)) / (2 * span)).toFloat())

        drawLine(Theme.hairline, Offset(0f, yOf(100 + grid)), Offset(w, yOf(100 + grid)), strokeWidth = 1 * u)
        drawLine(Theme.hairline, Offset(0f, yOf(100 - grid)), Offset(w, yOf(100 - grid)), strokeWidth = 1 * u)
        for ((name, line) in series) {
            val was = name.startsWith("was")
            val path = monotonePath(line.map { xOf(it.level) }, line.map { yOf(it.value) })
            drawPath(
                path,
                Theme.channel(line.first().channel).copy(alpha = if (was) 0.3f else 1f),
                style = Stroke((if (was) 1.5f else 2f) * u, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
        drawLine(Theme.textSecondary, Offset(0f, yOf(100.0)), Offset(w, yOf(100.0)), strokeWidth = 1 * u)

        for ((i, tick) in ticks.withIndex()) {
            val l = labels[i]
            val x = clampF(xOf(tick) - l.size.width / 2f, 0f, w - l.size.width)
            drawText(l, topLeft = Offset(x, plotH + 4 * u))
        }
    }
}

// MARK: - Results

/** The score tiles for one measurement: greyscale, colours, and gamma (SDR only, "target 2.40"). */
@Composable
fun ResultScores(report: Report, hdr: Boolean, modifier: Modifier = Modifier, before: Report? = null) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScoreTile(
            label = "Greyscale",
            value = Analysis.greyAverage(report, hdr),
            was = before?.let { Analysis.greyAverage(it, hdr) },
        )
        if (report.colours.isNotEmpty()) {
            ScoreTile(
                label = "Colours",
                value = Analysis.colourAverage(report),
                was = before?.let { b -> if (b.colours.isEmpty()) null else Analysis.colourAverage(b) },
                kind = ScoreTile.Kind.COLOUR,
            )
        }
        // HDR brightness isn't scored: no step adjusts it, and TVs differ in how bright they show HDR.
        if (!hdr) {
            ScoreTile(
                label = "Gamma",
                value = Analysis.gamma(report),
                was = before?.let { Analysis.gamma(it) },
                digits = 2,
                caption = "target 2.40",
            )
        }
    }
}

/**
 * The measurement's warnings, in the limit colour. Like the Swift (a bare ForEach), each warning is its own
 * child of the caller's Column, so the caller's spacing sits between them; [modifier] goes on each one.
 */
@Composable
fun ResultWarnings(warnings: List<String>, modifier: Modifier = Modifier) {
    for (w in warnings) {
        Text(w, modifier.fillMaxWidth(), style = Fonts.small.copy(color = Theme.limit))
    }
}

/** The greyscale and colour detail cards ("12 Results · charts"). [showsColours] false where it's already shown. */
@Composable
fun ResultCharts(
    report: Report,
    hdr: Boolean,
    modifier: Modifier = Modifier,
    before: Report? = null,
    showsColours: Boolean = true,
) {
    val greyTitle = run {
        val lv = report.greys.filter { hdr || it.level >= 0.099 }.map { it.level * 100 }
        val lo = lv.minOrNull()
        val hi = lv.maxOrNull()
        if (lo == null || hi == null) "Greyscale"
        else "Greyscale · ${swiftRounded(lo).toInt()}–${swiftRounded(hi).toInt()}%"
    }
    val colourTitle = run {
        val total = report.colours.size
        val ok = report.colours.count { it.de < 3 }
        if (ok == total) (if (total == 6) "Colours · all six on target" else "Colours · all on target")
        else "Colours · $ok of $total on target"
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (report.greys.isNotEmpty()) {
            Card {
                Text(greyTitle, style = Fonts.bodyStrong.white())
                GreyBalanceChart(report = report, hdr = hdr, before = before)
                Text(
                    if (before != null) "Before faded · after solid · flat target line" else "Flat line is the target",
                    style = Fonts.small.secondary(),
                )
            }
        }
        if (showsColours && report.colours.isNotEmpty()) {
            Card {
                Text(colourTitle, style = Fonts.bodyStrong.white())
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    GamutChart(
                        dots = GamutChart.dots(report),
                        hdr = hdr,
                        modifier = Modifier.widthIn(max = 340.dp),
                        before = before?.let { GamutChart.dots(it) } ?: emptyList(),
                    )
                }
                Text(
                    if (before != null) "Rings: target · hollow: before · dots: after" else "Rings: target · dots: your TV",
                    style = Fonts.small.secondary(),
                )
            }
        }
    }
}

object ReportSummary {
    /** The dE explanation under the score tiles. */
    const val deExplainer =
        "How far off (dE): under 1 is perfect. Greys under 2 and colours under 3 are excellent; TVs rarely match every colour exactly."
}

/**
 * Scores, warnings and charts for one measurement: ReportPanel { ReportHero }, warnings, ResultCharts
 * (no colours), ScoreInfo. [margin]: the kind of screen for the phone's margin of error; null hides it.
 */
@Composable
fun ReportSummary(
    report: Report,
    hdr: Boolean,
    modifier: Modifier = Modifier,
    before: Report? = null,
    margin: PanelType? = null,
) {
    val profiles = LocalApp.current.profiles
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ReportPanel {
            ReportHero(
                report = report,
                hdr = hdr,
                before = before,
                margin = margin?.let { profiles.marginDE(it) },
            )
        }
        ResultWarnings(warnings = report.warnings)
        ResultCharts(report = report, hdr = hdr, before = before, showsColours = false)
        ScoreInfo(margin = margin)
    }
}

/** The phone's margin of error under the scores (app.profiles.marginNote(panel)); nothing when null. */
@Composable
fun MarginNote(panel: PanelType, modifier: Modifier = Modifier) {
    val text = LocalApp.current.profiles.marginNote(panel) ?: return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Icon(
            Symbols.info,
            contentDescription = null,
            tint = Theme.tealBright,
            // Swift: info.circle at 13 pt (~15 pt circle); Material's circle is 20/24 of its box.
            modifier = Modifier.size(17.dp),
        )
        Text(text, Modifier.weight(1f), style = Fonts.small.secondary())
    }
}

// MARK: - Previews (a made-up report: no LocalApp needed)

/** A plausible SDR report for the previews: a slightly blue greyscale and colours a little off. */
internal fun previewReport(shift: Double = 1.0): Report {
    val levels = listOf(0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0)
    val greys = levels.mapIndexed { i, lv ->
        val y = 100 * lv.pow(2.4)
        val r = 1 - 0.02 * shift * (1 + i % 3)
        val b = 1 + 0.03 * shift * (1 + (i + 1) % 2)
        val xy = Point2(ColourMath.d65.x - 0.002 * shift, ColourMath.d65.y - 0.003 * shift)
        GreyResult(
            id = "G${(lv * 100).toInt()}",
            level = lv,
            name = "${(lv * 100).toInt()}% GREY",
            xyz = ColourMath.xyYToXYZ(xy, y),
            Y = y * (1 + 0.01 * shift),
            targetY = y,
            bal = Vec3(r, 1.0, b),
            rgb = Vec3(r, 1.0, b),
            de = 2.4 * shift,
            clipped = false,
        )
    }
    fun colour(name: String, tx: Double, ty: Double, dx: Double, dy: Double, de: Double): ColourResult {
        val t = Point2(tx, ty)
        val m = Point2(tx + dx * shift, ty + dy * shift)
        return ColourResult(
            name = name,
            xyz = ColourMath.xyYToXYZ(m, 20.0),
            target = ColourMath.xyYToXYZ(t, 20.0),
            xy = m,
            txy = t,
            sat = 3.0 * shift,
            hue = 2.0 * shift,
            lum = 1.0 * shift,
            de = de * shift,
        )
    }
    val colours = listOf(
        colour("RED", 0.640, 0.330, 0.012, -0.004, 3.5),
        colour("GREEN", 0.300, 0.600, -0.010, 0.020, 4.1),
        colour("BLUE", 0.150, 0.060, 0.004, 0.006, 2.2),
        colour("CYAN", 0.225, 0.329, -0.006, 0.004, 2.8),
        colour("MAGENTA", 0.321, 0.154, 0.010, -0.002, 3.2),
        colour("YELLOW", 0.419, 0.505, 0.006, 0.004, 1.9),
    )
    return Report(greys = greys, colours = colours, white = Vec3(95.05, 100.0, 108.9))
}

@Preview(widthDp = 360, backgroundColor = 0xFF05171A, showBackground = true)
@Composable
private fun GamutChartPreview() {
    TVCalTheme {
        GamutChart(
            dots = GamutChart.dots(previewReport(0.4)),
            hdr = false,
            before = GamutChart.dots(previewReport(1.0)),
        )
    }
}

@Preview(widthDp = 360, backgroundColor = 0xFF05171A, showBackground = true)
@Composable
private fun GreyBalanceChartPreview() {
    TVCalTheme { GreyBalanceChart(report = previewReport(0.3), hdr = false, before = previewReport(1.0)) }
}

@Preview(widthDp = 360, backgroundColor = 0xFF05171A, showBackground = true)
@Composable
private fun WhiteTargetPreview() {
    TVCalTheme {
        WhiteTarget(
            uv = ColourMath.upvp(Point2(0.3120, 0.3285)),
            d65 = ColourMath.upvp(ColourMath.d65),
            ok = true,
            modifier = Modifier.widthIn(max = 132.dp),
            was = ColourMath.upvp(Point2(0.305, 0.322)),
        )
    }
}

@Preview(widthDp = 360, backgroundColor = 0xFF05171A, showBackground = true)
@Composable
private fun BalanceBarPreview() {
    TVCalTheme {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BalanceBar("Red", -0.028, Theme.red)
            BalanceBar("Blue", 0.006, Theme.blue)
            BalanceScaleLabels()
            ScoreTile("Greyscale", 1.4, was = 4.8)
            ScoreTile("Gamma", 2.41, was = 2.18, digits = 2, caption = "target 2.40")
        }
    }
}
