package com.superessence.cuttinx.core.smart

import com.superessence.cuttinx.core.analysis.FrameStats
import com.superessence.cuttinx.core.analysis.SubjectAnalysis
import com.superessence.cuttinx.core.autoedit.AiActions
import com.superessence.cuttinx.core.autoedit.ProTools
import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.util.Ids
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** The kind of film the autonomous director makes. */
enum class DirectorStyle(val label: String, val pitch: String, val fallbackBpm: Double) {
    VIRAL("Viral", "Accroche choc dès la 1re seconde, coupes rapides, zooms sur les temps, titre et fondus", 124.0),
    CINEMATIC("Cinéma", "Plans plus longs, ralentis sur le climax, étalonnage film et bandes cinéma", 92.0),
    HYPE("Hype", "Énergie maximale : coupes sur chaque temps au drop, impacts, secousses et flashs", 140.0),
    STORY("Récit", "Ordre chronologique des prises, rythme posé, raccords doux", 100.0),
}

/**
 * The autonomous director ("Réalisateur autonome"): from every take and the music, it writes the
 * whole edit like an editor would, then hands back an ordinary editable project.
 *
 * 1. Reads each take: an interest curve per sample (motion, sharpness, colour, exposure, sound),
 *    camera movement and subject position when measured.
 * 2. Reads the music: beats, bars, energy of every bar, the drop (biggest rise of energy).
 * 3. Writes the structure: hook (best moment of all, first second) → build-up → climax on the drop
 *    → outro; shot lengths follow the energy (1, 2 or 4 beats), every cut on a beat, frame-accurate.
 * 4. Casts each slot: the window of a take that best matches the energy wanted there, never the same
 *    moment twice, alternating takes, colour continuity, chronology for the "Récit" style.
 * 5. Finishing: matched colours, style grade, slow motion on the climax, punch-in zooms, impacts on
 *    the drop and on bars, motion-matched blur at the cuts, stabilisation and subject reframing when
 *    measured, hook title, fades, music fitted, beat markers.
 *
 * Everything decided here is pure and tested; the apps only measure the media.
 */
object HyperDirector {

    /** What the apps measured on one take of the main track. Times are source times. */
    class Take(
        val clipId: String,
        val stats: List<FrameStats>,
        /** Loudness in dB every [audioStepUs], starting at the clip's sourceIn (empty = no sound). */
        val audioDb: DoubleArray = DoubleArray(0),
        val audioStepUs: Long = 50_000,
        val look: Magic.Look? = null,
        val motion: List<CameraMotion.Step> = emptyList(),
        val reframe: List<SubjectAnalysis.ReframeKey> = emptyList(),
    )

    /** The music, in timeline time. [energyDb] every [energyStepUs] from [startUs]. */
    class Music(val bpm: Double, val beatsUs: List<Long>, val downbeatsUs: List<Long>, val energyDb: DoubleArray = DoubleArray(0), val energyStepUs: Long = 50_000, val startUs: Long = 0)

    /** One cut of the film being written. */
    data class Slot(val startUs: Long, val endUs: Long, val energy: Double, val section: String, val slowMotion: Boolean = false) {
        val durationUs get() = endUs - startUs
    }

    data class Shot(val slot: Slot, val clipId: String, val sourceInUs: Long, val sourceOutUs: Long, val score: Double)

    data class Result(val project: Project, val shots: List<Shot>, val report: List<String>, val dropUs: Long?)

    // ------------------------------------------------------------------ 1. reading the takes

