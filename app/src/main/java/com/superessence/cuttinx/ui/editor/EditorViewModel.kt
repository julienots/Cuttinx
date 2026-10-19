package com.superessence.cuttinx.ui.editor

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.superessence.cuttinx.CuttinxApp
import com.superessence.cuttinx.core.anim.KeyframeEvaluator
import com.superessence.cuttinx.core.anim.SpeedMath
import com.superessence.cuttinx.core.effects.EffectPreset
import com.superessence.cuttinx.core.effects.EffectRegistry
import com.superessence.cuttinx.core.effects.Presets
import com.superessence.cuttinx.core.effects.TransitionRegistry
import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.project.ExportPlanner
import com.superessence.cuttinx.core.project.ResolvedExport
import com.superessence.cuttinx.core.render.FrameEvaluator
import com.superessence.cuttinx.core.subtitles.Cue
import com.superessence.cuttinx.core.subtitles.SubtitleCodec
import com.superessence.cuttinx.core.timeline.EditResult
import com.superessence.cuttinx.core.timeline.EditSession
import com.superessence.cuttinx.core.timeline.Snapping
import com.superessence.cuttinx.core.timeline.TimelineEditor
import com.superessence.cuttinx.core.util.Ids
import com.superessence.cuttinx.engine.PreviewEngine
import com.superessence.cuttinx.media.CodecInfo
import com.superessence.cuttinx.recording.VoiceRecorder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

@UnstableApi
class EditorViewModel(app: Application, val projectId: String) : AndroidViewModel(app) {
    val c = (app as CuttinxApp).container
    val ctx: Application get() = getApplication()
    val session: EditSession
    val project: StateFlow<Project>
    val preview = PreviewEngine(app, c.factory)
    val previewState get() = preview.state

    val selection = MutableStateFlow<Set<String>>(emptySet())
    val multiSelect = MutableStateFlow(false)
    val workspace = MutableStateFlow(if (c.settings.value.beginnerMode) Workspace.QUICK else Workspace.STUDIO)
    val panel = MutableStateFlow<Panel?>(null)
    val message = MutableStateFlow<String?>(null)
    val task = MutableStateFlow<TaskState?>(null)
    private var taskJob: Job? = null
    var clipboard: List<TimelineEditor.ClipboardEntry> = emptyList(); private set
    val recorder = VoiceRecorder(app)
    val recording = MutableStateFlow(false)
    private var recordStartUs = 0L
    /** Beats found by the AI (shown on the timeline, used for snapping). */
    val beats = MutableStateFlow<List<Long>>(emptyList())
    /** Framing guides drawn over the preview only (never rendered in the export). */
    val guides = MutableStateFlow(PreviewGuides.NONE)
    val loadError = MutableStateFlow<String?>(null)

    init {
        val loaded = runCatching { c.store.load(projectId) }.getOrElse { e -> loadError.value = "Impossible d'ouvrir le projet : ${e.message}"; Project(projectId, "Projet") }
        session = EditSession(loaded)
        project = session.project
        TimelineEditor.magneticMainTrack = c.settings.value.magneticMainTrack
        preview.quality = c.settings.value.previewQuality
        c.store.openSession(projectId)
        CuttinxApp.crashSaver = { runCatching { c.store.autosave(session.current) } }
        // missing media detection
        viewModelScope.launch(Dispatchers.IO) {
            val checked = c.importer.refreshMissing(loaded.media)
            if (checked != loaded.media) {
                session.replace(session.current.copy(media = checked))
                val n = checked.count { it.missing }
                if (n > 0) message.value = "$n média(s) introuvable(s) : remplacez-les depuis l'onglet Médias."
            }
        }
        // preview follows every change
        viewModelScope.launch { session.revision.collect { preview.update(session.current) } }
        // autosave
        viewModelScope.launch(Dispatchers.IO) {
            var lastSaved = session.revision.value
            while (isActive) {
                delay(c.settings.value.autosaveSeconds.coerceAtLeast(3) * 1000L)
                val rev = session.revision.value
                if (rev != lastSaved) { runCatching { c.store.autosave(session.current) }; lastSaved = rev }
            }
        }
        preview.update(loaded, immediate = true)
    }

