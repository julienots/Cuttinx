package com.superessence.cuttinx.ui.editor

import android.media.MediaPlayer
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.superessence.cuttinx.core.analysis.MediaOrganizer
import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.resources.Synth
import com.superessence.cuttinx.core.util.Time
import com.superessence.cuttinx.ui.components.*
import com.superessence.cuttinx.ui.theme.Cx
import com.superessence.cuttinx.ui.theme.LocalCx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@UnstableApi
@Composable
fun AssetThumb(vm: EditorViewModel, a: MediaAsset, modifier: Modifier) {
    var bmp by remember(a.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(a.id) { if (a.isVisual) bmp = vm.c.thumbnails.frame(a, 0, 120)?.asImageBitmap() }
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFF1E1E24)), contentAlignment = Alignment.Center) {
        bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } ?: Icon(when (a.type) { MediaType.AUDIO -> Icons.Default.MusicNote; MediaType.FONT -> Icons.Default.FontDownload; else -> Icons.Default.Movie }, null, tint = LocalCx.current.dim)
        if (a.durationUs > 0) Text(Time.format(a.durationUs, false), fontSize = 9.sp, color = Color.White, modifier = Modifier.align(Alignment.BottomEnd).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 3.dp))
        if (a.favorite) Icon(Icons.Default.Star, null, tint = Cx.Yellow, modifier = Modifier.align(Alignment.TopStart).size(14.dp))
        if (a.missing || a.tags.contains("décodage-non-supporté")) Icon(Icons.Default.Warning, null, tint = Cx.Danger, modifier = Modifier.align(Alignment.TopEnd).size(16.dp))
    }
}

