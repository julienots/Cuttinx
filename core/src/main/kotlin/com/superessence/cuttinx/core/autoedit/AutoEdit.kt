package com.superessence.cuttinx.core.autoedit

import com.superessence.cuttinx.core.analysis.BeatResult
import com.superessence.cuttinx.core.analysis.FrameStats
import com.superessence.cuttinx.core.analysis.HighlightDetector
import com.superessence.cuttinx.core.effects.Presets
import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.subtitles.Cue
import com.superessence.cuttinx.core.timeline.TimelineEditor
import com.superessence.cuttinx.core.util.Ids
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class AutoStyle(val label: String, val minCutUs: Long, val maxCutUs: Long, val beatsPerCut: Int, val transitions: List<String>, val preset: String?, val textAnim: TextAnimation) {
    DYNAMIC("Dynamique", 700_000, 2_000_000, 2, listOf("whip", "glitch", "flash", "zoom_in"), "Clip musical", TextAnimation.POP),
    CINEMATIC("Cinématique", 2_500_000, 5_500_000, 8, listOf("fade", "dissolve", "fade_black"), "Blockbuster", TextAnimation.FADE),
    VLOG("Vlog", 1_800_000, 4_000_000, 4, listOf("slide", "zoom_in"), "Éclat", TextAnimation.SLIDE_UP),
    CALM("Calme", 3_500_000, 7_000_000, 8, listOf("fade", "blur"), "Pastel", TextAnimation.FADE),
    SPORT("Sport / Action", 600_000, 1_600_000, 1, listOf("zoom_in", "whip", "flash"), "Contraste dramatique", TextAnimation.ZOOM),
}

data class SourceInfo(val asset: MediaAsset, val stats: List<FrameStats> = emptyList(), val audioDb: DoubleArray = DoubleArray(0), val sceneCutsUs: List<Long> = emptyList())

data class AutoEditRequest(
    val sources: List<SourceInfo>,
    val style: AutoStyle,
    val targetDurationUs: Long,
    val format: ProjectFormat,
    val music: MediaAsset? = null,
    val beats: BeatResult? = null,
    val title: String? = null,
    val subtitles: Map<String, List<Cue>> = emptyMap(),
    val projectName: String = "Montage auto",
)

/** A decision of the assistant that the user can accept or reject before generating. */
data class Decision(val id: String, val label: String, val detail: String, val accepted: Boolean = true)

data class Segment(val assetId: String, val sourceInUs: Long, val sourceOutUs: Long, val score: Double)

data class AutoEditProposal(val project: Project, val decisions: List<Decision>, val segments: List<Segment>)

