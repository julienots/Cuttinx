package com.superessence.cuttinx.core.create

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A rectangle in pixels. */
data class Box(val x: Int, val y: Int, val w: Int, val h: Int)

/** Output formats of the photo studios. */
enum class PhotoFrame(val label: String, val w: Int, val h: Int) {
    ORIGINAL("Original", 0, 0), SQUARE("1:1", 1, 1), PORTRAIT("4:5", 4, 5), STORY("9:16", 9, 16), WIDE("16:9", 16, 9), CLASSIC("3:2", 3, 2),
    PORTRAIT_34("3:4", 3, 4), PANORAMA("2:1", 2, 1), CINEMA("21:9", 21, 9);
    /** Width / height, or null for "keep the picture's own ratio". */
    val ratio: Double? get() = if (w == 0) null else w.toDouble() / h
}

/** Collage layouts. */
enum class CollageStyle(val label: String) { GRID("Grille"), MOSAIC("Mosaïque"), STRIP("Bande"), HERO("Une grande"), COLUMNS("Colonnes"), PYRAMID("Pyramide"), SIDE_HERO("Grande à gauche") }

/**
 * The layout maths of the photo studios (collage, carousel, crop) and of the audio studio
 * (waveform). Pure functions: the apps only draw.
 */
object CreateKit {
    const val VERSION = "3.3"

    // ------------------------------------------------------------------ crop

    /** Largest centred crop of a w×h picture with the [frame] ratio (the whole picture for ORIGINAL). */
    fun centerCrop(w: Int, h: Int, frame: PhotoFrame): Box {
        val r = frame.ratio ?: return Box(0, 0, w, h)
        return if (w.toDouble() / h > r) { val cw = (h * r).roundToInt().coerceIn(1, w); Box((w - cw) / 2, 0, cw, h) }
        else { val ch = (w / r).roundToInt().coerceIn(1, h); Box(0, (h - ch) / 2, w, ch) }
    }

    /** Output size for a long side of [longSide] px with the [frame] ratio (or the source ratio). */
    fun outputSize(srcW: Int, srcH: Int, frame: PhotoFrame, longSide: Int): Pair<Int, Int> {
        val r = frame.ratio ?: (srcW.toDouble() / max(1, srcH))
        return if (r >= 1) longSide to max(1, (longSide / r).roundToInt()) else max(1, (longSide * r).roundToInt()) to longSide
    }

    // ------------------------------------------------------------------ collage

    /**
     * Cells of a collage of [n] photos on a [w]×[h] canvas with [gap] px between cells and around
     * them. GRID: balanced rows; MOSAIC: alternating big / small rows; STRIP: one column (portrait)
     * or one row (landscape); HERO: the first photo takes the top half.
     */
    fun collage(n: Int, w: Int, h: Int, gap: Int, style: CollageStyle): List<Box> {
        if (n <= 0) return emptyList()
        val g = gap.coerceAtLeast(0)
        fun rows(counts: List<Int>, top: Int, height: Int, weights: List<Double> = counts.map { 1.0 }): List<Box> {
            val out = ArrayList<Box>()
            val total = weights.sum()
            var y = top + g
            val avail = height - g * (counts.size + 1)
            counts.forEachIndexed { r, c ->
                val rh = if (r == counts.lastIndex) top + height - g - y else (avail * weights[r] / total).roundToInt()
                val cw = (w - g * (c + 1)) / c.toDouble()
                for (i in 0 until c) {
                    val x = (g + i * (cw + g)).roundToInt()
                    val x2 = if (i == c - 1) w - g else (g + i * (cw + g) + cw).roundToInt()
                    out += Box(x, y, x2 - x, rh)
                }
                y += rh + g
            }
            return out
        }
        return when (style) {
            CollageStyle.STRIP -> if (h >= w) rows(List(n) { 1 }, 0, h) else {
                val cw = (w - g * (n + 1)) / n.toDouble()
                List(n) { i -> val x = (g + i * (cw + g)).roundToInt(); val x2 = if (i == n - 1) w - g else (g + i * (cw + g) + cw).roundToInt(); Box(x, g, x2 - x, h - 2 * g) }
            }
            CollageStyle.HERO -> if (n == 1) rows(listOf(1), 0, h) else {
                val rest = n - 1
                val restRows = splitRows(rest, maxPerRow = 3)
                rows(listOf(1) + restRows, 0, h, listOf(restRows.size.toDouble().coerceAtLeast(1.0) * 1.2) + restRows.map { 1.0 })
            }
            CollageStyle.MOSAIC -> {
                val counts = ArrayList<Int>(); var left = n; var big = true
                while (left > 0) { val c = min(left, if (big) 1 else 2).let { if (left == 1) 1 else it }; counts += c; left -= c; big = !big }
                rows(counts, 0, h, counts.map { if (it == 1) 1.4 else 1.0 })
            }
            CollageStyle.GRID -> rows(splitRows(n, maxPerRow = ceil(sqrt(n.toDouble())).toInt()), 0, h)
            // 3.3: the same layouts turned a quarter (columns), growing rows, a big photo on the left
            CollageStyle.COLUMNS -> transpose(collage(n, h, w, gap, CollageStyle.GRID))
            CollageStyle.PYRAMID -> {
                val counts = ArrayList<Int>(); var left = n; var k = 1
                while (left > 0) { val c = min(k, left); counts += c; left -= c; k++ }
                rows(counts, 0, h)
            }
            CollageStyle.SIDE_HERO -> transpose(collage(n, h, w, gap, CollageStyle.HERO))
        }
    }

