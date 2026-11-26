package tvcal.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tvcal.android.BuildConfig
import tvcal.android.LocalApp
import tvcal.android.app.PrefKeys
import tvcal.android.measure.GuidedController
import tvcal.android.profiles.DeviceModel
import tvcal.android.storage.GuideStep
import tvcal.android.storage.TVRecord
import tvcal.android.ui.components.BottomAction
import tvcal.android.ui.components.CalBottomSheet
import tvcal.android.ui.components.Card
import tvcal.android.ui.components.LinkButton
import tvcal.android.ui.components.ModeBadge
import tvcal.android.ui.components.NavBar
import tvcal.android.ui.components.NavLeading
import tvcal.android.ui.components.PlacementArt
import tvcal.android.ui.components.PrimaryButton
import tvcal.android.ui.components.ProgressTrack
import tvcal.android.ui.components.ReportHero
import tvcal.android.ui.components.ReportPanel
import tvcal.android.ui.components.ScreenColumn
import tvcal.android.ui.components.ScreenScaffold
import tvcal.android.ui.components.SecondaryButton
import tvcal.android.ui.components.ShrinkToFitText
import tvcal.android.ui.components.WalkArtPanel
import tvcal.android.ui.components.shareDir
import tvcal.android.ui.components.shareFile
import tvcal.android.ui.theme.Fonts
import tvcal.android.ui.theme.Symbols
import tvcal.android.ui.theme.Theme
import tvcal.android.ui.theme.blurb
import tvcal.android.ui.theme.dateAbbreviated
import tvcal.android.ui.theme.isAdvanced
import tvcal.android.ui.theme.lineSpacing
import tvcal.android.ui.theme.secondary
import tvcal.android.ui.theme.tint
import tvcal.android.ui.theme.tracking
import tvcal.kit.Analysis
import tvcal.kit.Report
import tvcal.kit.SignalMode
import java.time.Instant

// Port of Views/TVHomeView.swift: the screen home, its report card, the format selector, the advanced
// format sheet and the step navigator.

/** Swift `latest`: the latest finished calibration (or else the latest starting point) for a format. */
private data class LatestReport(val report: Report, val before: Report?, val date: Instant?, val finished: Boolean)

/**
 * The screen home (Figma 06 Screen home). [guided] is the TV's shared controller (ui/nav/GuidedHolder);
 * its mode starts as the saved "mode-<UUID>" and changes go to prefs + guided.reload(tv, m) (also reload on
 * a changed [tv]). Content: ScreenReportCard (→ guided.go(AFTER or BEFORE) then [onOpenSteps]) or
 * WalkArtPanel(150) { PlacementArt }, ModeSelector (HDR10+/DV first ask AdvancedFormatSheet unless
 * "advanced-ok-<UUID>"), StepBar(onTap → StepNavigatorSheet) while 0 < done < total-1, the demo note.
 * NavBar title tv.name, back → [onBack]; the ⋯ menu (DropdownMenu): Screen settings (TVSetupFlow(tv)),
 * Export history (app.store.export(...) into shareDir, then shareFile "application/json"), Delete this screen
 * (AlertDialog "Delete <name>?" → guided.stopLive(); onBack(); then app.model.delete(tv) 0.5 s later in
 * app.scope). BottomAction: "Start with X" / "Continue X" / "View X results" → [onOpenSteps].
 * Leaving to the home screen is handled by GuidedHolder.onCleared (cancels the runner and live readings).
 */
