package com.superessence.cuttinx.ui.editor

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.superessence.cuttinx.core.analysis.SubjectAnalysis
import com.superessence.cuttinx.core.autoedit.AutoStyle
import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.project.ExportPlanner
import com.superessence.cuttinx.core.resources.Synth
import com.superessence.cuttinx.core.util.Time
import com.superessence.cuttinx.engine.ExportState
import com.superessence.cuttinx.media.CodecInfo
import com.superessence.cuttinx.ui.components.*
import com.superessence.cuttinx.ui.theme.Cx
import com.superessence.cuttinx.ui.theme.LocalCx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun AiTool(icon: ImageVector, title: String, desc: String, enabled: Boolean = true, onClick: () -> Unit) {
    val cx = LocalCx.current
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp)).background(cx.panel2).clickable(enabled = enabled, onClick = onClick).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (enabled) cx.accent else cx.dim)
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = if (enabled) MaterialTheme.colorScheme.onSurface else cx.dim)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = cx.dim)
        }
    }
}

@UnstableApi
@Composable
fun AiPanel(vm: EditorViewModel, p: Project, sel: Clip?, openProject: (String) -> Unit) {
    val result by EditorAi.result.collectAsStateWithLifecycle()
    var section by remember { mutableStateOf<String?>(null) }
    val selAsset = sel?.let { p.asset(it.assetId) }
    val isVideo = selAsset?.type == MediaType.VIDEO
    val hasAudio = selAsset?.hasAudio == true
    result?.let { r -> AiResultView(vm, p, r, openProject); HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
    when (section) {
        "speech" -> return SpeechSection(vm, p, sel) { section = null }
        "track" -> return TrackingSection(vm, p, sel) { section = null }
        "auto" -> return AutoEditSection(vm, p) { section = null }
        "titles" -> return TitlesSection(vm) { section = null }
    }
    Text("Tout est calculé sur votre téléphone, gratuitement et hors ligne (seuls les modèles vocaux se téléchargent une fois).", style = MaterialTheme.typography.bodySmall, color = LocalCx.current.dim)
    if (sel == null) InfoCard("Sélectionnez un élément de la timeline pour activer les outils qui l'analysent.")
    SectionTitle("Parole")
    AiTool(Icons.Default.Subtitles, "Sous-titres automatiques", "Reconnaissance vocale locale (Vosk), segments modifiables") { section = "speech" }
    SectionTitle("Audio")
    AiTool(Icons.Default.ContentCut, "Détecter et retirer les silences", "Coupe les blancs d'une prise de parole", hasAudio) { EditorAi.detectSilences(vm, sel?.id) }
    AiTool(Icons.Default.GraphicEq, "Réduction de bruit", "Nettoie la voix (nouveau fichier, original intact)", hasAudio) { EditorAi.processAudio(vm, sel?.id, "denoise") }
    AiTool(Icons.Default.RecordVoiceOver, "Séparer voix / musique", "Isole la voix ou crée un instrumental (méthode spectrale)", hasAudio) { EditorAi.processAudio(vm, sel?.id, "voice") }
    AiTool(Icons.Default.MusicOff, "Retirer la voix (karaoké)", "Garde la musique d'un morceau stéréo", hasAudio) { EditorAi.processAudio(vm, sel?.id, "karaoke") }
    AiTool(Icons.Default.Equalizer, "Analyse de la qualité audio", "Volume LUFS, crêtes, saturation, bruit, phase", hasAudio) { EditorAi.audioReport(vm, sel?.id) }
    AiTool(Icons.Default.MusicNote, "Rythme de la musique", "Tempo + temps forts pour caler les coupes", hasAudio) { EditorAi.beats(vm, sel?.id) }
    SectionTitle("Image")
    AiTool(Icons.Default.Movie, "Changements de scène", "Trouve les changements de plan d'une vidéo", isVideo) { EditorAi.detectScenes(vm, sel?.id) }
    AiTool(Icons.Default.Whatshot, "Moments intéressants", "Action, son, netteté, couleurs", isVideo) { EditorAi.highlights(vm, sel?.id) }
    AiTool(Icons.Default.Flag, "Suggérer des points de coupe", "Marqueurs sur scènes et silences", isVideo || hasAudio) { EditorAi.suggestCuts(vm, sel?.id) }
    AiTool(Icons.Default.CropFree, "Recadrage automatique", "Garde le sujet dans le format du projet", selAsset?.isVisual == true) { EditorAi.autoReframe(vm, sel?.id) }
    AiTool(Icons.Default.TrackChanges, "Suivi d'un sujet", "Un texte ou autocollant suit un objet de la vidéo", isVideo) { section = "track" }
    AiTool(Icons.Default.PersonRemove, "Supprimer l'arrière-plan (image)", "Fonds unis ou peu texturés", selAsset?.type == MediaType.IMAGE) { EditorAi.removeBackground(vm, sel?.id) }
    SectionTitle("Texte & montage")
    AiTool(Icons.Default.Title, "Générer des titres", "À partir d'une description") { section = "titles" }
    AiTool(Icons.Default.Summarize, "Résumé de la vidéo", "D'après les sous-titres / la structure") { EditorAi.summary(vm) }
    AiTool(Icons.Default.Lightbulb, "Suggestions de montage", "Conseils appliquables en un geste") { EditorAi.suggestions(vm) }
    AiTool(Icons.Default.FolderSpecial, "Organiser les médias", "Dossiers par type/date, doublons") { EditorAi.organizeMedia(vm) }
    AiTool(Icons.Default.AutoAwesome, "Montage automatique", "Une première version modifiable à partir de vos clips") { section = "auto" }
    if (vm.beats.collectAsStateWithLifecycle().value.isNotEmpty()) Row {
        TextButton({ EditorAi.syncMainTrackToBeats(vm) }) { Text("Caler les coupes sur le rythme") }
        TextButton({ EditorAi.beatMarkers(vm, 1) }) { Text("Marqueurs de temps") }
    }
}

@UnstableApi
@Composable
private fun AiResultView(vm: EditorViewModel, p: Project, r: AiResult, openProject: (String) -> Unit) {
    val cx = LocalCx.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cx.accent.copy(alpha = 0.10f)).padding(10.dp)) {
        when (r) {
            is AiResult.Silences -> {
                Text("${r.ranges.size} silence(s) — %.1f s au total".format(r.totalUs / 1e6), fontWeight = FontWeight.Bold)
                r.ranges.take(8).forEach { Text("• ${Time.format(it.startUs)} → ${Time.format(it.endUs)}", style = MaterialTheme.typography.bodySmall) }
                if (r.ranges.size > 8) Text("…", style = MaterialTheme.typography.bodySmall)
                Row { AccentButton("Retirer", { EditorAi.applySilenceRemoval(vm, r) }, enabled = r.ranges.isNotEmpty()); TextButton({ EditorAi.detectSilences(vm, r.clipId, 6.0) }) { Text("Plus strict") }; TextButton({ EditorAi.detectSilences(vm, r.clipId, 16.0) }) { Text("Plus souple") } }
            }
            is AiResult.Scenes -> {
                Text("${r.cutsSourceUs.size} changement(s) de scène", fontWeight = FontWeight.Bold)
                Text(r.cutsSourceUs.joinToString { Time.format(it, false) }, style = MaterialTheme.typography.bodySmall)
                Row { AccentButton("Couper", { EditorAi.splitAtScenes(vm, r) }, enabled = r.cutsSourceUs.isNotEmpty()); TextButton({ EditorAi.scenesAsMarkers(vm, r) }) { Text("Marqueurs") } }
            }
            is AiResult.Highlights -> {
                Text("Moments forts", fontWeight = FontWeight.Bold)
                r.items.forEach { h -> Text("• ${Time.format(h.startUs, false)}–${Time.format(h.endUs, false)}  score ${(h.score * 100).toInt()}  ${h.reasons.joinToString()}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.clickable { vm.clip(r.clipId)?.let { c -> vm.preview.seekTo(c.startUs + com.superessence.cuttinx.core.anim.SpeedMath.sourceToTimeline(c, h.startUs)) } }) }
                Row { AccentButton("Garder uniquement ces moments", { EditorAi.keepOnlyHighlights(vm, r) }) }
            }
            is AiResult.Beats -> {
                Text("Tempo ≈ %.1f BPM (confiance %d %%)".format(r.result.bpm, (r.result.confidence * 100).toInt()), fontWeight = FontWeight.Bold)
                Text("${r.timelineBeats.size} temps détectés, ${r.result.onsetsUs.size} attaques", style = MaterialTheme.typography.bodySmall)
                Row { AccentButton("Caler les coupes", { EditorAi.syncMainTrackToBeats(vm) }); TextButton({ EditorAi.beatMarkers(vm, 4) }) { Text("Marqueurs (mesures)") } }
            }
            is AiResult.Report -> {
                val rep = r.report
                Text("Qualité audio : ${rep.score}/100", fontWeight = FontWeight.Bold)
                Text("Volume intégré %.1f LUFS • crête %.1f dBFS • plage %.1f LU\nBruit de fond %.0f dB • rapport signal/bruit %.0f dB • silences %.0f %%%s".format(rep.integratedLufs, rep.peakDb, rep.loudnessRange, rep.noiseFloorDb, rep.snrDb, rep.silencePercent, rep.stereoCorrelation?.let { " • corrélation stéréo %.2f".format(it) } ?: ""), style = MaterialTheme.typography.bodySmall)
                if (rep.clipEvents.isNotEmpty()) Text("Saturations : " + rep.clipEvents.take(6).joinToString { Time.format(it.startUs, false) }, color = Cx.Danger, style = MaterialTheme.typography.bodySmall)
                rep.recommendations.forEach { Text("→ $it", style = MaterialTheme.typography.bodySmall) }
                Row { TextButton({ EditorAi.normalize(vm, listOf(r.clipId)) }) { Text("Normaliser") }; TextButton({ vm.updateClip("Limiteur", id = r.clipId) { it.copy(audio = it.audio.copy(effects = it.audio.effects + EffectInstance(com.superessence.cuttinx.core.util.Ids.new("fx"), "a_limiter", mapOf("ceiling" to -1.0, "release" to 80.0)))) } }) { Text("Ajouter un limiteur") } }
            }
            is AiResult.Titles -> {
                Text("Titres proposés (touchez pour ajouter)", fontWeight = FontWeight.Bold)
                r.items.forEach { t -> Text("• $t", Modifier.fillMaxWidth().clickable { vm.addText(t, TextStyle(sizeSp = 60.0, strokeWidth = 4.0), TextAnimation.POP) }.padding(vertical = 4.dp)) }
            }
            is AiResult.Summary -> {
                Text("Résumé", fontWeight = FontWeight.Bold)
                Text(r.text, style = MaterialTheme.typography.bodySmall)
                if (r.points.isNotEmpty()) TextButton({ vm.edit("Marqueurs du résumé") { pr -> r.points.fold(pr) { acc, (t, l) -> com.superessence.cuttinx.core.timeline.TimelineEditor.addMarker(acc, t, l.take(30)) } } }) { Text("Ajouter en marqueurs") }
            }
            is AiResult.SuggestionList -> {
                Text("${r.items.size} suggestion(s)", fontWeight = FontWeight.Bold)
                r.items.forEach { s -> Row(verticalAlignment = Alignment.CenterVertically) { Text("• ${s.message}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall); s.actionLabel?.let { TextButton({ EditorAi.applySuggestion(vm, s) }) { Text(it) } } } }
            }
            is AiResult.Transcript -> {
                Text("Transcription : ${r.cues.size} segments", fontWeight = FontWeight.Bold)
                Text(r.cues.take(5).joinToString("\n") { it.text.replace('\n', ' ') }, style = MaterialTheme.typography.bodySmall)
                TextButton({ vm.panel.value = Panel.SUBTITLES }) { Text("Relire et corriger →") }
            }
            is AiResult.AutoEdit -> {
                var rejected by remember(r.request) { mutableStateOf(setOf<String>()) }
                Text("Proposition : ${r.proposal.project.allClips.size} éléments, ${Time.format(r.proposal.project.durationUs, false)}", fontWeight = FontWeight.Bold)
                Text("Décochez ce que vous refusez puis régénérez :", style = MaterialTheme.typography.bodySmall)
                r.proposal.decisions.forEach { d ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(d.id !in rejected, { ok -> rejected = if (ok) rejected - d.id else rejected + d.id })
                        Column { Text(d.label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold); Text(d.detail, style = MaterialTheme.typography.labelSmall, color = cx.dim) }
                    }
                }
                Row { TextButton({ EditorAi.rebuildAutoEdit(r, rejected) }) { Text("Régénérer") }; AccentButton("Créer le projet", { val id = EditorAi.acceptAutoEdit(vm, r); vm.close(); openProject(id) }) }
            }
        }
        TextButton({ EditorAi.result.value = null }) { Text("Fermer le résultat") }
    }
}

@UnstableApi
@Composable
private fun SpeechSection(vm: EditorViewModel, p: Project, sel: Clip?, back: () -> Unit) {
    var refresh by remember { mutableIntStateOf(0) }
    val task by vm.task.collectAsStateWithLifecycle()
    LaunchedEffect(task) { refresh++ }
    var maxChars by remember { mutableIntStateOf(32) }
    TextButton(back) { Text("← Outils") }
    Text("Choisissez la langue parlée. Le modèle (~40 Mo, licence Apache 2.0, projet Vosk) se télécharge une seule fois ; ensuite la reconnaissance se fait hors ligne.", style = MaterialTheme.typography.bodySmall)
    val targets = if (sel != null && p.asset(sel.assetId)?.hasAudio == true) listOf(sel.id) else p.tracks.filter { it.kind == TrackKind.VIDEO || (it.kind == TrackKind.AUDIO && it.audioRole == AudioRole.VOICE_OVER) }.flatMap { it.clips }.filter { p.asset(it.assetId)?.hasAudio == true && !it.audio.muted }.map { it.id }
    Text(if (sel != null) "Cible : élément sélectionné" else "Cible : ${targets.size} élément(s) avec du son", color = LocalCx.current.accent, style = MaterialTheme.typography.bodySmall)
    ValueSlider("Caractères max. par ligne", maxChars.toFloat(), 16f..48f, { maxChars = it.toInt() }, decimals = 0, step = 1f)
    key(refresh) {
        vm.c.speech.models.forEach { m ->
            val installed = vm.c.speech.isInstalled(m)
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(10.dp)).background(LocalCx.current.panel2).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(m.language, fontWeight = FontWeight.SemiBold); Text(if (installed) "Installé" else "${m.sizeMb} Mo à télécharger • ${m.license}", style = MaterialTheme.typography.labelSmall, color = LocalCx.current.dim) }
                if (installed) {
                    AccentButton("Transcrire", { EditorAi.autoSubtitles(vm, m, targets, maxChars) }, enabled = targets.isNotEmpty())
                    IconButton({ vm.c.speech.delete(m); refresh++ }) { Icon(Icons.Default.Delete, "Supprimer le modèle") }
                } else OutlinedButton({ EditorAi.downloadModel(vm, m) }) { Text("Télécharger") }
            }
        }
    }
    InfoCard("La reconnaissance automatique peut se tromper (noms propres, bruit, accents) : relisez toujours les sous-titres dans l'onglet Sous-titres.")
}

