package com.superessence.cuttinx.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/** Decoded PCM (interleaved floats). */
class Pcm(val samples: FloatArray, val sampleRate: Int, val channels: Int) {
    val durationUs: Long get() = if (sampleRate == 0 || channels == 0) 0 else samples.size.toLong() / channels * 1_000_000 / sampleRate
    fun mono(): Pcm = if (channels == 1) this else Pcm(com.superessence.cuttinx.core.audio.AudioAnalysis.toMono(samples, channels), sampleRate, 1)
}

/** Decodes the audio track of a file to float PCM with MediaCodec (used by waveforms and AI tools). */
object PcmDecoder {
    /**
     * @param startUs/endUs source range; decoding stops at [endUs] or after [maxSeconds].
     * @param downmix average channels to mono.
     */
    fun decode(context: Context, uri: Uri, startUs: Long = 0, endUs: Long = Long.MAX_VALUE, downmix: Boolean = false, maxSeconds: Double = 3600.0, cancelled: () -> Boolean = { false }): Pcm {
        val ex = MediaExtractor()
        ex.setDataSource(context, uri, null)
        val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ?: run { ex.release(); return Pcm(FloatArray(0), 48000, 1) }
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val mime = fmt.getString(MediaFormat.KEY_MIME)!!
        var sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        if (startUs > 0) ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(fmt, null, null, 0)
        codec.start()
        val out = FloatArrayBuilder()
        val info = MediaCodec.BufferInfo()
        var inputDone = false; var outputDone = false
        var pcmFloat = false
        val maxFrames = (maxSeconds * sr).toLong()
        try {
            while (!outputDone && !cancelled()) {
                if (!inputDone) {
                    val ib = codec.dequeueInputBuffer(10_000)
                    if (ib >= 0) {
                        val buf = codec.getInputBuffer(ib)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0 || ex.sampleTime > endUs) { codec.queueInputBuffer(ib, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { codec.queueInputBuffer(ib, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val ob = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    ob == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        sr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) && of.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                    }
                    ob >= 0 -> {
                        val buf = codec.getOutputBuffer(ob)!!.order(ByteOrder.nativeOrder())
                        if (info.size > 0 && info.presentationTimeUs + 1 >= startUs) {
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            // skip samples before start in the first buffer
                            val skipFrames = if (info.presentationTimeUs < startUs) ((startUs - info.presentationTimeUs) * sr / 1_000_000).toInt() else 0
                            if (pcmFloat) {
                                val fb = buf.asFloatBuffer(); val n = fb.remaining()
                                var i = skipFrames * ch
                                while (i + ch <= n) { if (downmix) { var s = 0f; for (c in 0 until ch) s += fb.get(i + c); out.add(s / ch) } else for (c in 0 until ch) out.add(fb.get(i + c)); i += ch }
                            } else {
                                val sb = buf.asShortBuffer(); val n = sb.remaining()
                                var i = skipFrames * ch
                                while (i + ch <= n) { if (downmix) { var s = 0f; for (c in 0 until ch) s += sb.get(i + c); out.add(s / ch / 32768f) } else for (c in 0 until ch) out.add(sb.get(i + c) / 32768f); i += ch }
                            }
                        }
                        codec.releaseOutputBuffer(ob, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        if (out.size / (if (downmix) 1 else ch) >= maxFrames) outputDone = true
                        if (info.presentationTimeUs > endUs) outputDone = true
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }; codec.release(); ex.release()
        }
        return Pcm(out.toArray(), sr, if (downmix) 1 else ch)
    }

    private class FloatArrayBuilder {
        private var a = FloatArray(1 shl 16); var size = 0; private set
        fun add(v: Float) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
        fun toArray() = a.copyOf(size)
    }
}

/** Minimal 16-bit PCM WAV writer for processed audio (denoise, separation, generated sounds). */
object WavWriter {
    fun write(file: java.io.File, samples: FloatArray, sampleRate: Int, channels: Int) {
        val dataLen = samples.size * 2
        java.io.DataOutputStream(java.io.BufferedOutputStream(java.io.FileOutputStream(file))).use { o ->
            fun i32(v: Int) { o.write(v and 0xFF); o.write((v shr 8) and 0xFF); o.write((v shr 16) and 0xFF); o.write((v shr 24) and 0xFF) }
            fun i16(v: Int) { o.write(v and 0xFF); o.write((v shr 8) and 0xFF) }
            o.writeBytes("RIFF"); i32(36 + dataLen); o.writeBytes("WAVE")
            o.writeBytes("fmt "); i32(16); i16(1); i16(channels); i32(sampleRate); i32(sampleRate * channels * 2); i16(channels * 2); i16(16)
            o.writeBytes("data"); i32(dataLen)
            for (s in samples) i16((s.coerceIn(-1f, 1f) * 32767).toInt())
        }
    }
}
