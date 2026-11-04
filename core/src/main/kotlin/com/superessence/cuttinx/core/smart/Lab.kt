package com.superessence.cuttinx.core.smart

import com.superessence.cuttinx.core.effects.EffectRegistry
import com.superessence.cuttinx.core.effects.Presets
import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.timeline.TimelineEditor
import com.superessence.cuttinx.core.util.Ids
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToLong

enum class Pacing(val label: String) { CRESCENDO("crescendo (de plus en plus court)"), DECRESCENDO("decrescendo (de plus en plus long)"), PULSE("pulsé (court / long)") }

/** The technologies of the Labo CUTTINX (see [Tech]). Everything produces ordinary, editable clips. */
object Lab {
    private fun fx(id: String, vararg params: Pair<String, Double>): EffectInstance? =
        EffectRegistry[id]?.let { d -> EffectInstance(Ids.new("fx"), id, d.defaults() + params.toMap().filterKeys { k -> d.params.any { it.key == k } }) }

    private fun mainShots(p: Project) = SmartTools.mainTrack(p)?.takeUnless { it.locked }?.sortedClips().orEmpty()

    private fun firstVideo(p: Project, selection: List<String>): Clip? =
        selection.mapNotNull { p.findClip(it)?.second }.firstOrNull { it.kind == ClipKind.MEDIA && p.asset(it.assetId)?.type == MediaType.VIDEO }
            ?: mainShots(p).firstOrNull { it.kind == ClipKind.MEDIA && p.asset(it.assetId)?.type == MediaType.VIDEO }

    /** Cut times of the main track (between shots). */
    fun cuts(p: Project): List<Long> = mainShots(p).drop(1).map { it.startUs }

    // ------------------------------------------------------------------ kinetic typography

    /** A text becomes a rapid word-by-word sequence; long words are bigger and yellow. */
    fun kinetic(p: Project, selection: List<String>): Project {
        val ids = selection.mapNotNull { p.findClip(it) }.filter { (_, c) -> c.text != null }.map { it.second.id }
            .ifEmpty { p.tracks.filter { it.kind == TrackKind.TEXT && !it.locked }.flatMap { it.clips }.filter { it.text != null }.map { it.id } }
        var np = p
        val anims = listOf(TextAnimation.POP, TextAnimation.ZOOM, TextAnimation.SLIDE_UP, TextAnimation.DROP, TextAnimation.ELASTIC)
        for (id in ids) {
            val (t, c) = np.findClip(id) ?: continue
            if (t.locked) continue
            val words = c.text!!.text.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (words.size < 2) continue
            val step = (c.durationUs / words.size).coerceAtLeast(120_000)
            val longest = words.maxOf { it.length }
            val base = c.text.style
            np = runCatching { TimelineEditor.deleteClips(np, listOf(id)) }.getOrDefault(np)
            for ((i, w) in words.withIndex()) {
                val strong = w.length >= 6 || w.length == longest || w.all { !it.isLetter() || it.isUpperCase() }
                val style = base.copy(sizeSp = base.sizeSp * (if (strong) 1.6 else 1.15), uppercase = true, color = if (strong) 0xFFFFD21F else base.color,
                    strokeWidth = maxOf(base.strokeWidth, 5.0), strokeColor = 0xFF000000, gradientEnabled = false, backgroundColor = 0)
                val start = c.startUs + i * step
                val dur = if (i == words.lastIndex) (c.endUs - start).coerceAtLeast(step) else step
                val wc = TimelineEditor.textClip(w, start, dur, style).let {
                    it.copy(name = "Mot ${i + 1}", transform = c.transform.copy(rotation = if (i % 3 == 1) -4.0 else if (i % 3 == 2) 4.0 else 0.0),
                        text = it.text!!.copy(animIn = anims[i % anims.size], animInUs = (dur / 2).coerceAtMost(180_000), animOut = TextAnimation.NONE, maxWidth = 0.95))
                }
                np = Kit.place(np, TrackKind.TEXT, t.name, wc)
            }
        }
        return np
    }

    // ------------------------------------------------------------------ echo trail & clones

