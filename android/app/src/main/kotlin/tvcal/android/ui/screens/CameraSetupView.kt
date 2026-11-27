package tvcal.android.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tvcal.android.LocalApp
import tvcal.android.camera.Camera2Meter
import tvcal.android.camera.CameraMeter
import tvcal.android.camera.CameraPreview
import tvcal.android.camera.CameraXYZMeter
import tvcal.android.camera.rememberCameraStarter
import tvcal.android.measure.FreeMeter
import tvcal.android.profiles.ProfileService
import tvcal.android.ui.components.BalanceBar
import tvcal.android.ui.components.BottomAction
import tvcal.android.ui.components.Card
import tvcal.android.ui.components.Eyebrow
import tvcal.android.ui.components.GamutChart
import tvcal.android.ui.components.LinkButton
import tvcal.android.ui.components.NavLeading
import tvcal.android.ui.components.PrimaryButton
import tvcal.android.ui.components.ScreenColumn
import tvcal.android.ui.components.ScreenScaffold
import tvcal.android.ui.components.SecondaryButton
import tvcal.android.ui.components.TipRow
import tvcal.android.ui.components.TipTone
import tvcal.android.ui.components.WhiteTarget
import tvcal.android.ui.components.boldMarkdown
import tvcal.android.ui.components.shareText
import tvcal.android.ui.theme.Fonts
import tvcal.android.ui.theme.Symbols
import tvcal.android.ui.theme.Theme
import tvcal.android.ui.theme.fmt
import tvcal.android.ui.theme.monospacedDigit
import tvcal.android.ui.theme.secondary
import tvcal.android.ui.theme.signed
import tvcal.android.ui.theme.tracking
import tvcal.android.ui.theme.white
import tvcal.kit.LiveAnalysis

// Port of Views/CameraSetupView.swift (camera setup and the live meter) and CameraViewfinder from
// Camera/CameraXYZMeter.swift. The camera is Camera2Meter on phones with a RAW + manual-sensor back camera;
// elsewhere UnavailableCameraMeter, whose message (what the phone lacks) these screens show as a tip.

/** What a test reading gave (Swift CameraSetupView.TestOutcome). */
private sealed interface TestOutcome {
    data class Reading(val luminance: Double, val x: Double, val y: Double, val uneven: Boolean) : TestOutcome
    data class Failed(val why: String) : TestOutcome
}

/** Where the setup is: one main (yellow) action per stage. */
private enum class SetupStage { START, DARKNESS, PLACEMENT, READY }

/** Swift messageTip's (tone, text). */
private data class CamTip(val tone: TipTone, val text: String)

private class CamLast<T>(var value: T)

/** Swift `.onChange(of: value) { _, v in action(v) }`: runs on each change, not for the first value. */
@Composable
private fun <T> CamOnChange(value: T, action: (T) -> Unit) {
    val latest = rememberUpdatedState(action)
    val last = remember { CamLast(value) }
    SideEffect {
        if (last.value != value) {
            last.value = value
            latest.value(value)
        }
    }
}

/** Swift `String.capitalized` for the app's ids ("RED" → "Red"). */
private fun camCap(s: String): String =
    s.lowercase().split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.titlecase() } }

