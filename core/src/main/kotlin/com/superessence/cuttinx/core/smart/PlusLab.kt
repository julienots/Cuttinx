package com.superessence.cuttinx.core.smart

import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.timeline.TimelineEditor
import com.superessence.cuttinx.core.util.Ids
import kotlin.math.roundToLong

enum class Grade(val label: String) { SCOPE("Cinémascope 2.39"), FILM35("Pellicule 35 mm"), CLEAN("Numérique net"), NIGHT("Nuit bleutée") }

/**
 * The CUTTINX Plus technologies (see [Tech.plus]). Like the Labo, everything produces ordinary,
 * editable clips, layers, keyframes and audio effects.
 */
object PlusLab {
    private fun fx(id: String, vararg params: Pair<String, Double>) = Lab.fx(id, *params)

    // ------------------------------------------------------------------ frame accuracy

    fun frameUs(p: Project): Double = 1_000_000.0 / p.format.frameRate.coerceIn(1, 240)
    private fun snap(t: Long, f: Double): Long = ((t / f).roundToLong() * f).roundToLong()

    /**
     * Everything on the exact frame grid of the project: clip starts and ends, keyframes and
     * markers. Neighbouring clips that were glued stay glued; gaps and overlaps of up to two
     * frames on a track are closed. Clips with a speed curve keep their length.
     */
    fun frameLock(p: Project): Project {
        val f = frameUs(p)
        val tracks = p.tracks.map { t ->
            if (t.locked) return@map t
            val snapped = t.clips.map { c ->
                val s = snap(c.startUs, f).coerceAtLeast(0)
                val keys = c.keyframes.mapValues { (_, k) -> KeyframeTrack(k.keyframes.map { it.copy(timeUs = snap(it.timeUs, f).coerceAtLeast(0)) }.distinctBy { it.timeUs }) }
                if (c.speedCurve.isNotEmpty()) c.copy(startUs = s, keyframes = keys)
                else {
                    val e = snap(c.endUs, f).let { if (it <= s) s + f.roundToLong() else it }
                    val src = ((e - s) * c.speed).roundToLong().coerceAtLeast(1)
                    c.copy(startUs = s, sourceOutUs = c.sourceInUs + src, keyframes = keys)
                }
            }.sortedBy { it.startUs }
            // close what is left of the micro gaps / overlaps (≤ 2 frames), in order
            val out = ArrayList<Clip>(snapped.size)
            for (c in snapped) {
                val prev = out.lastOrNull()
                val d = if (prev == null) 0L else c.startUs - prev.endUs
                out += if (prev != null && d != 0L && kotlin.math.abs(d) <= (2 * f).roundToLong()) c.copy(startUs = prev.endUs) else c
            }
            t.copy(clips = out)
        }
        val markers = p.markers.map { it.copy(timeUs = snap(it.timeUs, f)) }.distinctBy { it.timeUs }
        return p.copy(tracks = tracks, markers = markers)
    }

    /** Beats of [bpm] put on the frame grid (an offset in µs moves the first beat). */
    fun frameBeats(p: Project, bpm: Double, offsetUs: Long = 0): List<Long> {
        val f = frameUs(p)
        val beat = 60_000_000.0 / bpm.coerceIn(40.0, 240.0)
        val out = ArrayList<Long>()
        var i = 0
        while (true) {
            val t = snap((offsetUs + i * beat).roundToLong(), f)
            if (t >= p.durationUs || i > 4000) break
            out += t; i++
        }
        return out
    }

    /**
     * Cuts on the beats at frame accuracy (every [everyBeats] beats), a hit on the first beat of
     * each bar (zoom blur and shake fading in 0.25 s), then the whole timeline on the frame grid.
     * Uses the tempo of the markers when there are some, else [bpm].
     */
    fun beatLock(p: Project, bpm: Double, everyBeats: Int = 2): Project {
        val tempo = Lab.bpmFromMarkers(p) ?: bpm
        val offset = if (Lab.bpmFromMarkers(p) != null) p.markers.minOf { it.timeUs }.let { m -> val b = 60_000_000.0 / tempo; (m - (m / b).toLong() * b).roundToLong() } else 0L
        val beats = frameBeats(p, tempo, offset)
        if (beats.size < 2) return frameLock(p)
        var np = SmartTools.tempoCut(p, beats, everyBeats)
        for (b in beats.filterIndexed { i, _ -> i % 4 == 0 }.take(300))
            np = Lab.burst(np, "Temps forts", b, 250_000, listOfNotNull(fx("zoom_blur", "amount" to 0.25), fx("shake", "intensity" to 0.02)),
                mapOf("amount" to "zoom_blur", "intensity" to "shake"))
        return frameLock(np)
    }

