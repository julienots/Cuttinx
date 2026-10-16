package com.superessence.cuttinx.ui.help

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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

private val topics = listOf(
    "Premiers pas" to "1. Créez un projet et choisissez son format (9:16 pour les stories, 16:9 pour YouTube…).\n2. Ajoutez vos vidéos avec « Média ».\n3. Faites glisser la timeline pour vous déplacer ; pincez pour zoomer.\n4. Touchez un plan pour le sélectionner, puis utilisez la barre d'outils du bas.\n5. Exportez avec le bouton jaune : MP4 sans filigrane.",
    "Couper et assembler" to "Placez la tête de lecture (ligne blanche) puis « Diviser ». Sélectionnez un plan et tirez ses poignées jaunes pour le raccourcir. Appui long sur un plan puis glisser pour le déplacer (y compris vers une autre piste). Le magnétisme aligne les bords. « Multi » permet de sélectionner plusieurs éléments pour les déplacer, grouper ou fusionner.",
    "Transitions" to "Touchez le petit carré blanc entre deux plans de la piste principale. Réglez la durée, la direction et la courbe. « Appliquer à toutes les coupes » traite tout le montage.",
    "Images clés" to "Sélectionnez un élément, placez la tête de lecture, touchez le losange à côté d'une valeur (position, échelle, rotation, opacité, volume, paramètres d'effet, masque). Déplacez la tête de lecture et changez la valeur : une animation est créée. Le panneau « Images clés » permet de choisir l'interpolation (linéaire, douce, Bézier…).",
    "Texte et sous-titres" to "« Texte » ajoute un titre : modifiez police, couleur, contour, ombre, fond, dégradé et animations. « Sous-titres » permet d'ajouter, importer (SRT/VTT), corriger la synchronisation et exporter. Les sous-titres automatiques utilisent un modèle vocal local à télécharger une fois.",
    "Audio" to "Sélectionnez un clip avec du son puis « Audio » : volume, fondus, balance, égaliseur 8 bandes, compresseur, limiteur, réverbération, écho, effets de voix. « Voix off » enregistre au micro en lisant le montage. L'espace « Audio » offre un mixage par piste.",
    "Compositing" to "« Incrustation » ajoute une vidéo/image au-dessus. Sur un plan : « Incrustation » (fond vert) avec tolérance et suppression des franges, « Masque » (rectangle, ellipse, linéaire, bande) avec contour progressif, « Opacité ».",
    "CUTTINX AI LAB" to "Tous les outils tournent sur le téléphone : silences, scènes, moments forts, rythme, réduction de bruit, séparation voix/musique (méthode spectrale), recadrage automatique, suivi de sujet, détourage d'image, titres, résumé, suggestions, montage automatique. Aucun service payant.",
    "Sauvegarde et récupération" to "Le projet est enregistré automatiquement. En cas de fermeture inattendue, l'accueil propose de récupérer les modifications. Les fichiers d'origine ne sont jamais modifiés. Exportez un projet (.cuttinx) pour le transférer.",
    "Performances" to "Si l'aperçu saccade, baissez la qualité d'aperçu dans les Paramètres (l'export garde la résolution demandée). Les très longues vidéos 4K et de nombreuses pistes vidéo simultanées dépendent des capacités du décodeur du téléphone.",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(onBack: () -> Unit, startTutorial: () -> Unit) {
    var open by remember { mutableStateOf<Int?>(0) }
    Scaffold(topBar = { TopAppBar(title = { Text("Aide") }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } }) }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            Button(startTutorial, Modifier.fillMaxWidth()) { Text("Ouvrir le projet d'exemple guidé") }
            SectionTitle("Guides")
            topics.forEachIndexed { i, (t, body) ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(LocalCx.current.panel2).clickable { open = if (open == i) null else i }.padding(12.dp)) {
                    Text(t, fontWeight = FontWeight.Bold)
                    if (open == i) Text(body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                }
            }
            Spacer(Modifier.height(30.dp))
        }
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
