package tvcal.android.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import tvcal.android.LocalApp
import tvcal.android.measure.GuidedController
import tvcal.android.measure.SweepRunner
import tvcal.android.storage.GamutCheck
import tvcal.android.storage.GuideStep
import tvcal.android.storage.Multipoint
import tvcal.android.storage.upper
import tvcal.android.ui.components.Card
import tvcal.android.ui.components.Eyebrow
import tvcal.android.ui.components.GamutChart
import tvcal.android.ui.components.LinkButton
import tvcal.android.ui.components.MeasuringCard
import tvcal.android.ui.components.PinnedAction
import tvcal.android.ui.components.PlayFileCard
import tvcal.android.ui.components.SecondaryButton
import tvcal.android.ui.components.TipRow
import tvcal.android.ui.components.TipTone
import tvcal.android.ui.components.WhiteTarget
import tvcal.android.ui.components.boldMarkdown
import tvcal.android.ui.theme.Fonts
import tvcal.android.ui.theme.Symbols
import tvcal.android.ui.theme.Theme
import tvcal.android.ui.theme.fmt
import tvcal.android.ui.theme.monospacedDigit
import tvcal.android.ui.theme.secondary
import tvcal.android.ui.theme.signed
import tvcal.android.ui.theme.tracking
import tvcal.android.ui.theme.white
import tvcal.kit.Analysis
import tvcal.kit.ColourMenu
import tvcal.kit.LiveReading
import tvcal.kit.Patterns
import tvcal.kit.PointFinder
import tvcal.kit.cfmt
import tvcal.kit.swiftRounded
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// Port of Views/LiveSteps.swift: the live steps (white balance gain/offset/fine, colours, fine-tune and the
// point finder) and the demo remote. Chime: LocalApp.current.chime.observe(key, ok, LocalView.current) on
// every new value of a chimed row. confirmationDialog → AlertDialog.

enum class WBKind { GAIN, OFFSET, FINE }

/** "This TV's readings wobble…" (jitter >= 0.004) and "Average of 5 readings · latest 42.1 cd/m²  [Restart]". */
@Composable
fun AverageLine(reading: LiveReading, restart: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (reading.jitter >= 0.004) {
            Text(
                "This TV's readings wobble about ±${fmt(reading.jitter * 100)}%, so the bars show an average.",
                style = Fonts.small.secondary(),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Average of ${reading.averaged} reading${if (reading.averaged == 1) "" else "s"}" +
                    (if (reading.averaged < reading.need) " (needs ${reading.need} to call it steady)" else "") +
                    " · latest ${fmt(reading.latestY, if (reading.latestY < 10) 2 else 1)} cd/m²",
                style = Fonts.small.monospacedDigit().secondary(),
                modifier = Modifier.weight(1f),
            )
            RestartLink(restart)
        }
    }
}

/**
 * AverageLine's "Restart" link (LinkButtonStyle(font: .captionStrong)) with Swift's accessibilityHint "Use after
 * changing a setting, to see its effect sooner", given as the click label (LinkButton takes no hint).
 */
@Composable
private fun RestartLink(restart: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .heightIn(min = 44.dp)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClickLabel = "Use after changing a setting, to see its effect sooner",
                onClick = restart,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "Restart",
            style = Fonts.captionStrong,
            color = if (pressed) Theme.tealBright.copy(alpha = 0.6f) else Theme.tealBright,
        )
    }
}

/** TipList.TipKind. Swift `.todo` is [ACTION]. */
enum class TipKind { ACTION, OK, DONE, LIMIT }

/** Swift's (text, kind) tuple. */
data class TipItem(val text: String, val kind: TipKind)

/** TipList.tone(kind): ACTION → ACTION, OK/DONE → OK, LIMIT → LIMIT. */
val TipKind.tone: TipTone
    get() = when (this) {
        TipKind.ACTION -> TipTone.ACTION
        TipKind.OK, TipKind.DONE -> TipTone.OK
        TipKind.LIMIT -> TipTone.LIMIT
    }

/** The step's tips, each a TipRow, 8 dp apart. */
@Composable
fun TipList(tips: List<TipItem>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (t in tips) TipRow(tone = t.kind.tone, text = t.text)
    }
}

// MARK: - Small helpers (file-private)

/** Swift `String.capitalized` for the app's ids ("RED" → "Red"). */
private fun liveCap(s: String): String =
    s.lowercase().split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.titlecase() } }

private class LiveLast<T>(var value: T)

/** Swift `.onChange(of: value) { _, v in action(v) }`: runs on each change, not for the first value. */
@Composable
private fun <T> LiveOnChange(value: T, action: (T) -> Unit) {
    val latest = rememberUpdatedState(action)
    val last = remember { LiveLast(value) }
    SideEffect {
        if (last.value != value) {
            last.value = value
            latest.value(value)
        }
    }
}

/** One line of text that shrinks to fit (Swift lineLimit(1) + minimumScaleFactor). */
@Composable
private fun LiveScaledText(text: String, style: TextStyle, minScale: Float, modifier: Modifier = Modifier) {
    val maxSize = style.fontSize
    BasicText(
        text,
        modifier = modifier,
        style = style,
        maxLines = 1,
        softWrap = false,
        autoSize = TextAutoSize.StepBased(minFontSize = maxSize * minScale, maxFontSize = maxSize, stepSize = 1.sp),
    )
}

// MARK: - Shared parts of the live steps

/** "+3.2%", "−2.1": a big signed reading with a true minus sign; one decimal below 9.95, none above. */
private fun bigSigned(v: Double?, suffix: String = ""): String {
    if (v == null || !v.isFinite()) return "–"
    val digits = if (abs(v) < 9.95) 1 else 0
    return cfmt(v, digits, plus = true).replace("-", "−") + suffix
}