@UnstableApi
@Composable
private fun TrackingSection(vm: EditorViewModel, p: Project, sel: Clip?, back: () -> Unit) {
    val scope = rememberCoroutineScope()
    TextButton(back) { Text("← Outils") }
    if (sel == null || p.asset(sel.assetId)?.type != MediaType.VIDEO) { InfoCard("Sélectionnez la vidéo contenant le sujet à suivre."); return }
    val followers = p.allClips.filter { it.id != sel.id && (it.text != null || p.findClip(it.id)?.first?.let { t -> t.kind == TrackKind.VIDEO && t.id != p.tracks.first { x -> x.kind == TrackKind.VIDEO }.id } == true) && it.startUs < sel.endUs && sel.startUs < it.endUs }
    var follower by remember { mutableStateOf(followers.firstOrNull()?.id) }
    var box by remember { mutableStateOf(SubjectAnalysis.Box(0.5, 0.5, 0.25, 0.25)) }
    Text("1. Placez la tête de lecture au début du suivi, 2. réglez la zone du sujet, 3. choisissez l'élément qui doit le suivre.", style = MaterialTheme.typography.bodySmall)
    if (followers.isEmpty()) InfoCard("Ajoutez d'abord un texte ou un autocollant qui chevauche cette vidéo dans le temps.")
    else ChoiceChips(followers, followers.firstOrNull { it.id == follower }, { it.name.ifBlank { it.text?.text ?: "Élément" }.take(18) }, { follower = it.id })
    TextButton({ scope.launch { withContext(Dispatchers.IO) { EditorAi.detectSubjectBox(vm, sel.id) }?.let { box = it } } }) { Text("Détecter le sujet automatiquement") }
    ValueSlider("Centre X", box.cx.toFloat(), 0f..1f, { box = box.copy(cx = it.toDouble()) })
    ValueSlider("Centre Y", box.cy.toFloat(), 0f..1f, { box = box.copy(cy = it.toDouble()) })
    ValueSlider("Taille", box.w.toFloat(), 0.05f..0.8f, { box = box.copy(w = it.toDouble(), h = it.toDouble()) })
    AccentButton("Lancer le suivi", { follower?.let { EditorAi.track(vm, sel.id, it, box) } }, enabled = follower != null)
}

