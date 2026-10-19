package com.superessence.cuttinx.ui.editor

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.util.Time
import com.superessence.cuttinx.ui.components.*
import com.superessence.cuttinx.ui.theme.Cx
import com.superessence.cuttinx.ui.theme.LocalCx
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@UnstableApi
@Composable
fun EditorScreen(vm: EditorViewModel, onBack: () -> Unit, openProject: (String) -> Unit) {
    val project by vm.project.collectAsStateWithLifecycle()
    val ps by vm.previewState.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val workspace by vm.workspace.collectAsStateWithLifecycle()
    val panel by vm.panel.collectAsStateWithLifecycle()
    val msg by vm.message.collectAsStateWithLifecycle()
    val task by vm.task.collectAsStateWithLifecycle()
    val multi by vm.multiSelect.collectAsStateWithLifecycle()
    val beats by vm.beats.collectAsStateWithLifecycle()
    val settings by vm.c.settings.state.collectAsStateWithLifecycle()
    val exportState by vm.c.export.state.collectAsStateWithLifecycle()
    val cx = LocalCx.current
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val selected = selection.firstOrNull()?.let { id -> project.findClip(id)?.second }
    val selectedTrack = selection.firstOrNull()?.let { id -> project.findClip(id)?.first }
    var pxPerSecond by remember { mutableFloatStateOf(80f) }
    var timelineH by remember { mutableFloatStateOf(250f) }
    var trackMenu by remember { mutableStateOf<String?>(null) }
    var renameProject by remember { mutableStateOf(false) }

    LaunchedEffect(msg) { msg?.let { snack.showSnackbar(it, withDismissAction = true); vm.message.value = null } }
    // pause & save when going to background
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) { vm.preview.pause(); vm.save() } }
        lifecycle.addObserver(obs); onDispose { lifecycle.removeObserver(obs) }
    }
    BackHandler { if (panel != null) vm.panel.value = null else { vm.close(); onBack() } }

    // media providers for the timeline
    val thumbs = remember { mutableStateMapOf<String, ImageBitmap>() }
    val waves = remember { mutableStateMapOf<String, FloatArray>() }
    val requests = remember { MutableSharedFlow<Pair<MediaAsset, Long>>(extraBufferCapacity = 256) }
    LaunchedEffect(Unit) {
        val pending = HashSet<String>()
        requests.collect { (a, t) ->
            if (t < 0) {
                if (a.id in waves || !pending.add("w" + a.id)) return@collect
                launch { vm.c.waveforms.load(a)?.let { waves[a.id] = it } }
            } else {
                val key = "${a.id}_${(t / 250_000) * 250_000}"
                if (key in thumbs || !pending.add(key)) return@collect
                launch {
                    // video thumbnails need a hardware decoder too: let the preview finish loading first
                    // (4K/60 i/s sources can exhaust the decoders and leave the player loading forever)
                    if (a.type == MediaType.VIDEO) kotlinx.coroutines.withTimeoutOrNull(5_000) { vm.previewState.first { !it.buffering } }
                    vm.c.thumbnails.frame(a, t, 96)?.let { thumbs[key] = it.asImageBitmap() }
                }
            }
        }
    }
    val media = remember { TimelineMedia({ a, t -> thumbs["${a.id}_${(t / 250_000) * 250_000}"] }, { a -> waves[a.id] }, vm.c.waveforms.bucketsPerSecond, requests) }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            // ---------------- top bar
            Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ vm.close(); onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
                Column(Modifier.weight(1f).clickable { renameProject = true }) {
                    Text(project.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${project.format.width}×${project.format.height} • ${project.format.frameRate} i/s", style = MaterialTheme.typography.labelSmall, color = cx.dim)
                }
                // one-tap keyframe (diamond + plus), next to undo / redo like the pro editors
                val kfOn = selected != null && vm.anyKeyframeAtPlayhead()
                IconButton(vm::toggleQuickKeyframe) {
                    Box {
                        Icon(Icons.Default.Diamond, if (kfOn) "Retirer l'image clé" else "Ajouter une image clé", tint = if (kfOn) cx.accent else if (selected != null) MaterialTheme.colorScheme.onSurface else cx.dim)
                        Text(if (kfOn) "−" else "+", Modifier.align(Alignment.TopStart).offset((-6).dp, (-8).dp), fontSize = 14.sp, fontWeight = FontWeight.Black, color = if (kfOn) cx.accent else MaterialTheme.colorScheme.onSurface)
                    }
                }
                IconButton(vm::undo, enabled = vm.session.history.canUndo || true) { Icon(Icons.AutoMirrored.Filled.Undo, "Annuler") }
                IconButton(vm::redo) { Icon(Icons.AutoMirrored.Filled.Redo, "Rétablir") }
                IconButton({ vm.panel.value = Panel.HISTORY }) { Icon(Icons.Default.History, "Historique") }
                AccentButton("Exporter", { vm.panel.value = Panel.EXPORT }, Modifier.padding(end = 6.dp))
            }
            // ---------------- preview
            PreviewPane(vm, project, ps.positionUs, selected, ps.buffering, ps.error, Modifier.fillMaxWidth().weight(1f), notice = ps.notice)
            // ---------------- transport
            Transport(vm, ps.positionUs, maxOf(project.durationUs, ps.durationUs), ps.playing, selected)
            // ---------------- workspaces
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Workspace.entries.forEach { w ->
                    val sel = w == workspace
                    Text(w.label, Modifier.clip(RoundedCornerShape(50)).background(if (sel) cx.accent else cx.panel2).clickable { vm.workspace.value = w; vm.panel.value = workspacePanel(w) }.padding(horizontal = 12.dp, vertical = 5.dp),
                        color = if (sel) cx.onAccent else MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                }
            }
            // ---------------- timeline (resizable)
            Box(Modifier.fillMaxWidth().height(10.dp).pointerInput(Unit) { detectVerticalDragGestures { _, d -> timelineH = (timelineH - d / density).coerceIn(120f, 520f) } }, contentAlignment = Alignment.Center) {
                Box(Modifier.width(40.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(cx.line))
            }
            TimelineView(project, ps.positionUs, selection, pxPerSecond, { pxPerSecond = it }, media,
                TimelineCallbacks(
                    onSeek = { vm.preview.seekTo(it) },
                    onSelect = { vm.select(it); if (it == null && panel?.let(::isClipPanel) == true) vm.panel.value = null },
                    onMove = { id, s, t -> vm.moveClip(id, s, t) },
                    onTrimStart = { id, t -> vm.trimStart(id, t) },
                    onTrimEnd = { id, t -> vm.trimEnd(id, t) },
                    onTransitionTap = { id -> vm.select(id); vm.panel.value = Panel.TRANSITION },
                    onTrackHeader = { trackMenu = it },
                    onScrubStart = { vm.preview.pause() },
                    onAddTrack = { k -> when (k) { TrackKind.TEXT -> vm.addText(); TrackKind.AUDIO -> vm.panel.value = Panel.MUSIC; TrackKind.EFFECT -> vm.panel.value = Panel.ADJUST_LAYER; else -> vm.panel.value = Panel.MEDIA } },
                ), vm::snapCandidates, beats, Modifier.fillMaxWidth().height(timelineH.dp))
            // ---------------- bottom: panel or toolbar
            val p = panel
            if (p != null) PanelHost(vm, p, project, selected, selectedTrack, openProject)
            else Toolbar(vm, workspace, selected, selectedTrack, multi, settings.beginnerMode)
        }
        SnackbarHost(snack, Modifier.align(Alignment.TopCenter).padding(top = 60.dp))
        task?.let { t -> Box(Modifier.align(Alignment.Center).padding(24.dp)) { ProgressCard(t.title, t.progress, t.detail, onCancel = vm::cancelTask) } }
        if (vm.recording.collectAsStateWithLifecycle().value) RecordingOverlay(vm, Modifier.align(Alignment.Center))
    }

    trackMenu?.let { id -> TrackMenu(vm, project, id) { trackMenu = null } }
    if (renameProject) TextInputDialog("Nom du projet", project.name, { renameProject = false }) { vm.rename(it); renameProject = false }
    vm.loadError.collectAsStateWithLifecycle().value?.let { e -> AlertDialog({}, title = { Text("Projet illisible") }, text = { Text(e) }, confirmButton = { TextButton({ vm.close(); onBack() }) { Text("Retour") } }) }
}

