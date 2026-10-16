package com.superessence.cuttinx.ui.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.superessence.cuttinx.core.anim.KeyframeEvaluator
import com.superessence.cuttinx.core.effects.*
import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.util.Ids
import com.superessence.cuttinx.ui.components.*
import com.superessence.cuttinx.ui.theme.Cx
import com.superessence.cuttinx.ui.theme.LocalCx

@UnstableApi
@Composable
fun EffectParams(vm: EditorViewModel, c: Clip, fx: EffectInstance, audio: Boolean) {
    val def = EffectRegistry[fx.effectId] ?: return
    val pos = rememberPos(vm)
    val rel = (pos - c.startUs).coerceIn(0, c.durationUs)
    def.params.forEach { pd ->
        val prop = Prop.effect(fx.id, pd.key)
        val base = fx.params[pd.key] ?: pd.default
        val v = KeyframeEvaluator.evaluate(c.keyframes[prop], rel, base)
        fun setBase(nv: Double) = vm.updateClip(def.name, "fx_${fx.id}_${pd.key}", c.id) { cl ->
            if (audio) cl.copy(audio = cl.audio.copy(effects = cl.audio.effects.map { if (it.id == fx.id) it.copy(params = it.params + (pd.key to nv)) else it }))
            else cl.copy(effects = cl.effects.map { if (it.id == fx.id) it.copy(params = it.params + (pd.key to nv)) else it })
        }
        when (pd.type) {
            ParamType.TOGGLE -> LabeledSwitch(pd.label, v > 0.5, { setBase(if (it) 1.0 else 0.0) })
            ParamType.CHOICE -> { Text(pd.label, style = MaterialTheme.typography.bodySmall); ChoiceChips(pd.choices.indices.toList(), v.toInt(), { pd.choices[it] }, { setBase(it.toDouble()) }) }
            else -> ValueSlider(pd.label, v.toFloat(), pd.min.toFloat()..pd.max.toFloat(), { nv ->
                if (!audio && c.keyframes[prop]?.isEmpty == false) vm.edit("Image clé", "kf_$prop") { p -> com.superessence.cuttinx.core.timeline.TimelineEditor.setKeyframe(p, c.id, prop, rel, nv.toDouble()) } else setBase(nv.toDouble())
            }, default = pd.default.toFloat(), unit = pd.unit.let { if (it.isNotEmpty()) " $it" else "" }, decimals = if (pd.max - pd.min > 50) 0 else 2,
                onKeyframe = if (audio) null else ({ vm.toggleKeyframe(prop, v) }), hasKeyframe = c.keyframes[prop]?.keyframes?.any { kotlin.math.abs(it.timeUs - rel) < 30_000 } == true)
        }
    }
}

@UnstableApi
@Composable
fun AppliedEffects(vm: EditorViewModel, c: Clip, audio: Boolean) {
    val list = if (audio) c.audio.effects else c.effects
    var open by remember(c.id) { mutableStateOf<String?>(list.lastOrNull()?.id) }
    if (list.isEmpty()) { Text("Aucun effet appliqué.", color = LocalCx.current.dim, style = MaterialTheme.typography.bodySmall); return }
    list.forEachIndexed { i, fx ->
        val def = EffectRegistry[fx.effectId]
        Column(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp)).background(LocalCx.current.panel2).padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(fx.enabled, { en -> vm.updateClip("Effet activé", id = c.id) { cl -> if (audio) cl.copy(audio = cl.audio.copy(effects = cl.audio.effects.map { if (it.id == fx.id) it.copy(enabled = en) else it })) else cl.copy(effects = cl.effects.map { if (it.id == fx.id) it.copy(enabled = en) else it }) } })
                Text(def?.name ?: fx.effectId, Modifier.weight(1f).clickable { open = if (open == fx.id) null else fx.id }, fontWeight = FontWeight.SemiBold)
                if (!audio) {
                    IconButton({ vm.updateClip("Ordre des effets", id = c.id) { cl -> cl.copy(effects = cl.effects.toMutableList().also { l -> if (i > 0) { val x = l.removeAt(i); l.add(i - 1, x) } }) } }, Modifier.size(32.dp), enabled = i > 0) { Icon(Icons.Default.ArrowUpward, "Monter", Modifier.size(16.dp)) }
                }
                IconButton({ vm.updateClip("Retirer l'effet", id = c.id) { cl -> if (audio) cl.copy(audio = cl.audio.copy(effects = cl.audio.effects.filterNot { it.id == fx.id })) else cl.copy(effects = cl.effects.filterNot { it.id == fx.id }, keyframes = cl.keyframes.filterKeys { k -> !k.startsWith("fx:${fx.id}:") }) } }, Modifier.size(32.dp)) { Icon(Icons.Default.Delete, "Retirer", Modifier.size(16.dp)) }
                IconButton({ open = if (open == fx.id) null else fx.id }, Modifier.size(32.dp)) { Icon(if (open == fx.id) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null) }
            }
            if (open == fx.id) EffectParams(vm, c, fx, audio)
        }
    }
}

