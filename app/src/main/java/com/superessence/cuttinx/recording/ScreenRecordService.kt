package com.superessence.cuttinx.recording

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.superessence.cuttinx.MainActivity
import com.superessence.cuttinx.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import kotlin.concurrent.thread

/** Son enregistré avec l'écran. */
enum class ScreenAudio(val label: String) {
    NONE("Sans son"), MIC("Micro"), DEVICE("Son du téléphone"), BOTH("Téléphone + micro");
    val device get() = this == DEVICE || this == BOTH
    val mic get() = this == MIC || this == BOTH
}

/** Définition de la capture : la plus grande dimension de l'image produite. */
enum class ScreenQuality(val label: String, val maxSide: Int, val mbps: Int) {
    HD("720p", 1280, 6), FHD("1080p", 1920, 12), QHD("1440p", 2560, 20)
}

/** Réglages d'une capture d'écran. */
data class ScreenOptions(
    val quality: ScreenQuality = ScreenQuality.FHD,
    val fps: Int = 30,
    val audio: ScreenAudio = ScreenAudio.DEVICE,
    val maxSeconds: Int = 0,
)

/** État partagé entre le service et l'écran « Filmer » (le service vit même si l'appli passe en arrière-plan). */
object ScreenRecState {
    enum class Phase { IDLE, RECORDING, PAUSED }
    data class State(val phase: Phase = Phase.IDLE, val elapsedMs: Long = 0, val error: String? = null, val note: String? = null)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    /** Captures terminées pas encore reprises par l'écran « Filmer » (survit à la recomposition). */
    private val finished = ArrayList<File>()

    internal fun set(s: State) { _state.value = s }
    internal fun update(f: (State) -> State) { _state.value = f(_state.value) }
    @Synchronized internal fun addFinished(f: File) { finished += f }
    @Synchronized fun takeFinished(): List<File> = finished.toList().also { finished.clear() }
    fun clearMessages() = update { it.copy(error = null, note = null) }
}

/**
 * Enregistre l'écran du téléphone (MediaProjection) en MP4 H.264, avec le son interne des
 * applis (Android 10+), le micro, ou les deux mixés. Service au premier plan de type
 * « mediaProjection » : la capture continue pendant que tu filmes un jeu, une appli ou un tuto.
 * Pause / reprise / arrêt depuis la notification.
 */