    // ------------------------------------------------------------------ core helpers

    val current get() = session.current
    val position get() = preview.state.value.positionUs
    val primaryId get() = selection.value.firstOrNull()
    fun clip(id: String?) = id?.let { current.findClip(it)?.second }
    fun selectedClip() = clip(primaryId)

    fun edit(label: String, key: String? = null, op: (Project) -> Project): Boolean {
        val r = session.edit(label, key, op)
        if (r is EditResult.Failed) { message.value = r.message; return false }
        return true
    }

    fun undo() { session.undo()?.let { message.value = "Annulé : $it" }; pruneSelection() }
    fun redo() { session.redo()?.let { message.value = "Rétabli : $it" }; pruneSelection() }
    private fun pruneSelection() { selection.value = selection.value.filter { current.findClip(it) != null }.toSet() }

    fun select(id: String?) {
        if (id == null) { selection.value = emptySet(); return }
        selection.value = if (multiSelect.value) (if (id in selection.value) selection.value - id else selection.value + id) else setOf(id)
    }

    fun save(): Boolean = runCatching { c.store.save(current); true }.getOrElse { message.value = "Sauvegarde impossible : ${it.message}"; false }

    fun close() {
        save()
        c.store.closeSession(projectId)
        CuttinxApp.crashSaver = null
        saveThumbnail()
    }

    override fun onCleared() {
        preview.release()
        if (recorder.isRecording) recorder.cancel()
        super.onCleared()
    }

