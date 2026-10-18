package com.superessence.cuttinx.desktop.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superessence.cuttinx.core.effects.EffectCategory
import com.superessence.cuttinx.core.effects.EffectKind
import com.superessence.cuttinx.core.effects.EffectRegistry
import com.superessence.cuttinx.core.effects.TransitionRegistry
import com.superessence.cuttinx.core.model.LoopMotion
import com.superessence.cuttinx.core.model.MediaType
import com.superessence.cuttinx.core.model.MotionPreset
import com.superessence.cuttinx.core.resources.Synth
import com.superessence.cuttinx.desktop.Cx
import com.superessence.cuttinx.desktop.engine.EditorModel
import com.superessence.cuttinx.desktop.engine.PcMedia
import com.superessence.cuttinx.desktop.ui.Hint
import com.superessence.cuttinx.desktop.ui.LocalAccent
import com.superessence.cuttinx.desktop.ui.Title
import java.io.File
import javax.sound.sampled.AudioSystem

private enum class Lib(val label: String, val icon: String) {
    MEDIA("Médias", "📁"), TEXT("Textes", "T"), MUSIC("Musique", "🎵"), SFX("Bruitages", "💥"), FX("Effets", "✨"),
    TRANSITIONS("Transitions", "⇄"), MOTION("Animations", "🎬"), VOICE("Voix", "🎙"), PRO("Outils pro", "⚡"),
}

@Composable
fun Library(m: EditorModel, modifier: Modifier) {
    var tab by remember { mutableStateOf(Lib.MEDIA) }
    Row(modifier.background(Cx.Panel)) {
        Column(Modifier.width(74.dp).fillMaxHeight().background(Color(0xFF131419)).verticalScroll(rememberScrollState())) {
            Lib.entries.forEach { l ->
                val sel = l == tab
                Column(Modifier.fillMaxWidth().clickable { tab = l }.background(if (sel) Cx.Panel else Color.Transparent).padding(vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(l.icon, fontSize = 18.sp, color = if (sel) LocalAccent.current else Color.White)
                    Text(l.label, fontSize = 10.sp, color = if (sel) LocalAccent.current else Cx.Dim, maxLines = 1)
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(10.dp)) {
            Text(tab.label, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            when (tab) {
                Lib.MEDIA -> MediaTab(m)
                Lib.TEXT -> TextTab(m)
                Lib.MUSIC -> MusicTab(m)
                Lib.SFX -> SfxTab(m)
                Lib.FX -> FxTab(m)
                Lib.TRANSITIONS -> TransitionsTab(m)
                Lib.MOTION -> MotionTab(m)
                Lib.VOICE -> VoiceTab(m)
                Lib.PRO -> ProTab(m)
            }
        }
    }
}

@Composable
private fun Item(title: String, sub: String? = null, trailing: String = "+", onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(8.dp)).background(Cx.Panel2).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, fontSize = 10.sp, color = Cx.Dim, maxLines = 2, lineHeight = 12.sp)
        }
        Text(trailing, color = LocalAccent.current, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MediaTab(m: EditorModel) {
    val p by m.project.collectAsState()
    Button({ m.importFiles(openFiles("Importer des médias", (PcMedia.videoExt + PcMedia.imageExt + PcMedia.audioExt).toList())) }, Modifier.fillMaxWidth()) { Text("⬆ Importer vidéos, photos, sons") }
    Hint("Les fichiers d'origine ne sont jamais modifiés.")
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(0xFF000000, 0xFFFFFFFF, 0xFFFFCC1F, 0xFFFF3D8B, 0xFF3D8BFF, 0xFF2FB67C).forEach { c ->
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(Color(c)).clickable { m.addColor(c) })
        }
        Text("Fond", Modifier.align(Alignment.CenterVertically), fontSize = 11.sp, color = Cx.Dim)
    }
    Spacer(Modifier.height(6.dp))
    LazyColumn {
        items(p?.media?.filter { !it.id.startsWith("res_") } ?: emptyList(), key = { it.id }) { a ->
            val icon = when (a.type) { MediaType.VIDEO -> "🎞"; MediaType.IMAGE, MediaType.STICKER -> "🖼"; MediaType.AUDIO -> "🎵"; else -> "📄" }
            if (a.missing) Item("⚠ ${a.displayName}", "Introuvable — clique pour choisir le fichier", "↺") {
                openFiles("Retrouver ${a.displayName}", emptyList(), multiple = false).firstOrNull()?.let { m.relink(a, it) }
            } else Item("$icon ${a.displayName}", "${timecode(a.durationUs)}${if (a.width > 0) " · ${a.displayWidth}×${a.displayHeight}" else ""}") { m.addAssetToTimeline(a) }
        }
    }
}

@Composable
private fun TextTab(m: EditorModel) {
    Hint("Ajouté à la tête de lecture. Modifie le texte dans le panneau de droite.")
    LazyColumn {
        items(EditorModel.titles) { t -> Item(t.name, t.sample.replace('\n', ' ')) { m.addText(t, subtitle = t.name.startsWith("Sous-titre")) } }
    }
}

@Composable
private fun MusicTab(m: EditorModel) {
    Hint("Musiques originales générées par CUTTINX, libres de droits pour toutes tes vidéos (YouTube, TikTok, Twitch).")
    LazyColumn {
        items(Synth.musicList) { t -> Item(t.name, "${t.mood} · ${t.bpm} BPM · ${t.seconds} s") { m.addMusic(t.id) } }
    }
}

private var previewClip: javax.sound.sampled.Clip? = null

private fun preview(f: File) = runCatching {
    previewClip?.close()
    val c = AudioSystem.getClip()
    c.open(AudioSystem.getAudioInputStream(f))
    c.start()
    previewClip = c
}

@Composable
private fun SfxTab(m: EditorModel) {
    Hint("▶ pour écouter, + pour l'ajouter à la tête de lecture. Libres de droits.")
    LazyColumn {
        Synth.sfxList.groupBy { it.category }.forEach { (cat, list) ->
            item { Title(cat.uppercase()) }
            items(list) { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("▶", Modifier.clip(RoundedCornerShape(6.dp)).clickable { Thread { preview(m.sfxFile(s.id)) }.start() }.padding(8.dp), color = LocalAccent.current)
                    Box(Modifier.weight(1f)) { Item(s.name) { m.addSfx(s.id) } }
                }
            }
        }
    }
}