@UnstableApi
@Composable
private fun TitlesSection(vm: EditorViewModel, back: () -> Unit) {
    var desc by remember { mutableStateOf("") }
    TextButton(back) { Text("← Outils") }
    OutlinedTextField(desc, { desc = it }, Modifier.fillMaxWidth(), label = { Text("Décrivez votre vidéo") }, minLines = 2)
    AccentButton("Proposer des titres", { EditorAi.titles(desc) }, enabled = desc.isNotBlank())
    Text("Générateur local par mots-clés et modèles de phrases (pas de serveur, pas de compte).", style = MaterialTheme.typography.labelSmall, color = LocalCx.current.dim)
}

@UnstableApi
@Composable
private fun AutoEditSection(vm: EditorViewModel, p: Project, back: () -> Unit) {
    val scope = rememberCoroutineScope()
    val candidates = p.media.filter { it.type == MediaType.VIDEO || it.type == MediaType.IMAGE }
    var chosen by remember { mutableStateOf(candidates.map { it.id }.toSet()) }
    var style by remember { mutableStateOf(AutoStyle.DYNAMIC) }
    var seconds by remember { mutableFloatStateOf(30f) }
    var music by remember { mutableStateOf<String?>(p.media.firstOrNull { it.type == MediaType.AUDIO }?.id) }
    var title by remember { mutableStateOf("") }
    var aspect by remember { mutableStateOf(p.format.aspect.takeIf { it != AspectPreset.CUSTOM } ?: AspectPreset.PORTRAIT_9_16) }
    TextButton(back) { Text("← Outils") }
    if (candidates.isEmpty()) { InfoCard("Importez d'abord des vidéos ou des photos dans la bibliothèque du projet."); return }
    SectionTitle("Clips")
    candidates.forEach { a -> Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(a.id in chosen, { chosen = if (it) chosen + a.id else chosen - a.id }); Text(a.displayName, style = MaterialTheme.typography.bodySmall, maxLines = 1) } }
    SectionTitle("Style")
    ChoiceChips(AutoStyle.entries.toList(), style, { it.label }, { style = it })
    ValueSlider("Durée visée", seconds, 5f..180f, { seconds = it }, unit = " s", decimals = 0, step = 5f)
    SectionTitle("Musique")
    val audios = p.media.filter { it.type == MediaType.AUDIO }
    ChoiceChips(listOf<String?>(null) + audios.map { it.id } + Synth.musicList.map { "builtin:" + it.id }, music, { id -> when { id == null -> "Aucune"; id.startsWith("builtin:") -> "Musique : " + Synth.musicList.first { it.id == id.removePrefix("builtin:") }.name; else -> audios.first { it.id == id }.displayName } }, { music = it })
    OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Titre d'intro (facultatif)") }, singleLine = true)
    ChoiceChips(listOf(AspectPreset.PORTRAIT_9_16, AspectPreset.LANDSCAPE_16_9, AspectPreset.SQUARE_1_1, AspectPreset.PORTRAIT_4_5), aspect, { it.label }, { aspect = it })
    AccentButton("Analyser et proposer", {
        scope.launch {
            var mid = music
            if (mid != null && mid.startsWith("builtin:")) {
                val a = withContext(Dispatchers.Default) { vm.c.resources.musicAsset(mid!!.removePrefix("builtin:")) }
                vm.edit("Musique") { com.superessence.cuttinx.core.timeline.TimelineEditor.addMedia(it, a) }; mid = a.id
            }
            EditorAi.prepareAutoEdit(vm, chosen.toList(), style, (seconds * 1e6).toLong(), mid, title.ifBlank { null }, aspect)
        }
    }, enabled = chosen.isNotEmpty())
    Text("Le résultat est un nouveau projet entièrement modifiable ; ce projet-ci n'est pas modifié.", style = MaterialTheme.typography.labelSmall, color = LocalCx.current.dim)
}

