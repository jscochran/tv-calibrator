package tvcal.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import tvcal.android.LocalApp
import tvcal.android.R
import tvcal.android.app.PrefKeys
import tvcal.android.measure.GuidedController
import tvcal.android.measure.QuickWhich
import tvcal.android.measure.SweepRunner
import tvcal.android.profiles.onYouTube
import tvcal.android.storage.Brand
import tvcal.android.storage.GamutCheck
import tvcal.android.storage.GuideStep
import tvcal.android.storage.Multipoint
import tvcal.android.storage.upper
import tvcal.android.ui.components.Card
import tvcal.android.ui.components.FormatOrderArt
import tvcal.android.ui.components.DarkRoomArt
import tvcal.android.ui.components.GreyBalanceChart
import tvcal.android.ui.components.LinkButton
import tvcal.android.ui.components.MeasuringCard
import tvcal.android.ui.components.ModeBadge
import tvcal.android.ui.components.NavLeading
import tvcal.android.ui.components.OrderStrip
import tvcal.android.ui.components.PinnedAction
import tvcal.android.ui.components.PinnedActionHost
import tvcal.android.ui.components.PlacementArt
import tvcal.android.ui.components.PlayFileCard
import tvcal.android.ui.components.PrimaryButton
import tvcal.android.ui.components.FullScreenSheet
import tvcal.android.ui.components.KeepScreenOn
import tvcal.android.ui.components.ReportSummary
import tvcal.android.ui.components.ScoreInfo
import tvcal.android.ui.components.ScoreTile
import tvcal.android.ui.components.ScreenColumn
import tvcal.android.ui.components.ScreenScaffold
import tvcal.android.ui.components.SecondaryButton
import tvcal.android.ui.components.TipRow
import tvcal.android.ui.components.TipTone
import tvcal.android.ui.components.WalkPage
import tvcal.android.ui.components.Walkthrough
import tvcal.android.ui.components.openUrl
import tvcal.android.ui.nav.LocalNav
import tvcal.android.ui.theme.Fonts
import tvcal.android.ui.theme.Symbols
import tvcal.android.ui.theme.Theme
import tvcal.android.ui.theme.fmt
import tvcal.android.ui.theme.lineSpacing
import tvcal.android.ui.theme.monospacedDigit
import tvcal.android.ui.theme.secondary
import tvcal.android.ui.theme.signed
import tvcal.android.ui.theme.tint
import tvcal.android.ui.theme.tracking
import tvcal.android.ui.theme.typographicMinus
import tvcal.android.ui.theme.white
import tvcal.kit.Analysis
import tvcal.kit.GreyResult
import tvcal.kit.Measurements
import tvcal.kit.Patterns
import tvcal.kit.Report
import tvcal.kit.SignalMode
import java.time.Instant
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

// Port of Views/GuidedView.swift: the guided calibration, one step per screen (Set up, Before / Check /
// After, the step bar, the newsletter card). Long work: guided.startQuick / startFinder from onClick
// (app scope, so leaving the screen doesn't cancel a sweep).

/**
 * The step switch: GuidedContextHeader (compact for BRIGHT/DARK/COLOURS/FINE), guided.message as a LIMIT
 * TipRow (+ "Set up the camera" SecondaryButton → CameraSetupSheet for real TVs while app.model.cameraBlocker
 * != null), then the step, keyed by "<mode>-<step>-<restarts>" (`key(...)`). Closing the camera sheet: when
 * the blocker is gone, guided.message = null and a compact (live) step restarts (restarts += 1).
 */
@Composable
fun GuidedView(guided: GuidedController, modifier: Modifier = Modifier) {
    // rememberSaveable: the camera sheet stays open across Activity recreation (its onDismiss then still runs).
    var showingCamera by rememberSaveable { mutableStateOf(false) }
    // Bumped when the camera setup closes during a live step, so the step starts reading again.
    var restarts by rememberSaveable { mutableIntStateOf(0) }

    val step = guided.progress.step
    // The hands-on steps (bright end, dark end, colours, fine-tune) show a one-line context;
    // the others show the screen, the format and the seven-step bar.
    val compactContext = step == GuideStep.BRIGHT || step == GuideStep.DARK ||
        step == GuideStep.COLOURS || step == GuideStep.FINE

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GuidedContextHeader(guided, compact = compactContext)
        val m = guided.message
        if (m != null) {
            TipRow(TipTone.LIMIT, m)
            if (!guided.tv.isDemo && guided.app.cameraBlocker != null) {
                SecondaryButton("Set up the camera", onClick = { showingCamera = true })
            }
        }
        key("${guided.mode.rawValue}-${step.rawValue}-$restarts") {
            when (step) {
                GuideStep.SETUP -> SetupStep(guided, openCamera = { showingCamera = true })
                GuideStep.BEFORE -> QuickStep(guided, QuickWhich.BEFORE)
                GuideStep.BRIGHT -> WhiteBalanceStep(guided, WBKind.GAIN, guided.plan.gain)
                GuideStep.DARK -> WhiteBalanceStep(guided, WBKind.OFFSET, guided.plan.offset)
                GuideStep.CHECK -> CheckStep(guided)
                GuideStep.COLOURS -> ColoursStep(guided)
                GuideStep.AFTER -> QuickStep(guided, QuickWhich.AFTER)
                GuideStep.FINE -> FineStep(guided)
            }
        }
    }

    // A sheet, not a push (as on the iPhone).
    if (showingCamera) {
        CameraSetupSheet(onDismiss = {
            showingCamera = false
            // Back from the camera setup: forget the "start the camera" message once it no longer applies,
            // and let a live step begin reading.
            if (guided.app.cameraBlocker == null) {
                guided.message = null
                val s = guided.progress.step
                if (s == GuideStep.BRIGHT || s == GuideStep.DARK || s == GuideStep.COLOURS || s == GuideStep.FINE) {
                    restarts += 1
                }
            }
        })
    }
}

