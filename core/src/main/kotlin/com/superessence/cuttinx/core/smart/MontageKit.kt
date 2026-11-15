package com.superessence.cuttinx.core.smart

import com.superessence.cuttinx.core.autoedit.ProTools
import com.superessence.cuttinx.core.effects.EffectRegistry
import com.superessence.cuttinx.core.effects.TransitionRegistry
import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.timeline.TimelineEditor
import com.superessence.cuttinx.core.util.Ids
import kotlin.math.roundToLong

/**
 * CUTTINX 3.3 montage tools: one gesture each, no analysis, always one undoable edit. They never
 * touch locked tracks and return the project unchanged when there is nothing to do.
 */
object MontageKit {
    /** Free toolbar gestures added in 3.3 (id to label). */
    val gestures = listOf(
        "rhythm" to "Rythme rapide", "velocity" to "Vélocité", "slowmo_end" to "Ralenti final", "stutter" to "Saccade",
        "cinema" to "Mode ciné", "countdown" to "3·2·1", "end_card" to "Carte de fin", "glitch_cuts" to "Coupes glitch",
        "slow_zoom" to "Zoom lent", "slideshow" to "Diaporama", "shorts59" to "Moins d'1 min",
    )

    fun run(id: String, p: Project): Project? = when (id) {
        "rhythm" -> rhythm(p)
        "velocity" -> velocity(p)
        "slowmo_end" -> slowMotionEnding(p)
        "stutter" -> stutter(p)
        "cinema" -> cinemaMode(p)
        "countdown" -> Titles.countdown(p, 0)
        "end_card" -> Titles.endCard(p)
        "glitch_cuts" -> glitchCuts(p)
        "slow_zoom" -> SmartTools.mainTrack(p)?.let { ProTools.slowZoomAll(p, it.id) } ?: p
        "slideshow" -> slideshow(p)
        "shorts59" -> underOneMinute(p)
        else -> null
    }

    private fun main(p: Project) = SmartTools.mainTrack(p)?.takeIf { !it.locked }
    private fun isVideo(p: Project, c: Clip) = c.kind == ClipKind.MEDIA && p.asset(c.assetId)?.type == MediaType.VIDEO

    /** Velocity curve: fast, a slow-motion moment in the middle, fast again. */
    val VELOCITY = listOf(SpeedPoint(0.0, 2.4), SpeedPoint(0.3, 2.4), SpeedPoint(0.42, 0.35), SpeedPoint(0.58, 0.35), SpeedPoint(0.7, 2.4), SpeedPoint(1.0, 2.4))

    /**
     * Fast rhythm: every video shot longer than [maxUs] keeps only its centre part, photos are cut
     * to [maxUs]. Shots with a speed curve are left as they are.
     */
    fun rhythm(p: Project, maxUs: Long = 2_000_000): Project {
        val t = main(p) ?: return p
        var np = p
        for (c in t.sortedClips()) {
            if (c.durationUs <= maxUs || c.speedCurve.isNotEmpty()) continue
            np = when {
                isVideo(p, c) -> Kit.edit(np, c.id) { x ->
                    val keepSrc = (maxUs * x.speed).roundToLong().coerceAtMost(x.sourceDurationUs)
                    val inUs = x.sourceInUs + (x.sourceDurationUs - keepSrc) / 2
                    val factor = maxUs.toDouble() / x.durationUs
                    x.copy(sourceInUs = inUs, sourceOutUs = inUs + keepSrc, keyframes = x.keyframes.mapValues { (_, k) -> KeyframeTrack(k.keyframes.map { it.copy(timeUs = (it.timeUs * factor).roundToLong()) }) })
                }
                c.kind == ClipKind.MEDIA || c.kind == ClipKind.COLOR -> runCatching { TimelineEditor.setDuration(np, c.id, maxUs) }.getOrDefault(np)
                else -> np
            }
        }
        return np
    }

    /** Velocity edit: every video shot of at least 1.5 s goes fast, slows down in the middle, then speeds up. */
    fun velocity(p: Project): Project {
        val t = main(p) ?: return p
        return t.sortedClips().filter { isVideo(p, it) && it.durationUs >= 1_500_000 }
            .fold(p) { acc, c -> runCatching { TimelineEditor.setSpeedCurve(acc, c.id, VELOCITY) }.getOrDefault(acc) }
    }