    /** Interest of every sample of a take, 0..1: what makes a moment worth showing. */
    fun interest(t: Take, style: DirectorStyle): DoubleArray {
        val s = t.stats
        if (s.isEmpty()) return DoubleArray(0)
        val motion = s.map { it.motion }.let { m -> val hi = percentile(m, 0.95).coerceAtLeast(1e-4); m.map { (it / hi).coerceIn(0.0, 1.0) } }
        val sharp = s.map { it.sharpness }.let { m -> val hi = percentile(m, 0.9).coerceAtLeast(1e-4); m.map { (it / hi).coerceIn(0.0, 1.0) } }
        val color = s.map { it.colorfulness }.let { m -> val hi = percentile(m, 0.9).coerceAtLeast(1e-4); m.map { (it / hi).coerceIn(0.0, 1.0) } }
        val first = s.first().timeUs
        val sound = s.map { st ->
            if (t.audioDb.isEmpty()) 0.0 else {
                val i = ((st.timeUs - first) / t.audioStepUs.coerceAtLeast(1)).toInt().coerceIn(0, t.audioDb.lastIndex)
                ((t.audioDb[i] + 50.0) / 44.0).coerceIn(0.0, 1.0)
            }
        }
        val wMotion = when (style) { DirectorStyle.HYPE -> 0.45; DirectorStyle.VIRAL -> 0.35; DirectorStyle.CINEMATIC -> 0.2; DirectorStyle.STORY -> 0.15 }
        val wSound = if (t.audioDb.isEmpty()) 0.0 else if (style == DirectorStyle.STORY) 0.35 else 0.15
        return DoubleArray(s.size) { i ->
            val exposure = 1.0 - min(1.0, abs(s[i].brightness - 0.5) * 2.2) // too dark / burnt = bad
            val blackOrFlash = if (s[i].brightness < 0.06 || s[i].brightness > 0.96) 0.6 else 0.0
            val v = wMotion * motion[i] + 0.25 * sharp[i] + 0.12 * color[i] + 0.18 * exposure + wSound * sound[i] - blackOrFlash
            v.coerceIn(0.0, 1.5) / (wMotion + 0.25 + 0.12 + 0.18 + wSound)
        }
    }

    // ------------------------------------------------------------------ 2. reading the music

    /** Bars of the music: (start, end, normalised energy 0..1). */
    fun bars(m: Music, untilUs: Long): List<Triple<Long, Long, Double>> {
        val beat = (60_000_000.0 / m.bpm.coerceIn(50.0, 220.0)).roundToLong()
        val starts = (m.downbeatsUs.ifEmpty { m.beatsUs.filterIndexed { i, _ -> i % 4 == 0 } }).filter { it < untilUs }.sorted().distinct()
            .ifEmpty { generateSequence(m.startUs) { it + 4 * beat }.takeWhile { it < untilUs }.toList() }
        val raw = starts.mapIndexed { i, a ->
            val b = starts.getOrNull(i + 1) ?: (a + 4 * beat)
            val e = if (m.energyDb.isEmpty()) Double.NaN else {
                val i0 = ((a - m.startUs) / m.energyStepUs.coerceAtLeast(1)).toInt().coerceAtLeast(0)
                val i1 = ((b - m.startUs) / m.energyStepUs.coerceAtLeast(1)).toInt().coerceAtMost(m.energyDb.size)
                if (i1 <= i0) Double.NaN else (i0 until i1).map { m.energyDb[it] }.average()
            }
            Triple(a, b, e)
        }
        val known = raw.map { it.third }.filter { !it.isNaN() }
        val lo = if (known.isEmpty()) 0.0 else percentile(known, 0.1); val hi = if (known.isEmpty()) 1.0 else percentile(known, 0.95)
        return raw.mapIndexed { i, (a, b, e) ->
            // no measure: a narrative arc (rise to 70 %, then fall)
            val n = if (e.isNaN() || hi - lo < 0.5) arc(i.toDouble() / max(1, raw.size - 1)) else ((e - lo) / (hi - lo)).coerceIn(0.0, 1.0)
            Triple(a, b, n)
        }
    }

    private fun arc(x: Double) = if (x < 0.7) 0.35 + 0.6 * (x / 0.7) else 0.95 - 0.55 * ((x - 0.7) / 0.3)

    /** The drop: the bar where energy rises the most (smoothed), between 20 % and 85 % of the film. */
    fun findDrop(bars: List<Triple<Long, Long, Double>>, totalUs: Long): Long? {
        if (bars.size < 4) return null
        var best = -1.0; var at: Long? = null
        for (i in 2 until bars.size) {
            val before = (bars[i - 1].third + bars[i - 2].third) / 2
            val after = (bars[i].third + (bars.getOrNull(i + 1)?.third ?: bars[i].third)) / 2
            val rise = after - before
            val x = bars[i].first.toDouble() / max(1L, totalUs)
            if (x in 0.2..0.85 && rise > best) { best = rise; at = bars[i].first }
        }
        return if (best >= 0.15) at else null
    }

