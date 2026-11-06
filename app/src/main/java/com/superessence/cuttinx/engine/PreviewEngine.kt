package com.superessence.cuttinx.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceView
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MultipleInputVideoGraph
import androidx.media3.transformer.CompositionPlayer
import com.superessence.cuttinx.core.model.Project
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max
import kotlin.math.roundToInt

enum class PreviewQuality(val label: String, val shortSide: Int) { FULL("Pleine (1080p)", 1080), HIGH("Haute (720p)", 720), MEDIUM("Moyenne (540p)", 540), LOW("Économie (360p)", 360) }

data class PreviewState(
    val positionUs: Long = 0,
    val durationUs: Long = 0,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val error: String? = null,
    val skippedAssets: List<String> = emptyList(),
    val renderWidth: Int = 0,
    val renderHeight: Int = 0,
    /** Non blocking information (e.g. the preview recovered by disabling GPU effects). */
    val notice: String? = null,
    /** True while the preview runs without CUTTINX shaders after a GPU failure. */
    val safeMode: Boolean = false,
)

/**
 * Real-time preview built on Media3 CompositionPlayer, using the very same [CompositionFactory]
 * as the export. Structural edits rebuild the composition (debounced); other edits are applied
 * live through [LiveProject].
 */
@UnstableApi
class PreviewEngine(private val context: Context, private val factory: CompositionFactory) {
    private val main = Handler(Looper.getMainLooper())
    private var player: CompositionPlayer? = null
    private var surface: SurfaceView? = null
    private val live = LiveProject(Project("", ""))
    private var structure = ""
    private var pending: Runnable? = null
    private val _state = MutableStateFlow(PreviewState())
    val state: StateFlow<PreviewState> = _state.asStateFlow()
    var quality: PreviewQuality = PreviewQuality.HIGH
        set(v) { if (field != v) { field = v; structure = ""; update(live.project, immediate = true) } }
    private var loopRange: LongRange? = null
    /** Shaders disabled after the GPU driver failed on one of them (the export keeps them). */
    private var safeMode = false
    /** Automatic recoveries attempted for the current structure (avoids rebuild loops). */
    private var recoveries = 0

    /** Target of the seek waiting for the current one to finish (seeks are coalesced). */
    private var pendingSeekMs: Long? = null
    private var seeking = false
    /** Uptime when the player entered STATE_BUFFERING (0 = not buffering). */
    private var bufferingSinceMs = 0L
    /** Stalls detected for the current structure; the second one lowers the preview quality. */
    private var stalls = 0
    private var tickerRunning = false

    private val ticker = object : Runnable {
        override fun run() {
            val p = player
            if (p == null) { tickerRunning = false; return }
            val pos = (pendingSeekMs ?: p.currentPosition) * 1000
            loopRange?.let { r -> if (p.isPlaying && pos >= r.last) seekTo(r.first) }
            _state.value = _state.value.copy(positionUs = if (seeking || pendingSeekMs != null) _state.value.positionUs else pos, playing = p.isPlaying)
            watchdog(p)
            main.postDelayed(this, 33)
        }
    }

    /**
     * Some devices never leave STATE_BUFFERING after a seek or a composition change with heavy
     * sources (4K / 60 i/s, HEVC): decoders are not handed back. When buffering lasts too long the
     * player is recreated at the same position; on a second stall the preview quality is lowered.
     */
    private fun watchdog(p: CompositionPlayer) {
        if (bufferingSinceMs == 0L || p.playbackState != Player.STATE_BUFFERING) return
        val limit = if (stalls == 0) STALL_MS else STALL_MS * 2
        if (android.os.SystemClock.uptimeMillis() - bufferingSinceMs < limit) return
        bufferingSinceMs = 0
        stalls++
        Log.w(TAG, "Aperçu bloqué en chargement : reconstruction du lecteur (blocage n°$stalls)")
        if (stalls >= 2 && quality != PreviewQuality.LOW) {
            val lower = PreviewQuality.entries[quality.ordinal + 1]
            _state.value = _state.value.copy(notice = "Aperçu allégé (${lower.label}) : la vidéo est très lourde pour ce téléphone. L'export reste en pleine qualité.")
            quality = lower // rebuilds
            return
        }
        val resume = p.playWhenReady
        structure = ""
        val (w, h) = previewSize(live.project)
        rebuild(StructureKey.of(live.project, w, h, live.project.format.frameRate), resume)
    }

    /** Item boundaries of the current primary video sequence. */
    private var boundariesUs: List<Long> = emptyList()

