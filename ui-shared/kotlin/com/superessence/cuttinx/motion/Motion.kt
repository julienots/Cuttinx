package com.superessence.cuttinx.motion

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Colours of the four spaces: editor, Twitch, identity, Studio Créateur. Used as thin markers only. */
val SpaceColors = listOf(Color(0xFFFFCC1F), Color(0xFF3D8BFF), Color(0xFFFF4FA3), Color(0xFFFF5A36))

private val SpaceNames = listOf("ÉDITEUR", "TWITCH", "ID INFLUENCEUR", "CRÉATEUR")

private fun seg(t: Float, a: Float, b: Float) = ((t - a) / (b - a)).coerceIn(0f, 1f)
private fun easeOut(x: Float) = 1f - (1f - x) * (1f - x) * (1f - x)

/**
 * Start screen of CUTTINX PRO (Android and PC): a calm, short sequence on a plain dark ground.
 * The name appears with a slight opening of its letter spacing, a fine rule is drawn under it,
 * then the four spaces are listed with a small marker in their colour. No glow, no particles.
 * A tap / click skips it.
 */
@Composable
fun LaunchIntro(onDone: () -> Unit, durationMs: Int = 1700) {
    val p = remember { Animatable(0f) }
    val done by rememberUpdatedState(onDone)
    LaunchedEffect(Unit) { p.animateTo(1f, tween(durationMs, easing = LinearEasing)); done() }
    val t = p.value
    val name = easeOut(seg(t, 0.06f, 0.42f))
    val rule = easeOut(seg(t, 0.28f, 0.62f))
    val spaces = seg(t, 0.5f, 0.78f)
    val exit = seg(t, 0.88f, 1f)
    Box(Modifier.fillMaxSize().background(Color(0xFF0B0C0F))
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { done() }
        .graphicsLayer { alpha = 1f - exit },
        contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("CUTTINX", color = Color.White.copy(alpha = name), fontSize = 40.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (2 + 8 * name).sp)
            Spacer(Modifier.height(14.dp))
            Box(Modifier.width(220.dp * rule).height(1.dp).background(Color.White.copy(alpha = 0.35f)))
            Spacer(Modifier.height(14.dp))
            Text("STUDIO DE MONTAGE", color = Color.White.copy(alpha = 0.6f * spaces), fontSize = 11.sp, letterSpacing = 4.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.graphicsLayer { alpha = spaces; translationY = (1f - spaces) * 8f }) {
                SpaceNames.forEachIndexed { i, n ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(n, color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
                        Spacer(Modifier.height(5.dp))
                        Box(Modifier.width(18.dp).height(2.dp).background(SpaceColors[i]))
                    }
                }
            }
        }
    }
}

/** Switching between two spaces: a short fade with a barely visible slide, nothing that distracts from the work. */
fun <S> AnimatedContentTransitionScope<S>.spaceTransition(forward: Boolean): ContentTransform {
    val dir = if (forward) 1 else -1
    return (slideInHorizontally(tween(240, easing = FastOutSlowInEasing)) { it * dir / 24 } + fadeIn(tween(220, delayMillis = 40))) togetherWith
        (slideOutHorizontally(tween(180)) { -it * dir / 24 } + fadeOut(tween(140)))
}

/**
 * Thin line in the colour of the space being opened that runs once along the top edge when the
 * space changes. Drawn over the content, never blocks touches.
 */
@Composable
fun SpaceSwoosh(key: Any, color: Color, forward: Boolean, modifier: Modifier = Modifier) {
    val p = remember { Animatable(1f) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(key) {
        if (first) { first = false; return@LaunchedEffect }
        p.snapTo(0f); p.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
    }
    val k = p.value
    if (k >= 1f) return
    Canvas(modifier.fillMaxSize()) {
        val w = size.width
        val x0 = if (forward) 0f else w * (1f - k)
        val x1 = if (forward) w * k else w
        drawLine(color.copy(alpha = 1f - k * 0.7f), Offset(x0, 1.dp.toPx()), Offset(x1, 1.dp.toPx()), strokeWidth = 2.dp.toPx())
    }
}
