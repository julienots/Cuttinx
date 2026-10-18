package com.superessence.cuttinx.desktop.export

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.util.zip.CRC32

/**
 * Minimal APNG (animated PNG) writer: assembles already encoded PNG frames of identical size.
 * APNG keeps full transparency, loops forever and is read by OBS (media source / stinger) and by
 * every browser source — a light, dependency-free alternative to WebM with alpha.
 */
class ApngWriter(private val out: OutputStream, private val frameCount: Int, private val fps: Int, private val loops: Int = 0) {
    private var seq = 0
    private var index = 0
    private var headerWritten = false

    private class Chunk(val type: String, val data: ByteArray)

    private fun parse(png: ByteArray): List<Chunk> {
        require(png.size > 8 && png[1] == 'P'.code.toByte() && png[2] == 'N'.code.toByte()) { "PNG invalide" }
        val chunks = ArrayList<Chunk>()
        var p = 8
        while (p + 8 <= png.size) {
            val len = ((png[p].toInt() and 0xFF) shl 24) or ((png[p + 1].toInt() and 0xFF) shl 16) or ((png[p + 2].toInt() and 0xFF) shl 8) or (png[p + 3].toInt() and 0xFF)
            val type = String(png, p + 4, 4, Charsets.US_ASCII)
            chunks += Chunk(type, png.copyOfRange(p + 8, p + 8 + len))
            p += 12 + len
            if (type == "IEND") break
        }
        return chunks
    }

    private fun writeChunk(type: String, data: ByteArray) {
        val d = DataOutputStream(out)
        d.writeInt(data.size)
        val t = type.toByteArray(Charsets.US_ASCII)
        d.write(t); d.write(data)
        val crc = CRC32(); crc.update(t); crc.update(data)
        d.writeInt(crc.value.toInt())
    }

    private fun ints(vararg v: Int, shorts: IntArray = IntArray(0), bytes: IntArray = IntArray(0)): ByteArray {
        val b = ByteArrayOutputStream(); val d = DataOutputStream(b)
        v.forEach { d.writeInt(it) }; shorts.forEach { d.writeShort(it) }; bytes.forEach { d.writeByte(it) }
        return b.toByteArray()
    }

    /** Adds one frame (a complete PNG file of the animation size). */
    fun addFrame(png: ByteArray) {
        require(index < frameCount) { "Trop d'images" }
        val chunks = parse(png)
        val ihdr = chunks.first { it.type == "IHDR" }
        val w = java.nio.ByteBuffer.wrap(ihdr.data, 0, 4).int; val h = java.nio.ByteBuffer.wrap(ihdr.data, 4, 4).int
        if (!headerWritten) {
            out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A))
            writeChunk("IHDR", ihdr.data)
            writeChunk("acTL", ints(frameCount, loops))
            headerWritten = true
        }
        // fcTL: sequence, width, height, x, y, delay num/den, dispose = none, blend = source (each frame replaces the previous one)
        writeChunk("fcTL", ints(seq++, w, h, 0, 0, shorts = intArrayOf(1, fps), bytes = intArrayOf(0, 0)))
        for (c in chunks.filter { it.type == "IDAT" }) {
            if (index == 0) writeChunk("IDAT", c.data)
            else writeChunk("fdAT", ints(seq++) + c.data)
        }
        index++
    }

    fun finish() {
        check(index == frameCount) { "Animation incomplète ($index/$frameCount)" }
        writeChunk("IEND", ByteArray(0))
        out.flush()
    }
}