    // ------------------------------------------------------------------ picture

    /** One adjustment layer over the whole video on its own effects track. */
    private fun layer(p: Project, name: String, effects: List<EffectInstance>, keyframes: (List<EffectInstance>, Long) -> Map<String, KeyframeTrack> = { _, _ -> emptyMap() }): Project {
        val d = p.durationUs
        if (d <= 0 || effects.isEmpty()) return p
        val clip = TimelineEditor.adjustmentClip(0, d).copy(name = name, effects = effects, keyframes = keyframes(effects, d))
        return Kit.place(p, TrackKind.EFFECT, name, clip)
    }

    /** Layered grade: exposure/contrast/colour, a film response, halation, fine grain, vignette (and the 2.39 frame for SCOPE). */
    fun cineGrade(p: Project, g: Grade): Project = layer(p, "Étalonnage Plus · ${g.label}", when (g) {
        Grade.SCOPE -> listOfNotNull(fx("adjust", "contrast" to 0.14, "saturation" to -0.06, "exposure" to 0.02, "temperature" to 0.04),
            fx("teal_orange", "amount" to 0.35, "contrast" to 0.2), fx("halation", "amount" to 0.25, "radius" to 3.0, "warmth" to 0.5),
            fx("grain", "amount" to 0.08, "size" to 1.0), fx("vignette", "amount" to 0.22, "size" to 0.85, "softness" to 0.6), fx("letterbox", "ratio" to 2.39, "opacity" to 1.0))
        Grade.FILM35 -> listOfNotNull(fx("adjust", "contrast" to 0.08, "saturation" to -0.04), fx("film_look", "amount" to 0.7, "warmth" to 0.25, "halation" to 0.35),
            fx("grain", "amount" to 0.14, "size" to 1.4), fx("vignette", "amount" to 0.3, "size" to 0.8, "softness" to 0.55))
        Grade.CLEAN -> listOfNotNull(fx("adjust", "contrast" to 0.1, "saturation" to 0.08), fx("sharpen", "amount" to 0.35, "radius" to 0.8),
            fx("cool_cinema", "amount" to 0.3), fx("vignette", "amount" to 0.12, "size" to 0.95, "softness" to 0.7))
        Grade.NIGHT -> listOfNotNull(fx("adjust", "contrast" to 0.16, "saturation" to -0.2, "exposure" to -0.08, "temperature" to -0.3, "tint" to 0.05),
            fx("halation", "amount" to 0.3, "radius" to 4.0, "warmth" to 0.1), fx("grain", "amount" to 0.1, "size" to 1.2), fx("vignette", "amount" to 0.35, "size" to 0.75, "softness" to 0.5))
    })

    /** The sharp zone travels through the shot like a focus puller: from the bottom to the top (or back). */
    fun rackFocus(p: Project, selection: List<String>, upward: Boolean = true): Project {
        val c = Lab.firstVideo(p, selection) ?: p.tracks.filter { it.kind == TrackKind.VIDEO && !it.locked }.flatMap { it.clips }
            .firstOrNull { it.kind == ClipKind.MEDIA && p.asset(it.assetId)?.isVisual == true } ?: return p
        val ts = fx("tilt_shift", "center" to 0.8, "width" to 0.22, "radius" to 12.0) ?: return p
        val bokeh = fx("bokeh", "amount" to 0.25, "size" to 0.05)
        val d = c.durationUs
        val (a, b) = if (upward) 0.8 to 0.25 else 0.25 to 0.8
        val hold = (d * 0.2).roundToLong()
        val kf = KeyframeTrack(listOf(Keyframe(0, a, Easing.Hold), Keyframe(hold, a, Easing.bezier(0.4, 0.0, 0.2, 1.0)), Keyframe(d - hold, b, Easing.Linear)))
        return Kit.edit(p, c.id) { it.copy(effects = it.effects + listOfNotNull(ts, bokeh), keyframes = it.keyframes + (Prop.effect(ts.id, "center") to kf)) }
    }

    /** Curved speed ramp down to 0.25× and back, measured motion blur and stabilisation. */
    fun smoothSlowmo(p: Project, selection: List<String>): Project {
        val c = Lab.firstVideo(p, selection) ?: return p
        val curve = listOf(SpeedPoint(0.0, 1.0), SpeedPoint(0.25, 1.0), SpeedPoint(0.38, 0.25), SpeedPoint(0.62, 0.25), SpeedPoint(0.75, 1.0), SpeedPoint(1.0, 1.0))
        var np = runCatching { TimelineEditor.setSpeedCurve(p, c.id, curve) }.getOrDefault(p)
        np = Kit.edit(np, c.id) { it.copy(stabilization = maxOf(it.stabilization, 0.6), effects = it.effects.filterNot { e -> e.effectId == "motion_blur" } + listOfNotNull(fx("motion_blur", "amount" to 10.0))) }
        return np
    }