    // ------------------------------------------------------------------ 3. structure

    /**
     * The slots of the film: one per cut, on the beats. Shot length by energy: ≥ 0.75 → 1 beat
     * (HYPE) or 2 beats, ≥ 0.45 → 2 beats, else 4 beats (8 for CINEMATIC). The first slot is the hook.
     */
    fun structure(m: Music, targetUs: Long, style: DirectorStyle, frameUs: Double): List<Slot> {
        val beat = 60_000_000.0 / m.bpm.coerceIn(50.0, 220.0)
        val allBars = bars(m, targetUs + (8 * beat).roundToLong())
        val drop = findDrop(allBars, targetUs)
        fun snap(t: Long) = ((t / frameUs).roundToLong() * frameUs).roundToLong()
        val slots = ArrayList<Slot>()
        var t = allBars.firstOrNull()?.first ?: m.startUs
        for ((a, b, e) in allBars) {
            if (t >= targetUs) break
            val section = when {
                slots.isEmpty() -> "Accroche"
                drop != null && a >= drop && a < drop + (8 * beat).roundToLong() -> "Climax"
                drop != null && a < drop -> "Montée"
                a.toDouble() / targetUs > 0.85 -> "Final"
                else -> if (e >= 0.6) "Climax" else "Développement"
            }
            val beats = when {
                slots.isEmpty() -> 2.0 // hook: short and strong
                style == DirectorStyle.HYPE && e >= 0.75 -> 1.0
                e >= 0.75 -> 2.0
                e >= 0.45 -> if (style == DirectorStyle.CINEMATIC) 4.0 else 2.0
                else -> if (style == DirectorStyle.CINEMATIC) 8.0 else 4.0
            }
            val len = beat * beats
            var s = max(t, a)
            val barEnd = b
            while (s + len * 0.5 < barEnd && s < targetUs) {
                val e2 = min(barEnd, (s + len).roundToLong()).coerceAtMost(targetUs)
                if (e2 - s < 250_000) break
                val slow = style != DirectorStyle.HYPE && drop != null && s == max(drop, s) && s - drop < beat * 0.5 && style != DirectorStyle.STORY
                slots += Slot(snap(s), snap(e2), e, if (slots.isEmpty()) "Accroche" else section, slow)
                s = e2
                if (slots.size == 1) break // the hook stays alone in its bar
            }
            t = max(s, barEnd)
        }
        // glue: the film starts at 0 and every shot lasts until the next cut (cuts stay on the beats)
        return slots.mapIndexed { i, sl ->
            val start = if (i == 0) 0L else sl.startUs
            val end = slots.getOrNull(i + 1)?.startUs ?: sl.endUs
            sl.copy(startUs = start, endUs = max(end, start + 100_000))
        }
    }

    // ------------------------------------------------------------------ 4. casting

    private data class Window(val inUs: Long, val outUs: Long, val score: Double, val motion: Double)

    /** Best window of [lenUs] source time in a take, avoiding [used] ranges; score = mean interest. */
    private fun bestWindow(t: Take, curve: DoubleArray, clip: Clip, lenUs: Long, used: List<LongRange>, wantMotion: Double, after: Long?): Window? {
        val s = t.stats
        if (s.size < 2 || clip.sourceDurationUs < lenUs) return null
        val step = ((s.last().timeUs - s.first().timeUs) / max(1, s.size - 1)).coerceAtLeast(1)
        val n = (lenUs / step).toInt().coerceAtLeast(1)
        val motionMax = s.maxOf { it.motion }.coerceAtLeast(1e-4)
        var best: Window? = null
        for (i in 0..max(0, s.size - n)) {
            val a = s[i].timeUs.coerceAtLeast(clip.sourceInUs)
            val b = a + lenUs
            if (b > clip.sourceOutUs) break
            if (after != null && a < after) continue
            if (used.any { r -> a < r.last + 200_000 && b > r.first - 200_000 }) continue
            var sc = 0.0; var mo = 0.0; var k = 0
            for (j in i until min(s.size, i + n)) { sc += curve[j]; mo += s[j].motion; k++ }
            if (k == 0) continue
            sc /= k; mo = (mo / k / motionMax).coerceIn(0.0, 1.0)
            val fit = sc + 0.35 * (1.0 - abs(mo - wantMotion))
            if (best == null || fit > best.score) best = Window(a, b, fit, mo)
        }
        return best
    }

