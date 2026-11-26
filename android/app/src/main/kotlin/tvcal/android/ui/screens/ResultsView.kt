package tvcal.android.ui.screens

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tvcal.android.LocalApp
import tvcal.android.R
import tvcal.android.storage.PanelType
import tvcal.android.ui.components.LinkButton
import tvcal.android.ui.components.ModeBadge
import tvcal.android.ui.components.NavLeading
import tvcal.android.ui.components.ReportHero
import tvcal.android.ui.components.ReportPanel
import tvcal.android.ui.components.ResultCharts
import tvcal.android.ui.components.ResultWarnings
import tvcal.android.ui.components.ScoreInfo
import tvcal.android.ui.components.ScreenColumn
import tvcal.android.ui.components.ScreenScaffold
import tvcal.android.ui.components.fileProviderAuthority
import tvcal.android.ui.components.shareDir
import tvcal.android.ui.theme.Fonts
import tvcal.android.ui.theme.Symbols
import tvcal.android.ui.theme.Theme
import tvcal.android.ui.theme.dateAbbreviated
import tvcal.android.ui.theme.dateLong
import tvcal.android.ui.theme.secondary
import tvcal.android.ui.theme.tracking
import tvcal.android.ui.theme.white
import tvcal.kit.Analysis
import tvcal.kit.Report
import tvcal.kit.SignalMode
import java.io.File
import java.time.Instant

// Port of Views/ResultsView.swift: the Before → After results, "Grey by grey" and the shareable summary.

/** The shared image: 393 × 660 dp drawn at 3 px per dp (Swift ImageRenderer scale 3). */
private const val SHARE_WIDTH_DP = 393
private const val SHARE_HEIGHT_DP = 660
private const val SHARE_SCALE = 3f

/**
 * The results of a calibration (shown by the After step, inside GuidedScreen): ReportPanel { "BEFORE → AFTER"
 * / "MEASURED", the date, the headline ("Your picture, measured." / "Not quite there yet." when worse by more
 * than 0.3 / "A more natural picture."), ReportHero }, NewsletterCard when [showsNewsletter], ResultWarnings,
 * "Grey by grey" (LinkButton, chart icon → [onOpenCharts]) and "Share" (renders ShareSummaryCard at
 * 393 × 660 dp × 3 to a PNG in shareDir via GraphicsLayer.toImageBitmap, then shareResultsImage (thumbnail + title) with the
 * title "<tvName> · calibration results"), ScoreInfo. [margin]: the kind of screen; null hides it (demo).
 */
@Composable
fun ResultsView(
    report: Report,
    hdr: Boolean,
    mode: SignalMode,
    tvName: String,
    onOpenCharts: () -> Unit,
    modifier: Modifier = Modifier,
    before: Report? = null,
    date: Instant = Instant.now(),
    margin: PanelType? = null,
    showsNewsletter: Boolean = false,
) {
    val app = LocalApp.current
    val context = LocalContext.current

    val headline = if (before == null) {
        "Your picture, measured."
    } else {
        val now = Analysis.greyAverage(report, hdr)
        val was = Analysis.greyAverage(before, hdr)
        if (now.isFinite() && was.isFinite() && now > was + 0.3) "Not quite there yet." else "A more natural picture."
    }
    val shareTitle = "$tvName · calibration results"
    val marginDE = margin?.let { app.profiles.marginDE(it) }

    // The shareable summary, drawn off screen into this layer and saved as a PNG (Swift renderSummary()).
    // Swift @State shareImage lives as long as the screen does (coming back from "Grey by grey" keeps it), so
    // the PNG's path and the report it was drawn for are saveable; a changed report drops the old PNG at once
    // (Share greys out) and a failed render leaves none, as Swift's renderSummary() returning nil does.
    val layer = rememberGraphicsLayer()
    var sharePath by rememberSaveable { mutableStateOf<String?>(null) }
    var sharedReport by rememberSaveable { mutableStateOf<Int?>(null) }
    val sharePng = sharePath?.let { File(it) }
    LaunchedEffect(report) {
        val key = report.hashCode()
        val kept = sharePath
        // Swift .onAppear { if shareImage == nil { render } }: keep the image already drawn for this report.
        if (kept != null && sharedReport == key && File(kept).exists()) return@LaunchedEffect
        // Swift .onChange(of: report) { shareImage = renderSummary() }: never share the previous results.
        sharePath = null
        sharedReport = null
        // Wait until the hidden card has been laid out and recorded.
        var frames = 0
        while (frames < 2 || (layer.size == IntSize.Zero && frames < 60)) {
            withFrameNanos { }
            frames++
        }
        if (layer.size == IntSize.Zero) return@LaunchedEffect
        val bitmap = try {
            layer.toImageBitmap().asAndroidBitmap()
        } catch (_: Exception) {
            return@LaunchedEffect
        }
        val file = withContext(Dispatchers.IO) {
            try {
                val f = File(shareDir(context), "calibration-results.png")
                f.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
                f
            } catch (_: Exception) {
                null
            }
        }
        if (file != null) {
            sharePath = file.path
            sharedReport = key
        }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // The report: the headline, each score on its gauge, the white point and the colours.
        ReportPanel {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    if (before != null) "BEFORE → AFTER" else "MEASURED",
                    style = Fonts.badge.tracking(0.8f),
                    color = Theme.accent,
                    modifier = Modifier.alignByBaseline(),
                )
                // Swift HStack(spacing: 8) around Spacer(minLength: 8): at least 8 + 8 + 8.
                Spacer(Modifier.width(24.dp))
                Spacer(Modifier.weight(1f))
                // The screen's name and format are in the step's header, just above.
                Text(
                    dateAbbreviated(date),
                    style = Fonts.small.secondary(),
                    modifier = Modifier.alignByBaseline(),
                )
            }
            Text(headline, style = Fonts.pageTitle.tracking(-0.6f), color = Color.White)
            ReportHero(report = report, hdr = hdr, before = before, margin = marginDE)
        }

        if (showsNewsletter) NewsletterCard()
        ResultWarnings(report.warnings)

        Box(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LinkButton(
                    "Grey by grey",
                    onClick = onOpenCharts,
                    style = Fonts.callout14,
                    leadingIcon = Symbols.chart,
                )
                // Swift HStack(spacing: 12) around Spacer(minLength: 8): at least 12 + 8 + 12.
                Spacer(Modifier.width(32.dp))
                Spacer(Modifier.weight(1f))
                val png = sharePng
                LinkButton(
                    "Share",
                    onClick = { if (png != null) shareResultsImage(context, png, shareTitle) },
                    style = Fonts.callout14,
                    enabled = png != null,
                    leadingIcon = Symbols.share,
                )
            }
            // The card to share, off screen: recorded into [layer], never drawn here.
            Box(Modifier.size(0.dp)) {
                CompositionLocalProvider(LocalDensity provides Density(SHARE_SCALE, fontScale = 1f)) {
                    Box(
                        Modifier
                            .wrapContentSize(Alignment.TopStart, unbounded = true)
                            .requiredSize(SHARE_WIDTH_DP.dp, SHARE_HEIGHT_DP.dp)
                            .clearAndSetSemantics { }
                            .drawWithContent {
                                layer.record { this@drawWithContent.drawContent() }
                            },
                    ) {
                        ShareSummaryCard(
                            report = report,
                            hdr = hdr,
                            mode = mode,
                            tvName = tvName,
                            before = before,
                            date = date,
                            margin = marginDE,
                        )
                    }
                }
            }
        }
        ScoreInfo(margin = margin)
    }
}

