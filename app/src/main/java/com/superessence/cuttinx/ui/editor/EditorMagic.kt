package com.superessence.cuttinx.ui.editor

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import com.superessence.cuttinx.core.analysis.BeatDetector
import com.superessence.cuttinx.core.analysis.Frame
import com.superessence.cuttinx.core.analysis.HighlightDetector
import com.superessence.cuttinx.core.analysis.SceneDetector
import com.superessence.cuttinx.core.analysis.SilenceDetector
import com.superessence.cuttinx.core.analysis.SubjectAnalysis
import com.superessence.cuttinx.core.analysis.TimeRange
import com.superessence.cuttinx.core.analysis.VideoAnalysis
import com.superessence.cuttinx.core.anim.SpeedMath
import com.superessence.cuttinx.core.audio.AudioAnalysis
import com.superessence.cuttinx.core.autoedit.AiActions
import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.smart.Magic
import com.superessence.cuttinx.core.smart.Platform
import com.superessence.cuttinx.core.smart.PlusLab
import com.superessence.cuttinx.core.smart.SmartTools
import com.superessence.cuttinx.core.smart.Tech
import com.superessence.cuttinx.media.FrameSampler
import com.superessence.cuttinx.media.PcmDecoder
import com.superessence.cuttinx.media.Thumbnails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.withContext

/**
 * The one-gesture tools of the editor's toolbar. The Plus ones really analyse the media (sound
 * decoded, frames read) over the whole project, then make one undoable edit (with a version kept
 * just before it).
 */
@UnstableApi
object EditorMagic {
    /** Free gestures: instant, no analysis. */
    val instant = listOf(
        "punch" to "Zoom coupes", "transitions" to "Transitions", "fades" to "Fondus", "hook" to "Accroche",
        "music_fit" to "Musique calée", "progress" to "Progression", "loop" to "Boucle", "vertical" to "Vertical", "clean" to "Nettoyer",
    ) + com.superessence.cuttinx.core.smart.MontageKit.gestures

    fun runInstant(vm: EditorViewModel, id: String, label: String) {
        vm.snapshot("Avant : $label", auto = true)
        if (vm.edit(label) { Magic.instant(id, it, Platform.REELS) }) vm.message.value = "$label : fait (annulable)"
    }

    fun run(vm: EditorViewModel, t: Tech) = when (t) {
        Tech.BEAT_EDIT -> beatEdit(vm)
        Tech.JUMP_CUT -> jumpCut(vm, null)
        Tech.BEST_OF -> bestOf(vm)
        Tech.SHOT_MATCH -> shotMatch(vm)
        Tech.SMART_VERTICAL -> smartVertical(vm)
        Tech.STUDIO_SOUND -> studioSound(vm)
        Tech.HYPER_DIRECTOR -> { DirectorUi.asking = true; Unit }
        Tech.STABILIZE -> stabilize(vm)
        Tech.MOTION_MATCH -> motionMatch(vm)
        else -> Unit
    }

    // ------------------------------------------------------------------ 3.2: autonomous director, stabilisation, motion cuts