    /** Three time-delayed, fading copies of the shot drawn over it in "screen" mode: a motion trail. */
    fun echo(p: Project, selection: List<String>, delayUs: Long = 70_000): Project {
        val c = firstVideo(p, selection) ?: return p
        var np = p
        for (k in 1..3) {
            val shift = delayUs * k
            val srcCut = (shift * c.speed).roundToLong()
            if (c.sourceDurationUs - srcCut < TimelineEditor.MIN_CLIP_US) break
            val copy = c.copy(id = Ids.new("clp"), startUs = c.startUs + shift, sourceOutUs = c.sourceOutUs - srcCut, speedCurve = emptyList(), transitionIn = null, groupId = null,
                transform = c.transform.copy(opacity = c.transform.opacity * (0.55 - 0.13 * k)), blendMode = BlendMode.SCREEN, audio = c.audio.copy(muted = true),
                effects = c.effects.map { it.copy(id = Ids.new("fx")) }, keyframes = c.keyframes.filterKeys { !it.startsWith("fx:") })
            val (q, t) = Kit.namedTrack(np, TrackKind.VIDEO, "Écho $k")
            np = runCatching { TimelineEditor.insertClip(q, t.id, copy) }.getOrDefault(q)
        }
        return np
    }

    /** The shot three times on screen, each copy 0.4 s later in the action. */
    fun clones(p: Project, selection: List<String>, offsetUs: Long = 400_000): Project {
        val c = firstVideo(p, selection) ?: return p
        if (c.sourceDurationUs < offsetUs * 3) return p
        val vertical = p.format.height > p.format.width
        val boxes = (0..2).map { i -> if (vertical) doubleArrayOf(0.5, (i * 2 + 1) / 6.0, 1.0, 1 / 3.0) else doubleArrayOf((i * 2 + 1) / 6.0, 0.5, 1 / 3.0, 1.0) }
        val a = p.asset(c.assetId)
        val (w, h) = (a?.width?.takeIf { it > 0 } ?: p.format.width) to (a?.height?.takeIf { it > 0 } ?: p.format.height)
        val cw = p.format.width.toDouble(); val ch = p.format.height.toDouble()
        fun scale(b: DoubleArray) = min(cw * b[2] / w, ch * b[3] / h) / min(cw / w, ch / h)
        var np = Kit.edit(p, c.id) { it.copy(transform = it.transform.copy(posX = boxes[0][0], posY = boxes[0][1], scaleX = scale(boxes[0]), scaleY = scale(boxes[0]), fill = false)) }
        for (i in 1..2) {
            val inUs = c.sourceInUs + offsetUs * i
            val copy = c.copy(id = Ids.new("clp"), sourceInUs = inUs, speedCurve = emptyList(), transitionIn = null, groupId = null, audio = c.audio.copy(muted = true),
                transform = c.transform.copy(posX = boxes[i][0], posY = boxes[i][1], scaleX = scale(boxes[i]), scaleY = scale(boxes[i]), fill = false),
                effects = c.effects.map { it.copy(id = Ids.new("fx")) }, keyframes = emptyMap())
            val (q, t) = Kit.namedTrack(np, TrackKind.VIDEO, "Clone ${i + 1}")
            np = runCatching { TimelineEditor.insertClip(q, t.id, copy) }.getOrDefault(q)
        }
        return np
    }

    /** Opening wall: up to 9 shots at once in a grid for [durUs], popping in one after the other. */
    fun wall(p: Project, durUs: Long = 2_500_000): Project {
        val shots = mainShots(p).filter { it.kind == ClipKind.MEDIA && p.asset(it.assetId)?.isVisual == true }
        if (shots.size < 2) return p
        val n = if (shots.size >= 5) min(9, shots.size) else min(4, shots.size)
        val cols = if (n > 4) 3 else 2
        val rows = (n + cols - 1) / cols
        val main = SmartTools.mainTrack(p)!!
        var np = runCatching { TimelineEditor.insertClip(p, main.id, TimelineEditor.colorClip(0xFF0B0C0F, 0, durUs).copy(name = "Mur de clips"), ripple = true) }.getOrDefault(p)
        if (np === p) return p
        // everything else (music, texts, layers, markers) moves with the main track
        np = np.copy(tracks = np.tracks.map { t -> if (t.id == main.id || t.locked) t else t.copy(clips = t.clips.map { it.copy(startUs = it.startUs + durUs) }) },
            markers = np.markers.map { it.copy(timeUs = it.timeUs + durUs) })
        for ((i, s) in shots.take(n).withIndex()) {
            val col = i % cols; val row = i / cols
            val bw = 1.0 / cols; val bh = 1.0 / rows
            val a = np.asset(s.assetId)
            val w = a?.width?.takeIf { it > 0 } ?: np.format.width; val h = a?.height?.takeIf { it > 0 } ?: np.format.height
            val cw = np.format.width.toDouble(); val ch = np.format.height.toDouble()
            val sc = min(cw * bw / w, ch * bh / h) / min(cw / w, ch / h) * 0.94
            val start = i * 120_000L
            val src = if (np.asset(s.assetId)?.type == MediaType.VIDEO) min(s.sourceDurationUs, ((durUs - start) * s.speed).roundToLong()) else durUs - start
            val tile = s.copy(id = Ids.new("clp"), startUs = start, sourceOutUs = s.sourceInUs + src.coerceAtLeast(TimelineEditor.MIN_CLIP_US), speed = if (np.asset(s.assetId)?.type == MediaType.VIDEO) s.speed else 1.0,
                speedCurve = emptyList(), transitionIn = null, groupId = null, keyframes = emptyMap(), audio = s.audio.copy(muted = true),
                transform = s.transform.copy(posX = (col + 0.5) * bw, posY = (row + 0.5) * bh, scaleX = sc, scaleY = sc, fill = false, rotation = 0.0),
                motion = ClipMotion(animIn = MotionPreset.POP, inUs = 300_000, animOut = MotionPreset.ZOOM_IN, outUs = 300_000),
                effects = s.effects.map { it.copy(id = Ids.new("fx")) })
            val (q, t) = Kit.namedTrack(np, TrackKind.VIDEO, "Mur ${i + 1}")
            np = runCatching { TimelineEditor.insertClip(q, t.id, tile) }.getOrDefault(q)
        }
        return np
    }

