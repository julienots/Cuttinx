package com.superessence.cuttinx.desktop.engine

import com.superessence.cuttinx.core.overlay.export.VideoSink
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.Frame
import java.io.File
import java.nio.ByteBuffer

/**
 * Transparent WebM (VP9 + alpha) for OBS and Streamlabs: media sources, alert boxes and Stinger
 * transitions keep their animation and their transparency.
 */
object WebmAlpha : VideoSink {
    override fun write(file: File, width: Int, height: Int, fps: Int, frames: Sequence<IntArray>) {
        file.parentFile?.mkdirs()
        // VP9 needs even sizes
        val w = width / 2 * 2; val h = height / 2 * 2
        val r = FFmpegFrameRecorder(file, w, h, 0)
        r.format = "webm"
        r.videoCodecName = "libvpx-vp9"
        r.pixelFormat = avutil.AV_PIX_FMT_YUVA420P
        r.frameRate = fps.toDouble()
        r.setVideoOption("crf", "28")
        r.setVideoOption("b:v", "0")
        r.setVideoOption("deadline", "good")
        r.setVideoOption("cpu-used", "4")
        r.setVideoOption("row-mt", "1")
        // the alpha layer is lost with alternate reference frames
        r.setVideoOption("auto-alt-ref", "0")
        r.start()
        try {
            val frame = Frame(w, h, Frame.DEPTH_UBYTE, 4)
            val buf = frame.image[0] as ByteBuffer
            val stride = frame.imageStride
            for (px in frames) {
                for (y in 0 until h) {
                    var o = y * stride
                    var i = y * width
                    for (x in 0 until w) {
                        val c = px[i++]
                        buf.put(o, (c and 255).toByte()); buf.put(o + 1, ((c shr 8) and 255).toByte())
                        buf.put(o + 2, ((c shr 16) and 255).toByte()); buf.put(o + 3, ((c ushr 24) and 255).toByte())
                        o += 4
                    }
                }
                r.record(frame, avutil.AV_PIX_FMT_BGRA)
            }
        } finally {
            r.stop(); r.release()
        }
    }
}
