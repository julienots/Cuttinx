package com.superessence.cuttinx.core.timeline

import com.superessence.cuttinx.core.anim.KeyframeEvaluator
import com.superessence.cuttinx.core.anim.SpeedMath
import com.superessence.cuttinx.core.anim.splitLeft
import com.superessence.cuttinx.core.anim.splitRight
import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.util.Ids
import kotlin.math.max
import kotlin.math.min

class EditException(message: String) : Exception(message)

/**
 * Pure, non destructive timeline operations. Every function takes a [Project] and returns a new
 * one; source media are never touched. Invariant maintained after each operation: clips of a
 * track never overlap, except by the duration of a transition between two adjacent clips.
 */
object TimelineEditor {
    const val DEFAULT_IMAGE_DURATION_US = 3_000_000L
    const val DEFAULT_TEXT_DURATION_US = 3_000_000L
    const val MIN_CLIP_US = 100_000L

    // ------------------------------------------------------------------ tracks

    fun addTrack(p: Project, kind: TrackKind, name: String? = null, role: AudioRole = AudioRole.GENERAL): Pair<Project, Track> {
        val count = p.tracks.count { it.kind == kind }
        val t = Track(Ids.new("trk"), kind, name ?: defaultTrackName(kind, count + 1, role), audioRole = role)
        return p.copy(tracks = p.tracks + t) to t
    }

    fun defaultTrackName(kind: TrackKind, n: Int, role: AudioRole = AudioRole.GENERAL) = when (kind) {
        TrackKind.VIDEO -> if (n == 1) "Piste principale" else "Vidéo $n"
        TrackKind.AUDIO -> when (role) {
            AudioRole.MUSIC -> "Musique"; AudioRole.VOICE_OVER -> "Voix off"; AudioRole.AMBIENCE -> "Ambiance"; AudioRole.SFX -> "Bruitages"; else -> "Audio $n"
        }
        TrackKind.TEXT -> "Texte $n"
        TrackKind.SUBTITLE -> "Sous-titres"
        TrackKind.EFFECT -> "Effets $n"
    }

    fun removeTrack(p: Project, trackId: String): Project {
        val t = p.track(trackId) ?: return p
        if (t.locked) throw EditException("Piste verrouillée")
        return p.copy(tracks = p.tracks.filterNot { it.id == trackId })
    }

    fun updateTrack(p: Project, trackId: String, f: (Track) -> Track): Project =
        p.copy(tracks = p.tracks.map { if (it.id == trackId) f(it) else it })

    fun moveTrack(p: Project, trackId: String, newIndex: Int): Project {
        val list = p.tracks.toMutableList()
        val i = list.indexOfFirst { it.id == trackId }
        if (i < 0) return p
        val t = list.removeAt(i)
        list.add(newIndex.coerceIn(0, list.size), t)
        return p.copy(tracks = list)
    }

    /** Main track = first video track; created on demand. */
    fun mainTrack(p: Project): Pair<Project, Track> {
        p.tracks.firstOrNull { it.kind == TrackKind.VIDEO }?.let { return p to it }
        val (np, t) = addTrack(p, TrackKind.VIDEO)
        // keep main track first
        return moveTrack(np, t.id, 0) to t
    }

    fun ensureTrack(p: Project, kind: TrackKind, role: AudioRole = AudioRole.GENERAL): Pair<Project, Track> {
        p.tracks.firstOrNull { it.kind == kind && !it.locked && (kind != TrackKind.AUDIO || it.audioRole == role) }?.let { return p to it }
        return addTrack(p, kind, role = role)
    }

    // ------------------------------------------------------------------ media

    fun addMedia(p: Project, asset: MediaAsset): Project =
        if (p.media.any { it.id == asset.id }) p else p.copy(media = p.media + asset)

    fun updateMedia(p: Project, assetId: String, f: (MediaAsset) -> MediaAsset) =
        p.copy(media = p.media.map { if (it.id == assetId) f(it) else it })

    /** Relinks a (missing) asset to a new file without rebuilding the edit. */
    fun relinkMedia(p: Project, assetId: String, newUri: String, newDurationUs: Long? = null): Project {
        var np = updateMedia(p, assetId) { it.copy(uri = newUri, missing = false, durationUs = newDurationUs ?: it.durationUs) }
        if (newDurationUs != null && newDurationUs > 0) {
            np = mapClips(np) { c -> if (c.assetId == assetId && c.sourceOutUs > newDurationUs) c.copy(sourceOutUs = newDurationUs, sourceInUs = min(c.sourceInUs, newDurationUs - MIN_CLIP_US).coerceAtLeast(0)) else c }
        }
        return np
    }

