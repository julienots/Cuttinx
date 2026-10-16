package com.superessence.cuttinx.core.analysis

import kotlin.math.*

/** A small sampled frame (ARGB pixels), produced by the platform frame sampler. */
class Frame(val timeUs: Long, val width: Int, val height: Int, val argb: IntArray) {
    val luma: FloatArray by lazy {
        FloatArray(argb.size) { i -> val c = argb[i]; (0.2126f * ((c shr 16) and 255) + 0.7152f * ((c shr 8) and 255) + 0.0722f * (c and 255)) / 255f }
    }
}

data class FrameStats(val timeUs: Long, val brightness: Double, val sharpness: Double, val colorfulness: Double, val motion: Double, val sceneDiff: Double)

object VideoAnalysis {
    fun histogram(f: Frame): DoubleArray {
        val h = DoubleArray(8 * 4 * 4)
        for (c in f.argb) {
            val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
            val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
            val v = mx / 255.0
            val s = if (mx == 0) 0.0 else (mx - mn).toDouble() / mx
            var hue = 0.0
            if (mx != mn) {
                hue = when (mx) { r -> (g - b).toDouble() / (mx - mn); g -> 2 + (b - r).toDouble() / (mx - mn); else -> 4 + (r - g).toDouble() / (mx - mn) }
                hue = ((hue * 60) + 360) % 360
            }
            val hi = (hue / 45).toInt().coerceIn(0, 7); val si = (s * 4).toInt().coerceIn(0, 3); val vi = (v * 4).toInt().coerceIn(0, 3)
            h[hi * 16 + si * 4 + vi] += 1.0
        }
        val n = f.argb.size.toDouble().coerceAtLeast(1.0)
        for (i in h.indices) h[i] /= n
        return h
    }

    /** 0 (identical) .. 1 (completely different) — histogram intersection distance mixed with pixel diff. */
    fun difference(a: Frame, b: Frame, ha: DoubleArray = histogram(a), hb: DoubleArray = histogram(b)): Double {
        var inter = 0.0
        for (i in ha.indices) inter += min(ha[i], hb[i])
        val histD = 1 - inter
        val pix = if (a.width == b.width && a.height == b.height) {
            var acc = 0.0
            val la = a.luma; val lb = b.luma
            for (i in la.indices) acc += abs(la[i] - lb[i])
            acc / la.size
        } else histD
        return (histD * 0.6 + pix * 0.4 * 2).coerceIn(0.0, 1.0)
    }

    fun brightness(f: Frame) = f.luma.average()

    /** Variance of the Laplacian of luma (focus measure). */
    fun sharpness(f: Frame): Double {
        val w = f.width; val h = f.height; val l = f.luma
        if (w < 3 || h < 3) return 0.0
        var sum = 0.0; var sq = 0.0; var n = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val lap = 4 * l[i] - l[i - 1] - l[i + 1] - l[i - w] - l[i + w]
            sum += lap; sq += lap * lap; n++
        }
        val m = sum / n
        return sq / n - m * m
    }

    /** Hasler & Süsstrunk colourfulness metric (normalised ~0..1). */
    fun colorfulness(f: Frame): Double {
        var mRg = 0.0; var mYb = 0.0; var sRg = 0.0; var sYb = 0.0
        val n = f.argb.size.toDouble()
        for (c in f.argb) {
            val r = ((c shr 16) and 255).toDouble(); val g = ((c shr 8) and 255).toDouble(); val b = (c and 255).toDouble()
            val rg = r - g; val yb = 0.5 * (r + g) - b
            mRg += rg; mYb += yb; sRg += rg * rg; sYb += yb * yb
        }
        mRg /= n; mYb /= n
        val stdRg = sqrt(max(0.0, sRg / n - mRg * mRg)); val stdYb = sqrt(max(0.0, sYb / n - mYb * mYb))
        return (sqrt(stdRg * stdRg + stdYb * stdYb) + 0.3 * sqrt(mRg * mRg + mYb * mYb)) / 150.0
    }

    fun motion(a: Frame, b: Frame): Double {
        if (a.width != b.width || a.height != b.height) return 0.0
        val la = a.luma; val lb = b.luma
        var acc = 0.0
        for (i in la.indices) acc += abs(la[i] - lb[i])
        return acc / la.size
    }

    fun stats(frames: List<Frame>): List<FrameStats> {
        val hist = frames.map { histogram(it) }
        return frames.mapIndexed { i, f ->
            val prev = frames.getOrNull(i - 1)
            FrameStats(
                f.timeUs, brightness(f), sharpness(f), colorfulness(f),
                if (prev != null) motion(prev, f) else 0.0,
                if (prev != null) difference(prev, f, hist[i - 1], hist[i]) else 0.0,
            )
        }
    }
}

