package com.superessence.cuttinx.desktop.engine

import com.superessence.cuttinx.core.autoedit.ProTools
import com.superessence.cuttinx.core.effects.EffectRegistry
import com.superessence.cuttinx.core.effects.TransitionRegistry
import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.project.ProjectArchive
import com.superessence.cuttinx.core.project.ProjectStore
import com.superessence.cuttinx.core.resources.Synth
import com.superessence.cuttinx.core.timeline.EditResult
import com.superessence.cuttinx.core.timeline.EditSession
import com.superessence.cuttinx.core.timeline.TimelineEditor
import com.superessence.cuttinx.core.util.Ids
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Export progress (null = idle). */
data class ExportProgress(val fraction: Double, val label: String)

/** A ready-made title style of the text library (same list as the Android app). */
data class TitleTemplate(val name: String, val style: TextStyle, val animIn: TextAnimation, val animOut: TextAnimation, val sample: String)

/**
 * Everything the PC video editor does, independent from the UI: projects on disk, the edit session
 * (undo / redo), media import, resources, editing commands, preview player and export.
 */
class EditorModel(val home: File = File(System.getProperty("user.home"), "CuttinxPRO")) {
    val store = ProjectStore(File(home, "projets").also { it.mkdirs() })
    val resDir = File(home, "ressources").also { it.mkdirs() }
    val importDir = File(home, "imports").also { it.mkdirs() }

    val projects = MutableStateFlow(runCatching { store.list() }.getOrDefault(emptyList()))
    val project = MutableStateFlow<Project?>(null)
    val selection = MutableStateFlow<Set<String>>(emptySet())
    val message = MutableStateFlow<String?>(null)
    val export = MutableStateFlow<ExportProgress?>(null)
    val canUndo = MutableStateFlow(false)
    val canRedo = MutableStateFlow(false)