    fun removeMedia(p: Project, assetId: String): Project {
        if (p.allClips.any { it.assetId == assetId }) throw EditException("Ce média est utilisé dans la timeline")
        return p.copy(media = p.media.filterNot { it.id == assetId })
    }

    // ------------------------------------------------------------------ clips: creation

    fun clipFromAsset(asset: MediaAsset, startUs: Long): Clip {
        val dur = when (asset.type) {
            MediaType.IMAGE, MediaType.STICKER -> DEFAULT_IMAGE_DURATION_US
            else -> asset.durationUs.coerceAtLeast(MIN_CLIP_US)
        }
        return Clip(
            id = Ids.new("clp"), kind = ClipKind.MEDIA, assetId = asset.id, name = asset.displayName,
            startUs = startUs, sourceInUs = 0, sourceOutUs = dur,
        )
    }

    fun textClip(text: String, startUs: Long, durationUs: Long = DEFAULT_TEXT_DURATION_US, style: TextStyle = TextStyle(), kind: ClipKind = ClipKind.TEXT): Clip =
        Clip(Ids.new("txt"), kind, name = text.take(24), startUs = startUs, sourceInUs = 0, sourceOutUs = durationUs, text = TextContent(text, style),
            transform = Transform(posY = if (kind == ClipKind.SUBTITLE) 0.85 else 0.5))

    fun colorClip(color: Long, startUs: Long, durationUs: Long = DEFAULT_IMAGE_DURATION_US) =
        Clip(Ids.new("col"), ClipKind.COLOR, name = "Couleur", startUs = startUs, sourceOutUs = durationUs, color = color)

    fun adjustmentClip(startUs: Long, durationUs: Long = DEFAULT_IMAGE_DURATION_US) =
        Clip(Ids.new("adj"), ClipKind.ADJUSTMENT, name = "Calque d'effets", startUs = startUs, sourceOutUs = durationUs)

    /** Appends [asset] at the end of the main track (or a given track). */
    fun appendAsset(p: Project, asset: MediaAsset, trackId: String? = null): Pair<Project, Clip> {
        var np = addMedia(p, asset)
        val track: Track
        if (trackId != null) {
            track = np.track(trackId) ?: throw EditException("Piste introuvable")
        } else if (asset.type == MediaType.AUDIO) {
            val r = ensureTrack(np, TrackKind.AUDIO, AudioRole.MUSIC); np = r.first; track = r.second
        } else {
            val r = mainTrack(np); np = r.first; track = r.second
        }
        val clip = clipFromAsset(asset, track.endUs)
        return insertClip(np, track.id, clip) to clip
    }

    fun insertClip(p: Project, trackId: String, clip: Clip, ripple: Boolean = false): Project {
        val t = p.track(trackId) ?: throw EditException("Piste introuvable")
        if (t.locked) throw EditException("Piste verrouillée")
        checkKind(t, clip)
        var clips = t.clips
        if (ripple) {
            // insert: push everything at or after the insertion point
            clips = clips.map { if (it.startUs >= clip.startUs) it.copy(startUs = it.startUs + clip.durationUs) else it }
            // split clip under insertion point? keep simple: the clip under the point is pushed after.
        }
        return setTrackClips(p, t.copy(clips = clips + clip), anchorId = clip.id)
    }

    private fun checkKind(t: Track, c: Clip) {
        val ok = when (t.kind) {
            TrackKind.VIDEO -> c.kind == ClipKind.MEDIA || c.kind == ClipKind.COLOR
            TrackKind.AUDIO -> c.kind == ClipKind.MEDIA
            TrackKind.TEXT -> c.kind == ClipKind.TEXT
            TrackKind.SUBTITLE -> c.kind == ClipKind.SUBTITLE || c.kind == ClipKind.TEXT
            TrackKind.EFFECT -> c.kind == ClipKind.ADJUSTMENT
        }
        if (!ok) throw EditException("Ce type d'élément ne peut pas aller sur cette piste")
    }

    // ------------------------------------------------------------------ clips: editing

