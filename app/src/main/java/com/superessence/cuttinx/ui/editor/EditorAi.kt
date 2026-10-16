package com.superessence.cuttinx.ui.editor

import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import com.superessence.cuttinx.ai.SpeechModelInfo
import com.superessence.cuttinx.core.analysis.*
import com.superessence.cuttinx.core.anim.SpeedMath
import com.superessence.cuttinx.core.audio.AudioAnalysis
import com.superessence.cuttinx.core.audio.AudioReport
import com.superessence.cuttinx.core.audio.OfflineAudio
import com.superessence.cuttinx.core.autoedit.*
import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.project.ProjectStore
import com.superessence.cuttinx.core.subtitles.Cue
import com.superessence.cuttinx.core.subtitles.SubtitleCodec
import com.superessence.cuttinx.core.timeline.TimelineEditor
import com.superessence.cuttinx.core.util.Ids
import com.superessence.cuttinx.media.FrameSampler
import com.superessence.cuttinx.media.MediaProbe
import com.superessence.cuttinx.media.PcmDecoder
import com.superessence.cuttinx.media.Thumbnails
import com.superessence.cuttinx.media.WavWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File

/** Results shown in the AI Lab for review before being applied. */
sealed interface AiResult {
    data class Silences(val clipId: String, val ranges: List<TimeRange>, val totalUs: Long) : AiResult
    data class Scenes(val clipId: String, val cutsSourceUs: List<Long>) : AiResult
    data class Highlights(val clipId: String, val items: List<Highlight>) : AiResult
    data class Beats(val clipId: String, val result: BeatResult, val timelineBeats: List<Long>) : AiResult
    data class Report(val clipId: String, val report: AudioReport) : AiResult
    data class Titles(val items: List<String>) : AiResult
    data class Summary(val text: String, val points: List<Pair<Long, String>>) : AiResult
    data class SuggestionList(val items: List<Suggestion>) : AiResult
    data class Transcript(val clipId: String, val cues: List<Cue>) : AiResult
    data class AutoEdit(val proposal: AutoEditProposal, val request: AutoEditRequest) : AiResult
}

@UnstableApi
object EditorAi {
    val result = MutableStateFlow<AiResult?>(null)
    val modelDownload = MutableStateFlow<Pair<String, Float>?>(null)

    private fun EditorViewModel.clipOrFail(id: String?): Pair<Clip, MediaAsset> {
        val c = clip(id) ?: error("Sélectionnez un élément de la timeline")
        val a = current.asset(c.assetId) ?: error("Cet élément n'a pas de média source")
        if (a.missing) error("Le média source est introuvable")
        return c to a
    }

    // ------------------------------------------------------------------ speech

    fun downloadModel(vm: EditorViewModel, m: SpeechModelInfo) = vm.runTask("Téléchargement du modèle ${m.language} (${m.sizeMb} Mo)") { prog ->
        withContext(Dispatchers.IO) { vm.c.speech.download(m, { prog(it, "${(it * 100).toInt()} %") }, { !isActive }) }
        vm.message.value = "Modèle ${m.language} installé : la reconnaissance fonctionne maintenant hors ligne"
    }

    /** Transcribes the selected clip (or every clip of the main track) and adds editable subtitles. */
    fun autoSubtitles(vm: EditorViewModel, model: SpeechModelInfo, clipIds: List<String>, maxChars: Int = 32) = vm.runTask("Sous-titres automatiques") { prog ->
        if (!vm.c.speech.isInstalled(model)) error("Téléchargez d'abord le modèle ${model.language}")
        var all = 0
        clipIds.forEachIndexed { i, id ->
            val (c, a) = vm.clipOrFail(id)
            if (!a.hasAudio) return@forEachIndexed
            val words = withContext(Dispatchers.IO) {
                vm.c.speech.transcribe(model, Uri.parse(a.uri), c.sourceInUs, c.sourceOutUs, { p -> prog((i + p) / clipIds.size, "Élément ${i + 1}/${clipIds.size}") }, { !isActive })
            }
            val cues = SubtitleCodec.segment(words, maxChars)
            all += cues.size
            withContext(Dispatchers.Main) { vm.edit("Sous-titres automatiques") { AiActions.addSubtitlesForClip(it, id, cues) } }
            result.value = AiResult.Transcript(id, cues)
        }
        vm.message.value = if (all == 0) "Aucune parole reconnue" else "$all sous-titres ajoutés — relisez-les : la reconnaissance n'est jamais parfaite."
    }

