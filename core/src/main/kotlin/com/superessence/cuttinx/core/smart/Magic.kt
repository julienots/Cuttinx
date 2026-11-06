package com.superessence.cuttinx.core.smart

import com.superessence.cuttinx.core.analysis.Frame
import com.superessence.cuttinx.core.analysis.Highlight
import com.superessence.cuttinx.core.analysis.TimeRange
import com.superessence.cuttinx.core.autoedit.AiActions
import com.superessence.cuttinx.core.effects.EffectRegistry
import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.timeline.TimelineEditor
import com.superessence.cuttinx.core.util.Ids
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * The "one gesture" tools: whole-project edits built on real measurements of the media (colour of
 * every shot, silences, beats, best moments). The apps do the measuring (decoding is platform
 * code); everything that decides and edits lives here, so it is tested and identical on PC.
 */
object Magic {

    // ------------------------------------------------------------------ shot matching (colour)

    /** Average colour of a shot, 0..1, and its average saturation (max - min of each pixel). */
    data class Look(val r: Double, val g: Double, val b: Double, val sat: Double) {
        val luma: Double get() = 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /** Measures frames; clipped and near-black pixels are left out of the colour cast. */
    fun measure(frames: List<Frame>): Look? {
        var r = 0.0; var g = 0.0; var b = 0.0; var s = 0.0; var n = 0
        for (f in frames) for (c in f.argb) {
            val cr = ((c shr 16) and 255) / 255.0; val cg = ((c shr 8) and 255) / 255.0; val cb = (c and 255) / 255.0
            val mx = max(cr, max(cg, cb)); val mn = min(cr, min(cg, cb))
            if (mx < 0.03 || mn > 0.97) continue
            r += cr; g += cg; b += cb; s += mx - mn; n++
        }
        return if (n < 16) null else Look(r / n, g / n, b / n, s / n)
    }

    /**
     * The look every shot is brought to: the average brightness (kept in a pleasant range), the
     * average warmth softened by half, no green/magenta cast, the average saturation.
     */
    fun target(looks: List<Look>): Look {
        val l = looks.map { it.luma }.average().coerceIn(0.38, 0.55)
        val warmth = looks.map { it.r - it.b }.average() * 0.5
        val sat = looks.map { it.sat }.average().coerceIn(0.12, 0.45)
        return Look(l + warmth / 2, l, l - warmth / 2, sat)
    }

    /** Parameters of the "adjust" effect that bring [l] to [t] (see its shader: exposure in EV, temperature ±0.12 on R/B, tint ±0.10 on G). */
    fun correction(l: Look, t: Look, strength: Double = 0.85): Map<String, Double> {
        val ev = (ln(t.luma / l.luma.coerceAtLeast(0.02)) / ln(2.0) * strength).coerceIn(-1.2, 1.2)
        val k = Math.pow(2.0, ev)
        val rb = (l.r - l.b) * k; val trb = t.r - t.b
        val temperature = ((trb - rb) / 0.24 * strength).coerceIn(-0.6, 0.6)
        val gm = ((l.r + l.b) / 2 - l.g) * k; val tgm = (t.r + t.b) / 2 - t.g
        val tint = ((tgm - gm) / 0.15 * strength).coerceIn(-0.5, 0.5)
        val saturation = ((t.sat / l.sat.coerceAtLeast(0.02) - 1) * strength).coerceIn(-0.5, 0.5)
        return mapOf("exposure" to ev, "temperature" to temperature, "tint" to tint, "saturation" to saturation)
    }

    /** Puts (or replaces) the matching correction first in the clip's effects. */
    fun applyMatch(p: Project, clipId: String, params: Map<String, Double>): Project {
        val def = EffectRegistry["adjust"] ?: return p
        return Kit.edit(p, clipId) { c ->
            c.copy(effects = listOf(EffectInstance("match_" + Ids.new(), "adjust", def.defaults() + params)) + c.effects.filterNot { it.id.startsWith("match_") })
        }
    }

    /** Matches every measured shot to the common target. */
    fun matchShots(p: Project, looks: Map<String, Look>): Project {
        if (looks.isEmpty()) return p
        val t = target(looks.values.toList())
        return looks.entries.fold(p) { acc, (id, l) -> applyMatch(acc, id, correction(l, t)) }
    }

    // ------------------------------------------------------------------ jump cuts

    /** Removes the silences of each clip (source time) and alternates a punch-in on every jump cut. */
    fun jumpCut(p: Project, silences: Map<String, List<TimeRange>>, punch: Double = 0.12): Project {
        var np = p
        for ((id, ranges) in silences) if (ranges.isNotEmpty()) np = runCatching { AiActions.removeSourceRanges(np, id, ranges) }.getOrDefault(np)
        if (np === p) return p
        return SmartTools.microFades(SmartTools.punchIn(np, punch))
    }

    // ------------------------------------------------------------------ best of

