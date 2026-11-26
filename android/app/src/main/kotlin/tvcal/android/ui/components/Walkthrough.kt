package tvcal.android.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tvcal.android.profiles.onYouTube
import tvcal.android.ui.theme.Fonts
import tvcal.android.ui.theme.Symbols
import tvcal.android.ui.theme.TVCalTheme
import tvcal.android.ui.theme.Theme
import tvcal.android.ui.theme.lineSpacing
import tvcal.android.ui.theme.secondary
import tvcal.android.ui.theme.tint
import tvcal.android.ui.theme.tracking
import tvcal.android.ui.theme.white
import tvcal.kit.ColourMenu
import tvcal.kit.SignalMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// Port of Views/Walkthrough.swift: a swipeable, one-idea-per-page walkthrough, and its illustrations.

/** One page. Swift `symbol: ""` (none) is null here; `art`/`extra` (AnyView) are composable slots. */
class WalkPage(
    val symbol: ImageVector? = null,
    val tint: Color = Theme.tealBright,
    val title: String,
    val text: String,
    val bullets: List<String> = emptyList(),
    /** A picture above the text, drawn on the teal illustration panel (replaces the symbol). */
    val art: (@Composable () -> Unit)? = null,
    /** Controls under the text (buttons, choices). */
    val extra: (@Composable () -> Unit)? = null,
    /** The small teal line above the title. When null the walkthrough shows "<section> · 2 of 6". */
    val eyebrow: String? = null,
    /** A larger white sentence before `text` (17 pt), for the one thing to do on this page. */
    val lead: String? = null,
)

/** SwiftUI `.easeInOut`. */
private val EaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
private const val PAGE_MS = 280

/**
 * Pages you swipe (|dx| > 1.5·|dy|, past 50 dp) or tap Next through; the last page's button finishes.
 * The yellow Next / [finishTitle] button is declared with [PinnedAction] (the hosting screen's
 * [PinnedActionHost] pins it); the page dots and Back sit under the page. Page changes slide + fade (0.28 s).
 * Reset it with `key(...)` (Swift `.id(mode)`). Not scrollable itself: put it in a [ScreenColumn].
 */
@Composable
fun Walkthrough(
    pages: List<WalkPage>,
    modifier: Modifier = Modifier,
    finishTitle: String = "Done",
    /** The eyebrow's prefix: "Set up" gives "Set up · 5 of 6". */
    section: String? = null,
    /** Page titles: 22 pt semibold, the same as a step's title. */
    titleStyle: TextStyle = Fonts.stepTitle,
    /** Height of the illustration panel for pages with `art`. */
    artHeight: Dp = 170.dp,
    /** Whether the user may leave page i (e.g. a name is filled in). */
    canAdvance: (Int) -> Boolean = { true },
    onFinish: () -> Unit,
) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    var forward by rememberSaveable { mutableStateOf(true) }
    // The swipe handler and the pinned button read these at the time of the gesture / tap.
    val latestPages by rememberUpdatedState(pages)
    val latestCanAdvance by rememberUpdatedState(canAdvance)
    val latestFinish by rememberUpdatedState(onFinish)

    val current = min(index, max(pages.size - 1, 0))
    val onLastPage = index >= pages.size - 1

    fun next() {
        if (index >= latestPages.size - 1 || !latestCanAdvance(index)) return
        forward = true
        index += 1
    }

    fun back() {
        if (index <= 0) return
        forward = false
        index -= 1
    }

    PinnedAction(if (onLastPage) finishTitle else "Next", enabled = canAdvance(current)) {
        // Read the page at tap time: the host keeps the closure while the title stays the same.
        if (index >= latestPages.size - 1) latestFinish() else next()
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (pages.isNotEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clipToBounds()
                    .pointerInput(Unit) {
                        // Swift .simultaneousGesture(DragGesture(minimumDistance: 30)): watches without
                        // consuming, so the screen still scrolls and the page's controls still work.
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            var last = down.position
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                last = change.position
                                if (!change.pressed) break
                            }
                            val dx = last.x - down.position.x
                            val dy = last.y - down.position.y
                            val moved = kotlin.math.sqrt(dx * dx + dy * dy)
                            if (moved >= 30.dp.toPx() && abs(dx) > abs(dy) * 1.5f) {
                                if (dx < -50.dp.toPx()) next() else if (dx > 50.dp.toPx()) back()
                            }
                        }
                    },
            ) {
                AnimatedContent(
                    targetState = current,
                    transitionSpec = {
                        val dir = if (forward) 1 else -1
                        (slideInHorizontally(tween(PAGE_MS, easing = EaseInOut)) { w -> dir * w } +
                            fadeIn(tween(PAGE_MS, easing = EaseInOut))) togetherWith
                            (slideOutHorizontally(tween(PAGE_MS, easing = EaseInOut)) { w -> -dir * w } +
                                fadeOut(tween(PAGE_MS, easing = EaseInOut))) using
                            SizeTransform(clip = false) { _, _ -> tween(PAGE_MS, easing = EaseInOut) }
                    },
                    label = "walkthrough",
                ) { i ->
                    val p = pages.getOrNull(i)
                    if (p != null) {
                        WalkPageView(p, eyebrowFor(p, i, pages.size, section), titleStyle, artHeight)
                    }
                }
            }

            // Back (a teal link) on the left, the page dots in the middle.
            Box(Modifier.fillMaxWidth().heightIn(min = 44.dp), contentAlignment = Alignment.Center) {
                if (pages.size > 1) {
                    PageDots(count = pages.size, index = current)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (current > 0) {
                        LinkButton("Back", onClick = { back() }, style = Fonts.bodyBrand)
                    }
                    Box(Modifier.weight(1f))
                }
            }
        }
    }
}