/** Camera setup opened from a calibration step: FullScreenSheet { CameraSetupView(leading = CLOSE) }. */
@Composable
fun CameraSetupSheet(onDismiss: () -> Unit) {
    val app = LocalApp.current
    FullScreenSheet(onDismiss = onDismiss) {
        CameraSetupView(
            camera = app.camera,
            profiles = app.profiles,
            onDone = onDismiss,
            leading = NavLeading.CLOSE,
        )
    }
}

/**
 * One step as its own screen (pushed from the screen home): NavBar title ("Check and fine-tune" when the
 * check has a report and isn't running, else the step's label), back → [onBack]; PinnedActionHost around a
 * ScreenColumn { GuidedView }, with below = a "Cancel" SecondaryButton bar while guided.runner.running
 * (guided.runner.cancel()). KeepScreenOn() while shown; on dispose guided.stopLive().
 */
@Composable
fun GuidedScreen(guided: GuidedController, onBack: () -> Unit) {
    val title = if (guided.progress.step == GuideStep.CHECK && guided.checkReport != null && !guided.runner.running) {
        "Check and fine-tune"
    } else {
        guided.progress.step.label
    }
    // Measuring takes minutes with nobody touching the phone (and the demo has no camera to keep it awake).
    KeepScreenOn()
    DisposableEffect(guided) {
        onDispose { guided.stopLive() }
    }
    val running = guided.runner.running
    // While a video is being measured, Cancel sits under the content.
    val cancelBar: @Composable () -> Unit = {
        Box(
            Modifier
                .fillMaxWidth()
                .background(Theme.background.copy(alpha = 0.96f))
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 10.dp),
        ) {
            SecondaryButton("Cancel", onClick = { guided.runner.cancel() })
        }
    }
    ScreenScaffold(title = title, leading = NavLeading.BACK, onLeading = onBack) {
        PinnedActionHost(
            modifier = Modifier.fillMaxSize(),
            below = if (running) cancelBar else null,
        ) {
            ScreenColumn {
                GuidedView(guided)
            }
        }
    }
}

/**
 * "Living room OLED" + ModeBadge, then the StepBar; or, [compact], a capsule "Living room OLED · Step 3 of 7"
 * (list icon) that opens StepNavigatorSheet(open = {}).
 */
