package com.superessence.cuttinx.core.smart

import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.timeline.TimelineEditor
import kotlin.math.ceil
import kotlin.math.min

/** Cutting, repeating and reordering operations on shots (pure, undoable project edits). */
object ClipOps {
    private const val MIN = TimelineEditor.MIN_CLIP_US

    /** Cuts a clip into [parts] pieces of the same length (limited by what the clip can hold). */
    fun splitEqual(p: Project, clipId: String, parts: Int): Project {
        val c = p.findClip(clipId)?.second ?: return p
        val n = min(parts, (c.durationUs / (MIN * 2)).toInt())
        if (n < 2) return p
        var np = p; var cur = clipId
        for (i in 1 until n) { val (sp, rid) = TimelineEditor.split(np, cur, c.startUs + c.durationUs * i / n); np = sp; cur = rid }
        return np
    }

    /** Cuts a clip every [everyUs]; a remainder shorter than 0.3 s stays glued to the last piece. */
    fun splitEvery(p: Project, clipId: String, everyUs: Long): Project {
        val c = p.findClip(clipId)?.second ?: return p
        if (everyUs < 2 * MIN || c.durationUs < everyUs + 300_000) return p
        var np = p; var cur = clipId; var at = c.startUs + everyUs
        while (c.endUs - at >= 300_000) { val (sp, rid) = TimelineEditor.split(np, cur, at); np = sp; cur = rid; at += everyUs }
        return np
    }

    /** Plays the clip [times] times in a row. */
    fun repeat(p: Project, clipId: String, times: Int): Project {
        if (p.findClip(clipId) == null) return p
        var np = p; var last = clipId
        repeat((times - 1).coerceIn(0, 50)) { val (q, id) = TimelineEditor.duplicate(np, last); np = q; last = id }
        return np
    }

    /** Repeats a clip back to back until it covers [endUs] (the last copy is trimmed): loops a song or a short shot. */
    fun fillTo(p: Project, clipId: String, endUs: Long): Project {
        val c = p.findClip(clipId)?.second ?: return p
        if (c.endUs >= endUs || c.durationUs < MIN) return p
        var np = p; var last = clipId; var guard = 0
        while ((np.findClip(last)?.second?.endUs ?: endUs) < endUs && guard++ < 200) { val (q, id) = TimelineEditor.duplicate(np, last); np = q; last = id }
        return runCatching { TimelineEditor.trimEnd(np, last, endUs) }.getOrDefault(np)
    }

    /**
     * Gives every shot of [trackId] the same length by cutting equally at both ends (the middle,
     * where the action usually is, is kept). Shots already shorter are left alone; photos are set to [durationUs].
     */
    fun equalShots(p: Project, trackId: String, durationUs: Long): Project {
        val ids = p.track(trackId)?.sortedClips()?.filter { it.kind == ClipKind.MEDIA }?.map { it.id } ?: return p
        var np = p
        for (id in ids) {
            val c = np.findClip(id)?.second ?: continue
            val still = np.asset(c.assetId)?.type.let { it == MediaType.IMAGE || it == MediaType.STICKER }
            if (still) { np = TimelineEditor.setDuration(np, id, durationUs); continue }
            val excess = c.durationUs - durationUs
            if (excess < 100_000) continue
            np = TimelineEditor.trimEnd(np, id, c.endUs - excess / 2)
            val c2 = np.findClip(id)?.second ?: continue
            np = TimelineEditor.trimStart(np, id, c2.startUs + (excess - excess / 2))
        }
        return np
    }

    private fun reorder(p: Project, trackId: String, order: (List<Clip>) -> List<Clip>): Project {
        val t = p.track(trackId) ?: return p
        if (t.locked || t.clips.size < 2) return p
        var cur = 0L
        val clips = order(t.sortedClips()).map { c -> val nc = c.copy(startUs = cur, transitionIn = null); cur = nc.endUs; nc }
        return p.copy(tracks = p.tracks.map { if (it.id == t.id) t.copy(clips = clips) else it })
    }

    /** Last shot first (transitions are dropped: they belonged to the old neighbours). */
    fun reverseOrder(p: Project, trackId: String): Project = reorder(p, trackId) { it.reversed() }

    /** Random but repeatable order ([seed]); always different from the current one when there are 2+ shots. */
    fun shuffle(p: Project, trackId: String, seed: Long = 1): Project = reorder(p, trackId) { l ->
        val s = l.shuffled(java.util.Random(seed))
        if (s.map { it.id } == l.map { it.id }) l.drop(1) + l.first() else s
    }
}