    // ------------------------------------------------------------------ audio analysis

    private suspend fun EditorViewModel.pcmOf(c: Clip, a: MediaAsset, mono: Boolean = true) = withContext(Dispatchers.IO) {
        PcmDecoder.decode(ctx, Uri.parse(a.uri), c.sourceInUs, c.sourceOutUs, downmix = mono, maxSeconds = 1800.0)
    }

    fun detectSilences(vm: EditorViewModel, clipId: String?, marginDb: Double = 10.0, minSilenceMs: Int = 450) = vm.runTask("Détection des silences") { prog ->
        val (c, a) = vm.clipOrFail(clipId)
        if (!a.hasAudio) error("Cet élément n'a pas de son")
        prog(0.1f, "Décodage audio")
        val pcm = vm.pcmOf(c, a)
        prog(0.7f, "Analyse")
        val ranges = SilenceDetector.detect(pcm.samples, pcm.sampleRate, 1, SilenceDetector.Params(marginDb = marginDb, minSilenceUs = minSilenceMs * 1000L))
            .map { TimeRange(it.startUs + c.sourceInUs, it.endUs + c.sourceInUs) }
        result.value = AiResult.Silences(c.id, ranges, ranges.sumOf { it.durationUs })
        vm.panel.value = Panel.AI
    }

    fun applySilenceRemoval(vm: EditorViewModel, r: AiResult.Silences) {
        if (vm.edit("Supprimer ${r.ranges.size} silences") { AiActions.removeSourceRanges(it, r.clipId, r.ranges) }) {
            vm.message.value = "%.1f s de silence retirées (annulable)".format(r.totalUs / 1e6); result.value = null
        }
    }

    fun beats(vm: EditorViewModel, clipId: String?) = vm.runTask("Détection du rythme") { prog ->
        val (c, a) = vm.clipOrFail(clipId)
        prog(0.1f, "Décodage audio")
        val pcm = vm.pcmOf(c, a)
        prog(0.6f, "Analyse du tempo")
        val r = BeatDetector.detect(pcm.samples, pcm.sampleRate, 1)
        val beats = r.beatsUs.map { c.startUs + SpeedMath.sourceToTimeline(c, c.sourceInUs + it) }.filter { it <= c.endUs }
        vm.beats.value = beats
        result.value = AiResult.Beats(c.id, r, beats)
    }

    fun syncMainTrackToBeats(vm: EditorViewModel) {
        val main = vm.current.tracks.firstOrNull { it.kind == TrackKind.VIDEO } ?: return
        vm.edit("Synchroniser sur la musique") { AiActions.syncCutsToBeats(it, main.id, vm.beats.value) }
    }

    fun beatMarkers(vm: EditorViewModel, every: Int) = vm.edit("Marqueurs de rythme") { AiActions.addBeatMarkers(it, vm.beats.value, every = every) }

    fun audioReport(vm: EditorViewModel, clipId: String?) = vm.runTask("Analyse de la qualité audio") { prog ->
        val (c, a) = vm.clipOrFail(clipId)
        prog(0.1f, "Décodage audio")
        val pcm = vm.pcmOf(c, a, mono = false)
        prog(0.6f, "Mesures")
        result.value = AiResult.Report(c.id, AudioAnalysis.report(pcm.samples, pcm.sampleRate, pcm.channels))
    }