@Composable
private fun GuidedContextHeader(guided: GuidedController, compact: Boolean) {
    var navigating by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (compact) {
                // The hands-on steps keep the bar out of the way: "Step 3 of 7" opens the step list.
                Box(Modifier.weight(1f)) {
                    Row(
                        Modifier
                            .heightIn(min = 32.dp)
                            .clip(CircleShape)
                            .background(Theme.card)
                            .border(1.dp, Theme.hairline, CircleShape)
                            .clickable(role = Role.Button, onClickLabel = "Shows all the steps, to go back or ahead") {
                                navigating = true
                            }
                            .padding(horizontal = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${guided.tv.name} · ${StepBar.position(guided.progress.step)}",
                            style = Fonts.captionStrong,
                            color = Theme.tealBright,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Icon(
                            Symbols.listNumber,
                            contentDescription = null,
                            tint = Theme.tealBright,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            } else {
                Text(
                    guided.tv.name,
                    style = Fonts.captionBrand.secondary(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            ModeBadge(guided.mode)
        }
        if (!compact) {
            StepBar(guided)
        }
    }
    if (navigating) {
        StepNavigatorSheet(
            guided = guided,
            screenName = guided.tv.name,
            mode = guided.mode,
            open = {},
            onDismiss = { navigating = false },
        )
    }
}

/** StepBar's static helpers. */
object StepBar {
    /** The step shown as current: fine-tuning belongs to the Check step. */
    fun shown(step: GuideStep): GuideStep = if (step == GuideStep.FINE) GuideStep.CHECK else step

    /** "Step 3 of 7". */
    fun position(of: GuideStep): String {
        val idx = GuideStep.stepper.indexOf(shown(of))
        val i = (if (idx < 0) 0 else idx) + 1
        return "Step $i of ${GuideStep.stepper.size}"
    }
}

/**
 * The seven steps as buttons (done teal ✓, current yellow number, the rest outlined), then the current
 * step's label and "Step 3 of 7 · tap a step to jump". Tapping a step: guided.go(step).
 * [onTap] non-null (the screen home): the steps don't react; the whole bar is one button calling it
 * (Swift allowsHitTesting(false) + overlay, "Jump to a step").
 */
@Composable
fun StepBar(guided: GuidedController, modifier: Modifier = Modifier, onTap: (() -> Unit)? = null) {
    val current = StepBar.shown(guided.progress.step)
    val done = guided.progress.done
    val barModifier = if (onTap != null) {
        modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onTap,
            )
            .clearAndSetSemantics { contentDescription = "Jump to a step" }
    } else {
        modifier
    }
    Column(barModifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
            GuideStep.stepper.forEachIndexed { i, step ->
                val now = step == current
                val isDone = !now && step in done
                val shape = RoundedCornerShape(8.dp)
                val fg = if (now || isDone) Theme.background else Theme.textSecondary
                var box = Modifier
                    .weight(1f)
                    .heightIn(min = 30.dp)
                    .clip(shape)
                    .background(if (now) Theme.accent else if (isDone) Theme.tealBright else Theme.card)
                if (!now && !isDone) box = box.border(1.dp, Theme.hairline, shape)
                if (onTap == null) {
                    box = box
                        .clickable(role = Role.Button) { guided.go(step) }
                        .semantics {
                            contentDescription = "${step.label}, step ${i + 1}" +
                                (if (now) ", current" else if (isDone) ", done" else "")
                        }
                }
                Box(box, contentAlignment = Alignment.Center) {
                    if (isDone) {
                        Icon(Symbols.checkmark, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
                    } else {
                        Text(
                            "${i + 1}",
                            style = Fonts.captionStrong,
                            color = fg,
                            modifier = Modifier.clearAndSetSemantics {},
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text(
                current.label,
                style = Fonts.captionBrand,
                color = Theme.accent,
                modifier = Modifier.alignByBaseline(),
            )
            Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
            Text(
                StepBar.position(guided.progress.step) + " · tap a step to jump",
                style = Fonts.small.secondary(),
                modifier = Modifier.alignByBaseline(),
            )
        }
    }
}

// MARK: - 1. Set up

/**
 * Getting ready, one idea per page (Walkthrough, section "Getting ready", finish "Start: measure the TV as it
 * is" → guided.go(BEFORE, markDone = SETUP)), keyed by mode: the format (FormatOrderArt), the videos
 * (GuidedSourceHero + GuidedNoticeCard + PatternLinkButton), the TV's settings (GuidedSettingsList of
 * brand.setup(mode)), dim the room, place the phone, and for real TVs "Get the camera ready" (CameraStatusRow
 * → [openCamera]).
 */
@Composable
fun SetupStep(guided: GuidedController, openCamera: () -> Unit) {
    val mode = guided.mode
    val brand = guided.tv.brand
    val brandName = if (brand == Brand.OTHER) "TV" else brand.label
    val isDemo = guided.tv.isDemo

    val formatText = when (mode) {
        SignalMode.SDR -> "Start with everyday TV. Each format keeps its own settings on the TV."
        SignalMode.HDR10 -> "The TV has separate settings for HDR, so it’s calibrated after SDR."
        SignalMode.HDR10P, SignalMode.DV ->
            "Advanced: do SDR and HDR10 first. ${mode.label} has its own settings on the TV."
    }

    val pages = buildList {
        add(
            WalkPage(
                symbol = Symbols.stack, tint = mode.tint, title = "Calibrating ${mode.label}",
                text = formatText, art = { FormatOrderArt(current = mode) },
            ),
        )
        add(
            WalkPage(
                symbol = if (mode.onYouTube) Symbols.playCircle else Symbols.externalDrive, tint = mode.tint,
                title = if (mode.onYouTube) "The ${mode.label} videos are on YouTube" else "Download the ${mode.label} patterns",
                text = "",
                art = { GuidedSourceHero(mode) },
                extra = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        GuidedNoticeCard(
                            title = if (mode.onYouTube) "Only play videos whose title starts with ‘${mode.packPrefix}’"
                            else "Only play files whose name starts with ‘${mode.packPrefix}’",
                            titleColour = Theme.accent,
                            text = (if (mode == SignalMode.SDR) "The TV should show no HDR badge. "
                            else "The TV should show its ${mode.badge} badge. ") +
                                "You start each video with the TV's remote.",
                        )
                        PatternLinkButton(mode)
                    }
                },
            ),
        )
        add(
            WalkPage(
                symbol = null, tint = Theme.accent,
                title = if (isDemo) "On a real TV: picture settings" else "Set up the $brandName",
                text = "On the TV, open Picture and match these.",
                extra = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        GuidedSettingsList(brand.setup(mode))
                        Text(
                            "Menu names may vary a little by $brandName model.",
                            style = Fonts.captionBrand.secondary(),
                        )
                    }
                },
            ),
        )
        add(
            WalkPage(
                symbol = Symbols.moon, title = "Dim the room",
                text = "", art = { DarkRoomArt() },
                extra = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "Lights off, curtains closed, and the TV on for 20–30 minutes first: screens shift as they warm up.",
                            style = Fonts.bodyBrand.secondary().lineSpacing(4f),
                        )
                    }
                },
            ),
        )
        add(
            WalkPage(
                symbol = Symbols.phone, title = "Place the phone",
                text = "Keep the yellow box inside the grey patch, and don’t move the phone once you start.",
                art = { PlacementArt() },
                lead = "A hand's length (15–30 cm) from the TV, facing its middle, not touching the screen.",
            ),
        )
        if (!isDemo) {
            add(
                WalkPage(
                    symbol = null, title = "Get the camera ready",
                    text = "",
                    extra = {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            CameraStatusRow(
                                Modifier.clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    role = Role.Button,
                                    onClick = openCamera,
                                ),
                            )
                            Text(
                                "Next, the app measures the TV as it is, for your before and after. About two minutes.",
                                style = Fonts.captionBrand.secondary(),
                            )
                        }
                    },
                ),
            )
        }
    }

    key(mode) {
        Walkthrough(
            pages = pages,
            finishTitle = "Start: measure the TV as it is",
            section = "Getting ready",
        ) {
            guided.go(GuideStep.BEFORE, markDone = GuideStep.SETUP)
        }
    }
}