    /** Last video shot in slow motion ([speed]×) with a long sound fade: a cinematic ending. */
    fun slowMotionEnding(p: Project, speed: Double = 0.5): Project {
        val t = main(p) ?: return p
        val last = t.sortedClips().lastOrNull { isVideo(p, it) } ?: return p
        val np = runCatching { TimelineEditor.setSpeed(p, last.id, speed) }.getOrDefault(p)
        return Kit.edit(np, last.id) { it.copy(audio = it.audio.copy(fadeOutUs = maxOf(it.audio.fadeOutUs, 1_200_000))) }
    }

    /**
     * Stutter hook: the first 0.2 s of the video is played three times with a growing zoom
     * ("re-re-regarde"), then the shot goes on.
     */
    fun stutter(p: Project, pieceUs: Long = 200_000, times: Int = 2): Project {
        val t = main(p) ?: return p
        val first = t.sortedClips().firstOrNull() ?: return p
        if (!isVideo(p, first) || first.durationUs < pieceUs * 4 || first.speedCurve.isNotEmpty()) return p
        var np = p
        for (k in 1..times) {
            val zoom = 1.0 + 0.08 * k
            val piece = first.copy(
                id = Ids.new("clp"), name = "Saccade", sourceOutUs = first.sourceInUs + (pieceUs * first.speed).roundToLong(),
                transitionIn = null, keyframes = emptyMap(), motion = ClipMotion(), groupId = null,
                transform = first.transform.copy(scaleX = first.transform.scaleX * zoom, scaleY = first.transform.scaleY * zoom),
            )
            np = runCatching { TimelineEditor.insertClip(np, t.id, piece.copy(startUs = first.startUs), ripple = true) }.getOrDefault(np)
        }
        return np
    }

    const val CINEMA_TRACK = "Mode ciné"

    /** Cinema mode: one adjustment layer over the whole film with a film look, scope bars and fine grain (replaced, not stacked). */
    fun cinemaMode(p: Project): Project {
        val d = p.durationUs
        if (d <= 0) return p
        val cleared = p.copy(tracks = p.tracks.map { t -> if (t.kind == TrackKind.EFFECT && t.name == CINEMA_TRACK && !t.locked) t.copy(clips = emptyList()) else t })
        val fx = listOf("film_look" to emptyMap(), "letterbox" to emptyMap(), "grain" to mapOf("amount" to 0.08), "vignette" to mapOf("amount" to 0.25))
            .mapNotNull { (id, prm) -> EffectRegistry[id]?.let { def -> EffectInstance(Ids.new("fx"), id, def.defaults() + prm) } }
        val layer = TimelineEditor.adjustmentClip(0, d).copy(name = "Mode ciné", effects = fx)
        val (q, track) = Kit.namedTrack(cleared, TrackKind.EFFECT, CINEMA_TRACK)
        return runCatching { TimelineEditor.insertClip(q, track.id, layer) }.getOrDefault(p)
    }

    /** Glitch transitions on every cut, alternating the classic glitch and the glitch slide. */
    fun glitchCuts(p: Project): Project {
        val t = main(p) ?: return p
        val ids = listOf("glitch", "glitch_slide", "rgb_glitch_cut")
        return t.sortedClips().drop(1).withIndex().fold(p) { acc, (i, c) ->
            val id = ids[i % ids.size].takeIf { TransitionRegistry[it] != null } ?: "glitch"
            val def = TransitionRegistry[id] ?: return@fold acc
            runCatching { TimelineEditor.setTransition(acc, c.id, TransitionInstance(id, minOf(def.defaultDurationUs, c.durationUs / 2), def.params.associate { it.key to it.default })) }.getOrDefault(acc)
        }
    }

    /** Photo slideshow: every photo lasts [photoUs], gets a slow Ken Burns move and the shots cross-fade. */
    fun slideshow(p: Project, photoUs: Long = 2_500_000): Project {
        val t = main(p) ?: return p
        var np = EditTools.equalizeDurations(p, t.id, photoUs)
        np = ProTools.kenBurnsImages(np)
        return SmartTools.transitionsEverywhere(np, "fade")
    }

    /** Under one minute: the film is shortened to 59 s (each shot loses a bit) and set to vertical 9:16. */
    fun underOneMinute(p: Project): Project = SmartTools.forPlatform(SmartTools.fitToDuration(p, 59_000_000), Platform.SHORTS)
}
