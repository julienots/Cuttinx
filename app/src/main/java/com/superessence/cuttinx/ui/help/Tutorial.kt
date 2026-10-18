package com.superessence.cuttinx.ui.help

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superessence.cuttinx.ui.components.AccentButton
import com.superessence.cuttinx.ui.theme.LocalCx

private class Step(val emoji: String, val title: String, val text: String, val tip: String)

private val steps = listOf(
    Step("🎬", "Bienvenue dans CUTTINX PRO", "Le studio de montage des créateurs : multipiste, animations, effets GPU, sons, IA locale et export 4K sans filigrane.", "Tout reste sur ton téléphone, gratuitement."),
    Step("➕", "1. Crée un projet", "Touche « Créer » ou « Depuis la galerie » et choisis le format : 9:16 pour TikTok / Reels / Shorts, 16:9 pour YouTube.", "Le format se change à tout moment dans « Format »."),
    Step("✂️", "2. Coupe et assemble", "Place la ligne blanche (tête de lecture) puis « Diviser ». Tire les poignées jaunes pour raccourcir, appui long pour déplacer un plan.", "Pince la timeline pour zoomer, « Multi » pour sélectionner plusieurs éléments."),
    Step("✨", "3. Anime tout", "Sélectionne un plan ou un texte puis « Animations » : entrée, sortie, boucle (zoom lent, zoom choc, Ken Burns, tremblement…) et combos prêts comme « Hook TikTok ».", "Les images clés (losange) permettent des mouvements libres."),
    Step("🔀", "4. Transitions & sons", "Touche le petit carré entre deux plans pour choisir une transition (fouet, glitch, cœur, étoile, stores…). Ajoute un whoosh ou un impact depuis « Audio » (bruitages et jingles).", "Une transition + un son = un montage pro."),
    Step("🎙️", "5. Soigne le son", "Sur un clip : « Audio » > « Voix » pour réduire le bruit de fond en un geste, voix studio, ou effets (robot, hélium, monstre, fantôme…).", "« Voix off » enregistre au micro pendant la lecture."),
    Step("⭐", "6. Outils pro", "« Outils pro » : accroche d'ouverture, barre de progression, filigrane @compte, sous-titres viraux, zoom sur les temps de la musique, zones TikTok / Reels / Shorts.", "Parfait pour garder les spectateurs jusqu'au bout."),
    Step("🚀", "7. Exporte", "Le bouton jaune « Exporter » : jusqu'à 4K 60 i/s, H.264 / HEVC / AV1, sans filigrane. La vidéo arrive dans ta galerie.", "Le guide complet est dans Aide (bouton ? de l'accueil)."),
)

/** Step-by-step tutorial shown on first launch and from the help screen. */
@Composable
fun TutorialOverlay(onDone: () -> Unit) {
    var i by remember { mutableIntStateOf(0) }
    val cx = LocalCx.current
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.92f)).pointerInput(Unit) { detectTapGestures { } }, contentAlignment = Alignment.Center) {
        Column(Modifier.padding(24.dp).widthIn(max = 520.dp).clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF1B1B1F), Color(0xFF2A2200)))).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            AnimatedContent(i, label = "tuto") { k ->
                val s = steps[k]
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(s.emoji, fontSize = 64.sp)
                    Spacer(Modifier.height(12.dp))
                    Text(s.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(10.dp))
                    Text(s.text, color = Color.White.copy(alpha = 0.85f), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(12.dp))
                    Text("💡 ${s.tip}", color = cx.accent, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                steps.indices.forEach { k -> Box(Modifier.size(if (k == i) 10.dp else 7.dp).clip(CircleShape).background(if (k == i) cx.accent else Color.White.copy(alpha = 0.3f))) }
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (i < steps.lastIndex) TextButton(onDone) { Text("Passer", color = Color.White.copy(alpha = 0.6f)) }
                Spacer(Modifier.weight(1f))
                if (i > 0) TextButton({ i-- }) { Text("Précédent", color = Color.White) }
                AccentButton(if (i == steps.lastIndex) "C'est parti !" else "Suivant", { if (i == steps.lastIndex) onDone() else i++ })
            }
        }
    }
}