    // ------------------------------------------------------------------ cut effects

    /** One effects layer (adjustment clip) on an effects track, with its effects fading out. */
    private fun burst(p: Project, trackName: String, atUs: Long, durUs: Long, effects: List<EffectInstance>, fade: Map<String, String> = emptyMap()): Project {
        if (effects.isEmpty()) return p
        val start = atUs.coerceAtLeast(0)
        val kf = HashMap<String, KeyframeTrack>()
        for (e in effects) for ((param, _) in fade.filter { it.value == e.effectId }) {
            val v = e.params[param] ?: continue
            kf[Prop.effect(e.id, param)] = KeyframeTrack(listOf(Keyframe(0, v, Easing.EaseOut), Keyframe(durUs, 0.0, Easing.Smooth)))
        }
        val clip = TimelineEditor.adjustmentClip(start, durUs).copy(name = trackName, effects = effects, keyframes = kf)
        return Kit.place(p, TrackKind.EFFECT, trackName, clip)
    }

    /** At every cut: radial speed blur, colour split and a short shake, all fading in 0.35 s. */
    fun impacts(p: Project): Project = cuts(p).fold(p) { acc, t ->
        burst(acc, "Impacts", t - 60_000, 350_000, listOfNotNull(fx("zoom_blur", "amount" to 0.35), fx("rgb_split", "amount" to 22.0), fx("shake", "intensity" to 0.03)),
            mapOf("amount" to "zoom_blur", "intensity" to "shake"))
    }

    /** Every shot dives into the next: fast zoom-in at the end, zoom-out landing at the start, speed blur on the cut. */
    fun zoomFlow(p: Project, edgeUs: Long = 280_000): Project {
        val shots = mainShots(p)
        if (shots.size < 2) return p
        var np = p
        for ((i, s) in shots.withIndex()) {
            np = Kit.edit(np, s.id) { c ->
                val d = c.durationUs
                val e = edgeUs.coerceAtMost(d / 3)
                val keys = ArrayList<Keyframe>()
                if (i > 0) { keys += Keyframe(0, 0.55, Easing.EaseOut); keys += Keyframe(e, 1.0, Easing.Smooth) }
                if (i < shots.lastIndex) { keys += Keyframe(d - e, 1.0, Easing.EaseIn); keys += Keyframe(d, 2.4, Easing.Linear) }
                if (keys.isEmpty()) c else c.copy(keyframes = c.keyframes + (Prop.SCALE to KeyframeTrack(keys.distinctBy { it.timeUs }.sortedBy { it.timeUs })), transitionIn = null)
            }
        }
        return cuts(np).fold(np) { acc, t -> burst(acc, "Zoom infini", t - edgeUs / 2, edgeUs, listOfNotNull(fx("zoom_blur", "amount" to 0.45)), mapOf("amount" to "zoom_blur")) }
    }