@UnstableApi
@Composable
fun MediaPanel(vm: EditorViewModel, p: Project) {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(MediaOrganizer.SortKey.DATE) }
    var type by remember { mutableStateOf<MediaType?>(null) }
    var folder by remember { mutableStateOf<String?>(null) }
    var onlyFav by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<MediaAsset?>(null) }
    var relinkId by remember { mutableStateOf<String?>(null) }
    val pickVisual = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { if (it.isNotEmpty()) vm.importMedia(it, addToTimeline = false) }
    val pickDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) vm.importMedia(it, addToTimeline = false) }
    val relink = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> val id = relinkId; if (u != null && id != null) vm.relink(id, u); relinkId = null }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton({ pickVisual.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }) { Text("Photos/vidéos") }
        OutlinedButton({ pickDoc.launch(arrayOf("video/*", "image/*", "audio/*", "application/x-subrip", "text/*", "font/*", "application/octet-stream")) }) { Text("Fichiers") }
        TextButton({ EditorAi.organizeMedia(vm) }) { Text("Ranger") }
    }
    OutlinedTextField(query, { query = it }, placeholder = { Text("Rechercher") }, singleLine = true, modifier = Modifier.fillMaxWidth(), leadingIcon = { Icon(Icons.Default.Search, null) })
    ChoiceChips(listOf<MediaType?>(null, MediaType.VIDEO, MediaType.IMAGE, MediaType.AUDIO, MediaType.FONT), type, { when (it) { null -> "Tout"; MediaType.VIDEO -> "Vidéos"; MediaType.IMAGE -> "Images"; MediaType.AUDIO -> "Audio"; else -> "Polices" } }, { type = it })
    Row(verticalAlignment = Alignment.CenterVertically) {
        ChoiceChips(MediaOrganizer.SortKey.entries.toList(), sort, { when (it) { MediaOrganizer.SortKey.NAME -> "Nom"; MediaOrganizer.SortKey.DATE -> "Date"; MediaOrganizer.SortKey.DURATION -> "Durée"; MediaOrganizer.SortKey.TYPE -> "Type"; MediaOrganizer.SortKey.SIZE -> "Taille" } }, { sort = it }, Modifier.weight(1f))
        IconToggleButton(onlyFav, { onlyFav = it }) { Icon(if (onlyFav) Icons.Default.Star else Icons.Default.StarBorder, "Favoris", tint = Cx.Yellow) }
    }
    val folders = p.media.map { it.folder }.filter { it.isNotBlank() }.distinct().sorted()
    if (folders.isNotEmpty()) ChoiceChips(listOf<String?>(null) + folders, folder, { it ?: "Tous les dossiers" }, { folder = it })
    val missing = p.media.filter { it.missing }
    if (missing.isNotEmpty()) InfoCard("${missing.size} média(s) introuvable(s). Touchez-les pour les relier à un nouveau fichier : le montage est conservé.", icon = Icons.Default.Warning, tint = Cx.Danger)
    val list = MediaOrganizer.sort(MediaOrganizer.search(p.media, query), sort, descending = sort == MediaOrganizer.SortKey.DATE)
        .filter { (type == null || it.type == type) && (folder == null || it.folder == folder) && (!onlyFav || it.favorite) && !it.id.startsWith("res_") || (type == null && folder == null && !onlyFav && query.isBlank() && false) }
    if (list.isEmpty()) Text("Aucun média. Importez-en avec les boutons ci-dessus.", color = LocalCx.current.dim, modifier = Modifier.padding(8.dp))
    list.chunked(4).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
            row.forEach { a ->
                Column(Modifier.weight(1f).clickable { details = a }) {
                    AssetThumb(vm, a, Modifier.fillMaxWidth().aspectRatio(1f))
                    Text(a.displayName, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
    details?.let { a ->
        var folderEdit by remember { mutableStateOf(false) }
        AlertDialog({ details = null }, title = { Text(a.displayName, maxLines = 2) }, text = {
            Column {
                AssetThumb(vm, a, Modifier.fillMaxWidth().height(140.dp))
                val usage = p.allClips.count { it.assetId == a.id }
                listOf(
                    "Type" to a.type.name, "Format" to a.mimeType, "Durée" to (if (a.durationUs > 0) Time.format(a.durationUs) else "—"),
                    "Dimensions" to (if (a.width > 0) "${a.displayWidth}×${a.displayHeight}" + if (a.rotationDegrees != 0) " (rotation ${a.rotationDegrees}°)" else "" else "—"),
                    "Image/s" to (if (a.frameRate > 0) "%.2f".format(a.frameRate) else "—"), "Codec vidéo" to a.videoCodec.ifBlank { "—" }, "Codec audio" to a.audioCodec.ifBlank { "—" },
                    "Audio" to (if (a.hasAudio) "${a.sampleRate} Hz, ${a.channelCount} canal(aux)" else "non"), "Taille" to (if (a.sizeBytes > 0) "%.1f Mo".format(a.sizeBytes / 1e6) else "—"),
                    "Ajouté le" to DateFormat.getDateTimeInstance().format(Date(a.dateAddedMs)), "Utilisations" to "$usage dans la timeline",
                ).forEach { (k, v) -> Row { Text(k, Modifier.width(110.dp), color = LocalCx.current.dim, fontSize = 12.sp); Text(v, fontSize = 12.sp) } }
                if (a.tags.contains("décodage-non-supporté")) InfoCard("Ce téléphone n'a pas de décodeur pour ce codec : la lecture et l'export de ce média échoueront.", tint = Cx.Danger)
            }
        }, confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                Row {
                    if (a.isVisual) TextButton({ vm.importMediaAsset(a, false); details = null }) { Text("Ajouter") }
                    if (a.isVisual) TextButton({ vm.addOverlay(a); details = null }) { Text("Incruster") }
                    if (a.type == MediaType.AUDIO) TextButton({ vm.addResourceAsset(a, AudioRole.MUSIC); details = null }) { Text("Ajouter") }
                    if (vm.primaryId != null && a.isVisual) TextButton({ vm.replaceSelected(a); details = null }) { Text("Remplacer la sélection") }
                }
                Row {
                    TextButton({ vm.updateAsset(a.id) { it.copy(favorite = !it.favorite) }; details = null }) { Text(if (a.favorite) "Retirer des favoris" else "Favori") }
                    TextButton({ folderEdit = true }) { Text("Dossier") }
                    TextButton({ relinkId = a.id; relink.launch(arrayOf("*/*")); details = null }) { Text("Relier") }
                    TextButton({ vm.removeAsset(a.id); details = null }) { Text("Retirer", color = Cx.Danger) }
                }
            }
        })
        if (folderEdit) TextInputDialog("Dossier", a.folder, { folderEdit = false }) { f -> vm.updateAsset(a.id) { it.copy(folder = f) }; folderEdit = false; details = null }
    }
}

