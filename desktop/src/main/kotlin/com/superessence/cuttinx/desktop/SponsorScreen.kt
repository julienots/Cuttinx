package com.superessence.cuttinx.desktop

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superessence.cuttinx.core.smart.TechVault
import com.superessence.cuttinx.icons.CxIcons
import com.superessence.cuttinx.lab.TechGate
import kotlinx.coroutines.delay

/**
 * PC version of the "full-screen advert": a sponsor screen that covers the whole window for
 * [SECONDS] seconds (CUTTINX's own announcement, no tracking), after which the technology unlocks.
 */
private const val SECONDS = 12

private val slides = listOf(
    Triple("Rocket", "CUTTINX PRO", "Monte comme un pro. Du téléphone au PC."),
    Triple("Search", "440 actions rapides", "Tape « karaoké », « écran partagé » ou « tempo » : c'est fait."),
    Triple("Studio", "Modèles en un geste", "Vlog, bande-annonce, top 5, podcast, story : tout le montage d'un coup."),
    Triple("Twitch", "Twitch Editor", "Scènes, alertes, emotes et badges animés, prêts pour OBS."),
    Triple("Identity", "ID Influenceur", "Couleurs, polices, bios et bannières de ta marque."),
)

@Composable
fun SponsorOverlay(accent: Color) {
    val req = TechGate.pending ?: return
    var left by remember(req) { mutableIntStateOf(SECONDS) }
    LaunchedEffect(req) { while (left > 0) { delay(1000); left-- } }
    val slide = slides[((SECONDS - left) / 3).coerceIn(0, slides.lastIndex)]
    val pulse by rememberInfiniteTransition().animateFloat(0.96f, 1.04f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse))
    Box(Modifier.fillMaxSize().background(Color(0xF20B0C0F)).clickable(MutableInteractionSource(), null) { }, contentAlignment = Alignment.Center) {
        Text("ANNONCE CUTTINX", Modifier.align(Alignment.TopStart).padding(22.dp), color = Cx.Dim, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Text(if (left > 0) "$left s" else "", Modifier.align(Alignment.TopEnd).padding(22.dp), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 760.dp).padding(24.dp)) {
            Icon(CxIcons.byName(slide.first), null, Modifier.size(84.dp).scale(pulse), tint = accent)
            Spacer(Modifier.height(18.dp))
            Text(slide.second, fontSize = 46.sp, fontWeight = FontWeight.Black, color = Color.White, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(slide.third, fontSize = 18.sp, color = Cx.Dim, textAlign = TextAlign.Center)
            Spacer(Modifier.height(40.dp))
            LinearProgressIndicator(progress = { 1f - left.toFloat() / SECONDS }, modifier = Modifier.width(420.dp).height(5.dp).clip(RoundedCornerShape(3.dp)), color = accent, trackColor = Color.White.copy(alpha = 0.1f))
            Spacer(Modifier.height(22.dp))
            Text("Ensuite : ${req.tech.label} débloqué pendant ${TechVault.UNLOCK_HOURS} h", color = Color.White, fontSize = 15.sp)
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton({ TechGate.cancel() }) { Text("Annuler", color = Cx.Dim) }
                Spacer(Modifier.width(12.dp))
                Button({ TechGate.grant(req.tech) }, enabled = left == 0, colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.Black)) {
                    Text(if (left > 0) "Patiente $left s" else "Débloquer ${req.tech.label}", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