private fun eyebrowFor(p: WalkPage, i: Int, count: Int, section: String?): String? {
    if (p.eyebrow != null) return p.eyebrow
    if (count <= 1) return section
    val n = "${i + 1} of $count"
    return if (section != null) "$section · $n" else n
}

@Composable
private fun WalkPageView(p: WalkPage, eyebrow: String?, titleStyle: TextStyle, artHeight: Dp) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (eyebrow != null) Eyebrow(eyebrow)
        Text(p.title, style = titleStyle.tracking(-0.4f).white())
        val art = p.art
        if (art != null) {
            WalkArtPanel(height = artHeight) { art() }
        } else if (p.symbol != null) {
            WalkArtPanel(height = 120.dp) {
                Icon(p.symbol, contentDescription = null, tint = p.tint, modifier = Modifier.size(48.dp))
            }
        }
        if (p.lead != null) {
            Text(p.lead, style = Fonts.brand(17f).lineSpacing(4f).white())
        }
        if (p.text.isNotEmpty()) {
            Text(p.text, style = Fonts.bodyBrand.lineSpacing(4f).secondary())
        }
        if (p.bullets.isNotEmpty()) {
            Card(spacing = 10.dp) {
                for (b in p.bullets) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                        Icon(
                            Symbols.checkmarkCircle,
                            contentDescription = null,
                            tint = p.tint,
                            modifier = Modifier.padding(top = 2.dp).size(16.dp),
                        )
                        Text(b, Modifier.weight(1f), style = Fonts.bodyBrand.white())
                    }
                }
            }
        }
        p.extra?.invoke()
    }
}

/** The illustration panel: the teal surface, 18 dp corners, the picture centred. */
@Composable
fun WalkArtPanel(
    modifier: Modifier = Modifier,
    height: Dp = 170.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(18.dp))
            .background(Theme.surface),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

// MARK: - Illustrations
// Flat line drawings: teal outlines, the screen in the background colour, the grey patch #777D7E and the
// yellow measuring box. Track colour white 14%.

private object ArtColour {
    val line = Theme.tealBright
    val screen = Theme.background
    val patch = Color(0xFF777D7E)
    val track = Color.White.copy(alpha = 0.14f)
}