    /**
     * The autonomous director: every video of the main track is read (interest of each moment,
     * sound, colour, camera movement, subject), the music is listened to (beats, bars, energy),
     * then [HyperDirector] writes the whole film in one undoable edit.
     */
    fun hyperDirector(vm: EditorViewModel, style: com.superessence.cuttinx.core.smart.DirectorStyle, targetUs: Long, hook: String?) = vm.runTask("Réalisateur autonome") { prog ->
        val p = vm.current
        val clips = videoClips(p)
        if (clips.isEmpty()) error("Ajoute tes vidéos sur la piste principale : le réalisateur monte à partir d'elles")
        val vertical = p.format.width < p.format.height
        val takes = ArrayList<com.superessence.cuttinx.core.smart.HyperDirector.Take>()
        clips.forEachIndexed { i, c ->
            ensureActive()
            val name = c.name.ifBlank { "prise ${i + 1}" }
            prog(0.05f + 0.7f * i / clips.size, "Je regarde « $name » (${i + 1}/${clips.size})")
            val a = p.asset(c.assetId)!!
            val step = maxOf(66_000L, c.sourceDurationUs / 600)
            val fr = withContext(Dispatchers.IO) { FrameSampler.sample(vm.ctx, a, c.sourceInUs, c.sourceOutUs, step, 64, cancelled = { !isActive }) }
            if (fr.size < 2) return@forEachIndexed
            ensureActive()
            val stats = VideoAnalysis.stats(fr)
            val audioDb = if (a.hasAudio) withContext(Dispatchers.IO) {
                runCatching { PcmDecoder.decode(vm.ctx, Uri.parse(a.uri), c.sourceInUs, c.sourceOutUs, downmix = true, maxSeconds = 1800.0) }.getOrNull()
                    ?.let { AudioAnalysis.envelopeDb(it.samples, it.sampleRate, 1, 50) }
            } ?: DoubleArray(0) else DoubleArray(0)
            val motion = com.superessence.cuttinx.core.smart.CameraMotion.estimate(fr)
            val reframe = if (vertical && a.displayWidth > a.displayHeight) SubjectAnalysis.autoReframe(fr, a.displayWidth, a.displayHeight, p.format.width, p.format.height) else emptyList()
            takes += com.superessence.cuttinx.core.smart.HyperDirector.Take(c.id, stats, audioDb, 50_000, Magic.measure(fr.filterIndexed { k, _ -> k % 8 == 0 }), motion, reframe)
        }
        if (takes.isEmpty()) error("Ces vidéos n'ont pas pu être lues")
        // the music (a music track first, else none: the director then follows a narrative arc)
        val musicClip = Magic.musicClip(p)
        val music = musicClip?.let { mc ->
            val ma = p.asset(mc.assetId) ?: return@let null
            prog(0.8f, "J'écoute la musique")
            val pcm = withContext(Dispatchers.IO) { runCatching { PcmDecoder.decode(vm.ctx, Uri.parse(ma.uri), mc.sourceInUs, mc.sourceOutUs, downmix = true, maxSeconds = 900.0) }.getOrNull() } ?: return@let null
            ensureActive()
            val r = BeatDetector.detect(pcm.samples, pcm.sampleRate, 1)
            fun toTimeline(s: Long) = mc.startUs + SpeedMath.sourceToTimeline(mc, mc.sourceInUs + s)
            val beats = r.beatsUs.map(::toTimeline).filter { it in mc.startUs..mc.endUs }
            if (beats.size < 4) null
            else com.superessence.cuttinx.core.smart.HyperDirector.Music(r.bpm, beats, r.downbeatsUs.map(::toTimeline).filter { it in mc.startUs..mc.endUs },
                AudioAnalysis.envelopeDb(pcm.samples, pcm.sampleRate, 1, 50), 50_000, mc.startUs)
        }
        prog(0.92f, "J'écris le montage")
        var report: List<String> = emptyList()
        commit(vm, "Réalisateur autonome", "Film monté par le réalisateur autonome (annulable en un geste)") { cur ->
            val res = com.superessence.cuttinx.core.smart.HyperDirector.direct(cur, takes, music, style, targetUs, hook?.takeIf { it.isNotBlank() })
            report = res.report
            res.project
        }
        withContext(Dispatchers.Main) { if (report.isNotEmpty()) DirectorUi.report = report }
    }

    /** Selected video clips, else every video of the main track. */
    private fun targets(vm: EditorViewModel, p: Project): List<Clip> {
        val sel = vm.selection.value
        val all = p.tracks.flatMap { it.clips }.filter { it.kind == ClipKind.MEDIA && p.asset(it.assetId)?.type == MediaType.VIDEO }
        return all.filter { it.id in sel }.ifEmpty { videoClips(p) }
    }

    private fun stabilize(vm: EditorViewModel) = vm.runTask("Stabilisation IA") { prog ->
        val p = vm.current
        val clips = targets(vm, p)
        if (clips.isEmpty()) error("Aucune vidéo à stabiliser")
        val result = HashMap<String, com.superessence.cuttinx.core.smart.CameraMotion.Stabilization>()
        clips.forEachIndexed { i, c ->
            ensureActive()
            prog(i.toFloat() / clips.size, "Mesure du mouvement, plan ${i + 1}/${clips.size}")
            val a = p.asset(c.assetId)!!
            // dense sampling: the shake lives between neighbouring frames
            val step = maxOf(1_000_000L / 15, c.sourceDurationUs / 900)
            val fr = withContext(Dispatchers.IO) { FrameSampler.sample(vm.ctx, a, c.sourceInUs, c.sourceOutUs, step, 96, exact = true, cancelled = { !isActive }) }
            if (fr.size < 4) return@forEachIndexed
            val st = com.superessence.cuttinx.core.smart.CameraMotion.stabilization(com.superessence.cuttinx.core.smart.CameraMotion.estimate(fr), fr.first().timeUs, 0.85)
            if (st.shakiness > 0.002) result[c.id] = st
        }
        if (result.isEmpty()) error("Ces plans sont déjà stables : rien à corriger")
        val zoom = result.values.maxOf { it.zoom }
        commit(vm, "Stabilisation IA", "${result.size} plan(s) stabilisé(s), zoom de compensation jusqu'à ${((zoom - 1) * 100).toInt()} % (images clés modifiables)") { q ->
            result.entries.fold(q) { acc, (id, st) -> com.superessence.cuttinx.core.smart.CameraMotion.applyStabilization(acc, id, st) }
        }
    }