    /**
     * Picks the best moments of all the shots (highest scores first) until [targetUs] is filled,
     * then keeps only them, in the original order. [moments] are in source time per clip.
     */
    fun bestOf(p: Project, moments: Map<String, List<Highlight>>, targetUs: Long): Project {
        val main = SmartTools.mainTrack(p) ?: return p
        val ranked = moments.flatMap { (id, hs) -> hs.map { id to it } }.sortedByDescending { it.second.score }
        val chosen = HashMap<String, MutableList<TimeRange>>()
        var total = 0L
        for ((id, h) in ranked) {
            if (total >= targetUs) break
            val c = p.findClip(id)?.second ?: continue
            val len = ((h.endUs - h.startUs) / c.speed).toLong()
            if (len < 400_000) continue
            chosen.getOrPut(id) { ArrayList() } += TimeRange(h.startUs, h.endUs)
            total += len
        }
        if (chosen.isEmpty()) return p
        var np = p
        for (c in main.sortedClips()) {
            val keep = chosen[c.id]?.sortedBy { it.startUs }
            if (keep == null) {
                if (c.kind == ClipKind.MEDIA && np.asset(c.assetId)?.type == MediaType.VIDEO) np = TimelineEditor.deleteClips(np, listOf(c.id), ripple = true)
                continue
            }
            val remove = ArrayList<TimeRange>(); var cur = c.sourceInUs
            for (k in keep) { if (k.startUs > cur) remove += TimeRange(cur, k.startUs); cur = max(cur, k.endUs) }
            if (cur < c.sourceOutUs) remove += TimeRange(cur, c.sourceOutUs)
            np = runCatching { AiActions.removeSourceRanges(np, c.id, remove) }.getOrDefault(np)
        }
        np = EditTools.closeGaps(np, main.id)
        np = SmartTools.transitionsEverywhere(np, "fade")
        return SmartTools.fitMusicToVideo(np)
    }

    // ------------------------------------------------------------------ edit on the music

    /**
     * Edit on the detected beats of the music: cuts moved onto beats, long shots cut every
     * [everyBeats] beats, a hit on each bar, beat markers, everything frame-accurate.
     */
    fun beatEdit(p: Project, beatsUs: List<Long>, downbeatsUs: List<Long>, bpm: Double): Project {
        val main = SmartTools.mainTrack(p) ?: return p
        if (beatsUs.size < 4) return p
        val every = if (bpm >= 140) 4 else 2
        var np = AiActions.syncCutsToBeats(p, main.id, beatsUs, maxShiftUs = (60_000_000 / bpm.coerceIn(60.0, 200.0) / 2).toLong())
        np = SmartTools.tempoCut(np, beatsUs, every)
        val bars = downbeatsUs.ifEmpty { beatsUs.filterIndexed { i, _ -> i % 4 == 0 } }
        for (b in bars.filter { it < np.durationUs }.take(300))
            np = Lab.burst(np, "Temps forts", b, 220_000, listOfNotNull(Lab.fx("zoom_blur", "amount" to 0.22), Lab.fx("shake", "intensity" to 0.015)),
                mapOf("amount" to "zoom_blur", "intensity" to "shake"))
        np = AiActions.addBeatMarkers(np, bars)
        return PlusLab.frameLock(np)
    }

    /** The music clip the beats are read from: a music track first, else any audio track, else null (the video's own sound). */
    fun musicClip(p: Project): Clip? =
        p.tracks.filter { it.kind == TrackKind.AUDIO && it.audioRole == AudioRole.MUSIC && !it.muted }.flatMap { it.clips }.maxByOrNull { it.durationUs }
            ?: p.tracks.filter { it.kind == TrackKind.AUDIO && !it.muted }.flatMap { it.clips }.maxByOrNull { it.durationUs }

    // ------------------------------------------------------------------ instant (free) gestures

    /** Whole-video gestures that need no analysis. */
    fun instant(id: String, p: Project, platform: Platform = Platform.REELS): Project = when (id) {
        "punch" -> SmartTools.punchIn(p)
        "fades" -> SmartTools.fadeInOut(p)
        "hook" -> ProToolsBridge.hook(p)
        "vertical" -> SmartTools.forPlatform(p, Platform.REELS)
        "loop" -> SmartTools.perfectLoop(p)
        "music_fit" -> SmartTools.fitMusicToVideo(p)
        "progress" -> ProToolsBridge.progress(p)
        "transitions" -> SmartTools.mainTrack(p)?.let { ProToolsBridge.transitions(p, it.id) } ?: p
        "clean" -> SmartTools.cleanUp(p)
        "safe" -> SmartTools.safeZones(p, platform)
        else -> p
    }
}

/** Small bridge so [Magic.instant] reads well. */
private object ProToolsBridge {
    fun hook(p: Project) = com.superessence.cuttinx.core.autoedit.ProTools.hookTitle(p, "REGARDE ÇA")
    fun progress(p: Project) = com.superessence.cuttinx.core.autoedit.ProTools.progressBar(p)
    fun transitions(p: Project, trackId: String) = com.superessence.cuttinx.core.autoedit.ProTools.autoTransitions(p, trackId)
}