class ScreenRecordService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var engine: ScreenEncoder? = null
    private val main = Handler(Looper.getMainLooper())
    private var startedAt = 0L
    private var pausedTotal = 0L
    private var pausedAt = 0L
    private var options = ScreenOptions()
    private var width = 0
    private var height = 0
    private var dpi = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_STOP -> stopCapture()
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        if (engine != null) return
        options = ScreenOptions(
            quality = ScreenQuality.entries.getOrElse(intent.getIntExtra(EXTRA_QUALITY, 1)) { ScreenQuality.FHD },
            fps = intent.getIntExtra(EXTRA_FPS, 30),
            audio = ScreenAudio.entries.getOrElse(intent.getIntExtra(EXTRA_AUDIO, 2)) { ScreenAudio.DEVICE },
            maxSeconds = intent.getIntExtra(EXTRA_MAX, 0),
        )
        // Android 14+ : le service doit être au premier plan AVANT d'obtenir la projection
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        val fgOk = runCatching { ServiceCompat.startForeground(this, NOTIF_ID, notification(paused = false), type) }.isSuccess
        if (!fgOk) { fail("Impossible de démarrer l'enregistrement de l'écran sur ce téléphone"); return }

        val code = intent.getIntExtra(EXTRA_CODE, 0)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = runCatching { data?.let { mpm.getMediaProjection(code, it) } }.getOrNull()
        if (mp == null) { fail("Autorisation de capture refusée"); return }
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { stopCapture() } }
        }, main)

        computeSize()
        val dir = File(filesDir, "recordings").apply { mkdirs() }
        val file = File(dir, "ecran_${System.currentTimeMillis()}.mp4")
        val withDevice = options.audio.device && Build.VERSION.SDK_INT >= 29
        val withMic = options.audio.mic
        val audioOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val note = when {
            options.audio.device && Build.VERSION.SDK_INT < 29 -> "Le son interne demande Android 10 : seul le micro est enregistré"
            (withDevice || withMic) && !audioOk -> "Micro non autorisé : capture sans son"
            else -> null
        }
        val enc = runCatching {
            ScreenEncoder(file, width, height, options.fps, options.quality.mbps * 1_000_000,
                if (audioOk && withDevice) mp else null, audioOk && (withMic || (options.audio.device && Build.VERSION.SDK_INT < 29)))
        }.getOrElse { fail("Encodeur vidéo indisponible : ${it.message}"); return }
        engine = enc
        display = runCatching {
            mp.createVirtualDisplay("CUTTINX-ecran", width, height, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, enc.surface, null, main)
        }.getOrElse { enc.release(); engine = null; fail("Capture d'écran impossible : ${it.message}"); return }
        enc.start()
        startedAt = System.currentTimeMillis(); pausedTotal = 0; pausedAt = 0
        ScreenRecState.set(ScreenRecState.State(ScreenRecState.Phase.RECORDING, 0, null, note))
        main.post(tick)
    }

    private val tick: Runnable = object : Runnable {
        override fun run() {
            val s = ScreenRecState.state.value
            if (engine == null || s.phase == ScreenRecState.Phase.IDLE) return
            val elapsed = elapsed()
            ScreenRecState.update { it.copy(elapsedMs = elapsed) }
            if (options.maxSeconds > 0 && elapsed >= options.maxSeconds * 1000L) { stopCapture(); return }
            main.postDelayed(this, 250)
        }
    }

    private fun elapsed(): Long {
        val now = if (pausedAt > 0) pausedAt else System.currentTimeMillis()
        return now - startedAt - pausedTotal
    }

    private fun pause() {
        val e = engine ?: return
        if (pausedAt > 0) return
        pausedAt = System.currentTimeMillis()
        display?.surface = null
        e.pause()
        ScreenRecState.update { it.copy(phase = ScreenRecState.Phase.PAUSED, elapsedMs = elapsed()) }
        notifyState(true)
    }

    private fun resume() {
        val e = engine ?: return
        if (pausedAt == 0L) return
        pausedTotal += System.currentTimeMillis() - pausedAt
        pausedAt = 0
        e.resume()
        display?.surface = e.surface
        ScreenRecState.update { it.copy(phase = ScreenRecState.Phase.RECORDING) }
        notifyState(false)
    }

    private fun stopCapture() {
        val e = engine
        engine = null
        main.removeCallbacks(tick)
        runCatching { display?.release() }; display = null
        val file = e?.let { runCatching { it.stop() }.getOrNull() }
        runCatching { projection?.stop() }; projection = null
        if (file != null && file.length() > 0) ScreenRecState.addFinished(file)
        ScreenRecState.update { ScreenRecState.State(error = if (e != null && file == null) "La capture n'a pas pu être finalisée" else it.error) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun fail(msg: String) {
        ScreenRecState.set(ScreenRecState.State(error = msg))
        runCatching { projection?.stop() }; projection = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (engine != null) stopCapture()
        super.onDestroy()
    }

    /** Taille de capture : proportions de l'écran, plus grand côté limité par la qualité, multiple de 16. */
    private fun computeSize() {
        val wm = getSystemService(WindowManager::class.java)
        val (w, h) = if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds; b.width() to b.height()
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m); m.widthPixels to m.heightPixels
        }
        dpi = resources.displayMetrics.densityDpi
        val scale = minOf(1.0, options.quality.maxSide.toDouble() / maxOf(w, h))
        width = ((w * scale).toInt() / 16 * 16).coerceAtLeast(320)
        height = ((h * scale).toInt() / 16 * 16).coerceAtLeast(320)
    }

    private fun notifyState(paused: Boolean) {
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(paused)) }
    }

    private fun notification(paused: Boolean): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Enregistrement de l'écran", NotificationManager.IMPORTANCE_LOW))
        fun action(a: String, rc: Int) = PendingIntent.getService(this, rc, Intent(this, ScreenRecordService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(if (paused) "CUTTINX — capture en pause" else "CUTTINX enregistre ton écran")
            .setContentText("Touchez « Arrêter » puis revenez dans CUTTINX pour monter la vidéo")
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .setUsesChronometer(!paused)
            .addAction(0, if (paused) "Reprendre" else "Pause", action(if (paused) ACTION_RESUME else ACTION_PAUSE, 1))
            .addAction(0, "Arrêter", action(ACTION_STOP, 2))
            .build()
    }

    companion object {
        const val CHANNEL = "cuttinx_screen"
        private const val NOTIF_ID = 31
        const val ACTION_START = "cuttinx.screen.START"
        const val ACTION_PAUSE = "cuttinx.screen.PAUSE"
        const val ACTION_RESUME = "cuttinx.screen.RESUME"
        const val ACTION_STOP = "cuttinx.screen.STOP"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        private const val EXTRA_QUALITY = "quality"
        private const val EXTRA_FPS = "fps"
        private const val EXTRA_AUDIO = "audio"
        private const val EXTRA_MAX = "max"

        /** Intention système qui demande à l'utilisateur l'autorisation de capturer l'écran. */
        fun consentIntent(ctx: Context): Intent = ctx.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent()

        fun start(ctx: Context, resultCode: Int, data: Intent, o: ScreenOptions) {
            ScreenRecState.clearMessages()
            val i = Intent(ctx, ScreenRecordService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_CODE, resultCode).putExtra(EXTRA_DATA, data)
                .putExtra(EXTRA_QUALITY, o.quality.ordinal).putExtra(EXTRA_FPS, o.fps)
                .putExtra(EXTRA_AUDIO, o.audio.ordinal).putExtra(EXTRA_MAX, o.maxSeconds)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun send(ctx: Context, action: String) {
            runCatching { ctx.startService(Intent(ctx, ScreenRecordService::class.java).setAction(action)) }
        }
    }
}

/**
 * Encodeur MediaCodec : vidéo H.264 depuis une Surface (l'écran virtuel) et AAC depuis
 * le son interne (AudioPlaybackCapture) et / ou le micro, mixés, puis multiplexés en MP4.
 */
private class ScreenEncoder(
    private val file: File,
    width: Int, height: Int, fps: Int, bitrate: Int,
    deviceAudio: MediaProjection?,
    mic: Boolean,
) {
    private val video: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    val surface: android.view.Surface
    private val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val lock = Object()
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxing = false
    private val wantsAudio: Boolean
    private var audio: MediaCodec? = null
    private var devRec: AudioRecord? = null
    private var micRec: AudioRecord? = null
    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var pauseStartUs = 0L
    @Volatile private var pausedUs = 0L
    @Volatile private var needKey = false
    private var lastVideoPts = -1L
    private var lastAudioPts = -1L
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null
    private var wroteVideo = false

    init {
        val vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            // répète la dernière image quand l'écran ne bouge pas (sinon la vidéo « saute »)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / fps)
        }
        video.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface = video.createInputSurface()

        var a: MediaCodec? = null
        if (deviceAudio != null || mic) runCatching {
            if (deviceAudio != null && Build.VERSION.SDK_INT >= 29) devRec = playbackRecord(deviceAudio)
            if (mic) micRec = micRecord()
            if (devRec != null || micRec != null) {
                val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 2).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK * 2)
                }
                a = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also { it.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE) }
            }
        }.onFailure { releaseAudioSources() }
        audio = a
        wantsAudio = a != null
    }

    @SuppressLint("MissingPermission") // vérifiée par le service avant la création
    @RequiresApi(29)
    private fun playbackRecord(mp: MediaProjection): AudioRecord? {
        val cfg = AudioPlaybackCaptureConfiguration.Builder(mp)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioRecord.Builder().setAudioFormat(fmt).setBufferSizeInBytes(maxOf(min, CHUNK) * 4).setAudioPlaybackCaptureConfig(cfg).build()
            .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
    }

    @SuppressLint("MissingPermission") // vérifiée par le service avant la création
    private fun micRecord(): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, CHUNK) * 4)
            .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
    }

    fun start() {
        running = true
        video.start()
        videoThread = thread(name = "cx-screen-video") { drainVideo() }
        audio?.let { enc ->
            enc.start()
            devRec?.startRecording(); micRec?.startRecording()
            audioThread = thread(name = "cx-screen-audio") { audioLoop(enc) }
        }
    }

    fun pause() { if (!paused) { pauseStartUs = System.nanoTime() / 1000; paused = true } }
    fun resume() {
        if (!paused) return
        pausedUs += System.nanoTime() / 1000 - pauseStartUs
        // les images encodées pendant la pause sont jetées : on repart sur une image clé
        needKey = true
        runCatching { video.setParameters(android.os.Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
        paused = false
    }

    /** Arrête l'encodage et renvoie le fichier, ou null si rien d'exploitable n'a été écrit. */
    fun stop(): File? {
        if (paused) resume()
        runCatching { video.signalEndOfInputStream() }
        videoThread?.join(3000)
        running = false
        audioThread?.join(3000)
        release()
        return if (wroteVideo) file else { file.delete(); null }
    }

    fun release() {
        running = false
        runCatching { video.stop() }; runCatching { video.release() }
        runCatching { audio?.stop() }; runCatching { audio?.release() }
        releaseAudioSources()
        runCatching { surface.release() }
        synchronized(lock) { if (muxing) runCatching { muxer.stop() }; runCatching { muxer.release() }; muxing = false }
    }

    private fun releaseAudioSources() {
        runCatching { devRec?.stop() }; runCatching { devRec?.release() }; devRec = null
        runCatching { micRec?.stop() }; runCatching { micRec?.release() }; micRec = null
    }

    private fun startMuxerIfReady() {
        if (!muxing && videoTrack >= 0 && (!wantsAudio || audioTrack >= 0)) { muxer.start(); muxing = true }
    }

    private fun write(track: Int, buf: java.nio.ByteBuffer, info: MediaCodec.BufferInfo, isVideo: Boolean) {
        synchronized(lock) {
            if (!muxing || track < 0 || info.size <= 0) return
            val pts = info.presentationTimeUs - pausedUs
            if (isVideo) { if (pts <= lastVideoPts) return; lastVideoPts = pts } else { if (pts <= lastAudioPts) return; lastAudioPts = pts }
            info.presentationTimeUs = pts
            buf.position(info.offset); buf.limit(info.offset + info.size)
            runCatching { muxer.writeSampleData(track, buf, info); if (isVideo) wroteVideo = true }
        }
    }

    private fun drainVideo() {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = runCatching { video.dequeueOutputBuffer(info, 10_000) }.getOrElse { return }
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(lock) { videoTrack = muxer.addTrack(video.outputFormat); startMuxerIfReady() }
                idx >= 0 -> {
                    val buf = video.getOutputBuffer(idx)
                    val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    if (key) needKey = false
                    if (buf != null && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && !paused && !needKey) write(videoTrack, buf, info, true)
                    video.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                idx == MediaCodec.INFO_TRY_AGAIN_LATER && !running -> return
            }
        }
    }

    private fun audioLoop(enc: MediaCodec) {
        val a = ShortArray(CHUNK / 2)
        val b = ShortArray(CHUNK / 2)
        val bytes = java.nio.ByteBuffer.allocate(CHUNK).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val info = MediaCodec.BufferInfo()
        var eos = false
        while (true) {
            if (!eos) {
                val n = readMixed(a, b)
                val inIdx = runCatching { enc.dequeueInputBuffer(10_000) }.getOrDefault(-1)
                if (inIdx >= 0) {
                    val ib = enc.getInputBuffer(inIdx)
                    val ptsUs = System.nanoTime() / 1000
                    if (!running) { enc.queueInputBuffer(inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM); eos = true }
                    else if (ib != null && n > 0 && !paused) {
                        bytes.clear(); for (i in 0 until n) bytes.putShort(a[i]); bytes.flip()
                        ib.clear(); ib.put(bytes)
                        enc.queueInputBuffer(inIdx, 0, n * 2, ptsUs, 0)
                    } else enc.queueInputBuffer(inIdx, 0, 0, ptsUs, 0)
                }
            }
            val outIdx = runCatching { enc.dequeueOutputBuffer(info, 5_000) }.getOrElse { return }
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(lock) { audioTrack = muxer.addTrack(enc.outputFormat); startMuxerIfReady() }
                outIdx >= 0 -> {
                    val ob = enc.getOutputBuffer(outIdx)
                    if (ob != null && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) write(audioTrack, ob, info, false)
                    enc.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                eos && outIdx == MediaCodec.INFO_TRY_AGAIN_LATER && videoThread?.isAlive != true -> return
            }
        }
    }

    /** Lit un bloc du son interne et du micro et les additionne (avec écrêtage doux). */
    private fun readMixed(a: ShortArray, b: ShortArray): Int {
        val d = devRec; val m = micRec
        val na = d?.read(a, 0, a.size) ?: 0
        val nb = m?.read(b, 0, b.size) ?: 0
        if (d == null) { System.arraycopy(b, 0, a, 0, maxOf(nb, 0)); return nb }
        if (m == null || nb <= 0) return na
        val n = maxOf(na, nb)
        for (i in 0 until n) {
            val s = (if (i < na) a[i].toInt() else 0) + (if (i < nb) b[i].toInt() else 0)
            a[i] = s.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return n
    }

    companion object {
        const val RATE = 48_000
        const val CHUNK = 4096
    }
}
