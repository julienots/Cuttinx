package com.superessence.cuttinx.core.smart

import com.superessence.cuttinx.core.analysis.Frame
import com.superessence.cuttinx.core.anim.SpeedMath
import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.timeline.TimelineEditor
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Camera motion, measured on the small frames the apps sample: the global shift of the picture
 * between two frames (projection profiles + normalised cross-correlation, sub-pixel), the path of
 * the camera, and what CUTTINX builds on it:
 *  - stabilisation (the shaky part of the path is compensated with keyframes, slight zoom to hide the edges);
 *  - transitions that follow the movement (motion blur in the direction the camera moves at each cut).
 * Original code, no external library.
 */
object CameraMotion {
    /** Shift of the picture from the previous frame, in fractions of the frame (x: width, y: height). */
    data class Step(val timeUs: Long, val dx: Double, val dy: Double, val confidence: Double)

    /** Camera position (cumulated shift) at [timeUs]. */
    data class Point(val timeUs: Long, val x: Double, val y: Double)

    private fun profile(f: Frame, columns: Boolean): DoubleArray {
        val l = f.luma; val w = f.width; val h = f.height
        val out = DoubleArray(if (columns) w else h)
        if (columns) { for (y in 0 until h) for (x in 0 until w) out[x] += l[y * w + x].toDouble(); for (x in 0 until w) out[x] /= h }
        else { for (y in 0 until h) { var s = 0.0; for (x in 0 until w) s += l[y * w + x]; out[y] = s / w } }
        // remove the slow trend (lighting) so only the structure is compared
        val r = max(2, out.size / 8)
        val trend = DoubleArray(out.size) { i -> var s = 0.0; var n = 0; for (j in max(0, i - r)..min(out.size - 1, i + r)) { s += out[j]; n++ }; s / n }
        return DoubleArray(out.size) { out[it] - trend[it] }
    }

    /**
     * Best shift (in samples) of [b] relative to [a] within ±[maxShift]: content at index i in [a]
     * is at i + shift in [b]. Returns the shift (sub-sample) and the correlation peak (0..1).
     */
    fun bestShift(a: DoubleArray, b: DoubleArray, maxShift: Int): Pair<Double, Double> {
        val n = min(a.size, b.size)
        val m = maxShift.coerceIn(1, n / 2)
        val scores = DoubleArray(2 * m + 1)
        for (k in -m..m) {
            var sab = 0.0; var saa = 0.0; var sbb = 0.0
            for (i in max(0, -k) until min(n, n - k)) { val x = a[i]; val y = b[i + k]; sab += x * y; saa += x * x; sbb += y * y }
            scores[k + m] = if (saa <= 1e-12 || sbb <= 1e-12) 0.0 else sab / sqrt(saa * sbb)
        }
        var bi = m; for (i in scores.indices) if (scores[i] > scores[bi]) bi = i
        // parabolic refinement
        var sub = 0.0
        if (bi in 1 until scores.size - 1) {
            val l = scores[bi - 1]; val c = scores[bi]; val r = scores[bi + 1]
            val d = l - 2 * c + r
            if (abs(d) > 1e-12) sub = (0.5 * (l - r) / d).coerceIn(-0.5, 0.5)
        }
        return (bi - m + sub) to scores[bi].coerceIn(0.0, 1.0)
    }

    /** Shift between consecutive frames (same size), at most 20 % of the frame per step. */
    fun estimate(frames: List<Frame>): List<Step> {
        if (frames.size < 2) return emptyList()
        val out = ArrayList<Step>(frames.size - 1)
        var pc = profile(frames[0], true); var pr = profile(frames[0], false)
        for (i in 1 until frames.size) {
            val f = frames[i]; val prev = frames[i - 1]
            val cc = profile(f, true); val cr = profile(f, false)
            if (f.width != prev.width || f.height != prev.height) { out += Step(f.timeUs, 0.0, 0.0, 0.0); pc = cc; pr = cr; continue }
            val (sx, kx) = bestShift(pc, cc, (f.width * 0.2).roundToInt())
            val (sy, ky) = bestShift(pr, cr, (f.height * 0.2).roundToInt())
            out += Step(f.timeUs, sx / f.width, sy / f.height, min(kx, ky))
            pc = cc; pr = cr
        }
        return out
    }