/** One channel of the white balance: "Red  +3.2%" (reading 38) over a CentredTrack; chimes under [chime]. */
@Composable
private fun ChannelBalanceRow(
    label: String,
    value: Double?,
    colour: Color,
    fullScale: Double = 0.05,
    tolerance: Double = 0.012,
    chime: String? = null,
) {
    val app = LocalApp.current
    val view = LocalView.current
    val ok = value?.let { abs(it) <= tolerance } ?: false
    LiveOnChange(value) { v -> app.chime.observe(chime, v?.let { abs(it) <= tolerance } ?: false, view) }
    val tint = if (ok) Theme.green else Color.White
    Column(
        Modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = label
                stateDescription = if (ok) "On target" else bigSigned(value?.let { it * 100 }, suffix = " percent")
            },
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = Fonts.brand(16f, androidx.compose.ui.text.font.FontWeight.SemiBold).white())
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ok) Icon(Symbols.checkmark, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
                LiveScaledText(
                    bigSigned(value?.let { it * 100 }, suffix = "%"),
                    style = Fonts.reading(38f).tracking(-1f).copy(color = tint),
                    minScale = 0.6f,
                )
            }
        }
        CentredTrack(value = value, colour = colour, fullScale = fullScale, tolerance = tolerance, ok = ok)
    }
}

/** The centred track: the white target line, the green tolerance zone, the amount growing left or right. */
@Composable
private fun CentredTrack(value: Double?, colour: Color, fullScale: Double, tolerance: Double, ok: Boolean) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(20.dp)
            .clip(RoundedCornerShape(6.dp)),
    ) {
        val w = size.width
        val h = size.height
        val mid = w / 2
        val px4 = 4.dp.toPx()
        drawRoundRect(Color.White.copy(alpha = 0.07f), cornerRadius = CornerRadius(6.dp.toPx()))
        val zone = min(w, max(px4, (tolerance / fullScale).toFloat() * mid * 2))
        drawRect(Theme.green.copy(alpha = 0.16f), topLeft = Offset(mid - zone / 2, 0f), size = Size(zone, h))
        if (value != null) {
            val barH = 10.dp.toPx()
            val top = (h - barH) / 2
            val radius = CornerRadius(4.dp.toPx())
            if (ok) {
                val bw = 8.dp.toPx()
                drawRoundRect(Theme.green, topLeft = Offset(mid - bw / 2, top), size = Size(bw, barH), cornerRadius = radius)
            } else {
                val off = tvcal.kit.clamp(value / fullScale, -1.0, 1.0).toFloat() * mid
                val bw = max(abs(off), px4)
                val x = if (off < 0) mid + off else mid
                drawRoundRect(colour, topLeft = Offset(x, top), size = Size(bw, barH), cornerRadius = radius)
            }
        }
        val line = 2.dp.toPx()
        drawRect(Color.White, topLeft = Offset(mid - line / 2, 0f), size = Size(line, h))
    }
}

/** "Too little · On target · Too much" under the balance bars. */
@Composable
private fun BalanceScale() {
    Row(Modifier.fillMaxWidth().clearAndSetSemantics { }) {
        Text("Too little", style = Fonts.tiny.secondary(), modifier = Modifier.weight(1f))
        Text("On target", style = Fonts.tiny.copy(color = Theme.green))
        Text("Too much", style = Fonts.tiny.secondary(), textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}

/** One colour-menu control: "Saturation  +3.1" (reading 30) over a CentredTrack, the [caption] when off target. */
@Composable
private fun ColourControlRow(
    label: String,
    value: Double?,
    colour: Color,
    fullScale: Double,
    tolerance: Double,
    caption: String? = null,
    chime: String? = null,
) {
    val app = LocalApp.current
    val view = LocalView.current
    val ok = value?.let { abs(it) <= tolerance } ?: false
    LiveOnChange(value) { v -> app.chime.observe(chime, v?.let { abs(it) <= tolerance } ?: false, view) }
    val tint = if (ok) Theme.green else Color.White
    Column(
        Modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = label
                stateDescription = if (ok) "On target" else (caption ?: bigSigned(value))
            },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = Fonts.brand(16f, androidx.compose.ui.text.font.FontWeight.SemiBold).white())
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (ok) Icon(Symbols.checkmark, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                LiveScaledText(
                    bigSigned(value),
                    style = Fonts.reading(30f).tracking(-1f).copy(color = tint),
                    minScale = 0.6f,
                )
            }
        }
        CentredTrack(value = value, colour = colour, fullScale = fullScale, tolerance = tolerance, ok = ok)
        if (caption != null && !ok && value != null) {
            Text(caption, style = Fonts.small.secondary())
        }
    }
}

/** Demo TVs: "Demo · simulated readings" / "Try the remote buttons below." on a hero card. */
@Composable
private fun DemoNote() {
    Card(hero = true) {
        Text("Demo · simulated readings", style = Fonts.captionStrong.copy(color = Theme.accent))
        Text("Try the remote buttons below.", style = Fonts.small.secondary())
    }
}

/** Shown until the first reading arrives (spinner + "Start the video on the TV: readings appear…"). */
@Composable
private fun WaitingForReading() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(Modifier.size(20.dp), color = Theme.tealBright, strokeWidth = 2.dp)
        Text(
            "Start the video on the TV: readings appear as soon as the camera sees it.",
            style = Fonts.captionBrand.secondary(),
            modifier = Modifier.weight(1f),
        )
    }
}

// MARK: - 3, 4 + fine-tune: live white balance

/** Swift `still: (q: [Double], since: Date)`. */
private data class StillBars(val q: List<Double>, val sinceMillis: Long)

private const val STILL_NOTICE =
    "The bars haven't moved for a minute. If you've been adjusting, it may be the wrong control: try the one a step above or below, and when you find it, pick it under “The control that moves this grey”."