    fun normalize(vm: EditorViewModel, ids: Collection<String>, targetLufs: Double = -14.0) = vm.runTask("Normalisation du volume") { prog ->
        ids.forEachIndexed { i, id ->
            val (c, a) = vm.clipOrFail(id)
            if (!a.hasAudio) return@forEachIndexed
            prog(i.toFloat() / ids.size, c.name)
            val pcm = vm.pcmOf(c, a, mono = false)
            val g = AudioAnalysis.normalizationGain(pcm.samples, pcm.sampleRate, pcm.channels, targetLufs)
            withContext(Dispatchers.Main) { vm.updateClip("Normaliser", id = id) { it.copy(audio = it.audio.copy(normalizeGainDb = g)) } }
        }
        vm.message.value = "Volume normalisé à $targetLufs LUFS (mesure BS.1770)"
    }

    /** Offline processing (denoise / voice isolation / karaoke) producing a new audio file. */
    fun processAudio(vm: EditorViewModel, clipId: String?, mode: String, amount: Double = 0.8) = vm.runTask(when (mode) { "denoise" -> "Réduction de bruit"; "voice" -> "Isolation de la voix"; else -> "Suppression de la voix" }) { prog ->
        val (c, a) = vm.clipOrFail(clipId)
        if (!a.hasAudio) error("Cet élément n'a pas de son")
        prog(0.05f, "Décodage audio")
        val pcm = vm.pcmOf(c, a, mono = false)
        if (pcm.durationUs > 20 * 60 * 1_000_000L) error("Extrait trop long (> 20 min) : divisez l'élément")
        prog(0.35f, "Traitement spectral")
        val out = when (mode) {
            "denoise" -> OfflineAudio.denoise(pcm.samples, pcm.sampleRate, pcm.channels, amount)
            "voice" -> OfflineAudio.isolateVocals(pcm.samples, pcm.sampleRate, pcm.channels)
            else -> OfflineAudio.removeVocals(pcm.samples, pcm.sampleRate, pcm.channels)
        }
        ensureActive()
        prog(0.85f, "Écriture")
        val dir = File(vm.ctx.filesDir, "processed").also { it.mkdirs() }
        val f = File(dir, "${mode}_${System.currentTimeMillis()}.wav")
        WavWriter.write(f, out, pcm.sampleRate, pcm.channels)
        val label = when (mode) { "denoise" -> "voix nettoyée"; "voice" -> "voix isolée"; else -> "instrumental" }
        val newAsset = MediaAsset(Ids.new("med"), Uri.fromFile(f).toString(), "${a.displayName} ($label)", MediaType.AUDIO, "audio/wav", pcm.durationUs, hasAudio = true, hasVideo = false, sampleRate = pcm.sampleRate, channelCount = pcm.channels, folder = "Traités", dateAddedMs = System.currentTimeMillis())
        withContext(Dispatchers.Main) {
            vm.edit("Audio : $label") { p ->
                var np = TimelineEditor.addMedia(p, newAsset)
                val isAudioOnly = a.type == MediaType.AUDIO
                // the new file covers exactly the clip's source range
                val nc = c.copy(id = Ids.new("clp"), assetId = newAsset.id, name = newAsset.displayName, sourceInUs = 0, sourceOutUs = pcm.durationUs, transitionIn = null, effects = emptyList(), groupId = null,
                    audio = c.audio.copy(muted = false, normalizeGainDb = 0.0, effects = c.audio.effects.filter { it.effectId != "a_denoise" }))
                if (isAudioOnly) TimelineEditor.updateClip(np, c.id) { nc.copy(id = c.id, startUs = c.startUs) }
                else {
                    np = TimelineEditor.updateClip(np, c.id) { it.copy(audio = it.audio.copy(muted = true)) }
                    val t = np.tracks.firstOrNull { tr -> tr.kind == TrackKind.AUDIO && tr.clips.none { it.startUs < nc.endUs && nc.startUs < it.endUs } } ?: TimelineEditor.addTrack(np, TrackKind.AUDIO, role = AudioRole.VOICE_OVER).also { np = it.first }.second
                    TimelineEditor.insertClip(np, t.id, nc)
                }
            }
        }
        vm.message.value = "Nouveau fichier créé (${newAsset.displayName}). L'original reste intact."
    }

    // ------------------------------------------------------------------ video analysis

