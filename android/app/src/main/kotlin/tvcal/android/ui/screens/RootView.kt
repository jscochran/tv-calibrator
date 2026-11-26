package tvcal.android.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tvcal.android.LocalApp
import tvcal.android.app.AppModel
import tvcal.android.app.PrefKeys
import tvcal.android.camera.CameraMeter
import tvcal.android.profiles.ProfileService
import tvcal.android.storage.GuideStep
import tvcal.android.storage.TVRecord
import tvcal.android.storage.upper
import tvcal.android.ui.components.BottomAction
import tvcal.android.ui.components.Card
import tvcal.android.ui.components.FullScreenSheet
import tvcal.android.ui.components.IconBubble
import tvcal.android.ui.components.LinkButton
import tvcal.android.ui.components.NavLeading
import tvcal.android.ui.components.PrimaryButton
import tvcal.android.ui.components.ScoreTile
import tvcal.android.ui.components.ScreenScaffold
import tvcal.android.ui.components.SecondaryButton
import tvcal.android.ui.theme.Fonts
import tvcal.android.ui.theme.Symbols
import tvcal.android.ui.theme.Theme
import tvcal.android.ui.theme.secondary
import tvcal.android.ui.theme.tint
import tvcal.kit.Analysis
import tvcal.kit.SignalMode
import java.util.UUID

// Port of Views/RootView.swift (all but RootView itself, which is ui/nav/RootNav.kt): the home screen,
// TVCard, ToolTile, the camera status card, Manage Screens and the delete alert.
// Text: "iPhone" → "phone" where it means the user's device.

// MARK: - Home

/**
 * The home screen (Figma "02 Home", Swift TVListView): own bar (HomeNavigationBar), the first-visit
 * headline, CameraStatusRow (→ camera setup), "Your screens" + "Manage Screens" (ManageScreensView sheet),
 * EmptyScreensCard or a TVCard per TV (tap → [onOpenTV], long-press → Delete → DeleteScreenAlert),
 * "Add a Screen" (TVSetupFlow sheet; on add → [onOpenTV]), "Try the demo, no screen needed" (only without a
 * demo TV: app.model.addDemoTV() then [onOpenTV]), and the ToolTiles (How it works → WelcomeView sheet,
 * Live meter, Settings, Lab mode in the lab build). Opens WelcomeView by itself 2.1 s after launch when
 * `welcome.seen` is false and there are no TVs (prefs.boolState(PrefKeys.WELCOME_SEEN, false)).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TVListView(
    onOpenTV: (UUID) -> Unit,
    onOpenCamera: () -> Unit,
    onOpenLive: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLab: () -> Unit,
) {
    val app = LocalApp.current
    val model = app.model
    val links = app.links.links
    /** "How it works" (WelcomeView) opens by itself once, on the first launch. */
    val welcomeSeenState = remember { app.prefs.boolState(PrefKeys.WELCOME_SEEN, false) }
    var welcomeSeen by welcomeSeenState
    // Sheet flags survive Activity recreation (the sheets' own fields are rememberSaveable); a TV is kept by id.
    var welcoming by rememberSaveable { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var doomedID by rememberSaveable { mutableStateOf<String?>(null) }
    val doomed = doomedID?.let { id -> model.tvs.firstOrNull { it.id.upper == id } }
    var managing by rememberSaveable { mutableStateOf(false) }
    val tvs = model.tvs

    LaunchedEffect(Unit) {
        // After the opening screen has faded.
        if (welcomeSeenState.value || model.tvs.isNotEmpty()) return@LaunchedEffect
        delay(2_100)
        if (!welcomeSeenState.value) welcoming = true
    }

    ScreenScaffold(title = null) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
        ) {
            HomeNavigationBar(openSettings = onOpenSettings)

            Column(
                Modifier.padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // The headline is for a first visit; with screens saved, they come first.
                if (tvs.isEmpty()) {
                    Text("See it the way\nit was made.", style = Fonts.displayStyle)
                    Text(
                        "Calibrate your TV’s picture with your phone as the meter.",
                        style = Fonts.bodyBrand.secondary(),
                    )
                }

                CameraStatusRow(Modifier.clip(RoundedCornerShape(18.dp)).clickable(onClick = onOpenCamera))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Your screens",
                        style = Fonts.section,
                        color = Color.White,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                    LinkButton("Manage Screens", onClick = { managing = true }, style = Fonts.captionStrong)
                }

                if (tvs.isEmpty()) {
                    EmptyScreensCard(video = links.explainerURL) { welcoming = true }
                }
                for (tv in tvs) {
                    key(tv.id) {
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            TVCard(
                                tv,
                                Modifier
                                    .clip(RoundedCornerShape(18.dp))
                                    .combinedClickable(
                                        onClick = { onOpenTV(tv.id) },
                                        onLongClick = { menu = true },
                                    ),
                            )
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Delete", color = Theme.red) },
                                    leadingIcon = { Icon(Symbols.trash, contentDescription = null, tint = Theme.red) },
                                    onClick = {
                                        menu = false
                                        doomedID = tv.id.upper
                                    },
                                )
                            }
                        }
                    }
                }

                PrimaryButton("Add a Screen", onClick = { adding = true })
                if (tvs.none { it.isDemo }) {
                    SecondaryButton("Try the demo, no screen needed", onClick = {
                        val tv = model.addDemoTV()
                        onOpenTV(tv.id)
                    })
                }

                ToolTile(
                    Symbols.playRectangle,
                    "How it works",
                    if (links.explainerURL == null) "What you need, how it works" else "The video, and what you need",
                    Modifier.clickable { welcoming = true },
                )
                ToolTile(
                    Symbols.viewfinder,
                    "Read any colour on screen",
                    "Live meter",
                    Modifier.clickable(onClick = onOpenLive),
                )
                ToolTile(
                    Symbols.sliders,
                    "Settings",
                    "Camera profile, videos",
                    Modifier.clickable(onClick = onOpenSettings),
                    boxed = false,
                )
                if (AppModel.isLabBuild) {
                    ToolTile(
                        Symbols.flask,
                        "Lab mode",
                        "Train a camera profile",
                        Modifier.clickable(onClick = onOpenLab),
                        boxed = false,
                    )
                }
            }
        }
    }

    if (adding) {
        TVSetupFlow(tv = null, onDismiss = { adding = false }, onAdded = { tv -> onOpenTV(tv.id) })
    }
    if (managing) {
        ManageScreensView(onDismiss = { managing = false })
    }
    if (welcoming) {
        WelcomeView(
            onFinish = {
                welcomeSeen = true
                welcoming = false
            },
            onDismiss = { welcoming = false },
        )
    }
    DeleteScreenAlert(doomed, onDismiss = { doomedID = null }) { tv -> model.delete(tv) }
}

