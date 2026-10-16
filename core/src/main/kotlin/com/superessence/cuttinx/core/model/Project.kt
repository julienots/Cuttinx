package com.superessence.cuttinx.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class AspectPreset(val w: Int, val h: Int, val label: String) {
    PORTRAIT_9_16(9, 16, "9:16"),
    LANDSCAPE_16_9(16, 9, "16:9"),
    SQUARE_1_1(1, 1, "1:1"),
    PORTRAIT_4_5(4, 5, "4:5"),
    CINEMA_21_9(21, 9, "21:9"),
    CLASSIC_4_3(4, 3, "4:3"),
    CUSTOM(0, 0, "Perso"),
}

@Serializable
data class ProjectFormat(
    val width: Int = 1080,
    val height: Int = 1920,
    val frameRate: Int = 30,
    val aspect: AspectPreset = AspectPreset.PORTRAIT_9_16,
    /** 0xAARRGGBB background colour behind letterboxed media. */
    val backgroundColor: Long = 0xFF000000,
) {
    val aspectRatio: Double get() = width.toDouble() / height

    companion object {
        /** Returns an even-dimension format whose short side is [shortSide] pixels. */
        fun forAspect(aspect: AspectPreset, shortSide: Int = 1080, fps: Int = 30, customW: Int = 1080, customH: Int = 1920): ProjectFormat {
            if (aspect == AspectPreset.CUSTOM) return ProjectFormat(even(customW), even(customH), fps, aspect)
            val (w, h) = if (aspect.w >= aspect.h) {
                even(shortSide * aspect.w / aspect.h) to shortSide
            } else {
                shortSide to even(shortSide * aspect.h / aspect.w)
            }
            return ProjectFormat(w, h, fps, aspect)
        }
        private fun even(v: Int) = (v / 2) * 2
    }
}

@Serializable
data class Marker(val id: String, val timeUs: Long, val label: String = "", val color: Long = 0xFFFFD21F)

@Serializable
enum class VideoCodec(val mime: String, val label: String) {
    H264("video/avc", "H.264"),
    HEVC("video/hevc", "HEVC (H.265)"),
    AV1("video/av01", "AV1"),
    VP9("video/x-vnd.on2.vp9", "VP9"),
}

@Serializable
enum class AudioCodec(val mime: String, val label: String) {
    AAC("audio/mp4a-latm", "AAC"),
    OPUS("audio/opus", "Opus"),
}

@Serializable
enum class Container(val ext: String, val label: String) { MP4("mp4", "MP4"), WEBM("webm", "WebM"), MOV("mov", "MOV") }

@Serializable
data class ExportSettings(
    /** Output height for landscape / width for portrait — i.e. the short side ("1080p"). */
    val resolutionShortSide: Int = 1080,
    val frameRate: Int = 30,
    val videoCodec: VideoCodec = VideoCodec.H264,
    val audioCodec: AudioCodec = AudioCodec.AAC,
    val container: Container = Container.MP4,
    /** Video bitrate in bits/s; 0 = automatic from resolution/fps. */
    val videoBitrate: Int = 0,
    val audioBitrate: Int = 192_000,
    val audioSampleRate: Int = 48_000,
    val quality: ExportQuality = ExportQuality.HIGH,
    val destinationFolder: String = "Movies/Cuttinx",
    val fileName: String = "",
    val includeAudio: Boolean = true,
)

@Serializable
enum class ExportQuality(val bppf: Double) { DRAFT(0.05), STANDARD(0.08), HIGH(0.12), MAXIMUM(0.2) }

@Serializable
data class Project(
    val id: String,
    val name: String,
    val createdAtMs: Long = 0,
    val modifiedAtMs: Long = 0,
    val format: ProjectFormat = ProjectFormat(),
    val media: List<MediaAsset> = emptyList(),
    val tracks: List<Track> = emptyList(),
    val markers: List<Marker> = emptyList(),
    val export: ExportSettings = ExportSettings(),
    val archived: Boolean = false,
    val thumbnailPath: String? = null,
    val schemaVersion: Int = SCHEMA_VERSION,
) {
    val durationUs: Long get() = tracks.maxOfOrNull { it.endUs } ?: 0

    fun asset(id: String?): MediaAsset? = if (id == null) null else media.firstOrNull { it.id == id }
    fun track(id: String): Track? = tracks.firstOrNull { it.id == id }
    fun findClip(clipId: String): Pair<Track, Clip>? {
        for (t in tracks) for (c in t.clips) if (c.id == clipId) return t to c
        return null
    }
    val allClips: List<Clip> get() = tracks.flatMap { it.clips }
    val missingMedia: List<MediaAsset> get() = media.filter { it.missing }

    companion object { const val SCHEMA_VERSION = 1 }
}