/**
 * The live white balance for [kind] on grey [pid]: the instruction ("Adjust Red and Blue Gain"), BigWhiteTarget,
 * DemoNote or the compact PlayFileCard, the Red/Blue ChannelBalanceRows (chime key
 * "<tv.id>|<mode>|<kind>|<pid>|<R|B>"), the still-bars notice (fine, 60 s), DemoRemote for the demo,
 * "Can't get closer" (markLimit "bright"/"dark"/"fine:<pid>", next(done = true)) and "Back to the check",
 * "TV menu: …" and the AverageLine. PinnedAction: "Next: dark end" / "Next: check" / "Next grey" / "Back to
 * the check" → next(done = live.reading?.steady). On appear guided.showLive(pid).
 */
@Composable
fun WhiteBalanceStep(guided: GuidedController, kind: WBKind, pid: String) {
    val brand = guided.tv.brand
    val suggestion: Int? = if (kind == WBKind.FINE) guided.suggestions[pid] else null
    var still by remember { mutableStateOf<StillBars?>(null) }

    // "10% grey", "50% grey (92 nits)", "100 nits".
    val greyName = shortName(Patterns.patch(guided.hdr, pid)?.name ?: pid).replaceFirst("%", "% grey")

    val instructions = when (kind) {
        WBKind.GAIN -> "Adjust Red and Blue ${brand.gainName}"
        WBKind.OFFSET -> "Adjust Red and Blue ${brand.offsetName}"
        WBKind.FINE ->
            if (suggestion != null) "Adjust Red and Blue on the $suggestion% control"
            else "Adjust Red and Blue on the control nearest this grey"
    }

    val whereText = if (kind == WBKind.FINE) {
        brand.whiteBalancePath.replace(" (set it to 2 Point)", "") + " › 10 or 20 Point"
    } else {
        brand.whiteBalancePath
    }

    fun nextGrey(after: String): String? {
        val f = guided.plan.fine
        val i = f.indexOf(after)
        return if (i >= 0 && i + 1 < f.size) f[i + 1] else null
    }

    // Moves on. The step is marked done only when it was on target and steady (or at the TV's limit).
    // Fine-tune works out the grey at call time: the pinned button can outlive this view.
    fun next(done: Boolean) {
        when (kind) {
            WBKind.GAIN -> guided.go(GuideStep.DARK, markDone = if (done) GuideStep.BRIGHT else null)
            WBKind.OFFSET -> guided.go(GuideStep.CHECK, markDone = if (done) GuideStep.DARK else null)
            WBKind.FINE -> {
                val cur = guided.progress.finePid ?: guided.plan.fine[0]
                val n = nextGrey(cur)
                if (n != null) {
                    guided.setFinePid(n)
                    guided.showLive(n)
                } else {
                    guided.go(GuideStep.CHECK)
                }
            }
        }
    }

    val nextTitle = when (kind) {
        WBKind.GAIN -> "Next: dark end"
        WBKind.OFFSET -> "Next: check"
        WBKind.FINE -> if (nextGrey(pid) != null) "Next grey" else "Back to the check"
    }

    val demoControls: List<DemoControl> = when (kind) {
        WBKind.GAIN -> listOf(DemoControl("Red ${brand.gainName}", "gain:R"), DemoControl("Blue ${brand.gainName}", "gain:B"))
        WBKind.OFFSET -> listOf(DemoControl("Red ${brand.offsetName}", "offset:R"), DemoControl("Blue ${brand.offsetName}", "offset:B"))
        WBKind.FINE -> {
            val p = suggestion ?: swiftRounded(Patterns.patch(guided.hdr, pid)?.percent ?: 50.0).toInt()
            listOf(DemoControl("Red $p%", "p20:R:$p"), DemoControl("Blue $p%", "p20:B:$p"))
        }
    }

    // The success chime's name for one channel of this step: per TV, format, step and grey.
    fun chimeKey(channel: String): String =
        "${guided.tv.id.upper}|${guided.mode.rawValue}|${kind.name.lowercase()}|$pid|$channel"

    // Fine-tune: the bars haven't moved for a minute, so it may be the wrong control.
    fun stillNotice(r: LiveReading): String? {
        val s = still
        if (kind != WBKind.FINE || r.ok || s == null || System.currentTimeMillis() - s.sinceMillis <= 60_000) return null
        return STILL_NOTICE
    }

    // Swift tips(_:) / wbTip(_:_:), ported as they are. Like the Swift body (which shows the Red/Blue bars and
    // the still notice, never tips(_:)), nothing here renders them; to show them, put
    // `TipList(tips(reading))` after the bars.
    fun wbTip(name: String, e: Double): TipItem {
        if (abs(e) <= 0.012) return TipItem("$name is on target", TipKind.OK)
        val clicks = if (e.isFinite()) tvcal.kit.clamp(swiftRounded(abs(e) / 0.008).toInt(), 1, 10) else 1
        val control = when (kind) {
            WBKind.GAIN -> "$name ${brand.gainName}"
            WBKind.OFFSET -> "$name ${brand.offsetName}"
            WBKind.FINE -> suggestion?.let { "$name on the $it% control" } ?: "$name on the multipoint control"
        }
        return TipItem(
            "$name is ${fmt(abs(e) * 100, 0)}% ${if (e > 0) "too strong" else "too weak"}: " +
                "${if (e > 0) "lower" else "raise"} $control about $clicks click${if (clicks > 1) "s" else ""}, then watch the bar.",
            TipKind.ACTION,
        )
    }

    @Suppress("unused")
    fun tips(r: LiveReading): List<TipItem> {
        if (r.steady) return listOf(TipItem("Both channels are on target. Keep these TV settings.", TipKind.DONE))
        val out = mutableListOf(wbTip("Red", r.qR ?: 0.0), wbTip("Blue", r.qB ?: 0.0))
        val s = still
        if (kind == WBKind.FINE && !r.ok && s != null && System.currentTimeMillis() - s.sinceMillis > 60_000) {
            out += TipItem(STILL_NOTICE, TipKind.LIMIT)
        }
        return out
    }

    LaunchedEffect(Unit) { guided.showLive(pid) }

    // Fine-tune: notice when the bars haven't moved for a minute (maybe the wrong control).
    LiveOnChange(guided.live.reading?.qR) {
        if (kind == WBKind.FINE) {
            val r = guided.live.reading
            val qR = r?.qR
            val qB = r?.qB
            if (qR != null && qB != null) {
                val s = still
                if (!(s != null && abs(s.q[0] - qR) <= 0.006 && abs(s.q[1] - qB) <= 0.006)) {
                    still = StillBars(listOf(qR, qB), System.currentTimeMillis())
                }
            }
        }
    }

    PinnedAction(nextTitle) { next(done = guided.live.reading?.steady ?: false) }

    val reading = guided.live.reading
    // The reading, when it's for the pattern on screen.
    val valid = reading?.takeIf { it.mismatch == null }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // The instruction, then the white-point target, big: the dot has to land in the centre ring. Then the
        // video to play (the grey's details when fine-tuning).
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (kind == WBKind.FINE) Eyebrow("Fine-tune $greyName")
            Text(instructions, style = Fonts.stepTitleStyle)
            Text("Until both bars sit on the line. Leave Green alone.", style = Fonts.captionBrand.secondary())
        }
        if (valid != null) BigWhiteTarget(valid)
        if (guided.tv.isDemo) {
            DemoNote()
        } else {
            val f = guided.file(pid)
            if (f != null) PlayFileCard(file = f, mode = guided.mode, compact = true, patch = Patterns.patch(guided.hdr, pid))
        }

        // live
        val e = guided.live.error
        if (e != null) TipRow(TipTone.LIMIT, e)
        if (reading != null) {
            val m = reading.mismatch
            if (m != null) {
                TipRow(TipTone.LIMIT, "$m Is the right file playing, with the camera pointed at the centre?")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    ChannelBalanceRow(label = "Red", value = reading.qR, colour = Theme.red, chime = chimeKey("R"))
                    ChannelBalanceRow(label = "Blue", value = reading.qB, colour = Theme.blue, chime = chimeKey("B"))
                    BalanceScale()
                }
                val stuck = stillNotice(reading)
                if (stuck != null) TipRow(TipTone.LIMIT, stuck)
            }
        } else {
            WaitingForReading()
        }

        if (guided.tv.isDemo) {
            DemoRemote(guided = guided, controls = demoControls, modifier = Modifier.padding(top = 4.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            LinkButton("Can't get closer", onClick = {
                guided.markLimit(
                    when (kind) {
                        WBKind.GAIN -> "bright"
                        WBKind.OFFSET -> "dark"
                        WBKind.FINE -> "fine:$pid"
                    },
                )
                next(done = true)
            })
            if (kind == WBKind.FINE && nextGrey(pid) != null) {
                LinkButton("Back to the check", onClick = { guided.go(GuideStep.CHECK) })
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("TV menu: $whereText", style = Fonts.small.secondary())
            if (valid != null) AverageLine(reading = valid, restart = { guided.live.restartAverage() })
        }
    }
}

/** The white-point target (150 dp) beside "On target" / "In the ring. Hold still…" / "Bring the dot into the ring". */
@Composable
private fun BigWhiteTarget(reading: LiveReading) {
    // Beside its message, so the Red and Blue bars below still fit on screen.
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clearAndSetSemantics {
                contentDescription = "White point target"
                stateDescription = if (reading.steady) "On target" else "dE ${fmt(reading.de, 1)} from D65"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        WhiteTarget(uv = reading.uv, d65 = reading.d65uv, ok = reading.ok, modifier = Modifier.size(150.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (reading.steady) "On target"
                else if (reading.ok) "In the ring. Hold still…" else "Bring the dot into the ring",
                style = Fonts.bodyStrong.copy(color = if (reading.ok) Theme.green else Color.White),
            )
            Text("dE ${fmt(reading.de, 1)} from D65", style = Fonts.small.monospacedDigit().secondary())
        }
    }
}

// MARK: - 6. Colours, one at a time

/** One of the six colours: yellow ring when on screen, a check when done, a minus at the TV's limit. */
@Composable
private fun ColourChip(
    id: String,
    selected: Boolean,
    done: Boolean,
    limit: Boolean,
    action: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Theme.swatch(id)
    val name = liveCap(id)
    Column(
        modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = action,
            )
            .clearAndSetSemantics {
                contentDescription = name + (if (limit) ", as close as this TV gets" else if (done) ", done" else "")
                this.selected = selected
                role = Role.Button
                onClick { action(); true }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(if (selected) c.copy(alpha = 0.15f) else Theme.card)
                .border(1.5.dp, if (selected) Theme.accent else c, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (done) {
                Icon(if (limit) Symbols.minus else Symbols.checkmark, contentDescription = null, tint = c, modifier = Modifier.size(13.dp))
            } else {
                Box(Modifier.size(5.dp).clip(CircleShape).background(c))
            }
        }
        Text(
            name,
            style = Fonts.micro.copy(color = if (selected) Color.White else Theme.textSecondary),
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * Colours one at a time: MenuQuestionCard(colours) while the colour menu is unknown (real TVs); else the
 * chips, the before/gamut notes, PlayFileCard, the live GamutChart + control rows (HSL or R/G/B) + AverageLine
 * + TipList, DemoRemote, "Can't get closer", "TV menu: …". PinnedAction "Next colour" / "Finish colours": on
 * target (or menu OFF) → finishColour, else the "<Colour> isn't on target yet" AlertDialog ("Move on: it's as
 * close as this TV gets" / "Keep adjusting").
 */
@Composable
fun ColoursStep(guided: GuidedController) {
    if (!guided.tv.colourMenuKnown && !guided.tv.isDemo) {
        MenuQuestionCard(tv = guided.tv, colours = true)
    } else {
        ColoursContent(guided)
    }
}

@Composable
private fun ColoursContent(guided: GuidedController) {
    // "Next colour" was tapped before this colour got on target: ask whether it's as close as it gets.
    var unfinished by remember { mutableStateOf<String?>(null) }

    fun current(): String =
        guided.progress.colour ?: Patterns.colourIDs.firstOrNull { it !in guided.progress.colours } ?: "RED"

    val cms = guided.tv.colourMenu

    fun show() {
        if (guided.progress.step == GuideStep.COLOURS) guided.showLive(current())
    }

    // Every other colour is already done.
    fun isLastColour(c: String): Boolean = Patterns.colourIDs.all { it == c || it in guided.progress.colours }

    val cur = current()
    val name = liveCap(cur)

    LaunchedEffect(Unit) { show() }

    PinnedAction(if (isLastColour(cur)) "Finish colours" else "Next colour") {
        // Worked out at tap time: the pinned button can outlive this colour.
        val c = current()
        val r = guided.live.reading
        val onTarget = (r?.id == c && (r?.steady ?: false)) || guided.tv.colourMenu == ColourMenu.OFF
        if (onTarget) {
            guided.finishColour(c, done = true)
            show()
        } else {
            unfinished = c
        }
    }

    // The before check, analysed once per measurement (for the gamut note).
    val before = guided.lastSession?.before
    val hdr = guided.hdr
    val beforeReport = remember(before, hdr) { before?.let { Analysis.analyse(it, hdr) } }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (guided.tv.isDemo) DemoNote()
        Text("Bring $name onto its ring", style = Fonts.stepTitleStyle)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            for (c in Patterns.colourIDs) {
                val done = c in guided.progress.colours
                ColourChip(
                    id = c,
                    selected = c == cur,
                    done = done,
                    limit = done && c in guided.progress.limits,
                    action = {
                        guided.setColour(c)
                        guided.showLive(c)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (guided.latestBefore == null) {
            TipRow(TipTone.LIMIT, "Run the Before check first, so brightness can be judged against this TV's own white.")
        } else if (!guided.tv.isDemo && beforeReport != null) {
            val note = GamutCheck.note(beforeReport, guided.mode, guided.tv.brand)
            if (note != null) TipRow(TipTone.LIMIT, note)
        }
        if (!guided.tv.isDemo) {
            val f = guided.file(cur)
            if (f != null) PlayFileCard(file = f, mode = guided.mode)
        }
        ColoursLive(guided, cur, cms)
        if (guided.tv.isDemo && cms != ColourMenu.OFF) {
            val n = guided.tv.brand.cmsNames
            DemoRemote(
                guided = guided,
                controls = if (cms == ColourMenu.RGB_SLIDERS) {
                    listOf(
                        DemoControl("$name Red", "cms:$cur:R"),
                        DemoControl("$name Green", "cms:$cur:G"),
                        DemoControl("$name Blue", "cms:$cur:B"),
                    )
                } else {
                    listOf(
                        DemoControl("$name ${n.hue}", "hsl:$cur:hue"),
                        DemoControl("$name ${n.saturation}", "hsl:$cur:sat"),
                        DemoControl("$name ${n.brightness}", "hsl:$cur:lum"),
                    )
                },
            )
        }
        LinkButton("Can't get closer", onClick = {
            val c = current()
            guided.markLimit(c)
            guided.finishColour(c, done = true)
            show()
        })
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (cms == ColourMenu.OFF) {
                Text(
                    "No colour controls are set for this TV: just check the colours, or choose its colour menu in Screen settings.",
                    style = Fonts.small.secondary(),
                )
            }
            Text("TV menu: ${guided.tv.brand.colourMenuPath} › $name", style = Fonts.small.secondary())
        }
    }

    val pending = unfinished
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { unfinished = null },
            containerColor = Theme.surface,
            titleContentColor = Color.White,
            textContentColor = Theme.textSecondary,
            title = { Text("${liveCap(pending)} isn't on target yet", style = Fonts.headlineBrand) },
            text = {
                Text(
                    "If the sliders have stopped bringing the dot closer to its ring, the TV has reached its limit for this colour. That's common, and the app will note it in your results.",
                    style = Fonts.bodyBrand,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val c = unfinished
                    if (c != null) {
                        guided.markLimit(c)
                        guided.finishColour(c, done = true)
                        show()
                    }
                    unfinished = null
                }) { Text("Move on: it's as close as this TV gets", style = Fonts.bodyStrong.copy(color = Theme.tealBright)) }
            },
            dismissButton = {
                TextButton(onClick = { unfinished = null }) {
                    Text("Keep adjusting", style = Fonts.bodyBrand.copy(color = Theme.tealBright))
                }
            },
        )
    }
}

/** The live part of the colours step (Swift liveView(cur)): siblings in the step's column. */
@Composable
private fun ColoursLive(guided: GuidedController, cur: String, cms: ColourMenu) {
    val e = guided.live.error
    if (e != null) TipRow(TipTone.LIMIT, e)
    val r = guided.live.reading
    if (r != null && r.id == cur) {
        val m = r.mismatch
        if (m != null) {
            TipRow(TipTone.LIMIT, "$m Is the right file playing, with the camera pointed at the centre?")
        } else {
            val t = r.tuv
            if (t != null) {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    GamutChart(
                        dots = listOf(GamutChart.Dot(id = cur, uv = r.uv, target = t, de = r.de)),
                        hdr = guided.hdr,
                        modifier = Modifier.heightIn(max = 220.dp),
                    )
                    Text(
                        "Ring: target · dot: now · dE ${fmt(r.de, 1)}",
                        style = Fonts.small.monospacedDigit().secondary(),
                    )
                }
            }
            ColourControlRows(guided, r, cur, cms)
            AverageLine(reading = r, restart = { guided.live.restartAverage() })
            TipList(colourTips(r, cur, cms))
        }
    } else {
        WaitingForReading()
    }
}

/** The success chime's name for one control of one colour: per TV, format, colour and control. */
private fun colourChimeKey(guided: GuidedController, colour: String, control: String): String =
    "${guided.tv.id.upper}|${guided.mode.rawValue}|colour|$colour|$control"

@Composable
private fun ColourControlRows(guided: GuidedController, r: LiveReading, cur: String, cms: ColourMenu) {
    val colour = Theme.swatch(cur)
    if (cms == ColourMenu.RGB_SLIDERS) {
        val h = r.rgbHint
        val stuck = r.outOfReach?.channels ?: emptyList()
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            listOf("Red", "Green", "Blue").forEachIndexed { j, label ->
                // The hint says how far to move the slider (+ raise); drawn as the error (+ too much).
                val v: Double? = if (h == null || listOf("R", "G", "B")[j] in stuck) null else -h[j] * 100
                ColourControlRow(
                    label = label, value = v, colour = colour, fullScale = 20.0, tolerance = 3.0,
                    caption = v?.let { if (it > 0) "Too much: lower it" else "Too little: raise it" },
                    chime = colourChimeKey(guided, cur, label),
                )
            }
            BalanceScale()
        }
        if (r.rgbApprox) {
            Text("Estimated: the Before check didn't get all three primaries.", style = Fonts.small.secondary())
        }
    } else {
        val n = guided.tv.brand.cmsNames
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            ColourControlRow(
                label = n.hue, value = r.hue, colour = colour, fullScale = 10.0, tolerance = 2.0,
                caption = "Leans toward ${r.toward?.let { liveCap(it) } ?: "a neighbour"}: move ${n.hue} a click and keep going the way that shrinks the bar",
                chime = colourChimeKey(guided, cur, "hue"),
            )
            ColourControlRow(
                label = n.saturation, value = r.sat, colour = colour, fullScale = 15.0, tolerance = 3.0,
                caption = if ((r.sat ?: 0.0) > 0) "Too strong: lower ${n.saturation}" else "Too weak: raise ${n.saturation}",
                chime = colourChimeKey(guided, cur, "sat"),
            )
            ColourControlRow(
                label = n.brightness, value = r.lum, colour = colour, fullScale = 25.0, tolerance = 5.0,
                caption = if ((r.lum ?: 0.0) > 0) "Too bright: lower ${n.brightness}" else "Too dim: raise ${n.brightness}",
                chime = colourChimeKey(guided, cur, "lum"),
            )
            BalanceScale()
        }
    }
}

