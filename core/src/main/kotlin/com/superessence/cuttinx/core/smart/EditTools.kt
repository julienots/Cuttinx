package com.superessence.cuttinx.core.smart

import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.timeline.TimelineEditor
import com.superessence.cuttinx.core.util.Ids
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** Groups of settings that "paste attributes" can copy from one clip to others. */
enum class Attribute(val label: String) { EFFECTS("Effets"), TRANSFORM("Position et taille"), MOTION("Animations"), AUDIO("Son"), KEY_MASK("Incrustation et masque") }

/**
 * Precision editing tools of the pro editors: ripple trims at the playhead, J / L cuts, paste
 * attributes, equal durations, markers at cuts, solo… Pure project edits (one undo step each).
 */
object EditTools {
    /** Clips of the main track under [atUs] (strictly inside), the default target of the trims. */
    fun mainClipsAt(p: Project, atUs: Long): List<String> =
        SmartTools.mainTrack(p)?.clips?.filter { atUs > it.startUs && atUs < it.endUs }?.map { it.id }.orEmpty()

    /**
     * Ripple trim: removes what lies before ([keepAfter] = true) or after the playhead on each given
     * clip and closes the hole on that track, like the Q / W keys of the pro editors.
     */
    fun rippleTrim(p: Project, atUs: Long, keepAfter: Boolean, clipIds: Collection<String>): Project {
        var np = p
        for (id in clipIds) {
            val (_, c) = np.findClip(id) ?: continue
            if (atUs <= c.startUs || atUs >= c.endUs) continue
            val (sp, rightId) = TimelineEditor.split(np, id, atUs)
            np = TimelineEditor.deleteClips(sp, listOf(if (keepAfter) id else rightId), ripple = true)
        }
        return np
    }

    /** Removes the empty spaces of one track (or of the main track), keeping transitions. */
    fun closeGaps(p: Project, trackId: String? = null): Project {
        val t = (trackId?.let { p.track(it) } ?: SmartTools.mainTrack(p)) ?: return p
        if (t.locked) return p
        return p.copy(tracks = p.tracks.map { if (it.id == t.id) TimelineEditor.closeGaps(it) else it })
    }

    /** Gives every photo, text and colour clip of [trackId] the same duration (a slideshow in one tap). */
    fun equalizeDurations(p: Project, trackId: String, durationUs: Long): Project {
        val t = p.track(trackId) ?: return p
        var np = p
        for (c in t.sortedClips()) {
            val still = c.kind != ClipKind.MEDIA || p.asset(c.assetId)?.type.let { it == MediaType.IMAGE || it == MediaType.STICKER }
            if (still && c.kind != ClipKind.ADJUSTMENT) np = TimelineEditor.setDuration(np, c.id, durationUs)
        }
        return np
    }

    /** One marker on every cut of the main track ("Plan 2", "Plan 3"…); existing markers are kept. */
    fun markersAtCuts(p: Project): Project {
        val main = SmartTools.mainTrack(p) ?: return p
        val frame = 1_000_000L / p.format.frameRate.coerceAtLeast(1)
        var np = p
        main.sortedClips().forEachIndexed { i, c ->
            if (i > 0 && np.markers.none { kotlin.math.abs(it.timeUs - c.startUs) < frame }) np = TimelineEditor.addMarker(np, c.startUs, "Plan ${i + 1}")
        }
        return np
    }

    /** Cuts every unlocked track at each marker. */
    fun splitAtMarkers(p: Project): Project {
        var np = p
        for (m in p.markers.sortedBy { it.timeUs }) np = TimelineEditor.splitAll(np, m.timeUs)
        return np
    }

    /** Only [trackId] is heard (everything else muted); calling it again on the same track restores the sound. */
    fun solo(p: Project, trackId: String): Project {
        val soloed = isSolo(p, trackId)
        return p.copy(tracks = p.tracks.map { t ->
            if (t.kind != TrackKind.AUDIO && t.kind != TrackKind.VIDEO) t else t.copy(muted = if (soloed) false else t.id != trackId)
        })
    }

    fun isSolo(p: Project, trackId: String): Boolean {
        val audible = p.tracks.filter { it.kind == TrackKind.AUDIO || it.kind == TrackKind.VIDEO }
        return audible.size > 1 && audible.all { it.muted == (it.id != trackId) }
    }

    /** Moves clips by [frames] images (negative = earlier). */
    fun nudge(p: Project, ids: Collection<String>, frames: Int): Project =
        TimelineEditor.moveClips(p, ids, frames * (1_000_000L / p.format.frameRate.coerceAtLeast(1)))