@Composable
fun TVHomeView(
    tv: TVRecord,
    guided: GuidedController,
    onOpenSteps: () -> Unit,
    onBack: () -> Unit,
) {
    val app = LocalApp.current
    val context = LocalContext.current
    // The controller holds the format (it outlives this composable while the steps are open); it starts
    // as the saved "mode-<UUID>" (GuidedHolder).
    val mode = guided.mode
    // The sheet flags survive Activity recreation (SignalMode is an enum, so Bundle-saveable).
    var editing by rememberSaveable { mutableStateOf(false) }
    var askAdvanced by rememberSaveable { mutableStateOf<SignalMode?>(null) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    var navigating by rememberSaveable { mutableStateOf(false) }
    var openAfterNavigating by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    fun setMode(m: SignalMode) {
        if (m == guided.mode) return
        app.prefs.setString(PrefKeys.mode(tv.id), m.rawValue)
        guided.reload(tv, m)
    }

    // Swift .onChange(of: tv): the TV's details changed (Screen settings, a menu question).
    LaunchedEffect(tv) {
        if (guided.tv != tv) guided.reload(tv, guided.mode)
    }

    val total = GuideStep.stepper.size
    val done = guided.state.progress.done.size
    val calibrated = done >= total - 1
    val state = guided.state
    val latest = remember(state.sessions, mode) {
        val hdr = mode.isHDR
        val sessions = state.sessions
        val a = sessions.lastOrNull { it.after != null }
        val b = sessions.lastOrNull { it.before != null }
        if (a?.after != null) {
            LatestReport(Analysis.analyse(a.after, hdr), a.before?.let { Analysis.analyse(it, hdr) }, a.finished, true)
        } else if (b?.before != null) {
            LatestReport(Analysis.analyse(b.before, hdr), null, b.started, false)
        } else {
            null
        }
    }
    val mainTitle = if (done == 0) "Start with ${mode.label}"
    else if (calibrated) "View ${mode.label} results" else "Continue ${mode.label}"

    fun choose(m: SignalMode) {
        if (!m.isAdvanced || app.prefs.bool(PrefKeys.advancedOk(tv.id), false)) setMode(m) else askAdvanced = m
    }

    ScreenScaffold(
        title = tv.name,
        onLeading = onBack,
        actions = {
            // The screen's menu, in the navigation bar: its settings, its history, delete.
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Symbols.ellipsis, contentDescription = "Screen options", tint = Theme.tealBright)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Screen settings") },
                        leadingIcon = { Icon(Symbols.pencil, contentDescription = null) },
                        onClick = {
                            menu = false
                            editing = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Export history") },
                        leadingIcon = { Icon(Symbols.share, contentDescription = null) },
                        onClick = {
                            menu = false
                            val record = tv
                            val profile = app.profiles.profile
                            val dir = shareDir(context)
                            app.scope.launch {
                                val file = withContext(Dispatchers.IO) {
                                    app.model.store.export(record, profile, BuildConfig.VERSION_NAME, DeviceModel.identifier, dir)
                                }
                                if (file != null) shareFile(context, file, "application/json")
                            }
                        },
                    )
                    HorizontalDivider(color = Theme.hairline)
                    DropdownMenuItem(
                        text = { Text("Delete this screen", color = Theme.red) },
                        leadingIcon = { Icon(Symbols.trash, contentDescription = null, tint = Theme.red) },
                        onClick = {
                            menu = false
                            deleting = true
                        },
                    )
                }
            }
        },
        bottomBar = {
            BottomAction { PrimaryButton(mainTitle, onClick = onOpenSteps) }
        },
    ) {
        ScreenColumn(spacing = 12.dp) {
            // The report first, then the formats.
            if (latest != null) {
                ScreenReportCard(
                    report = latest.report,
                    before = latest.before,
                    hdr = mode.isHDR,
                    mode = mode,
                    date = latest.date,
                    finished = latest.finished,
                    margin = if (tv.isDemo) null else app.profiles.marginDE(tv.panel),
                ) {
                    guided.go(if (latest.finished) GuideStep.AFTER else GuideStep.BEFORE)
                    onOpenSteps()
                }
            } else {
                // Nothing measured yet: the picture of what's about to happen.
                WalkArtPanel(height = 150.dp) { PlacementArt(Modifier.fillMaxSize()) }
            }
            ModeSelector(tv = tv, mode = mode, choose = { m -> choose(m) }, refreshKey = state)
            if (done > 0 && !calibrated) {
                // The bar opens "Jump to a step" (the step navigator).
                StepBar(guided, Modifier.padding(top = 4.dp), onTap = { navigating = true })
            }
            if (tv.isDemo) DemoNote()
        }
    }

    if (editing) {
        TVSetupFlow(tv = tv, onDismiss = { editing = false })
    }
    askAdvanced?.let { m ->
        AdvancedFormatSheet(
            mode = m,
            confirm = {
                app.prefs.setBool(PrefKeys.advancedOk(tv.id), true)
                setMode(m)
            },
            onDismiss = { askAdvanced = null },
        )
    }
    if (navigating) {
        StepNavigatorSheet(
            guided = guided,
            screenName = tv.name,
            mode = mode,
            open = { openAfterNavigating = true },
            onDismiss = {
                navigating = false
                // Push the step screen once the sheet has gone.
                if (openAfterNavigating) {
                    openAfterNavigating = false
                    onOpenSteps()
                }
            },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete ${tv.name}?", style = Fonts.headlineBrand, color = Color.White) },
            text = {
                Text(
                    "Its measurements and calibration progress are removed from this phone.",
                    style = Fonts.bodyBrand.secondary(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    val doomed = tv
                    guided.stopLive()
                    onBack()
                    // Remove it once this screen has left the navigation stack.
                    app.scope.launch {
                        delay(500)
                        app.model.delete(doomed)
                    }
                }) { Text("Delete the screen and its history", style = Fonts.bodyStrong, color = Theme.red) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = false }) {
                    Text("Cancel", style = Fonts.bodyBrand, color = Theme.tealBright)
                }
            },
            containerColor = Theme.surface,
        )
    }
}

