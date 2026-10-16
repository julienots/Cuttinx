package com.superessence.cuttinx.core.project

import com.superessence.cuttinx.core.model.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Encoder capabilities reported by the device (MediaCodecList on Android). */
data class EncoderCaps(val codec: VideoCodec, val maxWidth: Int, val maxHeight: Int, val maxFps: Int, val maxBitrate: Int, val hardware: Boolean, val supportsSize: (Int, Int) -> Boolean = { w, h -> w <= maxWidth && h <= maxHeight || w <= maxHeight && h <= maxWidth })

data class ResolvedExport(
    val width: Int, val height: Int, val frameRate: Int, val videoBitrate: Int, val videoCodec: VideoCodec,
    val audioCodec: AudioCodec, val audioBitrate: Int, val container: Container, val warnings: List<String>,
    val estimatedBytes: Long,
)

object ExportPlanner {
    val resolutionPresets = listOf(480 to "480p", 720 to "720p", 1080 to "1080p", 1440 to "1440p (2K)", 2160 to "2160p (4K)")
    val frameRates = listOf(24, 25, 30, 50, 60)

    /** Output size for a short side, keeping the project aspect ratio, even dimensions. */
    fun outputSize(format: ProjectFormat, shortSide: Int): Pair<Int, Int> {
        val portrait = format.height > format.width
        val r = format.width.toDouble() / format.height
        return if (portrait) even(shortSide) to even((shortSide / r).roundToInt()) else even((shortSide * r).roundToInt()) to even(shortSide)
    }

    private fun even(v: Int) = max(2, v / 2 * 2)

    fun autoBitrate(w: Int, h: Int, fps: Int, q: ExportQuality, codec: VideoCodec): Int {
        val efficiency = when (codec) { VideoCodec.H264 -> 1.0; VideoCodec.HEVC -> 0.65; VideoCodec.AV1 -> 0.55; VideoCodec.VP9 -> 0.7 }
        return (w.toLong() * h * fps * q.bppf * efficiency).toLong().coerceIn(500_000, 150_000_000).toInt()
    }

    /**
     * Resolves user settings against the device capabilities. Never claims a resolution higher than
     * what will really be encoded: when the hardware can't do it the size is lowered and a warning
     * says so.
     */
    fun resolve(s: ExportSettings, format: ProjectFormat, durationUs: Long, caps: List<EncoderCaps>, maxSourceShortSide: Int? = null): ResolvedExport {
        val warnings = ArrayList<String>()
        var codec = s.videoCodec
        var cap = caps.firstOrNull { it.codec == codec }
        if (cap == null) {
            warnings += "${codec.label} n'est pas disponible sur cet appareil : H.264 utilisé."
            codec = VideoCodec.H264
            cap = caps.firstOrNull { it.codec == VideoCodec.H264 }
        }
        if (codec == VideoCodec.VP9) {
            warnings += "VP9 ne peut pas être écrit dans un MP4 : H.264 utilisé."
            codec = VideoCodec.H264; cap = caps.firstOrNull { it.codec == VideoCodec.H264 }
        }
        // The Android muxer used by CUTTINX writes MP4 only; never pretend otherwise.
        val container = Container.MP4
        if (s.container != Container.MP4) warnings += "${s.container.label} n'est pas pris en charge par le moteur d'export : MP4 utilisé."
        val audio = AudioCodec.AAC
        var (w, h) = outputSize(format, s.resolutionShortSide)
        if (maxSourceShortSide != null && s.resolutionShortSide > maxSourceShortSide * 1.05)
            warnings += "Vos médias sont en ${maxSourceShortSide}p : l'export en ${s.resolutionShortSide}p sera agrandi (pas de détail supplémentaire)."
        if (cap != null) {
            var short = s.resolutionShortSide
            while (!cap.supportsSize(w, h) && short > 240) {
                short = when { short > 2160 -> 2160; short > 1440 -> 1440; short > 1080 -> 1080; short > 720 -> 720; else -> 480 }.let { if (it == short) short - 120 else it }
                val sz = outputSize(format, short); w = sz.first; h = sz.second
            }
            if (short != s.resolutionShortSide) warnings += "L'encodeur ${codec.label} de cet appareil est limité à ${min(w, h)}p pour ce format : export en ${w}×$h."
        }
        var fps = s.frameRate
        if (cap != null && fps > cap.maxFps) { warnings += "Fréquence limitée à ${cap.maxFps} i/s par l'encodeur."; fps = cap.maxFps }
        var br = if (s.videoBitrate > 0) s.videoBitrate else autoBitrate(w, h, fps, s.quality, codec)
        if (cap != null && cap.maxBitrate > 0 && br > cap.maxBitrate) { br = cap.maxBitrate; warnings += "Débit limité à ${br / 1_000_000} Mb/s par l'encodeur." }
        val bytes = ((br.toLong() + s.audioBitrate) * durationUs / 8_000_000)
        return ResolvedExport(w, h, fps, br, codec, audio, s.audioBitrate, container, warnings, bytes)
    }
}
