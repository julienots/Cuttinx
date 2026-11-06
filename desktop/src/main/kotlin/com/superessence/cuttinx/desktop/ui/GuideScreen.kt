package com.superessence.cuttinx.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superessence.cuttinx.desktop.Cx

private class Chapter(val title: String, val steps: List<String>)

private val chapters = listOf(
    Chapter("Éditeur vidéo : premier montage", listOf(
        "Onglet « Éditeur » en haut : choisis le format (9:16 TikTok / Reels / Shorts, 16:9 YouTube…) et clique « Créer le projet ».",
        "« Importer » à gauche (ou glisse-dépose tes fichiers sur la fenêtre) : les vidéos et photos se placent à la suite, les sons à la tête de lecture.",
        "Espace = lecture / pause. Clique sur la règle pour te déplacer, ← → pour avancer image par image (Maj = 1 seconde).",
        "S coupe à la tête de lecture, Suppr supprime, Maj+Suppr supprime en resserrant, Ctrl+D duplique, Ctrl+Z / Ctrl+Y annule / rétablit.",
        "Glisse un clip pour le déplacer (il s'aimante à la tête de lecture et aux autres clips), tire ses bords pour le rogner, glisse-le vers le haut / bas pour changer de piste.",
        "Le panneau de droite règle le clip sélectionné : position, taille, rotation, opacité, vitesse, volume, fondus, effets, animations, fond vert, masque. Glisse aussi directement sur l'aperçu pour le déplacer.",
        "« Exporter la vidéo » en haut à droite : 720p à 4K, 24 à 60 i/s, MP4 (H.264 + AAC) prêt pour TikTok, YouTube et Instagram.",
    )),
    Chapter("Éditeur vidéo : effets, sons et montage pro", listOf(
        "Effets : clique un effet avec un clip sélectionné ; sans sélection il devient un calque d'effets sur tout ce qui est dessous. Réglages en direct à droite.",
        "Transitions : sélectionne le clip qui arrive puis choisis la transition, ou « Partout » pour toutes les coupes (cœur, étoile, whip, glitch, zoom…).",
        "Animations : entrée, sortie et boucle (zoom lent avant / arrière, panoramique, pulsation…) pour chaque clip.",
        "Musique et Bruitages : sons originaux libres de droits (whoosh, bass drop, ba dum tss, jingles…). Le bouton lecture permet d'écouter avant d'ajouter.",
        "Voix : réduire le bruit de fond, voix studio, et 18 effets de voix (hélium, monstre, robot, radio, fantôme…).",
        "Outils pro : zoom lent sur tout le montage, transitions et animations automatiques, barre de progression, titre d'accroche, pseudo en filigrane.",
        "Projet commencé sur le téléphone ? Sur Android : menu du projet > « Exporter le projet (.cuttinx) » avec les médias, puis ici « Importer un projet » (ou glisse le fichier sur la fenêtre) : tu continues le montage sur PC.",
    )),
    Chapter("Twitch Editor : démarrer en 3 minutes", listOf(
        "Ouvre « Twitch Editor » et choisis un thème dans la colonne de gauche (filtre par catégorie : super-héros, geek, anime, mignon, saisons…).",
        "À droite, écris le nom de ta chaîne, ta phrase d'accroche, tes réseaux et ton planning : ils apparaissent sur toutes les scènes.",
        "Clique sur les scènes sous l'aperçu pour les voir : « Le live va commencer », « Je reviens », « Pause », « Fin du live », les cadres de jeu, de discussion, de caméra et de chat, l'alerte de follow.",
        "Clique sur « Exporter le pack OBS complet », choisis un dossier : tout est prêt en quelques secondes.",
    )),
    Chapter("Installer le pack dans OBS", listOf(
        "Règle OBS en 1920×1080 (Paramètres > Vidéo > Résolution de base) — ou 1280×720 si tu as exporté en 720p.",
        "Scènes animées : Sources > + > Navigateur > « Fichier local » > scene.html. Largeur 1920, hauteur 1080. Le compte à rebours démarre tout seul.",
        "Cadres : Sources > + > Image > le PNG du cadre. Mets ta capture de jeu et ta webcam EN DESSOUS de l'image dans la liste des sources.",
        "Transitions : en bas à droite « Transitions de scène » > + > Stinger > choisis le PNG animé du dossier transitions. Point de transition : 600 ms.",
        "Alerte : utilise nouveau-follow.png comme image d'alerte dans Streamlabs / StreamElements.",
    )),
    Chapter("Créer ton identité (DA)", listOf(
        "Ouvre « ID Influenceur », écris ton nom de créateur, ta niche, coche 2 ou 3 traits de personnalité et ton public.",
        "Clique « Créer mon identité » : palette, polices, slogans, bios, piliers de contenu, hashtags, ton et idée de logo.",
        "Pas convaincu ? « Autre proposition » génère une variante. Tu peux imposer ta couleur préférée.",
        "« Utiliser pour mes overlays Twitch » applique tes couleurs et ton nom à tous les overlays.",
        "Exporte la planche PNG (à garder sous les yeux) et le guide texte pour rester cohérent sur TikTok, Instagram, YouTube et Twitch.",
    )),
    Chapter("Conseils de pro", listOf(
        "Un seul thème, partout : scènes, cadres, transitions, miniatures et bannières. C'est ce qui rend une chaîne reconnaissable.",
        "Garde ta caméra du même côté sur toutes les scènes (option « Caméra à droite »).",
        "Le chat doit rester lisible : place la source du chat dans le cadre « Chat », police 18–22 px, fond semi-transparent déjà fourni.",
        "Change de thème selon la saison ou les événements (Halloween, Noël, Saint-Valentin, Nouvel An…) pour créer des rendez-vous.",
        "Monte tes clips (TikTok, Shorts, Reels) dans l'onglet « Éditeur » ici ou sur l'appli Android : mêmes effets, mêmes polices, mêmes projets.",
    )),
)

@Composable
fun GuideScreen(state: com.superessence.cuttinx.desktop.AppState) {
    var open by remember { mutableStateOf(0) }
    var film by remember { mutableStateOf<com.superessence.cuttinx.core.tutorial.Film?>(null) }
    film?.let { f -> FilmPlayer(state, f) { film = null }; return }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Text("Tutos vidéo", fontSize = 26.sp, fontWeight = FontWeight.Black)
        Hint("Des petits films qui montrent chaque outil en vrai, étape par étape (éditeur, Twitch, identité).")
        Spacer(Modifier.height(12.dp))
        FilmGrid(state) { film = it }
        Spacer(Modifier.height(24.dp))
        Text("Guide d'utilisation", fontSize = 26.sp, fontWeight = FontWeight.Black)
        Hint("Tout pour monter tes vidéos, créer et installer tes overlays Twitch et construire ton image de créateur.")
        Spacer(Modifier.height(12.dp))
        chapters.forEachIndexed { i, ch ->
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(14.dp)).background(Cx.Panel).clickable { open = if (open == i) -1 else i }.padding(16.dp)) {
                Text("${i + 1}. ${ch.title}", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = if (open == i) Cx.Yellow else androidx.compose.ui.graphics.Color.White)
                if (open == i) ch.steps.forEachIndexed { j, s ->
                    Row(Modifier.padding(top = 10.dp)) {
                        Text("${j + 1}", color = Cx.Yellow, fontWeight = FontWeight.Black, modifier = Modifier.width(26.dp))
                        Text(s, fontSize = 14.sp, lineHeight = 20.sp)
                    }
                }
            }
        }
    }
}
