package com.superessence.cuttinx.desktop.engine

import com.superessence.cuttinx.core.model.Container
import com.superessence.cuttinx.core.model.Project
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.Frame
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt

/** Output size of an export: the short side follows the export resolution, dimensions are even. */
fun exportSize(p: Project, shortSide: Int = p.export.resolutionShortSide): Pair<Int, Int> {
    val w = p.format.width; val h = p.format.height
    val k = shortSide.toDouble() / minOf(w, h)
    fun even(v: Double) = max(2, (v / 2).roundToInt() * 2)
    return even(w * k) to even(h * k)
}

/**
 * Renders a project to a video file with FFmpeg: H.264 (OpenH264 / Media Foundation, MPEG-4 as a
 * last resort) or VP9 for WebM, AAC / Opus sound.
 */
class PcExporter(private val text: PcText) {
    class Result(val file: File, val codec: String)

    fun export(p: Project, out: File, shortSide: Int, fps: Int, cancel: AtomicBoolean, onProgress: (Double) -> Unit): Result {
        val (w, h) = exportSize(p, shortSide)
        val total = p.durationUs
        require(total > 0) { "Le montage est vide" }
        out.parentFile?.mkdirs()
        val webm = p.export.container == Container.WEBM || out.extension.equals("webm", true)
        val pool = DecoderPool(max(w, h))
        val renderer = PcRenderer(pool, text)
        val audio = PcAudio(pool)
        val withAudio = p.export.includeAudio
        val (rec, codec) = openRecorder(out, w, h, fps, webm, withAudio, p)
        val info = ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.PREMUL)
        val surface = Surface.makeRaster(info)
        val bitmap = org.jetbrains.skia.Bitmap().apply { allocPixels(info) }
        val frame = Frame(w, h, Frame.DEPTH_UBYTE, 4)
        val pixels = ByteArray(w * h * 4)
        val frames = ((total * fps + 999_999) / 1_000_000).toInt()
        val aBuf = FloatArray(48_000 / max(1, fps) * 2 + 8192)
        var audioFrames = 0L
        try {
            for (n in 0 until frames) {
                if (cancel.get()) throw InterruptedException("Export annulé")
                val t = n * 1_000_000L / fps
                renderer.render(p, t, surface)
                surface.readPixels(bitmap, 0, 0)
                val bytes = bitmap.readPixels(info, w * 4, 0, 0) ?: error("lecture de l'image impossible")
                System.arraycopy(bytes, 0, pixels, 0, minOf(bytes.size, pixels.size))
                val bb = frame.image[0] as ByteBuffer
                bb.clear(); bb.put(pixels); bb.flip()
                rec.timestamp = t
                rec.record(frame, avutil.AV_PIX_FMT_BGRA)
                if (withAudio) {
                    val until = (n + 1).toLong() * 48_000 / fps
                    val count = (until - audioFrames).toInt()
                    if (count > 0) {
                        audio.mix(p, audioFrames * 1_000_000 / 48_000, count, aBuf)
                        rec.recordSamples(48_000, 2, FloatBuffer.wrap(aBuf, 0, count * 2))
                        audioFrames = until
                    }
                }
                if (n % 5 == 0) { onProgress(n.toDouble() / frames); pool.trim(4000) }
            }
            rec.stop()
            onProgress(1.0)
        } catch (e: Throwable) {
            runCatching { rec.stop() }
            out.delete()
            throw e
        } finally {
            runCatching { rec.release() }
            surface.close(); bitmap.close(); pool.close()
        }
        return Result(out, codec)
    }

    private fun openRecorder(out: File, w: Int, h: Int, fps: Int, webm: Boolean, audio: Boolean, p: Project): Pair<FFmpegFrameRecorder, String> {
        val bitrate = if (p.export.videoBitrate > 0) p.export.videoBitrate else (w.toLong() * h * fps * p.export.quality.bppf).toInt().coerceIn(1_000_000, 120_000_000)
        val candidates = if (webm) listOf("libvpx-vp9", "libvpx") else listOf("libx264", "libopenh264", "h264_mf", "mpeg4")
        var last: Throwable? = null
        for (name in candidates) {
            if (avcodec.avcodec_find_encoder_by_name(name) == null) continue
            val r = FFmpegFrameRecorder(out, w, h, if (audio) 2 else 0)
            try {
                r.format = if (webm) "webm" else if (out.extension.equals("mov", true)) "mov" else "mp4"
                r.videoCodecName = name
                if (name == "mpeg4") r.videoCodec = avcodec.AV_CODEC_ID_MPEG4
                r.pixelFormat = avutil.AV_PIX_FMT_YUV420P
                r.frameRate = fps.toDouble()
                r.gopSize = fps * 2
                r.videoBitrate = bitrate
                if (name == "libx264") { r.setVideoOption("preset", "medium"); r.setVideoOption("crf", "20") }
                if (name.startsWith("libvpx")) { r.setVideoOption("deadline", "good"); r.setVideoOption("cpu-used", "4") }
                if (!webm) r.setOption("movflags", "+faststart")
                if (audio) {
                    r.sampleRate = 48_000
                    r.audioBitrate = p.export.audioBitrate
                    if (webm) r.audioCodecName = "libopus" else r.audioCodec = avcodec.AV_CODEC_ID_AAC
                }
                r.start()
                return r to name
            } catch (e: Throwable) {
                last = e
                runCatching { r.release() }
                out.delete()
            }
        }
        throw IllegalStateException("Aucun encodeur vidéo disponible (${last?.message})")
    }
}
