package com.superessence.cuttinx.core.smart

import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.timeline.TimelineEditor

/** Small helpers shared by the 2.0 tools: they never touch locked tracks and never throw. */
internal object Kit {
    /** Edits one clip; clips on locked tracks (or gone) are left as they are. */
    fun edit(p: Project, id: String, f: (Clip) -> Clip): Project {
        val (t, _) = p.findClip(id) ?: return p
        if (t.locked) return p
        return runCatching { TimelineEditor.updateClip(p, id, f) }.getOrDefault(p)
    }

    fun editAll(p: Project, ids: List<String>, f: (Clip) -> Clip): Project = ids.fold(p) { acc, id -> edit(acc, id, f) }

    fun isStill(p: Project, c: Clip) = p.asset(c.assetId)?.type.let { it == MediaType.IMAGE || it == MediaType.STICKER }
    fun isVisual(p: Project, c: Clip) = when (c.kind) { ClipKind.MEDIA -> p.asset(c.assetId)?.isVisual == true; ClipKind.ADJUSTMENT -> false; else -> true }
    fun hasSound(p: Project, c: Clip) = c.kind == ClipKind.MEDIA && p.asset(c.assetId)?.hasAudio == true

    /** Selected elements matching [ok], else every element of the project matching [ok]. */
    fun selOrAll(p: Project, selection: List<String>, ok: (Track, Clip) -> Boolean): List<String> {
        val sel = selection.mapNotNull { p.findClip(it) }.filter { (t, c) -> ok(t, c) }.map { it.second.id }
        if (sel.isNotEmpty()) return sel
        return p.tracks.filter { !it.locked }.flatMap { t -> t.sortedClips().filter { ok(t, it) }.map { it.id } }
    }

    fun texts(p: Project, selection: List<String>) = selOrAll(p, selection) { _, c -> c.text != null }

    /** A new track of [kind], or the existing one called [name]. */
    fun namedTrack(p: Project, kind: TrackKind, name: String, role: AudioRole = AudioRole.GENERAL): Pair<Project, Track> =
        p.tracks.firstOrNull { it.kind == kind && it.name == name && !it.locked }?.let { p to it } ?: TimelineEditor.addTrack(p, kind, name, role)

    /** Puts [clip] on a track named [name] at its start time, on a new track of that family when it would overlap. */
    fun place(p: Project, kind: TrackKind, name: String, clip: Clip): Project {
        val candidates = p.tracks.filter { it.kind == kind && it.name.startsWith(name) && !it.locked }
        val free = candidates.firstOrNull { t -> t.clips.none { it.startUs < clip.endUs && clip.startUs < it.endUs } }
        val (np, t) = if (free != null) p to free else TimelineEditor.addTrack(p, kind, if (candidates.isEmpty()) name else "$name ${candidates.size + 1}")
        return TimelineEditor.insertClip(np, t.id, clip)
    }
}