fun isClipPanel(p: Panel) = p in setOf(Panel.TEXT, Panel.TEXT_STYLE, Panel.TEXT_ANIM, Panel.AUDIO, Panel.SPEED, Panel.TRANSFORM, Panel.CROP, Panel.KEYFRAMES, Panel.MASK, Panel.CHROMA, Panel.BLEND, Panel.TRANSITION, Panel.MOTION)

fun workspacePanel(w: Workspace): Panel? = when (w) {
    Workspace.AUDIO -> Panel.MIXER; Workspace.COLOR -> Panel.FILTERS; Workspace.SUBTITLES -> Panel.SUBTITLES; Workspace.AI -> Panel.AI
    Workspace.COMPOSITING -> Panel.TRACKS; Workspace.EXPORT -> Panel.EXPORT; Workspace.STUDIO -> Panel.PRO_TOOLS; else -> null
}

@UnstableApi
@Composable
private fun Transport(vm: EditorViewModel, pos: Long, dur: Long, playing: Boolean, selected: Clip?) {
    val cx = LocalCx.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${Time.format(pos)} / ${Time.format(dur, false)}", style = MaterialTheme.typography.labelMedium, color = cx.dim, modifier = Modifier.weight(1f))
        val fr = 1_000_000L / vm.current.format.frameRate
        IconButton({ vm.preview.seekTo((pos - fr).coerceAtLeast(0)) }) { Icon(Icons.Default.KeyboardArrowLeft, "Image précédente") }
        IconButton({ vm.preview.toggle() }, Modifier.size(48.dp)) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pause" else "Lecture", Modifier.size(34.dp), tint = cx.accent) }
        IconButton({ vm.preview.seekTo(pos + fr) }) { Icon(Icons.Default.KeyboardArrowRight, "Image suivante") }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
            if (selected != null) IconButton({ vm.panel.value = Panel.KEYFRAMES }) { Icon(Icons.Default.Diamond, "Images clés", tint = if (selected.keyframes.isNotEmpty()) cx.accent else cx.dim) }
            IconButton({ vm.addMarker() }) { Icon(Icons.Default.BookmarkAdd, "Marqueur") }
        }
    }
}