    private suspend fun EditorViewModel.frames(c: Clip, a: MediaAsset, stepUs: Long, width: Int, exact: Boolean, prog: (Float, String?) -> Unit): List<Frame> = withContext(Dispatchers.IO) {
        FrameSampler.sample(ctx, a, c.sourceInUs, c.sourceOutUs, stepUs, width, exact, { false }) { prog(it * 0.8f, "Lecture des images") }
    }

    private fun step(durUs: Long, maxFrames: Int, minStep: Long) = maxOf(minStep, durUs / maxFrames)

    fun detectScenes(vm: EditorViewModel, clipId: String?) = vm.runTask("Détection des changements de scène") { prog ->
        val (c, a) = vm.clipOrFail(clipId)
        if (a.type != MediaType.VIDEO) error("Sélectionnez une vidéo")
        val frames = vm.frames(c, a, step(c.sourceDurationUs, 900, 120_000), 48, false, prog)
        prog(0.9f, "Analyse")
        val cuts = SceneDetector.detect(frames).filter { it > c.sourceInUs + 300_000 && it < c.sourceOutUs - 300_000 }
        result.value = AiResult.Scenes(c.id, cuts)
    }

    fun splitAtScenes(vm: EditorViewModel, r: AiResult.Scenes) {
        vm.edit("Couper aux changements de scène") { p ->
            var np = p; var id = r.clipId
            for (s in r.cutsSourceUs) { val c = np.findClip(id)?.second ?: break; val t = c.startUs + SpeedMath.sourceToTimeline(c, s); if (t > c.startUs + 100_000 && t < c.endUs - 100_000) id = TimelineEditor.split(np, id, t).also { np = it.first }.second }
            np
        }
        result.value = null
    }

    fun scenesAsMarkers(vm: EditorViewModel, r: AiResult.Scenes) {
        val c = vm.clip(r.clipId) ?: return
        vm.edit("Marqueurs de scène") { p -> r.cutsSourceUs.fold(p) { acc, s -> TimelineEditor.addMarker(acc, c.startUs + SpeedMath.sourceToTimeline(c, s), "Scène") } }
    }

    fun highlights(vm: EditorViewModel, clipId: String?, segmentUs: Long = 3_000_000, count: Int = 5) = vm.runTask("Repérage des moments forts") { prog ->
        val (c, a) = vm.clipOrFail(clipId)
        if (a.type != MediaType.VIDEO) error("Sélectionnez une vidéo")
        val frames = vm.frames(c, a, step(c.sourceDurationUs, 600, 150_000), 48, false, prog)
        val stats = VideoAnalysis.stats(frames).map { it.copy(timeUs = it.timeUs - c.sourceInUs) }
        val audioDb = if (a.hasAudio) { prog(0.85f, "Analyse du son"); val pcm = vm.pcmOf(c, a); AudioAnalysis.envelopeDb(pcm.samples, pcm.sampleRate, 1, 50) } else DoubleArray(0)
        val hl = HighlightDetector.detect(stats, audioDb, 50_000, emptyList(), segmentUs, count, SceneDetector.detectFromStats(stats))
        result.value = AiResult.Highlights(c.id, hl.map { it.copy(startUs = it.startUs + c.sourceInUs, endUs = it.endUs + c.sourceInUs) })
    }

    fun keepOnlyHighlights(vm: EditorViewModel, r: AiResult.Highlights) {
        val c = vm.clip(r.clipId) ?: return
        // remove everything outside the highlights
        val keep = r.items.sortedBy { it.startUs }
        val remove = ArrayList<TimeRange>(); var cur = c.sourceInUs
        for (h in keep) { if (h.startUs > cur) remove += TimeRange(cur, h.startUs); cur = maxOf(cur, h.endUs) }
        if (cur < c.sourceOutUs) remove += TimeRange(cur, c.sourceOutUs)
        vm.edit("Garder les moments forts") { AiActions.removeSourceRanges(it, c.id, remove) }
        result.value = null
    }

