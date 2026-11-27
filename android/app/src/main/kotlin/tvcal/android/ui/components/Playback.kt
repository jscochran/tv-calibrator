package tvcal.android.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import tvcal.android.LocalApp
import tvcal.android.measure.SweepRunner
import tvcal.android.profiles.onYouTube
import tvcal.android.ui.theme.Fonts
import tvcal.android.ui.theme.Symbols
import tvcal.android.ui.theme.Theme
import tvcal.android.ui.theme.fmt
import tvcal.android.ui.theme.monospacedDigit
import tvcal.android.ui.theme.secondary
import tvcal.android.ui.theme.tint
import tvcal.android.ui.theme.tracking
import tvcal.android.ui.theme.white
import tvcal.kit.Patch
import tvcal.kit.PatchKind
import tvcal.kit.Patterns
import tvcal.kit.SignalMode
import kotlin.math.log10
import kotlin.math.max

// Port of Views/Playback.swift: the "play this on the TV" card and the progress while a video is measured.
// Links: LocalApp.current.links.links (video/playlist/download are https Strings; open with openUrl).

private val cardShape = RoundedCornerShape(18.dp)

/** The small cards' surface: card fill, hairline border, 18 dp corners. */
private fun Modifier.playCardSurface(): Modifier =
    this
        .fillMaxWidth()
        .clip(cardShape)
        .background(Theme.card)
        .border(1.dp, Theme.hairline, cardShape)

/**
 * "Play on the TV": the format's badge and where the video comes from, the video's title, and a link to open
 * it on YouTube (links.video(file) ?: playlist(mode)) or get the USB file (download(mode)).
 * [compact]: the small card beside a live reading, with a swatch of [patch].
 */
@Composable
fun PlayFileCard(
    file: String,
    mode: SignalMode,
    modifier: Modifier = Modifier,
    fromStart: Boolean = false,
    compact: Boolean = false,
    patch: Patch? = null,
) {
    val links = LocalApp.current.links.links
    val context = LocalContext.current
    val sourceLabel = if (mode.onYouTube) "YouTube" else "USB file"
    val playLabel = if (fromStart) "Play on the TV, from the start" else "Play on the TV"

    if (compact) {
        Column(
            modifier.playCardSurface().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("$playLabel · ${mode.label} · $sourceLabel", style = Fonts.tiny.copy(color = Theme.tealBright))
            Text(file, style = Fonts.brand(14f, FontWeight.SemiBold).white())
            if (patch != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(width = 24.dp, height = 18.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(patchColour(patch.kind, patch.id, patch.level)),
                    )
                    Text(patchLabel(patch.name), style = Fonts.tiny.secondary())
                }
            }
        }
        return
    }

    // The video itself on YouTube (or its playlist), or the pattern download. The whole row is the link.
    val url = if (mode.onYouTube) (links.video(file) ?: links.playlist(mode)) else links.download(mode)
    val linked = url != null
    Row(
        modifier
            .playCardSurface()
            .then(
                if (url != null) Modifier.clickable(role = Role.Button) { openUrl(context, url) } else Modifier,
            )
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TVPlayArt(Modifier.size(width = 92.dp, height = 64.dp), tint = mode.tint)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                playLabel.uppercase(),
                style = Fonts.micro.copy(fontWeight = FontWeight.SemiBold, color = mode.tint).tracking(0.6f),
            )
            Text(file, style = Fonts.bodyStrong.white())
            if (linked) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (mode.onYouTube) "Open on YouTube" else "Get the USB file",
                        style = Fonts.small.copy(color = Theme.tealBright),
                    )
                    Icon(
                        if (mode.onYouTube) Symbols.arrowUpRight else Symbols.arrowDown,
                        contentDescription = null,
                        tint = Theme.tealBright,
                        modifier = Modifier.size(12.dp),
                    )
                }
            } else {
                Text(if (mode.onYouTube) "YouTube · on your TV" else "USB file", style = Fonts.small.secondary())
            }
        }
    }
}