@Composable
private fun DemoNote() {
    Row(
        Modifier.padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Symbols.sparkles, contentDescription = null, tint = Theme.tealBright, modifier = Modifier.size(17.dp))
        Text(
            "Demo: simulated readings. The on-screen buttons stand in for the TV's remote.",
            style = Fonts.captionBrand.secondary(),
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The latest results first on the screen home: ReportPanel { "CALIBRATED"/"STARTING POINT", ModeBadge, the
 * date; ReportHero; "Full results ›" / "See the starting point ›" → [open] }.
 */
@Composable
private fun ScreenReportCard(
    report: Report,
    before: Report?,
    hdr: Boolean,
    mode: SignalMode,
    date: Instant?,
    finished: Boolean,
    margin: Double?,
    open: () -> Unit,
) {
    ReportPanel {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (finished) "CALIBRATED" else "STARTING POINT",
                style = Fonts.badge.tracking(0.8f),
                color = if (finished) Theme.green else Theme.accent,
                maxLines = 1,
            )
            ModeBadge(mode)
            Spacer(Modifier.weight(1f))
            if (date != null) {
                Text(dateAbbreviated(date), style = Fonts.small.secondary(), maxLines = 1)
            }
        }
        ReportHero(report = report, hdr = hdr, before = before, margin = margin)
        LinkButton(
            if (finished) "Full results" else "See the starting point",
            onClick = open,
            style = Fonts.captionStrong,
            trailingIcon = Symbols.chevronRight,
        )
    }
}

/**
 * Which format to calibrate: SDR and HDR10 as format cards side by side (badge, status mark, "Not started" /
 * "n of 7 steps" / "Calibrated", blurb, ProgressTrack), then the "Advanced · USB files" card with HDR10+ and
 * DV chips. [choose] is called with the tapped mode.
 */
