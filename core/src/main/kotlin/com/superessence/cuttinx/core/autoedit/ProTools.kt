package com.superessence.cuttinx.core.autoedit

import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.timeline.TimelineEditor

/**
 * One-tap creator tools ("Outils pro"). Every function is a pure, undoable project edit that only
 * produces regular clips, keyframes and animations: everything stays editable afterwards.
 */
object ProTools {

    /** Zoom "punch" keyframes on [clipId] at each beat it covers (classic beat-synced edit). */
    fun beatZoom(p: Project, clipId: String, beatsUs: List<Long>, amount: Double = 0.12, decayUs: Long = 220_000): Project {
        val c = p.findClip(clipId)?.second ?: return p
        val inside = beatsUs.filter { it >= c.startUs && it < c.endUs }.map { it - c.startUs }
        if (inside.isEmpty()) return p
        val kfs = ArrayList<Keyframe>()
        kfs += Keyframe(0, 1.0, Easing.Linear)
        for (b in inside) {
            if (b - 40_000 > (kfs.lastOrNull()?.timeUs ?: -1)) kfs += Keyframe(b - 40_000, 1.0, Easing.EaseIn)
            kfs += Keyframe(b, 1.0 + amount, Easing.EaseOut)
            kfs += Keyframe(minOf(b + decayUs, c.durationUs), 1.0, Easing.Linear)
        }
        val track = KeyframeTrack(kfs.distinctBy { it.timeUs }.sortedBy { it.timeUs })
        return TimelineEditor.updateClip(p, clipId) { it.copy(keyframes = it.keyframes + (Prop.SCALE to track)) }
    }

    private val autoEntries = listOf(MotionPreset.ZOOM_OUT, MotionPreset.SLIDE_LEFT, MotionPreset.POP, MotionPreset.WHIP, MotionPreset.RISE, MotionPreset.SPIN_ZOOM, MotionPreset.SLIDE_UP, MotionPreset.ELASTIC)

    /** Gives every visual clip of [trackId] a different, short entry animation (+ optional loop). */
    fun autoAnimate(p: Project, trackId: String, loop: LoopMotion = LoopMotion.NONE): Project {
        val t = p.track(trackId) ?: return p
        val ids = t.sortedClips().filter { it.kind != ClipKind.ADJUSTMENT }.map { it.id }
        var np = p
        ids.forEachIndexed { i, id ->
            np = TimelineEditor.updateClip(np, id) { it.copy(motion = it.motion.copy(animIn = autoEntries[i % autoEntries.size], inUs = 350_000, loop = loop)) }
        }
        return np
    }

    /** Applies a slow Ken Burns move to every still image of the project. */
    fun kenBurnsImages(p: Project): Project = TimelineEditor.mapClips(p) { c ->
        val a = p.asset(c.assetId)
        if (c.kind == ClipKind.MEDIA && a?.type == MediaType.IMAGE && c.motion.loop == LoopMotion.NONE) c.copy(motion = c.motion.copy(loop = LoopMotion.KEN_BURNS)) else c
    }

    /** Cycles a set of dynamic transitions over every cut of [trackId]. */
    fun autoTransitions(p: Project, trackId: String, ids: List<String> = listOf("whip", "zoom_in", "glitch", "spin_zoom", "flash", "slide")): Project {
        val t = p.track(trackId) ?: return p
        var np = p
        t.sortedClips().drop(1).forEachIndexed { i, c ->
            np = runCatching { TimelineEditor.setTransition(np, c.id, TransitionInstance(ids[i % ids.size], 400_000)) }.getOrDefault(np)
        }
        return np
    }

    /** Progress bar growing from left to right along the whole video (retention booster). */
    fun progressBar(p: Project, color: Long = 0xFFFFCC1F, top: Boolean = false): Project {
        val dur = p.durationUs
        if (dur <= 0) return p
        val (np, track) = TimelineEditor.addTrack(p, TrackKind.VIDEO, name = "Barre de progression")
        val clip = TimelineEditor.colorClip(color, 0, dur).copy(
            name = "Barre de progression",
            transform = Transform(posX = 0.0, posY = if (top) 0.006 else 0.994, scaleX = 1.0, scaleY = 0.012, anchorX = 0.0, anchorY = 0.5),
            keyframes = mapOf(Prop.SCALE_X to KeyframeTrack(listOf(Keyframe(0, 0.0, Easing.Linear), Keyframe(dur, 1.0, Easing.Linear)))),
        )
        return TimelineEditor.insertClip(np, track.id, clip)
    }

    /** Creator handle ("@moncompte") as a discreet watermark for the whole video. */
    fun handleWatermark(p: Project, handle: String, posX: Double = 0.78, posY: Double = 0.93): Project {
        val dur = p.durationUs
        if (dur <= 0 || handle.isBlank()) return p
        val (np, track) = TimelineEditor.addTrack(p, TrackKind.TEXT, name = "Filigrane")
        val text = if (handle.startsWith("@")) handle else "@$handle"
        val clip = TimelineEditor.textClip(text, 0, dur, TextStyle(sizeSp = 26.0, bold = true, shadowRadius = 6.0, color = 0xFFFFFFFF)).let {
            it.copy(name = "Filigrane", transform = it.transform.copy(posX = posX, posY = posY, opacity = 0.7), text = it.text!!.copy(animIn = TextAnimation.NONE, animOut = TextAnimation.NONE))
        }
        return TimelineEditor.insertClip(np, track.id, clip)
    }

    /** Big hook title on the first seconds (pop + rhythmic punch) to stop the scroll. */
    fun hookTitle(p: Project, text: String, durationUs: Long = 2_500_000): Project {
        val (np, track) = TimelineEditor.addTrack(p, TrackKind.TEXT, name = "Accroche")
        val style = TextStyle(fontFamily = "asset:fonts/Anton_400Regular.ttf", sizeSp = 78.0, uppercase = true, strokeWidth = 6.0, strokeColor = 0xFF000000, color = 0xFFFFFFFF,
            backgroundColor = 0x00000000, shadowRadius = 12.0)
        val clip = TimelineEditor.textClip(text, 0, durationUs, style).let {
            it.copy(name = "Accroche", transform = it.transform.copy(posY = 0.3),
                text = it.text!!.copy(animIn = TextAnimation.POP, animOut = TextAnimation.FADE),
                motion = ClipMotion(loop = LoopMotion.ZOOM_PUNCH, loopSpeed = 2.0, loopAmount = 0.6))
        }
        return TimelineEditor.insertClip(np, track.id, clip)
    }

    /** "Influencer" captions: bold uppercase, thick outline, yellow, pop-in, slightly above centre-bottom. */
    fun creatorCaptions(p: Project, color: Long = 0xFFFFE14D): Project = TimelineEditor.mapClips(p) { c ->
        val t = c.text
        if (c.kind != ClipKind.SUBTITLE || t == null) c
        else c.copy(
            transform = c.transform.copy(posY = 0.72),
            text = t.copy(
                style = t.style.copy(fontFamily = "asset:fonts/Montserrat_800ExtraBold.ttf", sizeSp = 44.0, bold = true, uppercase = true, color = color,
                    strokeWidth = 7.0, strokeColor = 0xFF000000, shadowRadius = 8.0, backgroundColor = 0x00000000),
                animIn = TextAnimation.POP, animInUs = 180_000, animOut = TextAnimation.NONE,
            ),
        )
    }
}