    /** Cumulated camera path from the steps (unreliable steps count as no movement). */
    fun path(startUs: Long, steps: List<Step>, minConfidence: Double = 0.35): List<Point> {
        val out = ArrayList<Point>(steps.size + 1)
        var x = 0.0; var y = 0.0
        out += Point(startUs, 0.0, 0.0)
        for (s in steps) {
            if (s.confidence >= minConfidence) { x += s.dx; y += s.dy }
            out += Point(s.timeUs, x, y)
        }
        return out
    }

    /** Gaussian smoothing of a path, sigma in samples; ends handled by clamping. */
    fun smooth(p: List<Point>, sigma: Double): List<Point> {
        if (p.size < 3 || sigma <= 0) return p
        val r = (sigma * 3).roundToInt().coerceAtLeast(1)
        val w = DoubleArray(2 * r + 1) { exp(-((it - r) * (it - r)) / (2 * sigma * sigma)) }
        return p.indices.map { i ->
            var sx = 0.0; var sy = 0.0; var sw = 0.0
            for (k in -r..r) { val j = (i + k).coerceIn(0, p.size - 1); sx += p[j].x * w[k + r]; sy += p[j].y * w[k + r]; sw += w[k + r] }
            Point(p[i].timeUs, sx / sw, sy / sw)
        }
    }

    /** How shaky a path is: average distance (fraction of the frame) between the raw and the smoothed path. */
    fun shakiness(raw: List<Point>, smooth: List<Point>): Double =
        if (raw.isEmpty()) 0.0 else raw.indices.sumOf { abs(raw[it].x - smooth[it].x) + abs(raw[it].y - smooth[it].y) } / raw.size

    /** Stabilisation result of one clip. */
    data class Stabilization(val zoom: Double, val keysX: List<Pair<Long, Double>>, val keysY: List<Pair<Long, Double>>, val shakiness: Double)

    /**
     * Correction of a camera path: the picture is moved against the shake (raw - smooth), with the
     * zoom needed so the moved picture still fills the frame (capped at [maxZoom]); corrections larger
     * than the zoom allows are scaled down. Times are source times. [strength] 0..1 (1 = smoothest).
     */
    fun stabilization(steps: List<Step>, startUs: Long, strength: Double = 0.8, maxZoom: Double = 1.25, maxKeys: Int = 160): Stabilization {
        val raw = path(startUs, steps)
        if (raw.size < 3) return Stabilization(1.0, emptyList(), emptyList(), 0.0)
        val sm = smooth(raw, 2.0 + 10.0 * strength.coerceIn(0.0, 1.0))
        val cx = raw.indices.map { sm[it].x - raw[it].x }
        val cy = raw.indices.map { sm[it].y - raw[it].y }
        // the zoom needed: a shift of c (fraction of the frame) needs 1 + 2c of zoom to keep the edges out
        val need = 1.0 + 2.0 * max(percentile(cx.map { abs(it) }, 0.95), percentile(cy.map { abs(it) }, 0.95))
        val zoom = need.coerceIn(1.0, maxZoom)
        val limit = (zoom - 1.0) / 2.0
        val k = if (need > zoom && need > 1.0) limit / ((need - 1.0) / 2.0) else 1.0
        val stride = max(1, raw.size / maxKeys)
        val idx = (raw.indices step stride).toMutableList().also { if (it.last() != raw.lastIndex) it += raw.lastIndex }
        return Stabilization(zoom,
            idx.map { raw[it].timeUs to (cx[it] * k).coerceIn(-limit, limit) },
            idx.map { raw[it].timeUs to (cy[it] * k).coerceIn(-limit, limit) },
            shakiness(raw, sm))
    }

    private fun percentile(v: List<Double>, q: Double): Double { if (v.isEmpty()) return 0.0; val s = v.sorted(); return s[((s.size - 1) * q).roundToInt()] }

