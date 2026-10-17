package com.superessence.cuttinx.ui.splash

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superessence.cuttinx.ui.components.CuttinxMark
import com.superessence.cuttinx.ui.components.CuttinxWordmark
import com.superessence.cuttinx.ui.theme.Cx
import kotlinx.coroutines.delay

/** Animated intro: the blades slide in and cut, then the wordmark and signature appear. */
@Composable
fun SplashScreen(onDone: () -> Unit) {
    val blades = remember { Animatable(0f) }
    val word = remember { Animatable(0f) }
    val sign = remember { Animatable(0f) }
    val flash = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        blades.animateTo(1f, tween(650, easing = FastOutSlowInEasing))
        flash.animateTo(1f, tween(90)); flash.animateTo(0f, tween(260))
        word.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
        sign.animateTo(1f, tween(380))
        delay(450)
        onDone()
    }
    Box(Modifier.fillMaxSize().background(Cx.Black).clickable(onClick = onDone), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = flash.value * 0.35f)))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CuttinxMark(132.dp, blades.value, Modifier.scale(0.9f + 0.1f * blades.value))
            Spacer(Modifier.height(18.dp))
            CuttinxWordmark(44.sp, Modifier.alpha(word.value).scale(0.92f + 0.08f * word.value))
            Spacer(Modifier.height(10.dp))
            Text("ÉDITION PROFESSIONNELLE", color = Cx.Yellow.copy(alpha = sign.value), fontSize = 13.sp, letterSpacing = 6.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(6.dp))
            Text("SUPERESSENCE CREATIVE STUDIO", color = Color.White.copy(alpha = 0.75f * sign.value), fontSize = 11.sp, letterSpacing = 4.sp, fontWeight = FontWeight.SemiBold)
        }
        Text("Le studio des créateurs • Sans filigrane • Sans abonnement", color = Cx.Yellow.copy(alpha = 0.8f * sign.value), fontSize = 11.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp))
    }
}