/** The big "where the videos come from" instruction on the teal panel. */
@Composable
private fun GuidedSourceHero(mode: SignalMode) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.Start,
    ) {
        Icon(
            if (mode.onYouTube) Symbols.playCircle else Symbols.externalDrive,
            contentDescription = null,
            tint = Theme.tealBright,
            modifier = Modifier.size(36.dp),
        )
        BasicText(
            if (mode.onYouTube) "Play the videos in your TV’s YouTube app, or cast from your phone."
            else "Copy the ${mode.packFolder} folder to a USB stick and play the files with the TV’s media player.",
            style = Fonts.brand(20f, FontWeight.SemiBold).white(),
            // Swift minimumScaleFactor(0.8).
            autoSize = TextAutoSize.StepBased(minFontSize = 16.sp, maxFontSize = 20.sp, stepSize = 0.5.sp),
        )
    }
}

/** A card with a coloured title and a line of secondary text. */
@Composable
private fun GuidedNoticeCard(title: String, titleColour: Color, text: String, big: Boolean = false) {
    Card(spacing = 12.dp) {
        Text(title, style = if (big) Fonts.section else Fonts.bodyStrong, color = titleColour)
        Text(text, style = (if (big) Fonts.bodyBrand else Fonts.captionBrand).secondary())
    }
}

/** One TV setting: "Picture mode" over "Filmmaker Mode". */
private data class GuidedSettingRow(val label: String?, val value: String)

/** The TV settings to make, ticked ("Name: value" split as label over value; two-setting items split on ", "). */
@Composable
private fun GuidedSettingsList(items: List<String>) {
    val rows = remember(items) {
        val out = mutableListOf<GuidedSettingRow>()
        for (item in items) {
            // "Gamma: BT.1886, Colour Temperature: Warm 50" holds two settings.
            val parts = if (item.split(": ").size > 2) item.split(", ") else listOf(item)
            for (part in parts) {
                val r = part.indexOf(": ")
                if (r >= 0) {
                    out.add(GuidedSettingRow(part.substring(0, r), part.substring(r + 2)))
                } else {
                    out.add(GuidedSettingRow(null, part))
                }
            }
        }
        out.toList()
    }
    Card(spacing = 0.dp) {
        rows.forEach { row ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp)
                    .semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    Symbols.checkmarkSquare,
                    contentDescription = null,
                    tint = Theme.tealBright,
                    modifier = Modifier
                        .padding(top = if (row.label == null) 0.dp else 8.dp)
                        .size(20.dp),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (row.label != null) {
                        Text(row.label, style = Fonts.small.secondary())
                    }
                    Text(
                        row.value,
                        style = if (row.label == null) Fonts.bodyBrand else Fonts.bodyStrong,
                        color = Color.White,
                    )
                }
            }
        }
    }
}

/** Opens this format's YouTube playlist ("Open the SDR playlist") or its download; nothing without a link. */
@Composable
fun PatternLinkButton(mode: SignalMode, modifier: Modifier = Modifier) {
    val app = LocalApp.current
    val context = LocalContext.current
    val links = app.links.links
    if (mode.onYouTube) {
        val url = links.playlist(mode) ?: return
        SecondaryButton(
            "Open the ${mode.label} playlist",
            onClick = { openUrl(context, url) },
            modifier = modifier,
            icon = Symbols.playRectangle,
        )
    } else {
        val url = links.download(mode) ?: return
        SecondaryButton(
            "Download the ${mode.label} patterns",
            onClick = { openUrl(context, url) },
            modifier = modifier,
            icon = Symbols.download,
        )
    }
}

