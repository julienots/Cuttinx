package com.superessence.cuttinx.core.render

import com.superessence.cuttinx.core.anim.Interpolation
import com.superessence.cuttinx.core.anim.KeyframeEvaluator
import com.superessence.cuttinx.core.anim.SpeedMath
import com.superessence.cuttinx.core.effects.EffectRegistry
import com.superessence.cuttinx.core.effects.LayerMod
import com.superessence.cuttinx.core.effects.TransitionRegistry
import com.superessence.cuttinx.core.model.*
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Evaluated spatial state of a visual layer (all values final, keyframes applied). */
data class LayerTransform(
    val posX: Double, val posY: Double,
    val scaleX: Double, val scaleY: Double,
    val rotation: Double, val opacity: Double,
)

/** Placement of a layer on the canvas in pixels. scale = factor applied to the native pixel size. */
data class Placement(val centerX: Double, val centerY: Double, val scaleX: Double, val scaleY: Double, val rotation: Double, val width: Double, val height: Double)

data class TextAnimState(val alpha: Double = 1.0, val dx: Double = 0.0, val dy: Double = 0.0, val scale: Double = 1.0, val rotation: Double = 0.0, val reveal: Double = 1.0)

data class LayerState(
    val clip: Clip,
    val trackId: String,
    val trackIndex: Int,
    val localUs: Long,
    val sourceUs: Long,
    val transform: LayerTransform,
    val transition: LayerMod?,
)

object FrameEvaluator {

    /** Base transform of a clip at clip-relative time [relUs]. */
    fun transform(c: Clip, relUs: Long): LayerTransform {
        val t = c.transform
        val k = c.keyframes
        val uniform = KeyframeEvaluator.evaluate(k[Prop.SCALE], relUs, 1.0)
        return LayerTransform(
            posX = KeyframeEvaluator.evaluate(k[Prop.POS_X], relUs, t.posX),
            posY = KeyframeEvaluator.evaluate(k[Prop.POS_Y], relUs, t.posY),
            scaleX = KeyframeEvaluator.evaluate(k[Prop.SCALE_X], relUs, t.scaleX) * uniform,
            scaleY = KeyframeEvaluator.evaluate(k[Prop.SCALE_Y], relUs, t.scaleY) * uniform,
            rotation = KeyframeEvaluator.evaluate(k[Prop.ROTATION], relUs, t.rotation),
            opacity = KeyframeEvaluator.evaluate(k[Prop.OPACITY], relUs, t.opacity).coerceIn(0.0, 1.0),
        )
    }

    /** Effect parameters with keyframes applied. */
    fun effectParams(c: Clip, fx: EffectInstance, relUs: Long): Map<String, Double> {
        val def = EffectRegistry[fx.effectId] ?: return fx.params
        return def.params.associate { pd ->
            val base = fx.params[pd.key] ?: pd.default
            pd.key to KeyframeEvaluator.evaluate(c.keyframes[Prop.effect(fx.id, pd.key)], relUs, base)
        }
    }

    /** Ordered param values for the shader `uP[]` array. */
    fun shaderParams(effectId: String, values: Map<String, Double>): FloatArray {
        val def = EffectRegistry[effectId] ?: return FloatArray(16)
        val out = FloatArray(16)
        def.params.forEachIndexed { i, pd -> if (i < 16) out[i] = (values[pd.key] ?: pd.default).toFloat() }
        return out
    }

    /** Linear audio gain at clip-relative time, combining volume, keyframes, fades and normalisation. */
    fun audioGain(c: Clip, relUs: Long, trackVolume: Double = 1.0): Double {
        if (c.audio.muted) return 0.0
        var g = KeyframeEvaluator.evaluate(c.keyframes[Prop.VOLUME], relUs, c.audio.volume)
        g *= trackVolume
        g *= Math.pow(10.0, c.audio.normalizeGainDb / 20.0)
        val d = c.durationUs
        if (c.audio.fadeInUs > 0 && relUs < c.audio.fadeInUs) g *= (relUs.toDouble() / c.audio.fadeInUs).coerceIn(0.0, 1.0)
        if (c.audio.fadeOutUs > 0 && relUs > d - c.audio.fadeOutUs) g *= ((d - relUs).toDouble() / c.audio.fadeOutUs).coerceIn(0.0, 1.0)
        return g.coerceIn(0.0, 8.0)
    }

    fun audioPan(c: Clip, relUs: Long): Double = KeyframeEvaluator.evaluate(c.keyframes[Prop.PAN], relUs, c.audio.pan).coerceIn(-1.0, 1.0)

    /**
     * Places a visual of native size [w]x[h] on a [cw]x[ch] canvas.
     * Scale 1 = contain (or cover when [Transform.fill]).
     */
    fun placement(w: Int, h: Int, cw: Int, ch: Int, tr: Transform, lt: LayerTransform, mod: LayerMod? = null): Placement {
        val sw = max(w, 1).toDouble(); val sh = max(h, 1).toDouble()
        val base = if (tr.fill) max(cw / sw, ch / sh) else min(cw / sw, ch / sh)
        val m = mod ?: LayerMod()
        val sx = base * lt.scaleX * m.scale * (if (tr.flipH) -1 else 1)
        val sy = base * lt.scaleY * m.scale * (if (tr.flipV) -1 else 1)
        return Placement(
            centerX = (lt.posX + m.dx) * cw, centerY = (lt.posY + m.dy) * ch,
            scaleX = sx, scaleY = sy, rotation = lt.rotation + m.rotation, width = sw, height = sh,
        )
    }