    private fun motionMatch(vm: EditorViewModel) = vm.runTask("Raccords mouvement") { prog ->
        val p = vm.current
        val clips = videoClips(p)
        if (clips.size < 2) error("Il faut au moins deux vidéos qui se suivent")
        val motion = HashMap<String, List<com.superessence.cuttinx.core.smart.CameraMotion.Step>>()
        clips.forEachIndexed { i, c ->
            ensureActive()
            prog(i.toFloat() / clips.size, "Mouvement aux coupes, plan ${i + 1}/${clips.size}")
            val a = p.asset(c.assetId)!!
            val edge = minOf(500_000L, c.sourceDurationUs / 2)
            val head = withContext(Dispatchers.IO) { FrameSampler.sample(vm.ctx, a, c.sourceInUs, c.sourceInUs + edge, 50_000, 64, exact = true) }
            val tail = withContext(Dispatchers.IO) { FrameSampler.sample(vm.ctx, a, c.sourceOutUs - edge, c.sourceOutUs, 50_000, 64, exact = true) }
            motion[c.id] = com.superessence.cuttinx.core.smart.CameraMotion.estimate(head) + com.superessence.cuttinx.core.smart.CameraMotion.estimate(tail)
        }
        commit(vm, "Raccords mouvement", "Raccords dans le sens du mouvement posés à chaque coupe où la caméra bouge (annulable)") { com.superessence.cuttinx.core.smart.CameraMotion.matchCuts(it, motion, transitions = true) }
    }

    // ------------------------------------------------------------------ helpers

    private fun mainClips(p: Project) = SmartTools.mainTrack(p)?.sortedClips().orEmpty()
    private fun videoClips(p: Project) = mainClips(p).filter { it.kind == ClipKind.MEDIA && p.asset(it.assetId)?.type == MediaType.VIDEO }

    private suspend fun commit(vm: EditorViewModel, label: String, done: String, op: (Project) -> Project) = withContext(Dispatchers.Main) {
        vm.snapshot("Avant : $label", auto = true)
        if (vm.edit(label, op = op)) vm.message.value = done
    }

    private fun frames(vm: EditorViewModel, c: Clip, a: MediaAsset, maxFrames: Int, width: Int): List<Frame> = when (a.type) {
        MediaType.VIDEO -> FrameSampler.sample(vm.ctx, a, c.sourceInUs, c.sourceOutUs, maxOf(150_000L, c.sourceDurationUs / maxFrames), width)
        MediaType.IMAGE -> listOfNotNull(Thumbnails.decodeImage(vm.ctx, Uri.parse(a.uri), width * 2, a.rotationDegrees)?.let { b ->
            val h = (width.toFloat() * b.height / b.width).toInt().coerceAtLeast(8)
            val s = android.graphics.Bitmap.createScaledBitmap(b, width, h, true)
            val px = IntArray(width * h); s.getPixels(px, 0, width, 0, 0, width, h); Frame(0, width, h, px)
        })
        else -> emptyList()
    }

    // ------------------------------------------------------------------ Plus tools