    private var session: EditSession? = null
    val text = PcText { id -> project.value?.asset(id)?.let { PcMedia.fileOf(it) } }
    val player = PcPlayer({ session?.current ?: EMPTY }, text)
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "cuttinx-io").apply { isDaemon = true } }
    private val cancelExport = AtomicBoolean(false)
    @Volatile private var savePending = false

    val current: Project? get() = session?.current
    val playhead: Long get() = player.position.value
    val selectedClip: Clip? get() = selection.value.firstOrNull()?.let { id -> current?.findClip(id)?.second }

    // ------------------------------------------------------------------ projects

    fun refreshProjects() { projects.value = runCatching { store.list() }.getOrDefault(emptyList()) }

    fun create(name: String, aspect: AspectPreset, fps: Int = 30): Project {
        val now = System.currentTimeMillis()
        val p = Project(Ids.new("prj"), name.ifBlank { "Nouveau projet" }, now, now, ProjectFormat.forAspect(aspect, 1080, fps))
        store.save(p)
        open(p)
        return p
    }

    fun open(id: String) = runCatching { open(store.load(id)) }.onFailure { message.value = "Ouverture impossible : ${it.message}" }

    private fun open(p0: Project) {
        // media moved / deleted since the last time are flagged so the user can relink them
        val p = p0.copy(media = p0.media.map { it.copy(missing = PcMedia.fileOf(it) == null) })
        session = EditSession(p)
        project.value = p
        selection.value = emptySet()
        player.pause(); player.seek(0); player.invalidate()
        updateHistory()
    }

    fun close() {
        player.pause()
        current?.let { p -> io.execute { runCatching { store.save(p) } } }
        session = null; project.value = null; selection.value = emptySet()
        io.execute { refreshProjects() }
    }

    /** Synchronous save (window closing). */
    fun saveNow() { player.pause(); current?.let { runCatching { store.save(it) } } }

    fun delete(id: String) { store.delete(id); refreshProjects() }

    fun duplicate(id: String) = runCatching { store.duplicate(id, store.load(id).name + " (copie)"); refreshProjects() }

    fun rename(name: String) = edit("Renommer") { it.copy(name = name) }

    /** Imports a .cuttinx archive (from the Android app or another PC). */
    fun importArchive(f: File) = runCatching {
        val imp = f.inputStream().buffered().use { ProjectArchive.read(it, File(importDir, Ids.new("a"))) }
        val p = imp.project.copy(media = imp.project.media.map { a -> imp.mediaFiles[a.id]?.let { a.copy(uri = it.absolutePath, missing = false) } ?: a })
        store.save(p); refreshProjects(); open(p)
        val missing = p.media.count { PcMedia.fileOf(it) == null }
        message.value = if (missing > 0) "Projet importé ✅ $missing média(s) à relier (clic sur le média ⚠)" else "Projet importé ✅"
    }.onFailure { message.value = "Import impossible : ${it.message}" }

    /** Saves a .cuttinx archive with the media inside (to continue on another device). */
    fun exportArchive(target: File, includeMedia: Boolean) = io.execute {
        val p = current ?: return@execute
        runCatching {
            target.outputStream().buffered().use { out ->
                ProjectArchive.write(out, p, includeMedia) { id -> p.asset(id)?.let { a -> PcMedia.fileOf(a)?.let { f -> f.name to f.inputStream() } } }
            }
        }.onSuccess { message.value = "Projet enregistré : ${target.name}" }.onFailure { message.value = "Enregistrement impossible : ${it.message}" }
    }

    // ------------------------------------------------------------------ editing core

    /** Applies an undoable edit; problems are shown to the user instead of crashing. */
    fun edit(label: String, key: String? = null, op: (Project) -> Project): Boolean {
        val s = session ?: return false
        val r = s.edit(label, key, op)
        if (r is EditResult.Failed) { message.value = r.message; return false }
        changed()
        return true
    }

    private fun changed() {
        val p = session?.current ?: return
        project.value = p
        selection.value = selection.value.filter { p.findClip(it) != null }.toSet()
        player.invalidate()
        updateHistory()
        scheduleSave()
    }

    private fun updateHistory() { canUndo.value = session?.history?.canUndo == true; canRedo.value = session?.history?.canRedo == true }

    private fun scheduleSave() {
        if (savePending) return
        savePending = true
        io.execute {
            Thread.sleep(800)
            savePending = false
            session?.current?.let { runCatching { store.save(it) } }
        }
    }

    fun undo() { session?.undo()?.let { message.value = "Annulé : $it" }; changed() }
    fun redo() { session?.redo()?.let { message.value = "Rétabli : $it" }; changed() }

    fun select(id: String?, add: Boolean = false) {
        val p = current ?: return
        if (id == null) { selection.value = emptySet(); return }
        val ids = TimelineEditor.expandGroups(p, listOf(id)).toSet()
        selection.value = if (add) (if (id in selection.value) selection.value - ids else selection.value + ids) else ids
    }

    fun updateClip(label: String, key: String? = null, f: (Clip) -> Clip) {
        val id = selection.value.firstOrNull() ?: return
        edit(label, key) { TimelineEditor.updateClip(it, id, f) }
    }

    // ------------------------------------------------------------------ timeline commands

    fun splitAtPlayhead() {
        val t = playhead
        val p = current ?: return
        val targets = selection.value.mapNotNull { p.findClip(it)?.second }.filter { it.contains(t) && t > it.startUs }
        if (targets.isEmpty()) { edit("Couper tout") { TimelineEditor.splitAll(it, t) }; return }
        edit("Couper") { pr -> targets.fold(pr) { acc, c -> TimelineEditor.split(acc, c.id, t).first } }
    }

    fun deleteSelection(ripple: Boolean = false) {
        val ids = selection.value
        if (ids.isEmpty()) return
        if (edit(if (ripple) "Supprimer et resserrer" else "Supprimer") { TimelineEditor.deleteClips(it, ids, ripple) }) selection.value = emptySet()
    }

    fun duplicateSelection() {
        val id = selection.value.firstOrNull() ?: return
        var newId: String? = null
        if (edit("Dupliquer") { TimelineEditor.duplicate(it, id).also { r -> newId = r.second }.first }) newId?.let { selection.value = setOf(it) }
    }

    fun moveClip(id: String, newStartUs: Long, targetTrackId: String?) = edit("Déplacer") { TimelineEditor.moveClipTo(it, id, newStartUs.coerceAtLeast(0), targetTrackId) }
    fun trimStart(id: String, us: Long) = edit("Rogner le début", "trims_$id") { TimelineEditor.trimStart(it, id, us) }
    fun trimEnd(id: String, us: Long) = edit("Rogner la fin", "trime_$id") { TimelineEditor.trimEnd(it, id, us) }

    fun addTrack(kind: TrackKind) = edit("Nouvelle piste") { TimelineEditor.addTrack(it, kind).first }
    fun updateTrack(id: String, label: String, f: (Track) -> Track) = edit(label) { TimelineEditor.updateTrack(it, id, f) }
    fun removeTrack(id: String) = edit("Supprimer la piste") { TimelineEditor.removeTrack(it, id) }
    fun addMarker() = edit("Marqueur") { TimelineEditor.addMarker(it, playhead) }

    /**
     * Places [clip] on a track of [kind] (and [role]) that is free at its time range; a new track is
     * created when all of them are busy, so adding never moves the user's existing clips.
     */
    private fun place(p: Project, kind: TrackKind, clip: Clip, role: AudioRole = AudioRole.GENERAL): Project {
        val free = p.tracks.firstOrNull { t -> t.kind == kind && !t.locked && (kind != TrackKind.AUDIO || t.audioRole == role) && t.clips.none { it.startUs < clip.endUs && clip.startUs < it.endUs } }
        val (np, t) = if (free != null) p to free else TimelineEditor.addTrack(p, kind, role = role)
        return TimelineEditor.insertClip(np, t.id, clip)
    }

    // ------------------------------------------------------------------ media & resources

    /** Imports files: videos/images go to the end of the main track, sounds at the playhead. */
    fun importFiles(files: List<File>, addToTimeline: Boolean = true) = io.execute {
        val ok = ArrayList<MediaAsset>(); val bad = ArrayList<String>()
        for (f in files) runCatching { PcMedia.probe(f) }.onSuccess { ok += it }.onFailure { bad += "${f.name} (${it.message})" }
        if (ok.isNotEmpty()) edit("Importer ${ok.size} média(s)") { p ->
            var np = p
            for (a in ok) {
                np = TimelineEditor.addMedia(np, a)
                if (!addToTimeline) continue
                np = if (a.type == MediaType.AUDIO) place(np, TrackKind.AUDIO, TimelineEditor.clipFromAsset(a, playhead), AudioRole.MUSIC)
                else TimelineEditor.appendAsset(np, a).first
            }
            np
        }
        message.value = when {
            bad.isEmpty() -> "${ok.size} média(s) importé(s) ✅"
            else -> "Non importé : " + bad.joinToString(", ")
        }
    }

    fun addAssetToTimeline(a: MediaAsset) = edit("Ajouter ${a.displayName}") { p ->
        if (a.type == MediaType.AUDIO) place(p, TrackKind.AUDIO, TimelineEditor.clipFromAsset(a, playhead), AudioRole.MUSIC)
        else {
            val main = p.tracks.firstOrNull { it.kind == TrackKind.VIDEO }
            // on the main track at the playhead when it is free there, otherwise as an overlay
            val clip = TimelineEditor.clipFromAsset(a, playhead)
            if (main == null || main.clips.isEmpty()) TimelineEditor.appendAsset(p, a).first
            else place(p, TrackKind.VIDEO, clip)
        }
    }

    fun relink(a: MediaAsset, f: File) = runCatching {
        val probed = PcMedia.probe(f)
        edit("Relier ${a.displayName}") { TimelineEditor.relinkMedia(it, a.id, f.absolutePath, probed.durationUs) }
    }.onFailure { message.value = "Fichier illisible : ${it.message}" }

    private fun resourceAsset(kind: String, id: String, name: String, folder: String, make: () -> Synth.Audio): MediaAsset {
        val f = File(resDir, "${kind}_$id.wav")
        val audio = if (!f.exists()) make().also { PcMedia.writeWav(f, it.samples, it.sampleRate, it.channels) } else null
        val dur = audio?.durationUs ?: runCatching { PcMedia.probe(f).durationUs }.getOrDefault(1_000_000)
        return MediaAsset("res_${kind}_$id", f.absolutePath, name, MediaType.AUDIO, "audio/wav", dur, hasAudio = true, hasVideo = false, sampleRate = Synth.SR, folder = folder, tags = listOf("cuttinx"))
    }

    fun sfxFile(id: String): File = File(resourceAsset("sfx", id, id, "Bruitages") { Synth.sfx(id) }.uri)

    fun addSfx(id: String) {
        val e = Synth.sfxList.first { it.id == id }
        val a = resourceAsset("sfx", id, e.name, "Bruitages") { Synth.sfx(id) }
        edit("Bruitage ${e.name}") { p -> place(TimelineEditor.addMedia(p, a), TrackKind.AUDIO, TimelineEditor.clipFromAsset(a, playhead), AudioRole.SFX) }
    }

    fun addMusic(id: String) = io.execute {
        val e = Synth.musicList.first { it.id == id }
        message.value = "Préparation de « ${e.name} »…"
        val a = resourceAsset("music", id, e.name, "Musiques") { Synth.music(id) }
        edit("Musique ${e.name}") { p -> place(TimelineEditor.addMedia(p, a), TrackKind.AUDIO, TimelineEditor.clipFromAsset(a, playhead), AudioRole.MUSIC) }
        message.value = "Musique ajoutée ✅"
    }

    fun addText(t: TitleTemplate, subtitle: Boolean = false) {
        val base = TimelineEditor.textClip(t.sample, playhead, kind = if (subtitle) ClipKind.SUBTITLE else ClipKind.TEXT, style = t.style)
        val clip = base.copy(text = base.text!!.copy(animIn = t.animIn, animOut = t.animOut))
        if (edit("Texte ${t.name}") { p -> place(p, if (subtitle) TrackKind.SUBTITLE else TrackKind.TEXT, clip) }) selection.value = setOf(clip.id)
    }

    fun addColor(color: Long) = edit("Fond de couleur") { p -> place(p, TrackKind.VIDEO, TimelineEditor.colorClip(color, playhead)) }

    fun addAdjustment(effectId: String) {
        val def = EffectRegistry[effectId] ?: return
        val c = TimelineEditor.adjustmentClip(playhead).copy(effects = listOf(EffectInstance(Ids.new("fx"), effectId, def.defaults())), name = def.name)
        if (edit("Calque ${def.name}") { p -> place(p, TrackKind.EFFECT, c) }) selection.value = setOf(c.id)
    }

    // ------------------------------------------------------------------ effects, transitions, motion

    /** Video effect on the selected clip, or on an effect layer over everything when nothing is selected. */
    fun applyEffect(effectId: String) {
        val def = EffectRegistry[effectId] ?: return
        val c = selectedClip
        if (c == null || c.kind == ClipKind.TEXT || c.kind == ClipKind.SUBTITLE) { addAdjustment(effectId); return }
        updateClip("Effet ${def.name}") { it.copy(effects = it.effects + EffectInstance(Ids.new("fx"), effectId, def.defaults())) }
    }

    fun applyAudioEffects(label: String, vararg ids: Pair<String, Map<String, Double>>) {
        val c = selectedClip ?: run { message.value = "Sélectionne d'abord un clip avec du son"; return }
        if (current?.asset(c.assetId)?.hasAudio != true) { message.value = "Ce clip n'a pas de son"; return }
        updateClip(label) { cl -> cl.copy(audio = cl.audio.copy(effects = cl.audio.effects + ids.map { (id, params) -> EffectInstance(Ids.new("fx"), id, (EffectRegistry[id]?.defaults() ?: emptyMap()) + params) })) }
    }

    fun applyTransition(id: String, all: Boolean = false) {
        val def = TransitionRegistry[id] ?: return
        val tr = TransitionInstance(id, def.defaultDurationUs, def.params.associate { it.key to it.default })
        val c = selectedClip
        if (all || c == null) {
            val main = current?.tracks?.firstOrNull { it.kind == TrackKind.VIDEO } ?: return
            edit("Transition ${def.name} partout") { TimelineEditor.setTransitionAll(it, main.id, tr) }
        } else edit("Transition ${def.name}") { TimelineEditor.setTransition(it, c.id, tr) }
    }

    fun removeTransition() { val c = selectedClip ?: return; edit("Retirer la transition") { TimelineEditor.setTransition(it, c.id, null) } }

    fun setMotion(f: (ClipMotion) -> ClipMotion) = updateClip("Animation") { it.copy(motion = f(it.motion)) }

    /** Pro tools: one click edits for creators. */
    fun pro(tool: String, arg: String = "") {
        val p = current ?: return
        val main = p.tracks.firstOrNull { it.kind == TrackKind.VIDEO }
        when (tool) {
            "slowzoom" -> if (main != null) edit("Zoom lent sur tout le montage") { ProTools.slowZoomAll(it, main.id) }
            "autoanim" -> if (main != null) edit("Animations automatiques") { ProTools.autoAnimate(it, main.id) }
            "kenburns" -> edit("Photos animées") { ProTools.kenBurnsImages(it) }
            "autotrans" -> if (main != null) edit("Transitions automatiques") { ProTools.autoTransitions(it, main.id) }
            "progress" -> edit("Barre de progression") { ProTools.progressBar(it) }
            "watermark" -> edit("Pseudo en filigrane") { ProTools.handleWatermark(it, arg.ifBlank { "@moncompte" }) }
            "hook" -> edit("Titre d'accroche") { ProTools.hookTitle(it, arg.ifBlank { "ATTENDS LA FIN 😱" }) }
            "captions" -> edit("Style sous-titres créateur") { ProTools.creatorCaptions(it) }
        }
    }

    fun changeFormat(aspect: AspectPreset) = edit("Format ${aspect.label}") { TimelineEditor.changeFormat(it, ProjectFormat.forAspect(aspect, 1080, it.format.frameRate).copy(backgroundColor = it.format.backgroundColor)) }

    // ------------------------------------------------------------------ export

    fun startExport(target: File, shortSide: Int, fps: Int, onDone: (File) -> Unit = {}) {
        val p = current ?: return
        if (export.value != null) return
        if (p.durationUs <= 0) { message.value = "Le montage est vide"; return }
        cancelExport.set(false)
        export.value = ExportProgress(0.0, "Préparation…")
        player.pause()
        Thread({
            runCatching {
                PcExporter(text).export(p, target, shortSide, fps, cancelExport) { f -> export.value = ExportProgress(f, "Export ${(f * 100).toInt()} %") }
            }.onSuccess { r -> message.value = "Vidéo exportée ✅ ${r.file.name}"; onDone(r.file) }
                .onFailure { message.value = if (it is InterruptedException) "Export annulé" else "Export impossible : ${it.message}" }
            export.value = null
        }, "cuttinx-export").apply { isDaemon = true; start() }
    }

    fun cancelExport() = cancelExport.set(true)

    companion object {
        private val EMPTY = Project("none", "")

        val titles = listOf(
            TitleTemplate("Impact", TextStyle(fontFamily = "asset:fonts/Anton_400Regular.ttf", sizeSp = 72.0, uppercase = true, strokeWidth = 5.0, strokeColor = 0xFF000000), TextAnimation.POP, TextAnimation.FADE, "TITRE CHOC"),
            TitleTemplate("Essence", TextStyle(fontFamily = "asset:fonts/Montserrat_800ExtraBold.ttf", sizeSp = 60.0, color = 0xFFFFCC1F, shadowRadius = 10.0), TextAnimation.SLIDE_UP, TextAnimation.FADE, "SuperEssence"),
            TitleTemplate("Néon", TextStyle(fontFamily = "asset:fonts/Righteous_400Regular.ttf", sizeSp = 58.0, color = 0xFFFFFFFF, shadowRadius = 24.0, shadowDy = 0.0, shadowColor = 0xFFFF3D8B), TextAnimation.FADE, TextAnimation.FADE, "Nuit néon"),
            TitleTemplate("Cinéma", TextStyle(fontFamily = "asset:fonts/BebasNeue_400Regular.ttf", sizeSp = 80.0, letterSpacing = 0.3, shadowRadius = 0.0), TextAnimation.ZOOM, TextAnimation.FADE, "LE FILM"),
            TitleTemplate("Bande dessinée", TextStyle(fontFamily = "asset:fonts/Bangers_400Regular.ttf", sizeSp = 70.0, color = 0xFFFFE14D, strokeWidth = 6.0), TextAnimation.BOUNCE, TextAnimation.ZOOM, "BOUM !"),
            TitleTemplate("Manuscrit", TextStyle(fontFamily = "asset:fonts/Caveat_700Bold.ttf", sizeSp = 64.0, bold = false, shadowRadius = 4.0), TextAnimation.TYPEWRITER, TextAnimation.FADE, "Carnet de voyage"),
            TitleTemplate("Élégant", TextStyle(fontFamily = "asset:fonts/PlayfairDisplay_700Bold_Italic.ttf", sizeSp = 56.0, bold = false, color = 0xFFF5E6C8), TextAnimation.BLUR_IN, TextAnimation.FADE, "Un soir d'été"),
            TitleTemplate("Étiquette", TextStyle(fontFamily = "asset:fonts/Montserrat_800ExtraBold.ttf", sizeSp = 40.0, color = 0xFF000000, backgroundColor = 0xFFFFCC1F, shadowRadius = 0.0, backgroundPadding = 18.0), TextAnimation.SLIDE_RIGHT, TextAnimation.SLIDE_LEFT, "NOUVEAU"),
            TitleTemplate("Dégradé", TextStyle(fontFamily = "asset:fonts/Fredoka_600SemiBold.ttf", sizeSp = 66.0, gradientEnabled = true, gradientStart = 0xFFFFCC1F, gradientEnd = 0xFFFF3D8B), TextAnimation.WAVE, TextAnimation.FADE, "Coucou !"),
            TitleTemplate("Rétro 8-bit", TextStyle(fontFamily = "asset:fonts/PressStart2P_400Regular.ttf", sizeSp = 34.0, color = 0xFF9CFF3D, shadowRadius = 0.0, strokeWidth = 3.0), TextAnimation.TYPEWRITER, TextAnimation.NONE, "GAME OVER"),
            TitleTemplate("Karaoké", TextStyle(fontFamily = "asset:fonts/Montserrat_800ExtraBold.ttf", sizeSp = 46.0, color = 0xFFFFE14D, strokeWidth = 4.0, uppercase = true), TextAnimation.KARAOKE, TextAnimation.FADE, "Les mots apparaissent"),
            TitleTemplate("Sous-titre clair", TextStyle(sizeSp = 34.0, strokeWidth = 3.0, shadowRadius = 4.0), TextAnimation.NONE, TextAnimation.NONE, "Sous-titre lisible"),
            TitleTemplate("Sous-titre boîte", TextStyle(sizeSp = 32.0, backgroundColor = 0xCC000000, shadowRadius = 0.0, backgroundPadding = 12.0), TextAnimation.NONE, TextAnimation.NONE, "Sous-titre en boîte"),
            TitleTemplate("Générique", TextStyle(fontFamily = "asset:fonts/Oswald_600SemiBold.ttf", sizeSp = 40.0, letterSpacing = 0.2, lineSpacing = 1.5), TextAnimation.SLIDE_UP, TextAnimation.SLIDE_UP, "Réalisation\nVOTRE NOM"),
        )

        val fonts = listOf(
            "Système" to "sans-serif", "Serif" to "serif", "Mono" to "monospace",
            "Bebas Neue" to "asset:fonts/BebasNeue_400Regular.ttf", "Anton" to "asset:fonts/Anton_400Regular.ttf",
            "Montserrat ExtraBold" to "asset:fonts/Montserrat_800ExtraBold.ttf", "Montserrat" to "asset:fonts/Montserrat_500Medium.ttf",
            "Oswald" to "asset:fonts/Oswald_600SemiBold.ttf", "Bangers" to "asset:fonts/Bangers_400Regular.ttf", "Righteous" to "asset:fonts/Righteous_400Regular.ttf",
            "Fredoka" to "asset:fonts/Fredoka_600SemiBold.ttf", "Pacifico" to "asset:fonts/Pacifico_400Regular.ttf", "Lobster" to "asset:fonts/Lobster_400Regular.ttf",
            "Caveat" to "asset:fonts/Caveat_700Bold.ttf", "Permanent Marker" to "asset:fonts/PermanentMarker_400Regular.ttf", "Abril Fatface" to "asset:fonts/AbrilFatface_400Regular.ttf",
            "Playfair Display" to "asset:fonts/PlayfairDisplay_700Bold_Italic.ttf", "Press Start 2P" to "asset:fonts/PressStart2P_400Regular.ttf",
        )
    }
}
