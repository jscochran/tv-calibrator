package tvcal.android.ui.screens

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import tvcal.android.R

// Port of Views/IntroView.swift. Shown by AppRoot over everything for 1.7 s, then faded out over 0.6 s.

/** Near-black #030F11 with the faintest teal (#082A2C) behind the logo. */
private val introBase = Color(0xFF030F11)
private val introGlow = Color(0xFF082A2C)
/** The logo's pink glow, Swift Color(red: 1, green: 0.2, blue: 0.4). */
private val introPink = Color(1f, 0.2f, 0.4f)
/** SwiftUI .easeOut. */
private val easeOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)

/**
 * The opening screen: only the Slidebean mark (R.drawable.slidebean_logo, 84 dp wide, a pink glow
 * (1, 0.2, 0.4) at 28%, radius 30) on #030F11 with a radial #082A2C glow (radius 320); the logo fades in and
 * scales 0.92 → 1 over 0.9 s ease-out. Accessibility label "TV Calibrator by Slidebean".
 */
@Composable
fun IntroView(modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val p by animateFloatAsState(if (shown) 1f else 0f, tween(900, easing = easeOut), label = "intro")

    Box(
        modifier
            // SwiftUI's opaque overlay takes the hits: taps mustn't reach the hidden home screen beneath.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            }
            .background(introBase)
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(introGlow, introBase),
                        center = center,
                        radius = 320.dp.toPx(),
                    ),
                    alpha = p,
                )
            }
            .clearAndSetSemantics { contentDescription = "TV Calibrator by Slidebean" },
        contentAlignment = Alignment.Center,
    ) {
        // The glow around the mark (Swift .shadow(radius: 30) on the logo), faded in with it.
        Box(
            Modifier
                .size(84.dp + 60.dp)
                .drawBehind {
                    drawCircle(
                        Brush.radialGradient(
                            colors = listOf(introPink.copy(alpha = 0.28f * p), Color.Transparent),
                            center = center,
                            radius = size.minDimension / 2,
                        ),
                    )
                },
        )
        Image(
            painterResource(R.drawable.slidebean_logo),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .width(84.dp)
                .graphicsLayer {
                    val s = 0.92f + 0.08f * p
                    scaleX = s
                    scaleY = s
                    alpha = p
                },
        )
    }
}