@UnstableApi
@Composable
private fun Toolbar(vm: EditorViewModel, ws: Workspace, sel: Clip?, track: Track?, multi: Boolean, beginner: Boolean) {
    val ctx = LocalContext.current
    val pickVisual = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { if (it.isNotEmpty()) vm.importMedia(it) }
    val pickOverlay = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let { u -> vm.importMedia(listOf(u), overlay = true) } }
    val pickReplace = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u -> val id = vm.primaryId; if (u != null && id != null) vm.importMedia(listOf(u), replaceClipId = id) }
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) vm.panel.value = Panel.VOICE else vm.message.value = "Autorisez le micro pour enregistrer une voix off" }
    data class T(val icon: ImageVector, val label: String, val enabled: Boolean = true, val active: Boolean = false, val action: () -> Unit)
    val tools: List<T> = if (sel == null) buildList {
        add(T(Icons.Default.VideoLibrary, "Média") { pickVisual.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) })
        add(T(Icons.Default.PhotoLibrary, "Bibliothèque") { vm.panel.value = Panel.MEDIA })
        add(T(Icons.Default.MusicNote, "Audio") { vm.panel.value = Panel.MUSIC })
        add(T(Icons.Default.TextFields, "Texte") { vm.addText() })
        add(T(Icons.Default.Title, "Titres") { vm.panel.value = Panel.TEMPLATES })
        add(T(Icons.Default.Subtitles, "Sous-titres") { vm.panel.value = Panel.SUBTITLES })
        add(T(Icons.Default.Mic, "Voix off") { if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.panel.value = Panel.VOICE else micPerm.launch(Manifest.permission.RECORD_AUDIO) })
        add(T(Icons.Default.AutoAwesome, "AI Lab") { vm.panel.value = Panel.AI })
        add(T(Icons.Default.Stars, "Outils pro") { vm.panel.value = Panel.PRO_TOOLS })
        if (!beginner || ws != Workspace.QUICK) {
            add(T(Icons.Default.Layers, "Incrustation") { pickOverlay.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) })
            add(T(Icons.Default.EmojiEmotions, "Autocollants") { vm.panel.value = Panel.STICKERS })
            add(T(Icons.Default.AutoFixHigh, "Effets") { vm.panel.value = Panel.EFFECTS })
            add(T(Icons.Default.FilterVintage, "Filtres") { vm.panel.value = Panel.FILTERS })
            add(T(Icons.Default.Tune, "Calque d'effets") { vm.panel.value = Panel.ADJUST_LAYER })
        }
        add(T(Icons.Default.AspectRatio, "Format") { vm.panel.value = Panel.FORMAT })
        add(T(Icons.Default.Wallpaper, "Fond") { vm.panel.value = Panel.BACKGROUND })
        add(T(Icons.Default.ViewStream, "Pistes") { vm.panel.value = Panel.TRACKS })
        add(T(Icons.Default.Bookmarks, "Marqueurs") { vm.panel.value = Panel.MARKERS })
        if (vm.clipboard.isNotEmpty()) add(T(Icons.Default.ContentPaste, "Coller") { vm.paste() })
        add(T(Icons.Default.Checklist, "Multi", active = multi) { vm.multiSelect.value = !multi })
    } else buildList {
        val asset = vm.current.asset(sel.assetId)
        val visual = sel.kind != ClipKind.ADJUSTMENT && track?.kind != TrackKind.AUDIO
        val isText = sel.text != null
        val hasAudio = asset?.hasAudio == true || track?.kind == TrackKind.AUDIO
        add(T(Icons.Default.ContentCut, "Diviser") { vm.split() })
        add(T(Icons.Default.Delete, "Supprimer") { vm.deleteSelected() })
        if (visual) add(T(Icons.Default.Animation, "Animations") { vm.panel.value = Panel.MOTION })
        if (isText) { add(T(Icons.Default.Edit, "Modifier") { vm.panel.value = Panel.TEXT }); add(T(Icons.Default.FormatPaint, "Style") { vm.panel.value = Panel.TEXT_STYLE }); add(T(Icons.Default.TextRotationNone, "Anim. texte") { vm.panel.value = Panel.TEXT_ANIM }) }
        if (sel.kind == ClipKind.MEDIA && asset?.type != MediaType.IMAGE) add(T(Icons.Default.Speed, "Vitesse") { vm.panel.value = Panel.SPEED })
        if (hasAudio) add(T(Icons.AutoMirrored.Filled.VolumeUp, "Audio") { vm.panel.value = Panel.AUDIO })
        if (visual) {
            add(T(Icons.Default.OpenWith, "Transformer") { vm.panel.value = Panel.TRANSFORM })
            if (!isText) add(T(Icons.Default.Crop, "Recadrer") { vm.panel.value = Panel.CROP })
        }
        if (sel.kind != ClipKind.ADJUSTMENT || true) if (track?.kind != TrackKind.AUDIO) { add(T(Icons.Default.AutoFixHigh, "Effets") { vm.panel.value = Panel.EFFECTS }); add(T(Icons.Default.FilterVintage, "Filtres") { vm.panel.value = Panel.FILTERS }); add(T(Icons.Default.Tune, "Ajuster") { vm.panel.value = Panel.ADJUST }) }
        add(T(Icons.Default.Diamond, "Images clés") { vm.panel.value = Panel.KEYFRAMES })
        if (track?.kind == TrackKind.VIDEO) add(T(Icons.Default.SwapHoriz, "Transition") { vm.panel.value = Panel.TRANSITION })
        if (visual && !isText) {
            add(T(Icons.Default.Gradient, "Masque") { vm.panel.value = Panel.MASK })
            add(T(Icons.Default.Contrast, "Incrustation") { vm.panel.value = Panel.CHROMA })
        }
        if (visual) add(T(Icons.Default.Opacity, "Opacité") { vm.panel.value = Panel.BLEND })
        add(T(Icons.Default.ContentCopy, "Dupliquer") { vm.duplicateSelected() })
        add(T(Icons.Default.CopyAll, "Copier") { vm.copySelected() })
        if (sel.kind == ClipKind.MEDIA && asset?.isVisual == true) add(T(Icons.Default.FindReplace, "Remplacer") { pickReplace.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) })
        if (asset?.type == MediaType.VIDEO && asset.hasAudio && track?.kind == TrackKind.VIDEO) add(T(Icons.AutoMirrored.Filled.CallSplit, "Extraire l'audio") { vm.extractAudio() })
        add(T(Icons.Default.AutoAwesome, "AI Lab") { vm.panel.value = Panel.AI })
        add(T(Icons.Default.Stars, "Outils pro") { vm.panel.value = Panel.PRO_TOOLS })
        add(T(Icons.Default.Checklist, "Multi", active = multi) { vm.multiSelect.value = !multi })
        if (vm.selection.value.size > 1) { add(T(Icons.Default.Link, "Grouper") { vm.groupSelected() }); add(T(Icons.Default.MergeType, "Fusionner") { vm.mergeSelected() }) }
        if (sel.groupId != null) add(T(Icons.Default.LinkOff, "Dissocier") { vm.ungroupSelected() })
        add(T(if (sel.enabled) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (sel.enabled) "Désactiver" else "Activer") { vm.updateSelected("Activer/désactiver") { it.copy(enabled = !it.enabled) } })
        add(T(Icons.Default.Close, "Désélect.") { vm.select(null) })
    }
    Row(Modifier.fillMaxWidth().background(LocalCx.current.panel).horizontalScroll(rememberScrollState()).padding(vertical = 4.dp, horizontal = 4.dp)) {
        tools.forEach { ToolButton(it.icon, it.label, it.action, active = it.active, enabled = it.enabled) }
    }
}