// ---------------------------------------------------------------------- export

@UnstableApi
@Composable
fun ExportPanel(vm: EditorViewModel, p: Project) {
    val st by vm.c.export.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var s by remember { mutableStateOf(p.export) }
    val caps = remember { CodecInfo.encoderCaps() }
    when (val e = st) {
        is ExportState.Running -> {
            ProgressCard("Export : ${e.stage}", e.progress / 100f, "${e.progress} % • écoulé ${Time.format(e.elapsedMs * 1000, false)}" + (e.etaMs?.let { " • reste ≈ ${Time.format(it * 1000, false)}" } ?: ""), onCancel = { vm.c.export.cancel() })
            InfoCard("Vous pouvez quitter l'écran : l'export continue en arrière-plan (notification).")
            return
        }
        is ExportState.Done -> {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Cx.Ok.copy(alpha = 0.15f)).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Icon(com.superessence.cuttinx.icons.CxIcons.Check, null, tint = LocalCx.current.accent); Spacer(Modifier.width(6.dp)); Text("Vidéo enregistrée", fontWeight = FontWeight.Bold) }
                Text("${e.width}×${e.height} (mesuré sur le fichier) • ${e.videoMime} • ${Time.format(e.durationUs, false)} • %.1f Mo • rendu en ${Time.format(e.elapsedMs * 1000, false)}".format(e.sizeBytes / 1e6), style = MaterialTheme.typography.bodySmall)
                Text(e.displayPath, style = MaterialTheme.typography.labelSmall, color = LocalCx.current.dim)
                e.warnings.forEach { WarnLine(it) }
                Row {
                    TextButton({ runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(e.uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) } }) { Text("Lire") }
                    TextButton({ ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, e.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Partager")) }) { Text("Partager") }
                    TextButton({ vm.c.export.reset() }) { Text("Nouvel export") }
                }
            }
            return
        }
        is ExportState.Failed -> { InfoCard(e.message + "\n\nDétail technique : " + e.technical, icon = Icons.Default.Error, tint = Cx.Danger); TextButton({ vm.c.export.reset() }) { Text("Réessayer") } }
        is ExportState.Cancelled -> { InfoCard("Export annulé, fichier temporaire supprimé."); }
        else -> {}
    }
    SectionTitle("Résolution")
    val maxShort = caps.maxOfOrNull { minOf(it.maxWidth, it.maxHeight) } ?: 1080
    ChoiceChips(ExportPlanner.resolutionPresets.filter { it.first <= maxOf(maxShort, 720) }, ExportPlanner.resolutionPresets.firstOrNull { it.first == s.resolutionShortSide }, { it.second }, { s = s.copy(resolutionShortSide = it.first) })
    SectionTitle("Images par seconde")
    ChoiceChips(ExportPlanner.frameRates, s.frameRate, { "$it" }, { s = s.copy(frameRate = it) })
    SectionTitle("Codec vidéo (encodeurs de cet appareil)")
    ChoiceChips(caps.map { it.codec }, s.videoCodec, { c -> c.label + if (caps.first { it.codec == c }.hardware) "" else " (logiciel)" }, { s = s.copy(videoCodec = it) })
    SectionTitle("Qualité")
    ChoiceChips(ExportQuality.entries.toList(), s.quality, { when (it) { ExportQuality.DRAFT -> "Brouillon"; ExportQuality.STANDARD -> "Standard"; ExportQuality.HIGH -> "Haute"; ExportQuality.MAXIMUM -> "Maximale" } }, { s = s.copy(quality = it, videoBitrate = 0) })
    ValueSlider("Débit vidéo (0 = auto)", s.videoBitrate / 1e6f, 0f..100f, { s = s.copy(videoBitrate = (it * 1e6).toInt()) }, unit = " Mb/s", decimals = 0, step = 1f)
    ChoiceChips(listOf(96_000, 128_000, 192_000, 256_000, 320_000), s.audioBitrate, { "AAC ${it / 1000} kb/s" }, { s = s.copy(audioBitrate = it) })
    OutlinedTextField(s.fileName, { s = s.copy(fileName = it) }, Modifier.fillMaxWidth(), label = { Text("Nom du fichier (sans extension)") }, placeholder = { Text(p.name) }, singleLine = true)
    ChoiceChips(listOf("Movies/Cuttinx", "Movies", "DCIM/Cuttinx"), s.destinationFolder, { it }, { s = s.copy(destinationFolder = it) })
    val r = remember(s, p.format, p.durationUs) { vm.resolveExport(s) }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(LocalCx.current.panel2).padding(10.dp)) {
        Text("Sera produit : ${r.width}×${r.height}, ${r.frameRate} i/s, ${r.videoCodec.label} ${r.videoBitrate / 1_000_000} Mb/s, AAC ${r.audioBitrate / 1000} kb/s, MP4", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
        Text("Durée ${Time.format(p.durationUs, false)} • taille estimée ≈ %.0f Mo".format(r.estimatedBytes / 1e6), style = MaterialTheme.typography.bodySmall, color = LocalCx.current.dim)
        r.warnings.forEach { WarnLine(it) }
        Text("Aucun filigrane, aucune limite de durée.", style = MaterialTheme.typography.labelSmall, color = LocalCx.current.accent)
    }
    Spacer(Modifier.height(8.dp))
    AccentButton("Exporter la vidéo", { vm.startExport(s) }, Modifier.fillMaxWidth(), icon = Icons.Default.Upload, enabled = p.durationUs > 0)
}

@Composable
private fun WarnLine(text: String) = Row(verticalAlignment = Alignment.Top) {
    Icon(com.superessence.cuttinx.icons.CxIcons.Warning, null, Modifier.size(16.dp), tint = Color(0xFFFFB020)); Spacer(Modifier.width(6.dp))
    Text(text, style = MaterialTheme.typography.bodySmall)
}