/** Where this mode's pattern videos come from: YouTube (SDR, HDR10) or a download (HDR10+, DV). */
@Composable
fun PatternSourceCard(mode: SignalMode, modifier: Modifier = Modifier) {
    Card(modifier, spacing = 12.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            ModeBadge(mode)
            Text(
                if (mode.onYouTube) "Patterns on YouTube" else "Pattern files to download",
                style = Fonts.headlineBrand,
                color = Color.White,
            )
        }
        Text(
            if (mode.onYouTube) {
                "Play the ${mode.label} patterns from the TV Calibrator playlist on the Slidebean channel, in the TV’s YouTube app or cast from your phone, at the highest quality."
            } else {
                "Copy the ${mode.packFolder} folder to a USB stick and play the files with the TV’s media player."
            },
            style = Fonts.captionBrand.secondary(),
        )
        PatternLinkButton(mode)
    }
}

// MARK: - Measuring a quick-check video (Before, Check, After)

/** Ready to measure: the title, PlayFileCard(fromStart), OrderStrip. Declares PinnedAction("Measure (about two minutes)"). */
@Composable
private fun GuidedQuickReady(title: String, file: String, mode: SignalMode, measure: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = Fonts.stepTitleStyle)
        PlayFileCard(file = file, mode = mode, fromStart = true)
        OrderStrip(listOf("Tap Measure", "Start the video on the TV"))
        Text(
            if (mode.onYouTube) "Keep the phone still. Ads are fine: the app waits for the pattern."
            else "Keep the phone still.",
            style = Fonts.captionBrand.secondary(),
        )
    }
    PinnedAction("Measure (about two minutes)", action = measure)
}

/** The measurement failed ("Is the SDR video playing?"). Declares PinnedAction("I’ll replay the SDR video") → [replay]. */
@Composable
private fun GuidedWrongVideo(file: String, mode: SignalMode, why: String, replay: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Is the ${mode.label} video playing?", style = Fonts.stepTitleStyle)
        Card(spacing = 12.dp) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clearAndSetSemantics { contentDescription = "${mode.label} expected. Nothing was saved." },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ModeBadge(mode)
                Icon(
                    Symbols.arrowRight,
                    contentDescription = null,
                    tint = Theme.textSecondary,
                    modifier = Modifier.size(17.dp),
                )
                Text(
                    "?",
                    style = Fonts.badge,
                    color = Theme.limit,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Theme.limit.copy(alpha = 0.13f))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
                Spacer(Modifier.weight(1f))
                Text("Nothing was saved", style = Fonts.small.secondary())
            }
            Text(
                "Replay the ${mode.label} video from the start, then measure again.",
                style = Fonts.bodyStrong,
                color = Color.White,
            )
            Text(why, style = Fonts.captionBrand.secondary())
        }
        PlayFileCard(file = file, mode = mode, fromStart = true)
    }
    PinnedAction("I’ll replay the ${mode.label} video", action = replay)
}

/** The runner's failure text, if its last sweep failed. */
private fun failureOf(guided: GuidedController): String? =
    (guided.runner.phase as? SweepRunner.Phase.Failed)?.text

// MARK: - 2 + 7. Before / after

/**
 * Before or After ([which]): MeasuringCard while running; GuidedWrongVideo on failure (replay →
 * guided.runner.reset()); the results (Before: "Your starting point", GamutCheck note for real TVs,
 * ReportSummary; After: ResultsView(showsNewsletter, onOpenCharts → LocalNav.openResultsCharts(tv.id)),
 * NotificationsCard for real TVs, the limits note) with "Measure again"; else GuidedQuickReady. Results
 * declare PinnedAction "Next: fix the white balance" (go(BRIGHT, markDone = BEFORE)) / "Start a new session"
 * (newSession()). Measuring: guided.startQuick(which); remeasure = false when it ends Done/Cancelled; After
 * Done → app.chime.play(view). On appear a stale failure is reset.
 */