private fun colourTips(d: LiveReading, cur: String, cms: ColourMenu): List<TipItem> {
    val name = liveCap(cur)
    if (d.steady) return listOf(TipItem("$name is on target. Nice.", TipKind.DONE))
    val t = mutableListOf<TipItem>()
    when (cms) {
        ColourMenu.OFF -> return listOf(TipItem("Nothing to adjust on this TV; just note the numbers.", TipKind.OK))
        ColourMenu.HSL -> Unit // each bar says what to do
        ColourMenu.RGB_SLIDERS -> {
            if (d.rgbHint == null) {
                return listOf(
                    TipItem(
                        "Run the Before check first: the Red/Green/Blue hints need this TV's white as a reference.",
                        TipKind.ACTION,
                    ),
                )
            }
            val names = listOf("Red", "Green", "Blue")
            val stuck = (d.outOfReach?.channels ?: emptyList()).mapNotNull { ch ->
                listOf("R", "G", "B").indexOf(ch).takeIf { it >= 0 }
            }
            // (Swift sorts the remaining sliders by hint size here but no longer uses the order: each bar says
            // what to do.)
            val o = d.outOfReach
            if (o != null) {
                val others = listOf(0, 1, 2).filter { it !in stuck }.joinToString(" and ") { names[it].lowercase() }
                val below = stuck.joinToString(" and ") { names[it] }
                t += TipItem(
                    "**Out of reach:** to match exactly, $name would need $below below zero. $name sits beyond what this TV's $others can mix, so that slider stops helping once it reaches zero. The best this colour can get is about **dE ${fmt(o.bestDE)}**. When you're there, use **Can't get closer**.",
                    TipKind.LIMIT,
                )
            }
        }
    }
    if (t.isEmpty() && d.ok) t += TipItem("Close enough - holding steady…", TipKind.OK)
    return t
}

