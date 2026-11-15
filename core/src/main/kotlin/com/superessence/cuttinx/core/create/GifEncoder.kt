package com.superessence.cuttinx.core.create

import java.io.OutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Animated GIF (GIF89a) writer, original pure Kotlin code: median-cut colour reduction to 256
 * colours per frame (local palette, so every frame keeps its own colours), optional
 * Floyd–Steinberg dithering, LZW compression and a looping animation.
 *
 * Frames are ARGB_8888 pixels (as given by Android's Bitmap.getPixels), all [width]×[height].
 */
class GifEncoder(private val out: OutputStream, val width: Int, val height: Int, loops: Int = 0, private val dither: Boolean = true) {
    private var frames = 0
    private var closed = false

    init {
        require(width in 1..65535 && height in 1..65535) { "taille GIF invalide" }
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        short(width); short(height)
        out.write(0x70) // no global table, 8-bit colour resolution
        out.write(0); out.write(0)
        // NETSCAPE2.0 extension: loop count (0 = forever)
        out.write(byteArrayOf(0x21, 0xFF.toByte(), 11))
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(3, 1)); short(loops.coerceIn(0, 65535)); out.write(0)
    }

    val frameCount get() = frames

    /** Adds one frame shown for [delayCs] hundredths of a second (GIF unit; 2 = 50 i/s max). */
    fun addFrame(argb: IntArray, delayCs: Int) {
        check(!closed) { "GIF déjà terminé" }
        require(argb.size >= width * height) { "image trop petite" }
        val palette = Quantizer.medianCut(argb, width * height, 256)
        val indices = Quantizer.map(argb, width, height, palette, dither)
        // graphic control: delay, no transparency, disposal "leave in place"
        out.write(byteArrayOf(0x21, 0xF9.toByte(), 4, 0x04)); short(delayCs.coerceIn(2, 65535)); out.write(0); out.write(0)
        // image descriptor with a local colour table of 2^(n+1) entries
        val bits = max(1, 32 - Integer.numberOfLeadingZeros(max(1, palette.size - 1)))
        out.write(0x2C); short(0); short(0); short(width); short(height)
        out.write(0x80 or (bits - 1))
        for (i in 0 until (1 shl bits)) {
            val c = if (i < palette.size) palette[i] else 0
            out.write((c shr 16) and 0xFF); out.write((c shr 8) and 0xFF); out.write(c and 0xFF)
        }
        Lzw.write(out, indices, max(2, bits))
        frames++
    }

    /** Writes the trailer; the stream is left open. */
    fun finish() {
        if (closed) return
        out.write(0x3B); out.flush(); closed = true
    }

    private fun short(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
}

/** Colour reduction (median cut on a 15-bit histogram) and mapping with optional dithering. */
object Quantizer {
    private class Cube(val colors: IntArray, val from: Int, val to: Int, val counts: IntArray) {
        var rMin = 31; var rMax = 0; var gMin = 31; var gMax = 0; var bMin = 31; var bMax = 0; var pop = 0L
        init {
            for (i in from until to) {
                val c = colors[i]; val r = c shr 10 and 31; val g = c shr 5 and 31; val b = c and 31
                rMin = min(rMin, r); rMax = max(rMax, r); gMin = min(gMin, g); gMax = max(gMax, g); bMin = min(bMin, b); bMax = max(bMax, b)
                pop += counts[c]
            }
        }
        val size get() = to - from
        val longest get() = maxOf(rMax - rMin, gMax - gMin, bMax - bMin)
        fun average(): Int {
            var r = 0L; var g = 0L; var b = 0L; var n = 0L
            for (i in from until to) { val c = colors[i]; val k = counts[c].toLong(); r += (c shr 10 and 31) * k; g += (c shr 5 and 31) * k; b += (c and 31) * k; n += k }
            if (n == 0L) return 0
            fun to8(v: Long) = ((v.toDouble() / n) * 255.0 / 31.0).roundToInt().coerceIn(0, 255)
            return (to8(r) shl 16) or (to8(g) shl 8) or to8(b)
        }
    }

    private fun key15(argb: Int) = ((argb shr 19 and 31) shl 10) or ((argb shr 11 and 31) shl 5) or (argb shr 3 and 31)

    /** Up to [maxColors] RGB colours (0xRRGGBB) representing the first [n] pixels. */
    fun medianCut(argb: IntArray, n: Int, maxColors: Int): IntArray {
        val counts = IntArray(32768)
        val step = max(1, n / 120_000) // a subsample is plenty for the palette
        var i = 0
        while (i < n) { counts[key15(argb[i])]++; i += step }
        val distinct = counts.count { it > 0 }
        val colors = IntArray(distinct)
        var k = 0
        for (c in 0 until 32768) if (counts[c] > 0) colors[k++] = c
        if (distinct == 0) return intArrayOf(0)
        val cubes = ArrayList<Cube>().apply { add(Cube(colors, 0, distinct, counts)) }
        while (cubes.size < maxColors) {
            // split the most populated box that can still be split (ties: the widest)
            val idx = cubes.indices.filter { cubes[it].size > 1 }.maxWithOrNull(compareBy({ cubes[it].pop * (cubes[it].longest + 1) }, { cubes[it].longest })) ?: break
            val c = cubes.removeAt(idx)
            val shift = when (c.longest) { c.rMax - c.rMin -> 10; c.gMax - c.gMin -> 5; else -> 0 }
            val sorted = colors.copyOfRange(c.from, c.to).sortedBy { it shr shift and 31 }
            for (j in sorted.indices) colors[c.from + j] = sorted[j]
            // median by population
            val half = c.pop / 2
            var acc = 0L; var cut = c.from
            while (cut < c.to - 1) { acc += counts[colors[cut]]; cut++; if (acc >= half) break }
            cut = cut.coerceIn(c.from + 1, c.to - 1)
            cubes += Cube(colors, c.from, cut, counts); cubes += Cube(colors, cut, c.to, counts)
        }
        return IntArray(cubes.size) { cubes[it].average() }
    }

    /** Palette index of each pixel (nearest colour, cached per 15-bit colour), with Floyd–Steinberg error diffusion if [dither]. */
    fun map(argb: IntArray, w: Int, h: Int, palette: IntArray, dither: Boolean): ByteArray {
        val out = ByteArray(w * h)
        val cache = IntArray(32768) { -1 }
        fun nearest(r: Int, g: Int, b: Int): Int {
            val key = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
            val hit = cache[key]; if (hit >= 0) return hit
            var best = 0; var bd = Int.MAX_VALUE
            for (p in palette.indices) {
                val c = palette[p]
                val dr = (c shr 16 and 255) - r; val dg = (c shr 8 and 255) - g; val db = (c and 255) - b
                val d = 2 * dr * dr + 4 * dg * dg + 3 * db * db
                if (d < bd) { bd = d; best = p; if (d == 0) break }
            }
            cache[key] = best; return best
        }
        if (!dither) {
            for (i in 0 until w * h) { val c = argb[i]; out[i] = nearest(c shr 16 and 255, c shr 8 and 255, c and 255).toByte() }
            return out
        }
        // two rows of error (r, g, b) in 1/16 units
        var cur = IntArray((w + 2) * 3); var next = IntArray((w + 2) * 3)
        for (y in 0 until h) {
            java.util.Arrays.fill(next, 0)
            for (x in 0 until w) {
                val c = argb[y * w + x]; val e = (x + 1) * 3
                val r = ((c shr 16 and 255) + cur[e] / 16).coerceIn(0, 255)
                val g = ((c shr 8 and 255) + cur[e + 1] / 16).coerceIn(0, 255)
                val b = ((c and 255) + cur[e + 2] / 16).coerceIn(0, 255)
                val p = nearest(r, g, b); out[y * w + x] = p.toByte()
                val pc = palette[p]
                val er = r - (pc shr 16 and 255); val eg = g - (pc shr 8 and 255); val eb = b - (pc and 255)
                // damped (×0.75) so flat areas stay calm and the file small
                fun add(arr: IntArray, i: Int, k: Int) { arr[i] += er * k * 3 / 4; arr[i + 1] += eg * k * 3 / 4; arr[i + 2] += eb * k * 3 / 4 }
                add(cur, e + 3, 7); add(next, e - 3, 3); add(next, e, 5); add(next, e + 3, 1)
            }
            val t = cur; cur = next; next = t
        }
        return out
    }
}

/** GIF flavour of LZW (variable code size, clear code at 4096 entries) in 255-byte sub-blocks. */
internal object Lzw {
    fun write(out: OutputStream, data: ByteArray, minCodeSize: Int) {
        out.write(minCodeSize)
        val clear = 1 shl minCodeSize; val eoi = clear + 1
        val block = ByteArray(255); var blockLen = 0
        var bitBuf = 0L; var bitCount = 0
        var codeSize = minCodeSize + 1
        fun flushBlock() { if (blockLen > 0) { out.write(blockLen); out.write(block, 0, blockLen); blockLen = 0 } }
        fun emit(code: Int) {
            bitBuf = bitBuf or (code.toLong() shl bitCount); bitCount += codeSize
            while (bitCount >= 8) { block[blockLen++] = (bitBuf and 0xFF).toByte(); bitBuf = bitBuf ushr 8; bitCount -= 8; if (blockLen == 255) flushBlock() }
        }
        // dictionary: (prefix code << 8 | byte) -> code
        val dict = HashMap<Int, Int>(8192)
        var next = eoi + 1
        emit(clear)
        if (data.isEmpty()) { emit(eoi) } else {
            var prefix = data[0].toInt() and 0xFF
            for (i in 1 until data.size) {
                val k = data[i].toInt() and 0xFF
                val key = (prefix shl 8) or k
                val found = dict[key]
                if (found != null) { prefix = found; continue }
                emit(prefix)
                if (next < 4096) {
                    dict[key] = next++
                    if (next > (1 shl codeSize) && codeSize < 12) codeSize++
                } else {
                    emit(clear); dict.clear(); next = eoi + 1; codeSize = minCodeSize + 1
                }
                prefix = k
            }
            emit(prefix)
            // the decoder grows its code size one code later than us when the table fills a power of two
            if (next < 4096 && next >= (1 shl codeSize) && codeSize < 12) codeSize++
            emit(eoi)
        }
        if (bitCount > 0) { block[blockLen++] = (bitBuf and 0xFF).toByte(); if (blockLen == 255) flushBlock() }
        flushBlock()
        out.write(0)
    }
}

/** How a GIF plays its frames. */
enum class GifMode(val label: String, val detail: String) {
    NORMAL("Normal", "Lecture en boucle"),
    BOOMERANG("Boomerang", "Aller-retour sans fin"),
    REVERSE("Inversé", "Lu à l'envers"),
}

/** The timing maths of the GIF studio. */
object GifKit {
    /** Times (s) of the frames to grab from [startS, endS] at [fps]. */
    fun sampleTimes(startS: Double, endS: Double, fps: Int): List<Double> {
        val f = fps.coerceIn(1, 50)
        val d = (endS - startS).coerceAtLeast(0.0)
        val n = max(1, (d * f).roundToInt())
        return List(n) { startS + it / f.toDouble() }
    }

    /** Order in which the [n] grabbed frames are written (indices into the grabbed list). */
    fun order(n: Int, mode: GifMode): List<Int> = when {
        n <= 0 -> emptyList()
        mode == GifMode.NORMAL -> (0 until n).toList()
        mode == GifMode.REVERSE -> (n - 1 downTo 0).toList()
        // boomerang: forward then back, without repeating the two ends (smooth loop)
        else -> (0 until n).toList() + (n - 2 downTo 1).toList()
    }

    /** Delay of each frame in 1/100 s for [fps] grabbed frames played at [speed]× (GIF minimum: 2). */
    fun delayCs(fps: Int, speed: Double): Int = (100.0 / (fps.coerceIn(1, 50) * speed.coerceIn(0.1, 8.0))).roundToInt().coerceAtLeast(2)

    /** Output size with the long side at most [maxSide] (even numbers, never upscaled). */
    fun outputSize(w: Int, h: Int, maxSide: Int): Pair<Int, Int> {
        if (w <= 0 || h <= 0) return maxSide to maxSide
        val k = min(1.0, maxSide.toDouble() / max(w, h))
        fun even(v: Double) = max(2, (v / 2).roundToInt() * 2)
        return even(w * k) to even(h * k)
    }

    /** Rough size of the GIF in bytes (for the estimate shown before creating it). */
    fun estimateBytes(w: Int, h: Int, frames: Int, dither: Boolean): Long = (w.toLong() * h * frames * (if (dither) 0.55 else 0.35)).toLong() + 1024
}