/**
 * Progress while a video is measured: waiting for its flashes, then each patch in turn (swatch, "n of m
 * patches", the patch strip, the Sparkline of runner.recent, "Camera sees 42.1 cd/m²"). Shows the headline
 * unless [showsTitle] is false. Cancel is pinned by GuidedScreen; [showsCancel] draws it here instead.
 * Android: both phases also say what the camera sees ("about" when [brightnessApproximate] and the reading
 * comes from the camera) and runner.cameraProblem (camera off, no readings coming), as text only: a viewfinder
 * would recreate the capture session.
 */
@Composable
fun MeasuringCard(
    runner: SweepRunner,
    file: String,
    mode: SignalMode,
    modifier: Modifier = Modifier,
    showsTitle: Boolean = true,
    showsCancel: Boolean = false,
    brightnessApproximate: Boolean = LocalApp.current.profiles.brightnessApproximate,
) {
    val approximate = brightnessApproximate && runner.camera != null
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (runner.phase) {
            SweepRunner.Phase.Waiting -> WaitingContent(runner, file, mode, showsTitle, approximate)
            SweepRunner.Phase.Measuring -> MeasuringContent(runner, mode, showsTitle, approximate)
            else -> {}
        }
        if (showsCancel && runner.running) {
            SecondaryButton("Cancel", onClick = { runner.cancel() })
        }
    }
}

// MARK: Waiting for the flashes

/** Emits its parts straight into MeasuringCard's column (Swift @ViewBuilder), so they share its spacing. */
@Composable
private fun WaitingContent(runner: SweepRunner, file: String, mode: SignalMode, showsTitle: Boolean, approximate: Boolean) {
    if (showsTitle) {
        Text("Start the video on the TV", style = Fonts.stepTitleStyle)
    }
    Card(spacing = 12.dp, hero = true) {
        // Swift .symbolEffect(.pulse, options: .repeating)
        val pulse = rememberInfiniteTransition(label = "pulse")
        val a by pulse.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
            label = "pulse",
        )
        Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
            Icon(
                Symbols.viewfinder,
                contentDescription = null,
                tint = Theme.tealBright,
                modifier = Modifier.size(58.dp).alpha(a),
            )
        }
        Text(
            "Waiting for its flashes…",
            Modifier.fillMaxWidth(),
            style = Fonts.bodyStrong.white(),
            textAlign = TextAlign.Center,
        )
        // Android: proof the camera is alive (or why it isn't) while nothing else changes on screen.
        val latest = runner.latestY
        if (latest != null) {
            CameraSeesText(latest, approximate, Modifier.fillMaxWidth(), TextAlign.Center)
        }
        CameraProblemText(runner.cameraProblem)
    }
    WaitingFileCard(file, mode)
    Text(
        if (mode.onYouTube) "Its title starts with ${mode.packPrefix}. Ads are fine: the app keeps waiting."
        else "Its file name starts with ${mode.packPrefix}.",
        style = Fonts.captionBrand.secondary(),
    )
}

// MARK: Measuring each patch

@Composable
private fun MeasuringContent(runner: SweepRunner, mode: SignalMode, showsTitle: Boolean, approximate: Boolean) {
    val patches = runner.patches
    val index = runner.index
    val current = if (index >= 0 && index < patches.size) patches[index] else null
    val doneCount = patches.count { it.done }
    fun colourFor(p: SweepRunner.PatchProgress): Color =
        patchColour(p.kind, p.id, Patterns.patch(hdr = mode.isHDR, id = p.id)?.level)

    if (showsTitle) {
        Text(current?.let { "Measuring ${patchLabel(it.name)}" } ?: "Measuring…", style = Fonts.stepTitleStyle)
    }
    Card(spacing = 12.dp, hero = true) {
        val swatch by animateColorAsState(current?.let { colourFor(it) } ?: Theme.card, tween(300), label = "patch")
        Box(Modifier.fillMaxWidth().height(130.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(110.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(swatch),
            )
        }
        Text(
            "$doneCount of ${patches.size} patches",
            Modifier.fillMaxWidth(),
            style = Fonts.brand(19f, FontWeight.SemiBold).monospacedDigit().white(),
            textAlign = TextAlign.Center,
        )
    }
    Card(spacing = 16.dp) {
        val stripLabel = "$doneCount of ${patches.size} patches measured"
        Row(
            Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = stripLabel },
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            patches.forEachIndexed { i, p ->
                val shape = RoundedCornerShape(3.dp)
                val fill = if (p.done) (if (p.Y == null) Theme.limit else colourFor(p)) else Theme.card
                val edge = if (!p.done && i == index) Theme.accent else Theme.hairline
                Box(
                    Modifier
                        .weight(1f)
                        .height(28.dp)
                        .clip(shape)
                        .background(fill)
                        .border(1.dp, edge, shape),
                )
            }
        }
        Sparkline(runner.recent, Modifier.fillMaxWidth().height(62.dp))
        CameraSeesText(runner.latestY, approximate)
        CameraProblemText(runner.cameraProblem)
    }
    Text("Keep the TV menu closed and the phone still.", style = Fonts.captionBrand.secondary())
}