// MARK: - Fine-tune (multipoint) and the point finder

/**
 * MenuQuestionCard(white balance) while multipoint is unknown (real TVs); 2-point TVs: "Fine-tune" text +
 * PinnedAction "Back to the check"; the PointFinderView while holding or until the finder is ready or skipped;
 * else FineTuneView. The point finder's hold (Swift @State holdingFinder) is guided.finderHolding.
 */
@Composable
fun FineStep(guided: GuidedController) {
    // guided.finderHolding keeps the point finder on screen through its second measurement and its result
    // page (where the user is told to put the controls back), even though the finder's map is ready by then.
    // It lives in the controller so a sweep that ends after this composable was recreated still lands there.
    if (!guided.tv.multipointKnown && !guided.tv.isDemo) {
        MenuQuestionCard(tv = guided.tv, colours = false)
    } else if (guided.tv.multipoint == Multipoint.NONE) {
        TwoPointFine(guided)
    } else if (guided.finderHolding || (!guided.finderReady && !guided.finderSkipped)) {
        PointFinderView(guided = guided)
    } else {
        FineTuneView(guided)
    }
}

/** 2-point white balance only: nothing to fine-tune. */
@Composable
private fun TwoPointFine(guided: GuidedController) {
    PinnedAction("Back to the check") { guided.go(GuideStep.CHECK) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Fine-tune", style = Fonts.stepTitleStyle)
        Text(
            "This TV has only 2-point white balance (Screen settings), so there's nothing to fine-tune beyond the bright and dark ends.",
            style = Fonts.bodyBrand.secondary(),
        )
    }
}