/** One line of text that shrinks to fit (Swift lineLimit(1) + minimumScaleFactor). */
@Composable
private fun CamScaledText(text: String, style: TextStyle, minScale: Float, modifier: Modifier = Modifier) {
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

/**
 * Getting the camera ready (Figma 05 Camera setup), pushed (CameraRoute, [leading] BACK) or in
 * CameraSetupSheet ([leading] CLOSE): NavBar "Set up the camera" (leading → [onDone]); the CameraViewfinder
 * (240 dp) + "Blurry on purpose…" pinned at the top; then the stage (start / darkness / placement / ready)
 * and the camera log. BottomAction: "Start the camera" (app.model.startCamera()) / "Measure the darkness
 * (again)" / "Test reading" / "Done" ([onDone]). camera.message tips per messageTip (quiet list,
 * "Measuring…" hidden, "…" suffix ACTION else LIMIT). Texts say "phone" for "iPhone".
 */
@Composable
fun CameraSetupView(
    camera: CameraMeter,
    profiles: ProfileService,
    onDone: () -> Unit,
    leading: NavLeading = NavLeading.BACK,
) {
    val app = LocalApp.current
    val context = LocalContext.current
    var test by remember { mutableStateOf<TestOutcome?>(null) }
    var showLog by remember { mutableStateOf(false) }
    // Measuring the darkness again (after a system update, say) while a measurement is already kept.
    var redoDark by remember { mutableStateOf(false) }

    val testPassed = (test as? TestOutcome.Reading)?.let { !it.uneven } ?: false
    val stage = when {
        !camera.running -> SetupStage.START
        camera.dark == null || redoDark -> SetupStage.DARKNESS
        testPassed -> SetupStage.READY
        else -> SetupStage.PLACEMENT
    }

    // The camera's own message, when it says something the stages don't: progress (teal) or a problem
    // (orange), such as light getting in while measuring the darkness.
    val messageTip: CamTip? = run {
        val m = camera.message
        val quiet = listOf(
            "The camera is off.", "Cover the lens and measure the darkness once.", "Ready to measure.",
            "Ready. Point the box at the centre of the TV.",
        )
        if (m.isEmpty() || m in quiet) return@run null
        // "Measuring with the lens covered…" would contradict the screen-down instructions; the button
        // being busy says enough.
        if (m.startsWith("Measuring")) return@run null
        CamTip(if (m.endsWith("…")) TipTone.ACTION else TipTone.LIMIT, m)
    }

    // The steps already done, shown above the next one.
    val doneSoFar = if (profiles.profile != null) "✓ Profile · ✓ camera locked" else "✓ Camera locked"

    val s = camera.settings
    val lockedText = run {
        val ap = s.aperture?.let { "aperture f/${fmt(it, 1)}" } ?: "fixed aperture"
        val size = if (camera.capturedSize.isEmpty()) "" else ", RAW photos ${camera.capturedSize}"
        "Locked: back main camera, ISO ${(s.iso ?: 0.0).toInt()}, $ap, focus closest$size"
    }
    // One line for the ready summary.
    val lockedSummary = run {
        val ap = s.aperture?.let { "f/${fmt(it, 1)}" } ?: "fixed aperture"
        "Settings locked · ISO ${(s.iso ?: 0.0).toInt()} · $ap · focus fixed"
    }

    // "4 setup checks complete" / "2 of 4 setup checks complete".
    val checksText = run {
        val done = listOf(
            profiles.profile != null, camera.running, camera.running && camera.dark != null && !redoDark, testPassed,
        ).count { it }
        if (done == 4) "4 setup checks complete" else "$done of 4 setup checks complete"
    }

    CamOnChange(camera.running) { running ->
        if (!running) {
            test = null
            redoDark = false
        }
    }

    fun measureDark() {
        app.scope.launch {
            test = null
            camera.measureDark()
            if (camera.message.startsWith("Ready")) redoDark = false
        }
    }

    fun runTest() {
        val p = profiles.profile ?: return
        app.scope.launch {
            val meter = CameraXYZMeter(camera = camera, profile = p, checkSettings = profiles.checksSettings)
            try {
                val r = meter.read()
                val xyz = r.xyz
                if (xyz == null) {
                    test = TestOutcome.Failed("No reading. Is the TV showing something bright in the box?")
                    return@launch
                }
                val a = LiveAnalysis.of(xyz, hdr = false)
                val uneven = (camera.lastReading?.uneven ?: 0.0) > 0.05
                test = TestOutcome.Reading(luminance = xyz.y, x = a.xy.x, y = a.xy.y, uneven = uneven)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                test = TestOutcome.Failed(e.message ?: e.toString())
            }
        }
    }

    // Asks for the CAMERA permission first when needed (design §12), then starts the camera.
    val startCamera = rememberCameraStarter { app.scope.launch { app.model.startCamera() } }

    val darknessActionTitle =
        if (redoDark || messageTip?.tone == TipTone.LIMIT) "Measure the darkness again" else "Measure the darkness"

    ScreenScaffold(
        title = "Set up the camera",
        leading = leading,
        onLeading = onDone,
        bottomBar = {
            BottomAction {
                // The pinned main action.
                when (stage) {
                    SetupStage.START -> PrimaryButton(
                        "Start the camera",
                        onClick = startCamera,
                        // As on iPhone: tappable even when the meter isn't available; start() then reports the
                        // problem through camera.message (UnavailableCameraMeter re-sets UNAVAILABLE).
                        // Android: also when only the approximate profile is missing, waiting for the permission.
                        enabled = (profiles.profile != null || profiles.approximateNeedsPermission) && !camera.busy,
                    )
                    SetupStage.DARKNESS -> PrimaryButton(
                        darknessActionTitle,
                        onClick = { measureDark() },
                        enabled = !camera.busy,
                    )
                    SetupStage.PLACEMENT -> PrimaryButton(
                        "Test reading",
                        onClick = { runTest() },
                        enabled = camera.ready && !camera.busy && profiles.profile != null,
                    )
                    SetupStage.READY -> PrimaryButton("Done", onClick = onDone)
                }
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            // The preview stays put while the steps scroll underneath.
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Theme.background)
                    .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                    CameraViewfinder(camera)
                }
                Text("Blurry on purpose: it averages out the TV's pixels", style = Fonts.small.secondary())
            }

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .animateContentSize(tween(200, easing = FastOutSlowInEasing)),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (profiles.isStandard) {
                    TipRow(TipTone.LIMIT, "Using the standard profile: readings are approximate.")
                } else if (profiles.isApproximate) {
                    TipRow(TipTone.LIMIT, "Using an approximate profile: readings are approximate.")
                }
                when (stage) {
                    SetupStage.START -> {
                        // Steps 1 and 2: the camera profile, then starting the camera.
                        if (messageTip != null) TipRow(messageTip.tone, messageTip.text)
                        StepCard("1 · Camera profile") {
                            val profile = profiles.profile
                            if (profile != null && profiles.isStandard) {
                                Text("No matching camera profile is installed for this phone.", style = Fonts.bodyBrand.white())
                                Text(
                                    "The standard profile works for now. Settings checks for a newer one.",
                                    style = Fonts.captionBrand.secondary(),
                                )
                            } else if (profile != null && profiles.isApproximate) {
                                Text("No matching camera profile is installed for this phone.", style = Fonts.bodyBrand.white())
                                Text(
                                    "An approximate one made from the camera's own colour data works for now. Settings checks for a newer one.",
                                    style = Fonts.captionBrand.secondary(),
                                )
                            } else if (profile != null) {
                                Text(profiles.summary, style = Fonts.bodyStrong.white())
                            } else {
                                Text(profiles.summary, style = Fonts.bodyBrand.white())
                                SecondaryButton("Check again", onClick = { app.scope.launch { profiles.refresh() } })
                            }
                        }
                        StepCard("2 · Start camera") {
                            Text("Exposure and focus stay locked after you start.", style = Fonts.bodyBrand.white())
                        }
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Next: measure darkness", style = Fonts.bodyBrand.white())
                                Text("Then place your phone on a stand", style = Fonts.captionBrand.secondary())
                            }
                            Icon(Symbols.arrowDown, contentDescription = null, tint = Theme.tealBright, modifier = Modifier.size(17.dp))
                        }
                    }
                    SetupStage.DARKNESS -> {
                        // Step 3: the sensor's baseline with the lens in total darkness.
                        StepCard(doneSoFar) {
                            Text("3 · Measure darkness", style = Fonts.cardTitle.tracking(-0.6f).white())
                            DarknessIllustration()
                            Text(
                                boldMarkdown(
                                    "This needs **total darkness**: lay the phone screen-up on a table, so the camera is pressed flat against it. A fingertip isn't enough.",
                                ),
                                style = Fonts.captionBrand.secondary(),
                            )
                            if (redoDark && !camera.busy) {
                                LinkButton("Keep the last measurement", onClick = { redoDark = false }, style = Fonts.captionStrong)
                            }
                        }
                        if (messageTip != null) TipRow(messageTip.tone, messageTip.text)
                    }
                    SetupStage.PLACEMENT -> {
                        // Step 4: the phone on a stand, pointing at the middle of the TV, and a test reading.
                        Text("4 · Place the phone", style = Fonts.stepTitleStyle)
                        PlacementIllustration()
                        Text(
                            "A hand's length (15–30 cm) from the TV, square to it, level with the centre. The grey patch should fill the yellow box with room to spare.",
                            style = Fonts.bodyBrand.secondary(),
                        )
                        if (messageTip != null) TipRow(messageTip.tone, messageTip.text)
                        when (val t = test) {
                            is TestOutcome.Reading -> {
                                TipRow(TipTone.OK, "Test reading received")
                                if (t.uneven) {
                                    TipRow(TipTone.LIMIT, "The box isn't evenly lit: move closer so the patch fills it.")
                                }
                                Card {
                                    Text(
                                        "${fmt(t.luminance, if (t.luminance < 10) 2 else 1)} cd/m²",
                                        style = Fonts.bodyStrong.monospacedDigit().white(),
                                    )
                                    Text(
                                        "x ${fmt(t.x, 4)} · y ${fmt(t.y, 4)}",
                                        style = Fonts.captionBrand.monospacedDigit().secondary(),
                                    )
                                    // Android: the standard and approximate profiles only estimate cd/m².
                                    profiles.brightnessNote?.let { Text(it, style = Fonts.captionBrand.secondary()) }
                                }
                            }
                            is TestOutcome.Failed -> TipRow(TipTone.LIMIT, t.why)
                            null -> Unit
                        }
                    }
                    SetupStage.READY -> {
                        // Everything done: the green summary.
                        Card {
                            Text(doneSoFar, style = Fonts.small.copy(color = Theme.green))
                            Text("✓ Darkness measured", style = Fonts.bodyBrand.copy(color = Theme.green))
                            Text("✓ Phone placement checked", style = Fonts.bodyBrand.copy(color = Theme.green))
                            Text(lockedSummary, style = Fonts.captionBrand.secondary())
                        }
                        TipRow(TipTone.OK, "Ready to measure. Keep the phone still.")
                    }
                }

                if (stage != SetupStage.START || camera.logLines.isNotEmpty()) {
                    // The camera log.
                    val rotation by animateFloatAsState(if (showLog) 180f else 0f, tween(200), label = "chevron")
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    role = Role.Button,
                                ) { showLog = !showLog }
                                .semantics { stateDescription = if (showLog) "Shown" else "Hidden" },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Camera log", style = Fonts.bodyBrand.white())
                                Text(checksText, style = Fonts.captionBrand.secondary())
                            }
                            Icon(
                                Symbols.chevronDown,
                                contentDescription = null,
                                tint = Theme.tealBright,
                                modifier = Modifier.size(17.dp).rotate(rotation),
                            )
                        }

                        if (showLog) {
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (camera.running) {
                                    Text(lockedText, style = Fonts.captionBrand.secondary())
                                    Column {
                                        if (camera.dark != null && stage != SetupStage.DARKNESS) {
                                            LinkButton(
                                                "Measure the darkness again",
                                                onClick = {
                                                    test = null
                                                    redoDark = true
                                                },
                                                style = Fonts.captionStrong,
                                                enabled = !camera.busy,
                                            )
                                        }
                                        if (stage == SetupStage.READY) {
                                            LinkButton(
                                                "Test again",
                                                onClick = { test = null },
                                                style = Fonts.captionStrong,
                                                enabled = !camera.busy,
                                            )
                                        }
                                        LinkButton(
                                            "Stop the camera",
                                            onClick = { camera.stop() },
                                            style = Fonts.captionStrong,
                                            enabled = !camera.busy,
                                        )
                                    }
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    for (line in camera.logLines.takeLast(40)) {
                                        Text(line, style = Fonts.tiny.copy(fontFamily = FontFamily.Monospace).secondary())
                                    }
                                }
                                if (camera.logLines.isNotEmpty()) {
                                    // Android: the camera's log, its last 200 lines (not just the 40 shown), for bug
                                    // reports. Repeated lines are collapsed (CameraLog), so the setup lines stay in.
                                    LinkButton(
                                        "Share the log",
                                        onClick = {
                                            val header = "Camera log · ${profiles.model} · ${profiles.profileLabel}"
                                            shareText(
                                                context,
                                                (listOf(header) + camera.logLines).joinToString("\n"),
                                                title = "Camera log",
                                            )
                                        },
                                        style = Fonts.captionStrong,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A step card: a teal eyebrow ("1 · Camera profile"), then the step. */
@Composable
private fun StepCard(eyebrow: String, content: @Composable ColumnScope.() -> Unit) {
    Card(spacing = 12.dp) {
        Text(eyebrow, style = Fonts.small.copy(color = Theme.tealBright))
        content()
    }
}

/** The phone lying screen-up on a table, in the dark (100 dp). */
@Composable
private fun DarknessIllustration() {
    Column(
        Modifier
            .fillMaxWidth()
            .height(100.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Theme.surface)
            .clearAndSetSemantics {
                contentDescription = "The phone lying screen-up on a table, its back camera pressed flat against it"
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.padding(top = 10.dp).size(28.dp), contentAlignment = Alignment.Center) {
            Icon(Symbols.moon, contentDescription = null, tint = Theme.accent, modifier = Modifier.size(24.dp))
        }
        val shape = RoundedCornerShape(8.dp)
        Box(
            Modifier
                .padding(top = 10.dp)
                .size(width = 160.dp, height = 18.dp)
                .clip(shape)
                .background(Theme.background)
                .border(2.dp, Theme.tealBright, shape),
        )
        Box(
            Modifier
                .padding(top = 2.dp)
                .padding(horizontal = 20.dp)
                .fillMaxWidth()
                .height(2.dp)
                .background(Theme.tealBright),
        )
        Text("Screen facing up", style = Fonts.tiny.secondary(), modifier = Modifier.padding(top = 5.dp))
    }
}

/** The TV with a grey patch, and the phone on its stand facing it (190 dp, 353-wide design, centred). */
@Composable
private fun PlacementIllustration() {
    val shape = RoundedCornerShape(18.dp)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(190.dp)
            .clip(shape)
            .background(Theme.surface)
            .clearAndSetSemantics {
                contentDescription =
                    "The phone on a stand, 15 to 30 centimetres from the TV, facing the middle of the screen"
            },
    ) {
        val oxDp = (maxWidth - 353.dp) / 2
        Canvas(Modifier.fillMaxSize()) {
            fun d(v: Float): Float = v.dp.toPx()
            val ox = (size.width - d(353f)) / 2
            // TV, its grey centre patch, and its stand.
            drawRoundRect(
                Theme.background, topLeft = Offset(ox + d(28f), d(20f)), size = Size(d(228f), d(116f)),
                cornerRadius = CornerRadius(d(7f)),
            )
            drawRoundRect(
                Theme.tealBright, topLeft = Offset(ox + d(29f), d(21f)), size = Size(d(226f), d(114f)),
                cornerRadius = CornerRadius(d(6f)), style = Stroke(d(2f)),
            )
            drawRoundRect(
                Color(0x77 / 255f, 0x7D / 255f, 0x7E / 255f),
                topLeft = Offset(ox + d(28f + 76f), d(20f + 13f)), size = Size(d(76f), d(90f)),
                cornerRadius = CornerRadius(d(1f)),
            )
            drawRect(Theme.tealBright, topLeft = Offset(ox + d(122f), d(141f)), size = Size(d(40f), d(3f)))
            // The phone, its measuring box, and the stand.
            drawRoundRect(
                Theme.background, topLeft = Offset(ox + d(270f), d(90f)), size = Size(d(35f), d(62f)),
                cornerRadius = CornerRadius(d(6f)),
            )
            drawRoundRect(
                Theme.tealBright, topLeft = Offset(ox + d(271f), d(91f)), size = Size(d(33f), d(60f)),
                cornerRadius = CornerRadius(d(5f)), style = Stroke(d(2f)),
            )
            drawRoundRect(
                Theme.accent, topLeft = Offset(ox + d(270f + 12f + 0.75f), d(90f + 25f + 0.75f)),
                size = Size(d(8.5f), d(8.5f)), cornerRadius = CornerRadius(d(0.25f)), style = Stroke(d(1.5f)),
            )
            val x = ox + d(287.5f)
            val stroke = d(2f)
            drawLine(Theme.tealBright, Offset(x, d(152f)), Offset(x, d(166f)), strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(Theme.tealBright, Offset(x, d(166f)), Offset(ox + d(268f), d(176f)), strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(Theme.tealBright, Offset(x, d(166f)), Offset(ox + d(307f), d(176f)), strokeWidth = stroke, cap = StrokeCap.Round)
        }
        Text(
            "15–30 cm · facing the centre",
            style = Fonts.tiny.secondary(),
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.offset(x = oxDp + 45.dp, y = 165.dp),
        )
    }
}

/**
 * The camera view (Swift CameraViewfinder): a 3:4 frame, 14 dp corners, Theme.previewFill, with the yellow
 * 1.5 dp measuring box (10% × 10%) in the centre, over the live CameraPreview while a Camera2Meter runs.
 */
@Composable
fun CameraViewfinder(camera: CameraMeter, modifier: Modifier = Modifier) {
    Box(
        modifier
            .aspectRatio(3f / 4f, matchHeightConstraintsFirst = true)
            .clip(RoundedCornerShape(14.dp))
            .background(Theme.previewFill)
            .clearAndSetSemantics {
                contentDescription = if (camera.running) "Camera view with the measuring box in the middle"
                else "Camera off. The measuring box is in the middle"
            },
    ) {
        // The live picture (Camera2Meter only), under the measuring box.
        if (camera.running && camera is Camera2Meter) CameraPreview(camera, Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize()) {
            val lw = 1.5.dp.toPx()
            val bw = size.width * 0.1f
            val bh = size.height * 0.1f
            drawRect(
                Theme.accent,
                topLeft = Offset((size.width - bw) / 2 + lw / 2, (size.height - bh) / 2 + lw / 2),
                size = Size(bw - lw, bh - lw),
                style = Stroke(lw),
            )
        }
    }
}

/**
 * Whatever the camera sees (Figma 13 Live meter), pushed (LiveRoute): an SDR/HDR segmented picker; the camera
 * blocker (LIMIT TipRow + "Set up the camera" LinkButton → [onOpenCamera]); the FreeMeter
 * (`remember { FreeMeter(app.scope) }`, started with CameraXYZMeter when there's no blocker, restarted on
 * hdr change, stopped on dispose) as white (reading 76, WhiteTarget, Red/Green/Blue BalanceBars, advice) or
 * colour (GamutChart, Hue/Saturation figures), or "Reading…". Footer "…Readings stay on this phone."
 */
@Composable
fun LiveMeterView(onBack: () -> Unit, onOpenCamera: () -> Unit) {
    val app = LocalApp.current
    val meter = remember { FreeMeter(app.scope) }
    // Kept while the camera setup is pushed on top and across recreation (Swift @State survives a push).
    var hdr by rememberSaveable { mutableStateOf(false) }

    fun start() {
        if (app.model.cameraBlocker != null) return
        val p = app.profiles.profile ?: return
        meter.start(CameraXYZMeter(camera = app.camera, profile = p, checkSettings = app.profiles.checksSettings), hdr)
    }

    // Swift .onAppear { start() } and .onChange(of: hdr) { start() }.
    LaunchedEffect(hdr) { start() }
    DisposableEffect(Unit) { onDispose { meter.stop() } }

    ScreenScaffold(title = "Live meter", onLeading = onBack) {
        ScreenColumn(spacing = 10.dp) {
            SdrHdrPicker(hdr = hdr, onChange = { hdr = it }, modifier = Modifier.padding(bottom = 2.dp))

            val blocker = app.model.cameraBlocker
            if (blocker != null) {
                TipRow(TipTone.LIMIT, blocker)
                LinkButton("Set up the camera", onClick = onOpenCamera)
            }
            val e = meter.error
            if (e != null) TipRow(TipTone.LIMIT, e)
            val a = meter.analysis
            if (a != null) {
                val bal = a.bal
                val name = a.name
                val t = a.tuv
                if (a.kind == LiveAnalysis.Kind.WHITE && bal != null) {
                    val onTarget = (a.de ?: 9.0) < 1
                    Eyebrow("Live · greyscale")
                    LiveReadingBlock(a)
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 110.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        WhiteTarget(uv = a.uv, d65 = a.d65uv, ok = onTarget, modifier = Modifier.size(110.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("White point", style = Fonts.bodyStrong.white())
                            Text(
                                "D65 target · dE ${fmt(a.de, 2)}",
                                style = Fonts.captionBrand.monospacedDigit()
                                    .copy(color = if (onTarget) Theme.green else Theme.textSecondary),
                            )
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        BalanceBar(label = "Red", value = bal.x - 1, colour = Theme.red)
                        BalanceBar(label = "Green", value = bal.y - 1, colour = Theme.green)
                        BalanceBar(label = "Blue", value = bal.z - 1, colour = Theme.blue)
                    }
                    val adv = a.advice
                    if (adv != null) TipRow(if (onTarget) TipTone.OK else TipTone.ACTION, adv)
                } else if (name != null && t != null) {
                    Eyebrow("Live · colour · ${camCap(name)}")
                    LiveReadingBlock(a)
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        GamutChart(
                            dots = listOf(GamutChart.Dot(id = name, uv = a.uv, target = t)),
                            hdr = hdr,
                            modifier = Modifier.widthIn(max = 320.dp).heightIn(max = 225.dp),
                        )
                    }
                    Card {
                        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            LiveFigure("Hue", "${signed(a.hue)}°", Modifier.weight(1f))
                            LiveFigure("Saturation", "${signed(a.sat)}%", Modifier.weight(1f))
                        }
                    }
                } else {
                    LiveReadingBlock(a)
                }
            } else if (blocker == null) {
                Row(
                    Modifier.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = Theme.textSecondary, strokeWidth = 2.dp)
                    Text("Reading…", style = Fonts.bodyBrand.secondary())
                }
            }
            Text(
                "Point the measuring box at a solid patch. Readings stay on this phone.",
                style = Fonts.small.secondary(),
            )
            // Android: the standard and approximate profiles only estimate cd/m².
            app.profiles.brightnessNote?.let { Text(it, style = Fonts.small.secondary()) }
        }
    }
}

/** Swift Picker("Targets") { SDR / HDR }.pickerStyle(.segmented).controlSize(.large). */
@Composable
private fun SdrHdrPicker(hdr: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val outer = RoundedCornerShape(9.dp)
    val inner = RoundedCornerShape(7.dp)
    Row(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(outer)
            .background(Color(0x3D767680))
            .padding(2.dp),
    ) {
        for ((label, value) in listOf("SDR" to false, "HDR" to true)) {
            val selected = hdr == value
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clip(inner)
                    .background(if (selected) Color(0xFF636366) else Color.Transparent)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Tab,
                    ) { onChange(value) }
                    .semantics {
                        contentDescription = "Targets, $label"
                        stateDescription = if (selected) "Selected" else "Not selected"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = (if (selected) Fonts.bodyStrong else Fonts.bodyBrand).white())
            }
        }
    }
}

/** The light level, big enough to read from across the room, and its chromaticity. */
@Composable
private fun LiveReadingBlock(a: LiveAnalysis) {
    Column(Modifier.semantics(mergeDescendants = true) { }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        CamScaledText(
            fmt(a.Y, if (a.Y < 10) 2 else 1),
            style = Fonts.reading(76f).tracking(-1f).white(),
            minScale = 0.5f,
        )
        Text("cd/m²", style = Fonts.brand(17f).white())
        Text("x ${fmt(a.xy.x, 4)} · y ${fmt(a.xy.y, 4)}", style = Fonts.captionBrand.monospacedDigit().secondary())
    }
}

/** A labelled figure: "Hue" over "+2.4°". */
@Composable
private fun LiveFigure(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = Fonts.captionBrand.secondary())
        CamScaledText(value, style = Fonts.reading(34f).tracking(-1f).white(), minScale = 0.6f)
    }
}