    private fun beatEdit(vm: EditorViewModel) = vm.runTask("Montage sur la musique") { prog ->
        val p = vm.current
        val music = Magic.musicClip(p) ?: videoClips(p).firstOrNull { p.asset(it.assetId)?.hasAudio == true } ?: error("Ajoute une musique (bouton Audio) pour monter dessus")
        val a = p.asset(music.assetId) ?: error("Musique introuvable")
        prog(0.1f, "Écoute de la musique")
        val pcm = withContext(Dispatchers.IO) { PcmDecoder.decode(vm.ctx, Uri.parse(a.uri), music.sourceInUs, music.sourceOutUs, downmix = true, maxSeconds = 900.0) }
        ensureActive()
        prog(0.6f, "Recherche des temps")
        val r = BeatDetector.detect(pcm.samples, pcm.sampleRate, 1)
        fun toTimeline(s: Long) = music.startUs + SpeedMath.sourceToTimeline(music, music.sourceInUs + s)
        val beats = r.beatsUs.map(::toTimeline).filter { it in music.startUs..music.endUs }
        val bars = r.downbeatsUs.map(::toTimeline).filter { it in music.startUs..music.endUs }
        if (beats.size < 4) error("Aucun rythme net trouvé dans cette musique")
        vm.beats.value = beats
        prog(0.9f, "Montage")
        commit(vm, "Montage sur la musique", "Monté sur ${r.bpm.toInt()} BPM : ${beats.size} temps, coupes à l'image près (annulable)") { Magic.beatEdit(it, beats, bars, r.bpm) }
    }

    /** Silences removed from every take with sound ([only]: just the clips of these media). */
    fun jumpCut(vm: EditorViewModel, only: Set<String>?) = vm.runTask("Coupe des blancs") { prog ->
        val p = vm.current
        val clips = mainClips(p).filter { it.kind == ClipKind.MEDIA && p.asset(it.assetId)?.hasAudio == true && it.speedCurve.isEmpty() && (only == null || p.asset(it.assetId)?.uri in only) }
        if (clips.isEmpty()) error("Aucune prise avec du son sur la piste principale")
        val silences = HashMap<String, List<TimeRange>>()
        clips.forEachIndexed { i, c ->
            ensureActive()
            prog(i.toFloat() / clips.size, "Écoute de « ${c.name.ifBlank { "prise ${i + 1}" }} »")
            val a = p.asset(c.assetId)!!
            val pcm = withContext(Dispatchers.IO) { PcmDecoder.decode(vm.ctx, Uri.parse(a.uri), c.sourceInUs, c.sourceOutUs, downmix = true, maxSeconds = 1800.0) }
            silences[c.id] = SilenceDetector.detect(pcm.samples, pcm.sampleRate, 1, SilenceDetector.Params(marginDb = 10.0, minSilenceUs = 400_000))
                .map { TimeRange(it.startUs + c.sourceInUs + 60_000, it.endUs + c.sourceInUs - 60_000) }.filter { it.endUs > it.startUs }
        }
        val total = silences.values.sumOf { l -> l.sumOf { it.durationUs } }
        if (total == 0L) error("Aucun blanc à retirer")
        commit(vm, "Coupe des blancs", "%.1f s de blancs retirées dans ${clips.size} prise(s), zoom alterné sur les coupes (annulable)".format(total / 1e6)) { Magic.jumpCut(it, silences) }
    }

    private fun bestOf(vm: EditorViewModel, targetUs: Long = 30_000_000) = vm.runTask("Best-of automatique") { prog ->
        val p = vm.current
        val clips = videoClips(p)
        if (clips.isEmpty()) error("Ajoute des vidéos sur la piste principale")
        val moments = HashMap<String, List<com.superessence.cuttinx.core.analysis.Highlight>>()
        clips.forEachIndexed { i, c ->
            ensureActive()
            prog(i.toFloat() / clips.size, "Analyse de « ${c.name.ifBlank { "vidéo ${i + 1}" }} »")
            val a = p.asset(c.assetId)!!
            val fr = withContext(Dispatchers.IO) { frames(vm, c, a, 300, 48) }
            val stats = VideoAnalysis.stats(fr).map { it.copy(timeUs = it.timeUs - c.sourceInUs) }
            val audioDb = if (a.hasAudio) withContext(Dispatchers.IO) {
                val pcm = PcmDecoder.decode(vm.ctx, Uri.parse(a.uri), c.sourceInUs, c.sourceOutUs, downmix = true, maxSeconds = 1800.0)
                AudioAnalysis.envelopeDb(pcm.samples, pcm.sampleRate, 1, 50)
            } else DoubleArray(0)
            val count = (c.durationUs / 6_000_000).toInt().coerceIn(1, 8)
            moments[c.id] = HighlightDetector.detect(stats, audioDb, 50_000, emptyList(), 3_000_000, count, SceneDetector.detectFromStats(stats))
                .map { it.copy(startUs = it.startUs + c.sourceInUs, endUs = it.endUs + c.sourceInUs) }
        }
        commit(vm, "Best-of automatique", "Best-of prêt : les meilleurs moments de ${clips.size} vidéo(s), environ ${targetUs / 1_000_000} s (annulable)") { Magic.bestOf(it, moments, targetUs) }
    }