@Composable
fun QuickStep(guided: GuidedController, which: QuickWhich) {
    val app = LocalApp.current
    // Kept across Activity recreation. waitingFrom: guided.quickEnded when this step started measuring; the
    // results come back once the controller counts that measurement as ended Done or Cancelled.
    var remeasure by rememberSaveable { mutableStateOf(false) }
    var waitingFrom by rememberSaveable { mutableStateOf<Int?>(null) }
    val ended = guided.quickEnded
    LaunchedEffect(ended) {
        val from = waitingFrom
        // Back to the results when it worked (or was cancelled); a failure stays on screen.
        if (from != null && ended > from) {
            waitingFrom = null
            remeasure = false
        }
    }

    // The step's own result stays on screen when coming back to it (until a new session starts).
    val session = guided.lastSession
    val now: Measurements?
    val before: Measurements?
    if (session == null) {
        now = null; before = null
    } else if (which == QuickWhich.BEFORE) {
        now = session.before; before = null
    } else {
        now = session.after; before = if (session.after != null) session.before else null
    }
    val failure = failureOf(guided)

    fun measure() {
        remeasure = true
        waitingFrom = guided.quickEnded
        val job = guided.startQuick(which)
        if (which == QuickWhich.AFTER) {
            // The final results arrive with the success chime (Swift Chime.shared.play() after the await). No
            // View or composition state is captured: the haptic goes to whichever window is showing then.
            app.scope.launch {
                job.join()
                if (guided.runner.phase == SweepRunner.Phase.Done) app.chime.play()
            }
        }
    }

    LaunchedEffect(Unit) {
        // A failure from another step's measurement doesn't belong here.
        if (failureOf(guided) != null) guided.runner.reset()
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (guided.runner.running) {
            MeasuringCard(runner = guided.runner, file = guided.quickFile, mode = guided.mode)
        } else if (failure != null) {
            GuidedWrongVideo(file = guided.quickFile, mode = guided.mode, why = failure) {
                guided.runner.reset()
            }
        } else if (now != null && !remeasure) {
            QuickResults(guided, which, now, before, measureAgain = { remeasure = true })
        } else {
            GuidedQuickReady(
                title = if (which == QuickWhich.BEFORE) "Measure your starting point" else "One final check",
                file = guided.quickFile,
                mode = guided.mode,
            ) { measure() }
        }
    }
}

/** QuickStep's results (Swift QuickStep.results(now:before:)). */
@Composable
private fun QuickResults(
    guided: GuidedController,
    which: QuickWhich,
    now: Measurements,
    before: Measurements?,
    measureAgain: () -> Unit,
) {
    val nav = LocalNav.current
    val hdr = guided.hdr
    val tv = guided.tv
    val report = remember(now, hdr) { Analysis.analyse(now, hdr) }
    val limits = guided.progress.limits
        .filter { it in Patterns.colourIDs }
        .map { capitalized(it) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (which == QuickWhich.BEFORE) {
            Text("Your starting point", style = Fonts.stepTitleStyle)
            if (!tv.isDemo) {
                val note = GamutCheck.note(report, guided.mode, tv.brand)
                if (note != null) TipRow(TipTone.LIMIT, note)
            }
            ReportSummary(
                report = report,
                hdr = hdr,
                before = null,
                margin = if (tv.isDemo) null else tv.panel,
            )
        } else {
            val beforeReport = remember(before, hdr) { before?.let { Analysis.analyse(it, hdr) } }
            val finished = guided.lastSession?.finished
            val fallbackDate = remember { Instant.now() }
            ResultsView(
                report = report,
                hdr = hdr,
                mode = guided.mode,
                tvName = tv.name,
                onOpenCharts = { nav.openResultsCharts(tv.id) },
                before = beforeReport,
                date = finished ?: fallbackDate,
                margin = if (tv.isDemo) null else tv.panel,
                showsNewsletter = true,
            )
            if (!tv.isDemo) {
                NotificationsCard(tvID = tv.id.upper, tvName = tv.name)
            }
            if (limits.isNotEmpty()) {
                TipRow(
                    TipTone.LIMIT,
                    "${limits.joinToString(", ")}: as close as this TV gets (its controls were at their limit).",
                )
            }
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            LinkButton("Measure again", onClick = measureAgain)
        }
    }
    PinnedAction(if (which == QuickWhich.BEFORE) "Next: fix the white balance" else "Start a new session") {
        if (which == QuickWhich.BEFORE) {
            guided.go(GuideStep.BRIGHT, markDone = GuideStep.BEFORE)
        } else {
            guided.newSession()
        }
    }
}

/** Swift String.capitalized for the colour ids: "MAGENTA" -> "Magenta". */
private fun capitalized(s: String): String =
    s.split(" ").joinToString(" ") { w ->
        w.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
    }

/**
 * Shown when a calibration is finished: "UNTANGLED BY SLIDEBEAN", "Access our tools before anyone else",
 * "Subscribe to Untangled" (PrimaryButton → openUrl(newsletterURL), and `untangled.opened` = true), the
 * dimmed BrandChart in the corner. Hidden for good once opened (prefs.boolState(PrefKeys.UNTANGLED_OPENED)).
 */