    /** Puts the start of the clips at [atUs], keeping their spacing. */
    fun alignStart(p: Project, ids: Collection<String>, atUs: Long): Project {
        val first = ids.mapNotNull { p.findClip(it)?.second?.startUs }.minOrNull() ?: return p
        return TimelineEditor.moveClips(p, ids, atUs - first)
    }

    /**
     * Paste attributes: copies the chosen settings of [fromId] onto every clip of [toIds]
     * (positions in time are never touched).
     */
    fun copyAttributes(p: Project, fromId: String, toIds: Collection<String>, what: Set<Attribute> = Attribute.entries.toSet()): Project {
        val src = p.findClip(fromId)?.second ?: return p
        var np = p
        for (id in toIds) {
            if (id == fromId) continue
            val dst = np.findClip(id)?.second ?: continue
            val visual = dst.kind != ClipKind.ADJUSTMENT
            np = TimelineEditor.updateClip(np, id) { c ->
                var r = c
                if (Attribute.EFFECTS in what) r = r.copy(effects = src.effects.map { it.copy(id = Ids.new("fx")) })
                if (Attribute.TRANSFORM in what && visual) r = r.copy(transform = src.transform)
                if (Attribute.MOTION in what) r = r.copy(motion = src.motion)
                if (Attribute.AUDIO in what) r = r.copy(audio = src.audio)
                if (Attribute.KEY_MASK in what) r = r.copy(chromaKey = src.chromaKey, mask = src.mask, blendMode = src.blendMode)
                r
            }
        }
        return np
    }

    /**
     * J / L cut: the sound of a video shot starts [jUs] before the image (J) and / or goes on
     * [lUs] after it (L), the classic way to smooth a dialogue. The sound is detached onto an audio
     * track (the shot itself is muted); calling it again re-adjusts the same detached sound.
     * The offsets are limited by what the media really contains.
     */
    fun jlCut(p: Project, videoClipId: String, jUs: Long, lUs: Long): Project {
        val (_, v) = p.findClip(videoClipId) ?: return p
        val a = p.asset(v.assetId) ?: return p
        if (v.kind != ClipKind.MEDIA || a.type != MediaType.VIDEO || !a.hasAudio || v.speedCurve.isNotEmpty()) return p
        val s = v.speed
        // limits: before the start of the timeline / of the media, after the end of the media
        val j = min(max(jUs, 0), min(v.startUs, (v.sourceInUs / s).roundToLong()))
        val roomAfter = if (a.durationUs > 0) ((a.durationUs - v.sourceOutUs) / s).roundToLong() else Long.MAX_VALUE
        val l = min(max(lUs, 0), roomAfter.coerceAtLeast(0))

        // a previous detached sound of this shot is replaced
        var np = p.copy(tracks = p.tracks.map { t ->
            if (t.kind != TrackKind.AUDIO) t else t.copy(clips = t.clips.filterNot { x -> x.assetId == v.assetId && x.name.startsWith("Audio · ") && x.startUs <= v.startUs && x.endUs >= v.endUs - 1_000 && kotlin.math.abs((x.sourceInUs + ((v.startUs - x.startUs) * x.speed).roundToLong()) - v.sourceInUs) < 2_000 })
        })
        np = TimelineEditor.updateClip(np, v.id) { it.copy(audio = it.audio.copy(muted = true)) }
        val ac = v.copy(
            id = Ids.new("clp"), startUs = v.startUs - j,
            sourceInUs = v.sourceInUs - (j * s).roundToLong(), sourceOutUs = v.sourceOutUs + (l * s).roundToLong(),
            transitionIn = null, effects = emptyList(), motion = ClipMotion(), groupId = null, name = "Audio · ${v.name}",
            keyframes = v.keyframes.filterKeys { it == Prop.VOLUME || it == Prop.PAN }.mapValues { (_, k) -> KeyframeTrack(k.keyframes.map { it.copy(timeUs = it.timeUs + j) }) },
            audio = v.audio.copy(muted = false, fadeInUs = if (j > 0) min(j, 250_000) else v.audio.fadeInUs, fadeOutUs = if (l > 0) min(l, 250_000) else v.audio.fadeOutUs),
        )
        val track = np.tracks.firstOrNull { t -> t.kind == TrackKind.AUDIO && !t.locked && t.audioRole == AudioRole.GENERAL && t.clips.none { it.startUs < ac.endUs && ac.startUs < it.endUs } }
            ?: TimelineEditor.addTrack(np, TrackKind.AUDIO).also { np = it.first }.second
        return TimelineEditor.insertClip(np, track.id, ac)
    }
}