@Composable
fun ModeSelector(
    tv: TVRecord,
    mode: SignalMode,
    choose: (SignalMode) -> Unit,
    modifier: Modifier = Modifier,
    /** Re-read the guide files when this changes (the screen home passes its guide state). */
    refreshKey: Any? = null,
) {
    val app = LocalApp.current
    // Each format's guide file is read once per TV, mode and guide state, not on every recomposition.
    val doneCounts = remember(tv, mode, refreshKey) {
        SignalMode.entries.associateWith { app.model.store.guideState(tv, it).progress.done.size }
    }
    fun stepsDone(m: SignalMode): Int = doneCounts.getValue(m)

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            for (m in listOf(SignalMode.SDR, SignalMode.HDR10)) {
                FormatCard(m, selected = m == mode, done = stepsDone(m), onClick = { choose(m) }, modifier = Modifier.weight(1f))
            }
        }
        AdvancedFormats(mode, choose)
    }
}

/** A format tile: its badge and a status mark, how far it has got, a short line, and its progress. */
@Composable
private fun FormatCard(m: SignalMode, selected: Boolean, done: Int, onClick: () -> Unit, modifier: Modifier) {
    val total = GuideStep.stepper.size
    val calibrated = done >= total - 1
    val progress = if (calibrated) 1.0 else minOf(done, total).toDouble() / maxOf(total, 1).toDouble()
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .clip(shape)
            .background(Theme.card)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) m.tint.copy(alpha = 0.7f) else Theme.hairline, shape)
            .selectable(selected = selected, onClick = onClick, role = Role.Tab)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ModeBadge(m)
            Spacer(Modifier.weight(1f))
            Icon(
                if (calibrated) Symbols.checkmarkCircleFill else if (selected) Symbols.circleSelected else Symbols.circle,
                contentDescription = null,
                tint = if (calibrated) Theme.green else if (selected) m.tint else Theme.textSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
        // Swift .lineLimit(1).minimumScaleFactor(0.8).
        ShrinkToFitText(
            if (done == 0) "Not started" else if (calibrated) "Calibrated" else "$done of $total steps",
            style = Fonts.bodyStrong,
            color = if (calibrated) Theme.green else Color.White,
            minScale = 0.8f,
        )
        Text(m.blurb, style = Fonts.small.secondary(), maxLines = 2, overflow = TextOverflow.Ellipsis)
        ProgressTrack(progress, colour = if (calibrated) Theme.green else m.tint)
    }
}

/** HDR10+ and Dolby Vision: USB files, after SDR and HDR10. */
@Composable
private fun AdvancedFormats(mode: SignalMode, choose: (SignalMode) -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(shape)
            .background(Theme.card)
            .border(
                if (mode.isAdvanced) 1.5.dp else 1.dp,
                if (mode.isAdvanced) mode.tint.copy(alpha = 0.7f) else Theme.hairline,
                shape,
            )
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Advanced", style = Fonts.captionStrong, color = Color.White, maxLines = 1)
        Text("USB files", style = Fonts.small.secondary(), maxLines = 1)
        Spacer(Modifier.weight(1f))
        for (m in listOf(SignalMode.HDR10P, SignalMode.DV)) {
            val chip = Modifier
                .clip(RoundedCornerShape(8.dp))
                .selectable(selected = m == mode, onClick = { choose(m) }, role = Role.Tab)
            if (m == mode) {
                // The format badge, filled in the format's colour when it's the one being calibrated.
                Text(
                    m.label,
                    style = Fonts.badge,
                    color = Theme.background,
                    maxLines = 1,
                    modifier = chip
                        .background(m.tint)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            } else {
                ModeBadge(m, chip)
            }
        }
    }
}

/**
 * Asked before an advanced format (HDR10+, DV) the first time on this screen (CalBottomSheet or
 * FullScreenSheet, "Advanced formats" with a close X): "<mode> needs a USB drive", the download → drive → TV
 * art, the text; BottomAction "Calibrate <mode> anyway" ([confirm] then [onDismiss]) and "Not now".
 */
