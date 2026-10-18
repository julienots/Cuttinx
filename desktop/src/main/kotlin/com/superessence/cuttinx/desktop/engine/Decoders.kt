package com.superessence.cuttinx.desktop.engine

import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Frame
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.io.File
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * Sequential video decoder of one clip. Reading forward (playback, export) decodes frame after
 * frame; a jump backwards or far ahead seeks precisely. Frames are scaled by FFmpeg to at most
 * [maxSide] pixels so that 4K files stay smooth in the preview.
 */
class VideoDecoder(val file: File, private val maxSide: Int) : AutoCloseable {
    private var g: FFmpegFrameGrabber? = null
    private var cur: Image? = null
    private var curTs = Long.MIN_VALUE
    private var nextTs = Long.MIN_VALUE
    private var frameUs = 33_333L
    private var bytes = ByteArray(0)
    var failed: String? = null; private set
    var lastUse = System.nanoTime(); private set

    private fun open(): FFmpegFrameGrabber? {
        g?.let { return it }
        if (failed != null) return null
        return runCatching {
            val probe = FFmpegFrameGrabber(file).also { it.start() }
            val w = probe.imageWidth; val h = probe.imageHeight
            probe.stop(); probe.release()
            val s = minOf(1.0, maxSide.toDouble() / maxOf(w, h, 1))
            FFmpegFrameGrabber(file).apply {
                pixelFormat = avutil.AV_PIX_FMT_BGRA
                if (s < 1.0) { imageWidth = (w * s).toInt() / 2 * 2; imageHeight = (h * s).toInt() / 2 * 2 }
                setOption("threads", "auto")
                start()
                if (frameRate > 1) frameUs = (1_000_000 / frameRate).toLong()
            }
        }.onFailure { failed = it.message ?: it.javaClass.simpleName }.getOrNull()?.also { g = it }
    }

    /** Frame shown at [sourceUs] (position in the file), or the closest one available. */
    @Synchronized fun frameAt(sourceUs: Long): Image? {
        lastUse = System.nanoTime()
        val gr = open() ?: return null
        val t = sourceUs.coerceAtLeast(0)
        if (cur != null && t >= curTs && t < nextTs) return cur
        try {
            if (cur == null || t < curTs || t > curTs + 2_000_000) {
                gr.setVideoTimestamp(t)
                curTs = Long.MIN_VALUE
            }
            var guard = 0
            while (guard++ < 600) {
                val f = gr.grabImage() ?: break
                val ts = gr.timestamp
                store(f, ts)
                if (ts + frameUs > t) break
            }
        } catch (e: Exception) {
            if (cur == null) failed = e.message ?: "décodage impossible"
        }
        return cur
    }

    private fun store(f: Frame, ts: Long) {
        val buf = f.image?.get(0) as? ByteBuffer ?: return
        val stride = f.imageStride
        val size = stride * f.imageHeight
        if (bytes.size != size) bytes = ByteArray(size)
        buf.position(0); buf.get(bytes, 0, minOf(size, buf.remaining()))
        cur?.close()
        cur = Image.makeRaster(ImageInfo(f.imageWidth, f.imageHeight, ColorType.BGRA_8888, ColorAlphaType.OPAQUE), bytes, stride)
        curTs = ts; nextTs = ts + frameUs
    }

    @Synchronized override fun close() {
        runCatching { g?.stop(); g?.release() }
        g = null; cur?.close(); cur = null
    }
}

/** Sequential audio decoder: interleaved stereo float samples at [rate] Hz. */
class AudioDecoder(val file: File, private val rate: Int) : AutoCloseable {
    private var g: FFmpegFrameGrabber? = null
    private var pending = FloatArray(0)
    private var pendingStart = 0
    private var pendingLen = 0
    /** Source position (in frames at [rate]) of the next sample returned by [read]. */
    private var posFrames = -1L
    var failed = false; private set
    var lastUse = System.nanoTime(); private set

    private fun open(): FFmpegFrameGrabber? {
        g?.let { return it }
        if (failed) return null
        return runCatching {
            FFmpegFrameGrabber(file).apply {
                sampleRate = rate; audioChannels = 2; sampleFormat = avutil.AV_SAMPLE_FMT_FLT
                start()
                if (!hasAudio()) error("pas de son")
            }
        }.onFailure { failed = true }.getOrNull()?.also { g = it }
    }

    /**
     * Reads [frames] stereo frames starting at source time [sourceUs] into [out] (2 floats per
     * frame). Returns false when nothing could be read (end of file, no audio).
     */
    @Synchronized fun read(sourceUs: Long, frames: Int, out: FloatArray): Boolean {
        lastUse = System.nanoTime()
        val gr = open() ?: return false
        val want = sourceUs * rate / 1_000_000
        if (posFrames < 0 || kotlin.math.abs(want - posFrames) > rate / 20) {
            runCatching { gr.setAudioTimestamp(sourceUs.coerceAtLeast(0)) }
            pendingLen = 0; posFrames = want
        }
        var written = 0
        while (written < frames) {
            if (pendingLen == 0 && !fill(gr)) break
            val n = minOf(frames - written, pendingLen / 2)
            System.arraycopy(pending, pendingStart, out, written * 2, n * 2)
            pendingStart += n * 2; pendingLen -= n * 2; written += n
        }
        if (written < frames) java.util.Arrays.fill(out, written * 2, frames * 2, 0f)
        posFrames += frames
        return written > 0
    }

    private fun fill(gr: FFmpegFrameGrabber): Boolean {
        val f = runCatching { gr.grabSamples() }.getOrNull() ?: return false
        val s = f.samples ?: return true
        val b = s[0]
        val n: Int
        when (b) {
            is FloatBuffer -> { n = b.remaining(); if (pending.size < n) pending = FloatArray(n); b.get(pending, 0, n) }
            is ShortBuffer -> { n = b.remaining(); if (pending.size < n) pending = FloatArray(n); for (i in 0 until n) pending[i] = b.get() / 32768f }
            else -> return true
        }
        pendingStart = 0; pendingLen = n - n % 2
        return true
    }

    @Synchronized override fun close() { runCatching { g?.stop(); g?.release() }; g = null }
}

/** Decoders shared by the preview and the export, keyed by clip (one sequential reader per clip). */
class DecoderPool(private val maxSide: Int, private val rate: Int = 48_000) : AutoCloseable {
    private val videos = HashMap<String, VideoDecoder>()
    private val audios = HashMap<String, AudioDecoder>()

    @Synchronized fun video(key: String, file: File): VideoDecoder =
        videos[key]?.takeIf { it.file == file } ?: VideoDecoder(file, maxSide).also { videos.put(key, it)?.close() }

    @Synchronized fun audio(key: String, file: File): AudioDecoder =
        audios[key]?.takeIf { it.file == file } ?: AudioDecoder(file, rate).also { audios.put(key, it)?.close() }

    /** Closes decoders unused for [idleMs] (clips far from the playhead). */
    @Synchronized fun trim(idleMs: Long = 8000) {
        val limit = System.nanoTime() - idleMs * 1_000_000
        videos.entries.removeIf { (_, d) -> (d.lastUse < limit).also { if (it) d.close() } }
        audios.entries.removeIf { (_, d) -> (d.lastUse < limit).also { if (it) d.close() } }
    }

    @Synchronized override fun close() { videos.values.forEach { it.close() }; audios.values.forEach { it.close() }; videos.clear(); audios.clear() }
}