/** "Camera sees 42.1 cd/m²" (2 decimals under 10), "Camera sees about 42.1 cd/m²" when [approximate]. */
@Composable
private fun CameraSeesText(
    latest: Double?,
    approximate: Boolean,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
) {
    val reading = "${fmt(latest, if ((latest ?: 0.0) < 10) 2 else 1)} cd/m²"
    Text(
        buildAnnotatedString {
            append(if (approximate) "Camera sees about " else "Camera sees ")
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Color.White)) { append(reading) }
        },
        modifier,
        style = Fonts.captionBrand.secondary(),
        textAlign = textAlign,
    )
}

/** SweepRunner.cameraProblem with a warning sign, or nothing. */
@Composable
private fun CameraProblemText(problem: String?) {
    if (problem == null) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Icon(
            Symbols.exclamationTriangle,
            contentDescription = null,
            tint = Theme.limit,
            modifier = Modifier.size(16.dp),
        )
        Text(problem, Modifier.weight(1f), style = Fonts.captionBrand.copy(color = Theme.limit))
    }
}

/** The video to play while waiting: its format, where it comes from and its title. */
@Composable
private fun WaitingFileCard(file: String, mode: SignalMode) {
    Column(
        Modifier.playCardSurface().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ModeBadge(mode)
            Box(Modifier.weight(1f))
            Text(
                if (mode.onYouTube) "YouTube · on your TV" else "USB file",
                style = Fonts.small.secondary(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(file, style = Fonts.brand(14f, FontWeight.SemiBold).white())
    }
}

/** How a pattern looks on screen: its colour (Theme.swatch), or its grey level (clamped 0...1, default 1). */
internal fun patchColour(kind: PatchKind, id: String, level: Double?): Color {
    if (kind == PatchKind.COLOUR) return Theme.swatch(id)
    val l = (level ?: 1.0).coerceIn(0.0, 1.0).toFloat()
    return Color(l, l, l)
}

/** "100% WHITE" -> "100% white", "PQ 50% GREY (92 nits)" -> "50% grey (92 nits)". */
internal fun patchLabel(name: String): String = name.replace("PQ ", "").lowercase()

/** The camera's recent brightness readings, as a line (log10 scale, at least 0.5 decades tall). */
@Composable
fun Sparkline(values: List<Double>, modifier: Modifier = Modifier, colour: Color = Theme.tealBright) {
    Canvas(modifier) {
        if (values.size <= 1) return@Canvas
        val u = density
        val logs = values.map { log10(max(it, 0.01)) }
        val lo = logs.minOrNull() ?: 0.0
        val hi = max(logs.maxOrNull() ?: 1.0, lo + 0.5)
        val path = Path()
        logs.forEachIndexed { i, v ->
            val pt = Offset(
                size.width * i / (logs.size - 1),
                1 * u + (size.height - 2 * u) * (1 - ((v - lo) / (hi - lo)).toFloat()),
            )
            if (i == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
        }
        drawPath(path, colour, style = Stroke(2 * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