/**
 * Swift ShareLink(item: image, preview: SharePreview(shareTitle, image: image)): the system share sheet for the
 * results PNG. ClipData carries the image so the Sharesheet (Android 10+) shows its thumbnail, and EXTRA_TITLE
 * the "<tvName> · calibration results" title (the chooser title is ignored there for ACTION_SEND).
 */
private fun shareResultsImage(context: Context, file: File, title: String) {
    val uri = try {
        FileProvider.getUriForFile(context, fileProviderAuthority(context), file)
    } catch (_: IllegalArgumentException) {
        return
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TITLE, title)
        clipData = ClipData.newUri(context.contentResolver, title, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(chooser)
    } catch (_: ActivityNotFoundException) {
    }
}

/** "Grey by grey" (pushed, ResultsChartsRoute): tvName + ModeBadge, ResultCharts, ResultWarnings. Back → [onBack]. */
@Composable
fun ResultsChartsView(
    report: Report,
    hdr: Boolean,
    mode: SignalMode,
    tvName: String,
    onBack: () -> Unit,
    before: Report? = null,
) {
    ScreenScaffold(title = "Grey by grey", leading = NavLeading.BACK, onLeading = onBack) {
        ScreenColumn {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(tvName, style = Fonts.bodyStrong, color = Color.White, modifier = Modifier.weight(1f))
                ModeBadge(mode)
            }
            ResultCharts(report = report, hdr = hdr, before = before)
            ResultWarnings(report.warnings)
        }
    }
}

/**
 * The image people share (393 × 660 dp): Slidebean logo + "TV Calibrator" + ModeBadge, the TV name (30), "Before →
 * after · <long date>" / "Measured · …", ReportHero(animated = false), "by Slidebean" and "Measured with
 * phone". tealGradient over the background, 24 dp corners. [margin]: the phone's margin of error in dE.
 */
@Composable
fun ShareSummaryCard(
    report: Report,
    hdr: Boolean,
    mode: SignalMode,
    tvName: String,
    modifier: Modifier = Modifier,
    before: Report? = null,
    date: Instant = Instant.now(),
    margin: Double? = null,
) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier
            .fillMaxSize()
            .clip(shape)
            .background(Theme.background, shape)
            .background(Theme.tealGradient, shape)
            .border(1.dp, Theme.hairline, shape)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painterResource(R.drawable.slidebean_logo),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(22.dp),
            )
            Text("TV Calibrator", style = Fonts.bodyStrong, color = Color.White)
            Spacer(Modifier.weight(1f))
            ModeBadge(mode)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BasicText(
                tvName,
                style = Fonts.brand(30f).tracking(-0.6f).white(),
                maxLines = 1,
                // Swift lineLimit(1): still too long at 60%, the name ends in "…".
                overflow = TextOverflow.Ellipsis,
                // Swift minimumScaleFactor(0.6).
                autoSize = TextAutoSize.StepBased(minFontSize = 18.sp, maxFontSize = 30.sp, stepSize = 0.5.sp),
            )
            Text(
                (if (before != null) "Before → after · " else "Measured · ") + dateLong(date),
                style = Fonts.captionBrand.secondary(),
            )
        }
        ReportHero(report = report, hdr = hdr, before = before, margin = margin, animated = false)
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth()) {
            Text(
                "by Slidebean",
                style = Fonts.callout14,
                color = Theme.accent,
                modifier = Modifier.alignByBaseline(),
            )
            // Swift default HStack spacing (8) around Spacer(minLength: 8).
            Spacer(Modifier.width(24.dp))
            Spacer(Modifier.weight(1f))
            Text(
                "Measured with phone",
                style = Fonts.tiny.secondary(),
                modifier = Modifier.alignByBaseline(),
            )
        }
    }
}