/** The home screen's own bar: "TV Calibrator" on the left, the settings button on the right (48 dp). */
@Composable
private fun HomeNavigationBar(openSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(48.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "TV Calibrator",
            style = Fonts.headlineBrand,
            color = Color.White,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        Box(
            Modifier
                .size(44.dp)
                .clickable(onClick = openSettings)
                .semantics { contentDescription = "Settings" },
            contentAlignment = Alignment.CenterEnd,
        ) {
            Icon(Symbols.sliders, contentDescription = null, tint = Theme.tealBright, modifier = Modifier.size(22.dp))
        }
    }
}

/** No screens yet: PlacementPanel, then VideoPreview(compact) or "New to this? See how it works". */
@Composable
private fun EmptyScreensCard(video: String?, howItWorks: () -> Unit) {
    Card(spacing = 12.dp) {
        PlacementPanel()
        if (video != null) {
            VideoPreview(url = video, compact = true)
        } else {
            LinkButton("New to this? See how it works", onClick = howItWorks, style = Fonts.captionStrong)
        }
    }
}

/** #777D7E */
private val patchGrey = Color(0xFF777D7E)

/** The TV with its grey centre patch and the phone on its stand facing it (100 dp tall, 321-wide design). */
@Composable
private fun PlacementPanel() {
    val panel = RoundedCornerShape(18.dp)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(100.dp)
            .clip(panel)
            .background(Theme.surface)
            .clearAndSetSemantics {
                contentDescription =
                    "Put the phone on a stand 15 to 30 centimetres from the TV, facing the centre of the screen"
            },
    ) {
        val w = maxWidth
        val s = w.value / 321f
        val phoneX = w - 51.dp
        val stemX = phoneX + 17.5.dp
        // TV
        Box(
            Modifier
                .offset(x = (28 * s).dp, y = 20.dp)
                .size(width = (228 * s).dp, height = 36.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(Theme.background)
                .border(2.dp, Theme.tealBright, RoundedCornerShape(7.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(width = (76 * s).dp, height = 18.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(patchGrey),
            )
        }
        Text(
            "15–30 cm · facing the centre",
            style = Fonts.tiny.secondary(),
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.offset(x = (45 * s).dp, y = 75.dp),
        )
        // Stand
        Canvas(Modifier.fillMaxSize()) {
            val x = stemX.toPx()
            val path = Path().apply {
                moveTo(x, 66.dp.toPx())
                lineTo(x, 76.dp.toPx())
                moveTo(x - 20.dp.toPx(), 86.dp.toPx())
                lineTo(x, 76.dp.toPx())
                lineTo(x + 20.dp.toPx(), 86.dp.toPx())
            }
            drawPath(
                path,
                Theme.tealBright,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
        // Phone, with the measuring box
        Box(
            Modifier
                .offset(x = phoneX, y = 5.dp)
                .size(width = 35.dp, height = 62.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Theme.background)
                .border(2.dp, Theme.tealBright, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(10.dp).border(1.5.dp, Theme.accent, RoundedCornerShape(1.dp)))
        }
    }
}

/**
 * One TV on the home screen (Figma "Screen card"): IconBubble (sparklesTv for the demo), name, brand (or
 * "Demo: simulated readings"), a FormatChip per mode and the score word of the latest After (SDR first).
 */
@Composable
fun TVCard(tv: TVRecord, modifier: Modifier = Modifier) {
    val app = LocalApp.current
    val store = app.model.store
    // Read each format's guide file once per TV (not on every recomposition); the home screen is composed
    // afresh whenever it comes back into view, so this is never stale.
    val states = remember(tv) { SignalMode.entries.associateWith { store.guideState(tv, it) } }

    /** The greyscale score of the latest finished calibration (SDR first), shown as its word. */
    val score: Double? = run {
        for (m in SignalMode.entries) {
            val a = states.getValue(m).sessions.lastOrNull { it.after != null }?.after
            if (a != null) {
                val de = Analysis.greyAverage(Analysis.analyse(a, m.isHDR), m.isHDR)
                if (de.isFinite()) return@run de
            }
        }
        null
    }

    /** Filled when a format is calibrated, half when started, an outline when not started. */
    fun stage(m: SignalMode): FormatStage {
        val done = states.getValue(m).progress.done.size
        return if (done >= GuideStep.stepper.size - 1) FormatStage.DONE
        else if (done > 0) FormatStage.STARTED
        else FormatStage.NOT_STARTED
    }

    Card(modifier, spacing = 12.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBubble(if (tv.isDemo) Symbols.sparklesTv else Symbols.tv, size = 44.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(tv.name, style = Fonts.bodyStrong, color = Color.White)
                Text(
                    if (tv.isDemo) "Demo: simulated readings" else tv.brand.label,
                    style = Fonts.captionBrand.secondary(),
                )
            }
            Icon(Symbols.chevronRight, contentDescription = null, tint = Theme.tealBright, modifier = Modifier.size(18.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            for (m in SignalMode.entries) FormatChip(m, stage(m))
            Spacer(Modifier.weight(1f))
            if (score != null) {
                Text(
                    ScoreTile.word(score),
                    style = Fonts.captionStrong,
                    color = ScoreTile.tint(score),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Filled when a format is calibrated, outlined when started, faint when not started. */
private enum class FormatStage { NOT_STARTED, STARTED, DONE }

@Composable
private fun FormatChip(mode: SignalMode, stage: FormatStage) {
    val shape = RoundedCornerShape(6.dp)
    val fg = when (stage) {
        FormatStage.DONE -> Theme.background
        FormatStage.STARTED -> mode.tint
        FormatStage.NOT_STARTED -> Theme.textSecondary
    }
    val stroke = when (stage) {
        FormatStage.DONE -> Color.Transparent
        FormatStage.STARTED -> mode.tint.copy(alpha = 0.7f)
        FormatStage.NOT_STARTED -> Theme.hairline
    }
    val word = when (stage) {
        FormatStage.DONE -> "calibrated"
        FormatStage.STARTED -> "started"
        FormatStage.NOT_STARTED -> "not started"
    }
    Row(
        Modifier
            .clip(shape)
            .background(if (stage == FormatStage.DONE) mode.tint else Color.Transparent)
            .border(1.dp, stroke, shape)
            .padding(horizontal = 7.dp, vertical = 4.dp)
            .clearAndSetSemantics { contentDescription = "${mode.label}: $word" },
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (stage == FormatStage.DONE) {
            Icon(Symbols.checkmark, contentDescription = null, tint = fg, modifier = Modifier.size(9.dp))
        }
        Text(
            mode.label,
            style = Fonts.micro.copy(fontWeight = FontWeight.SemiBold),
            color = fg,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * A home-screen row: a label over a secondary line, with a teal icon on the right. Boxed in a card
 * ([boxed]) or bare. The caller makes it clickable.
 */
@Composable
fun ToolTile(
    symbol: ImageVector,
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    boxed: Boolean = true,
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(modifier)
            .background(if (boxed) Theme.card else Color.Transparent)
            .border(1.dp, if (boxed) Theme.hairline else Color.Transparent, shape)
            .padding(if (boxed) 12.dp else 0.dp)
            .heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = Fonts.bodyBrand, color = Color.White)
            Text(text, style = Fonts.captionBrand.secondary())
        }
        Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
            Icon(symbol, contentDescription = null, tint = Theme.tealBright, modifier = Modifier.size(21.dp))
        }
    }
}

// MARK: - Camera status

/** The camera card on the home screen and the setup step: CameraStatusContent(app.profiles, app.camera). */
@Composable
fun CameraStatusRow(modifier: Modifier = Modifier) {
    val app = LocalApp.current
    CameraStatusContent(app.profiles, app.camera, modifier)
}

private enum class CameraPhase { CHECKING, UNAVAILABLE, SET_UP, DARKNESS, STANDARD, READY }

/**
 * Phases checking / unavailable / setUp / darkness / standard / ready (Swift CameraStatusContent): a teal
 * hero Card when there's something to do (setUp, darkness, unavailable), else one 48 dp row.
 * Android: a profile on a phone the camera meter can't use is "unavailable" too, with camera.unavailableReason.
 */
@Composable
private fun CameraStatusContent(profiles: ProfileService, camera: CameraMeter, modifier: Modifier = Modifier) {
    val phase = when {
        profiles.profile == null ->
            if (profiles.status == ProfileService.Status.Checking) CameraPhase.CHECKING else CameraPhase.UNAVAILABLE
        !camera.available -> CameraPhase.UNAVAILABLE
        !camera.running -> CameraPhase.SET_UP
        camera.dark == null -> CameraPhase.DARKNESS
        profiles.isStandard || profiles.isApproximate -> CameraPhase.STANDARD
        else -> CameraPhase.READY
    }
    val symbol = when (phase) {
        CameraPhase.CHECKING -> Symbols.refresh
        CameraPhase.UNAVAILABLE -> Symbols.videoSlash
        CameraPhase.SET_UP -> Symbols.camera
        CameraPhase.DARKNESS -> Symbols.moon
        CameraPhase.STANDARD -> Symbols.exclamationTriangle
        CameraPhase.READY -> Symbols.checkmarkCircle
    }
    val colour = when (phase) {
        CameraPhase.CHECKING, CameraPhase.SET_UP, CameraPhase.DARKNESS -> Theme.tealBright
        CameraPhase.UNAVAILABLE, CameraPhase.STANDARD -> Theme.limit
        CameraPhase.READY -> Theme.green
    }
    val title = when (phase) {
        CameraPhase.CHECKING -> "Checking this phone"
        CameraPhase.UNAVAILABLE -> "Camera not available"
        CameraPhase.SET_UP -> "Set up the camera"
        CameraPhase.DARKNESS -> "Measure the darkness"
        CameraPhase.STANDARD -> if (profiles.isApproximate) "Approximate profile" else "Standard profile"
        CameraPhase.READY -> "Ready to measure"
    }
    val detail = when (phase) {
        CameraPhase.CHECKING -> "Finding the right camera profile."
        CameraPhase.UNAVAILABLE -> if (profiles.profile == null) profiles.summary else camera.unavailableReason
        CameraPhase.SET_UP -> "A minute, before your first reading."
        CameraPhase.DARKNESS -> "Cover the camera to set its baseline."
        CameraPhase.STANDARD -> "Readings approximate. You can still get started."
        CameraPhase.READY -> "Camera set up. Place your phone on its stand."
    }
    /** Something for the user to do (set up, measure the darkness): the teal card. Otherwise one line. */
    val needsAction = phase == CameraPhase.SET_UP || phase == CameraPhase.DARKNESS || phase == CameraPhase.UNAVAILABLE

    if (needsAction) {
        Card(modifier.semantics(mergeDescendants = true) {}, hero = true) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBubble(symbol, tint = colour, size = 36.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(title, style = Fonts.headlineBrand, color = colour)
                    Text(detail, style = Fonts.captionBrand.secondary())
                }
                Icon(Symbols.chevronRight, contentDescription = null, tint = Theme.tealBright, modifier = Modifier.size(16.dp))
            }
        }
    } else {
        val shape = RoundedCornerShape(16.dp)
        Row(
            modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .background(Theme.card, shape)
                .border(1.dp, Theme.hairline, shape)
                .padding(horizontal = 14.dp)
                .semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(19.dp), contentAlignment = Alignment.Center) {
                if (phase == CameraPhase.CHECKING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = Theme.tealBright,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(symbol, contentDescription = null, tint = colour, modifier = Modifier.size(19.dp))
                }
            }
            Text(title, style = Fonts.captionStrong, color = colour, maxLines = 1)
            Text(
                if (phase == CameraPhase.STANDARD) "Readings approximate" else "phone camera",
                style = Fonts.small.secondary(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            Icon(Symbols.chevronRight, contentDescription = null, tint = Theme.tealBright, modifier = Modifier.size(15.dp))
        }
    }
}

// MARK: - Manage Screens

/**
 * Every saved screen (Figma "04 Manage Screens"), as a FullScreenSheet: "Manage Screens" with a close X,
 * a ManagedScreenRow per TV (swipe or the trash button → DeleteScreenAlert, pencil → TVSetupFlow(tv)),
 * "Add a Screen" (TVSetupFlow(null)), "No screens yet." when empty, and a "Done" BottomAction.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageScreensView(onDismiss: () -> Unit) {
    val app = LocalApp.current
    val model = app.model
    // Kept across Activity recreation; TVs by id, looked up in model.tvs.
    var editingID by rememberSaveable { mutableStateOf<String?>(null) }
    val editing = editingID?.let { id -> model.tvs.firstOrNull { it.id.upper == id } }
    var doomedID by rememberSaveable { mutableStateOf<String?>(null) }
    val doomed = doomedID?.let { id -> model.tvs.firstOrNull { it.id.upper == id } }
    var adding by rememberSaveable { mutableStateOf(false) }

    /** "LG · SDR calibrated", "Samsung · Not started". */
    fun detail(tv: TVRecord): String {
        val full = GuideStep.stepper.size - 1
        val counts = SignalMode.entries.map { m -> m to model.store.guideState(tv, m).progress.done.size }
        val calibrated = counts.filter { it.second >= full }.map { it.first.label }
        val started = counts.firstOrNull { it.second > 0 }
        val progress = when {
            calibrated.isNotEmpty() -> calibrated.joinToString(", ") + " calibrated"
            started != null -> started.first.label + " in progress"
            else -> "Not started"
        }
        return (if (tv.isDemo) "Demo" else tv.brand.label) + " · " + progress
    }

    FullScreenSheet(onDismiss = onDismiss) {
        ScreenScaffold(
            title = "Manage Screens",
            leading = NavLeading.CLOSE,
            onLeading = onDismiss,
            bottomBar = {
                BottomAction { SecondaryButton("Done", onClick = onDismiss) }
            },
        ) {
            Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                ) {
                    for (tv in model.tvs) {
                        key(tv.id) {
                            Box(Modifier.padding(vertical = 6.dp)) {
                                // Swift .swipeActions(edge: .trailing, allowsFullSwipe: false): the swipe only
                                // reveals a red "Delete" button; tapping it asks (DeleteScreenAlert).
                                SwipeRevealDelete(onDelete = { doomedID = tv.id.upper }) {
                                    ManagedScreenRow(
                                        tv = tv,
                                        detail = remember(tv) { detail(tv) },
                                        onEdit = { editingID = tv.id.upper },
                                        onDelete = { doomedID = tv.id.upper },
                                    )
                                }
                            }
                        }
                    }
                    Box(Modifier.padding(vertical = 6.dp)) {
                        SecondaryButton("Add a Screen", onClick = { adding = true })
                    }
                }
                if (model.tvs.isEmpty()) {
                    Text(
                        "No screens yet.",
                        style = Fonts.bodyBrand.secondary(),
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        }
        editing?.let { tv ->
            key(tv.id) { TVSetupFlow(tv = tv, onDismiss = { editingID = null }) }
        }
        if (adding) {
            TVSetupFlow(tv = null, onDismiss = { adding = false })
        }
        DeleteScreenAlert(doomed, onDismiss = { doomedID = null }) { tv -> model.delete(tv) }
    }
}

/**
 * Swift `.swipeActions(edge: .trailing, allowsFullSwipe: false) { Button(role: .destructive) { … }
 * label: { Label("Delete", systemImage: "trash") }.tint(Theme.red) }`: swiping the row left reveals a red
 * "Delete" button (trash icon over the title) that stays open; only tapping it calls [onDelete]. A short
 * swipe springs back, dragging past the button stretches it but never deletes (no full swipe), and tapping
 * the row while it is open closes it. TalkBack gets the same "Delete" as a custom action.
 */
@Composable
private fun SwipeRevealDelete(onDelete: () -> Unit, content: @Composable () -> Unit) {
    val buttonWidth = 84.dp
    val revealPx = with(LocalDensity.current) { buttonWidth.toPx() }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    fun settle(open: Boolean) {
        scope.launch { offset.animateTo(if (open) -revealPx else 0f) }
    }
    val dragState = rememberDraggableState { delta ->
        scope.launch { offset.snapTo((offset.value + delta).coerceIn(-revealPx * 1.6f, 0f)) }
    }
    val tapSource = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(18.dp)

    Box(
        Modifier
            .fillMaxWidth()
            .semantics {
                customActions = listOf(CustomAccessibilityAction("Delete") { onDelete(); true })
            },
    ) {
        if (offset.value < 0f) {
            // The revealed action, as wide as the row has moved (at least its own width once open).
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
                val shown = with(LocalDensity.current) { (-offset.value).toDp() }
                Column(
                    Modifier
                        .fillMaxHeight()
                        .width(shown)
                        .clip(shape)
                        .background(Theme.red)
                        .clickable {
                            settle(false)
                            onDelete()
                        }
                        .clearAndSetSemantics {},
                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Symbols.trash, contentDescription = null, tint = Color.White, modifier = Modifier.size(19.dp))
                    Text("Delete", style = Fonts.captionStrong, color = Color.White, maxLines = 1, overflow = TextOverflow.Clip)
                }
            }
        }
        Box(
            Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .draggable(
                    state = dragState,
                    orientation = Orientation.Horizontal,
                    onDragStopped = { velocity ->
                        settle(offset.value < -revealPx / 2f || velocity < -800f)
                    },
                ),
        ) {
            content()
            if (offset.value < 0f) {
                // Tapping the row while the action is showing just closes it, as on iOS.
                Box(
                    Modifier
                        .matchParentSize()
                        .clickable(interactionSource = tapSource, indication = null) { settle(false) }
                        .clearAndSetSemantics {},
                )
            }
        }
    }
}

/** One saved screen: name and "LG · SDR calibrated", with edit (teal, not for the demo) and delete (red). */
@Composable
private fun ManagedScreenRow(tv: TVRecord, detail: String, onEdit: () -> Unit, onDelete: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(shape)
            .background(Theme.background)
            .background(Theme.card)
            .border(1.dp, Theme.hairline, shape)
            .padding(start = 16.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier.weight(1f).semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(tv.name, style = Fonts.bodyStrong, color = Color.White)
            Text(detail, style = Fonts.captionBrand.secondary())
        }
        if (!tv.isDemo) {
            IconButton(onClick = onEdit, modifier = Modifier.size(44.dp)) {
                Icon(Symbols.pencil, contentDescription = "Edit ${tv.name}", tint = Theme.tealBright, modifier = Modifier.size(21.dp))
            }
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(44.dp)) {
            Icon(Symbols.trash, contentDescription = "Delete ${tv.name}", tint = Theme.red, modifier = Modifier.size(21.dp))
        }
    }
}

/**
 * "Delete <name>?" with Cancel and Delete (AlertDialog), shown while [doomed] isn't null. Message: "Delete the
 * screen and its history. Its measurements and calibration progress are removed from this phone."
 */
@Composable
fun DeleteScreenAlert(doomed: TVRecord?, onDismiss: () -> Unit, delete: (TVRecord) -> Unit) {
    val tv = doomed ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete ${tv.name}?", style = Fonts.headlineBrand, color = Color.White) },
        text = {
            Text(
                "Delete the screen and its history. Its measurements and calibration progress are removed from this phone.",
                style = Fonts.bodyBrand.secondary(),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                delete(tv)
                onDismiss()
            }) { Text("Delete", style = Fonts.bodyStrong, color = Theme.red) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", style = Fonts.bodyBrand, color = Theme.tealBright) }
        },
        containerColor = Theme.surface,
    )
}
