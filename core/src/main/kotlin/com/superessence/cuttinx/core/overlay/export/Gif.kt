package com.superessence.cuttinx.core.overlay.export

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Animated GIF writer with transparency. GIFs play everywhere (file explorers, phones, OBS
 * « Image » sources, Streamlabs alert boxes…), so the streamer immediately sees the animation.
 * The palette (255 colours + transparent) is built from sample frames given to [palette].
 */
class GifWriter(private val out: OutputStream, private val width: Int, private val height: Int, fps: Int) {
    private val delay = (100.0 / fps.coerceIn(1, 50)).toInt().coerceAtLeast(2)
    private var colors = IntArray(0)
    /** 12-bit colour (4 bits per channel) → palette index. */
    private val lut = ByteArray(4096)
    private var started = false

    /** Builds the palette from a few frames (most used colours). Call once, before [addFrame]. */
    fun palette(samples: List<IntArray>) {
        val hist = IntArray(4096)
        for (px in samples) { var i = 0; while (i < px.size) { val c = px[i]; if ((c ushr 24) >= 128) hist[key(c)]++; i += 2 } }
        val used = (0 until 4096).filter { hist[it] > 0 }.sortedByDescending { hist[it] }
        // keep the 255 most used, but make sure dark / light extremes survive
        colors = used.take(255).map { k -> val r = (k shr 8) and 15; val g = (k shr 4) and 15; val b = k and 15; (r * 17 shl 16) or (g * 17 shl 8) or (b * 17) }.toIntArray()
        if (colors.isEmpty()) colors = intArrayOf(0)
        for (k in 0 until 4096) {
            val r = ((k shr 8) and 15) * 17; val g = ((k shr 4) and 15) * 17; val b = (k and 15) * 17
            var best = 0; var bd = Int.MAX_VALUE
            for (j in colors.indices) {
                val c = colors[j]; val dr = r - ((c shr 16) and 255); val dg = g - ((c shr 8) and 255); val db = b - (c and 255)
                val d = 2 * dr * dr + 4 * dg * dg + 3 * db * db
                if (d < bd) { bd = d; best = j }
            }
            lut[k] = (best + 1).toByte() // index 0 = transparent
        }
    }

    private fun key(c: Int) = (((c shr 20) and 15) shl 8) or (((c shr 12) and 15) shl 4) or ((c shr 4) and 15)

    private fun header() {
        out.write("GIF89a".toByteArray())
        short(width); short(height)
        out.write(0xF7); out.write(0); out.write(0) // global table of 256 colours
        for (i in 0 until 256) {
            val c = if (i == 0 || i - 1 >= colors.size) 0 else colors[i - 1]
            out.write((c shr 16) and 255); out.write((c shr 8) and 255); out.write(c and 255)
        }
        // loop forever
        out.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B)); out.write("NETSCAPE2.0".toByteArray()); out.write(byteArrayOf(3, 1, 0, 0, 0))
        started = true
    }

    fun addFrame(argb: IntArray) {
        if (!started) { if (colors.isEmpty()) palette(listOf(argb)); header() }
        // graphic control: restore to background (transparent) between frames, index 0 transparent
        out.write(byteArrayOf(0x21, 0xF9.toByte(), 4, 0x09)); short(delay); out.write(0); out.write(0)
        out.write(0x2C); short(0); short(0); short(width); short(height); out.write(0)
        val idx = ByteArray(argb.size) { i -> val c = argb[i]; if ((c ushr 24) < 128) 0 else lut[key(c)] }
        lzw(idx)
    }

    fun finish() { if (!started) header(); out.write(0x3B); out.flush() }

    private fun short(v: Int) { out.write(v and 255); out.write((v shr 8) and 255) }

    /** Standard GIF LZW with 8-bit codes, written in 255-byte sub-blocks. */
    private fun lzw(data: ByteArray) {
        val minSize = 8
        out.write(minSize)
        val clear = 1 shl minSize; val eoi = clear + 1
        val block = ByteArrayOutputStream()
        var bits = 0L; var nbits = 0
        var codeSize = minSize + 1
        fun emit(code: Int) {
            bits = bits or (code.toLong() shl nbits); nbits += codeSize
            while (nbits >= 8) { block.write((bits and 255).toInt()); bits = bits ushr 8; nbits -= 8 }
        }
        val dict = HashMap<Int, Int>(8192)
        var next = eoi + 1
        emit(clear)
        var prefix = -1
        for (b in data) {
            val c = b.toInt() and 255
            if (prefix < 0) { prefix = c; continue }
            val k = (prefix shl 8) or c
            val found = dict[k]
            if (found != null) { prefix = found; continue }
            emit(prefix)
            if (next < 4096) {
                dict[k] = next++
                if (next > (1 shl codeSize) && codeSize < 12) codeSize++
            } else { emit(clear); dict.clear(); next = eoi + 1; codeSize = minSize + 1 }
            prefix = c
        }
        if (prefix >= 0) emit(prefix)
        emit(eoi)
        if (nbits > 0) block.write((bits and 255).toInt())
        val bytes = block.toByteArray()
        var o = 0
        while (o < bytes.size) { val n = minOf(255, bytes.size - o); out.write(n); out.write(bytes, o, n); o += n }
        out.write(0)
    }
}
