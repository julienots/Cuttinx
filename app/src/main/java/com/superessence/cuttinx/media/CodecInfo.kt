package com.superessence.cuttinx.media

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import com.superessence.cuttinx.core.model.VideoCodec
import com.superessence.cuttinx.core.project.EncoderCaps

data class CodecEntry(val name: String, val mime: String, val encoder: Boolean, val hardware: Boolean, val maxW: Int, val maxH: Int, val maxFps: Int)

/** Device codec capabilities, queried from MediaCodecList — never assumed. */
object CodecInfo {
    private val list by lazy { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.toList() }

    private fun isHw(info: MediaCodecInfo) = if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated else !info.name.lowercase().let { it.startsWith("omx.google") || it.startsWith("c2.android") }

    fun all(): List<CodecEntry> = list.flatMap { info ->
        info.supportedTypes.mapNotNull { mime ->
            val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: return@mapNotNull null
            val v = caps.videoCapabilities
            CodecEntry(info.name, mime, info.isEncoder, isHw(info), v?.supportedWidths?.upper ?: 0, v?.supportedHeights?.upper ?: 0, v?.supportedFrameRates?.upper ?: 0)
        }
    }

    fun canDecode(mime: String, w: Int, h: Int): Boolean = list.any { info ->
        !info.isEncoder && info.supportedTypes.any { it.equals(mime, true) } && runCatching {
            val v = info.getCapabilitiesForType(mime).videoCapabilities
            v == null || w <= 0 || v.isSizeSupported(w, h) || v.isSizeSupported(h, w)
        }.getOrDefault(false)
    }

    fun canDecodeAudio(mime: String): Boolean = list.any { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } }

    /** Video encoders usable by the export, with their real limits. */
    fun encoderCaps(): List<EncoderCaps> = VideoCodec.entries.mapNotNull { codec ->
        if (codec == VideoCodec.AV1 && Build.VERSION.SDK_INT < 34) return@mapNotNull null // muxer limitation
        val encs = list.filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(codec.mime, true) } }
        if (encs.isEmpty()) return@mapNotNull null
        val best = encs.sortedByDescending { if (isHw(it)) 1 else 0 }.first()
        val v = best.getCapabilitiesForType(codec.mime).videoCapabilities ?: return@mapNotNull null
        EncoderCaps(codec, v.supportedWidths.upper, v.supportedHeights.upper, v.supportedFrameRates.upper, v.bitrateRange.upper, isHw(best)) { w, h ->
            encs.any { e -> runCatching { e.getCapabilitiesForType(codec.mime).videoCapabilities.let { it.isSizeSupported(w, h) || it.isSizeSupported(h, w) } }.getOrDefault(false) }
        }
    }

    fun decoderSummary(): List<Pair<String, Boolean>> {
        val wanted = linkedMapOf("video/avc" to "H.264", "video/hevc" to "HEVC", "video/av01" to "AV1", "video/x-vnd.on2.vp9" to "VP9", "video/x-vnd.on2.vp8" to "VP8",
            "audio/mp4a-latm" to "AAC", "audio/mpeg" to "MP3", "audio/opus" to "Opus", "audio/vorbis" to "Vorbis", "audio/flac" to "FLAC", "audio/raw" to "WAV (PCM)")
        return wanted.map { (m, label) -> label to (m == "audio/raw" || list.any { !it.isEncoder && it.supportedTypes.any { t -> t.equals(m, true) } }) }
    }
}