/** The TV with a grey patch in the middle, and the phone on its stand facing it (325 × 160 canvas, scaled). */
@Composable
fun PlacementArt(modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val labelStyle = Fonts.tiny.secondary()
    Canvas(
        modifier
            .fillMaxSize()
            .clearAndSetSemantics {
                contentDescription =
                    "A phone on a stand, 15 to 30 centimetres from the TV, facing the grey patch in the middle"
            },
    ) {
        // Drawn on a 325 × 160 canvas (the Figma frame) in points, scaled to fit and centred.
        val k = max(0.01f * density, min(size.width / 325f, size.height / 160f))
        val ox = (size.width - 325f * k) / 2
        val oy = (size.height - 160f * k) / 2
        // the label, measured at its size in canvas units (Swift scales it with the rest)
        val label = measurer.measure(
            "15–30 cm · facing the centre",
            labelStyle.copy(fontSize = (labelStyle.fontSize.toPx() / density).toSp()),
            maxLines = 1,
            softWrap = false,
        )
        translate(ox, oy) {
            scale(k, k, pivot = Offset.Zero) {
                // TV and its stand
                drawRoundRect(ArtColour.screen, Offset(28f, 20f), Size(228f, 86f), CornerRadius(7f))
                drawRoundRect(ArtColour.line, Offset(28f, 20f), Size(228f, 86f), CornerRadius(7f), style = Stroke(2f))
                drawRoundRect(
                    ArtColour.patch,
                    Offset(28f + (228f - 76f) / 2, 20f + (86f - 60f) / 2),
                    Size(76f, 60f),
                    CornerRadius(1f),
                )
                drawRect(ArtColour.line, Offset(28f + (228f - 40f) / 2, 111f), Size(40f, 3f))
                // Phone with the measuring box
                drawRoundRect(ArtColour.screen, Offset(270f, 60f), Size(35f, 62f), CornerRadius(6f))
                drawRoundRect(ArtColour.line, Offset(270f, 60f), Size(35f, 62f), CornerRadius(6f), style = Stroke(2f))
                drawRoundRect(
                    Theme.accent,
                    Offset(270f + 17.5f - 5f, 60f + 31f - 5f),
                    Size(10f, 10f),
                    CornerRadius(1f),
                    style = Stroke(1.5f),
                )
                // Its stand: a stem and two legs
                val x = 287.5f
                val stand = Path().apply {
                    moveTo(x, 122f)
                    lineTo(x, 136f)
                    moveTo(x - 21f, 147f)
                    lineTo(x, 136f)
                    lineTo(x + 21f, 147f)
                }
                drawPath(stand, ArtColour.line, style = Stroke(1.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawText(label, topLeft = Offset(45f, 135f))
            }
        }
    }
}

/** Lights off, TV on, 20–30 min. */
@Composable
fun DarkRoomArt(modifier: Modifier = Modifier) {
    Row(
        modifier.clearAndSetSemantics { contentDescription = "Lights off, TV on, 20–30 min" },
        horizontalArrangement = Arrangement.spacedBy(30.dp),
        verticalAlignment = Alignment.Top,
    ) {
        DarkRoomItem(Symbols.lightbulbSlash, "Lights off")
        DarkRoomItem(Symbols.tv, "TV on")
        DarkRoomItem(Symbols.timer, "20–30 min")
    }
}

@Composable
private fun DarkRoomItem(symbol: ImageVector, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.height(44.dp), contentAlignment = Alignment.Center) {
            Icon(symbol, contentDescription = null, tint = ArtColour.line, modifier = Modifier.size(40.dp))
        }
        Text(label, style = Fonts.small.secondary())
    }
}

/** The four formats, in the order to calibrate them, with [current] highlighted. */
@Composable
fun FormatOrderArt(current: SignalMode, modifier: Modifier = Modifier) {
    val description = "Calibrate in order: SDR, HDR10, HDR10+, Dolby Vision. Now: ${current.label}"
    Column(
        modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val modes = SignalMode.entries
            for (m in modes) {
                Column(
                    Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val selected = m == current
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) m.tint else m.tint.copy(alpha = 0.13f))
                            .padding(6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        ShrinkToFitText(
                            m.label,
                            Fonts.badge,
                            if (selected) Theme.background else m.tint,
                            Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                        )
                    }
                    Text(
                        if (m.onYouTube) "YouTube" else "USB file",
                        style = Fonts.micro.secondary(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (m != modes.last()) {
                    Icon(
                        Symbols.chevronRight,
                        contentDescription = null,
                        tint = Theme.textSecondary,
                        modifier = Modifier.padding(bottom = 16.dp).size(12.dp),
                    )
                }
            }
        }
        Text(
            "Each format has its own picture settings on the TV",
            Modifier.padding(horizontal = 16.dp),
            style = Fonts.small.secondary(),
            textAlign = TextAlign.Center,
        )
    }
}

/** The TV outline that the menu illustrations draw their sliders in, fitted to any size. */
private fun DrawScope.artTVRect(): Rect {
    val pad = max(1 * density, min(size.width, size.height) * 0.12f)
    val h = max(1f, size.height - pad * 2)
    val w = max(1f, min(size.width - pad * 2, h * 2.6f))
    val left = (size.width - w) / 2
    val top = (size.height - h) / 2
    return Rect(left, top, left + w, top + h)
}

private fun DrawScope.drawArtTV(tv: Rect) {
    val r = min(9 * density, tv.height * 0.1f)
    drawRoundRect(ArtColour.screen, tv.topLeft, tv.size, CornerRadius(r))
    drawRoundRect(ArtColour.line, tv.topLeft, tv.size, CornerRadius(r), style = Stroke(1.5f * density))
}

/** Swift `CGRect.insetBy(dx:dy:)`. */
private fun Rect.insetBy(dx: Float, dy: Float): Rect = Rect(left + dx, top + dy, right - dx, bottom - dy)