    /**
     * Applies a stabilisation to a clip: zoom on the transform, position keyframes (timeline time
     * relative to the clip) converted from fractions of the picture to canvas units, fill mode on.
     */
    fun applyStabilization(p: Project, clipId: String, s: Stabilization): Project {
        if (s.keysX.isEmpty()) return p
        return Kit.edit(p, clipId) { c ->
            val a = p.asset(c.assetId)
            val W = p.format.width.toDouble(); val H = p.format.height.toDouble()
            val sw = (a?.displayWidth ?: p.format.width).toDouble().coerceAtLeast(1.0); val sh = (a?.displayHeight ?: p.format.height).toDouble().coerceAtLeast(1.0)
            val base = max(W / sw, H / sh) // fill
            val shownW = sw * base * s.zoom; val shownH = sh * base * s.zoom
            fun keys(list: List<Pair<Long, Double>>, centre: Double, shown: Double, canvas: Double) = KeyframeTrack(list.filter { it.first in c.sourceInUs..c.sourceOutUs }
                .map { (t, v) -> Keyframe(SpeedMath.sourceToTimeline(c, t), centre + v * shown / canvas, Easing.Linear) }.distinctBy { it.timeUs })
            c.copy(stabilization = 1.0,
                transform = c.transform.copy(fill = true, scaleX = c.transform.scaleX.coerceAtLeast(1.0) * s.zoom, scaleY = c.transform.scaleY.coerceAtLeast(1.0) * s.zoom),
                keyframes = c.keyframes + (Prop.POS_X to keys(s.keysX, c.transform.posX, shownW, W)) + (Prop.POS_Y to keys(s.keysY, c.transform.posY, shownH, H)))
        }
    }

    // ------------------------------------------------------------------ motion-matched cuts

    /** Average movement (fraction of frame per second) over [fromUs, toUs] of source time. */
    fun velocity(steps: List<Step>, fromUs: Long, toUs: Long): Pair<Double, Double> {
        val sel = steps.filter { it.timeUs in fromUs..toUs && it.confidence >= 0.35 }
        if (sel.size < 2) return 0.0 to 0.0
        val dt = (sel.last().timeUs - sel.first().timeUs).coerceAtLeast(1) / 1e6
        return sel.drop(1).sumOf { it.dx } / dt to sel.drop(1).sumOf { it.dy } / dt
    }

    /**
     * Direction (degrees, 0 = towards the right, 90 = down) of the camera movement across a cut, or
     * null when the two shots barely move. The outgoing end and the incoming start are averaged.
     */
    fun cutDirection(outV: Pair<Double, Double>, inV: Pair<Double, Double>, minSpeed: Double = 0.08): Double? {
        val vx = (outV.first + inV.first) / 2; val vy = (outV.second + inV.second) / 2
        if (sqrt(vx * vx + vy * vy) < minSpeed) return null
        // the picture moves opposite to the camera: blur along the picture's movement
        var a = Math.toDegrees(atan2(vy, vx))
        if (a < 0) a += 360.0
        return a
    }

    /**
     * Motion-matched cuts on the main track: at every cut where the camera moves, a short motion
     * blur in the direction of the movement (and a whip transition when [transitions] is on and
     * the timing may move). [motion] = steps per clip id (source time).
     */
    fun matchCuts(p: Project, motion: Map<String, List<Step>>, transitions: Boolean = true): Project {
        val main = SmartTools.mainTrack(p) ?: return p
        val shots = main.sortedClips().filter { it.kind == ClipKind.MEDIA }
        var np = p
        var count = 0
        for (i in 1 until shots.size) {
            val a = shots[i - 1]; val b = shots[i]
            val sa = motion[a.id] ?: continue; val sb = motion[b.id] ?: continue
            val dir = cutDirection(velocity(sa, a.sourceOutUs - 400_000, a.sourceOutUs), velocity(sb, b.sourceInUs, b.sourceInUs + 400_000)) ?: continue
            val cut = np.findClip(b.id)?.second?.startUs ?: continue
            np = Lab.burst(np, "Raccords mouvement", cut - 120_000, 260_000, listOfNotNull(Lab.fx("motion_blur", "amount" to 40.0, "angle" to dir)), mapOf("amount" to "motion_blur"))
            if (transitions) {
                // whip: 0 left, 1 right, 2 up, 3 down (the new shot comes from where the camera goes)
                val d = when { dir < 45 || dir >= 315 -> 1.0; dir < 135 -> 3.0; dir < 225 -> 0.0; else -> 2.0 }
                np = runCatching { TimelineEditor.setTransition(np, b.id, TransitionInstance("whip", 300_000, mapOf("direction" to d))) }.getOrDefault(np)
            }
            count++
        }
        return np
    }
}