    private fun lookDistance(a: Magic.Look?, b: Magic.Look?) = if (a == null || b == null) 0.0 else sqrt((a.r - b.r).let { it * it } + (a.g - b.g).let { it * it } + (a.b - b.b).let { it * it })

    fun cast(p: Project, slots: List<Slot>, takes: List<Take>, style: DirectorStyle): List<Shot> {
        val clips = takes.mapNotNull { t -> p.findClip(t.clipId)?.second?.let { t to it } }
        if (clips.isEmpty() || slots.isEmpty()) return emptyList()
        val curves = clips.associate { (t, _) -> t.clipId to interest(t, style) }
        val used = HashMap<String, MutableList<LongRange>>()
        val lastUse = HashMap<String, Int>()
        val chrono = HashMap<String, Long>()
        val out = ArrayList<Shot>()
        for ((i, slot) in slots.withIndex()) {
            val speed = if (slot.slowMotion) 0.5 else 1.0
            val len = (slot.durationUs * speed).roundToLong()
            var bestShot: Shot? = null; var bestFit = Double.NEGATIVE_INFINITY
            for ((ti, pair) in clips.withIndex()) {
                val (t, c) = pair
                val after = if (style == DirectorStyle.STORY) chrono[t.clipId] else null
                // the hook wants the most striking moment of all
                val want = if (i == 0) 0.9 else slot.energy
                val w = bestWindow(t, curves.getValue(t.clipId), c, len, used[t.clipId].orEmpty(), want, after) ?: continue
                var fit = w.score
                // variety: the same take twice in a row is a jump cut; keep it rare
                lastUse[t.clipId]?.let { last -> fit -= when (i - last) { 1 -> 0.45; 2 -> 0.18; else -> 0.0 } }
                // colour continuity with the previous shot
                out.lastOrNull()?.let { prev -> fit -= 0.25 * lookDistance(takes.firstOrNull { it.clipId == prev.clipId }?.look, t.look) }
                if (style == DirectorStyle.STORY) {
                    fit += 0.3 * (1.0 - ti.toDouble() / clips.size) * (if (i < slots.size / 2) 1.0 else 0.0)
                    // a story moves forward in each take: skipping material costs
                    fit -= 0.08 * ((w.inUs - (after ?: c.sourceInUs)).coerceAtLeast(0) / 1e6)
                }
                if (fit > bestFit) { bestFit = fit; bestShot = Shot(slot, t.clipId, w.inUs, w.outUs, w.score) }
            }
            // a story never goes back in time: it ends when the takes are told
            if (bestShot == null && style == DirectorStyle.STORY) break
            // nothing left that fits (all used): allow reuse of the least recently used take
            val shot = bestShot ?: clips.minByOrNull { lastUse[it.first.clipId] ?: -1 }?.let { (t, c) ->
                val a = c.sourceInUs; val b = min(c.sourceOutUs, a + len)
                if (b - a < 100_000) null else Shot(slot, t.clipId, a, b, 0.0)
            } ?: continue
            out += shot
            used.getOrPut(shot.clipId) { ArrayList() } += shot.sourceInUs..shot.sourceOutUs
            lastUse[shot.clipId] = i
            // the hook is a teaser: the story itself starts from the beginning of the takes
            if (i > 0) chrono[shot.clipId] = shot.sourceOutUs
        }
        return out
    }

    // ------------------------------------------------------------------ 5. writing the project