/**
 * WhiteBalanceStep(FINE, current grey) keyed by the grey, the grey chips ("50% → TV 40% ✓"), and the "The
 * control that moves this grey" picker (DropdownMenu: "TV 40% (measured)" / "Measured", then TV grid%…100%)
 * with "Run the point finder again"; or, skipped, "If the bars don't move…".
 */
@Composable
fun FineTuneView(guided: GuidedController) {
    val cur = guided.progress.finePid ?: guided.plan.fine[0]
    val sugg = guided.suggestions
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        key(cur) {
            WhiteBalanceStep(guided = guided, kind = WBKind.FINE, pid = cur)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Eyebrow("Greys")
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 1.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (pid in guided.plan.fine) {
                    val selected = pid == cur
                    val shape = CircleShape
                    Text(
                        shortName(Patterns.patch(guided.hdr, pid)?.name ?: pid) +
                            (sugg[pid]?.let { " → TV $it%" } ?: "") +
                            (if (guided.state.finder.overrides[pid] != null) " ✓" else ""),
                        style = Fonts.captionBrand.copy(color = if (selected) Color.White else Theme.textSecondary),
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .clip(shape)
                            .background(if (selected) Theme.surface else Theme.card)
                            .border(if (selected) 1.5.dp else 1.dp, if (selected) Theme.accent else Theme.hairline, shape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Button,
                            ) {
                                guided.setFinePid(pid)
                                guided.showLive(pid)
                            }
                            // Swift .accessibilityAddTraits(selected ? .isSelected : [])
                            .semantics { this.selected = selected }
                            .padding(vertical = 8.dp, horizontal = 12.dp),
                    )
                }
            }
        }
        Card(spacing = 8.dp) {
            if (!guided.finderSkipped) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "The control that moves this grey",
                        style = Fonts.bodyStrong.white(),
                        modifier = Modifier.weight(1f),
                    )
                    ControlPicker(guided, cur, sugg[cur])
                }
                Text(
                    "If you pick one, it's remembered for this TV, and the suggestions for nearby greys adjust to match.",
                    style = Fonts.small.secondary(),
                )
                LinkButton("Run the point finder again", onClick = { guided.resetFinder() }, style = Fonts.captionStrong)
            } else {
                Text(
                    "If the bars don't move, try the control one step lower or higher.",
                    style = Fonts.small.secondary(),
                )
            }
        }
    }
}