/** Scene cut detection with an adaptive threshold (local mean + k·σ). */
object SceneDetector {
    data class Params(val minThreshold: Double = 0.28, val k: Double = 3.0, val window: Int = 8, val minSceneUs: Long = 600_000)

    fun detect(frames: List<Frame>, params: Params = Params()): List<Long> = detectFromStats(VideoAnalysis.stats(frames), params)

    fun detectFromStats(stats: List<FrameStats>, params: Params = Params()): List<Long> {
        val cuts = ArrayList<Long>()
        val d = stats.map { it.sceneDiff }
        for (i in 1 until stats.size) {
            val lo = max(1, i - params.window); val hi = min(stats.size - 1, i + params.window)
            val neigh = (lo..hi).filter { it != i }.map { d[it] }
            val mean = neigh.average().takeIf { !it.isNaN() } ?: 0.0
            val sd = sqrt(neigh.map { (it - mean).pow(2) }.average().takeIf { !it.isNaN() } ?: 0.0)
            val thr = max(params.minThreshold, mean + params.k * sd)
            val isPeak = d[i] >= (d.getOrNull(i - 1) ?: 0.0) && d[i] >= (d.getOrNull(i + 1) ?: 0.0)
            if (d[i] > thr && isPeak) {
                val t = stats[i].timeUs
                if (cuts.isEmpty() || t - cuts.last() >= params.minSceneUs) cuts += t
            }
        }
        return cuts
    }

    /** Black / fade frames (useful to avoid unexpected black frames in the edit). */
    fun blackFrames(stats: List<FrameStats>, threshold: Double = 0.06) = stats.filter { it.brightness < threshold }.map { it.timeUs }
}

data class Highlight(val startUs: Long, val endUs: Long, val score: Double, val reasons: List<String>)

/** Scores "interesting moments" from video stats + optional audio loudness. */
object HighlightDetector {
    /**
     * @param audioDb loudness per [audioStepUs] (e.g. 50 ms RMS dB), may be empty.
     * @param onsetsUs musical/sound onsets, may be empty.
     */
    fun detect(stats: List<FrameStats>, audioDb: DoubleArray = DoubleArray(0), audioStepUs: Long = 50_000, onsetsUs: List<Long> = emptyList(),
               segmentUs: Long = 3_000_000, count: Int = 5, cutsUs: List<Long> = emptyList()): List<Highlight> {
        if (stats.isEmpty()) return emptyList()
        val dur = stats.last().timeUs + 1
        val step = max(250_000L, segmentUs / 4)
        val maxMotion = stats.maxOf { it.motion }.coerceAtLeast(1e-6)
        val maxSharp = stats.maxOf { it.sharpness }.coerceAtLeast(1e-9)
        val maxColor = stats.maxOf { it.colorfulness }.coerceAtLeast(1e-6)
        val audioMax = audioDb.maxOrNull() ?: -100.0
        val candidates = ArrayList<Highlight>()
        var s = 0L
        while (s + segmentUs / 2 <= dur) {
            val e = min(dur, s + segmentUs)
            val inside = stats.filter { it.timeUs in s until e }
            if (inside.isNotEmpty()) {
                val motion = inside.map { it.motion }.average() / maxMotion
                val sharp = inside.map { it.sharpness }.average() / maxSharp
                val color = inside.map { it.colorfulness }.average() / maxColor
                val bright = inside.map { it.brightness }.average()
                val exposure = 1 - abs(bright - 0.5) * 2
                var audio = 0.0
                if (audioDb.isNotEmpty()) {
                    val a = (s / audioStepUs).toInt(); val b = (e / audioStepUs).toInt().coerceAtMost(audioDb.size)
                    if (b > a) audio = ((audioDb.copyOfRange(a, b).average() - (audioMax - 40)) / 40).coerceIn(0.0, 1.0)
                }
                val onsetDensity = (onsetsUs.count { it in s until e } / (segmentUs / 1e6) / 4.0).coerceIn(0.0, 1.0)
                val cutPenalty = if (cutsUs.any { it in (s + 200_000) until (e - 200_000) }) 0.15 else 0.0
                val score = 0.32 * motion + 0.18 * sharp + 0.12 * color + 0.12 * exposure + 0.18 * audio + 0.08 * onsetDensity - cutPenalty
                val reasons = buildList {
                    if (motion > 0.6) add("Beaucoup d'action")
                    if (audio > 0.7) add("Son intense")
                    if (sharp > 0.7) add("Image nette")
                    if (color > 0.7) add("Couleurs vives")
                    if (onsetDensity > 0.6) add("Rythme soutenu")
                }
                candidates += Highlight(s, e, score, reasons)
            }
            s += step
        }
        // non-maximum suppression
        val picked = ArrayList<Highlight>()
        for (c in candidates.sortedByDescending { it.score }) {
            if (picked.none { it.startUs < c.endUs && c.startUs < it.endUs }) picked += c
            if (picked.size >= count) break
        }
        return picked.sortedBy { it.startUs }
    }
}

