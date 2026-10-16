package com.superessence.cuttinx.engine

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.superessence.cuttinx.core.model.Project
import com.superessence.cuttinx.core.project.ResolvedExport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

sealed interface ExportState {
    data object Idle : ExportState
    data class Running(val projectName: String, val progress: Int, val elapsedMs: Long, val etaMs: Long?, val stage: String) : ExportState
    /** [width]/[height]/[durationUs] are measured on the produced file, never assumed. */
    data class Done(val uri: Uri, val displayPath: String, val width: Int, val height: Int, val durationUs: Long, val sizeBytes: Long, val elapsedMs: Long, val warnings: List<String>, val videoMime: String) : ExportState
    data class Failed(val message: String, val technical: String) : ExportState
    data object Cancelled : ExportState
}

/**
 * Renders a project to an MP4 file with Media3 Transformer (hardware encoders), then publishes it
 * to the gallery (Movies/Cuttinx). No watermark, no duration or resolution limit is added.
 */
@UnstableApi
class ExportEngine(private val context: Context, private val factory: CompositionFactory) {
    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow<ExportState>(ExportState.Idle)
    val state: StateFlow<ExportState> = _state.asStateFlow()
    private var transformer: Transformer? = null
    private var tmp: File? = null
    private var startMs = 0L
    private val progress = ProgressHolder()

    val isRunning get() = _state.value is ExportState.Running

    fun start(project: Project, settings: ResolvedExport, destinationFolder: String, fileName: String) {
        if (isRunning) return
        val built = try {
            factory.build(LiveProject(project), settings.width, settings.height, settings.frameRate, preview = false)
        } catch (e: Exception) {
            _state.value = ExportState.Failed("Impossible de préparer le rendu : ${e.message}", e.toString()); return
        }
        val out = File(context.cacheDir, "export").also { it.mkdirs() }.let { File(it, "cuttinx_${System.currentTimeMillis()}.mp4") }
        tmp = out
        val encoder = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(settings.videoBitrate).build())
            .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(settings.audioBitrate).build())
            .setEnableFallback(true)
            .build()
        val t = Transformer.Builder(context)
            .setVideoMimeType(settings.videoCodec.mime)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoder)
            .setLooper(Looper.getMainLooper())
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) = finish(project, out, destinationFolder, fileName, settings, exportResult)
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    out.delete()
                    transformer = null
                    stopService()
                    _state.value = ExportState.Failed(friendly(exportException), "${exportException.errorCodeName}: ${exportException.message} ${exportException.cause?.message ?: ""}")
                }
            })
            .build()
        transformer = t
        startMs = System.currentTimeMillis()
        _state.value = ExportState.Running(project.name, 0, 0, null, "Préparation")
        startService()
        try {
            t.start(built.composition, out.absolutePath)
        } catch (e: Exception) {
            transformer = null
            stopService()
            _state.value = ExportState.Failed("Démarrage impossible : ${e.message}", e.toString())
            return
        }
        poll(project.name)
    }

    private fun poll(name: String) {
        val t = transformer ?: return
        val st = t.getProgress(progress)
        val elapsed = System.currentTimeMillis() - startMs
        if (_state.value is ExportState.Running) {
            val pct = if (st == Transformer.PROGRESS_STATE_AVAILABLE) progress.progress else (_state.value as ExportState.Running).progress
            val eta = if (pct in 3..99) elapsed * (100 - pct) / pct else null
            _state.value = ExportState.Running(name, pct, elapsed, eta, if (pct == 0) "Préparation" else "Encodage")
            main.postDelayed({ poll(name) }, 250)
        }
    }

    private fun finish(p: Project, file: File, folder: String, fileName: String, settings: ResolvedExport, result: ExportResult) {
        transformer = null
        _state.value = ExportState.Running(p.name, 100, System.currentTimeMillis() - startMs, 0, "Enregistrement dans la galerie")
        Thread {
            try {
                // measure the real output
                val mmr = MediaMetadataRetriever()
                mmr.setDataSource(file.absolutePath)
                val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: result.width
                val h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: result.height
                val rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                val d = (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) * 1000
                val mime = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: "video/mp4"
                mmr.release()
                val (dw, dh) = if (rot % 180 != 0) h to w else w to h
                val warnings = settings.warnings.toMutableList()
                if (dw != settings.width || dh != settings.height) warnings += "Résolution réellement produite : ${dw}×$dh (demandée ${settings.width}×${settings.height})."
                val name = fileName.ifBlank { p.name }.replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".mp4"
                val (uri, path) = publish(file, name, folder)
                val size = file.length()
                file.delete()
                main.post {
                    stopService()
                    _state.value = ExportState.Done(uri, path, dw, dh, d, size, System.currentTimeMillis() - startMs, warnings, settings.videoCodec.label)
                }
            } catch (e: Exception) {
                main.post { stopService(); _state.value = ExportState.Failed("La vidéo a été rendue mais n'a pas pu être enregistrée : ${e.message}", e.toString()) }
            }
        }.start()
    }

    private fun publish(file: File, name: String, folder: String): Pair<Uri, String> {
        val rel = folder.trim('/').ifBlank { "Movies/Cuttinx" }
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, if (rel.startsWith("Movies") || rel.startsWith("DCIM")) rel else "Movies/$rel")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: error("MediaStore a refusé le fichier")
            resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri to "$rel/$name"
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), rel.removePrefix("Movies/").ifBlank { "Cuttinx" }).also { it.mkdirs() }
            val dest = File(dir, name)
            file.copyTo(dest, overwrite = true)
            android.media.MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf("video/mp4"), null)
            return Uri.fromFile(dest) to dest.absolutePath
        }
    }

    fun cancel() {
        transformer?.cancel()
        transformer = null
        tmp?.delete()
        stopService()
        _state.value = ExportState.Cancelled
    }

    fun reset() { if (!isRunning) _state.value = ExportState.Idle }

    private fun startService() {
        runCatching { ContextCompat.startForegroundService(context, Intent(context, ExportService::class.java)) }
    }

    private fun stopService() { runCatching { context.stopService(Intent(context, ExportService::class.java)) } }

    companion object {
        fun friendly(e: ExportException): String = when (e.errorCode) {
            ExportException.ERROR_CODE_ENCODER_INIT_FAILED, ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED -> "L'encodeur de l'appareil refuse ces réglages. Essayez H.264, une résolution ou une fréquence plus basse."
            ExportException.ERROR_CODE_DECODER_INIT_FAILED, ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "Un média utilise un format que l'appareil ne sait pas décoder."
            ExportException.ERROR_CODE_IO_FILE_NOT_FOUND, ExportException.ERROR_CODE_IO_NO_PERMISSION -> "Un média du projet est introuvable ou n'est plus accessible."
            ExportException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED -> "Erreur du rendu GPU (effet ou mémoire). Réduisez la résolution ou le nombre d'effets."
            ExportException.ERROR_CODE_AUDIO_PROCESSING_FAILED -> "Erreur lors du traitement audio."
            ExportException.ERROR_CODE_MUXING_FAILED, ExportException.ERROR_CODE_MUXING_TIMEOUT -> "Écriture du fichier impossible (espace de stockage ?)."
            else -> "L'export a échoué (${e.errorCodeName})."
        }
    }
}