/** The Swift menu Picker: the chosen control in teal; a tap opens the list. 0 = the measured suggestion. */
@Composable
private fun ControlPicker(guided: GuidedController, cur: String, suggested: Int?) {
    var open by remember { mutableStateOf(false) }
    val selection = guided.state.finder.overrides[cur] ?: 0
    val measuredLabel = suggested?.let { "TV $it% (measured)" } ?: "Measured"
    val g = guided.grid
    val options: List<Int> = listOf(0) + (if (g > 0) (g..100 step g).toList() else emptyList())
    fun label(v: Int): String = if (v == 0) measuredLabel else "TV $v%"
    Box {
        Row(
            Modifier
                .heightIn(min = 44.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClickLabel = "The control that moves this grey",
                ) { open = true },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(label(selection), style = Fonts.bodyBrand.copy(color = Theme.tealBright), maxLines = 1)
            Icon(Symbols.chevronDown, contentDescription = null, tint = Theme.tealBright, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (v in options) {
                DropdownMenuItem(
                    text = { Text(label(v), style = Fonts.bodyBrand.white()) },
                    onClick = {
                        open = false
                        guided.setOverride(cur, point = if (v == 0) null else v)
                    },
                    leadingIcon = {
                        if (v == selection) {
                            Icon(Symbols.checkmark, contentDescription = null, tint = Theme.tealBright, modifier = Modifier.size(18.dp))
                        } else {
                            Spacer(Modifier.size(18.dp))
                        }
                    },
                )
            }
        }
    }
}

private enum class FinderStatus { DONE, ACTIVE, WAITING }

/** One line of the point finder's progress: done (check), happening now (spinner) or to come (clock). */
@Composable
private fun FinderStatusRow(text: String, state: FinderStatus) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text,
            style = Fonts.bodyBrand.copy(color = if (state == FinderStatus.WAITING) Theme.textSecondary else Color.White),
            modifier = Modifier.weight(1f),
        )
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            when (state) {
                FinderStatus.DONE ->
                    Icon(Symbols.checkmark, contentDescription = "Done", tint = Theme.tealBright, modifier = Modifier.size(18.dp))
                FinderStatus.ACTIVE ->
                    CircularProgressIndicator(Modifier.size(16.dp), color = Theme.tealBright, strokeWidth = 2.dp)
                FinderStatus.WAITING ->
                    Icon(Symbols.clock, contentDescription = "To come", tint = Theme.tealBright, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/**
 * The point finder (3 steps: measure as is, nudge four controls +PointFinder.NUDGE, measure again), then the
 * result page ("Here's what each control moves", "Now put them back."). PinnedAction: "Measure (about 2½
 * minutes)" (guided.startFinder(false)) / "I've made the four changes - measure" (guided.startNudgedFinder():
 * holding, measureFinder(true), result page when finderMap.size >= 2, else not holding) / "Fine-tune with these
 * points" (guided.finishFinderResult(): go(FINE)); none while running (Cancel in the page). The hold and the
 * result page (Swift @Binding holding / @State showingResult) are guided.finderHolding / finderShowingResult.
 */
@Composable
fun PointFinderView(guided: GuidedController) {
    val showingResult = guided.finderShowingResult

    val haveBase = guided.state.finder.base != null
    // 1: measure as it is, 2: nudge four controls, 3: measure again.
    val step = if (!haveBase) 1 else if (guided.runner.running) 3 else 2

    // The main action for the state on screen; none while measuring (Cancel is in the page).
    val pinnedTitle: String? = when {
        showingResult -> "Fine-tune with these points"
        guided.runner.running -> null
        haveBase -> "I've made the four changes - measure"
        else -> "Measure (about 2½ minutes)"
    }

    PinnedAction(pinnedTitle) {
        if (guided.finderShowingResult) {
            guided.finishFinderResult()
        } else if (guided.runner.running) {
            // nothing: Cancel is in the page
        } else if (guided.state.finder.base != null) {
            guided.startNudgedFinder()
        } else {
            guided.startFinder(nudged = false)
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (showingResult) {
            FinderResult(guided)
        } else {
            FinderFinding(guided, haveBase, step)
        }
    }
}

@Composable
private fun FinderResult(guided: GuidedController) {
    Text("Here's what each control moves", style = Fonts.stepTitleStyle)
    Text("TV control → moves the grey at", style = Fonts.bodyBrand.secondary())
    Card(spacing = 4.dp) {
        for (m in guided.finderMap) {
            val shift = m.level - m.point.toDouble()
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .clearAndSetSemantics {
                        contentDescription =
                            "${if (m.channel == "R") "Red" else if (m.channel == "B") "Blue" else "TV"} ${m.point}, " +
                            "→ ${fmt(m.level, 0)}%, Shift ${signed(shift, 0)}"
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${if (m.channel == "R") "Red" else if (m.channel == "B") "Blue" else "TV"} ${m.point}",
                        style = Fonts.brand(16f).white(),
                        modifier = Modifier.alignByBaseline(),
                    )
                    Spacer(Modifier.weight(1f))
                    Column(Modifier.alignByBaseline(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("→ ${fmt(m.level, 0)}%", style = Fonts.brand(17f).copy(color = Theme.tealBright))
                        Text(
                            "Shift ${signed(shift, 0)}",
                            style = Fonts.tiny.copy(color = if (abs(shift) >= 5) Theme.limit else Theme.green),
                        )
                    }
                }
            }
        }
    }
    TipRow(
        TipTone.ACTION,
        "**Now put them back.** Set those four controls back to where they were (−10 each) before fine-tuning.",
    )
}

@Composable
private fun FinderFinding(guided: GuidedController, haveBase: Boolean, step: Int) {
    val running = guided.runner.running
    Text(if (running) "Finding your TV's points" else "Find your TV's controls", style = Fonts.stepTitleStyle)
    Card(hero = true) {
        Eyebrow("Optional · about 6 minutes")
        Text("Step $step of 3", style = Fonts.cardTitle.tracking(-0.6f).white())
        when (step) {
            1 -> Text(
                boldMarkdown("Measure the greys as they are. Tap Measure, then play **${guided.finderFile}** on the TV."),
                style = Fonts.bodyBrand.white(),
            )
            2 -> {
                Text("Nudge four controls in the multipoint menu, exactly as listed:", style = Fonts.bodyBrand.white())
                // Swift Grid(horizontalSpacing: 18, verticalSpacing: 6): one column per field.
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (n in PointFinder.nudges) Text("TV ${n.point}% control", style = Fonts.bodyBrand.white())
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (n in PointFinder.nudges) {
                            Text(
                                if (n.channel == "R") "Red" else "Blue",
                                style = Fonts.bodyBrand.copy(color = Theme.channel(n.channel)),
                            )
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (n in PointFinder.nudges) Text("+${PointFinder.NUDGE}", style = Fonts.bodyBrand.white())
                    }
                }
                Text(
                    "Then measure again with the same video. The app compares the two and finds each control.",
                    style = Fonts.captionBrand.secondary(),
                )
            }
            else -> Text(
                "Measure again with the same video. The app compares the two and finds each control.",
                style = Fonts.bodyBrand.white(),
            )
        }
        Row(Modifier.fillMaxWidth().clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            for (i in 1..3) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(if (i <= step) Theme.tealBright else Theme.hairline),
                )
            }
        }
    }
    Text(
        "A TV's “40%” control often moves a different grey. This finds where each one really acts.",
        style = Fonts.captionBrand.secondary(),
    )
    val phase = guided.runner.phase
    if (phase is SweepRunner.Phase.Failed) TipRow(TipTone.LIMIT, phase.text)
    if (running) MeasuringCard(runner = guided.runner, file = guided.finderFile, mode = guided.mode)
    Card(spacing = 0.dp) {
        FinderStatusRow(
            "Measure the greys as they are",
            if (haveBase) FinderStatus.DONE else if (running) FinderStatus.ACTIVE else FinderStatus.WAITING,
        )
        FinderStatusRow(
            "Nudge four controls (+${PointFinder.NUDGE} each)",
            if (step == 3) FinderStatus.DONE else if (step == 2) FinderStatus.ACTIVE else FinderStatus.WAITING,
        )
        FinderStatusRow(
            "Measure again and find each control",
            if (step == 3) FinderStatus.ACTIVE else FinderStatus.WAITING,
        )
    }
    if (running) {
        SecondaryButton("Cancel", onClick = { guided.runner.cancel() })
    } else {
        LinkButton("Skip: fine-tune without it", onClick = { guided.skipFinder() })
    }
}

