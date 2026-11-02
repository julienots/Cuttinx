package com.superessence.cuttinx.core.smart

import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.timeline.TimelineEditor
import com.superessence.cuttinx.core.util.Ids

/** Project housekeeping: labels, names, groups, tracks, markers, clean-ups. */
object Organizer {
    /** Colour label by kind: video 1, photo 2, text 3, sound 4, colour 5, other 6. */
    fun labelByType(p: Project): Project = TimelineEditor.mapClips(p) { c ->
        val a = p.asset(c.assetId)
        c.copy(label = when {
            c.kind == ClipKind.TEXT || c.kind == ClipKind.SUBTITLE -> 3
            c.kind == ClipKind.COLOR -> 5
            a?.type == MediaType.VIDEO -> 1
            a?.type == MediaType.IMAGE || a?.type == MediaType.STICKER -> 2
            a?.type == MediaType.AUDIO -> 4
            else -> 6
        })
    }

    fun clearLabels(p: Project): Project = TimelineEditor.mapClips(p) { it.copy(label = 0) }

    /** "Plan 1", "Plan 2"… on the main track, in order. */
    fun numberShots(p: Project, prefix: String = "Plan"): Project {
        val ids = SmartTools.mainTrack(p)?.sortedClips()?.map { it.id } ?: return p
        return ids.withIndex().fold(p) { acc, (i, id) -> Kit.edit(acc, id) { it.copy(name = "$prefix ${i + 1}") } }
    }

    fun group(p: Project, selection: List<String>): Project = TimelineEditor.group(p, selection)
    fun ungroup(p: Project, selection: List<String>): Project = TimelineEditor.ungroup(p, selection)

    /** Disables (or re-enables) the selection: disabled elements stay on the timeline but are not seen or heard. */
    fun toggleEnabled(p: Project, selection: List<String>): Project {
        val clips = selection.mapNotNull { p.findClip(it)?.second }
        if (clips.isEmpty()) return p
        val enable = clips.all { !it.enabled }
        return Kit.editAll(p, clips.map { it.id }) { it.copy(enabled = enable) }
    }

    fun enableAll(p: Project): Project = TimelineEditor.mapClips(p) { it.copy(enabled = true) }

    fun lockAll(p: Project, lock: Boolean): Project = p.copy(tracks = p.tracks.map { it.copy(locked = lock) })
    fun showAll(p: Project): Project = p.copy(tracks = p.tracks.map { it.copy(hidden = false, muted = false) })

    /** Removes tracks that hold nothing (the main track always stays). */
    fun removeEmptyTracks(p: Project): Project {
        val main = SmartTools.mainTrack(p)?.id
        return p.copy(tracks = p.tracks.filter { it.id == main || it.clips.isNotEmpty() || it.locked })
    }

    fun removeEffects(p: Project): Project = p.copy(tracks = p.tracks.map { t -> if (t.locked) t else t.copy(clips = t.clips.map { c ->
        c.copy(effects = emptyList(), keyframes = c.keyframes.filterKeys { !it.startsWith("fx:") }, chromaKey = ChromaKey(), blendMode = BlendMode.NORMAL)
    }) })

    fun removeTransitions(p: Project): Project {
        val main = SmartTools.mainTrack(p)?.id
        return p.copy(tracks = p.tracks.map { t ->
            if (t.locked || t.clips.none { it.transitionIn != null }) t
            else TimelineEditor.normalize(t.copy(clips = t.clips.map { it.copy(transitionIn = null) })).let { if (t.id == main && TimelineEditor.magneticMainTrack) TimelineEditor.closeGaps(it) else it }
        })
    }

    fun removeAnimations(p: Project): Project = p.copy(tracks = p.tracks.map { t -> if (t.locked) t else t.copy(clips = t.clips.map { c ->
        c.copy(motion = ClipMotion(), keyframes = c.keyframes.filterKeys { it.startsWith("fx:") || it == Prop.VOLUME || it == Prop.PAN },
            text = c.text?.copy(animIn = TextAnimation.NONE, animOut = TextAnimation.NONE))
    }) })

    /** Every speed back to 1x. */
    fun normalSpeed(p: Project): Project = p.allClips.filter { it.speed != 1.0 || it.speedCurve.isNotEmpty() }.fold(p) { acc, c ->
        if (acc.findClip(c.id)?.first?.locked != false) acc else runCatching { TimelineEditor.setSpeed(acc, c.id, 1.0) }.getOrDefault(acc)
    }

    fun markersEvery(p: Project, everyUs: Long): Project {
        val d = p.durationUs
        if (d <= everyUs) return p
        val times = generateSequence(everyUs) { it + everyUs }.takeWhile { it < d }.take(500).toList()
        val have = p.markers.map { it.timeUs }.toSet()
        return p.copy(markers = (p.markers + times.filter { it !in have }.map { Marker(Ids.new("mrk"), it) }).sortedBy { it.timeUs })
    }

    fun markersOnBeats(p: Project, bpm: Double, everyBeats: Int = 4): Project {
        val beats = SmartTools.beatsFromBpm(p, bpm).filterIndexed { i, _ -> i % everyBeats == 0 }.filter { it > 0 }
        val have = p.markers.map { it.timeUs }.toSet()
        return p.copy(markers = (p.markers + beats.filter { it !in have }.take(500).map { Marker(Ids.new("mrk"), it, color = 0xFF2BD9FF) }).sortedBy { it.timeUs })
    }

    fun clearMarkers(p: Project): Project = p.copy(markers = emptyList())

    /** Removes media no clip uses any more. */
    fun removeUnusedMedia(p: Project): Project {
        val used = p.allClips.mapNotNull { it.assetId }.toSet()
        return p.copy(media = p.media.filter { it.id in used })
    }
}
