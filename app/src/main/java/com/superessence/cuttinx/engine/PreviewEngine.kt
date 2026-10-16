package com.superessence.cuttinx.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
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

    private val ticker = object : Runnable {
        override fun run() {
            val p = player ?: return
            val pos = p.currentPosition * 1000
            loopRange?.let { r -> if (p.isPlaying && pos >= r.last) p.seekTo(r.first / 1000) }
            _state.value = _state.value.copy(positionUs = pos, playing = p.isPlaying)
            main.postDelayed(this, 33)
        }
    }

    private fun ensurePlayer(): CompositionPlayer {
        player?.let { return it }
        val p = CompositionPlayer.Builder(context)
            .setVideoGraphFactory(MultipleInputVideoGraph.Factory())
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                _state.value = _state.value.copy(error = friendly(error), playing = false)
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                _state.value = _state.value.copy(buffering = playbackState == Player.STATE_BUFFERING)
                if (playbackState == Player.STATE_ENDED) _state.value = _state.value.copy(playing = false)
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { _state.value = _state.value.copy(playing = isPlaying) }
        })
        surface?.let { p.setVideoSurfaceView(it) }
        player = p
        main.post(ticker)
        return p
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
            if (!pl.isPlaying) pl.seekTo(pl.currentPosition)
            return
        }
        pending?.let { main.removeCallbacks(it) }
        val r = Runnable { rebuild(key) }
        pending = r
        if (immediate || structure.isEmpty()) main.post(r) else main.postDelayed(r, 220)
    }

    private fun rebuild(key: String) {
        pending = null
        val p = live.project
        val pl = ensurePlayer()
        if (p.durationUs <= 0) {
            structure = key
            pl.stop()
            _state.value = _state.value.copy(durationUs = 0, positionUs = 0, playing = false)
            return
        }
        val wasPlaying = pl.isPlaying
        val pos = (pl.currentPosition * 1000).coerceIn(0, max(0, p.durationUs - 1000))
        val (w, h) = previewSize(p)
        try {
            val built = factory.build(live, w, h, p.format.frameRate, preview = true)
            structure = key
            pl.setComposition(built.composition, pos / 1000)
            pl.prepare()
            pl.playWhenReady = wasPlaying
            _state.value = _state.value.copy(durationUs = built.durationUs, error = null, skippedAssets = built.skippedAssets, renderWidth = w, renderHeight = h)
        } catch (e: Exception) {
            _state.value = _state.value.copy(error = "Aperçu impossible : ${e.message}")
        }
    }

    fun play() { val p = player ?: return; if (p.playbackState == Player.STATE_ENDED) p.seekTo(0); p.play() }
    fun pause() { player?.pause() }
    fun toggle() { if (player?.isPlaying == true) pause() else play() }
    fun seekTo(us: Long) {
        val p = player ?: return
        p.seekTo((us / 1000).coerceAtLeast(0))
        _state.value = _state.value.copy(positionUs = us)
    }
    fun setLoop(range: LongRange?) { loopRange = range }
    fun clearError() { _state.value = _state.value.copy(error = null) }

    fun release() {
        main.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        structure = ""
    }

    companion object {
        fun friendly(e: PlaybackException): String = when (e.errorCode) {
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED -> "Le décodeur de l'appareil ne peut pas lire un des médias (codec non pris en charge ou trop de vidéos simultanées). Essayez une qualité d'aperçu plus basse."
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> "Format vidéo non pris en charge par cet appareil."
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "Un média est introuvable ou l'accès a été retiré. Remplacez-le depuis la bibliothèque."
            else -> "Erreur de lecture : ${e.errorCodeName}"
        }
    }
}