// MARK: - Demo remote

/** One demo control: its label and the SimTV key ("gain:R", "p20:B:40", "cms:RED:G", "hsl:RED:hue"). */
data class DemoControl(val label: String, val key: String)

/** Demo TVs only: − / + buttons per control that stand in for the TV's menus (guided.demoPress(key to ±1)). */
@Composable
fun DemoRemote(guided: GuidedController, controls: List<DemoControl>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (c in controls) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(c.label, style = Fonts.bodyBrand.white(), modifier = Modifier.weight(1f))
                DemoKey("−", "${c.label} down") { guided.demoPress(mapOf(c.key to -1)) }
                DemoKey("+", "${c.label} up") { guided.demoPress(mapOf(c.key to 1)) }
            }
        }
    }
}

@Composable
private fun DemoKey(symbol: String, accessibility: String, action: () -> Unit) {
    Box(
        Modifier
            .size(width = 48.dp, height = 44.dp)
            .clip(CircleShape)
            .background(Theme.surface)
            .border(1.dp, Theme.tealBright, CircleShape)
            .clickable(role = Role.Button, onClick = action)
            .clearAndSetSemantics {
                contentDescription = accessibility
                role = Role.Button
                onClick { action(); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, style = Fonts.brand(24f).copy(color = Theme.tealBright))
    }
}