    /**
     * Makes the film. [targetUs] = wanted length (clamped by the music and the material). Returns the
     * project plus a plain-French report of every decision.
     */
    fun direct(p: Project, takes: List<Take>, music: Music?, style: DirectorStyle, targetUs: Long, hookText: String? = null): Result {
        val main = SmartTools.mainTrack(p) ?: return Result(p, emptyList(), listOf("Aucune piste vidéo"), null)
        val usable = takes.filter { t -> p.findClip(t.clipId)?.second?.let { it.kind == ClipKind.MEDIA && p.asset(it.assetId)?.type == MediaType.VIDEO } == true && t.stats.size >= 2 }
        if (usable.isEmpty()) return Result(p, emptyList(), listOf("Aucune vidéo analysée sur la piste principale"), null)
        val material = usable.sumOf { p.findClip(it.clipId)!!.second.sourceDurationUs }
        val frameUs = 1_000_000.0 / p.format.frameRate.coerceIn(1, 240)
        val m = music?.takeIf { it.beatsUs.size >= 4 } ?: run {
            val beat = 60_000_000.0 / style.fallbackBpm
            Music(style.fallbackBpm, generateSequence(0L) { (it + beat).roundToLong() }.takeWhile { it < targetUs + 20_000_000 }.toList(), emptyList())
        }
        val target = min(targetUs, (material * 0.8).roundToLong()).coerceAtLeast(3_000_000)
        val slots = structure(m, target, style, frameUs)
        val shots = cast(p, slots, usable, style)
        if (shots.isEmpty()) return Result(p, emptyList(), listOf("Pas assez de matière pour monter"), null)
        val report = ArrayList<String>()
        val drop = findDrop(bars(m, target + 8_000_000), target)
        report += if (music != null && music.beatsUs.size >= 4) "Musique lue : ${m.bpm.toInt()} BPM, ${bars(m, target).size} mesures" else "Pas de musique : tempo de travail ${m.bpm.toInt()} BPM, arc narratif montée → climax → final"
        drop?.let { report += "Drop détecté à ${SmartTools.clock(it)} : le climax est posé dessus" }
        val first = shots.first()
        report += "Accroche : le meilleur moment de « ${p.findClip(first.clipId)?.second?.name?.ifBlank { "prise" } ?: "prise"} » en ouverture"

        // the new main track: one clip per shot, cut on the slots
        val byId = usable.associateBy { it.clipId }
        val newIds = HashMap<String, String>() // new clip -> source take
        val clips = shots.map { s ->
            val src = p.findClip(s.clipId)!!.second
            val id = Ids.new("cl")
            newIds[id] = s.clipId
            val speed = (s.sourceOutUs - s.sourceInUs).toDouble() / s.slot.durationUs.coerceAtLeast(1)
            src.copy(id = id, startUs = s.slot.startUs, sourceInUs = s.sourceInUs, sourceOutUs = s.sourceOutUs, speed = speed.coerceIn(0.1, 100.0),
                speedCurve = emptyList(), keyframes = emptyMap(), transitionIn = null, groupId = null, motion = ClipMotion(),
                transform = src.transform.copy(fill = true), effects = src.effects.filterNot { it.id.startsWith("match_") || it.id.startsWith("look_") })
        }
        val others = main.clips.filter { it.kind != ClipKind.MEDIA || p.asset(it.assetId)?.type != MediaType.VIDEO }
        var np = p.copy(tracks = p.tracks.map { if (it.id == main.id) it.copy(clips = clips + others.filter { o -> o.startUs >= clips.last().let { l -> l.startUs + l.durationUs } }) else it })
        report += "${shots.size} plans coupés sur les temps, de ${shots.minOf { it.slot.durationUs } / 1000} à ${shots.maxOf { it.slot.durationUs } / 1000} ms, ${shots.map { it.clipId }.distinct().size} prise(s) utilisée(s), aucun moment répété"

        // colour: every shot matched to the common look, then the style grade
        val looks = newIds.mapNotNull { (nid, src) -> byId[src]?.look?.let { nid to it } }.toMap()
        if (looks.size >= 2) { np = Magic.matchShots(np, looks); report += "Couleurs égalisées sur ${looks.size} plans" }
        when (style) {
            DirectorStyle.CINEMATIC -> {
                np = SmartTools.applyLook(np, "Blockbuster")
                np = Lab.burst(np, "Cinéma", 0, np.durationUs, listOfNotNull(Lab.fx("letterbox", "ratio" to 2.39), Lab.fx("halation", "amount" to 0.25)))
                report += "Étalonnage film, halation et bandes 2.39"
            }
            DirectorStyle.VIRAL -> np = SmartTools.applyLook(np, "Éclat")
            DirectorStyle.HYPE -> np = SmartTools.applyLook(np, "Clip musical")
            DirectorStyle.STORY -> Unit
        }

        // framing: stabilisation and subject reframing when measured
        var stabilized = 0; var reframed = 0
        for ((nid, src) in newIds) {
            val t = byId[src] ?: continue
            if (t.reframe.isNotEmpty() && p.format.aspectRatio < 0.9) { np = runCatching { AiActions.applyReframe(np, nid, t.reframe) }.getOrDefault(np); reframed++ }
            else if (t.motion.size >= 4) {
                val c = np.findClip(nid)?.second ?: continue
                val st = CameraMotion.stabilization(t.motion.filter { it.timeUs in c.sourceInUs..c.sourceOutUs }, c.sourceInUs, 0.7, 1.15)
                if (st.shakiness > 0.004) { np = CameraMotion.applyStabilization(np, nid, st); stabilized++ }
            }
        }
        if (reframed > 0) report += "$reframed plan(s) recadré(s) sur leur sujet"
        if (stabilized > 0) report += "$stabilized plan(s) tremblé(s) stabilisé(s)"

        // energy: punch-ins on high-energy shots, impacts on the drop and on bars of the climax
        val newShots = np.track(main.id)!!.sortedClips().filter { it.id in newIds }
        var zoom = false
        for ((i, c) in newShots.withIndex()) {
            val sl = shots.getOrNull(i)?.slot ?: continue
            if (sl.energy >= 0.6 && style != DirectorStyle.STORY) {
                zoom = !zoom
                if (zoom) np = Kit.edit(np, c.id) { it.copy(transform = it.transform.copy(scaleX = it.transform.scaleX * 1.1, scaleY = it.transform.scaleY * 1.1)) }
            }
            if (sl.slowMotion) report += "Ralenti ×0,5 sur le climax (${SmartTools.clock(sl.startUs)})"
        }
        if (style != DirectorStyle.STORY) {
            val hits = shots.filter { it.slot.section == "Climax" }.map { it.slot.startUs }.let { if (style == DirectorStyle.HYPE) it else it.filterIndexed { i, _ -> i % 2 == 0 } }
            for (h in hits.take(120)) np = Lab.burst(np, "Impacts", (h - 40_000).coerceAtLeast(0), 240_000,
                listOfNotNull(Lab.fx("zoom_blur", "amount" to if (style == DirectorStyle.HYPE) 0.3 else 0.18), if (style == DirectorStyle.HYPE) Lab.fx("shake", "intensity" to 0.025) else null),
                mapOf("amount" to "zoom_blur", "intensity" to "shake"))
            drop?.let { d -> np = Lab.burst(np, "Drop", (d - 30_000).coerceAtLeast(0), 300_000, listOfNotNull(Lab.fx("rgb_split", "amount" to 18.0), Lab.fx("zoom_blur", "amount" to 0.35)), mapOf("amount" to "zoom_blur")) }
            if (hits.isNotEmpty()) report += "${hits.size} impact(s) sur les temps du climax"
        }

        // motion-matched cuts (blur in the direction the camera moves), timing kept
        val motionByNew = newIds.mapNotNull { (nid, src) -> byId[src]?.motion?.takeIf { it.isNotEmpty() }?.let { nid to it } }.toMap()
        if (motionByNew.size >= 2) { np = CameraMotion.matchCuts(np, motionByNew, transitions = false); report += "Raccords dans le sens du mouvement de caméra" }

        // words and sound
        if (style == DirectorStyle.VIRAL || hookText != null) np = ProTools.hookTitle(np, hookText ?: "ATTENDS LA FIN", min(2_500_000L, shots.first().slot.durationUs + 1_000_000))
        np = SmartTools.fadeInOut(np, if (style == DirectorStyle.CINEMATIC) 900_000 else 400_000)
        np = SmartTools.microFades(np)
        if (music != null) { np = SmartTools.fitMusicToVideo(np); np = AiActions.addBeatMarkers(np, bars(m, np.durationUs).map { it.first }) }
        np = PlusLab.frameLock(np)
        report += "Durée finale ${SmartTools.clock(np.durationUs)}, tout reste modifiable (annulable en un geste)"
        return Result(np, shots, report, drop)
    }

    private fun percentile(v: List<Double>, q: Double): Double { if (v.isEmpty()) return 0.0; val s = v.sorted(); return s[((s.size - 1) * q).toInt().coerceIn(0, s.size - 1)] }
}
