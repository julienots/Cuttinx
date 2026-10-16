package com.superessence.cuttinx.media

import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.superessence.cuttinx.core.model.MediaAsset
import com.superessence.cuttinx.core.model.MediaType
import com.superessence.cuttinx.core.util.Ids

class UnsupportedMediaException(message: String) : Exception(message)

/** Reads real metadata from a media file (never assumes a format is supported). */
object MediaProbe {
    fun displayName(cr: ContentResolver, uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment ?: "media"
        var size = 0L
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        } else if (uri.scheme == "file") { val f = java.io.File(uri.path!!); name = f.name; size = f.length() }
        return name to size
    }

    fun typeOf(mime: String, name: String): MediaType? {
        val n = name.lowercase()
        return when {
            mime.startsWith("video/") || n.endsWith(".mp4") || n.endsWith(".mov") || n.endsWith(".webm") || n.endsWith(".mkv") || n.endsWith(".3gp") -> MediaType.VIDEO
            mime == "image/gif" || n.endsWith(".gif") -> MediaType.IMAGE
            mime.startsWith("image/") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp") || n.endsWith(".heic") -> MediaType.IMAGE
            mime.startsWith("audio/") || n.endsWith(".mp3") || n.endsWith(".wav") || n.endsWith(".aac") || n.endsWith(".m4a") || n.endsWith(".ogg") || n.endsWith(".flac") || n.endsWith(".opus") -> MediaType.AUDIO
            n.endsWith(".srt") || n.endsWith(".vtt") -> MediaType.SUBTITLE
            n.endsWith(".ttf") || n.endsWith(".otf") -> MediaType.FONT
            else -> null
        }
    }

    fun probe(context: Context, uri: Uri, forcedType: MediaType? = null): MediaAsset {
        val cr = context.contentResolver
        val (name, size) = displayName(cr, uri)
        val mime = cr.getType(uri) ?: ""
        val type = forcedType ?: typeOf(mime, name) ?: throw UnsupportedMediaException("Type de fichier non reconnu : $name")
        val now = System.currentTimeMillis()
        val base = MediaAsset(Ids.new("med"), uri.toString(), name, type, mime, sizeBytes = size, dateAddedMs = now)
        return when (type) {
            MediaType.IMAGE, MediaType.STICKER -> probeImage(cr, uri, base)
            MediaType.VIDEO, MediaType.AUDIO -> probeAv(context, uri, base)
            else -> base.copy(hasVideo = false, hasAudio = false)
        }
    }

    private fun probeImage(cr: ContentResolver, uri: Uri, a: MediaAsset): MediaAsset {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: throw UnsupportedMediaException("Image illisible : ${a.displayName}")
        if (opts.outWidth <= 0) throw UnsupportedMediaException("Format d'image non pris en charge : ${a.displayName}")
        val rot = runCatching {
            cr.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrNull().let {
            when (it) { ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90; ExifInterface.ORIENTATION_ROTATE_180 -> 180; ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270; else -> 0 }
        }
        return a.copy(width = opts.outWidth, height = opts.outHeight, rotationDegrees = rot, mimeType = opts.outMimeType ?: a.mimeType, hasVideo = true, hasAudio = false)
    }

    private fun probeAv(context: Context, uri: Uri, a: MediaAsset): MediaAsset {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(context, uri)
            val dur = (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) * 1000
            val hasVideo = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            val hasAudio = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val br = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
            var asset = a.copy(durationUs = dur, hasVideo = hasVideo, hasAudio = hasAudio, width = w, height = h, rotationDegrees = rot, bitrate = br,
                type = if (a.type == MediaType.VIDEO && !hasVideo && hasAudio) MediaType.AUDIO else a.type)
            // codec details via MediaExtractor
            val ex = MediaExtractor()
            try {
                ex.setDataSource(context, uri, null)
                for (i in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(i)
                    val m = f.getString(MediaFormat.KEY_MIME) ?: continue
                    if (m.startsWith("video/") && asset.videoCodec.isEmpty()) {
                        val fr = if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) runCatching { f.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }.getOrElse { runCatching { f.getFloat(MediaFormat.KEY_FRAME_RATE) }.getOrDefault(0f) } else 0f
                        asset = asset.copy(videoCodec = m, frameRate = fr)
                    }
                    if (m.startsWith("audio/") && asset.audioCodec.isEmpty()) {
                        asset = asset.copy(audioCodec = m,
                            sampleRate = if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) f.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 0,
                            channelCount = if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 0)
                    }
                }
            } catch (_: Exception) { } finally { ex.release() }
            if (!hasVideo && !hasAudio) throw UnsupportedMediaException("Aucune piste lisible dans ${a.displayName}")
            if (dur <= 0) throw UnsupportedMediaException("Durée inconnue : ${a.displayName}")
            // real decoder support check
            val tags = ArrayList<String>()
            if (asset.videoCodec.isNotEmpty() && !CodecInfo.canDecode(asset.videoCodec, asset.width, asset.height)) tags += "décodage-non-supporté"
            if (asset.audioCodec.isNotEmpty() && !CodecInfo.canDecodeAudio(asset.audioCodec)) tags += "audio-non-supporté"
            return asset.copy(tags = tags)
        } catch (e: UnsupportedMediaException) { throw e
        } catch (e: Exception) {
            throw UnsupportedMediaException("Impossible de lire ${a.displayName} : ${e.message ?: "format non pris en charge"}")
        } finally { runCatching { mmr.release() } }
    }

    /** Checks that the file is still reachable (missing media detection). */
    fun isReachable(context: Context, uriString: String): Boolean = runCatching {
        val uri = Uri.parse(uriString)
        if (uri.scheme == "file") java.io.File(uri.path!!).exists()
        else context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    }.getOrDefault(false)
}