@Composable
private fun FxTab(m: EditorModel) {
    val sel by m.selection.collectAsState()
    Hint(if (sel.isEmpty()) "Aucun clip sélectionné : l'effet s'appliquera à tout ce qui est dessous (calque d'effets)." else "S'applique au clip sélectionné. Réglages dans le panneau de droite.")
    val groups = EffectRegistry.all.filter { it.kind == EffectKind.VIDEO && it.category != EffectCategory.INTERNAL && !it.id.startsWith("_") }.groupBy { it.category }
    LazyColumn {
        groups.forEach { (cat, list) ->
            item { Title(cat.label.uppercase()) }
            items(list, key = { it.id }) { d -> Item(d.name, d.description.ifBlank { null }) { m.applyEffect(d.id) } }
        }
    }
}

@Composable
private fun TransitionsTab(m: EditorModel) {
    Hint("Sélectionne le clip qui ARRIVE puis choisis une transition. « Partout » l'applique à toutes les coupes.")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton({ m.removeTransition() }) { Text("Retirer", fontSize = 12.sp) }
    }
    LazyColumn {
        items(TransitionRegistry.all, key = { it.id }) { d ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Item(d.name, "%.1f s".format(d.defaultDurationUs / 1e6)) { m.applyTransition(d.id) } }
                TextButton({ m.applyTransition(d.id, all = true) }) { Text("Partout", fontSize = 11.sp) }
            }
        }
    }
}