    /** Boxes of a layout computed on the swapped canvas (h×w), swapped back: rows become columns. */
    private fun transpose(boxes: List<Box>): List<Box> = boxes.map { Box(it.y, it.x, it.h, it.w) }

    /** n items in rows of at most [maxPerRow], as even as possible (e.g. 5 → 2, 3). */
    fun splitRows(n: Int, maxPerRow: Int): List<Int> {
        if (n <= 0) return emptyList()
        val per = maxPerRow.coerceAtLeast(1)
        val rows = ceil(n / per.toDouble()).toInt()
        val base = n / rows; val extra = n % rows
        return List(rows) { r -> base + if (r >= rows - extra) 1 else 0 }
    }

    /** Part of a w×h picture shown in a cell of cw×ch (centred "cover"). */
    fun cover(w: Int, h: Int, cw: Int, ch: Int): Box {
        val r = cw.toDouble() / max(1, ch)
        return if (w.toDouble() / h > r) { val sw = (h * r).roundToInt().coerceIn(1, w); Box((w - sw) / 2, 0, sw, h) }
        else { val sh = (w / r).roundToInt().coerceIn(1, h); Box(0, (h - sh) / 2, w, sh) }
    }

    // ------------------------------------------------------------------ carousel

    /**
     * Instagram carousel: [slides] side-by-side parts of one panorama of w×h, each with the
     * [frame] ratio. The panorama is centre-cropped to exactly slides × ratio first, so the slides
     * join seamlessly when swiped.
     */
    fun carousel(w: Int, h: Int, slides: Int, frame: PhotoFrame = PhotoFrame.PORTRAIT): List<Box> {
        val n = slides.coerceIn(2, 10)
        val r = (frame.ratio ?: 0.8) * n
        val area = if (w.toDouble() / h > r) { val cw = (h * r).roundToInt().coerceIn(1, w); Box((w - cw) / 2, 0, cw, h) }
            else { val ch = (w / r).roundToInt().coerceIn(1, h); Box(0, (h - ch) / 2, w, ch) }
        val sw = area.w / n.toDouble()
        return List(n) { i -> val x = (area.x + i * sw).roundToInt(); val x2 = if (i == n - 1) area.x + area.w else (area.x + (i + 1) * sw).roundToInt(); Box(x, area.y, x2 - x, area.h) }
    }

    // ------------------------------------------------------------------ audio

    /** Peak of each of [bars] equal parts of a mono or interleaved buffer, 0..1. */
    fun waveform(samples: FloatArray, channels: Int, bars: Int): FloatArray {
        val frames = samples.size / max(1, channels)
        if (frames == 0 || bars <= 0) return FloatArray(max(0, bars))
        val out = FloatArray(bars)
        for (b in 0 until bars) {
            val s = (b.toLong() * frames / bars).toInt(); val e = max(s + 1, ((b + 1).toLong() * frames / bars).toInt())
            var m = 0f
            var i = s * channels
            val end = min(e * channels, samples.size)
            val step = max(1, (end - i) / 512)
            while (i < end) { val v = kotlin.math.abs(samples[i]); if (v > m) m = v; i += step }
            out[b] = m.coerceIn(0f, 1f)
        }
        return out
    }

    /** Linear fades (in place) over the first / last [fadeInS] / [fadeOutS] seconds of an interleaved buffer. */
    fun fades(samples: FloatArray, sampleRate: Int, channels: Int, fadeInS: Double, fadeOutS: Double) {
        val frames = samples.size / max(1, channels)
        val fi = (fadeInS * sampleRate).toInt().coerceIn(0, frames); val fo = (fadeOutS * sampleRate).toInt().coerceIn(0, frames)
        for (f in 0 until fi) { val g = f / fi.toFloat(); for (c in 0 until channels) samples[f * channels + c] *= g }
        for (f in 0 until fo) { val g = f / fo.toFloat(); val idx = frames - 1 - f; for (c in 0 until channels) samples[idx * channels + c] *= g }
    }

    /** The part [startS, endS] of an interleaved buffer. */
    fun trim(samples: FloatArray, sampleRate: Int, channels: Int, startS: Double, endS: Double): FloatArray {
        val frames = samples.size / max(1, channels)
        val a = (startS * sampleRate).toInt().coerceIn(0, frames); val b = (endS * sampleRate).toInt().coerceIn(a, frames)
        return samples.copyOfRange(a * channels, b * channels)
    }
}
