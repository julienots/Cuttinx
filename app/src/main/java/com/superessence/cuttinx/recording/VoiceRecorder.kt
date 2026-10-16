package com.superessence.cuttinx.recording

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/** Records voice-overs to AAC (.m4a) in app storage. */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    var file: File? = null; private set
    private var startMs = 0L

    val isRecording get() = recorder != null
    val elapsedMs get() = if (recorder != null) System.currentTimeMillis() - startMs else 0

    fun start(): File {
        val dir = File(context.filesDir, "recordings").also { it.mkdirs() }
        val f = File(dir, "voix_${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioSamplingRate(48000)
        r.setAudioEncodingBitRate(160_000)
        r.setAudioChannels(1)
        r.setOutputFile(f.absolutePath)
        r.prepare()
        r.start()
        recorder = r; file = f; startMs = System.currentTimeMillis()
        return f
    }

    /** Peak level 0..1 since the last call (for the level meter). */
    fun level(): Float = runCatching { (recorder?.maxAmplitude ?: 0) / 32767f }.getOrDefault(0f)

    fun stop(): File? {
        val r = recorder ?: return null
        recorder = null
        return try { r.stop(); file } catch (e: RuntimeException) { file?.delete(); null } finally { r.release() }
    }

    fun cancel() { stop()?.delete() }
}