@Composable
private fun MotionTab(m: EditorModel) {
    Hint("Animations d'entrée, de sortie et en boucle du clip sélectionné (zoom lent, rebond, glitch…).")
    LazyColumn {
        item { Title("EN BOUCLE (PENDANT TOUT LE CLIP)") }
        items(LoopMotion.entries.filter { it != LoopMotion.NONE }) { l -> Item(l.label) { m.setMotion { it.copy(loop = l) } } }
        MotionPreset.entries.filter { it != MotionPreset.NONE }.groupBy { it.group }.forEach { (g, list) ->
            item { Title(g.uppercase()) }
            items(list) { mp ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(mp.label, Modifier.weight(1f), fontSize = 13.sp)
                    TextButton({ m.setMotion { it.copy(animIn = mp) } }) { Text("Entrée", fontSize = 11.sp) }
                    TextButton({ m.setMotion { it.copy(animOut = mp) } }) { Text("Sortie", fontSize = 11.sp) }
                }
            }
        }
        item { Spacer(Modifier.height(6.dp)); OutlinedButton({ m.setMotion { com.superessence.cuttinx.core.model.ClipMotion() } }) { Text("Retirer les animations") } }
    }
}

@Composable
private fun VoiceTab(m: EditorModel) {
    Hint("Sélectionne un clip avec du son (vidéo, voix off). Tout s'applique en direct, sans abîmer l'original.")
    Spacer(Modifier.height(4.dp))
    Button({ m.applyAudioEffects("Réducteur de bruit de fond", "a_highpass" to mapOf("freq" to 80.0), "a_denoise" to mapOf("amount" to 0.75), "a_gate" to mapOf("threshold" to -50.0)) }, Modifier.fillMaxWidth()) { Text("🔇 Réduire le bruit de fond") }
    OutlinedButton({ m.applyAudioEffects("Voix studio", "a_highpass" to mapOf("freq" to 90.0), "a_deesser" to emptyMap(), "a_compressor" to mapOf("threshold" to -20.0, "ratio" to 3.0), "a_limiter" to emptyMap()) }, Modifier.fillMaxWidth()) { Text("🎙 Voix studio") }
    LazyColumn {
        item { Title("EFFETS DE VOIX") }
        items(EffectRegistry.all.filter { it.kind == EffectKind.AUDIO && it.category == EffectCategory.VOICE }, key = { it.id }) { d -> Item(d.name, d.description.ifBlank { null }) { m.applyAudioEffects(d.name, d.id to emptyMap()) } }
        item { Title("EFFETS AUDIO") }
        items(EffectRegistry.all.filter { it.kind == EffectKind.AUDIO && it.category == EffectCategory.AUDIO }, key = { it.id }) { d -> Item(d.name, d.description.ifBlank { null }) { m.applyAudioEffects(d.name, d.id to emptyMap()) } }
    }
}

@Composable
private fun ProTab(m: EditorModel) {
    var handle by remember { mutableStateOf("@moncompte") }
    var hook by remember { mutableStateOf("ATTENDS LA FIN 😱") }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Hint("Montage en un clic, comme les grands créateurs. Tout reste modifiable et annulable.")
        Item("🔍 Zoom lent sur tout le montage", "Effet Ken Burns doux sur chaque plan") { m.pro("slowzoom") }
        Item("🎬 Animations automatiques", "Une entrée dynamique différente sur chaque plan") { m.pro("autoanim") }
        Item("⇄ Transitions automatiques", "Whip, zoom, glitch… alternés à chaque coupe") { m.pro("autotrans") }
        Item("🖼 Photos animées", "Mouvement lent sur toutes les photos") { m.pro("kenburns") }
        Item("▬ Barre de progression", "Garde les spectateurs jusqu'à la fin") { m.pro("progress") }
        Item("💬 Sous-titres style créateur", "Gros, jaunes, contour noir") { m.pro("captions") }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(hook, { hook = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Titre d'accroche (2 premières secondes)") })
        Item("⚡ Ajouter l'accroche") { m.pro("hook", hook) }
        OutlinedTextField(handle, { handle = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Ton pseudo") })
        Item("© Pseudo en filigrane") { m.pro("watermark", handle) }
    }
}