@UnstableApi
@Composable
fun EffectsPanel(vm: EditorViewModel, sel: Clip?) {
    val cats = EffectRegistry.video().map { it.category }.distinct()
    var cat by remember { mutableStateOf(cats.first()) }
    if (sel != null && vm.current.findClip(sel.id)?.first?.kind != TrackKind.AUDIO) {
        SectionTitle("Effets appliqués à « ${sel.name.ifBlank { "l'élément" }} »")
        AppliedEffects(vm, sel, audio = false)
    } else InfoCard("Aucun élément sélectionné : l'effet choisi sera ajouté sur un calque d'effets (appliqué à toute l'image sous la tête de lecture).")
    SectionTitle("Bibliothèque d'effets")
    ChoiceChips(cats, cat, { it.label }, { cat = it })
    val list = EffectRegistry.video().filter { it.category == cat }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
        list.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { d ->
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(LocalCx.current.panel2).clickable { vm.addEffect(d.id) }.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.AutoFixHigh, null, tint = if (d.animated) Cx.Yellow else Color.White, modifier = Modifier.size(20.dp))
                        Text(d.name, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 2)
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    Text("✦ jaune : effet animé automatiquement. Tous les paramètres sont réglables et animables par images clés.", style = MaterialTheme.typography.labelSmall, color = LocalCx.current.dim, modifier = Modifier.padding(top = 6.dp))
}

@UnstableApi
@Composable
fun FiltersPanel(vm: EditorViewModel, sel: Clip?) {
    val user by vm.c.presets.list.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var saveDialog by remember { mutableStateOf(false) }
    var renameId by remember { mutableStateOf<String?>(null) }
    val exportDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if (uri != null) runCatching { ctx.contentResolver.openOutputStream(uri)!!.use { it.write(vm.c.presets.exportText().toByteArray()) } }.onSuccess { vm.message.value = "Préréglages exportés" } }
    val importDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) runCatching { val n = vm.c.presets.importText(ctx.contentResolver.openInputStream(uri)!!.bufferedReader().readText()); vm.message.value = "$n préréglage(s) importé(s)" }.onFailure { vm.message.value = it.message } }
    if (sel == null) InfoCard("Sélectionnez un plan pour lui appliquer un filtre, ou appliquez-le à tous les plans.")
    SectionTitle("Filtres CUTTINX")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Presets.builtIn.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { pr -> PresetTile(pr, Modifier.weight(1f), onClick = { vm.applyPreset(pr) }, onLong = { vm.applyPresetToAll(pr) }) }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    Text("Appui long : appliquer à tous les plans.", style = MaterialTheme.typography.labelSmall, color = LocalCx.current.dim)
    SectionTitle("Mes préréglages", action = {
        Row {
            TextButton({ importDoc.launch(arrayOf("application/json", "*/*")) }) { Text("Importer") }
            TextButton({ exportDoc.launch("cuttinx-presets.json") }, enabled = user.isNotEmpty()) { Text("Exporter") }
        }
    })
    if (sel != null && sel.effects.isNotEmpty()) OutlinedButton({ saveDialog = true }) { Icon(Icons.Default.Save, null); Text(" Enregistrer les effets de ce plan") }
    user.forEach { pr ->
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(10.dp)).background(LocalCx.current.panel2).clickable { vm.applyPreset(pr) }.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(pr.name, Modifier.weight(1f)); Text("${pr.effects.size} effet(s)", color = LocalCx.current.dim, style = MaterialTheme.typography.labelSmall)
            IconButton({ renameId = pr.id }) { Icon(Icons.Default.Edit, "Renommer", Modifier.size(16.dp)) }
            IconButton({ vm.c.presets.duplicate(pr.id) }) { Icon(Icons.Default.ContentCopy, "Dupliquer", Modifier.size(16.dp)) }
            IconButton({ vm.c.presets.delete(pr.id) }) { Icon(Icons.Default.Delete, "Supprimer", Modifier.size(16.dp)) }
        }
    }
    if (saveDialog) TextInputDialog("Nom du préréglage", "Mon look", { saveDialog = false }) { vm.savePresetFromSelection(it); saveDialog = false }
    renameId?.let { id -> TextInputDialog("Renommer", user.firstOrNull { it.id == id }?.name ?: "", { renameId = null }) { vm.c.presets.rename(id, it); renameId = null } }
}