    fun updateClip(p: Project, clipId: String, f: (Clip) -> Clip): Project {
        val (t, _) = p.findClip(clipId) ?: throw EditException("Élément introuvable")
        if (t.locked) throw EditException("Piste verrouillée")
        val nt = t.copy(clips = t.clips.map { if (it.id == clipId) f(it) else it })
        return setTrackClips(p, nt, anchorId = clipId)
    }

    fun mapClips(p: Project, f: (Clip) -> Clip) = p.copy(tracks = p.tracks.map { t -> t.copy(clips = t.clips.map(f)) })

    fun deleteClips(p: Project, ids: Collection<String>, ripple: Boolean = false): Project {
        var np = p
        for (id in ids) {
            val (t, c) = np.findClip(id) ?: continue
            if (t.locked) throw EditException("Piste verrouillée")
            var clips = t.clips.filterNot { it.id == id }
            if (ripple) {
                val shift = c.durationUs - (c.transitionIn?.durationUs ?: 0)
                clips = clips.map { if (it.startUs >= c.endUs - 1) it.copy(startUs = (it.startUs - shift).coerceAtLeast(0)) else it }
            }
            // a transition into the next clip loses its "from" clip
            clips = clips.map { if (it.transitionIn != null && previousClip(clips, it) == null) it.copy(transitionIn = null) else it }
            np = setTrackClips(np, t.copy(clips = clips), anchorId = null)
        }
        return np
    }

    /** Splits [clipId] at absolute timeline time [atUs]. Returns the project and the id of the right part. */
    fun split(p: Project, clipId: String, atUs: Long): Pair<Project, String> {
        val (t, c) = p.findClip(clipId) ?: throw EditException("Élément introuvable")
        if (t.locked) throw EditException("Piste verrouillée")
        val rel = atUs - c.startUs
        if (rel < MIN_CLIP_US / 2 || c.durationUs - rel < MIN_CLIP_US / 2) throw EditException("Trop près du bord de l'élément")
        val srcAt = SpeedMath.timelineToSource(c, rel)
        val leftFrac = (srcAt - c.sourceInUs).toDouble() / c.sourceDurationUs.coerceAtLeast(1)
        val (leftCurve, rightCurve) = splitCurve(c.speedCurve, leftFrac)
        val left = c.copy(
            sourceOutUs = srcAt, speedCurve = leftCurve,
            keyframes = c.keyframes.mapValues { KeyframeEvaluator.splitLeft(it.value, rel) },
            audio = c.audio.copy(fadeOutUs = 0),
        )
        val right = c.copy(
            id = Ids.new("clp"), startUs = atUs, sourceInUs = srcAt, speedCurve = rightCurve,
            keyframes = c.keyframes.mapValues { KeyframeEvaluator.splitRight(it.value, rel) },
            audio = c.audio.copy(fadeInUs = 0), transitionIn = null,
            text = c.text,
        )
        val nt = t.copy(clips = t.clips.flatMap { if (it.id == clipId) listOf(left, right) else listOf(it) })
        return setTrackClips(p, nt, anchorId = null) to right.id
    }

    private fun splitCurve(curve: List<SpeedPoint>, frac: Double): Pair<List<SpeedPoint>, List<SpeedPoint>> {
        if (curve.isEmpty() || frac <= 0 || frac >= 1) return curve to curve
        val at = SpeedPoint(frac, SpeedMath.curveSpeedAt(curve, frac))
        val left = (curve.filter { it.position < frac } + at).map { it.copy(position = it.position / frac) }
        val right = (listOf(at) + curve.filter { it.position > frac }).map { it.copy(position = (it.position - frac) / (1 - frac)) }
        return left to right
    }

    /** Splits every unlocked clip under [atUs]. */
    fun splitAll(p: Project, atUs: Long): Project {
        var np = p
        for (t in p.tracks) {
            if (t.locked) continue
            for (c in t.clips) if (atUs > c.startUs + MIN_CLIP_US / 2 && atUs < c.endUs - MIN_CLIP_US / 2) np = split(np, c.id, atUs).first
        }
        return np
    }

    fun sourceLimit(p: Project, c: Clip): Long? {
        if (c.kind != ClipKind.MEDIA) return null
        val a = p.asset(c.assetId) ?: return null
        return if (a.type == MediaType.IMAGE || a.type == MediaType.STICKER) null else a.durationUs.takeIf { it > 0 }
    }