    /** Flash (and, every other beat, a shake) on each beat of [bpm]. */
    fun beatFx(p: Project, bpm: Double, shake: Boolean = true): Project {
        val beats = SmartTools.beatsFromBpm(p, bpm).filter { it < p.durationUs }.take(600)
        return beats.withIndex().fold(p) { acc, (i, b) ->
            val effects = listOfNotNull(fx("color_overlay", "opacity" to 0.45, "saturation" to 0.0), if (shake && i % 2 == 0) fx("shake", "intensity" to 0.025) else null)
            burst(acc, "Tempo", b, 140_000, effects, mapOf("opacity" to "color_overlay", "intensity" to "shake"))
        }
    }

    /** Random glitch bursts (seeded, so the same seed gives the same storm). */
    fun glitchStorm(p: Project, perTenSeconds: Int = 6, seed: Long = 7): Project {
        val d = p.durationUs
        if (d < 1_000_000) return p
        val r = java.util.Random(seed)
        val n = (d / 10_000_000.0 * perTenSeconds).toInt().coerceIn(2, 200)
        val ids = listOf("glitch_slices", "datamosh", "rgb_shake", "glitch", "chroma_aberration")
        val times = (0 until n).map { (r.nextDouble() * (d - 400_000)).toLong() }.sorted()
        var np = p
        var last = -1L
        for (t in times) {
            if (t < last + 250_000) continue
            val len = 120_000L + r.nextInt(220_000)
            np = burst(np, "Glitch", t, len, listOfNotNull(fx(ids[r.nextInt(ids.size)])))
            last = t + len
        }
        return np
    }

    // ------------------------------------------------------------------ rhythm

    /** Retimes the main-track shots along a curve, keeping the middle of each shot. */
    fun pacing(p: Project, mode: Pacing, longUs: Long = 2_600_000, shortUs: Long = 450_000): Project {
        val shots = mainShots(p).filter { it.kind == ClipKind.MEDIA }
        if (shots.size < 2) return p
        var np = p
        for ((i, s) in shots.withIndex()) {
            val f = i.toDouble() / (shots.size - 1)
            val target = when (mode) {
                Pacing.CRESCENDO -> longUs + (shortUs - longUs) * f
                Pacing.DECRESCENDO -> shortUs + (longUs - shortUs) * f
                Pacing.PULSE -> if (i % 2 == 0) longUs * 0.7 else shortUs * 1.2
            }.toLong()
            val c = np.findClip(s.id)?.second ?: continue
            if (Kit.isStill(np, c)) { np = runCatching { TimelineEditor.setDuration(np, c.id, target) }.getOrDefault(np); continue }
            val excess = c.durationUs - target
            if (excess < 60_000) continue
            np = runCatching { TimelineEditor.trimEnd(np, c.id, c.endUs - excess / 2) }.getOrDefault(np)
            val c2 = np.findClip(c.id)?.second ?: continue
            np = runCatching { TimelineEditor.trimStart(np, c.id, c2.startUs + (excess - excess / 2)) }.getOrDefault(np)
        }
        return np
    }

    /** Alternating speed ramps on every video shot. */
    fun velocity(p: Project): Project {
        val order = listOf(RampPreset.HERO, RampPreset.BULLET, RampPreset.RUSH, RampPreset.PULSE)
        return mainShots(p).filter { it.kind == ClipKind.MEDIA && p.asset(it.assetId)?.type == MediaType.VIDEO }.withIndex().fold(p) { acc, (i, s) ->
            runCatching { SmartTools.speedRamp(acc, s.id, order[i % order.size]) }.getOrDefault(acc)
        }
    }

    /** Tempo from the markers the user tapped on the beats (median gap), then a cut every 2 beats and markers on the bars. */
    fun bpmFromMarkers(p: Project): Double? {
        val m = p.markers.map { it.timeUs }.sorted()
        if (m.size < 2) return null
        val gaps = m.zipWithNext { a, b -> b - a }.filter { it in 200_000..3_000_000 }.sorted()
        if (gaps.isEmpty()) return null
        var beat = gaps[gaps.size / 2].toDouble()
        while (beat > 1_000_000) beat /= 2                  // markers on bars: back to a beat
        return 60_000_000.0 / beat
    }