    private fun shotMatch(vm: EditorViewModel) = vm.runTask("Égalisation des plans") { prog ->
        val p = vm.current
        val clips = mainClips(p).filter { it.kind == ClipKind.MEDIA && p.asset(it.assetId)?.isVisual == true }
        if (clips.size < 2) error("Il faut au moins deux plans à égaliser")
        val looks = HashMap<String, Magic.Look>()
        clips.forEachIndexed { i, c ->
            ensureActive()
            prog(i.toFloat() / clips.size, "Mesure du plan ${i + 1}/${clips.size}")
            val fr = withContext(Dispatchers.IO) { frames(vm, c, p.asset(c.assetId)!!, 12, 64) }
            Magic.measure(fr)?.let { looks[c.id] = it }
        }
        commit(vm, "Égalisation des plans", "${looks.size} plans égalisés : même lumière, même balance des blancs (réglage « Ajuster », modifiable)") { Magic.matchShots(it, looks) }
    }

    private fun smartVertical(vm: EditorViewModel) = vm.runTask("Vertical intelligent") { prog ->
        val p0 = vm.current
        val vertical = withContext(Dispatchers.Main) { SmartTools.forPlatform(p0, Platform.REELS) }
        val clips = mainClips(vertical).filter { it.kind == ClipKind.MEDIA && vertical.asset(it.assetId)?.isVisual == true }
        val keys = HashMap<String, List<SubjectAnalysis.ReframeKey>>()
        clips.forEachIndexed { i, c ->
            ensureActive()
            prog(i.toFloat() / maxOf(1, clips.size), "Recherche du sujet, plan ${i + 1}/${clips.size}")
            val a = vertical.asset(c.assetId)!!
            val fr = withContext(Dispatchers.IO) { frames(vm, c, a, 120, 64) }
            if (fr.isNotEmpty()) keys[c.id] = SubjectAnalysis.autoReframe(fr, a.displayWidth, a.displayHeight, vertical.format.width, vertical.format.height)
        }
        commit(vm, "Vertical intelligent", "Format 9:16 : ${keys.size} plan(s) cadré(s) sur leur sujet (images clés modifiables)") { p ->
            keys.entries.fold(SmartTools.forPlatform(p, Platform.REELS)) { acc, (id, k) -> runCatching { AiActions.applyReframe(acc, id, k) }.getOrDefault(acc) }
        }
    }

    private fun studioSound(vm: EditorViewModel) = vm.runTask("Son studio") { prog ->
        val p = vm.current
        val clips = p.tracks.filter { !it.muted }.flatMap { it.clips }.filter { it.kind == ClipKind.MEDIA && p.asset(it.assetId)?.hasAudio == true && !it.audio.muted }
        if (clips.isEmpty()) error("Aucun son dans ce projet")
        val gains = HashMap<String, Double>()
        clips.forEachIndexed { i, c ->
            ensureActive()
            prog(i.toFloat() / clips.size, "Mesure du volume ${i + 1}/${clips.size}")
            val a = p.asset(c.assetId)!!
            val pcm = withContext(Dispatchers.IO) { PcmDecoder.decode(vm.ctx, Uri.parse(a.uri), c.sourceInUs, c.sourceOutUs, downmix = false, maxSeconds = 900.0) }
            val music = p.tracks.firstOrNull { t -> t.clips.any { it.id == c.id } }?.audioRole == AudioRole.MUSIC
            gains[c.id] = AudioAnalysis.normalizationGain(pcm.samples, pcm.sampleRate, pcm.channels, if (music) -20.0 else -14.0)
        }
        commit(vm, "Son studio", "${gains.size} piste(s) mesurées et réglées (voix -14 LUFS, musique -20 LUFS) + chaîne broadcast (annulable)") { q ->
            val leveled = gains.entries.fold(q) { acc, (id, g) -> com.superessence.cuttinx.core.timeline.TimelineEditor.updateClip(acc, id) { it.copy(audio = it.audio.copy(normalizeGainDb = g)) } }
            PlusLab.mastering(leveled, emptyList())
        }
    }
}

/** State of the autonomous director's dialogs (settings before, report after). */
object DirectorUi {
    var asking by mutableStateOf(false)
    var report by mutableStateOf<List<String>?>(null)
}