    /** In/out animation of a text clip. */
    fun textAnimation(c: Clip, relUs: Long): TextAnimState {
        val tc = c.text ?: return TextAnimState()
        val d = c.durationUs
        val inP = if (tc.animIn == TextAnimation.NONE || tc.animInUs <= 0) 1.0 else (relUs.toDouble() / tc.animInUs).coerceIn(0.0, 1.0)
        val outP = if (tc.animOut == TextAnimation.NONE || tc.animOutUs <= 0) 1.0 else ((d - relUs).toDouble() / tc.animOutUs).coerceIn(0.0, 1.0)
        val a = anim(tc.animIn, Interpolation.ease(Easing.EaseOut, inP), inP, relUs)
        val b = anim(tc.animOut, Interpolation.ease(Easing.EaseOut, outP), outP, relUs)
        return TextAnimState(a.alpha * b.alpha, a.dx + b.dx, a.dy + b.dy, a.scale * b.scale, a.rotation + b.rotation, min(a.reveal, b.reveal))
    }

    private fun anim(type: TextAnimation, e: Double, raw: Double, relUs: Long): TextAnimState = when (type) {
        TextAnimation.NONE -> TextAnimState()
        TextAnimation.FADE -> TextAnimState(alpha = e)
        TextAnimation.POP -> TextAnimState(alpha = min(1.0, raw * 3), scale = if (raw >= 1) 1.0 else Interpolation.cubicBezier(0.34, 1.56, 0.64, 1.0, raw))
        TextAnimation.SLIDE_UP -> TextAnimState(alpha = e, dy = (1 - e) * 0.08)
        TextAnimation.SLIDE_DOWN -> TextAnimState(alpha = e, dy = -(1 - e) * 0.08)
        TextAnimation.SLIDE_LEFT -> TextAnimState(alpha = e, dx = (1 - e) * 0.15)
        TextAnimation.SLIDE_RIGHT -> TextAnimState(alpha = e, dx = -(1 - e) * 0.15)
        TextAnimation.TYPEWRITER -> TextAnimState(reveal = raw)
        TextAnimation.ZOOM -> TextAnimState(alpha = e, scale = 0.3 + 0.7 * e)
        TextAnimation.BOUNCE -> TextAnimState(alpha = min(1.0, raw * 2), dy = if (raw >= 1) 0.0 else -0.06 * kotlin.math.abs(sin(raw * PI * 2.5)) * (1 - raw))
        TextAnimation.BLUR_IN -> TextAnimState(alpha = e, scale = 1.0 + (1 - e) * 0.15)
        TextAnimation.WAVE -> TextAnimState(alpha = e, rotation = (1 - e) * -12.0, dy = (1 - e) * 0.03)
    }

    /**
     * All visual layers active at [timeUs], bottom first. Video tracks are ordered with the first
     * VIDEO track at the bottom. Text/subtitle tracks are included above all video tracks.
     */
    fun visualLayers(p: Project, timeUs: Long): List<LayerState> {
        val out = ArrayList<LayerState>()
        val ordered = visualTrackOrder(p)
        for ((idx, t) in ordered.withIndex()) {
            if (t.hidden) continue
            val clips = t.sortedClips()
            val laneMap = LanePlanner.partition(clips).flatMapIndexed { li, l -> l.map { it.id to li } }.toMap()
            for ((i, c) in clips.withIndex()) {
                if (!c.enabled || !c.contains(timeUs)) continue
                val rel = timeUs - c.startUs
                val mod = transitionMod(clips, i, timeUs) { laneMap[it.id] ?: 0 }
                out += LayerState(c, t.id, idx, rel, SpeedMath.timelineToSource(c, rel), transform(c, rel), mod)
            }
        }
        return out
    }

    /**
     * Transition modification of clip [index] of [sorted] at [timeUs]. [laneOf] gives the
     * compositing lane of a clip (higher = drawn above) so the result is order independent.
     */
    fun transitionMod(sorted: List<Clip>, index: Int, timeUs: Long, laneOf: (Clip) -> Int): LayerMod? {
        val c = sorted[index]
        val rel = timeUs - c.startUs
        val tin = c.transitionIn
        if (tin != null && index > 0 && rel < tin.durationUs) {
            val prev = sorted[index - 1]
            val prog = Interpolation.ease(tin.easing, rel.toDouble() / tin.durationUs)
            return TransitionRegistry.evaluate(tin.transitionId, prog, laneOf(c) >= laneOf(prev), tin.params).second
        }
        val next = sorted.getOrNull(index + 1)
        val tout = next?.transitionIn
        if (next != null && tout != null && timeUs >= next.startUs) {
            val prog = Interpolation.ease(tout.easing, (timeUs - next.startUs).toDouble() / tout.durationUs)
            return TransitionRegistry.evaluate(tout.transitionId, prog, laneOf(next) >= laneOf(c), tout.params).first
        }
        return null
    }

    fun visualTrackOrder(p: Project): List<Track> =
        p.tracks.filter { it.kind == TrackKind.VIDEO } + p.tracks.filter { it.kind == TrackKind.EFFECT } +
            p.tracks.filter { it.kind == TrackKind.TEXT } + p.tracks.filter { it.kind == TrackKind.SUBTITLE }
}
