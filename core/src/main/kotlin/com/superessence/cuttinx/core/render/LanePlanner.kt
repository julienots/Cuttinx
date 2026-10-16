package com.superessence.cuttinx.core.render

import com.superessence.cuttinx.core.model.Clip
import com.superessence.cuttinx.core.model.ClipKind
import com.superessence.cuttinx.core.model.Project
import com.superessence.cuttinx.core.model.Track
import com.superessence.cuttinx.core.model.TrackKind

/** A lane is a list of non overlapping clips that maps to one sequential stream of the engine. */
data class Lane(val trackId: String, val trackIndex: Int, val laneIndex: Int, val clips: List<Clip>, val audioOnly: Boolean, val trackMuted: Boolean, val trackHidden: Boolean, val trackVolume: Double)

/**
 * Converts the multi-track timeline into lanes: overlapping clips of one track (transitions)
 * are distributed over alternating lanes. Engines draw lanes in [videoLanes] order (bottom first).
 */
object LanePlanner {
    data class Plan(val videoLanes: List<Lane>, val audioLanes: List<Lane>, val adjustmentClips: List<Clip>, val textClips: List<Pair<Track, Clip>>, val durationUs: Long)

    fun plan(p: Project): Plan {
        val video = ArrayList<Lane>()
        val audio = ArrayList<Lane>()
        val order = FrameEvaluator.visualTrackOrder(p)
        for ((idx, t) in order.withIndex()) {
            if (t.kind != TrackKind.VIDEO) continue
            for ((li, clips) in partition(t.sortedClips().filter { it.enabled }).withIndex()) {
                video += Lane(t.id, idx, li, clips, false, t.muted, t.hidden, t.volume)
            }
        }
        for ((idx, t) in p.tracks.withIndex()) {
            if (t.kind != TrackKind.AUDIO) continue
            for ((li, clips) in partition(t.sortedClips().filter { it.enabled && it.kind == ClipKind.MEDIA }).withIndex()) {
                audio += Lane(t.id, idx, li, clips, true, t.muted, false, t.volume)
            }
        }
        val adj = p.tracks.filter { it.kind == TrackKind.EFFECT && !it.hidden }.flatMap { it.clips.filter { c -> c.enabled } }
        val texts = order.filter { (it.kind == TrackKind.TEXT || it.kind == TrackKind.SUBTITLE) && !it.hidden }
            .flatMap { t -> t.sortedClips().filter { it.enabled && it.text != null }.map { t to it } }
        return Plan(video, audio, adj, texts, p.durationUs)
    }

    /** Greedy interval partitioning; preserves order so adjacent clips alternate lanes. */
    fun partition(clips: List<Clip>): List<List<Clip>> {
        val lanes = ArrayList<MutableList<Clip>>()
        for (c in clips) {
            val lane = lanes.firstOrNull { it.last().endUs <= c.startUs }
            if (lane != null) lane += c else lanes += mutableListOf(c)
        }
        return lanes
    }

    /**
     * Items of a lane with gaps, as (startUs, durationUs, clip-or-null) — exactly what a
     * sequential engine (Media3 sequences) consumes.
     */
    fun withGaps(lane: Lane, totalUs: Long): List<Triple<Long, Long, Clip?>> {
        val out = ArrayList<Triple<Long, Long, Clip?>>()
        var cursor = 0L
        for (c in lane.clips) {
            if (c.startUs > cursor) out += Triple(cursor, c.startUs - cursor, null)
            out += Triple(c.startUs, c.durationUs, c)
            cursor = c.endUs
        }
        if (totalUs > cursor) out += Triple(cursor, totalUs - cursor, null)
        return out
    }
}