    fun tapTempo(p: Project): Project {
        val bpm = bpmFromMarkers(p) ?: return p
        val first = p.markers.minOf { it.timeUs }
        val beat = (60_000_000.0 / bpm).toLong()
        var origin = first; while (origin - beat >= 0) origin -= beat
        val beats = generateSequence(origin) { it + beat }.takeWhile { it < p.durationUs }.toList()
        val np = SmartTools.tempoCut(p, beats)
        val have = np.markers.map { it.timeUs }.toSet()
        val bars = beats.filterIndexed { i, _ -> i % 4 == 0 }.filter { b -> have.none { abs(it - b) < beat / 4 } }
        return np.copy(markers = (np.markers + bars.take(400).map { Marker(Ids.new("mrk"), it, "${bpm.roundToLong()} BPM", 0xFF2BD9FF) }).sortedBy { it.timeUs })
    }

    // ------------------------------------------------------------------ remix

    /** A whole new take on the montage from a seed: order, pacing, look and transitions. */
    fun remix(p: Project, seed: Int): Project {
        val r = java.util.Random(seed * 7919L + 13)
        val main = SmartTools.mainTrack(p) ?: return p
        var np = if (main.clips.size >= 3) ClipOps.shuffle(p, main.id, seed.toLong()) else p
        np = pacing(np, Pacing.entries[r.nextInt(Pacing.entries.size)])
        val looks = Presets.builtIn
        if (looks.isNotEmpty()) np = SmartTools.applyLook(np, looks[r.nextInt(looks.size)].name)
        val trs = listOf("whip", "zoom_in", "spin_zoom", "glitch", "flash_zoom", "push_zoom", "swipe_up", "fade")
        np = SmartTools.transitionsEverywhere(np, trs[r.nextInt(trs.size)])
        if (r.nextBoolean()) np = SmartTools.punchIn(np)
        return np
    }

    // ------------------------------------------------------------------ endings

    /** VHS rewind ending: every shot flashes by in reverse order, 0.14 s each, with the rewind effect. */
    fun rewind(p: Project, flashUs: Long = 140_000): Project {
        val shots = mainShots(p).filter { it.kind == ClipKind.MEDIA && p.asset(it.assetId)?.isVisual == true }
        if (shots.size < 2) return p
        val main = SmartTools.mainTrack(p)!!
        var np = p
        var at = main.endUs
        for (s in shots.reversed().take(40)) {
            val video = np.asset(s.assetId)?.type == MediaType.VIDEO
            val src = (flashUs * 3).coerceAtMost(s.sourceDurationUs)
            val c = s.copy(id = Ids.new("clp"), startUs = at, sourceInUs = if (video) s.sourceOutUs - src else s.sourceInUs, sourceOutUs = if (video) s.sourceOutUs else s.sourceInUs + flashUs,
                speed = if (video) 3.0 else 1.0, speedCurve = emptyList(), transitionIn = null, groupId = null, keyframes = emptyMap(), motion = ClipMotion(), audio = s.audio.copy(muted = true),
                effects = listOfNotNull(fx("vhs_rewind", "intensity" to 0.9)), name = "Rembobinage")
            np = runCatching { TimelineEditor.insertClip(np, main.id, c) }.getOrDefault(np)
            at = np.track(main.id)?.endUs ?: break
        }
        return np
    }

    /** Scrolling end credits: the project's text titles (or "Réalisé avec CUTTINX PRO") rolling up over 6 s. */
    fun credits(p: Project, durUs: Long = 6_000_000): Project {
        val lines = p.allClips.mapNotNull { it.text?.text?.trim() }.filter { it.isNotEmpty() && it.length <= 60 }.distinct().take(12)
        val body = (listOf("FIN", "") + lines.ifEmpty { listOf("Un film de", "Ton nom") } + listOf("", "Réalisé avec CUTTINX PRO")).joinToString("\n")
        var np = p
        val (q, main) = TimelineEditor.mainTrack(np)
        np = runCatching { TimelineEditor.insertClip(q, main.id, TimelineEditor.colorClip(0xFF0B0C0F, main.endUs, durUs).copy(name = "Générique")) }.getOrDefault(q)
        val start = (np.durationUs - durUs).coerceAtLeast(0)
        val style = Titles.style(TextLook.MINIMAL).copy(sizeSp = 34.0, lineSpacing = 1.6, uppercase = false)
        val c = TimelineEditor.textClip(body, start, durUs, style).let {
            it.copy(name = "Générique déroulant", text = it.text!!.copy(animIn = TextAnimation.NONE, animOut = TextAnimation.FADE, maxWidth = 0.8),
                keyframes = mapOf(Prop.POS_Y to KeyframeTrack(listOf(Keyframe(0, 1.45, Easing.Linear), Keyframe(durUs, -0.45, Easing.Linear)))))
        }
        return Kit.place(np, TrackKind.TEXT, "Générique", c)
    }
}