@Composable
fun NewsletterCard(modifier: Modifier = Modifier) {
    val app = LocalApp.current
    val context = LocalContext.current
    var opened by app.prefs.boolState(PrefKeys.UNTANGLED_OPENED, false)
    val url = app.links.links.newsletterURL
    if (opened || url == null) return
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Theme.accent.copy(alpha = 0.08f))
            .border(1.5.dp, Theme.accent, shape),
    ) {
        // The app icon's colour chart, dimmed, in the top right corner (behind, not sizing the card).
        Box(Modifier.matchParentSize()) {
            Image(
                painterResource(R.drawable.brand_chart),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 46.dp, y = (-40).dp)
                    .wrapContentSize(Alignment.TopEnd, unbounded = true)
                    .size(200.dp)
                    .alpha(0.5f),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Symbols.envelopeFill, contentDescription = null, tint = Theme.accent, modifier = Modifier.size(15.dp))
                Text("UNTANGLED BY SLIDEBEAN", style = Fonts.badge.tracking(0.8f), color = Theme.accent)
            }
            Text(
                "Access our tools before anyone else",
                style = Fonts.section,
                color = Color.White,
                modifier = Modifier.widthIn(max = 230.dp),
            )
            PrimaryButton(
                "Subscribe to Untangled",
                onClick = {
                    openUrl(context, url)
                    opened = true
                },
            )
        }
    }
}

// MARK: - 5. Check the greyscale

/** CheckStep's static helpers. */
object CheckStep {
    /** |redVsGreen| and |blueVsGreen| <= 0.015. */
    fun onTarget(g: GreyResult): Boolean = abs(g.redVsGreen) <= 0.015 && abs(g.blueVsGreen) <= 0.015

    /** The headline from the average grey dE: <1 "On target", <2 "A very good result", <3 "A good result". */
    fun verdict(average: Double): String {
        if (!average.isFinite()) return "Here are your greys"
        if (average < 1) return "On target"
        if (average < 2) return "A very good result"
        if (average < 3) return "A good result"
        return "Some greys need another pass"
    }

    /** "Every grey is on target." / "Most greys are on target. The dark end could be a little closer." ... */
    fun tip(off: List<GreyResult>, count: Int): String {
        if (off.isEmpty()) return "Every grey is on target."
        val ends = mutableListOf<String>()
        if (off.any { it.level <= 0.4 }) ends.add("dark end")
        if (off.any { it.level > 0.4 && it.level < 0.55 }) ends.add("middle greys")
        if (off.any { it.level >= 0.55 }) ends.add("bright end")
        val which = ends.joinToString(" and the ")
        if (off.size * 2 <= count) {
            return "Most greys are on target. The $which could be a little closer."
        }
        return "The $which could be closer: redo it below, or fine-tune."
    }
}

/**
 * Check the greys: MeasuringCard / GuidedWrongVideo / the summary (verdict + dE, GreyBalanceChart card, tip,
 * the grid Grey · Red · Blue · dE · Redo bright end / Redo dark end / Fine-tune, "Measure again", the
 * fine-tune link, ScoreInfo; PinnedAction "Continue to colours" → go(COLOURS, markDone = CHECK)) / else
 * GuidedQuickReady("Check the greys") with the fine-tune and "Next: colours" links.
 */
@Composable
fun CheckStep(guided: GuidedController) {
    val failure = failureOf(guided)
    val tv = guided.tv
    // Fine-tuning needs a TV with 10- or 20-point white balance.
    val canFineTune = !(tv.multipointKnown && tv.multipoint == Multipoint.NONE)
    val fineTuneTitle = if (tv.multipoint != Multipoint.NONE && !guided.finderReady && !guided.finderSkipped) {
        "Find my TV’s points"
    } else {
        "Fine-tune a grey (optional)"
    }
    val measure: () -> Unit = { guided.startQuick(QuickWhich.CHECK) }

    LaunchedEffect(Unit) {
        // A failure from another step's measurement doesn't belong here.
        if (failureOf(guided) != null) guided.runner.reset()
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val rep = guided.checkReport
        if (guided.runner.running) {
            MeasuringCard(runner = guided.runner, file = guided.quickFile, mode = guided.mode)
        } else if (failure != null) {
            GuidedWrongVideo(file = guided.quickFile, mode = guided.mode, why = failure) {
                guided.runner.reset()
            }
        } else if (rep != null) {
            CheckSummary(guided, rep, canFineTune, fineTuneTitle, measure)
        } else {
            GuidedQuickReady(title = "Check the greys", file = guided.quickFile, mode = guided.mode, measure = measure)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (canFineTune) {
                    LinkButton(fineTuneTitle, onClick = { guided.go(GuideStep.FINE) }, style = Fonts.captionBrand)
                }
                Spacer(Modifier.width(8.dp))
                Spacer(Modifier.weight(1f))
                LinkButton(
                    "Next: colours",
                    onClick = { guided.go(GuideStep.COLOURS, markDone = GuideStep.CHECK) },
                    style = Fonts.captionBrand,
                )
            }
        }
    }
}