@UnstableApi
fun EditorViewModel.importMediaAsset(a: MediaAsset, overlay: Boolean) {
    if (overlay) addOverlay(a) else edit("Ajouter ${a.displayName}") { p ->
        val (p1, main) = com.superessence.cuttinx.core.timeline.TimelineEditor.mainTrack(p)
        com.superessence.cuttinx.core.timeline.TimelineEditor.insertClip(p1, main.id, com.superessence.cuttinx.core.timeline.TimelineEditor.clipFromAsset(a, main.endUs))
    }
}

@UnstableApi
@Composable
fun MusicPanel(vm: EditorViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf<String?>(null) }
    var generating by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) { onDispose { player?.release() } }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) vm.importMedia(it, MediaType.AUDIO) }
    fun preview(id: String, make: () -> MediaAsset) {
        player?.release(); player = null
        if (playing == id) { playing = null; return }
        scope.launch {
            generating = id
            val a = withContext(Dispatchers.Default) { make() }
            generating = null
            player = MediaPlayer.create(ctx, Uri.parse(a.uri))?.apply { start(); setOnCompletionListener { playing = null } }
            playing = id
        }
    }
    fun add(id: String, role: AudioRole, make: () -> MediaAsset) = scope.launch {
        generating = id
        val a = withContext(Dispatchers.Default) { make() }
        generating = null
        vm.addResourceAsset(a, role)
    }
    OutlinedButton({ pickAudio.launch(arrayOf("audio/*")) }) { Icon(Icons.Default.LibraryMusic, null); Text(" Importer une musique / un son") }
    InfoCard("Musiques et bruitages ci-dessous : créations originales générées par CUTTINX, libres d'utilisation dans vos vidéos (y compris commerciales).")
    SectionTitle("Musiques")
    Synth.musicList.forEach { t ->
        ResourceRow(t.name, "${t.mood} • ${t.bpm} BPM • ${t.seconds} s", playing == "m_" + t.id, generating == "m_" + t.id,
            { preview("m_" + t.id) { vm.c.resources.musicAsset(t.id) } }, { add("m_" + t.id, AudioRole.MUSIC) { vm.c.resources.musicAsset(t.id) } })
    }
    SectionTitle("Bruitages")
    Synth.sfxList.groupBy { it.category }.forEach { (cat, l) ->
        Text(cat, style = MaterialTheme.typography.labelMedium, color = LocalCx.current.dim, modifier = Modifier.padding(top = 4.dp))
        l.forEach { s -> ResourceRow(s.name, "", playing == "s_" + s.id, generating == "s_" + s.id, { preview("s_" + s.id) { vm.c.resources.sfxAsset(s.id) } }, { add("s_" + s.id, AudioRole.SFX) { vm.c.resources.sfxAsset(s.id) } }) }
    }
}

@Composable
private fun ResourceRow(name: String, sub: String, playing: Boolean, busy: Boolean, onPreview: () -> Unit, onAdd: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(10.dp)).background(LocalCx.current.panel2).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onPreview) { if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(if (playing) Icons.Default.Stop else Icons.Default.PlayArrow, "Écouter") }
        Column(Modifier.weight(1f)) { Text(name, fontWeight = FontWeight.SemiBold); if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.labelSmall, color = LocalCx.current.dim) }
        IconButton(onAdd, enabled = !busy) { Icon(Icons.Default.AddCircle, "Ajouter", tint = LocalCx.current.accent) }
    }
}