@UnstableApi
@Composable
private fun TrackMenu(vm: EditorViewModel, p: Project, id: String, onDismiss: () -> Unit) {
    val t = p.track(id) ?: return onDismiss()
    var rename by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(t.name) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            LabeledSwitch("Verrouillée", t.locked, { vm.updateTrack(id) { tr -> tr.copy(locked = it) } }, description = "Empêche toute modification")
            if (t.kind != TrackKind.AUDIO) LabeledSwitch("Masquée", t.hidden, { vm.updateTrack(id) { tr -> tr.copy(hidden = it) } })
            if (t.kind == TrackKind.AUDIO || t.kind == TrackKind.VIDEO) {
                LabeledSwitch("Son coupé", t.muted, { vm.updateTrack(id) { tr -> tr.copy(muted = it) } })
                ValueSlider("Volume de la piste", t.volume.toFloat(), 0f..2f, { v -> vm.updateTrack(id) { it.copy(volume = v.toDouble()) } }, default = 1f)
            }
            Row { TextButton({ vm.moveTrack(id, -1) }) { Text("Monter") }; TextButton({ vm.moveTrack(id, 1) }) { Text("Descendre") }; TextButton({ rename = true }) { Text("Renommer") } }
            TextButton({ vm.removeTrack(id); onDismiss() }) { Text("Supprimer la piste", color = Cx.Danger) }
        }
    }, confirmButton = { TextButton(onDismiss) { Text("Fermer") } })
    if (rename) TextInputDialog("Nom de la piste", t.name, { rename = false }) { n -> vm.updateTrack(id) { it.copy(name = n) }; rename = false }
}

@UnstableApi
@Composable
private fun RecordingOverlay(vm: EditorViewModel, modifier: Modifier) {
    var level by remember { mutableFloatStateOf(0f) }
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { level = vm.recorder.level(); elapsed = vm.recorder.elapsedMs; kotlinx.coroutines.delay(80) } }
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(Color.Black.copy(alpha = 0.85f)).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("● Enregistrement", color = Cx.Danger, fontWeight = FontWeight.Bold)
        Text(Time.format(elapsed * 1000, false), color = Color.White, style = MaterialTheme.typography.headlineMedium)
        LinearProgressIndicator({ level }, Modifier.width(180.dp).padding(vertical = 8.dp), color = if (level > 0.95f) Cx.Danger else Cx.Ok)
        AccentButton("Arrêter", { vm.stopRecording() }, icon = Icons.Default.Stop)
    }
}