/** CheckStep's summary (Swift CheckStep.summary(_:)). */
@Composable
private fun CheckSummary(
    guided: GuidedController,
    rep: Report,
    canFineTune: Boolean,
    fineTuneTitle: String,
    measure: () -> Unit,
) {
    val hdr = guided.hdr
    val greys = rep.greys.filter { hdr || it.level >= 0.099 }
    val off = greys.filter { !CheckStep.onTarget(it) }
    val average = Analysis.greyAverage(rep, hdr)
    val cell = Fonts.captionBrand.monospacedDigit().white()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                CheckStep.verdict(average),
                style = Fonts.stepTitleStyle,
                modifier = Modifier.weight(1f).alignByBaseline(),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "dE ${fmt(average, 1)}",
                style = Fonts.captionStrong.monospacedDigit(),
                color = ScoreTile.tint(average),
                modifier = Modifier.alignByBaseline(),
            )
        }
        Card {
            GreyBalanceChart(report = rep, hdr = hdr)
        }
        TipRow(if (off.isEmpty()) TipTone.OK else TipTone.ACTION, CheckStep.tip(off, greys.size))
        Card(spacing = 0.dp) {
            GridTable(columns = 5, hSpacing = 6.dp) {
                GridHeading("Grey")
                GridHeading("Red")
                GridHeading("Blue")
                GridHeading("dE")
                GridHeading("")
                greys.forEach { g ->
                    key(g.id) {
                        Box(Modifier.widthIn(min = 43.dp).heightIn(min = 42.dp), contentAlignment = Alignment.CenterStart) {
                            Text(shortName(g.name), style = cell)
                        }
                        Text(typographicMinus(signed(g.redVsGreen * 100)), style = cell, modifier = Modifier.widthIn(min = 48.dp))
                        Text(typographicMinus(signed(g.blueVsGreen * 100)), style = cell, modifier = Modifier.widthIn(min = 48.dp))
                        Text(fmt(g.de, 1), style = cell, modifier = Modifier.widthIn(min = 40.dp))
                        GreyAction(guided, g)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            LinkButton("Measure again", onClick = measure, style = Fonts.captionBrand)
            Spacer(Modifier.width(8.dp))
            Spacer(Modifier.weight(1f))
            if (canFineTune) {
                LinkButton(fineTuneTitle, onClick = { guided.go(GuideStep.FINE) }, style = Fonts.captionBrand)
            }
        }
        ScoreInfo(margin = if (guided.tv.isDemo) null else guided.tv.panel)
    }
    PinnedAction("Continue to colours") { guided.go(GuideStep.COLOURS, markDone = GuideStep.CHECK) }
}

@Composable
private fun GridHeading(text: String) {
    Text(text, style = Fonts.tiny.secondary(), modifier = Modifier.padding(bottom = 4.dp))
}

/** The grid's last column: ✓ on target, else the step that fixes this grey. */
@Composable
private fun GreyAction(guided: GuidedController, g: GreyResult) {
    if (CheckStep.onTarget(g)) {
        Text(
            "✓",
            style = Fonts.tiny,
            color = Theme.tealBright,
            modifier = Modifier.clearAndSetSemantics { contentDescription = "On target" },
        )
    } else {
        val target = if (g.level >= 0.55) GuideStep.BRIGHT else if (g.level <= 0.4) GuideStep.DARK else GuideStep.FINE
        LinkButton(
            when (target) {
                GuideStep.BRIGHT -> "Redo bright end"
                GuideStep.DARK -> "Redo dark end"
                else -> "Fine-tune"
            },
            onClick = { guided.go(target) },
            style = Fonts.tiny,
        )
    }
}

/**
 * Swift Grid(alignment: .leading, horizontalSpacing:, verticalSpacing: 0): cells in row-major order, each
 * column as wide as its widest cell, each row as tall as its tallest; cells leading, centred vertically.
 */
@Composable
private fun GridTable(columns: Int, hSpacing: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val loose = Constraints(maxWidth = constraints.maxWidth)
        val placeables = measurables.map { it.measure(loose) }
        val rows = placeables.chunked(columns)
        val widths = IntArray(columns)
        rows.forEach { r -> r.forEachIndexed { i, p -> widths[i] = max(widths[i], p.width) } }
        val heights = rows.map { r -> r.maxOf { it.height } }
        val gap = hSpacing.roundToPx()
        val totalW = widths.sum() + gap * (columns - 1)
        val totalH = heights.sum()
        layout(
            totalW.coerceIn(constraints.minWidth, constraints.maxWidth),
            totalH.coerceIn(constraints.minHeight, constraints.maxHeight),
        ) {
            var y = 0
            rows.forEachIndexed { ri, r ->
                var x = 0
                r.forEachIndexed { i, p ->
                    p.placeRelative(x, y + (heights[ri] - p.height) / 2)
                    x += widths[i] + gap
                }
                y += heights[ri]
            }
        }
    }
}

/** "50% GREY" -> "50%", "PQ 50% GREY (92 nits)" -> "50% (92 nits)", "100% WHITE" -> "100%", "100 nit WHITE" -> "100 nits". */
fun shortName(name: String): String =
    name.replace("PQ ", "").replace(" GREY", "")
        .replace("100% WHITE", "100%").replace("100 nit WHITE", "100 nits")