    /** Curved camera moves on every photo: each one different (push in, pull out, pan, tilt), with a slight roll. */
    fun parallax(p: Project): Project {
        var i = 0
        return TimelineEditor.mapClips(p) { c ->
            if (c.kind != ClipKind.MEDIA || !Kit.isStill(p, c)) return@mapClips c
            val d = c.durationUs
            val k = i++ % 4
            val ease = Easing.bezier(0.45, 0.0, 0.25, 1.0)
            fun track(a: Double, b: Double) = KeyframeTrack(listOf(Keyframe(0, a, ease), Keyframe(d, b, Easing.Linear)))
            val base = c.transform
            val s = maxOf(base.scaleX, base.scaleY, 1.0)
            val keys = when (k) {
                0 -> mapOf(Prop.SCALE to track(s, s * 1.16), Prop.ROTATION to track(base.rotation - 1.0, base.rotation + 1.0))
                1 -> mapOf(Prop.SCALE to track(s * 1.18, s), Prop.POS_Y to track(base.posY + 0.02, base.posY - 0.02))
                2 -> mapOf(Prop.SCALE to track(s * 1.12, s * 1.12), Prop.POS_X to track(base.posX - 0.04, base.posX + 0.04))
                else -> mapOf(Prop.SCALE to track(s * 1.05, s * 1.2), Prop.POS_Y to track(base.posY - 0.03, base.posY + 0.02), Prop.ROTATION to track(base.rotation + 1.2, base.rotation - 0.6))
            }
            c.copy(keyframes = c.keyframes + keys, motion = ClipMotion())
        }
    }

    /** Light rays, halation and a soft glow over the whole video, breathing every 2 s. */
    fun volumetric(p: Project): Project = layer(p, "Lumière volumétrique",
        listOfNotNull(fx("god_rays", "amount" to 0.55, "x" to 0.5, "y" to 0.1, "length" to 0.5), fx("halation", "amount" to 0.3, "radius" to 5.0, "warmth" to 0.6), fx("glow"))) { fxs, d ->
        val rays = fxs.firstOrNull { it.effectId == "god_rays" } ?: return@layer emptyMap()
        val keys = generateSequence(0L) { it + 2_000_000 }.takeWhile { it <= d }.mapIndexed { i, t -> Keyframe(t, if (i % 2 == 0) 0.4 else 0.75, Easing.Smooth) }.toList()
        if (keys.size < 2) emptyMap() else mapOf(Prop.effect(rays.id, "amount") to KeyframeTrack(keys))
    }

    // ------------------------------------------------------------------ sound

    private fun chain(p: Project, ids: List<String>, effects: List<EffectInstance>): Project = Kit.editAll(p, ids) { c ->
        c.copy(audio = c.audio.copy(effects = c.audio.effects.filterNot { it.id.startsWith("au_plus") } + effects.map { it.copy(id = "au_plus_" + Ids.new()) }))
    }

    /**
     * Broadcast mastering: voices get rumble cut, gate, light noise reduction, de-essing, 3:1
     * compression and a -1 dB limiter; music gets sub cut, glue compression, a little width and
     * the limiter; then the music ducks under the voices and every clip gets click-free fades.
     */
    fun mastering(p: Project, selection: List<String>): Project {
        val voices = AudioPro.voices(p, selection)
        val music = AudioPro.music(p).filterNot { it in voices }
        var np = chain(p, voices, listOfNotNull(fx("a_highpass", "freq" to 80.0), fx("a_gate", "threshold" to -52.0, "release" to 120.0), fx("a_denoise", "amount" to 0.45),
            fx("a_deesser", "amount" to 0.5, "freq" to 6500.0), fx("a_compressor", "threshold" to -20.0, "ratio" to 3.0, "attack" to 8.0, "release" to 140.0, "makeup" to 5.0),
            fx("a_limiter", "ceiling" to -1.0, "release" to 60.0)))
        np = chain(np, music, listOfNotNull(fx("a_highpass", "freq" to 30.0), fx("a_compressor", "threshold" to -16.0, "ratio" to 2.0, "attack" to 25.0, "release" to 200.0, "makeup" to 2.0),
            fx("a_stereo_width", "width" to 1.15), fx("a_limiter", "ceiling" to -1.0, "release" to 80.0)))
        np = SmartTools.autoDuck(np, 0.3)
        return SmartTools.microFades(np)
    }
}