/** A white-balance menu on the TV: R, G, B sliders for 2-point ([points] <= 3), or one slider per point. */
@Composable
fun WhiteBalanceArt(points: Int, modifier: Modifier = Modifier) {
    val description = if (points <= 3) "Red, green and blue sliders" else "$points sliders"
    Canvas(modifier.fillMaxSize().clearAndSetSemantics { contentDescription = description }) {
        val u = density
        val tv = artTVRect()
        drawArtTV(tv)
        val n = if (points <= 3) 3 else points
        val inner = tv.insetBy(tv.width * 0.12f, tv.height * 0.1f)
        if (inner.width <= 0 || inner.height <= 0) return@Canvas
        val step = inner.width / n
        val track = min(4 * u, max(1 * u, step * 0.3f))
        val knob = minOf(14 * u, max(3 * u, step * 0.72f), inner.height * 0.3f)
        val heights = listOf(0.15f, 0.4f, 0.5f)
        for (i in 0 until n) {
            val x = inner.left + step * (i + 0.5f)
            drawRoundRect(
                ArtColour.track,
                Offset(x - track / 2, inner.top),
                Size(track, inner.height),
                CornerRadius(track / 2),
            )
            val f = if (n == 3) heights[i] else 0.45f + 0.3f * sin(i * 1.1).toFloat()
            val span = max(0f, inner.height - knob)
            val y = inner.top + knob / 2 + span * clampF(f, 0f, 1f)
            val colour = when (i % 3) {
                0 -> Theme.red
                1 -> Theme.green
                else -> Theme.blue
            }
            drawCircle(colour, radius = knob / 2, center = Offset(x, y))
        }
    }
}

/** A colour menu on the TV: three slider rows (H/S/B or R/G/B), or "No colour sliders" when OFF. */
@Composable
fun ColourMenuArt(menu: ColourMenu, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val labels = when (menu) {
        ColourMenu.HSL -> listOf("H", "S", "B")
        ColourMenu.RGB_SLIDERS -> listOf("R", "G", "B")
        ColourMenu.OFF -> emptyList()
    }
    val description = when (menu) {
        ColourMenu.OFF -> "No colour menu"
        ColourMenu.HSL -> "Hue, saturation and brightness sliders"
        ColourMenu.RGB_SLIDERS -> "Red, green and blue sliders"
    }
    val emptyStyle = Fonts.tiny.secondary()
    val labelStyle = Fonts.micro
    Canvas(modifier.fillMaxSize().clearAndSetSemantics { contentDescription = description }) {
        val u = density
        val tv = artTVRect()
        drawArtTV(tv)
        if (menu == ColourMenu.OFF) {
            val t = measurer.measure("No colour sliders", emptyStyle)
            drawText(t, topLeft = Offset(tv.center.x - t.size.width / 2f, tv.center.y - t.size.height / 2f))
            return@Canvas
        }
        val inner = tv.insetBy(tv.width * 0.12f, tv.height * 0.16f)
        if (inner.width <= 0 || inner.height <= 0) return@Canvas
        val rowStep = inner.height / 3
        val track = min(4 * u, max(1 * u, rowStep * 0.15f))
        val knob = min(14 * u, max(3 * u, rowStep * 0.55f))
        val labelW = if (inner.width > 80 * u) 16 * u else 0f
        val positions = listOf(0.27f, 0.42f, 0.58f)
        val colours = listOf(Theme.red, Theme.green, Theme.blue)
        for (i in 0 until 3) {
            val y = inner.top + rowStep * (i + 0.5f)
            if (labelW > 0) {
                val colour = if (menu == ColourMenu.RGB_SLIDERS) colours[i] else Theme.textSecondary
                val t = measurer.measure(labels[i], labelStyle.copy(color = colour))
                val cx = inner.left + labelW / 2 - 4 * u
                drawText(t, topLeft = Offset(cx - t.size.width / 2f, y - t.size.height / 2f))
            }
            val x0 = inner.left + labelW
            val w = max(1f, inner.width - labelW)
            drawRoundRect(ArtColour.track, Offset(x0, y - track / 2), Size(w, track), CornerRadius(track / 2))
            val x = x0 + knob / 2 + max(0f, w - knob) * positions[i]
            drawCircle(colours[i], radius = knob / 2, center = Offset(x, y))
        }
    }
}

// MARK: - Previews

@Preview(widthDp = 360, backgroundColor = 0xFF05171A, showBackground = true)
@Composable
private fun WalkArtPreview() {
    TVCalTheme {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            WalkArtPanel { PlacementArt() }
            WalkArtPanel(height = 120.dp) { DarkRoomArt() }
            WalkArtPanel(height = 120.dp) { FormatOrderArt(SignalMode.HDR10) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                WalkArtPanel(Modifier.weight(1f), height = 110.dp) { WhiteBalanceArt(20, Modifier.fillMaxSize()) }
                WalkArtPanel(Modifier.weight(1f), height = 110.dp) { ColourMenuArt(ColourMenu.HSL, Modifier.fillMaxSize()) }
            }
        }
    }
}