@Composable
fun AdvancedFormatSheet(mode: SignalMode, confirm: () -> Unit, onDismiss: () -> Unit) {
    CalBottomSheet(onDismiss = onDismiss) {
        NavBar("Advanced formats", leading = NavLeading.CLOSE, onLeading = onDismiss)
        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ModeBadge(mode)
            Text("${mode.label} needs a USB drive", style = Fonts.stepTitleStyle)
            WalkArtPanel(height = 120.dp) {
                Row(
                    Modifier.align(Alignment.Center).clearAndSetSemantics { },
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Symbols.download, contentDescription = null, tint = Theme.tealBright, modifier = Modifier.size(38.dp))
                    Icon(Symbols.arrowRight, contentDescription = null, tint = Theme.textSecondary, modifier = Modifier.size(16.dp))
                    Icon(Symbols.externalDrive, contentDescription = null, tint = Theme.tealBright, modifier = Modifier.size(38.dp))
                    Icon(Symbols.arrowRight, contentDescription = null, tint = Theme.textSecondary, modifier = Modifier.size(16.dp))
                    Icon(Symbols.tv, contentDescription = null, tint = Theme.tealBright, modifier = Modifier.size(38.dp))
                }
            }
            Text(
                "YouTube can't play ${mode.label}: download the pattern files, copy them to a USB drive and play them on the TV. Do SDR and HDR10 first; this builds on those settings.",
                style = Fonts.bodyBrand.secondary().lineSpacing(4f),
            )
        }
        BottomAction {
            PrimaryButton("Calibrate ${mode.label} anyway", onClick = {
                confirm()
                onDismiss()
            })
            SecondaryButton("Not now", onClick = onDismiss)
        }
    }
}

/**
 * "Jump to a step" (CalBottomSheet with a close X): the screen name + ModeBadge, a Card with the seven
 * steps (✓/number, label, Current/Done/Upcoming; tap picks). BottomAction "Go to <step label lowercased>":
 * guided.go(target), then [open], then [onDismiss]. The screen home's [open] pushes the guided screen;
 * GuidedContextHeader passes {}.
 */
@Composable
fun StepNavigatorSheet(
    guided: GuidedController,
    screenName: String,
    mode: SignalMode,
    open: () -> Unit,
    onDismiss: () -> Unit,
) {
    var picked by remember { mutableStateOf<GuideStep?>(null) }
    /** The fine-tune step lives under Check in the bar. */
    val currentStep = if (guided.progress.step == GuideStep.FINE) GuideStep.CHECK else guided.progress.step
    val target = picked ?: currentStep

    CalBottomSheet(onDismiss = onDismiss) {
        NavBar("Jump to a step", leading = NavLeading.CLOSE, onLeading = onDismiss)
        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(screenName, style = Fonts.bodyBrand, color = Color.White, modifier = Modifier.weight(1f))
                ModeBadge(mode)
            }
            Card(spacing = 0.dp) {
                GuideStep.stepper.forEachIndexed { i, step ->
                    val done = step in guided.progress.done
                    val isCurrent = step == currentStep
                    val isTarget = step == target
                    val status = if (isCurrent) "Current" else if (done) "Done" else "Upcoming"
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(selected = isTarget, onClick = { picked = step }, role = Role.RadioButton)
                            .semantics(mergeDescendants = true) {},
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (done && !isTarget) "✓" else "${i + 1}",
                            style = Fonts.brand(16f),
                            color = if (isTarget) Theme.accent else if (done) Theme.tealBright else Theme.textSecondary,
                            modifier = Modifier.width(20.dp),
                        )
                        Text(
                            step.label,
                            style = if (isTarget) Fonts.bodyStrong else Fonts.bodyBrand,
                            color = if (isTarget) Theme.accent else Color.White,
                            modifier = Modifier.weight(1f),
                        )
                        Text(status, style = Fonts.small.secondary())
                    }
                }
            }
        }
        BottomAction {
            PrimaryButton("Go to ${target.label.lowercase()}", onClick = {
                guided.go(target)
                open()
                onDismiss()
            })
        }
    }
}
