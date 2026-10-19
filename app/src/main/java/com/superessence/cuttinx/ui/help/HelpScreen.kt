package com.superessence.cuttinx.ui.help

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.superessence.cuttinx.ui.components.SectionTitle
import com.superessence.cuttinx.ui.theme.LocalCx

private class Topic(val category: String, val title: String, val body: String)

private val topics = listOf(
    // ---------------------------------------------------------------- bases
    Topic("Bases", "Premiers pas", "1. Crée un projet et choisis son format (9:16 pour TikTok / Reels / Shorts, 16:9 pour YouTube, 1:1 ou 4:5 pour Instagram).\n2. Ajoute tes vidéos avec « Média ».\n3. Fais glisser la timeline pour te déplacer ; pince pour zoomer.\n4. Touche un plan pour le sélectionner, puis utilise la barre d'outils du bas.\n5. Exporte avec le bouton jaune : MP4 sans filigrane."),
    Topic("Bases", "Couper et assembler", "Place la tête de lecture (ligne blanche) puis « Diviser ». Sélectionne un plan et tire ses poignées jaunes pour le raccourcir. Appui long sur un plan puis glisser pour le déplacer (y compris vers une autre piste). Le magnétisme aligne les bords. « Multi » permet de sélectionner plusieurs éléments pour les déplacer, grouper ou fusionner."),
    Topic("Bases", "Annuler, historique, sauvegarde", "Les flèches en haut annulent / rétablissent sans limite ; l'horloge ouvre l'historique complet. Le projet est enregistré automatiquement ; après une fermeture inattendue, l'accueil propose de récupérer les modifications. Les fichiers d'origine ne sont jamais modifiés."),
    Topic("Bases", "Formats et export", "« Format » change le cadre du projet à tout moment. À l'export : 480p à 4K, 24 à 60 i/s, H.264 / HEVC / AV1 selon ton téléphone, débit réglable. La vidéo est enregistrée dans Films/Cuttinx."),
    // ---------------------------------------------------------------- animation & effets
    Topic("Animation", "Animations d'entrée, de sortie et en boucle", "Sélectionne un plan, une image, un autocollant ou un texte puis « Animations ». Onglets : Entrée (zoom, tourbillon, pop, rebond, élastique, chute, glissements, fouet, roulade, retournements 3D, glitch, néon…), Sortie, Boucle (pulsation, battement de cœur, zoom choc, Ken Burns, zoom lent, panoramique, flottement, tremblement caméra…) et Combos prêts (Hook TikTok, Cinématique, Énergie, Douceur, Glitch…). « Appliquer à toute la piste » anime tout le montage."),
    Topic("Animation", "Faire un zoom lent (style YouTube)", "Sélectionne le plan > « Animations » > Boucle > « Zoom lent avant » (ou arrière). Règle l'intensité : 1 = 15 % de zoom sur la durée du plan. Pour tout le montage : « Outils pro » > « Zoom lent sur tout le montage »."),
    Topic("Animation", "Images clés", "Sélectionne un élément, place la tête de lecture, touche le losange à côté d'une valeur (position, échelle, rotation, opacité, volume, paramètres d'effet, masque). Déplace la tête de lecture et change la valeur : une animation est créée. Le panneau « Images clés » permet de choisir l'interpolation (linéaire, douce, Bézier…)."),
    Topic("Animation", "Effets vidéo et filtres", "« Effets » ajoute des effets GPU : réglages couleur, flou, netteté, vignettage, grain, glitch, VHS, CRT, néon, flammes, pluie, bokeh, cœurs flottants, reflet d'objectif, datamosh, écran fissuré, onde de choc, tunnel, peinture à l'huile, aquarelle, mosaïque, caméra de surveillance… « Filtres » applique un look complet en un geste. Les paramètres des effets s'animent avec les images clés."),
    Topic("Animation", "Transitions", "Touche le petit carré blanc entre deux plans de la piste principale. Simples (fondu, glissement, zoom, flou), originales (fouet, glitch, tourbillon, rebond, cœur, étoile, horloge, stores, damier, roue, pixelisation, ondulation…). Règle la durée, la direction et la courbe. « Appliquer à toutes les coupes » traite tout le montage."),
    // ---------------------------------------------------------------- texte & son
    Topic("Texte & son", "Texte et sous-titres", "« Texte » ajoute un titre : police, couleur, contour, ombre, fond, dégradé et 24 animations (pop, karaoké, machine à écrire, glitch, néon, élastique…). « Titres » propose des modèles prêts. « Sous-titres » permet d'ajouter, importer (SRT/VTT), recaler et exporter ; les sous-titres automatiques utilisent un modèle vocal local téléchargé une fois."),
    Topic("Texte & son", "Musique, bruitages et jingles", "« Audio » ouvre la bibliothèque : musiques originales libres de droits, et plus de 45 bruitages (whoosh, cymbale inversée, bass drop, impacts, explosion, épée, ba dum tss, disque rayé, sifflets, bonne / mauvaise réponse, roulement de tambour, applaudissements, tonnerre, caisse, bulle, magie, sons 8-bit) et des jingles d'intro, de fin, de suspense et d'impact épique."),
    Topic("Texte & son", "Réduire le bruit de fond", "Sélectionne le clip > « Audio » > onglet « Voix » > « Réduire le bruit de fond » : filtre passe-haut, réducteur de bruit spectral et porte de bruit, en temps réel et sans toucher au fichier. « Voix studio » ajoute en plus un de-esser, un compresseur et un limiteur. L'onglet « IA » propose aussi un débruitage plus poussé qui crée un nouveau fichier."),
    Topic("Texte & son", "Effets de voix", "Onglet « Voix » : robot, téléphone, radio, mégaphone, caverne, 8-bit, hélium, écureuil, enfant, voix grave, géant, monstre, alien, masque sombre, sous l'eau, fantôme, stade, talkie-walkie, et hauteur libre. Ils se combinent et s'appliquent à l'aperçu comme à l'export."),
    Topic("Texte & son", "Mixage et voix off", "Sur un clip : volume, fondus, balance, égaliseur 8 bandes, compresseur, limiteur, réverbération, écho. « Voix off » enregistre au micro en lisant le montage. L'espace « Audio » offre un mixage par piste et la normalisation du volume (LUFS)."),
    // ---------------------------------------------------------------- créateurs
    Topic("Créateurs", "Outils pro", "« Outils pro » (barre du bas) : accroche d'ouverture animée, barre de progression, filigrane @compte, sous-titres style créateur, zoom « punch » sur les temps de la musique, animations et transitions automatiques, zoom lent, Ken Burns sur les photos, et guides de cadrage (grille des tiers, zones TikTok / Reels / Shorts, titre sûr)."),
    Topic("Créateurs", "Recette : montage TikTok dynamique", "1. Format 9:16. 2. Coupe les silences (AI Lab). 3. « Outils pro » > Accroche d'ouverture. 4. Transitions dynamiques + un whoosh sur chaque coupe. 5. Détecte le rythme (AI Lab) puis « Zoom sur les temps ». 6. Sous-titres automatiques puis « Sous-titres créateur ». 7. Barre de progression. 8. Vérifie avec les zones TikTok que rien d'important n'est caché."),
    Topic("Créateurs", "Recette : vidéo YouTube posée", "Format 16:9, zoom lent sur les plans parlés, transitions « Fondu » ou « Flou », bruitages discrets, voix studio + réduction du bruit de fond, jingle d'intro et de fin, titres « Générique » ou « Élégant »."),
    Topic("Créateurs", "Version PC : overlays Twitch et identité", "L'appli PC CUTTINX PRO (Windows) crée tes overlays de live : 31 thèmes (super-héros, geek, anime, Japon, mignon, aesthetic, saisons…), scènes animées « Le live va commencer », « Je reviens », « Pause », « Fin », cadres jeu / caméra / chat, alertes et transitions OBS (toile d'araignée, sakura…). La partie « Identité & DA » génère ta palette, tes polices, tes bios et tes idées de contenu."),
    // ---------------------------------------------------------------- avancé
    Topic("Avancé", "Compositing", "« Incrustation » ajoute une vidéo / image au-dessus. Sur un plan : « Incrustation » (fond vert) avec tolérance et suppression des franges, « Masque » (rectangle, ellipse, linéaire, bande) avec contour progressif, « Opacité »."),
    Topic("Avancé", "CUTTINX AI LAB", "Tous les outils tournent sur le téléphone : silences, scènes, moments forts, rythme, réduction de bruit, séparation voix / musique (méthode spectrale), recadrage automatique, suivi de sujet, détourage d'image, titres, résumé, suggestions, montage automatique. Aucun service payant."),
    Topic("Avancé", "Performances", "Si l'aperçu saccade, baisse la qualité d'aperçu dans les Paramètres (l'export garde la résolution demandée). Les vidéos 4K 60 i/s sont lourdes : l'aperçu peut s'alléger tout seul. Les incrustations vidéo (une vidéo par-dessus une autre) sollicitent davantage le téléphone."),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(onBack: () -> Unit, startTutorial: () -> Unit) {
    var open by remember { mutableStateOf<Topic?>(topics.first()) }
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }
    var tutorial by remember { mutableStateOf(false) }
    var film by remember { mutableStateOf<com.superessence.cuttinx.core.tutorial.Film?>(null) }
    val cx = LocalCx.current
    Box {
        Scaffold(topBar = { TopAppBar(title = { Text("Guide d'utilisation") }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } }) }) { pad ->
            Column(Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ tutorial = true }, Modifier.weight(1f)) { Text("Tutoriel pas à pas") }
                    OutlinedButton(startTutorial, Modifier.weight(1f)) { Text("Projet d'exemple") }
                }
                SectionTitle("Tutos vidéo (${com.superessence.cuttinx.core.tutorial.Tutorials.all.size})")
                Text("Des petits films qui montrent chaque outil en vrai, étape par étape.", style = MaterialTheme.typography.bodySmall, color = cx.dim)
                Spacer(Modifier.height(6.dp))
                FilmGallery { film = it }
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(top = 10.dp), singleLine = true, placeholder = { Text("Rechercher (zoom lent, bruit, transition…)") })
                Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (listOf<String?>(null) + topics.map { it.category }.distinct()).forEach { c ->
                        val sel = c == category
                        Text(c ?: "Tout", Modifier.clip(RoundedCornerShape(50)).background(if (sel) cx.accent else cx.panel2).clickable { category = c }.padding(horizontal = 14.dp, vertical = 7.dp),
                            color = if (sel) cx.onAccent else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelMedium)
                    }
                }
                val q = query.trim().lowercase()
                val visible = topics.filter { (category == null || it.category == category) && (q.isEmpty() || it.title.lowercase().contains(q) || it.body.lowercase().contains(q)) }
                visible.groupBy { it.category }.forEach { (cat, list) ->
                    SectionTitle(cat)
                    list.forEach { t ->
                        val isOpen = open == t || q.isNotEmpty()
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(cx.panel2).clickable { open = if (open == t) null else t }.padding(12.dp)) {
                            Text(t.title, fontWeight = FontWeight.Bold, color = if (isOpen) cx.accent else MaterialTheme.colorScheme.onSurface)
                            if (isOpen) Text(t.body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
                if (visible.isEmpty()) Text("Aucun résultat pour « $query ».", color = cx.dim, modifier = Modifier.padding(top = 16.dp))
                Spacer(Modifier.height(30.dp))
            }
        }
        film?.let { f -> FilmPlayerDialog(f) { film = null } }
        if (tutorial) TutorialOverlay { tutorial = false }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val fontLicenses = remember { ctx.assets.list("fonts/licenses")?.sorted().orEmpty() }
    var show by remember { mutableStateOf<String?>(null) }
    Scaffold(topBar = { TopAppBar(title = { Text("Licences") }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } }) }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            SectionTitle("Ressources intégrées")
            Text("Musiques, bruitages, autocollants, formes, fonds, filtres, effets, transitions et modèles : créations originales générées par le code de CUTTINX (© SUPERESSENCE). Utilisation libre et gratuite dans vos vidéos, y compris commerciales.")
            SectionTitle("Bibliothèques")
            listOf("AndroidX Media3 (lecture, effets, export) — Apache 2.0", "Jetpack Compose / AndroidX — Apache 2.0", "Kotlin, kotlinx.coroutines, kotlinx.serialization — Apache 2.0",
                "Vosk (reconnaissance vocale) — Apache 2.0 ; modèles téléchargés à la demande, Apache 2.0", "JNA — Apache 2.0 / LGPL 2.1 (double licence, utilisée sous Apache 2.0)").forEach { Text("• $it") }
            SectionTitle("Polices")
            Text("Polices Google Fonts redistribuées sous SIL Open Font License 1.1 (Permanent Marker : Apache 2.0). Touchez pour lire la licence.")
            fontLicenses.forEach { f -> Text("• $f", Modifier.clickable { show = f }.padding(vertical = 4.dp), color = LocalCx.current.accent) }
            Spacer(Modifier.height(30.dp))
        }
    }
    show?.let { f -> AlertDialog({ show = null }, title = { Text(f) }, text = { Text(remember(f) { ctx.assets.open("fonts/licenses/$f").bufferedReader().readText() }, Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) }, confirmButton = { TextButton({ show = null }) { Text("Fermer") } }) }
}