/** Saliency-based subject detection, automatic reframing and template tracking. */
object SubjectAnalysis {
    data class Box(val cx: Double, val cy: Double, val w: Double, val h: Double)

    /** Saliency map: centre-surround luma contrast + colour saturation + skin tones + centre bias. */
    fun saliency(f: Frame, prev: Frame? = null): FloatArray {
        val w = f.width; val h = f.height; val l = f.luma
        val blur = boxBlur(l, w, h, max(2, w / 12))
        val out = FloatArray(l.size)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val c = f.argb[i]
            val r = ((c shr 16) and 255) / 255f; val g = ((c shr 8) and 255) / 255f; val b = (c and 255) / 255f
            val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
            val sat = if (mx > 0) (mx - mn) / mx else 0f
            val skin = if (r > 0.35f && r > g && g > b && r - b > 0.12f && r - g < 0.35f) 0.6f else 0f
            val dx = (x - w / 2f) / w; val dy = (y - h / 2f) / h
            val centre = 1f - (dx * dx + dy * dy) * 1.6f
            var m = 0f
            if (prev != null && prev.width == w && prev.height == h) m = abs(l[i] - prev.luma[i]) * 3
            out[i] = (abs(l[i] - blur[i]) * 2.5f + sat * 0.35f + skin + m) * centre.coerceAtLeast(0.15f)
        }
        return out
    }

    fun boxBlur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(src.size); val out = FloatArray(src.size)
        for (y in 0 until h) { var acc = 0f; var n = 0
            for (x in -r until w + r) {
                val xa = x + r; if (xa < w) { acc += src[y * w + xa]; n++ }
                val xr = x - r - 1; if (xr >= 0) { acc -= src[y * w + xr]; n-- }
                if (x in 0 until w) tmp[y * w + x] = acc / n
            }
        }
        for (x in 0 until w) { var acc = 0f; var n = 0
            for (y in -r until h + r) {
                val ya = y + r; if (ya < h) { acc += tmp[ya * w + x]; n++ }
                val yr = y - r - 1; if (yr >= 0) { acc -= tmp[yr * w + x]; n-- }
                if (y in 0 until h) out[y * w + x] = acc / n
            }
        }
        return out
    }

    /** Weighted centroid and spread of the most salient region (normalised 0..1). */
    fun detectSubject(f: Frame, prev: Frame? = null): Box {
        val s = saliency(f, prev)
        val sorted = s.sortedArray()
        val thr = sorted[(sorted.size * 0.85).toInt().coerceAtMost(sorted.size - 1)]
        var sx = 0.0; var sy = 0.0; var sw = 0.0
        for (y in 0 until f.height) for (x in 0 until f.width) {
            val v = s[y * f.width + x]
            if (v >= thr) { sx += x * v; sy += y * v; sw += v }
        }
        if (sw <= 0) return Box(0.5, 0.5, 0.5, 0.5)
        val cx = sx / sw; val cy = sy / sw
        var vx = 0.0; var vy = 0.0
        for (y in 0 until f.height) for (x in 0 until f.width) {
            val v = s[y * f.width + x]
            if (v >= thr) { vx += (x - cx).pow(2) * v; vy += (y - cy).pow(2) * v }
        }
        val bw = (sqrt(vx / sw) * 4 / f.width).coerceIn(0.1, 1.0); val bh = (sqrt(vy / sw) * 4 / f.height).coerceIn(0.1, 1.0)
        return Box((cx + 0.5) / f.width, (cy + 0.5) / f.height, bw, bh)
    }

    data class ReframeKey(val timeUs: Long, val posX: Double, val posY: Double, val scale: Double)

    /**
     * Computes keyframes that keep the subject in frame when a [srcW]x[srcH] video is shown in a
     * [dstW]x[dstH] canvas in "fill" mode. posX/posY are the clip-centre canvas positions.
     */
    fun autoReframe(frames: List<Frame>, srcW: Int, srcH: Int, dstW: Int, dstH: Int, smoothing: Double = 0.85, maxKeys: Int = 40): List<ReframeKey> {
        if (frames.isEmpty()) return emptyList()
        val fill = max(dstW.toDouble() / srcW, dstH.toDouble() / srcH)
        val shownW = srcW * fill; val shownH = srcH * fill
        // allowed centre offsets so that the canvas stays covered
        val maxOffX = (shownW - dstW) / 2 / dstW; val maxOffY = (shownH - dstH) / 2 / dstH
        var sx = 0.5; var sy = 0.5
        val raw = frames.mapIndexed { i, f ->
            val b = detectSubject(f, frames.getOrNull(i - 1))
            if (i == 0) { sx = b.cx; sy = b.cy } else { sx = smoothing * sx + (1 - smoothing) * b.cx; sy = smoothing * sy + (1 - smoothing) * b.cy }
            // subject at normalised source position s -> canvas centre must move by (0.5 - s) * shown
            val posX = 0.5 + ((0.5 - sx) * shownW / dstW).coerceIn(-maxOffX, maxOffX)
            val posY = 0.5 + ((0.5 - sy) * shownH / dstH).coerceIn(-maxOffY, maxOffY)
            ReframeKey(f.timeUs, posX, posY, 1.0)
        }
        // decimate keyframes: keep points where the path deviates
        if (raw.size <= maxKeys) return raw
        val stepN = raw.size.toDouble() / maxKeys
        return (0 until maxKeys).map { raw[(it * stepN).toInt()] } + raw.last()
    }

    /**
     * Tracks a user-selected region with normalised cross-correlation on luma.
     * [box] is in normalised coordinates on the first frame. Returns the box per frame.
     */
    fun track(frames: List<Frame>, box: Box, searchRadius: Double = 0.15): List<Pair<Long, Box>> {
        if (frames.isEmpty()) return emptyList()
        val f0 = frames[0]
        val w = f0.width; val h = f0.height
        val tw = (box.w * w).toInt().coerceIn(4, w); val th = (box.h * h).toInt().coerceIn(4, h)
        var tx = ((box.cx * w) - tw / 2).toInt().coerceIn(0, w - tw); var ty = ((box.cy * h) - th / 2).toInt().coerceIn(0, h - th)
        var template = crop(f0.luma, w, tx, ty, tw, th)
        val out = ArrayList<Pair<Long, Box>>()
        out += f0.timeUs to box
        val rx = (searchRadius * w).toInt().coerceAtLeast(2); val ry = (searchRadius * h).toInt().coerceAtLeast(2)
        for (k in 1 until frames.size) {
            val f = frames[k]
            if (f.width != w || f.height != h) { out += f.timeUs to out.last().second; continue }
            var best = -2.0; var bx = tx; var by = ty
            for (dy in -ry..ry step 1) for (dx in -rx..rx step 1) {
                val x = tx + dx; val y = ty + dy
                if (x < 0 || y < 0 || x + tw > w || y + th > h) continue
                val v = ncc(template, f.luma, w, x, y, tw, th)
                if (v > best) { best = v; bx = x; by = y }
            }
            tx = bx; ty = by
            if (best > 0.6) {
                val cur = crop(f.luma, w, tx, ty, tw, th)
                for (i in template.indices) template[i] = template[i] * 0.85f + cur[i] * 0.15f
            }
            out += f.timeUs to Box((tx + tw / 2.0) / w, (ty + th / 2.0) / h, box.w, box.h)
        }
        return out
    }

    private fun crop(l: FloatArray, w: Int, x: Int, y: Int, cw: Int, ch: Int) = FloatArray(cw * ch) { i -> l[(y + i / cw) * w + x + i % cw] }

    private fun ncc(t: FloatArray, img: FloatArray, w: Int, x: Int, y: Int, tw: Int, th: Int): Double {
        var mt = 0.0; var mi = 0.0
        for (j in 0 until th) for (i in 0 until tw) { mt += t[j * tw + i]; mi += img[(y + j) * w + x + i] }
        val n = (tw * th).toDouble(); mt /= n; mi /= n
        var num = 0.0; var dt = 0.0; var di = 0.0
        for (j in 0 until th) for (i in 0 until tw) {
            val a = t[j * tw + i] - mt; val b = img[(y + j) * w + x + i] - mi
            num += a * b; dt += a * a; di += b * b
        }
        return if (dt * di <= 1e-12) 0.0 else num / sqrt(dt * di)
    }

    /**
     * Background removal for still images with a fairly uniform background: the background colour
     * model is learnt from the image border, then a flood fill from the borders removes connected
     * similar pixels; the mask is feathered. Returns alpha (0..1) per pixel.
     */
    fun removeBackground(argb: IntArray, w: Int, h: Int, tolerance: Double = 0.18, feather: Int = 2): FloatArray {
        val border = ArrayList<Int>()
        for (x in 0 until w) { border += argb[x]; border += argb[(h - 1) * w + x] }
        for (y in 0 until h) { border += argb[y * w]; border += argb[y * w + w - 1] }
        val centers = kMeans(border, 3)
        fun distBg(c: Int): Double {
            val r = ((c shr 16) and 255) / 255.0; val g = ((c shr 8) and 255) / 255.0; val b = (c and 255) / 255.0
            return centers.minOf { (cr, cg, cb) -> sqrt((r - cr).pow(2) + (g - cg).pow(2) + (b - cb).pow(2)) }
        }
        val bg = BooleanArray(w * h)
        val queue = ArrayDeque<Int>()
        fun push(i: Int) { if (!bg[i] && distBg(argb[i]) < tolerance) { bg[i] = true; queue.addLast(i) } }
        for (x in 0 until w) { push(x); push((h - 1) * w + x) }
        for (y in 0 until h) { push(y * w); push(y * w + w - 1) }
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst(); val x = i % w; val y = i / w
            if (x > 0) push(i - 1); if (x < w - 1) push(i + 1); if (y > 0) push(i - w); if (y < h - 1) push(i + w)
        }
        val alpha = FloatArray(w * h) { if (bg[it]) 0f else 1f }
        return if (feather > 0) boxBlur(alpha, w, h, feather) else alpha
    }

    private fun kMeans(pixels: List<Int>, k: Int): List<Triple<Double, Double, Double>> {
        val pts = pixels.map { Triple(((it shr 16) and 255) / 255.0, ((it shr 8) and 255) / 255.0, (it and 255) / 255.0) }
        if (pts.isEmpty()) return listOf(Triple(0.0, 0.0, 0.0))
        var c = (0 until k).map { pts[(it * pts.size / k).coerceAtMost(pts.size - 1)] }
        repeat(10) {
            val groups = pts.groupBy { p -> c.indices.minBy { j -> (p.first - c[j].first).pow(2) + (p.second - c[j].second).pow(2) + (p.third - c[j].third).pow(2) } }
            c = c.indices.map { j -> groups[j]?.let { g -> Triple(g.map { it.first }.average(), g.map { it.second }.average(), g.map { it.third }.average()) } ?: c[j] }
        }
        // ignore tiny clusters (e.g. subject touching the border)
        val counts = pts.groupingBy { p -> c.indices.minBy { j -> (p.first - c[j].first).pow(2) + (p.second - c[j].second).pow(2) + (p.third - c[j].third).pow(2) } }.eachCount()
        return c.filterIndexed { j, _ -> (counts[j] ?: 0) > pts.size * 0.12 }.ifEmpty { c }
    }
}