    /** Moves the left edge of a clip to [newStartUs] (timeline), adjusting the source in-point. */
    fun trimStart(p: Project, clipId: String, newStartUs: Long): Project {
        val (_, c) = p.findClip(clipId) ?: throw EditException("Élément introuvable")
        val speed = averageSpeed(c)
        var deltaTimeline = newStartUs - c.startUs
        // limits
        val maxDelta = c.durationUs - MIN_CLIP_US
        val timeBased = c.kind == ClipKind.MEDIA && p.asset(c.assetId)?.type.let { it == MediaType.VIDEO || it == MediaType.AUDIO }
        val minDeltaBySource = if (timeBased) -(c.sourceInUs / speed).toLong() else Long.MIN_VALUE / 4
        val minDeltaByStart = -c.startUs
        deltaTimeline = deltaTimeline.coerceIn(max(minDeltaBySource, minDeltaByStart), maxDelta)
        val deltaSrc = (deltaTimeline * speed).toLong()
        return updateClip(p, clipId) {
            val newIn = (it.sourceInUs + deltaSrc)
            val shiftOut = if (newIn < 0) -newIn else 0
            it.copy(
                startUs = it.startUs + deltaTimeline,
                sourceInUs = newIn + shiftOut, sourceOutUs = it.sourceOutUs + shiftOut,
                keyframes = it.keyframes.mapValues { (_, k) -> KeyframeEvaluator.shift(k, -deltaTimeline, Long.MAX_VALUE / 4) },
            )
        }
    }

    /** Moves the right edge of a clip to [newEndUs]. */
    fun trimEnd(p: Project, clipId: String, newEndUs: Long): Project {
        val (_, c) = p.findClip(clipId) ?: throw EditException("Élément introuvable")
        val speed = averageSpeed(c)
        val newDur = (newEndUs - c.startUs).coerceAtLeast(MIN_CLIP_US)
        var newOut = c.sourceInUs + (newDur * speed).toLong()
        sourceLimit(p, c)?.let { newOut = min(newOut, it) }
        return updateClip(p, clipId) { it.copy(sourceOutUs = newOut) }
    }

    private fun averageSpeed(c: Clip): Double =
        if (c.durationUs <= 0) 1.0 else c.sourceDurationUs.toDouble() / c.durationUs

    /**
     * Moves clips by [deltaUs] and optionally to another track. Grouped clips move together.
     * Overlaps are resolved by pushing following clips.
     */
    fun moveClips(p: Project, ids: Collection<String>, deltaUs: Long, targetTrackId: String? = null): Project {
        val expanded = expandGroups(p, ids)
        var np = p
        val minStart = expanded.mapNotNull { np.findClip(it)?.second?.startUs }.minOrNull() ?: return p
        val delta = max(deltaUs, -minStart)
        for (id in expanded) {
            val (t, c) = np.findClip(id) ?: continue
            if (t.locked) throw EditException("Piste verrouillée")
            val moved = c.copy(startUs = c.startUs + delta, transitionIn = if (delta != 0L) null else c.transitionIn)
            val dest = if (targetTrackId != null && ids.size == 1) np.track(targetTrackId) ?: t else t
            if (dest.id != t.id) {
                if (dest.locked) throw EditException("Piste de destination verrouillée")
                checkKind(dest, moved)
                np = np.copy(tracks = np.tracks.map {
                    when (it.id) {
                        t.id -> normalize(it.copy(clips = it.clips.filterNot { x -> x.id == id }))
                        else -> it
                    }
                })
                val d2 = np.track(dest.id)!!
                np = setTrackClips(np, d2.copy(clips = d2.clips + moved), anchorId = id)
            } else {
                np = setTrackClips(np, t.copy(clips = t.clips.map { if (it.id == id) moved else it }), anchorId = id)
            }
        }
        return np
    }

    fun moveClipTo(p: Project, clipId: String, newStartUs: Long, targetTrackId: String? = null): Project {
        val (_, c) = p.findClip(clipId) ?: throw EditException("Élément introuvable")
        return moveClips(p, listOf(clipId), newStartUs - c.startUs, targetTrackId)
    }

    fun duplicate(p: Project, clipId: String): Pair<Project, String> {
        val (t, c) = p.findClip(clipId) ?: throw EditException("Élément introuvable")
        val copy = c.copy(id = Ids.new("clp"), startUs = c.endUs, transitionIn = null, groupId = null,
            effects = c.effects.map { it.copy(id = Ids.new("fx")) })
        // effect keyframes reference effect instance ids -> remap
        val remap = c.effects.zip(copy.effects).associate { it.first.id to it.second.id }
        val kf = c.keyframes.mapKeys { (k, _) -> remapKey(k, remap) }
        return insertClip(p, t.id, copy.copy(keyframes = kf)) to copy.id
    }