    fun suggestCuts(vm: EditorViewModel, clipId: String?) = vm.runTask("Suggestion de points de coupe") { prog ->
        val (c, a) = vm.clipOrFail(clipId)
        val markers = ArrayList<Pair<Long, String>>()
        if (a.type == MediaType.VIDEO) {
            val frames = vm.frames(c, a, step(c.sourceDurationUs, 600, 150_000), 48, false, prog)
            SceneDetector.detect(frames).forEach { markers += it to "Coupe : changement de plan" }
        }
        if (a.hasAudio) {
            prog(0.85f, "Analyse du son")
            val pcm = vm.pcmOf(c, a)
            SilenceDetector.detect(pcm.samples, pcm.sampleRate, 1).forEach { markers += (it.startUs + c.sourceInUs) to "Coupe : début de silence" }
        }
        withContext(Dispatchers.Main) {
            vm.edit("Points de coupe suggérés") { p -> markers.fold(p) { acc, (s, l) -> TimelineEditor.addMarker(acc, c.startUs + SpeedMath.sourceToTimeline(c, s), l) } }
        }
        vm.message.value = "${markers.size} points de coupe suggérés (marqueurs jaunes). Utilisez « Diviser » pour couper."
    }

    fun autoReframe(vm: EditorViewModel, clipId: String?) = vm.runTask("Recadrage automatique") { prog ->
        val (c, a) = vm.clipOrFail(clipId)
        if (!a.isVisual) error("Sélectionnez une vidéo ou une image")
        val frames = if (a.type == MediaType.VIDEO) vm.frames(c, a, step(c.sourceDurationUs, 240, 200_000), 64, false, prog)
        else listOfNotNull(withContext(Dispatchers.IO) { Thumbnails.decodeImage(vm.ctx, Uri.parse(a.uri), 128, a.rotationDegrees) }?.let { toFrame(it, 0) })
        val p = vm.current
        val keys = SubjectAnalysis.autoReframe(frames, a.displayWidth, a.displayHeight, p.format.width, p.format.height)
        withContext(Dispatchers.Main) { vm.edit("Recadrage automatique") { AiActions.applyReframe(it, c.id, keys) } }
        vm.message.value = "Le sujet est suivi par ${keys.size} images clés (modifiables)"
    }

    fun detectSubjectBox(vm: EditorViewModel, clipId: String): SubjectAnalysis.Box? {
        val c = vm.clip(clipId) ?: return null
        val a = vm.current.asset(c.assetId) ?: return null
        val src = SpeedMath.timelineToSource(c, (vm.position - c.startUs).coerceIn(0, c.durationUs))
        val f = FrameSampler.sample(vm.ctx, a, src, src, 1, 96, exact = true).firstOrNull() ?: return null
        return SubjectAnalysis.detectSubject(f)
    }

    /** Tracks [box] on [targetId] from the playhead and makes [followerId] follow it. */
    fun track(vm: EditorViewModel, targetId: String, followerId: String, box: SubjectAnalysis.Box) = vm.runTask("Suivi du sujet") { prog ->
        val (c, a) = vm.clipOrFail(targetId)
        if (a.type != MediaType.VIDEO) error("Le suivi nécessite une vidéo")
        val follower = vm.clip(followerId) ?: error("Choisissez l'élément qui doit suivre")
        val from = SpeedMath.timelineToSource(c, (follower.startUs - c.startUs).coerceIn(0, c.durationUs))
        val to = SpeedMath.timelineToSource(c, (follower.endUs - c.startUs).coerceIn(0, c.durationUs))
        val frames = withContext(Dispatchers.IO) { FrameSampler.sample(vm.ctx, a, from, to, 100_000, 96, exact = true, cancelled = { false }) { prog(it * 0.85f, "Lecture des images") } }
        prog(0.9f, "Suivi")
        val path = SubjectAnalysis.track(frames, box)
        withContext(Dispatchers.Main) { vm.edit("Suivi de mouvement") { AiActions.applyTracking(it, targetId, followerId, path) } }
        vm.message.value = "« ${follower.name} » suit maintenant le sujet (${path.size} positions)"
    }