/** Generates a first editable cut. The result is a normal [Project] fully editable on the timeline. */
object AutoEditPlanner {
    fun propose(req: AutoEditRequest, rejected: Set<String> = emptySet()): AutoEditProposal {
        val decisions = ArrayList<Decision>()
        val style = req.style
        val beatPeriod = req.beats?.takeIf { it.bpm > 0 }?.let { (60_000_000 / it.bpm).toLong() }
        val cutLen = if (beatPeriod != null && "sync" !in rejected) {
            var l = beatPeriod * style.beatsPerCut
            while (l < style.minCutUs) l *= 2
            while (l > style.maxCutUs * 1.5 && l > beatPeriod) l /= 2
            l
        } else (style.minCutUs + style.maxCutUs) / 2

        // 1. candidate segments
        val candidates = ArrayList<Segment>()
        for (src in req.sources) {
            val a = src.asset
            if (a.type == MediaType.IMAGE) { candidates += Segment(a.id, 0, cutLen, 0.5); continue }
            if (a.type != MediaType.VIDEO || a.durationUs < 300_000) continue
            if (src.stats.size >= 3) {
                val hl = HighlightDetector.detect(src.stats, src.audioDb, 50_000, emptyList(), cutLen, count = max(1, (a.durationUs / cutLen).toInt()), cutsUs = src.sceneCutsUs)
                hl.forEach { candidates += Segment(a.id, it.startUs, min(it.endUs, a.durationUs), it.score) }
            } else {
                var s = 0L
                while (s + cutLen / 2 < a.durationUs) { candidates += Segment(a.id, s, min(a.durationUs, s + cutLen), 0.5); s += cutLen * 2 }
            }
        }
        // 2. select best until target reached, then chronological order (keeps the story)
        val order = req.sources.map { it.asset.id }
        val selected = ArrayList<Segment>()
        var total = 0L
        for (c in candidates.sortedByDescending { it.score }) {
            if (total >= req.targetDurationUs) break
            if ("seg_${c.assetId}_${c.sourceInUs}" in rejected) continue
            selected += c; total += c.sourceOutUs - c.sourceInUs
        }
        // make sure every source is represented if possible
        for (src in req.sources) if (selected.none { it.assetId == src.asset.id }) candidates.filter { it.assetId == src.asset.id }.maxByOrNull { it.score }?.let { selected += it }
        val chronological = selected.sortedWith(compareBy({ order.indexOf(it.assetId) }, { it.sourceInUs }))
        chronological.forEach { decisions += Decision("seg_${it.assetId}_${it.sourceInUs}", "Segment ${req.sources.first { s -> s.asset.id == it.assetId }.asset.displayName}", "%.1f s → %.1f s (score %d)".format(it.sourceInUs / 1e6, it.sourceOutUs / 1e6, (it.score * 100).roundToInt())) }

        // 3. build project
        var p = Project(Ids.new("prj"), req.projectName, format = req.format)
        req.sources.forEach { p = TimelineEditor.addMedia(p, it.asset) }
        val (p1, main) = TimelineEditor.mainTrack(p); p = p1
        var cursor = 0L
        val beats = req.beats?.beatsUs.orEmpty()
        val clipIds = ArrayList<String>()
        for ((i, seg) in chronological.withIndex()) {
            var dur = seg.sourceOutUs - seg.sourceInUs
            if (beats.isNotEmpty() && "sync" !in rejected) {
                // end the clip on the beat closest to cursor + dur
                val target = cursor + dur
                val b = beats.filter { it > cursor + style.minCutUs / 2 }.minByOrNull { kotlin.math.abs(it - target) }
                if (b != null) dur = min(dur, b - cursor).coerceAtLeast(TimelineEditor.MIN_CLIP_US)
            }
            val asset = p.asset(seg.assetId)!!
            val clip = Clip(Ids.new("clp"), ClipKind.MEDIA, asset.id, asset.displayName, cursor, seg.sourceInUs, seg.sourceInUs + dur,
                transform = Transform(fill = true),
                audio = AudioSettings(volume = if (req.music != null && style != AutoStyle.VLOG) 0.25 else 1.0))
            p = TimelineEditor.insertClip(p, main.id, clip)
            clipIds += clip.id
            cursor += dur
            if (i == 0 && cursor >= req.targetDurationUs * 2) break
        }
        if (beats.isNotEmpty()) decisions += Decision("sync", "Synchroniser sur la musique", "Coupes calées sur les temps (%.0f BPM)".format(req.beats!!.bpm))

        // transitions
        decisions += Decision("transitions", "Transitions « ${style.label} »", style.transitions.joinToString())
        if ("transitions" !in rejected) {
            clipIds.drop(1).forEachIndexed { i, id ->
                if (style == AutoStyle.DYNAMIC || style == AutoStyle.SPORT) { if (i % 3 != 2) return@forEachIndexed }
                val tr = style.transitions[i % style.transitions.size]
                val d = if (style == AutoStyle.CINEMATIC || style == AutoStyle.CALM) 700_000L else 300_000L
                p = runCatching { TimelineEditor.setTransition(p, id, TransitionInstance(tr, d)) }.getOrDefault(p)
            }
        }
        // look
        style.preset?.let { name ->
            decisions += Decision("look", "Filtre « $name »", "Appliqué à tous les plans")
            if ("look" !in rejected) {
                val preset = Presets.builtIn.first { it.name == name }
                for (id in clipIds) p = TimelineEditor.updateClip(p, id) { it.copy(effects = it.effects + Presets.instantiate(preset)) }
            }
        }
        // music
        val totalDur = p.durationUs
        if (req.music != null) {
            decisions += Decision("music", "Musique : ${req.music.displayName}", "Coupée à la durée du montage, fondu de sortie")
            if ("music" !in rejected) {
                p = TimelineEditor.addMedia(p, req.music)
                val (p2, mt) = TimelineEditor.addTrack(p, TrackKind.AUDIO, role = AudioRole.MUSIC); p = p2
                val musicClip = Clip(Ids.new("clp"), ClipKind.MEDIA, req.music.id, req.music.displayName, 0, 0, min(req.music.durationUs, totalDur),
                    audio = AudioSettings(fadeInUs = 300_000, fadeOutUs = min(1_500_000, totalDur / 4)))
                p = TimelineEditor.insertClip(p, mt.id, musicClip)
            }
        }
        // title
        if (!req.title.isNullOrBlank()) {
            decisions += Decision("title", "Titre d'introduction", "« ${req.title} »")
            if ("title" !in rejected) {
                val (p3, tt) = TimelineEditor.addTrack(p, TrackKind.TEXT); p = p3
                val style2 = TextStyle(sizeSp = 64.0, bold = true, strokeWidth = 4.0)
                p = TimelineEditor.insertClip(p, tt.id, TimelineEditor.textClip(req.title, 0, min(3_000_000, max(1_000_000, totalDur / 3)), style2).let { c ->
                    c.copy(text = c.text!!.copy(animIn = style.textAnim, animOut = TextAnimation.FADE))
                })
            }
        }
        // subtitles (source time -> timeline time)
        if (req.subtitles.isNotEmpty()) {
            decisions += Decision("subtitles", "Sous-titres automatiques", "Repris de la transcription")
            if ("subtitles" !in rejected) {
                val (p4, st) = TimelineEditor.addTrack(p, TrackKind.SUBTITLE); p = p4
                for (c in p.tracks.first { it.id == main.id }.clips) {
                    val cues = req.subtitles[c.assetId] ?: continue
                    for (cue in cues) {
                        val s = max(cue.startUs, c.sourceInUs); val e = min(cue.endUs, c.sourceOutUs)
                        if (e - s < 300_000) continue
                        val ts = c.startUs + (s - c.sourceInUs); val dur = e - s
                        p = runCatching { TimelineEditor.insertClip(p, st.id, TimelineEditor.textClip(cue.text, ts, dur, TextStyle(sizeSp = 34.0, bold = true, strokeWidth = 3.0, shadowRadius = 4.0), ClipKind.SUBTITLE)) }.getOrDefault(p)
                    }
                }
            }
        }
        return AutoEditProposal(p, decisions, chronological)
    }
}