    private fun remapKey(k: String, remap: Map<String, String>): String {
        if (!k.startsWith("fx:")) return k
        val parts = k.split(":")
        return if (parts.size == 3 && remap.containsKey(parts[1])) "fx:${remap[parts[1]]}:${parts[2]}" else k
    }

    /** Replaces the media of a clip, keeping its position, duration, effects and keyframes. */
    fun replaceMedia(p: Project, clipId: String, asset: MediaAsset): Project {
        val np = addMedia(p, asset)
        return updateClip(np, clipId) { c ->
            val wanted = c.sourceDurationUs
            val limit = if (asset.type == MediaType.VIDEO || asset.type == MediaType.AUDIO) asset.durationUs else Long.MAX_VALUE
            val inUs = if (c.sourceInUs + wanted <= limit) c.sourceInUs else (limit - wanted).coerceAtLeast(0)
            c.copy(assetId = asset.id, name = asset.displayName, sourceInUs = inUs, sourceOutUs = min(inUs + wanted, limit))
        }
    }

    /** Merges two adjacent clips of the same media whose source ranges are contiguous. */
    fun merge(p: Project, leftId: String, rightId: String): Project {
        val (t, a) = p.findClip(leftId) ?: throw EditException("Élément introuvable")
        val (t2, b) = p.findClip(rightId) ?: throw EditException("Élément introuvable")
        if (t.id != t2.id) throw EditException("Les éléments doivent être sur la même piste")
        val (l, r) = if (a.startUs <= b.startUs) a to b else b to a
        if (l.assetId != r.assetId || l.kind != r.kind) throw EditException("Les éléments doivent provenir du même média")
        if (kotlin.math.abs(l.sourceOutUs - r.sourceInUs) > 5_000) throw EditException("Les segments ne sont pas contigus dans la source")
        if (kotlin.math.abs(l.endUs - r.startUs) > 5_000) throw EditException("Les segments doivent se toucher")
        if (l.speed != r.speed || l.speedCurve.isNotEmpty() || r.speedCurve.isNotEmpty()) throw EditException("Les vitesses doivent être identiques")
        val rel = r.startUs - l.startUs
        val kf = (l.keyframes.keys + r.keyframes.keys).associateWith { k ->
            KeyframeTrack(((l.keyframes[k]?.keyframes ?: emptyList()) + (r.keyframes[k]?.keyframes ?: emptyList()).map { it.copy(timeUs = it.timeUs + rel) }).sortedBy { it.timeUs })
        }
        val merged = l.copy(sourceOutUs = r.sourceOutUs, keyframes = kf, audio = l.audio.copy(fadeOutUs = r.audio.fadeOutUs))
        return setTrackClips(p, t.copy(clips = t.clips.filterNot { it.id == r.id }.map { if (it.id == l.id) merged else it }), anchorId = l.id)
    }

    fun setSpeed(p: Project, clipId: String, speed: Double): Project = updateClip(p, clipId) { c ->
        val old = c.durationUs.coerceAtLeast(1)
        val nc = c.copy(speed = SpeedMath.clampSpeed(speed), speedCurve = emptyList())
        val factor = nc.durationUs.toDouble() / old
        nc.copy(keyframes = nc.keyframes.mapValues { KeyframeEvaluator.retime(it.value, factor) })
    }

    fun setSpeedCurve(p: Project, clipId: String, curve: List<SpeedPoint>): Project = updateClip(p, clipId) { c ->
        val old = c.durationUs.coerceAtLeast(1)
        val nc = c.copy(speedCurve = curve.map { it.copy(speed = SpeedMath.clampSpeed(it.speed), position = it.position.coerceIn(0.0, 1.0)) }.sortedBy { it.position })
        val factor = nc.durationUs.toDouble() / old
        nc.copy(keyframes = nc.keyframes.mapValues { KeyframeEvaluator.retime(it.value, factor) })
    }