    fun removeBackground(vm: EditorViewModel, clipId: String?, tolerance: Double = 0.18) = vm.runTask("Suppression de l'arrière-plan") { prog ->
        val (c, a) = vm.clipOrFail(clipId)
        if (a.type != MediaType.IMAGE) error("Disponible pour les images (pour la vidéo, utilisez l'incrustation sur fond uni)")
        val bmp = withContext(Dispatchers.IO) { Thumbnails.decodeImage(vm.ctx, Uri.parse(a.uri), 1600, a.rotationDegrees) } ?: error("Image illisible")
        prog(0.3f, "Analyse des bords")
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h)
        val alpha = SubjectAnalysis.removeBackground(px, w, h, tolerance, feather = 2)
        for (i in px.indices) px[i] = (px[i] and 0x00FFFFFF) or ((alpha[i] * ((px[i] ushr 24) and 0xFF)).toInt() shl 24)
        val out = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        val f = File(File(vm.ctx.filesDir, "processed").also { it.mkdirs() }, "detour_${System.currentTimeMillis()}.png")
        withContext(Dispatchers.IO) { f.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        val na = a.copy(id = Ids.new("med"), uri = Uri.fromFile(f).toString(), displayName = a.displayName + " (détouré)", mimeType = "image/png", width = w, height = h, rotationDegrees = 0, folder = "Traités")
        withContext(Dispatchers.Main) { vm.edit("Détourage") { TimelineEditor.replaceMedia(it, c.id, na) } }
        vm.message.value = "Arrière-plan retiré (méthode locale : fonctionne sur les fonds unis ou peu texturés)"
    }

    // ------------------------------------------------------------------ text tools

    fun titles(description: String) { result.value = AiResult.Titles(TextTools.generateTitles(description, 10)) }

    fun summary(vm: EditorViewModel) {
        val subs = SubtitleCodec.fromProject(vm.current)
        val markers = vm.current.markers.map { it.timeUs to (it.label.ifBlank { "Marqueur" }) }
        if (subs.isNotEmpty()) {
            val sentences = subs.map { TextTools.Sentence(it.text.replace('\n', ' '), it.startUs, it.endUs) }
            val top = TextTools.summarize(sentences, 4)
            val kw = TextTools.keywords(subs.joinToString(" ") { it.text }, 6)
            result.value = AiResult.Summary("Mots-clés : ${kw.joinToString()}\n\n" + top.joinToString("\n") { "• " + it.text }, top.map { it.startUs to it.text })
        } else {
            val p = vm.current
            val main = p.tracks.firstOrNull { it.kind == TrackKind.VIDEO }?.sortedClips().orEmpty()
            val text = buildString {
                append("Durée : %.1f s, %d plans, %d pistes.\n".format(p.durationUs / 1e6, main.size, p.tracks.size))
                main.take(12).forEach { append("• %s → %s : %s\n".format(com.superessence.cuttinx.core.util.Time.format(it.startUs, false), com.superessence.cuttinx.core.util.Time.format(it.endUs, false), it.name)) }
                append("\nAstuce : générez des sous-titres automatiques pour obtenir un résumé du discours.")
            }
            result.value = AiResult.Summary(text, markers)
        }
    }

    fun suggestions(vm: EditorViewModel) { result.value = AiResult.SuggestionList(Suggestions.analyze(vm.current)) }

    fun applySuggestion(vm: EditorViewModel, s: Suggestion) {
        when (s.kind) {
            SuggestionKind.NO_TRANSITIONS -> vm.setTransitionAll("fade")
            SuggestionKind.MUSIC_TOO_LONG -> { val main = vm.current.tracks.first { it.kind == TrackKind.VIDEO }; s.clipId?.let { id -> vm.trimEnd(id, main.endUs) } }
            SuggestionKind.NO_FADE_OUT -> vm.updateClip("Fondu de sortie", id = s.clipId) { it.copy(audio = it.audio.copy(fadeOutUs = 1_500_000)) }
            SuggestionKind.LOUD_VIDEO_UNDER_MUSIC -> vm.edit("Baisser le son des vidéos") { p -> val main = p.tracks.first { it.kind == TrackKind.VIDEO }; TimelineEditor.updateTrack(p, main.id) { t -> t.copy(clips = t.clips.map { it.copy(audio = it.audio.copy(volume = 0.3)) }) } }
            SuggestionKind.ASPECT_MISMATCH -> s.clipId?.let { autoReframe(vm, it) }
            SuggestionKind.LONG_CLIP -> s.clipId?.let { detectSilences(vm, it) }
            SuggestionKind.GAP -> vm.edit("Combler le trou") { p -> val main = p.tracks.first { it.kind == TrackKind.VIDEO }; TimelineEditor.updateTrack(p, main.id) { TimelineEditor.closeGaps(it) } }
            SuggestionKind.NO_TITLE -> { vm.addText("Titre accrocheur") }
            else -> vm.panel.value = Panel.MEDIA
        }
        suggestions(vm)
    }

