package tvcal.android.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tvcal.android.LocalApp
import tvcal.android.R
import tvcal.android.ui.components.FullScreenSheet
import tvcal.android.ui.components.NavLeading
import tvcal.android.ui.components.PinnedActionHost
import tvcal.android.ui.components.PlacementArt
import tvcal.android.ui.components.ScreenColumn
import tvcal.android.ui.components.ScreenScaffold
import tvcal.android.ui.components.WalkPage
import tvcal.android.ui.components.Walkthrough
import tvcal.android.ui.components.openUrl
import tvcal.android.ui.theme.Fonts
import tvcal.android.ui.theme.Symbols
import tvcal.android.ui.theme.Theme

// Port of Views/WelcomeView.swift: "How it works", shown once on first launch and from the home screen.

/**
 * The "How it works" FullScreenSheet: PinnedActionHost + NavBar "Welcome" with a close X, a Walkthrough
 * (section "How it works", finish "Get started"): the explainer video page (when links.explainerURL is set,
 * with VideoPreview), "Make your TV look the way the film was made", "Your phone is the meter" (bullets),
 * "What you need" (PlacementArt, bullets). Finish and Close both call [onFinish] (the caller sets
 * `welcome.seen` and hides the sheet). System Back calls [onDismiss] (Swift: swiping the sheet away, which
 * doesn't call onFinish, so the walkthrough isn't marked seen and can open by itself again).
 */
@Composable
fun WelcomeView(onFinish: () -> Unit, onDismiss: () -> Unit) {
    val app = LocalApp.current
    val explainer = app.links.links.explainerURL

    val pages = buildList {
        if (explainer != null) {
            add(
                WalkPage(
                    title = "New to calibration? Start with the video",
                    text = "This app goes with Slidebean's videos on what calibration does to a TV's picture, with a full session start to finish. If you haven't seen them, they're worth a few minutes first.",
                    extra = { VideoPreview(url = explainer) },
                ),
            )
        }
        add(
            WalkPage(
                symbol = Symbols.tv,
                title = "Make your TV look the way the film was made",
                text = "Most TVs come out of the box too blue, too bright and too saturated. TV Calibrator walks you through your TV's own picture settings until they match the standard that films and shows are made to.",
            ),
        )
        add(
            WalkPage(
                symbol = Symbols.cameraViewfinder,
                title = "Your phone is the meter",
                text = "The TV plays test-pattern videos from our YouTube channel. Your phone's camera reads each colour, and the app tells you which setting to change and by how much. Nothing is installed on the TV.",
                bullets = listOf(
                    "Start from the most accurate picture mode (often Filmmaker Mode), then fine-tune it",
                    "One step at a time: white, greys, then colours",
                    "A before and after score at the end",
                ),
            ),
        )
        add(
            WalkPage(
                title = "What you need",
                text = "No TV handy? Try the demo on the home screen to see how it works.",
                bullets = listOf(
                    "Your TV's remote",
                    "The YouTube app on the TV",
                    "Something to stand the phone on, 15–30 cm from the screen",
                    "A dark room",
                ),
                art = { PlacementArt(Modifier.fillMaxSize()) },
            ),
        )
    }

    // Back (Swift: swiping the sheet away) only hides the sheet; Get started and the X mark it seen.
    FullScreenSheet(onDismiss = onDismiss) {
        ScreenScaffold(title = "Welcome", leading = NavLeading.CLOSE, onLeading = onFinish) {
            PinnedActionHost(Modifier.fillMaxSize()) {
                ScreenColumn {
                    Walkthrough(
                        pages = pages,
                        finishTitle = "Get started",
                        section = "How it works",
                        onFinish = onFinish,
                    )
                }
            }
        }
    }
}

/** #010D12, the frame's dark end and the play glyph. */
private val videoDark = Color(0xFF010D12)
/** #0A3A4A, the frame's glow. */
private val videoGlow = Color(0xFF0A3A4A)

/**
 * The explainer video as something that plainly plays on YouTube: a 16:9 frame (radial #0A3A4A → #010D12,
 * the BrandChart at 60%, a white play button, the Slidebean logo and "YouTube" when not [compact]), and
 * "Watch how TV calibration works" / "Slidebean · opens YouTube". [compact]: the 112 dp thumbnail row for the
 * home screen ("New to this? Watch how it works"). Tapping opens [url].
 */
@Composable
fun VideoPreview(url: String, modifier: Modifier = Modifier, compact: Boolean = false) {
    val context = LocalContext.current
    val link = modifier
        .fillMaxWidth()
        .clickable { openUrl(context, url) }
        .clearAndSetSemantics {
            contentDescription = "Watch how TV calibration works, on YouTube"
            role = Role.Button
        }
    if (compact) {
        Row(link, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            VideoFrame(play = 30.dp, compact = true, modifier = Modifier.width(112.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("New to this? Watch how it works", style = Fonts.bodyStrong, color = Color.White)
                VideoSource()
            }
        }
    } else {
        Column(link, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            VideoFrame(play = 60.dp, compact = false, modifier = Modifier.fillMaxWidth())
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Watch how TV calibration works", style = Fonts.bodyStrong, color = Color.White)
                VideoSource()
            }
        }
    }
}

@Composable
private fun VideoSource() {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Slidebean · opens YouTube", style = Fonts.small, color = Theme.tealBright)
        Icon(Symbols.arrowUpRight, contentDescription = null, tint = Theme.tealBright, modifier = Modifier.size(12.dp))
    }
}

/** The 16:9 picture: dark teal, the app icon's glowing colour chart behind, a white play button in front. */
@Composable
private fun VideoFrame(play: Dp, compact: Boolean, modifier: Modifier) {
    val shape = RoundedCornerShape(if (compact) 10.dp else 16.dp)
    Box(
        modifier
            .aspectRatio(16f / 9f)
            .clip(shape)
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(videoGlow, videoDark),
                        center = Offset(size.width * 0.3f, size.height * 0.25f),
                        radius = 260.dp.toPx(),
                    ),
                )
            }
            .border(1.dp, Theme.hairline, shape),
    ) {
        Image(
            painterResource(R.drawable.brand_chart),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = if (compact) 4.dp else 10.dp)
                .alpha(0.6f),
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)))
        Box(
            Modifier
                .align(Alignment.Center)
                .size(play)
                .shadow(6.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.4f), spotColor = Color.Black.copy(alpha = 0.4f))
                .background(Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Symbols.playFill,
                contentDescription = null,
                tint = videoDark,
                modifier = Modifier.offset(x = play * 0.04f).size(play * 0.5f),
            )
        }
        if (!compact) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painterResource(R.drawable.slidebean_logo),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.height(18.dp),
                    )
                    Text("Slidebean", style = Fonts.captionStrong, color = Color.White)
                }
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier
                        .align(Alignment.End)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Symbols.playRectangleFill, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                    Text("YouTube", style = Fonts.captionStrong, color = Color.White)
                }
            }
        }
    }
}
