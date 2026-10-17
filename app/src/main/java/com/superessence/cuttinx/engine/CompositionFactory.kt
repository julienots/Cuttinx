package com.superessence.cuttinx.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.OverlaySettings
import androidx.media3.common.SpeedParameters
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.Crop
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.google.common.collect.ImmutableList
import com.superessence.cuttinx.core.anim.KeyframeEvaluator
import com.superessence.cuttinx.core.anim.SpeedMath
import com.superessence.cuttinx.core.audio.AudioChainFactory
import com.superessence.cuttinx.core.audio.DspChain
import com.superessence.cuttinx.core.audio.GainPan
import com.superessence.cuttinx.core.effects.EffectRegistry
import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.render.FrameEvaluator
import com.superessence.cuttinx.core.render.Lane
import com.superessence.cuttinx.core.render.LanePlanner
import com.superessence.cuttinx.core.render.OverlayAnchors
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Project shared with the rendering threads. Non structural edits (transforms, keyframes, effect
 * parameters, volume...) are applied by swapping [project]; the GL/audio threads read the new
 * values on the next frame without rebuilding the composition.
 */
class LiveProject(initial: Project) {
    @Volatile var project: Project = initial
        set(value) { field = value; clips = index(value) }
    @Volatile private var clips: Map<String, Clip> = index(initial)
    private fun index(p: Project) = p.allClips.associateBy { it.id }
    fun clip(id: String): Clip? = clips[id]
}

/** Resolves project assets to playable URIs. */
fun interface MediaResolver { fun uri(asset: MediaAsset): Uri? }

data class BuiltComposition(val composition: Composition, val width: Int, val height: Int, val durationUs: Long, val skippedAssets: List<String>)

/**
 * Converts a CUTTINX [Project] into a Media3 [Composition].
 *
 * Sequence order (Media3 draws sequence 0 on top):
 *  0      : time base (transparent canvas at the output size) carrying titles & subtitles
 *  1..n   : video lanes from the top-most track to the main track
 *  n+1    : optional background colour
 *  then   : audio lanes
 * The compositor settings place every lane on the canvas for every frame (position, scale,
 * rotation, opacity, transitions), so preview and export share exactly the same rendering.
 */
@UnstableApi
class CompositionFactory(private val context: Context, private val resolver: MediaResolver, private val fonts: (String) -> File? = { null }) {