    /** Sets a still/text/colour clip duration directly. */
    fun setDuration(p: Project, clipId: String, durationUs: Long): Project = updateClip(p, clipId) { c ->
        val d = durationUs.coerceAtLeast(MIN_CLIP_US)
        if (c.kind == ClipKind.MEDIA && p.asset(c.assetId)?.type in setOf(MediaType.VIDEO, MediaType.AUDIO)) {
            val limit = p.asset(c.assetId)!!.durationUs
            c.copy(sourceOutUs = min(c.sourceInUs + (d * averageSpeed(c)).toLong(), limit))
        } else c.copy(sourceOutUs = c.sourceInUs + d)
    }

    // ------------------------------------------------------------------ transitions

    fun previousClip(clips: List<Clip>, c: Clip): Clip? =
        clips.filter { it.id != c.id && it.startUs < c.startUs }.maxByOrNull { it.startUs }?.takeIf { it.endUs >= c.startUs - 1_000 }

    /**
     * Adds a transition between [clipId] and the previous clip on its track. The incoming clip and
     * all following clips are pulled left so that both clips overlap by the transition duration.
     */
    fun setTransition(p: Project, clipId: String, transition: TransitionInstance?): Project {
        val (t, c) = p.findClip(clipId) ?: throw EditException("Élément introuvable")
        if (t.locked) throw EditException("Piste verrouillée")
        val sorted = t.sortedClips()
        val prev = previousClip(sorted, c) ?: if (transition == null) null else throw EditException("Une transition nécessite un élément juste avant")
        val oldD = c.transitionIn?.durationUs ?: 0
        val newD = if (transition == null || prev == null) 0 else transition.durationUs.coerceIn(MIN_CLIP_US, min(prev.durationUs, c.durationUs) / 2).coerceAtLeast(min(prev.durationUs, c.durationUs) / 8)
        val shift = newD - oldD
        val clips = sorted.map {
            when {
                it.id == c.id -> it.copy(startUs = it.startUs - shift, transitionIn = transition?.copy(durationUs = newD))
                it.startUs > c.startUs -> it.copy(startUs = it.startUs - shift)
                else -> it
            }
        }
        return p.copy(tracks = p.tracks.map { if (it.id == t.id) normalize(t.copy(clips = clips)) else it })
    }

    /** Applies the same transition to every cut of a track. */
    fun setTransitionAll(p: Project, trackId: String, transition: TransitionInstance?): Project {
        var np = p
        val t = p.track(trackId) ?: return p
        for (c in t.sortedClips().drop(1)) {
            val cur = np.findClip(c.id)?.second ?: continue
            val prev = previousClip(np.track(trackId)!!.sortedClips(), cur)
            if (prev != null) np = setTransition(np, c.id, transition)
        }
        return np
    }

    // ------------------------------------------------------------------ groups, markers, clipboard

    fun group(p: Project, ids: Collection<String>): Project {
        if (ids.size < 2) return p
        val g = Ids.new("grp")
        return mapClips(p) { if (it.id in ids) it.copy(groupId = g) else it }
    }

    fun ungroup(p: Project, ids: Collection<String>): Project {
        val groups = ids.mapNotNull { p.findClip(it)?.second?.groupId }.toSet()
        return mapClips(p) { if (it.groupId in groups) it.copy(groupId = null) else it }
    }

    fun expandGroups(p: Project, ids: Collection<String>): List<String> {
        val groups = ids.mapNotNull { p.findClip(it)?.second?.groupId }.toSet()
        return (ids + p.allClips.filter { it.groupId != null && it.groupId in groups }.map { it.id }).distinct()
    }

    fun addMarker(p: Project, timeUs: Long, label: String = ""): Project =
        p.copy(markers = (p.markers + Marker(Ids.new("mrk"), timeUs, label)).sortedBy { it.timeUs })

    fun removeMarker(p: Project, id: String) = p.copy(markers = p.markers.filterNot { it.id == id })

    data class ClipboardEntry(val trackKind: TrackKind, val trackIndexOfKind: Int, val clip: Clip)

    fun copy(p: Project, ids: Collection<String>): List<ClipboardEntry> = ids.mapNotNull { id ->
        val (t, c) = p.findClip(id) ?: return@mapNotNull null
        ClipboardEntry(t.kind, p.tracks.filter { it.kind == t.kind }.indexOf(t), c)
    }

