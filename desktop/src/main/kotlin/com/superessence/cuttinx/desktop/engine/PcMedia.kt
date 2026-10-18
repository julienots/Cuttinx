package com.superessence.cuttinx.desktop.engine

import com.superessence.cuttinx.core.model.MediaAsset
import com.superessence.cuttinx.core.model.MediaType
import com.superessence.cuttinx.core.util.Ids
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegLogCallback
import org.jetbrains.skia.Image
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Media files on the PC: probing, still images and WAV files. */
object PcMedia {
    val videoExt = setOf("mp4", "mov", "m4v", "mkv", "webm", "avi", "3gp", "ts", "mts", "wmv", "flv", "gif")
    val imageExt = setOf("png", "jpg", "jpeg", "webp", "bmp")
    val audioExt = setOf("mp3", "wav", "m4a", "aac", "ogg", "opus", "flac", "wma")

    init { runCatching { avutil.av_log_set_level(avutil.AV_LOG_ERROR); FFmpegLogCallback.setLevel(avutil.AV_LOG_ERROR) } }

    /** File behind an asset (plain path, or file:// URI of projects coming from Android). */
    fun fileOf(a: MediaAsset): File? {
        val f = if (a.uri.startsWith("file:")) runCatching { File(java.net.URI(a.uri)) }.getOrNull() else File(a.uri)
        return f?.takeIf { it.exists() }
    }

    fun typeOf(f: File): MediaType? = when (f.extension.lowercase()) {
        in videoExt -> MediaType.VIDEO
        in imageExt -> MediaType.IMAGE
        in audioExt -> MediaType.AUDIO
        else -> null
    }

    /** Reads the metadata of [f]. Throws with a readable message when the file can't be opened. */
    fun probe(f: File, folder: String = ""): MediaAsset {
        val type = typeOf(f) ?: error("Format non pris en charge : .${f.extension}")
        val id = Ids.new("m")
        val uri = f.absolutePath
        if (type == MediaType.IMAGE && f.extension.lowercase() != "gif") {
            val img = loadImage(f) ?: error("Image illisible : ${f.name}")
            return MediaAsset(id, uri, f.name, MediaType.IMAGE, "image/${f.extension.lowercase()}", 5_000_000, img.width, img.height,
                hasVideo = true, hasAudio = false, sizeBytes = f.length(), dateAddedMs = System.currentTimeMillis(), folder = folder)
        }
        FFmpegFrameGrabber(f).use { g ->
            g.start()
            val hasV = g.hasVideo() && type != MediaType.AUDIO
            val hasA = g.hasAudio()
            val rot = runCatching { g.getVideoMetadata("rotate")?.toInt() ?: 0 }.getOrDefault(0)
            var dur = g.lengthInTime
            if (dur <= 0 && hasV && g.frameRate > 0) dur = (g.lengthInFrames / g.frameRate * 1_000_000).toLong()
            if (dur <= 0) dur = 5_000_000
            return MediaAsset(
                id, uri, f.name, if (hasV) MediaType.VIDEO else MediaType.AUDIO, "", dur,
                if (hasV) g.imageWidth else 0, if (hasV) g.imageHeight else 0, ((rot % 360) + 360) % 360,
                if (hasV) g.frameRate.toFloat() else 0f, hasVideo = hasV, hasAudio = hasA,
                sampleRate = g.sampleRate, channelCount = g.audioChannels, bitrate = g.videoBitrate + g.audioBitrate,
                sizeBytes = f.length(), dateAddedMs = System.currentTimeMillis(), folder = folder,
                videoCodec = runCatching { g.videoCodecName ?: "" }.getOrDefault(""), audioCodec = runCatching { g.audioCodecName ?: "" }.getOrDefault(""),
            )
        }
    }

    private val images = object : LinkedHashMap<String, Image>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Image>?) = size > 24
    }

    /** Decoded still image (cached). */
    @Synchronized fun loadImage(f: File): Image? = images[f.path] ?: runCatching { Image.makeFromEncoded(f.readBytes()) }.getOrNull()?.also { images[f.path] = it }

    /** 16-bit PCM WAV. */
    fun writeWav(f: File, samples: FloatArray, sampleRate: Int, channels: Int) {
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { o ->
            val data = samples.size * 2
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()); h.putInt(36 + data); h.put("WAVE".toByteArray())
            h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(channels.toShort()); h.putInt(sampleRate)
            h.putInt(sampleRate * channels * 2); h.putShort((channels * 2).toShort()); h.putShort(16)
            h.put("data".toByteArray()); h.putInt(data)
            o.write(h.array())
            val bb = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (s in samples) bb.putShort((s.coerceIn(-1f, 1f) * 32767).toInt().toShort())
            o.write(bb.array())
        }
        f.delete(); tmp.renameTo(f)
    }
}
