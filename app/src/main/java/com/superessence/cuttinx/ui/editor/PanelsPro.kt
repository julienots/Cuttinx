package com.superessence.cuttinx.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.superessence.cuttinx.core.autoedit.ProTools
import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.ui.components.*
import com.superessence.cuttinx.ui.theme.LocalCx

/** Preview-only framing guides: platform UI safe zones and composition grids. */
enum class PreviewGuides(val label: String) {
    NONE("Aucun"), THIRDS("Grille des tiers"), TIKTOK("Zones TikTok"), REELS("Zones Reels"), SHORTS("Zones Shorts"), TITLE_SAFE("Titre sûr 90 %"),
}

/** One-tap creator tools: everything they produce stays editable (clips, keyframes, animations). */
@UnstableApi
@Composable
fun ProToolsPanel(vm: EditorViewModel, p: Project, sel: Clip?) {
    val cx = LocalCx.current
    val guides by vm.guides.collectAsStateWithLifecycle()
    val beats by vm.beats.collectAsStateWithLifecycle()
    val main = p.tracks.firstOrNull { it.kind == TrackKind.VIDEO }
    var handleDialog by remember { mutableStateOf(false) }
    var hookDialog by remember { mutableStateOf(false) }

    @Composable
    fun Tool(title: String, desc: String, enabled: Boolean = true, onClick: () -> Unit) {
        Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(cx.panel2)
            .clickable(enabled = enabled, onClick = onClick).padding(12.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = if (enabled) MaterialTheme.colorScheme.onSurface else cx.dim)
            Text(desc, style = MaterialTheme.typography.labelSmall, color = cx.dim)
        }
    }

    Text("CUTTINX PRO — la boîte à outils des créateurs. Chaque action s'annule et reste modifiable.", color = cx.dim, style = MaterialTheme.typography.bodySmall)
    SectionTitle("Cadrage (aperçu seulement)")
    ChoiceChips(PreviewGuides.entries.toList(), guides, { it.label }, { vm.guides.value = it })

    SectionTitle("Rétention & accroche")
    Tool("Accroche d'ouverture", "Gros titre animé sur les 2,5 premières secondes pour stopper le scroll") { hookDialog = true }
    Tool("Barre de progression", "Barre qui avance pendant toute la vidéo (incite à regarder jusqu'au bout)", p.durationUs > 0) {
        vm.edit("Barre de progression") { ProTools.progressBar(it) }
    }
    Tool("Filigrane @compte", "Votre pseudo discret sur toute la durée", p.durationUs > 0) { handleDialog = true }
    Tool("Sous-titres créateur", "Style viral : majuscules, contour épais, jaune, apparition Pop", p.allClips.any { it.kind == ClipKind.SUBTITLE }) {
        vm.edit("Sous-titres créateur") { ProTools.creatorCaptions(it) }
    }

    SectionTitle("Rythme & mouvement")
    Tool("Zoom sur les temps", if (beats.isEmpty()) "Détectez d'abord le rythme dans CUTTINX AI LAB" else "Zoom « punch » sur chaque temps de la musique (élément sélectionné)", sel != null && beats.isNotEmpty()) {
        vm.edit("Zoom sur les temps") { ProTools.beatZoom(it, sel!!.id, beats) }
    }
    Tool("Animer tout le montage", "Une animation d'entrée différente sur chaque plan de la piste principale", main != null) {
        vm.edit("Animations automatiques") { ProTools.autoAnimate(it, main!!.id) }
    }
    Tool("Transitions dynamiques", "Fouet, zoom, glitch, tourbillon, flash… en alternance sur chaque coupe", main != null && main.clips.size > 1) {
        vm.edit("Transitions dynamiques") { ProTools.autoTransitions(it, main!!.id) }
    }
    Tool("Ken Burns sur les photos", "Zoom et panoramique lents sur toutes les images fixes") {
        vm.edit("Ken Burns") { ProTools.kenBurnsImages(it) }
    }
    if (sel != null) Tool("Animations de l'élément", "Entrée, sortie, boucle et combos pour « ${sel.name.ifBlank { "élément" }} »") { vm.panel.value = Panel.MOTION }

    SectionTitle("Publication")
    Tool("Format & export pro", "9:16, 1:1, 4:5, 16:9 • jusqu'à 4K 60 i/s • HEVC / AV1 • sans filigrane") { vm.panel.value = Panel.EXPORT }

    if (handleDialog) TextInputDialog("Votre @compte", "@", { handleDialog = false }) { h -> vm.edit("Filigrane") { ProTools.handleWatermark(it, h) }; handleDialog = false }
    if (hookDialog) TextInputDialog("Texte d'accroche", "Attends la fin 😱", { hookDialog = false }) { t -> vm.edit("Accroche") { ProTools.hookTitle(it, t) }; hookDialog = false }
}