    fun organizeMedia(vm: EditorViewModel) {
        val folders = MediaOrganizer.suggestFolders(vm.current.media)
        vm.edit("Organiser les médias") { p -> p.copy(media = p.media.map { a -> if (a.folder.isBlank()) a.copy(folder = folders[a.id] ?: "") else a }) }
        val dups = MediaOrganizer.duplicates(vm.current.media)
        vm.message.value = "Médias classés par type et par mois" + if (dups.isNotEmpty()) " — ${dups.size} doublon(s) probable(s)" else ""
    }

    // ------------------------------------------------------------------ auto edit

    fun prepareAutoEdit(vm: EditorViewModel, assetIds: List<String>, style: AutoStyle, targetUs: Long, musicId: String?, title: String?, aspect: AspectPreset) = vm.runTask("Assistant de montage") { prog ->
        val p = vm.current
        val sources = assetIds.mapIndexedNotNull { i, id ->
            val a = p.asset(id) ?: return@mapIndexedNotNull null
            prog(i.toFloat() / (assetIds.size + 1), "Analyse de ${a.displayName}")
            if (a.type != MediaType.VIDEO) return@mapIndexedNotNull SourceInfo(a)
            val c = Clip("tmp", assetId = a.id, sourceOutUs = a.durationUs)
            val frames = withContext(Dispatchers.IO) { FrameSampler.sample(vm.ctx, a, 0, a.durationUs, step(a.durationUs, 300, 200_000), 40) }
            val stats = VideoAnalysis.stats(frames)
            val audioDb = if (a.hasAudio) runCatching { val pcm = vm.pcmOf(c, a); AudioAnalysis.envelopeDb(pcm.samples, pcm.sampleRate, 1, 50) }.getOrDefault(DoubleArray(0)) else DoubleArray(0)
            SourceInfo(a, stats, audioDb, SceneDetector.detectFromStats(stats))
        }
        if (sources.isEmpty()) error("Choisissez au moins un média")
        val music = musicId?.let { p.asset(it) }
        val beats = music?.let { m -> prog(0.9f, "Analyse de la musique"); val pcm = vm.pcmOf(Clip("m", assetId = m.id, sourceOutUs = m.durationUs), m); BeatDetector.detect(pcm.samples, pcm.sampleRate, 1) }
        val req = AutoEditRequest(sources, style, targetUs, ProjectFormat.forAspect(aspect, fps = p.format.frameRate), music, beats, title, projectName = "${p.name} — montage auto")
        result.value = AiResult.AutoEdit(AutoEditPlanner.propose(req), req)
    }

    fun rebuildAutoEdit(r: AiResult.AutoEdit, rejected: Set<String>) { result.value = r.copy(proposal = AutoEditPlanner.propose(r.request, rejected)) }

    /** Saves the proposal as a new project and returns its id. */
    fun acceptAutoEdit(vm: EditorViewModel, r: AiResult.AutoEdit): String {
        val now = System.currentTimeMillis()
        val p = r.proposal.project.copy(createdAtMs = now, modifiedAtMs = now)
        vm.c.store.save(p)
        result.value = null
        return p.id
    }

    private fun toFrame(b: Bitmap, t: Long): Frame {
        val w = 64; val h = (64f * b.height / b.width).toInt().coerceAtLeast(8)
        val s = Bitmap.createScaledBitmap(b, w, h, true)
        val px = IntArray(w * h); s.getPixels(px, 0, w, 0, 0, w, h)
        return Frame(t, w, h, px)
    }
}