    /**
     * @param gpuEffects false builds a "safe" composition without CUTTINX shaders (effects,
     *   transitions shaders, masks, chroma key): used by the preview to recover on devices whose
     *   GPU driver rejects one of them.
     */
    fun build(live: LiveProject, outW: Int, outH: Int, fps: Int, preview: Boolean, gpuEffects: Boolean = true): BuiltComposition {
        val p = live.project
        val plan = LanePlanner.plan(p)
        val total = max(plan.durationUs, 100_000L)
        val skipped = ArrayList<String>()
        val sequences = ArrayList<EditedMediaItemSequence>()
        val videoLanes = plan.videoLanes.filter { !it.trackHidden }.reversed() // top-most first
        val laneOrder = ArrayList<Lane?>() // index = compositor input index
        // 0. time base + texts
        sequences += timeBase(live, plan, total, outW, outH, fps)
        laneOrder += null
        // 1. video lanes
        for (lane in videoLanes) {
            sequences += videoSequence(live, lane, total, outW, outH, fps, skipped, gpuEffects)
            laneOrder += lane
        }
        // 2. background
        val bg = p.format.backgroundColor
        val hasBg = (bg and 0xFFFFFF) != 0L
        if (hasBg) {
            sequences += EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO)).addItem(imageItem(colorImage(bg), total, fps, listOf(Presentation.createForWidthAndHeight(outW, outH, Presentation.LAYOUT_STRETCH_TO_FIT)))).build()
            laneOrder += null
        }
        val videoInputs = sequences.size
        // 3. audio lanes
        for (lane in plan.audioLanes) {
            if (lane.trackMuted) continue
            audioSequence(live, lane, total, skipped)?.let { sequences += it }
        }
        val compositor = Compositor(live, laneOrder, outW, outH)
        val compEffects = if (gpuEffects) adjustmentEffects(live, plan.adjustmentClips) else ImmutableList.of()
        val composition = Composition.Builder(sequences)
            .setVideoCompositorSettings(compositor)
            .setEffects(Effects(ImmutableList.of(), compEffects))
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()
        return BuiltComposition(composition, outW, outH, total, skipped.distinct())
    }

    // ------------------------------------------------------------------ time base & texts

    private fun timeBase(live: LiveProject, plan: LanePlanner.Plan, total: Long, outW: Int, outH: Int, fps: Int): EditedMediaItemSequence {
        val overlays = ArrayList<TextureOverlay>()
        for ((_, clip) in plan.textClips) overlays += TextClipOverlay(live, clip, outW, outH, typefaceFor(clip))
        val effects = ArrayList<Effect>()
        effects += Presentation.createForWidthAndHeight(outW, outH, Presentation.LAYOUT_STRETCH_TO_FIT)
        if (overlays.isNotEmpty()) effects += OverlayEffect(ImmutableList.copyOf(overlays))
        val item = imageItem(transparentImage(), total, fps, effects)
        return EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO)).addItem(item).build()
    }

    private fun typefaceFor(c: Clip): Typeface = TextRenderer.typeface(c.text!!.style, fonts, context.assets)

    /** A text/subtitle clip drawn on the time base, with in/out animations and keyframes. */
    private inner class TextClipOverlay(private val live: LiveProject, private val base: Clip, private val outW: Int, private val outH: Int, private val tf: Typeface) : BitmapOverlay() {
        private val clip: Clip get() = live.clip(base.id) ?: base
        private val empty = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        private val cache = LinkedHashMap<Int, Bitmap>()
        private var lastSettings: OverlaySettings = hidden()

        private fun hidden(): OverlaySettings = StaticOverlaySettings.Builder().setAlphaScale(0f).build()

        override fun getBitmap(presentationTimeUs: Long): Bitmap =
            try { bitmapAt(presentationTimeUs) } catch (t: Throwable) { empty }

        private fun bitmapAt(presentationTimeUs: Long): Bitmap {
            if (!clip.contains(presentationTimeUs)) return empty
            val rel = presentationTimeUs - clip.startUs
            val reveal = FrameEvaluator.textAnimation(clip, rel).reveal
            val key = if (reveal >= 1.0) -1 else (reveal * 1000).toInt() / 20
            cache[key]?.let { return it }
            val bmp = TextRenderer.render(clip.text!!, outW, outH, reveal, tf)
            // Evicted bitmaps are left to the GC: Media3 may still hold the last one for upload.
            if (cache.size > 12) cache.remove(cache.keys.first())
            cache[key] = bmp
            return bmp
        }

        // Never let a bad value escape to the GL thread: one exception stops the whole preview/export.
        override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings =
            try { settings(presentationTimeUs) } catch (t: Throwable) { hidden() }

        private fun settings(presentationTimeUs: Long): OverlaySettings {
            if (!clip.contains(presentationTimeUs)) return hidden().also { lastSettings = it }
            val rel = presentationTimeUs - clip.startUs
            val t = FrameEvaluator.transform(clip, rel)
            val a = FrameEvaluator.textAnimation(clip, rel)
            val sx = t.scaleX * a.scale * (if (clip.transform.flipH) -1 else 1)
            val sy = t.scaleY * a.scale * (if (clip.transform.flipV) -1 else 1)
            val bmp = getBitmap(presentationTimeUs)
            val anchors = OverlayAnchors.solve(
                (t.posX + a.dx) * 2 - 1, 1 - (t.posY + a.dy) * 2, 0.0, 0.0,
                bmp.width.toDouble() / outW * sx, bmp.height.toDouble() / outH * sy, t.rotation + a.rotation,
            ) ?: return hidden().also { lastSettings = it }
            return StaticOverlaySettings.Builder()
                .setBackgroundFrameAnchor(anchors.bgX, anchors.bgY)
                .setOverlayFrameAnchor(anchors.ovX, anchors.ovY)
                .setScale(sx.toFloat(), sy.toFloat())
                .setRotationDegrees((-(t.rotation + a.rotation)).toFloat())
                .setAlphaScale(safeAlpha(t.opacity * a.alpha))
                .build().also { lastSettings = it }
        }
    }

    // ------------------------------------------------------------------ video

    private fun videoSequence(live: LiveProject, lane: Lane, total: Long, outW: Int, outH: Int, fps: Int, skipped: MutableList<String>, gpuEffects: Boolean): EditedMediaItemSequence {
        val p = live.project
        val wantsAudio = !lane.trackMuted && lane.clips.any { c -> c.kind == ClipKind.MEDIA && p.asset(c.assetId)?.hasAudio == true && !c.audio.muted }
        val types = if (wantsAudio) setOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO) else setOf(C.TRACK_TYPE_VIDEO)
        val b = EditedMediaItemSequence.Builder(types)
        val trackClips = p.track(lane.trackId)?.sortedClips()?.filter { it.enabled } ?: lane.clips
        val laneOf = LanePlanner.partition(trackClips).flatMapIndexed { li, l -> l.map { it.id to li } }.toMap()
        for ((start, dur, clip) in LanePlanner.withGaps(lane, total)) {
            if (clip == null) { b.addGap(dur); continue }
            val item = clipItem(live, clip, trackClips, laneOf, outW, outH, fps, wantsAudio, lane.trackId, gpuEffects)
            if (item == null) { skipped += clip.assetId ?: clip.id; b.addGap(dur) } else b.addItem(item)
        }
        return b.build()
    }

    /** Size of the frames that a clip delivers to the compositor (deterministic). */
    fun itemSize(p: Project, c: Clip, outW: Int, outH: Int): Pair<Int, Int> = itemSizeStatic(p, c, outW, outH)

    private fun clipItem(live: LiveProject, c: Clip, trackClips: List<Clip>, laneOf: Map<String, Int>, outW: Int, outH: Int, fps: Int, laneHasAudio: Boolean, trackId: String, gpuEffects: Boolean): EditedMediaItem? {
        val p = live.project
        fun lc(): Clip = live.clip(c.id) ?: c
        fun trackVolume() = live.project.track(trackId)?.volume ?: 1.0
        val effects = ArrayList<Effect>()
        val audio = ArrayList<AudioProcessor>()
        val asset = p.asset(c.assetId)
        val isImage = c.kind == ClipKind.COLOR || asset?.type == MediaType.IMAGE || asset?.type == MediaType.STICKER
        val uri: Uri = when {
            c.kind == ClipKind.COLOR -> colorImage(c.color)
            asset == null || asset.missing -> return null
            else -> resolver.uri(asset) ?: return null
        }
        // geometry: crop -> deterministic size
        val cr = c.transform.crop
        if (!cr.isIdentity) effects += Crop((-1 + 2 * cr.left).toFloat(), (1 - 2 * cr.right).toFloat(), (-1 + 2 * cr.bottom).toFloat(), (1 - 2 * cr.top).toFloat())
        val (iw, ih) = itemSize(p, c, outW, outH)
        effects += Presentation.createForWidthAndHeight(iw, ih, Presentation.LAYOUT_STRETCH_TO_FIT)
        // chroma key
        if (gpuEffects && c.chromaKey.enabled) {
            effects += ShaderEffect("chroma", EffectRegistry["_chroma_key"]!!.glsl) {
                val ck = lc().chromaKey
                if (!ck.enabled) null else FrameUniforms(FloatArray(16).also {
                    it[0] = ((ck.color shr 16) and 0xFF) / 255f; it[1] = ((ck.color shr 8) and 0xFF) / 255f; it[2] = (ck.color and 0xFF) / 255f
                    it[3] = ck.tolerance.toFloat(); it[4] = ck.softness.toFloat(); it[5] = ck.spill.toFloat()
                }, 0f)
            }
        }
        // user effects
        for (fx in c.effects) {
            if (!gpuEffects || !fx.enabled) continue
            val def = EffectRegistry[fx.effectId] ?: continue
            if (def.kind != com.superessence.cuttinx.core.effects.EffectKind.VIDEO) continue
            effects += ShaderEffect(def.id, def.glsl) { pts ->
                val cur = lc()
                val f = cur.effects.firstOrNull { it.id == fx.id }
                if (f == null || !f.enabled) null
                else { val rel = local(cur, pts); FrameUniforms(FrameEvaluator.shaderParams(def.id, FrameEvaluator.effectParams(cur, f, rel)), rel / 1e6f) }
            }
        }
        // transition layer shaders
        val idx = trackClips.indexOfFirst { it.id == c.id }
        if (gpuEffects && idx >= 0) {
            val shaderIds = setOfNotNull(c.transitionIn?.transitionId, trackClips.getOrNull(idx + 1)?.transitionIn?.transitionId)
                .flatMap { tid -> listOf(0.0, 0.5, 1.0).flatMap { pr -> com.superessence.cuttinx.core.effects.TransitionRegistry.evaluate(tid, pr, true).toList() + com.superessence.cuttinx.core.effects.TransitionRegistry.evaluate(tid, pr, false).toList() }.mapNotNull { it.shaderId } }.toSet()
            for (sid in shaderIds) {
                val def = EffectRegistry[sid] ?: continue
                effects += ShaderEffect(sid, def.glsl) { pts ->
                    val mod = FrameEvaluator.transitionMod(trackClips, idx, c.startUs + local(c, pts)) { laneOf[it.id] ?: 0 }
                    if (mod?.shaderId != sid) null
                    else FrameUniforms(FrameEvaluator.shaderParams(sid, mod.shaderParams), local(c, pts) / 1e6f, mod.shaderProgress.toFloat())
                }
            }
        }
        // mask
        if (gpuEffects && c.mask.shape != MaskShape.NONE) {
            effects += ShaderEffect("mask", EffectRegistry["_mask"]!!.glsl) { pts ->
                val c = lc()
                val m = c.mask
                if (m.shape == MaskShape.NONE) return@ShaderEffect null
                val rel = local(c, pts)
                fun k(prop: String, d: Double) = KeyframeEvaluator.evaluate(c.keyframes[prop], rel, d).toFloat()
                val arr = FloatArray(16)
                arr[0] = when (m.shape) { MaskShape.RECTANGLE -> 1f; MaskShape.ELLIPSE -> 2f; MaskShape.LINEAR -> 3f; MaskShape.MIRROR -> 4f; else -> 1f }
                arr[1] = k(Prop.MASK_X, m.centerX); arr[2] = k(Prop.MASK_Y, m.centerY); arr[3] = k(Prop.MASK_W, m.width); arr[4] = k(Prop.MASK_H, m.height)
                arr[5] = m.rotation.toFloat(); arr[6] = k(Prop.MASK_FEATHER, m.feather); arr[7] = if (m.invert) 1f else 0f
                FrameUniforms(arr, rel / 1e6f)
            }
        }
        // audio
        val clipHasAudio = !isImage && asset?.hasAudio == true && !c.audio.muted
        if (clipHasAudio) {
            val semis = AudioChainFactory.pitchSemitones(c)
            if (abs(semis) > 0.01) audio += SonicAudioProcessor().apply { setPitch(2.0.pow(semis / 12).toFloat()) }
            audio += ClipAudioProcessor({ liveChain(::lc, ::trackVolume) }, c.durationUs, listOf(c.startUs, c.sourceInUs))
        }
        val mediaItem = if (isImage) {
            MediaItem.Builder().setUri(uri).setImageDurationMs(max(1, c.durationUs / 1000)).build()
        } else {
            MediaItem.Builder().setUri(uri).setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionUs(c.sourceInUs).setEndPositionUs(c.sourceOutUs).build()
            ).build()
        }
        val b = EditedMediaItem.Builder(mediaItem)
            .setEffects(Effects(ImmutableList.copyOf(audio), ImmutableList.copyOf(effects)))
            .setFrameRate(fps)
        if (isImage) b.setDurationUs(max(1, c.durationUs))
        else {
            b.setDurationUs(max(asset!!.durationUs, c.sourceOutUs))
            if (laneHasAudio && !clipHasAudio && asset.hasAudio) b.setRemoveAudio(true)
            if (c.speedCurve.isNotEmpty() || abs(c.speed - 1.0) > 1e-6) b.setSpeed(SpeedParameters(ClipSpeedProvider(c), c.audio.preservePitch))
        }
        return b.build()
    }

    /** Converts a frame timestamp to a clip-relative time (sequence time == timeline time). */
    private fun local(c: Clip, pts: Long): Long =
        if (pts >= c.startUs - 100_000 && pts <= c.endUs + 100_000) (pts - c.startUs).coerceIn(0, c.durationUs) else pts.coerceIn(0, c.durationUs)

    private class ClipSpeedProvider(private val c: Clip) : SpeedProvider {
        override fun getSpeed(timeUs: Long): Float = SpeedMath.speedAtSource(c, c.sourceInUs + timeUs).toFloat()
        override fun getNextSpeedChangeTimeUs(timeUs: Long): Long =
            SpeedMath.nextSpeedChangeSource(c, c.sourceInUs + timeUs)?.let { it - c.sourceInUs } ?: C.TIME_UNSET
    }

    // ------------------------------------------------------------------ audio

    private fun audioSequence(live: LiveProject, lane: Lane, total: Long, skipped: MutableList<String>): EditedMediaItemSequence? {
        val p = live.project
        val b = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
        var any = false
        for ((_, dur, c) in LanePlanner.withGaps(lane, total)) {
            if (c == null) { b.addGap(dur); continue }
            val a = p.asset(c.assetId)
            val uri = if (a == null || a.missing) null else resolver.uri(a)
            if (uri == null || c.audio.muted) { if (uri == null) skipped += c.assetId ?: c.id; b.addGap(dur); continue }
            val procs = ArrayList<AudioProcessor>()
            val semis = AudioChainFactory.pitchSemitones(c)
            if (abs(semis) > 0.01) procs += SonicAudioProcessor().apply { setPitch(2.0.pow(semis / 12).toFloat()) }
            procs += ClipAudioProcessor({ liveChain({ live.clip(c.id) ?: c }, { live.project.track(lane.trackId)?.volume ?: lane.trackVolume }) }, c.durationUs, listOf(c.startUs, c.sourceInUs))
            val mi = MediaItem.Builder().setUri(uri).setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionUs(c.sourceInUs).setEndPositionUs(c.sourceOutUs).build()).build()
            val eb = EditedMediaItem.Builder(mi).setEffects(Effects(ImmutableList.copyOf(procs), ImmutableList.of())).setDurationUs(max(a!!.durationUs, c.sourceOutUs))
            if (c.speedCurve.isNotEmpty() || abs(c.speed - 1.0) > 1e-6) eb.setSpeed(SpeedParameters(ClipSpeedProvider(c), c.audio.preservePitch))
            b.addItem(eb.build()); any = true
        }
        return if (any) b.build() else null
    }

    /** Effects chain (rebuilt on seek) + gain/pan automation read live on every buffer. */
    private fun liveChain(clip: () -> Clip, trackVolume: () -> Double): DspChain {
        val fx = AudioChainFactory.build(clip(), includeGain = false)
        return DspChain(fx.processors + GainPan({ t -> FrameEvaluator.audioGain(clip(), t, trackVolume()) }, { t -> FrameEvaluator.audioPan(clip(), t) }))
    }

    // ------------------------------------------------------------------ adjustment layers

    private fun adjustmentEffects(live: LiveProject, clips: List<Clip>): ImmutableList<Effect> {
        val out = ArrayList<Effect>()
        for (c0 in clips) for (fx in c0.effects) {
            if (!fx.enabled) continue
            val def = EffectRegistry[fx.effectId] ?: continue
            if (def.kind != com.superessence.cuttinx.core.effects.EffectKind.VIDEO) continue
            out += ShaderEffect("adj_${def.id}", def.glsl) { pts ->
                val c = live.clip(c0.id) ?: c0
                val f = c.effects.firstOrNull { it.id == fx.id }
                if (!c.contains(pts) || f == null || !f.enabled) null
                else { val rel = pts - c.startUs; FrameUniforms(FrameEvaluator.shaderParams(def.id, FrameEvaluator.effectParams(c, f, rel)), rel / 1e6f) }
            }
        }
        return ImmutableList.copyOf(out)
    }

    // ------------------------------------------------------------------ compositor

    /** Places every lane on the output canvas for every frame. */
    private class Compositor(private val live: LiveProject, private val lanes: List<Lane?>, private val outW: Int, private val outH: Int) : VideoCompositorSettings {
        private val p = live.project
        private val hidden: OverlaySettings = StaticOverlaySettings.Builder().setAlphaScale(0f).build()
        private val full: OverlaySettings = StaticOverlaySettings.Builder().build()
        private val trackClips = lanes.filterNotNull().map { it.trackId }.distinct().associateWith { id -> p.track(id)?.sortedClips()?.filter { it.enabled } ?: emptyList() }
        private val laneMaps = trackClips.mapValues { (_, clips) -> LanePlanner.partition(clips).flatMapIndexed { li, l -> l.map { it.id to li } }.toMap() }
        private val sizeCache = HashMap<String, Pair<Int, Int>>()

        override fun getOutputSize(inputSizes: MutableList<Size>): Size = Size(outW, outH)

        override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings =
            try { settings(inputId, presentationTimeUs) } catch (t: Throwable) { hidden }

        private fun settings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
            if (inputId !in lanes.indices) return full
            val lane = lanes[inputId] ?: return full
            val base = lane.clips.firstOrNull { it.contains(presentationTimeUs) } ?: return hidden
            val clip = live.clip(base.id) ?: base
            val clips = trackClips[lane.trackId] ?: return hidden
            val idx = clips.indexOfFirst { it.id == clip.id }
            val rel = presentationTimeUs - clip.startUs
            val lt = FrameEvaluator.transform(clip, rel)
            val mod = if (idx >= 0) FrameEvaluator.transitionMod(clips, idx, presentationTimeUs) { laneMaps[lane.trackId]?.get(it.id) ?: 0 } else null
            val (iw, ih) = sizeCache.getOrPut(clip.id) { itemSizeStatic(p, clip, outW, outH) }
            val pl = FrameEvaluator.placement(iw, ih, outW, outH, clip.transform, lt, mod)
            val alpha = safeAlpha(lt.opacity * (mod?.alpha ?: 1.0))
            if (alpha <= 0.001f) return hidden
            // Media3 rejects anchors outside [-1, 1]: layers sliding out of the frame are re-expressed
            // with in-range anchors (or hidden when entirely off-screen).
            val anchors = OverlayAnchors.solve(
                pl.centerX / outW * 2 - 1, 1 - pl.centerY / outH * 2,
                clip.transform.anchorX * 2 - 1, 1 - clip.transform.anchorY * 2,
                iw.toDouble() / outW * pl.scaleX, ih.toDouble() / outH * pl.scaleY, pl.rotation,
            ) ?: return hidden
            return StaticOverlaySettings.Builder()
                .setBackgroundFrameAnchor(anchors.bgX, anchors.bgY)
                .setOverlayFrameAnchor(anchors.ovX, anchors.ovY)
                .setScale(pl.scaleX.toFloat(), pl.scaleY.toFloat())
                .setRotationDegrees((-pl.rotation).toFloat())
                .setAlphaScale(alpha)
                .build()
        }
    }

    // ------------------------------------------------------------------ generated images

    private fun imageItem(uri: Uri, durUs: Long, fps: Int, effects: List<Effect>): EditedMediaItem =
        EditedMediaItem.Builder(MediaItem.Builder().setUri(uri).setImageDurationMs(max(1, durUs / 1000)).build())
            .setDurationUs(max(1, durUs)).setFrameRate(fps)
            .setEffects(Effects(ImmutableList.of(), ImmutableList.copyOf(effects))).build()

    private fun transparentImage(): Uri = generated("transparent.png") { Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).also { it.eraseColor(0) } }

    private fun colorImage(color: Long): Uri = generated("color_%08x.png".format(color.toInt())) {
        Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).also { it.eraseColor(color.toInt()) }
    }

    private fun generated(name: String, make: () -> Bitmap): Uri {
        val dir = File(context.cacheDir, "generated").also { it.mkdirs() }
        val f = File(dir, name)
        if (!f.exists()) {
            val bmp = make()
            val tmp = File(dir, "$name.tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            tmp.renameTo(f)
        }
        return Uri.fromFile(f)
    }

    companion object {
        /** Opacity accepted by Media3 (NaN or negative values throw in StaticOverlaySettings). */
        fun safeAlpha(v: Double): Float = if (v.isNaN()) 0f else v.toFloat().coerceIn(0f, 1f)

        /** Same as [itemSize] without an instance (used by the compositor). */
        fun itemSizeStatic(p: Project, c: Clip, outW: Int, outH: Int): Pair<Int, Int> {
            val (nw, nh) = when (c.kind) {
                ClipKind.COLOR -> outW to outH
                else -> p.asset(c.assetId)?.let { a -> (if (a.displayWidth > 0) a.displayWidth else outW) to (if (a.displayHeight > 0) a.displayHeight else outH) } ?: (outW to outH)
            }
            val cr = c.transform.crop
            val cw = max(2.0, nw * (1 - cr.left - cr.right)); val ch = max(2.0, nh * (1 - cr.top - cr.bottom))
            // headroom so that zooming up to 1.6x keeps full detail; independent of the user scale
            // so that scaling stays a live (non structural) edit.
            val fit = if (c.transform.fill) max(outW / cw, outH / ch) else min(outW / cw, outH / ch)
            val k = min(1.0, fit * 1.6)
            fun even(v: Double) = max(2, (v / 2).roundToInt() * 2)
            return even(cw * k) to even(ch * k)
        }
    }
}