@UnstableApi
@Composable
fun StickersPanel(vm: EditorViewModel) {
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let { u -> vm.importMedia(listOf(u), overlay = true) } }
    OutlinedButton({ pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Importer un autocollant (PNG transparent)") }
    vm.c.resources.stickers.groupBy { it.category }.forEach { (cat, list) ->
        SectionTitle(cat)
        list.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                row.forEach { s ->
                    val bmp = remember(s.id) { vm.c.resources.drawSticker(s.id, 128).asImageBitmap() }
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(Color(0xFF26262C)).clickable { vm.addOverlay(vm.c.resources.stickerAsset(s.id), Transform(scaleX = 0.3, scaleY = 0.3)) }.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Image(bmp, s.name, Modifier.size(52.dp)); Text(s.name, fontSize = 9.sp, maxLines = 1)
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@UnstableApi
@Composable
fun BackgroundPanel(vm: EditorViewModel, p: Project) {
    SectionTitle("Couleur derrière les médias (bandes)")
    ColorPicker(p.format.backgroundColor, { vm.setBackgroundColor(it or 0xFF000000) }, withAlpha = false)
    SectionTitle("Fonds à insérer sur la piste principale")
    vm.c.resources.backgrounds.chunked(4).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
            row.forEach { b ->
                val bmp = remember(b.id) { vm.c.resources.drawBackground(b.id, 72, 128).asImageBitmap() }
                Column(Modifier.weight(1f).clickable { vm.addBackgroundToMain(vm.c.resources.backgroundAsset(b.id, p.format.width, p.format.height)) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(bmp, b.name, Modifier.fillMaxWidth().aspectRatio(9f / 16f).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop); Text(b.name, fontSize = 9.sp, maxLines = 1)
                }
            }
            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
    OutlinedButton({ vm.edit("Plan de couleur") { pr -> val (p1, m) = com.superessence.cuttinx.core.timeline.TimelineEditor.mainTrack(pr); com.superessence.cuttinx.core.timeline.TimelineEditor.insertClip(p1, m.id, com.superessence.cuttinx.core.timeline.TimelineEditor.colorClip(0xFF111111, m.endUs)) } }) { Text("Ajouter un plan de couleur unie") }
}

@UnstableApi
@Composable
fun FormatPanel(vm: EditorViewModel, p: Project) {
    var fill by remember { mutableStateOf<Boolean?>(null) }
    var cw by remember { mutableIntStateOf(p.format.width) }
    var ch by remember { mutableIntStateOf(p.format.height) }
    Text("Actuel : ${p.format.width}×${p.format.height} (${p.format.aspect.label}), ${p.format.frameRate} i/s", color = LocalCx.current.dim)
    SectionTitle("Repositionnement des médias")
    ChoiceChips(listOf<Boolean?>(null, false, true), fill, { when (it) { null -> "Inchangé"; false -> "Adapter (bandes)"; true -> "Remplir (recadrer)" } }, { fill = it })
    SectionTitle("Format")
    val short = minOf(p.format.width, p.format.height)
    ChoiceChips(AspectPreset.entries.filter { it != AspectPreset.CUSTOM }, p.format.aspect, { it.label }, { a -> vm.changeFormat(ProjectFormat.forAspect(a, short, p.format.frameRate).copy(backgroundColor = p.format.backgroundColor), fill) })
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        NumberField("Largeur", cw, { cw = it }, Modifier.weight(1f)); NumberField("Hauteur", ch, { ch = it }, Modifier.weight(1f))
        TextButton({ vm.changeFormat(ProjectFormat.forAspect(AspectPreset.CUSTOM, customW = cw.coerceIn(16, 4096), customH = ch.coerceIn(16, 4096), fps = p.format.frameRate).copy(backgroundColor = p.format.backgroundColor), fill) }) { Text("OK") }
    }
    SectionTitle("Images par seconde")
    ChoiceChips(listOf(24, 25, 30, 50, 60), p.format.frameRate, { "$it" }, { f -> vm.changeFormat(p.format.copy(frameRate = f), null) })
    InfoCard("Après un changement de format, l'outil IA « Recadrage automatique » garde le sujet dans le cadre.")
}

@UnstableApi
@Composable
fun VoicePanel(vm: EditorViewModel) {
    Text("La voix off démarre à la tête de lecture ; le montage est lu pendant l'enregistrement pour caler votre voix.", style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(10.dp))
    AccentButton("Enregistrer", { vm.startRecording() }, icon = Icons.Default.Mic)
    InfoCard("Conseil : utilisez des écouteurs pour éviter que le son du montage soit capté. Ensuite, Audio → IA → « Réduire le bruit » nettoie la prise.")
}

@UnstableApi
@Composable
fun TracksPanel(vm: EditorViewModel, p: Project) {
    p.tracks.forEachIndexed { i, t ->
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(10.dp)).background(LocalCx.current.panel2).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(trackColor(t)))
            Column(Modifier.weight(1f).padding(start = 8.dp)) { Text(t.name, fontWeight = FontWeight.SemiBold, maxLines = 1); Text("${t.clips.size} élément(s)", style = MaterialTheme.typography.labelSmall, color = LocalCx.current.dim) }
            IconButton({ vm.updateTrack(t.id) { it.copy(locked = !it.locked) } }, Modifier.size(34.dp)) { Icon(if (t.locked) Icons.Default.Lock else Icons.Default.LockOpen, "Verrou", Modifier.size(18.dp)) }
            IconButton({ vm.updateTrack(t.id) { it.copy(hidden = !it.hidden) } }, Modifier.size(34.dp)) { Icon(if (t.hidden) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Masquer", Modifier.size(18.dp)) }
            IconButton({ vm.updateTrack(t.id) { it.copy(muted = !it.muted) } }, Modifier.size(34.dp)) { Icon(if (t.muted) Icons.Default.VolumeOff else Icons.Default.VolumeUp, "Muet", Modifier.size(18.dp)) }
            IconButton({ vm.moveTrack(t.id, -1) }, Modifier.size(34.dp), enabled = i > 0) { Icon(Icons.Default.ArrowUpward, "Monter", Modifier.size(18.dp)) }
            IconButton({ vm.removeTrack(t.id) }, Modifier.size(34.dp)) { Icon(Icons.Default.Delete, "Supprimer", Modifier.size(18.dp)) }
        }
    }
    SectionTitle("Ajouter une piste")
    Row(Modifier.horizontalScrollCompat(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("Vidéo/incrustation" to { vm.addTrack(TrackKind.VIDEO) }, "Texte" to { vm.addTrack(TrackKind.TEXT) }, "Sous-titres" to { vm.addTrack(TrackKind.SUBTITLE) }, "Musique" to { vm.addTrack(TrackKind.AUDIO, AudioRole.MUSIC) },
            "Voix off" to { vm.addTrack(TrackKind.AUDIO, AudioRole.VOICE_OVER) }, "Ambiance" to { vm.addTrack(TrackKind.AUDIO, AudioRole.AMBIENCE) }, "Effets" to { vm.addTrack(TrackKind.EFFECT) })
            .forEach { (l, a) -> AssistChip({ a() }, label = { Text(l) }) }
    }
    LabeledSwitch("Piste principale magnétique", vm.c.settings.value.magneticMainTrack, { m -> vm.c.settings.update { it.copy(magneticMainTrack = m) }; com.superessence.cuttinx.core.timeline.TimelineEditor.magneticMainTrack = m }, description = "Supprime automatiquement les trous entre les plans")
}

@UnstableApi
@Composable
fun MarkersPanel(vm: EditorViewModel, p: Project) {
    var label by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(label, { label = it }, placeholder = { Text("Libellé") }, singleLine = true, modifier = Modifier.weight(1f))
        TextButton({ vm.addMarker(label); label = "" }) { Text("Ajouter ici") }
    }
    if (p.markers.isEmpty()) Text("Aucun marqueur.", color = LocalCx.current.dim)
    p.markers.forEach { m ->
        Row(Modifier.fillMaxWidth().clickable { vm.preview.seekTo(m.timeUs) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Bookmark, null, tint = Color(m.color.toInt()))
            Text(" ${Time.format(m.timeUs)}  ${m.label}", Modifier.weight(1f))
            IconButton({ vm.removeMarker(m.id) }) { Icon(Icons.Default.Delete, "Supprimer") }
        }
    }
    if (p.markers.isNotEmpty()) TextButton({ vm.edit("Effacer les marqueurs") { it.copy(markers = emptyList()) } }) { Text("Tout effacer") }
}

@UnstableApi
@Composable
fun HistoryPanel(vm: EditorViewModel) {
    val rev = vm.session.revision.collectAsStateWithLifecycle().value
    val entries = remember(rev) { vm.session.history.entries }
    val redo = remember(rev) { vm.session.history.redoEntries }
    Row { TextButton(vm::undo, enabled = entries.isNotEmpty()) { Text("Annuler") }; TextButton(vm::redo, enabled = redo.isNotEmpty()) { Text("Rétablir") } }
    if (entries.isEmpty()) Text("Aucune modification pour l'instant.", color = LocalCx.current.dim)
    entries.withIndex().reversed().forEach { (i, e) ->
        Row(Modifier.fillMaxWidth().clickable { vm.session.revertTo(i) }.padding(vertical = 6.dp)) {
            Text("${i + 1}. ${e.label}", Modifier.weight(1f)); Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(e.timeMs)), color = LocalCx.current.dim, fontSize = 12.sp)
        }
    }
    Text("Touchez une étape pour revenir à l'état d'avant celle-ci (rétablissable).", style = MaterialTheme.typography.labelSmall, color = LocalCx.current.dim)
}

@UnstableApi
@Composable
fun SubtitlesPanel(vm: EditorViewModel, p: Project) {
    val ctx = LocalContext.current
    val importDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) vm.importSubtitleFile(u) }
    val exportSrt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { u -> if (u != null) vm.exportSubtitles(u, false) }
    val exportVtt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/vtt")) { u -> if (u != null) vm.exportSubtitles(u, true) }
    val subs = p.tracks.filter { it.kind == TrackKind.SUBTITLE }.flatMap { it.sortedClips() }
    Row(Modifier.horizontalScrollCompat(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AssistChip({ vm.addSubtitle() }, label = { Text("+ Ajouter ici") })
        AssistChip({ vm.panel.value = Panel.AI; EditorAi.result.value = null }, label = { Text("Automatiques (IA)") })
        AssistChip({ importDoc.launch(arrayOf("application/x-subrip", "text/*", "*/*")) }, label = { Text("Importer SRT/VTT") })
        AssistChip({ exportSrt.launch("${p.name}.srt") }, label = { Text("Exporter SRT") }, enabled = subs.isNotEmpty())
        AssistChip({ exportVtt.launch("${p.name}.vtt") }, label = { Text("Exporter VTT") }, enabled = subs.isNotEmpty())
    }
    if (subs.isNotEmpty()) {
        SectionTitle("Synchronisation")
        Row(Modifier.horizontalScrollCompat(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(-500_000L, -100_000L, 100_000L, 500_000L).forEach { d -> AssistChip({ vm.shiftSubtitles(d) }, label = { Text("%+.1f s".format(d / 1e6)) }) }
        }
    }
    SectionTitle("${subs.size} segment(s)")
    subs.forEach { c ->
        var text by remember(c.id) { mutableStateOf(c.text?.text ?: "") }
        Column(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp)).background(if (c.id in vm.selection.value) LocalCx.current.accent.copy(alpha = 0.15f) else LocalCx.current.panel2).padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${Time.format(c.startUs)} → ${Time.format(c.endUs)}", Modifier.weight(1f).clickable { vm.preview.seekTo(c.startUs); vm.select(c.id) }, style = MaterialTheme.typography.labelMedium, color = LocalCx.current.accent)
                IconButton({ vm.trimStart(c.id, c.startUs - 100_000) }, Modifier.size(28.dp)) { Text("◀", fontSize = 11.sp) }
                IconButton({ vm.trimStart(c.id, c.startUs + 100_000) }, Modifier.size(28.dp)) { Text("▶", fontSize = 11.sp) }
                Text("|", color = LocalCx.current.dim)
                IconButton({ vm.trimEnd(c.id, c.endUs - 100_000) }, Modifier.size(28.dp)) { Text("◀", fontSize = 11.sp) }
                IconButton({ vm.trimEnd(c.id, c.endUs + 100_000) }, Modifier.size(28.dp)) { Text("▶", fontSize = 11.sp) }
                IconButton({ vm.edit("Supprimer le sous-titre") { com.superessence.cuttinx.core.timeline.TimelineEditor.deleteClips(it, listOf(c.id)) } }, Modifier.size(28.dp)) { Icon(Icons.Default.Delete, null, Modifier.size(16.dp)) }
            }
            BasicTextFieldCompat(text) { t -> text = t; vm.updateClip("Sous-titre", "sub_${c.id}", c.id) { it.copy(text = it.text!!.copy(text = t)) } }
        }
    }
    if (subs.isNotEmpty()) TextButton({ vm.select(subs.first().id); vm.panel.value = Panel.TEXT_STYLE }) { Text("Style des sous-titres →") }
}

@Composable
fun BasicTextFieldCompat(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodyMedium, minLines = 1)
}
