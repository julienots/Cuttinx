package com.superessence.cuttinx.core.autoedit

import com.superessence.cuttinx.core.analysis.SubjectAnalysis
import com.superessence.cuttinx.core.analysis.TimeRange
import com.superessence.cuttinx.core.anim.SpeedMath
import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.subtitles.Cue
import com.superessence.cuttinx.core.timeline.EditException
import com.superessence.cuttinx.core.timeline.TimelineEditor
import com.superessence.cuttinx.core.util.Ids
import kotlin.math.abs

/** Turns analysis results into real timeline edits. */
object AiActions {
    /**
     * Removes [ranges] (expressed in the clip's *source* time) from a clip, replacing it with the
     * kept parts placed back to back. Non destructive: only the edit instructions change.
     */
    fun removeSourceRanges(p: Project, clipId: String, ranges: List<TimeRange>): Project {
        val (t, c) = p.findClip(clipId) ?: throw EditException("Élément introuvable")
        if (t.locked) throw EditException("Piste verrouillée")
        if (c.speedCurve.isNotEmpty()) throw EditException("Retirez la courbe de vitesse avant de couper les silences")
        val inside = ranges.mapNotNull { r ->
            val s = maxOf(r.startUs, c.sourceInUs); val e = minOf(r.endUs, c.sourceOutUs)
            if (e - s > 0) TimeRange(s, e) else null
        }.sortedBy { it.startUs }
        if (inside.isEmpty()) return p
        val keeps = ArrayList<TimeRange>()
        var cur = c.sourceInUs
        for (r in inside) { if (r.startUs - cur > TimelineEditor.MIN_CLIP_US) keeps += TimeRange(cur, r.startUs); cur = maxOf(cur, r.endUs) }
        if (c.sourceOutUs - cur > TimelineEditor.MIN_CLIP_US) keeps += TimeRange(cur, c.sourceOutUs)
        if (keeps.isEmpty()) return TimelineEditor.deleteClips(p, listOf(clipId), ripple = true)
        val removedTimeline = c.durationUs - keeps.sumOf { (it.durationUs / SpeedMath.clampSpeed(c.speed)).toLong() }
        var start = c.startUs
        val parts = keeps.mapIndexed { i, k ->
            val part = c.copy(id = if (i == 0) c.id else Ids.new("clp"), startUs = start, sourceInUs = k.startUs, sourceOutUs = k.endUs,
                transitionIn = if (i == 0) c.transitionIn else null, keyframes = if (i == 0) c.keyframes else emptyMap(),
                audio = c.audio.copy(fadeInUs = if (i == 0) c.audio.fadeInUs else 0, fadeOutUs = if (i == keeps.size - 1) c.audio.fadeOutUs else 0))
            start = part.endUs
            part
        }
        val newClips = t.clips.flatMap { x ->
            when {
                x.id == clipId -> parts
                x.startUs >= c.endUs - 1_000 -> listOf(x.copy(startUs = x.startUs - removedTimeline))
                else -> listOf(x)
            }
        }
        return p.copy(tracks = p.tracks.map { if (it.id == t.id) TimelineEditor.normalize(t.copy(clips = newClips)) else it })
    }

    /** Moves every cut of [trackId] onto the nearest beat (within [maxShiftUs]) by trimming clip ends. */
    fun syncCutsToBeats(p: Project, trackId: String, beatsUs: List<Long>, maxShiftUs: Long = 400_000): Project {
        if (beatsUs.isEmpty()) return p
        var np = p
        val ids = np.track(trackId)?.sortedClips()?.map { it.id } ?: return p
        for (id in ids.dropLast(1)) {
            val c = np.findClip(id)?.second ?: continue
            val end = c.endUs
            val b = beatsUs.minByOrNull { abs(it - end) } ?: continue
            if (abs(b - end) <= maxShiftUs && b - c.startUs > TimelineEditor.MIN_CLIP_US) np = TimelineEditor.trimEnd(np, id, b)
        }
        return np
    }

    /** Puts beat markers on the timeline. */
    fun addBeatMarkers(p: Project, beatsUs: List<Long>, offsetUs: Long = 0, every: Int = 1): Project =
        p.copy(markers = (p.markers + beatsUs.filterIndexed { i, _ -> i % every == 0 }.map { Marker(Ids.new("mrk"), it + offsetUs, "♪", 0xFF4FC3F7) }).sortedBy { it.timeUs })