    /** Pastes clipboard entries so that the earliest one starts at [atUs]. */
    fun paste(p: Project, entries: List<ClipboardEntry>, atUs: Long): Pair<Project, List<String>> {
        if (entries.isEmpty()) return p to emptyList()
        val base = entries.minOf { it.clip.startUs }
        var np = p
        val newIds = mutableListOf<String>()
        for (e in entries) {
            val candidates = np.tracks.filter { it.kind == e.trackKind && !it.locked }
            val track = candidates.getOrNull(e.trackIndexOfKind) ?: candidates.firstOrNull() ?: addTrack(np, e.trackKind).also { np = it.first }.second
            val fxRemap = e.clip.effects.associate { it.id to Ids.new("fx") }
            val nc = e.clip.copy(
                id = Ids.new("clp"), startUs = atUs + (e.clip.startUs - base), transitionIn = null, groupId = null,
                effects = e.clip.effects.map { it.copy(id = fxRemap[it.id]!!) },
                keyframes = e.clip.keyframes.mapKeys { (k, _) -> remapKey(k, fxRemap) },
            )
            np = insertClip(np, track.id, nc)
            newIds += nc.id
        }
        return np to newIds
    }

    // ------------------------------------------------------------------ keyframes helpers

    fun setKeyframe(p: Project, clipId: String, prop: String, relTimeUs: Long, value: Double, easing: Easing = Easing.Smooth) =
        updateClip(p, clipId) { c -> c.copy(keyframes = c.keyframes + (prop to KeyframeEvaluator.upsert(c.keyframes[prop], Keyframe(relTimeUs.coerceIn(0, c.durationUs), value, easing)))) }

    fun removeKeyframe(p: Project, clipId: String, prop: String, relTimeUs: Long) = updateClip(p, clipId) { c ->
        val k = KeyframeEvaluator.remove(c.keyframes[prop], relTimeUs, 20_000)
        c.copy(keyframes = if (k.isEmpty) c.keyframes - prop else c.keyframes + (prop to k))
    }

    fun clearKeyframes(p: Project, clipId: String, prop: String? = null) =
        updateClip(p, clipId) { c -> c.copy(keyframes = if (prop == null) emptyMap() else c.keyframes - prop) }

    // ------------------------------------------------------------------ format

    /** Changes the canvas format; [reframe] = FIT keeps everything visible, FILL crops to fill. */
    fun changeFormat(p: Project, format: ProjectFormat, fillMode: Boolean? = null): Project {
        var np = p.copy(format = format)
        if (fillMode != null) np = mapClips(np) { if (it.kind == ClipKind.MEDIA) it.copy(transform = it.transform.copy(fill = fillMode)) else it }
        return np
    }

    // ------------------------------------------------------------------ invariants

    /** Re-sorts clips and pushes clips right so that nothing overlaps (except transition overlaps). */
    fun normalize(t: Track, anchorId: String? = null): Track {
        val sorted = t.clips.sortedWith(compareBy<Clip> { it.startUs }.thenBy { if (it.id == anchorId) 0 else 1 })
        val out = ArrayList<Clip>(sorted.size)
        for (c0 in sorted) {
            var c = c0
            val prev = out.lastOrNull()
            if (prev != null) {
                val allowed = c.transitionIn?.durationUs ?: 0
                val minStart = prev.endUs - allowed
                if (c.startUs < minStart) c = c.copy(startUs = minStart)
                if (c.transitionIn != null && c.startUs > prev.endUs - allowed + 1_000) {
                    // clips no longer touching: drop the transition
                    c = c.copy(transitionIn = null)
                }
            } else if (c.transitionIn != null) c = c.copy(transitionIn = null)
            if (c.startUs < 0) c = c.copy(startUs = 0)
            out += c
        }
        return t.copy(clips = out)
    }

    /** Removes gaps on a track (magnetic main track behaviour). */
    fun closeGaps(t: Track): Track {
        var cursor = 0L
        val out = t.sortedClips().map { c ->
            val start = if (c.transitionIn != null) cursor - c.transitionIn.durationUs else cursor
            val nc = c.copy(startUs = start.coerceAtLeast(0))
            cursor = nc.endUs
            nc
        }
        return t.copy(clips = out)
    }

    var magneticMainTrack: Boolean = true

    private fun setTrackClips(p: Project, t: Track, anchorId: String?): Project {
        var nt = normalize(t, anchorId)
        val isMain = p.tracks.firstOrNull { it.kind == TrackKind.VIDEO }?.id == t.id
        if (isMain && magneticMainTrack) nt = closeGaps(nt)
        return p.copy(tracks = p.tracks.map { if (it.id == t.id) nt else it })
    }
}
