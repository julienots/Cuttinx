package com.superessence.cuttinx.motion

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Colours of the four spaces: editor, Twitch, identity, Studio Créateur. */
val SpaceColors = listOf(Color(0xFFFFCC1F), Color(0xFF3D8BFF), Color(0xFFFF4FA3), Color(0xFFFF5A36))

private fun seg(t: Float, a: Float, b: Float) = ((t - a) / (b - a)).coerceIn(0f, 1f)
private fun easeOut(x: Float) = 1f - (1f - x) * (1f - x) * (1f - x)
private fun backOut(x: Float): Float { val c = 1.9f; val y = x - 1f; return 1f + (c + 1f) * y * y * y + c * y * y }

/**
 * Launch animation of CUTTINX PRO (Android and PC): four light blades in the colours of the
 * spaces slash the screen, the logo letters drop and bounce, a burst of sparks, then the whole
 * intro zooms through to the app. A tap / click skips it.
 */
@Composable
fun LaunchIntro(onDone: () -> Unit, durationMs: Int = 2800) {
    val p = remember { Animatable(0f) }
    val done by rememberUpdatedState(onDone)
    LaunchedEffect(Unit) { p.animateTo(1f, tween(durationMs, easing = LinearEasing)); done() }
    val t = p.value
    val exit = seg(t, 0.86f, 1f)
    Box(Modifier.fillMaxSize().background(Color(0xFF07080B))
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { done() }
        .graphicsLayer { val s = 1f + 0.18f * easeOut(exit); scaleX = s; scaleY = s; alpha = 1f - exit },
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height; val d = max(w, h)
            // breathing glow behind the logo
            val glow = seg(t, 0.25f, 0.6f)
            drawCircle(Brush.radialGradient(listOf(SpaceColors[0].copy(alpha = 0.28f * glow), Color.Transparent), Offset(w / 2, h / 2), d * 0.45f), d * 0.45f, Offset(w / 2, h / 2))
            // four blades slashing the screen one after the other
            SpaceColors.forEachIndexed { i, c ->
                val k = seg(t, 0.04f + i * 0.07f, 0.30f + i * 0.07f)
                if (k <= 0f || k >= 1f) return@forEachIndexed
                val e = easeOut(k)
                val cx = -d * 0.6f + e * (w + d * 1.2f)
                val band = d * 0.05f * (1f - k * 0.6f)
                val off = (i - 1.5f) * d * 0.09f
                val path = Path().apply {
                    moveTo(cx - band + off, -10f); lineTo(cx + band + off, -10f)
                    lineTo(cx + band - d * 0.35f + off, h + 10f); lineTo(cx - band - d * 0.35f + off, h + 10f); close()
                }
                drawPath(path, Brush.horizontalGradient(listOf(Color.Transparent, c.copy(alpha = 0.95f), Color.White.copy(alpha = 0.9f)), cx - band * 4 + off, cx + band + off))
            }
            // spark burst when the logo lands
            val b = seg(t, 0.46f, 0.86f)
            if (b > 0f) for (i in 0 until 44) {
                val a = i * 2.39996f
                val sp = 0.35f + (i * 37 % 100) / 100f * 0.65f
                val r = easeOut(b) * d * 0.42f * sp
                val pos = Offset(w / 2 + cos(a) * r, h / 2 + sin(a) * r * 0.75f)
                drawCircle(SpaceColors[i % 4].copy(alpha = (1f - b) * 0.9f), (3f + 4f * sp) * (1f - b * 0.5f), pos)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.Bottom) {
                "CUTTINX".forEachIndexed { i, ch ->
                    val k = seg(t, 0.30f + i * 0.035f, 0.46f + i * 0.035f)
                    Text(ch.toString(), color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic,
                        modifier = Modifier.graphicsLayer {
                            alpha = k; translationY = (1f - easeOut(k)) * -160f
                            val s = if (k <= 0f) 0.4f else backOut(k); scaleX = s; scaleY = s
                            rotationZ = (1f - k) * (if (i % 2 == 0) -18f else 18f)
                        })
                }
                val pro = seg(t, 0.56f, 0.70f)
                Text("PRO", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Black,
                    modifier = Modifier.padding(start = 6.dp, bottom = 16.dp).graphicsLayer { val s = backOut(pro); scaleX = s; scaleY = s; alpha = pro; rotationZ = -8f * (1f - pro) }
                        .clip(RoundedCornerShape(5.dp)).background(Brush.linearGradient(listOf(SpaceColors[0], Color(0xFFFF8A00)))).padding(horizontal = 6.dp, vertical = 2.dp))
            }
            // underline sweep in the four colours
            val u = easeOut(seg(t, 0.5f, 0.72f))
            Row(Modifier.padding(top = 4.dp).width(260.dp * u).height(4.dp).clip(RoundedCornerShape(2.dp))) {
                SpaceColors.forEach { c -> Box(Modifier.weight(1f).fillMaxHeight().background(c)) }
            }
            val tag = seg(t, 0.62f, 0.78f)
            Spacer(Modifier.height(14.dp))
            Text("ÉDITEUR · TWITCH · ID INFLU · CRÉATEUR", color = Color.White.copy(alpha = 0.8f * tag), fontSize = 12.sp, letterSpacing = (2 + 4 * tag).sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("SUPERESSENCE CREATIVE STUDIO", color = SpaceColors[0].copy(alpha = 0.75f * tag), fontSize = 10.sp, letterSpacing = 4.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Transition between two spaces: the new one slides in from the side of its tab with a slight zoom. */
fun <S> AnimatedContentTransitionScope<S>.spaceTransition(forward: Boolean): ContentTransform {
    val dir = if (forward) 1 else -1
    val spec = spring<androidx.compose.ui.unit.IntOffset>(dampingRatio = 0.82f, stiffness = 320f)
    return (slideInHorizontally(spec) { it * dir / 2 } + scaleIn(tween(380), initialScale = 0.9f) + fadeIn(tween(260, delayMillis = 60))) togetherWith
        (slideOutHorizontally(spec) { -it * dir / 3 } + scaleOut(tween(320), targetScale = 1.06f) + fadeOut(tween(200)))
}

/**
 * Coloured "swoosh" that crosses the screen when the space changes: a slanted band in the colour
 * of the space being opened, with a lighter trail. Drawn over the content, never blocks touches.
 */
@Composable
fun SpaceSwoosh(key: Any, color: Color, forward: Boolean, modifier: Modifier = Modifier) {
    val p = remember { Animatable(1f) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(key) {
        if (first) { first = false; return@LaunchedEffect }
        p.snapTo(0f); p.animateTo(1f, tween(620, easing = FastOutSlowInEasing))
    }
    val k = p.value
    if (k >= 1f) return
    Canvas(modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        val x = if (forward) -w * 0.5f + k * w * 2f else w * 1.5f - k * w * 2f
        val band = w * 0.22f
        val slant = h * 0.35f
        fun band(x0: Float, wd: Float, c: Color) = drawPath(Path().apply {
            moveTo(x0, 0f); lineTo(x0 + wd, 0f); lineTo(x0 + wd - slant, h); lineTo(x0 - slant, h); close()
        }, c)
        val fade = sin(k * PI.toFloat())
        band(x - band * (if (forward) 1.6f else -0.6f), band * 0.5f, color.copy(alpha = 0.25f * fade))
        band(x, band, color.copy(alpha = 0.55f * fade))
        band(x + band * 0.35f, band * 0.12f, Color.White.copy(alpha = 0.5f * fade))
    }
}