    /** Adds subtitle clips for cues expressed in the *source* time of [clipId]. */
    fun addSubtitlesForClip(p: Project, clipId: String, cues: List<Cue>, style: TextStyle = TextStyle(sizeSp = 34.0, strokeWidth = 3.0)): Project {
        val (_, c) = p.findClip(clipId) ?: throw EditException("Élément introuvable")
        var np = p
        val existing = np.tracks.firstOrNull { it.kind == TrackKind.SUBTITLE && !it.locked }
        val track = existing ?: TimelineEditor.addTrack(np, TrackKind.SUBTITLE).also { np = it.first }.second
        for (cue in cues) {
            val s = maxOf(cue.startUs, c.sourceInUs); val e = minOf(cue.endUs, c.sourceOutUs)
            if (e - s < 200_000) continue
            val ts = c.startUs + SpeedMath.sourceToTimeline(c, s)
            val te = c.startUs + SpeedMath.sourceToTimeline(c, e)
            np = TimelineEditor.insertClip(np, track.id, TimelineEditor.textClip(cue.text, ts, te - ts, style, ClipKind.SUBTITLE).let { it.copy(text = it.text!!.copy(animIn = TextAnimation.NONE, animOut = TextAnimation.NONE)) })
        }
        return np
    }

    /** Adds subtitle clips for cues in timeline time (imported .srt). */
    fun importSubtitles(p: Project, cues: List<Cue>, style: TextStyle = TextStyle(sizeSp = 34.0, strokeWidth = 3.0)): Project {
        var np = p
        val (p2, t) = TimelineEditor.addTrack(np, TrackKind.SUBTITLE); np = p2
        for (cue in cues) np = TimelineEditor.insertClip(np, t.id, TimelineEditor.textClip(cue.text, cue.startUs, (cue.endUs - cue.startUs).coerceAtLeast(200_000), style, ClipKind.SUBTITLE).let { it.copy(text = it.text!!.copy(animIn = TextAnimation.NONE, animOut = TextAnimation.NONE)) })
        return np
    }

    /** Applies auto-reframe keyframes (fill mode) to a clip. Times of [keys] are source times. */
    fun applyReframe(p: Project, clipId: String, keys: List<SubjectAnalysis.ReframeKey>): Project = TimelineEditor.updateClip(p, clipId) { c ->
        val inRange = keys.filter { it.timeUs in c.sourceInUs..c.sourceOutUs }
        if (inRange.isEmpty()) return@updateClip c.copy(transform = c.transform.copy(fill = true))
        val kx = KeyframeTrack(inRange.map { Keyframe(SpeedMath.sourceToTimeline(c, it.timeUs), it.posX, Easing.Smooth) })
        val ky = KeyframeTrack(inRange.map { Keyframe(SpeedMath.sourceToTimeline(c, it.timeUs), it.posY, Easing.Smooth) })
        c.copy(transform = c.transform.copy(fill = true), keyframes = c.keyframes + (Prop.POS_X to kx) + (Prop.POS_Y to ky))
    }

    /** Makes [followerId] (sticker/text) follow a tracked path computed on [targetClipId]. */
    fun applyTracking(p: Project, targetClipId: String, followerId: String, path: List<Pair<Long, SubjectAnalysis.Box>>, offsetX: Double = 0.0, offsetY: Double = 0.0): Project {
        val (_, target) = p.findClip(targetClipId) ?: throw EditException("Élément introuvable")
        val (_, follower) = p.findClip(followerId) ?: throw EditException("Élément introuvable")
        val a = p.asset(target.assetId)
        val W = p.format.width.toDouble(); val H = p.format.height.toDouble()
        val sw = (a?.displayWidth ?: p.format.width).toDouble(); val sh = (a?.displayHeight ?: p.format.height).toDouble()
        val base = if (target.transform.fill) maxOf(W / sw, H / sh) else minOf(W / sw, H / sh)
        val shownW = sw * base * target.transform.scaleX; val shownH = sh * base * target.transform.scaleY
        val kfX = ArrayList<Keyframe>(); val kfY = ArrayList<Keyframe>()
        for ((t, b) in path) {
            if (t !in target.sourceInUs..target.sourceOutUs) continue
            val timeline = target.startUs + SpeedMath.sourceToTimeline(target, t)
            val rel = timeline - follower.startUs
            if (rel < 0 || rel > follower.durationUs) continue
            val x = target.transform.posX + (b.cx - 0.5) * shownW / W + offsetX
            val y = target.transform.posY + (b.cy - 0.5) * shownH / H + offsetY
            kfX += Keyframe(rel, x, Easing.Linear); kfY += Keyframe(rel, y, Easing.Linear)
        }
        if (kfX.isEmpty()) throw EditException("Les éléments ne se chevauchent pas dans le temps")
        return TimelineEditor.updateClip(p, followerId) { it.copy(keyframes = it.keyframes + (Prop.POS_X to KeyframeTrack(kfX)) + (Prop.POS_Y to KeyframeTrack(kfY))) }
    }
}