    private fun saveThumbnail() {
        val p = current
        val first = p.tracks.firstOrNull { it.kind == TrackKind.VIDEO }?.sortedClips()?.firstOrNull() ?: return
        val a = p.asset(first.assetId) ?: return
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val bmp = c.thumbnails.frame(a, first.sourceInUs, 240) ?: return@launch
                File(ctx.filesDir, "project_thumbs").mkdirs()
                File(ctx.filesDir, "project_thumbs/${p.id}.jpg").outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            }
        }
    }

    fun runTask(title: String, block: suspend CoroutineScope.(progress: (Float, String?) -> Unit) -> Unit) {
        if (taskJob?.isActive == true) { message.value = "Une tâche est déjà en cours"; return }
        task.value = TaskState(title, null)
        taskJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                block { p, d -> task.value = TaskState(title, p, d) }
            } catch (e: CancellationException) {
                message.value = "« $title » annulé"
            } catch (e: OutOfMemoryError) {
                message.value = "Mémoire insuffisante pour « $title ». Essayez sur un extrait plus court."
            } catch (e: Exception) {
                message.value = "$title : ${e.message ?: e.javaClass.simpleName}"
            } finally { task.value = null }
        }
    }

    fun cancelTask() { taskJob?.cancel() }

    // ------------------------------------------------------------------ media

    fun importMedia(uris: List<Uri>, forced: MediaType? = null, addToTimeline: Boolean = true, replaceClipId: String? = null, overlay: Boolean = false) {
        runTask("Import des médias") { prog ->
            val res = withContext(Dispatchers.IO) { c.importer.import(uris, forced) }
            if (res.errors.isNotEmpty()) message.value = res.errors.joinToString("\n")
            withContext(Dispatchers.Main) {
                for (a in res.assets) {
                    if (a.tags.contains("décodage-non-supporté")) message.value = "Attention : « ${a.displayName} » utilise un codec que ce téléphone ne sait pas décoder."
                    when {
                        replaceClipId != null -> edit("Remplacer le média") { TimelineEditor.replaceMedia(it, replaceClipId, a) }
                        a.type == MediaType.SUBTITLE -> importSubtitleFile(Uri.parse(a.uri))
                        a.type == MediaType.FONT -> installFont(a)
                        !addToTimeline -> edit("Ajouter à la bibliothèque") { TimelineEditor.addMedia(it, a) }
                        overlay && a.isVisual -> addOverlay(a)
                        else -> edit("Ajouter ${a.displayName}") { p ->
                            if (a.type == MediaType.AUDIO) addAudioAt(p, a, position, AudioRole.MUSIC)
                            else {
                                val (p1, main) = TimelineEditor.mainTrack(p)
                                val clip = TimelineEditor.clipFromAsset(a, insertionPoint(p1, main)).let { if (a.type == MediaType.IMAGE) it.copy(sourceOutUs = c.settings.value.defaultImageDurationMs * 1000L) else it }
                                TimelineEditor.insertClip(TimelineEditor.addMedia(p1, a), main.id, clip, ripple = true)
                            }
                        }
                    }
                }
            }
        }
    }

    /** On the magnetic main track, insert at the cut nearest to the playhead. */
    private fun insertionPoint(p: Project, main: Track): Long {
        val pos = position
        val clips = main.sortedClips()
        if (clips.isEmpty()) return 0
        val under = clips.firstOrNull { it.contains(pos) } ?: return main.endUs
        return if (pos - under.startUs < under.endUs - pos) under.startUs else under.endUs
    }

    private fun addAudioAt(p: Project, a: MediaAsset, at: Long, role: AudioRole): Project {
        var np = TimelineEditor.addMedia(p, a)
        val clip = TimelineEditor.clipFromAsset(a, at)
        val track = np.tracks.firstOrNull { t -> t.kind == TrackKind.AUDIO && t.audioRole == role && !t.locked && t.clips.none { it.startUs < clip.endUs && at < it.endUs } }
            ?: TimelineEditor.addTrack(np, TrackKind.AUDIO, role = role).also { np = it.first }.second
        return TimelineEditor.insertClip(np, track.id, clip)
    }

    fun addOverlay(a: MediaAsset, transform: Transform = Transform(scaleX = 0.5, scaleY = 0.5)) = edit("Incrustation") { p ->
        var np = TimelineEditor.addMedia(p, a)
        val clip = TimelineEditor.clipFromAsset(a, position).copy(transform = transform)
        val track = np.tracks.drop(1).firstOrNull { t -> t.kind == TrackKind.VIDEO && !t.locked && t.clips.none { it.startUs < clip.endUs && position < it.endUs } }
            ?: TimelineEditor.addTrack(np, TrackKind.VIDEO).also { np = it.first }.second
        TimelineEditor.insertClip(np, track.id, clip)
    }

    fun addResourceAsset(a: MediaAsset, role: AudioRole = AudioRole.SFX) {
        when (a.type) {
            MediaType.AUDIO -> edit("Ajouter ${a.displayName}") { addAudioAt(it, a, position, role) }
            else -> addOverlay(a, if (a.folder == "Fonds") Transform(fill = true) else Transform(scaleX = 0.4, scaleY = 0.4))
        }
    }

    fun addBackgroundToMain(a: MediaAsset) = edit("Fond") { p ->
        val (p1, main) = TimelineEditor.mainTrack(TimelineEditor.addMedia(p, a))
        TimelineEditor.insertClip(p1, main.id, TimelineEditor.clipFromAsset(a, insertionPoint(p1, main)).copy(transform = Transform(fill = true)), ripple = true)
    }

    fun installFont(a: MediaAsset) {
        val dest = File(ctx.filesDir, "fonts").also { it.mkdirs() }
        runCatching { ctx.contentResolver.openInputStream(Uri.parse(a.uri))!!.use { inp -> File(dest, "${a.id}.ttf").outputStream().use { inp.copyTo(it) } } }
            .onSuccess { edit("Police importée") { TimelineEditor.addMedia(it, a.copy(type = MediaType.FONT)) }; message.value = "Police « ${a.displayName} » disponible dans le texte" }
            .onFailure { message.value = "Police illisible" }
    }

    fun relink(assetId: String, uri: Uri) = runTask("Relier le média") {
        val probe = withContext(Dispatchers.IO) { c.importer.import(listOf(uri)) }
        val a = probe.assets.firstOrNull() ?: run { message.value = probe.errors.firstOrNull() ?: "Fichier illisible"; return@runTask }
        withContext(Dispatchers.Main) { edit("Relier le média") { TimelineEditor.relinkMedia(it, assetId, a.uri, a.durationUs.takeIf { d -> d > 0 }) } }
    }

    fun updateAsset(id: String, f: (MediaAsset) -> MediaAsset) = edit("Média") { TimelineEditor.updateMedia(it, id, f) }

    fun removeAsset(id: String) = edit("Retirer le média") { TimelineEditor.removeMedia(it, id) }

    // ------------------------------------------------------------------ timeline edits

    fun split() {
        val pos = position
        val targets = selection.value.ifEmpty { current.tracks.filter { !it.locked && !it.hidden }.flatMap { t -> t.clips.filter { it.contains(pos) } }.map { it.id }.toSet() }
        if (targets.isEmpty()) { message.value = "Placez la tête de lecture sur un élément"; return }
        edit("Diviser") { p -> var np = p; for (id in targets) { val c = np.findClip(id)?.second ?: continue; if (c.contains(pos)) np = TimelineEditor.split(np, id, pos).first }; np }
    }

    fun deleteSelected(ripple: Boolean = false) {
        val ids = selection.value
        if (ids.isEmpty()) return
        if (edit(if (ripple) "Supprimer et resserrer" else "Supprimer") { TimelineEditor.deleteClips(it, ids, ripple) }) selection.value = emptySet()
    }

    fun duplicateSelected() { val id = primaryId ?: return; var newId: String? = null; edit("Dupliquer") { TimelineEditor.duplicate(it, id).also { r -> newId = r.second }.first }; newId?.let { selection.value = setOf(it) } }
    fun copySelected() { clipboard = TimelineEditor.copy(current, selection.value); message.value = "${clipboard.size} élément(s) copié(s)" }
    fun cutSelected() { copySelected(); deleteSelected() }
    fun paste() { if (clipboard.isEmpty()) return; var ids: List<String> = emptyList(); edit("Coller") { TimelineEditor.paste(it, clipboard, position).also { r -> ids = r.second }.first }; selection.value = ids.toSet() }
    fun groupSelected() = edit("Grouper") { TimelineEditor.group(it, selection.value) }
    fun ungroupSelected() = edit("Dissocier") { TimelineEditor.ungroup(it, selection.value) }

    fun mergeSelected() {
        val ids = selection.value.toList()
        if (ids.size != 2) { message.value = "Sélectionnez deux segments contigus du même média"; return }
        edit("Fusionner") { TimelineEditor.merge(it, ids[0], ids[1]) }
        selection.value = setOf(ids.minBy { current.findClip(it)?.second?.startUs ?: Long.MAX_VALUE })
    }

    fun moveClip(id: String, newStart: Long, trackId: String?) = edit("Déplacer", "move_$id") { TimelineEditor.moveClipTo(it, id, newStart, trackId) }
    fun moveSelected(delta: Long) = edit("Déplacer", "move_sel") { TimelineEditor.moveClips(it, selection.value, delta) }
    fun trimStart(id: String, t: Long) = edit("Raccourcir", "trimS_$id") { TimelineEditor.trimStart(it, id, t) }
    fun trimEnd(id: String, t: Long) = edit("Raccourcir", "trimE_$id") { TimelineEditor.trimEnd(it, id, t) }
    fun nudge(frames: Int) { val f = 1_000_000L / current.format.frameRate; moveSelected(frames * f) }

    fun updateClip(label: String, key: String? = null, id: String? = primaryId, f: (Clip) -> Clip) { if (id != null) edit(label, key) { TimelineEditor.updateClip(it, id) { c -> f(c) } } }
    fun updateSelected(label: String, key: String? = null, f: (Clip) -> Clip) = edit(label, key) { p -> var np = p; selection.value.forEach { id -> np = TimelineEditor.updateClip(np, id, f) }; np }
    fun updateTrack(id: String, f: (Track) -> Track) = edit("Piste") { TimelineEditor.updateTrack(it, id, f) }
    fun addTrack(kind: TrackKind, role: AudioRole = AudioRole.GENERAL) = edit("Nouvelle piste") { TimelineEditor.addTrack(it, kind, role = role).first }
    fun removeTrack(id: String) = edit("Supprimer la piste") { TimelineEditor.removeTrack(it, id) }
    fun moveTrack(id: String, dir: Int) = edit("Réordonner") { p -> val i = p.tracks.indexOfFirst { it.id == id }; TimelineEditor.moveTrack(p, id, i + dir) }

    fun setSpeed(speed: Double) { val id = primaryId ?: return; edit("Vitesse", "speed_$id") { TimelineEditor.setSpeed(it, id, speed) } }
    fun setSpeedCurve(curve: List<SpeedPoint>) { val id = primaryId ?: return; edit("Courbe de vitesse") { TimelineEditor.setSpeedCurve(it, id, curve) } }
    fun setDuration(us: Long) { val id = primaryId ?: return; edit("Durée", "dur_$id") { TimelineEditor.setDuration(it, id, us) } }

    fun replaceSelected(a: MediaAsset) { val id = primaryId ?: return; edit("Remplacer") { TimelineEditor.replaceMedia(it, id, a) } }

    /** Detaches the audio of a video clip onto an audio track (video gets muted). */
    fun extractAudio() {
        val c = selectedClip() ?: return
        val a = current.asset(c.assetId)
        if (a?.hasAudio != true || a.type != MediaType.VIDEO) { message.value = "Cet élément n'a pas de son"; return }
        edit("Extraire l'audio") { p ->
            var np = TimelineEditor.updateClip(p, c.id) { it.copy(audio = it.audio.copy(muted = true)) }
            val ac = c.copy(id = Ids.new("clp"), transitionIn = null, effects = emptyList(), keyframes = c.keyframes.filterKeys { it == Prop.VOLUME || it == Prop.PAN }, groupId = null, audio = c.audio.copy(muted = false))
            val track = np.tracks.firstOrNull { t -> t.kind == TrackKind.AUDIO && t.audioRole == AudioRole.GENERAL && t.clips.none { it.startUs < ac.endUs && ac.startUs < it.endUs } }
                ?: TimelineEditor.addTrack(np, TrackKind.AUDIO).also { np = it.first }.second
            TimelineEditor.insertClip(np, track.id, ac)
        }
    }

    fun addMarker(label: String = "") = edit("Marqueur") { TimelineEditor.addMarker(it, position, label) }
    fun removeMarker(id: String) = edit("Supprimer le marqueur") { TimelineEditor.removeMarker(it, id) }

    fun splitAll() = edit("Diviser toutes les pistes") { TimelineEditor.splitAll(it, position) }

    // ------------------------------------------------------------------ text

    fun addText(text: String = "Votre texte", style: TextStyle = TextStyle(), animIn: TextAnimation = TextAnimation.FADE, animOut: TextAnimation = TextAnimation.FADE) {
        var id: String? = null
        edit("Ajouter un texte") { p ->
            var np = p
            val clip = TimelineEditor.textClip(text, position, style = style).let { it.copy(text = it.text!!.copy(animIn = animIn, animOut = animOut)) }
            id = clip.id
            val track = np.tracks.firstOrNull { t -> t.kind == TrackKind.TEXT && !t.locked && t.clips.none { it.startUs < clip.endUs && clip.startUs < it.endUs } }
                ?: TimelineEditor.addTrack(np, TrackKind.TEXT).also { np = it.first }.second
            TimelineEditor.insertClip(np, track.id, clip)
        }
        id?.let { selection.value = setOf(it); panel.value = Panel.TEXT }
    }

    fun addSubtitle(text: String = "Sous-titre", durUs: Long = 2_000_000) {
        var id: String? = null
        edit("Ajouter un sous-titre") { p ->
            var np = p
            val clip = TimelineEditor.textClip(text, position, durUs, TextStyle(sizeSp = 34.0, strokeWidth = 3.0, shadowRadius = 4.0), ClipKind.SUBTITLE).let { it.copy(text = it.text!!.copy(animIn = TextAnimation.NONE, animOut = TextAnimation.NONE)) }
            id = clip.id
            val track = np.tracks.firstOrNull { it.kind == TrackKind.SUBTITLE && !it.locked } ?: TimelineEditor.addTrack(np, TrackKind.SUBTITLE).also { np = it.first }.second
            TimelineEditor.insertClip(np, track.id, clip)
        }
        id?.let { selection.value = setOf(it) }
    }

    fun importSubtitleFile(uri: Uri) = runTask("Import des sous-titres") {
        val text = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)!!.bufferedReader().readText() }
        val (name, _) = com.superessence.cuttinx.media.MediaProbe.displayName(ctx.contentResolver, uri)
        val cues = SubtitleCodec.parse(text, name)
        if (cues.isEmpty()) { message.value = "Aucun sous-titre reconnu dans $name"; return@runTask }
        withContext(Dispatchers.Main) { edit("Importer ${cues.size} sous-titres") { com.superessence.cuttinx.core.autoedit.AiActions.importSubtitles(it, cues) } }
        message.value = "${cues.size} sous-titres importés"
    }

    fun exportSubtitles(uri: Uri, vtt: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        val cues = SubtitleCodec.fromProject(current)
        runCatching { ctx.contentResolver.openOutputStream(uri)!!.use { it.write((if (vtt) SubtitleCodec.toVtt(cues) else SubtitleCodec.toSrt(cues)).toByteArray()) } }
            .onSuccess { message.value = "${cues.size} sous-titres exportés" }.onFailure { message.value = "Export impossible : ${it.message}" }
    }

    fun shiftSubtitles(deltaUs: Long) = edit("Décaler les sous-titres") { p ->
        TimelineEditor.mapClips(p) { c -> if (c.kind == ClipKind.SUBTITLE) c.copy(startUs = (c.startUs + deltaUs).coerceAtLeast(0)) else c }
    }

    // ------------------------------------------------------------------ keyframes

    /** Adds or removes a keyframe on [prop] at the playhead with the current evaluated value. */
    fun toggleKeyframe(prop: String, currentValue: Double) {
        val c = selectedClip() ?: return
        val rel = (position - c.startUs).coerceIn(0, c.durationUs)
        val existing = c.keyframes[prop]?.keyframes?.any { kotlin.math.abs(it.timeUs - rel) < 30_000 } == true
        if (existing) edit("Supprimer l'image clé") { TimelineEditor.removeKeyframe(it, c.id, prop, rel) }
        else edit("Image clé") { TimelineEditor.setKeyframe(it, c.id, prop, rel, currentValue) }
    }

    /** Sets a property value: writes a keyframe when the property is animated, otherwise the base value. */
    fun setAnimated(prop: String, value: Double, base: (Clip, Double) -> Clip) {
        val c = selectedClip() ?: return
        val rel = (position - c.startUs).coerceIn(0, c.durationUs)
        if (c.keyframes[prop]?.isEmpty == false) edit("Image clé", "kf_${c.id}_$prop") { TimelineEditor.setKeyframe(it, c.id, prop, rel, value, c.keyframes[prop]!!.keyframes.minByOrNull { k -> kotlin.math.abs(k.timeUs - rel) }?.easing ?: Easing.Smooth) }
        else updateClip(prop, "prop_${c.id}_$prop") { base(it, value) }
    }

    /** Transform properties animated by the one-tap keyframe button (CapCut style). */
    private val quickProps = listOf(Prop.POS_X, Prop.POS_Y, Prop.SCALE, Prop.ROTATION, Prop.OPACITY)

    /** True when the selected clip has a keyframe at the playhead (any property). */
    fun anyKeyframeAtPlayhead(): Boolean {
        val c = selectedClip() ?: return false
        val rel = position - c.startUs
        return c.keyframes.values.any { k -> k.keyframes.any { kotlin.math.abs(it.timeUs - rel) < 30_000 } }
    }

    /**
     * One tap: adds a keyframe on position, scale, rotation and opacity at the playhead with the
     * current values (then moving / zooming the clip elsewhere animates it), or removes them.
     */
    fun toggleQuickKeyframe() {
        val c = selectedClip() ?: run { message.value = "Sélectionne d'abord un clip"; return }
        val rel = (position - c.startUs).coerceIn(0, c.durationUs)
        if (position < c.startUs || position > c.endUs) { message.value = "Place la tête de lecture sur le clip sélectionné"; return }
        if (anyKeyframeAtPlayhead()) {
            edit("Supprimer l'image clé") { p -> TimelineEditor.updateClip(p, c.id) { cl -> cl.copy(keyframes = cl.keyframes.mapValues { (_, k) -> KeyframeTrack(k.keyframes.filterNot { kotlin.math.abs(it.timeUs - rel) < 30_000 }) }.filterValues { it.keyframes.isNotEmpty() }) } }
            return
        }
        val t = FrameEvaluator.transform(c, rel, withMotion = false)
        val values = mapOf(
            Prop.POS_X to t.posX, Prop.POS_Y to t.posY, Prop.ROTATION to t.rotation, Prop.OPACITY to t.opacity,
            Prop.SCALE to KeyframeEvaluator.evaluate(c.keyframes[Prop.SCALE], rel, 1.0),
        )
        edit("Image clé") { p -> quickProps.fold(p) { acc, prop -> TimelineEditor.setKeyframe(acc, c.id, prop, rel, values[prop] ?: 0.0) } }
        message.value = "Image clé ajoutée ◆ — déplace la tête de lecture puis bouge / zoome le clip"
    }

    fun keyframeAtPlayhead(prop: String): Boolean {
        val c = selectedClip() ?: return false
        val rel = position - c.startUs
        return c.keyframes[prop]?.keyframes?.any { kotlin.math.abs(it.timeUs - rel) < 30_000 } == true
    }

    fun setKeyframeEasing(prop: String, timeUs: Long, easing: Easing) { val c = selectedClip() ?: return; updateClip("Interpolation") { cl -> cl.copy(keyframes = cl.keyframes + (prop to KeyframeTrack(cl.keyframes[prop]!!.keyframes.map { if (it.timeUs == timeUs) it.copy(easing = easing) else it }))) } }
    fun moveKeyframe(prop: String, from: Long, to: Long) = updateClip("Déplacer l'image clé", "kfmove_$prop") { cl -> cl.copy(keyframes = cl.keyframes + (prop to KeyframeEvaluator.move(cl.keyframes[prop]!!, from, to.coerceIn(0, cl.durationUs)))) }
    fun copyKeyframesToSelection() {
        val src = selectedClip() ?: return
        edit("Copier les images clés") { p -> var np = p; selection.value.drop(1).forEach { id -> np = TimelineEditor.updateClip(np, id) { it.copy(keyframes = src.keyframes.filterKeys { k -> !k.startsWith("fx:") }) } }; np }
    }

    // ------------------------------------------------------------------ effects, filters, transitions

    fun addEffect(effectId: String) {
        val def = EffectRegistry[effectId] ?: return
        val fx = EffectInstance(Ids.new("fx"), effectId, def.defaults())
        if (selection.value.isEmpty()) { addAdjustmentLayer(listOf(fx)); return }
        if (def.kind == com.superessence.cuttinx.core.effects.EffectKind.AUDIO) updateSelected("Effet ${def.name}") { it.copy(audio = it.audio.copy(effects = it.audio.effects + fx)) }
        else updateSelected("Effet ${def.name}") { it.copy(effects = it.effects + fx) }
    }

    fun addAdjustmentLayer(effects: List<EffectInstance>) {
        var id: String? = null
        edit("Calque d'effets") { p ->
            var np = p
            val clip = TimelineEditor.adjustmentClip(position).copy(effects = effects)
            id = clip.id
            val track = np.tracks.firstOrNull { t -> t.kind == TrackKind.EFFECT && t.clips.none { it.startUs < clip.endUs && clip.startUs < it.endUs } } ?: TimelineEditor.addTrack(np, TrackKind.EFFECT).also { np = it.first }.second
            TimelineEditor.insertClip(np, track.id, clip)
        }
        id?.let { selection.value = setOf(it) }
    }

    fun applyPreset(preset: EffectPreset, replaceLooks: Boolean = true) {
        if (selection.value.isEmpty()) { addAdjustmentLayer(Presets.instantiate(preset)); return }
        updateSelected("Filtre ${preset.name}") { c -> c.copy(effects = (if (replaceLooks) emptyList() else c.effects) + Presets.instantiate(preset)) }
    }

    fun applyPresetToAll(preset: EffectPreset) = edit("Filtre ${preset.name} partout") { p ->
        TimelineEditor.mapClips(p) { c -> if (c.kind == ClipKind.MEDIA && p.asset(c.assetId)?.isVisual == true) c.copy(effects = Presets.instantiate(preset)) else c }
    }

    fun savePresetFromSelection(name: String) {
        val cl = selectedClip() ?: return
        if (cl.effects.isEmpty()) { message.value = "Aucun effet à enregistrer"; return }
        c.presets.save(name, cl.effects); message.value = "Préréglage « $name » enregistré"
    }

    fun setTransition(id: String, transitionId: String?, durationUs: Long? = null, params: Map<String, Double>? = null) {
        val cl = clip(id) ?: return
        val tr = transitionId?.let { tid -> TransitionInstance(tid, durationUs ?: cl.transitionIn?.durationUs ?: c.settings.value.defaultTransitionMs * 1000L, params ?: cl.transitionIn?.params ?: TransitionRegistry[tid]?.params?.associate { it.key to it.default } ?: emptyMap()) }
        edit(if (tr == null) "Retirer la transition" else "Transition", "tr_$id") { TimelineEditor.setTransition(it, id, tr) }
    }

    fun setTransitionAll(transitionId: String?) {
        val main = current.tracks.firstOrNull { it.kind == TrackKind.VIDEO } ?: return
        edit("Transition sur toutes les coupes") { TimelineEditor.setTransitionAll(it, main.id, transitionId?.let { t -> TransitionInstance(t, c.settings.value.defaultTransitionMs * 1000L) }) }
    }

    // ------------------------------------------------------------------ format & project

    fun changeFormat(f: ProjectFormat, fill: Boolean?) = edit("Format du projet") { TimelineEditor.changeFormat(it, f, fill) }
    fun setBackgroundColor(color: Long) = edit("Couleur de fond", "bg") { it.copy(format = it.format.copy(backgroundColor = color)) }
    fun rename(name: String) = edit("Renommer") { it.copy(name = name) }

    // ------------------------------------------------------------------ voice over

    fun startRecording(): Boolean {
        return try {
            preview.pause()
            recordStartUs = position
            recorder.start(); recording.value = true
            preview.play() // play the edit while recording so the user can follow
            true
        } catch (e: Exception) { message.value = "Micro indisponible : ${e.message}"; false }
    }

    fun stopRecording() {
        preview.pause()
        val f = recorder.stop(); recording.value = false
        if (f == null) { message.value = "Enregistrement trop court"; return }
        viewModelScope.launch(Dispatchers.IO) {
            val a = runCatching { com.superessence.cuttinx.media.MediaProbe.probe(ctx, Uri.fromFile(f), MediaType.AUDIO) }.getOrNull() ?: run { message.value = "Enregistrement illisible"; return@launch }
            withContext(Dispatchers.Main) {
                edit("Voix off") { addAudioAt(it, a.copy(displayName = "Voix off ${current.allClips.count { c -> c.name.startsWith("Voix") } + 1}", folder = "Voix off"), recordStartUs, AudioRole.VOICE_OVER) }
            }
        }
    }

    // ------------------------------------------------------------------ export

    fun resolveExport(s: ExportSettings): ResolvedExport {
        val maxShort = current.media.filter { it.type == MediaType.VIDEO && it.width > 0 }.maxOfOrNull { minOf(it.width, it.height) }
        return ExportPlanner.resolve(s, current.format, current.durationUs, CodecInfo.encoderCaps(), maxShort)
    }

    fun startExport(s: ExportSettings) {
        if (current.durationUs <= 0) { message.value = "Le projet est vide"; return }
        save()
        edit("Paramètres d'export", "export") { it.copy(export = s) }
        preview.pause()
        val r = resolveExport(s)
        c.export.start(current, r, s.destinationFolder, s.fileName.ifBlank { current.name })
    }

    // ------------------------------------------------------------------ utils

    fun snapCandidates(exclude: Set<String>) = if (c.settings.value.snapping) Snapping.candidates(current, exclude, position, beats.value) else emptyList()

    fun evaluatedTransform(clip: Clip) = FrameEvaluator.transform(clip, (position - clip.startUs).coerceIn(0, clip.durationUs), withMotion = false)

    class Factory(private val app: Application, private val id: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = EditorViewModel(app, id) as T
    }
}