@Composable
private fun PresetTile(pr: EffectPreset, modifier: Modifier, onClick: () -> Unit, onLong: () -> Unit) {
    // colour swatch derived from the preset's look parameters
    val adj = pr.effects.firstOrNull { it.effectId == "adjust" }?.params
    val temp = (adj?.get("temperature") ?: 0.0).toFloat()
    val col = Color(0.35f + temp * 0.3f, 0.35f + (adj?.get("tint")?.toFloat() ?: 0f) * -0.2f, 0.4f - temp * 0.3f)
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(col).then(Modifier.combinedClickableCompat2(onClick, onLong)).padding(10.dp)) {
        Text(pr.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
        Text(pr.effects.joinToString { EffectRegistry[it.effectId]?.name ?: "" }, color = Color.White.copy(alpha = 0.7f), fontSize = 9.sp, maxLines = 2)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun Modifier.combinedClickableCompat2(onClick: () -> Unit, onLong: () -> Unit) = this.then(Modifier.combinedClickable(onClick = onClick, onLongClick = onLong))

@UnstableApi
@Composable
fun AdjustPanel(vm: EditorViewModel, c: Clip) {
    val fx = c.effects.firstOrNull { it.effectId == "adjust" }
    if (fx == null) {
        LaunchedEffect(c.id) { vm.updateClip("Ajustements", id = c.id) { it.copy(effects = it.effects + EffectInstance(Ids.new("fx"), "adjust", EffectRegistry["adjust"]!!.defaults())) } }
        CircularProgressIndicator(); return
    }
    EffectParams(vm, c, fx, audio = false)
    TextButton({ vm.updateClip("Réinitialiser les ajustements", id = c.id) { cl -> cl.copy(effects = cl.effects.map { if (it.id == fx.id) it.copy(params = EffectRegistry["adjust"]!!.defaults()) else it }) } }) { Text("Réinitialiser") }
    TextButton({ vm.edit("Ajustements copiés sur tous les plans") { p -> com.superessence.cuttinx.core.timeline.TimelineEditor.mapClips(p) { cl -> if (cl.id != c.id && cl.kind == ClipKind.MEDIA && p.asset(cl.assetId)?.isVisual == true) cl.copy(effects = cl.effects.filterNot { it.effectId == "adjust" } + fx.copy(id = Ids.new("fx"))) else cl } } }) { Text("Copier sur tous les plans") }
}

@UnstableApi
@Composable
fun AdjustLayerPanel(vm: EditorViewModel) {
    InfoCard("Un calque d'effets applique ses effets à toute l'image (toutes les pistes) pendant sa durée. Déplacez-le et étirez-le sur la timeline comme un clip.")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        AccentButton("Calque vide", { vm.addAdjustmentLayer(emptyList()); vm.panel.value = Panel.EFFECTS })
        OutlinedButton({ vm.addAdjustmentLayer(listOf(EffectInstance(Ids.new("fx"), "adjust", EffectRegistry["adjust"]!!.defaults()))); vm.panel.value = Panel.ADJUST }) { Text("Correction couleur") }
    }
    SectionTitle("Avec un filtre")
    ChoiceChips(Presets.builtIn, null, { it.name }, { pr -> vm.select(null); vm.applyPreset(pr) })
}

// ---------------------------------------------------------------------- audio

@UnstableApi
@Composable
fun AudioPanel(vm: EditorViewModel, p: Project, c: Clip) {
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Volume", "Égaliseur", "Effets", "Voix", "IA")
    ChoiceChips(tabs, tabs[tab], { it }, { tab = tabs.indexOf(it) })
    when (tab) {
        0 -> {
            AnimSlider(vm, c, "Volume", Prop.VOLUME, 1.0, 0f..4f) { cl, v -> cl.copy(audio = cl.audio.copy(volume = v)) }
            AnimSlider(vm, c, "Balance G/D", Prop.PAN, 0.0, -1f..1f) { cl, v -> cl.copy(audio = cl.audio.copy(pan = v)) }
            ValueSlider("Fondu d'entrée", c.audio.fadeInUs / 1e6f, 0f..minOf(10f, c.durationUs / 2e6f), { v -> vm.updateClip("Fondu", "fin_${c.id}") { it.copy(audio = it.audio.copy(fadeInUs = (v * 1e6).toLong())) } }, default = 0f, unit = " s", decimals = 1, step = 0.1f)
            ValueSlider("Fondu de sortie", c.audio.fadeOutUs / 1e6f, 0f..minOf(10f, c.durationUs / 2e6f), { v -> vm.updateClip("Fondu", "fout_${c.id}") { it.copy(audio = it.audio.copy(fadeOutUs = (v * 1e6).toLong())) } }, default = 0f, unit = " s", decimals = 1, step = 0.1f)
            LabeledSwitch("Couper le son", c.audio.muted, { m -> vm.updateClip("Muet") { it.copy(audio = it.audio.copy(muted = m)) } })
            if (c.audio.normalizeGainDb != 0.0) Text("Normalisation : %+.1f dB".format(c.audio.normalizeGainDb), color = LocalCx.current.accent, style = MaterialTheme.typography.bodySmall)
            Row { TextButton({ EditorAi.normalize(vm, listOf(c.id)) }) { Text("Normaliser (-14 LUFS)") }; TextButton({ vm.updateClip("Normalisation") { it.copy(audio = it.audio.copy(normalizeGainDb = 0.0)) } }) { Text("Annuler la normalisation") } }
        }
        1 -> {
            LabeledSwitch("Égaliseur", c.audio.eqEnabled, { e -> vm.updateClip("Égaliseur") { it.copy(audio = it.audio.copy(eqEnabled = e)) } })
            val presets = mapOf("Plat" to List(8) { 0.0 }, "Voix claire" to listOf(-4.0, -2.0, 0.0, 1.0, 2.0, 4.0, 3.0, 1.0), "Basses+" to listOf(6.0, 4.0, 2.0, 0.0, 0.0, 0.0, 0.0, 0.0), "Brillance" to listOf(0.0, 0.0, 0.0, 0.0, 1.0, 2.0, 4.0, 6.0), "Radio" to listOf(-12.0, -6.0, 0.0, 3.0, 4.0, 2.0, -6.0, -12.0), "Podcast" to listOf(-3.0, 1.0, -2.0, 0.0, 2.0, 3.0, 1.0, -1.0))
            ChoiceChips(presets.keys.toList(), null, { it }, { name -> vm.updateClip("Égaliseur $name") { cl -> cl.copy(audio = cl.audio.copy(eqEnabled = true, eqBands = cl.audio.eqBands.mapIndexed { i, b -> b.copy(gainDb = presets[name]!![i]) })) } })
            c.audio.eqBands.forEachIndexed { i, b ->
                ValueSlider(if (b.frequencyHz >= 1000) "%.0f kHz".format(b.frequencyHz / 1000) else "%.0f Hz".format(b.frequencyHz), b.gainDb.toFloat(), -15f..15f, { v ->
                    vm.updateClip("Égaliseur", "eq${i}_${c.id}") { cl -> cl.copy(audio = cl.audio.copy(eqEnabled = true, eqBands = cl.audio.eqBands.mapIndexed { j, x -> if (j == i) x.copy(gainDb = v.toDouble()) else x })) }
                }, default = 0f, unit = " dB", decimals = 1)
            }
        }
        2 -> {
            AppliedEffects(vm, c, audio = true)
            SectionTitle("Ajouter")
            ChoiceChips(EffectRegistry.audio().filter { it.category == EffectCategory.AUDIO }, null, { it.name }, { d -> vm.updateClip("Effet ${d.name}") { it.copy(audio = it.audio.copy(effects = it.audio.effects + EffectInstance(Ids.new("fx"), d.id, d.defaults()))) } })
        }
        3 -> {
            Text("Effets vocaux appliqués en temps réel (aperçu et export).", style = MaterialTheme.typography.bodySmall, color = LocalCx.current.dim)
            ChoiceChips(EffectRegistry.audio().filter { it.category == EffectCategory.VOICE }, null, { it.name }, { d -> vm.updateClip("Voix ${d.name}") { it.copy(audio = it.audio.copy(effects = it.audio.effects + EffectInstance(Ids.new("fx"), d.id, d.defaults()))) } })
            AppliedEffects(vm, c, audio = true)
        }
        else -> {
            Text("Traitements locaux : un nouveau fichier est créé, l'original reste intact.", style = MaterialTheme.typography.bodySmall, color = LocalCx.current.dim)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton({ EditorAi.processAudio(vm, c.id, "denoise") }) { Text("Réduire le bruit") }
                OutlinedButton({ EditorAi.audioReport(vm, c.id); vm.panel.value = Panel.AI }) { Text("Analyser") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton({ EditorAi.processAudio(vm, c.id, "voice") }) { Text("Isoler la voix") }
                OutlinedButton({ EditorAi.processAudio(vm, c.id, "karaoke") }) { Text("Retirer la voix") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton({ EditorAi.detectSilences(vm, c.id) }) { Text("Couper les silences") }
                OutlinedButton({ EditorAi.beats(vm, c.id); vm.panel.value = Panel.AI }) { Text("Détecter le rythme") }
            }
            InfoCard("Isolation/suppression de voix : méthode spectrale locale (efficace sur les musiques stéréo avec voix centrée). Elle n'égale pas les modèles neuronaux lourds.")
        }
    }
}

@UnstableApi
@Composable
fun MixerPanel(vm: EditorViewModel, p: Project) {
    val tracks = p.tracks.filter { it.kind == TrackKind.AUDIO || it.kind == TrackKind.VIDEO }
    if (tracks.isEmpty()) return InfoCard("Ajoutez des médias pour mixer le son.")
    tracks.forEach { t ->
        Column(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp)).background(LocalCx.current.panel2).padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(trackColor(t)))
                Text("  ${t.name}", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                IconToggleButton(t.muted, { m -> vm.updateTrack(t.id) { it.copy(muted = m) } }) { Icon(if (t.muted) Icons.Default.VolumeOff else Icons.Default.VolumeUp, "Muet", tint = if (t.muted) Cx.Danger else Color.White) }
            }
            ValueSlider("Volume", t.volume.toFloat(), 0f..2f, { v -> vm.edit("Volume de piste", "tv_${t.id}") { pr -> com.superessence.cuttinx.core.timeline.TimelineEditor.updateTrack(pr, t.id) { it.copy(volume = v.toDouble()) } } }, default = 1f)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton({ EditorAi.normalize(vm, p.allClips.filter { c -> p.asset(c.assetId)?.hasAudio == true }.map { it.id }) }) { Text("Normaliser tout") }
        OutlinedButton({ vm.addTrack(TrackKind.AUDIO, AudioRole.AMBIENCE) }) { Text("+ Piste d'ambiance") }
    }
    vm.selectedClip()?.let { s -> if (p.asset(s.assetId)?.hasAudio == true) TextButton({ EditorAi.audioReport(vm, s.id); vm.panel.value = Panel.AI }) { Text("Crêtes et saturations de « ${s.name} »") } }
}
