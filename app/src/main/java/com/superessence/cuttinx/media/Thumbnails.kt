package com.superessence.cuttinx.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import com.superessence.cuttinx.core.model.MediaAsset
import com.superessence.cuttinx.core.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/** Memory + disk thumbnail cache (frames of videos, downscaled images). */
class Thumbnails(private val context: Context) {
    private val mem = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 16).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val dir = File(context.cacheDir, "thumbs").also { it.mkdirs() }
    private val gate = Semaphore(3)

    fun cached(key: String): Bitmap? = mem.get(key)

    /** Frame of [asset] at source time [timeUs] whose height is about [height] px. */
    suspend fun frame(asset: MediaAsset, timeUs: Long, height: Int = 120): Bitmap? {
        val bucket = if (asset.type == MediaType.VIDEO) (timeUs / 250_000) * 250_000 else 0
        val key = "${asset.id}_${bucket}_$height"
        mem.get(key)?.let { return it }
        return gate.withPermit {
            withContext(Dispatchers.IO) {
                mem.get(key)?.let { return@withContext it }
                val f = File(dir, key.hashCode().toUInt().toString(16) + ".jpg")
                val bmp = if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else load(asset, bucket, height)?.also { b ->
                    runCatching { f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 80, it) } }
                }
                bmp?.also { mem.put(key, it) }
            }
        }
    }

    private fun load(asset: MediaAsset, timeUs: Long, height: Int): Bitmap? = runCatching {
        val uri = Uri.parse(asset.uri)
        when (asset.type) {
            MediaType.IMAGE, MediaType.STICKER -> decodeImage(context, uri, height * 2, asset.rotationDegrees)
            MediaType.VIDEO -> {
                val mmr = MediaMetadataRetriever()
                try {
                    mmr.setDataSource(context, uri)
                    val w = (height * (asset.displayWidth.toDouble() / maxOf(1, asset.displayHeight))).toInt().coerceIn(16, 1024)
                    if (Build.VERSION.SDK_INT >= 27) mmr.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, w, height)
                    else mmr.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { Bitmap.createScaledBitmap(it, w, height, true) }
                } finally { mmr.release() }
            }
            MediaType.AUDIO -> {
                val mmr = MediaMetadataRetriever()
                try { mmr.setDataSource(context, uri); mmr.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } } finally { mmr.release() }
            }
            else -> null
        }
    }.getOrNull()

    companion object {
        fun decodeImage(context: Context, uri: Uri, maxSide: Int, rotation: Int = 0): Bitmap? {
            val cr = context.contentResolver
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            var sample = 1
            while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2
            val bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
            if (rotation == 0) return bmp
            return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
        }
    }
}

/** Cached waveform overviews (min/max pairs, 50 buckets per second). */
class Waveforms(private val context: Context) {
    private val mem = LruCache<String, FloatArray>(64)
    private val dir = File(context.cacheDir, "waves").also { it.mkdirs() }
    private val gate = Semaphore(2)
    val bucketsPerSecond = 50

    fun cached(assetId: String) = mem.get(assetId)

    suspend fun load(asset: MediaAsset): FloatArray? {
        mem.get(asset.id)?.let { return it }
        if (!asset.hasAudio) return null
        return gate.withPermit {
            withContext(Dispatchers.IO) {
                val f = File(dir, asset.id + ".wf")
                val data = if (f.exists()) runCatching {
                    java.io.DataInputStream(f.inputStream().buffered()).use { s -> FloatArray(s.readInt()) { s.readFloat() } }
                }.getOrNull() else null
                val wf = data ?: runCatching {
                    val pcm = PcmDecoder.decode(context, Uri.parse(asset.uri), downmix = true)
                    val buckets = maxOf(1, (pcm.durationUs * bucketsPerSecond / 1_000_000).toInt())
                    com.superessence.cuttinx.core.audio.AudioAnalysis.waveform(pcm.samples, 1, buckets).also { arr ->
                        java.io.DataOutputStream(f.outputStream().buffered()).use { s -> s.writeInt(arr.size); arr.forEach { s.writeFloat(it) } }
                    }
                }.getOrNull()
                wf?.also { mem.put(asset.id, it) }
            }
        }
    }
}

/** Samples small frames for the local AI (scene detection, highlights, tracking, reframing). */
object FrameSampler {
    fun sample(context: Context, asset: MediaAsset, startUs: Long, endUs: Long, stepUs: Long, width: Int = 64, exact: Boolean = false, cancelled: () -> Boolean = { false }, progress: (Float) -> Unit = {}): List<com.superessence.cuttinx.core.analysis.Frame> {
        val out = ArrayList<com.superessence.cuttinx.core.analysis.Frame>()
        val h = (width * asset.displayHeight.toDouble() / maxOf(1, asset.displayWidth)).toInt().coerceAtLeast(8)
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(context, Uri.parse(asset.uri))
            var t = startUs
            val total = (endUs - startUs).coerceAtLeast(1)
            while (t <= endUs && !cancelled()) {
                val opt = if (exact) MediaMetadataRetriever.OPTION_CLOSEST else MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                val bmp = (if (Build.VERSION.SDK_INT >= 27) mmr.getScaledFrameAtTime(t, opt, width, h) else mmr.getFrameAtTime(t, opt)?.let { Bitmap.createScaledBitmap(it, width, h, true) })
                if (bmp != null) {
                    val b = if (bmp.width != width || bmp.height != h) Bitmap.createScaledBitmap(bmp, width, h, true) else bmp
                    val px = IntArray(width * h); b.getPixels(px, 0, width, 0, 0, width, h)
                    out += com.superessence.cuttinx.core.analysis.Frame(t, width, h, px)
                }
                progress(((t - startUs).toFloat() / total).coerceIn(0f, 1f))
                t += stepUs
            }
        } finally { mmr.release() }
        return out
    }
}