    /**
     * @param multiInput only projects with video overlays need the multi-input graph (whose seeks
     *   are not reliable in Media3 1.11); every other project gets the default single-input graph.
     */
    private fun newPlayer(multiInput: Boolean): CompositionPlayer {
        val builder = CompositionPlayer.Builder(context)
            // maintainers' recommended fix for freezes near item boundaries (androidx/media#3453)
            .setPerStreamMediaProgressionEnabled(true)
        if (multiInput) builder.setVideoGraphFactory(MultipleInputVideoGraph.Factory())
        val p = builder.build()
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (player !== p) return
                Log.e(TAG, "Erreur de lecture", error)
                seeking = false; pendingSeekMs = null; bufferingSinceMs = 0
                if (recover(error)) return
                _state.value = _state.value.copy(error = friendly(error), playing = false, buffering = false)
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (player !== p) return
                val buffering = playbackState == Player.STATE_BUFFERING
                bufferingSinceMs = if (buffering) (if (bufferingSinceMs == 0L) android.os.SystemClock.uptimeMillis() else bufferingSinceMs) else 0L
                _state.value = _state.value.copy(buffering = buffering)
                if (playbackState == Player.STATE_READY) stalls = 0
                if (playbackState != Player.STATE_BUFFERING) onSeekSettled(p)
                if (playbackState == Player.STATE_ENDED) _state.value = _state.value.copy(playing = false)
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (player === p) _state.value = _state.value.copy(playing = isPlaying) }
        })
        surface?.let { p.setVideoSurfaceView(it) }
        return p
    }

    private fun ensurePlayer(multiInput: Boolean = false): CompositionPlayer {
        player?.let { return it }
        val p = newPlayer(multiInput)
        player = p
        if (!tickerRunning) { tickerRunning = true; main.post(ticker) }
        return p
    }

    /** Releases the current player (freeing its hardware decoders) and creates a fresh one. */
    private fun recreatePlayer(multiInput: Boolean): CompositionPlayer {
        player?.let { old ->
            surface?.let { old.clearVideoSurfaceView(it) }
            runCatching { old.release() }
        }
        player = null
        seeking = false; pendingSeekMs = null; bufferingSinceMs = 0
        return ensurePlayer(multiInput)
    }

    /** A seek finished: apply the most recent target requested meanwhile. */
    private fun onSeekSettled(p: CompositionPlayer) {
        seeking = false
        val next = pendingSeekMs ?: return
        pendingSeekMs = null
        if (next != p.currentPosition) { seeking = true; p.seekTo(next) }
    }

    fun attach(view: SurfaceView) {
        surface = view
        player?.setVideoSurfaceView(view)
    }

    fun detach(view: SurfaceView) {
        if (surface === view) { player?.clearVideoSurfaceView(view); surface = null }
    }

    /** Output size of the preview for the current quality. */
    fun previewSize(p: Project): Pair<Int, Int> {
        val short = minOf(p.format.width, p.format.height)
        val k = minOf(1.0, quality.shortSide.toDouble() / max(1, short))
        fun even(v: Double) = max(2, (v / 2).roundToInt() * 2)
        return even(p.format.width * k) to even(p.format.height * k)
    }

    /** Called on every project change. */
    fun update(p: Project, immediate: Boolean = false) {
        live.project = p
        val (w, h) = previewSize(p)
        val key = StructureKey.of(p, w, h, p.format.frameRate)
        if (key == structure && player != null) {
            // live edit: refresh the displayed frame when paused
            val pl = player!!
            if (!pl.isPlaying) seekTo((pendingSeekMs ?: pl.currentPosition) * 1000)
            return
        }
        if (structure.isNotEmpty()) recoveries = 0 // a new structure gets its own automatic retry
        pending?.let { main.removeCallbacks(it) }
        val r = Runnable { rebuild(key) }
        pending = r
        if (immediate || structure.isEmpty()) main.post(r) else main.postDelayed(r, 220)
    }

    /**
     * Automatic recovery from a playback failure: rebuilds the composition at the same position,
     * first as is (transient decoder/GL failures), then without GPU shaders.
     */
    private fun recover(error: PlaybackException): Boolean {
        val gpu = error.errorCode == PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED
        when {
            recoveries == 0 && !gpu -> recoveries = 1
            gpu && !safeMode -> {
                safeMode = true; recoveries = 1
                _state.value = _state.value.copy(safeMode = true, notice = "Aperçu simplifié : un effet GPU n'est pas pris en charge par ce téléphone. L'export reste complet.")
            }
            else -> return false
        }
        val resume = _state.value.playing || player?.playWhenReady == true
        structure = ""
        val (w, h) = previewSize(live.project)
        main.post { rebuild(StructureKey.of(live.project, w, h, live.project.format.frameRate), resume) }
        return true
    }

    /** Retries the full-quality preview (with effects) after an error or a safe-mode fallback. */
    fun retry() {
        safeMode = false; recoveries = 0; structure = ""
        _state.value = _state.value.copy(error = null, notice = null, safeMode = false)
        update(live.project, immediate = true)
    }

    private fun rebuild(key: String, resume: Boolean? = null) {
        pending = null
        val p = live.project
        val old = player
        // position/state of the outgoing player (a pending seek wins: it is what the user asked for)
        val oldPosMs = pendingSeekMs ?: old?.currentPosition ?: 0L
        val oldPlaying = old?.let { it.isPlaying || (it.playWhenReady && it.playbackState == Player.STATE_BUFFERING) } ?: false
        if (p.durationUs <= 0) {
            val pl = ensurePlayer()
            structure = key
            pl.stop()
            _state.value = _state.value.copy(durationUs = 0, positionUs = 0, playing = false)
            return
        }
        val wasPlaying = resume ?: oldPlaying
        val pos = (oldPosMs * 1000).coerceIn(0, max(0, p.durationUs - 1000))
        val (w, h) = previewSize(p)
        try {
            val built = if (factory.canUseSingleSequence(p)) factory.buildSingle(live, w, h, p.format.frameRate, gpuEffects = !safeMode)
                else factory.build(live, w, h, p.format.frameRate, preview = true, gpuEffects = !safeMode)
            structure = key
            boundariesUs = built.boundariesUs
            // A fresh player per structure: reusing one across setComposition() calls could stay
            // stuck in buffering with heavy sources (decoders not released in time).
            val pl = recreatePlayer(built.multiInput)
            pl.setComposition(built.composition, (if (wasPlaying) safeStart(pos) else pos) / 1000)
            pl.prepare()
            pl.playWhenReady = wasPlaying
            _state.value = _state.value.copy(durationUs = built.durationUs, error = null, skippedAssets = built.skippedAssets, renderWidth = w, renderHeight = h)
        } catch (e: Exception) {
            _state.value = _state.value.copy(error = "Aperçu impossible : ${e.message}")
        }
    }

    fun play() {
        val p = player ?: return
        if (p.playerError != null) {
            // the player stopped on an error: rebuild at the current position and resume
            recoveries = 0
            _state.value = _state.value.copy(error = null)
            val (w, h) = previewSize(live.project)
            structure = ""
            rebuild(StructureKey.of(live.project, w, h, live.project.format.frameRate), resume = true)
            return
        }
        if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
        else {
            val pos = (pendingSeekMs ?: p.currentPosition) * 1000
            val start = safeStart(pos)
            if (start != pos) { pendingSeekMs = null; seeking = true; p.seekTo(start / 1000) }
        }
        p.play()
    }
    fun pause() { player?.pause() }
    fun toggle() { if (player?.isPlaying == true) pause() else play() }
    /**
     * Media3 1.11 freezes for good when playback starts on the last frames of an item
     * (androidx/media#3452) — typically right after a split, the playhead sitting on the cut.
     * Starting exactly on the next boundary avoids it (a jump of at most a few frames).
     */
    private fun safeStart(posUs: Long): Long = boundariesUs.firstOrNull { it > posUs && it - posUs <= 120_000 } ?: posUs

    /**
     * Seeks are coalesced: while one is being decoded, only the latest requested position is
     * kept. Scrubbing the timeline therefore never queues dozens of 4K decodes.
     */
    fun seekTo(us: Long) {
        val p = player ?: return
        val ms = (us / 1000).coerceAtLeast(0)
        _state.value = _state.value.copy(positionUs = us)
        if (seeking && p.playbackState == Player.STATE_BUFFERING) { pendingSeekMs = ms; return }
        pendingSeekMs = null
        seeking = true
        p.seekTo(ms)
        // seeking to the current position may not change the state: never wait on it forever
        main.postDelayed({ if (player === p && seeking && p.playbackState != Player.STATE_BUFFERING) onSeekSettled(p) }, 150)
    }
    fun setLoop(range: LongRange?) { loopRange = range }
    fun clearError() { _state.value = _state.value.copy(error = null) }

    fun release() {
        main.removeCallbacksAndMessages(null)
        tickerRunning = false
        player?.release()
        player = null
        seeking = false; pendingSeekMs = null
        structure = ""
    }

    companion object {
        private const val TAG = "CuttinxPreview"
        /** Buffering longer than this is considered a stall (heavy 4K sources need a few seconds). */
        private const val STALL_MS = 7_000L

        /** Deepest useful message of the cause chain (shown to help diagnose device-specific failures). */
        private fun rootCause(e: Throwable): String? {
            var t: Throwable? = e.cause; var msg: String? = null
            while (t != null) { t.message?.takeIf { it.isNotBlank() }?.let { msg = it }; t = t.cause }
            return msg?.take(160)
        }

        fun friendly(e: PlaybackException): String = when (e.errorCode) {
            PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED, PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED ->
                "Le rendu GPU de l'aperçu a échoué" + (rootCause(e)?.let { " ($it)" } ?: "") + ". Touchez Réessayer ; baissez la qualité d'aperçu si cela persiste."
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED -> "Le décodeur de l'appareil ne peut pas lire un des médias (codec non pris en charge ou trop de vidéos simultanées). Essayez une qualité d'aperçu plus basse."
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> "Format vidéo non pris en charge par cet appareil."
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "Un média est introuvable ou l'accès a été retiré. Remplacez-le depuis la bibliothèque."
            else -> "Erreur de lecture : ${e.errorCodeName}" + (rootCause(e)?.let { " — $it" } ?: "")
        }
    }
}
