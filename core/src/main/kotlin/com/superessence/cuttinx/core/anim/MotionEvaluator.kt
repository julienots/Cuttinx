package com.superessence.cuttinx.core.anim

import com.superessence.cuttinx.core.model.ClipMotion
import com.superessence.cuttinx.core.model.LoopMotion
import com.superessence.cuttinx.core.model.MotionPreset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Offsets produced by a [ClipMotion] (positions in canvas fractions, rotation in degrees). */
data class MotionState(
    val alpha: Double = 1.0,
    val dx: Double = 0.0, val dy: Double = 0.0,
    val scaleX: Double = 1.0, val scaleY: Double = 1.0,
    val rotation: Double = 0.0,
) {
    operator fun plus(o: MotionState) = MotionState(alpha * o.alpha, dx + o.dx, dy + o.dy, scaleX * o.scaleX, scaleY * o.scaleY, rotation + o.rotation)
    val isIdentity get() = this == IDENTITY

    companion object { val IDENTITY = MotionState() }
}

/** Pure, deterministic evaluation of entry/exit/loop clip animations. */
object MotionEvaluator {

    fun evaluate(m: ClipMotion, relUs: Long, durationUs: Long): MotionState {
        if (m.isIdentity) return MotionState.IDENTITY
        val d = durationUs.coerceAtLeast(1)
        // entry and exit share the clip when it is shorter than both animations
        val inUs = min(m.inUs.coerceAtLeast(0), d / (if (m.animOut != MotionPreset.NONE) 2 else 1))
        val outUs = min(m.outUs.coerceAtLeast(0), d / (if (m.animIn != MotionPreset.NONE) 2 else 1))
        var s = MotionState.IDENTITY
        if (m.animIn != MotionPreset.NONE && inUs > 0 && relUs < inUs) s += preset(m.animIn, relUs.toDouble() / inUs, exit = false)
        if (m.animOut != MotionPreset.NONE && outUs > 0 && relUs > d - outUs) s += preset(m.animOut, ((d - relUs).toDouble() / outUs), exit = true)
        if (m.loop != LoopMotion.NONE) s += loop(m.loop, relUs / 1e6 * m.loopSpeed.coerceIn(0.05, 10.0), m.loopAmount.coerceIn(0.0, 5.0), relUs.toDouble() / d)
        return s
    }

    // ------------------------------------------------------------------ easing helpers

    private fun easeOutCubic(p: Double) = 1 - (1 - p).pow(3)
    private fun easeOutExpo(p: Double) = if (p >= 1) 1.0 else 1 - 2.0.pow(-10 * p)
    private fun backOut(p: Double): Double { val c1 = 1.70158; val c3 = c1 + 1; return 1 + c3 * (p - 1).pow(3) + c1 * (p - 1).pow(2) }
    private fun elasticOut(p: Double): Double = when {
        p <= 0 -> 0.0
        p >= 1 -> 1.0
        else -> 2.0.pow(-10 * p) * sin((p * 10 - 0.75) * (2 * PI / 3)) + 1
    }
    private fun bounceOut(p0: Double): Double {
        val n = 7.5625; val dd = 2.75
        var p = p0
        return when {
            p < 1 / dd -> n * p * p
            p < 2 / dd -> { p -= 1.5 / dd; n * p * p + 0.75 }
            p < 2.5 / dd -> { p -= 2.25 / dd; n * p * p + 0.9375 }
            else -> { p -= 2.625 / dd; n * p * p + 0.984375 }
        }
    }
    private fun hash(x: Double): Double { val v = sin(x * 127.1 + 311.7) * 43758.5453; return v - floor(v) }
    private fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t

    /**
     * @param p0 progress towards the resting state: 0 = start of the entry (or end of the exit), 1 = at rest.
     * @param exit directional offsets are mirrored so that an exit continues the movement of the entry.
     */
    fun preset(type: MotionPreset, p0: Double, exit: Boolean): MotionState {
        val p = p0.coerceIn(0.0, 1.0)
        val e = easeOutCubic(p)
        val dir = if (exit) -1.0 else 1.0
        val fadeQuick = min(1.0, p * 2.5)
        return when (type) {
            MotionPreset.NONE -> MotionState.IDENTITY
            MotionPreset.FADE -> MotionState(alpha = e)
            MotionPreset.ZOOM_IN -> MotionState(alpha = fadeQuick, scaleX = lerp(0.3, 1.0, e), scaleY = lerp(0.3, 1.0, e))
            MotionPreset.ZOOM_OUT -> MotionState(alpha = fadeQuick, scaleX = lerp(1.8, 1.0, e), scaleY = lerp(1.8, 1.0, e))
            MotionPreset.SPIN_ZOOM -> MotionState(alpha = e, scaleX = lerp(2.6, 1.0, e), scaleY = lerp(2.6, 1.0, e), rotation = -(1 - e) * 540 * dir)
            MotionPreset.POP -> { val k = backOut(p); MotionState(alpha = min(1.0, p * 4), scaleX = k, scaleY = k) }
            MotionPreset.BOUNCE -> { val k = bounceOut(p); MotionState(alpha = min(1.0, p * 4), scaleX = k, scaleY = k) }
            MotionPreset.ELASTIC -> { val k = elasticOut(p); MotionState(alpha = min(1.0, p * 4), scaleX = k, scaleY = k) }
            MotionPreset.DROP -> MotionState(dy = -(1 - bounceOut(p)) * 1.1 * dir)
            MotionPreset.SLIDE_LEFT -> MotionState(dx = (1 - e) * 1.0 * dir)
            MotionPreset.SLIDE_RIGHT -> MotionState(dx = -(1 - e) * 1.0 * dir)
            MotionPreset.SLIDE_UP -> MotionState(dy = (1 - e) * 1.0 * dir)
            MotionPreset.SLIDE_DOWN -> MotionState(dy = -(1 - e) * 1.0 * dir)
            MotionPreset.WHIP -> { val k = easeOutExpo(p); MotionState(dx = (1 - k) * 1.2 * dir, scaleX = 1 + (1 - k) * 0.25) }
            MotionPreset.RISE -> MotionState(alpha = e, dy = (1 - e) * 0.3 * dir)
            MotionPreset.ROLL -> MotionState(dx = (1 - e) * 1.0 * dir, rotation = (1 - e) * 360 * dir)
            MotionPreset.SPIN -> MotionState(alpha = fadeQuick, scaleX = lerp(0.2, 1.0, e), scaleY = lerp(0.2, 1.0, e), rotation = -(1 - e) * 360 * dir)
            MotionPreset.SWING -> MotionState(alpha = min(1.0, p * 3), rotation = 35 * (1 - p).pow(2) * cos(p * PI * 3) * dir)
            MotionPreset.FLIP_H -> MotionState(scaleX = cos((1 - e) * PI / 2).coerceAtLeast(0.001))
            MotionPreset.FLIP_V -> MotionState(scaleY = cos((1 - e) * PI / 2).coerceAtLeast(0.001))
            MotionPreset.STRETCH_H -> { val k = backOut(p); MotionState(alpha = min(1.0, p * 4), scaleX = k.coerceAtLeast(0.001), scaleY = lerp(1.4, 1.0, e)) }
            MotionPreset.STRETCH_V -> { val k = backOut(p); MotionState(alpha = min(1.0, p * 4), scaleY = k.coerceAtLeast(0.001), scaleX = lerp(1.4, 1.0, e)) }
            MotionPreset.SHAKE -> MotionState(alpha = min(1.0, p * 4), dx = sin(p * 45) * 0.05 * (1 - p), rotation = sin(p * 37) * 6 * (1 - p))
            MotionPreset.GLITCH -> {
                val step = floor(p * 14)
                val on = p < 1 && hash(step) > 0.35
                MotionState(alpha = if (p >= 1) 1.0 else if (hash(step + 3) > 0.25) 1.0 else 0.0,
                    dx = if (on) (hash(step + 7) - 0.5) * 0.12 * (1 - p) else 0.0,
                    scaleX = if (on) 1 + (hash(step + 9) - 0.5) * 0.2 * (1 - p) else 1.0)
            }
            MotionPreset.FLICKER -> MotionState(alpha = if (p >= 1) 1.0 else if (hash(floor(p * 18)) < 0.25 + p * 0.75) 1.0 else 0.08)
        }
    }

    /**
     * @param t loop time in seconds (already multiplied by the loop speed)
     * @param a intensity
     * @param progress 0..1 position in the clip (Ken Burns)
     */
    fun loop(type: LoopMotion, t: Double, a: Double, progress: Double): MotionState {
        val tau = 2 * PI
        return when (type) {
            LoopMotion.NONE -> MotionState.IDENTITY
            LoopMotion.PULSE -> { val k = 1 + 0.06 * a * sin(tau * t * 1.5); MotionState(scaleX = k, scaleY = k) }
            LoopMotion.BREATHE -> { val k = 1 + 0.05 * a * (0.5 - 0.5 * cos(tau * t * 0.3)); MotionState(scaleX = k, scaleY = k) }
            LoopMotion.HEARTBEAT -> {
                val f = t - floor(t)
                val beat = exp(-((f - 0.1) / 0.05).pow(2)) + 0.6 * exp(-((f - 0.3) / 0.05).pow(2))
                val k = 1 + 0.1 * a * beat; MotionState(scaleX = k, scaleY = k)
            }
            LoopMotion.ZOOM_PUNCH -> { val f = t - floor(t); val k = 1 + 0.12 * a * exp(-f * 7); MotionState(scaleX = k, scaleY = k) }
            LoopMotion.KEN_BURNS -> { val pr = progress.coerceIn(0.0, 1.0); val k = 1 + 0.18 * a * pr; MotionState(scaleX = k, scaleY = k, dx = 0.04 * a * (pr - 0.5), dy = -0.02 * a * (pr - 0.5)) }
            LoopMotion.FLOAT -> MotionState(dy = 0.018 * a * sin(tau * t * 0.6), rotation = 1.5 * a * sin(tau * t * 0.4))
            LoopMotion.BOUNCE -> MotionState(dy = -0.045 * a * abs(sin(PI * t * 1.6)))
            LoopMotion.ORBIT -> MotionState(dx = 0.02 * a * cos(tau * t * 0.5), dy = 0.02 * a * sin(tau * t * 0.5))
            LoopMotion.SWING -> MotionState(rotation = 7 * a * sin(tau * t * 0.8))
            LoopMotion.WIGGLE -> MotionState(rotation = 4 * a * sin(tau * t * 4))
            LoopMotion.SPIN -> MotionState(rotation = 90 * t * a.coerceAtLeast(0.1))
            LoopMotion.JELLY -> { val k = 0.06 * a * sin(tau * t * 2); MotionState(scaleX = 1 + k, scaleY = 1 - k) }
            LoopMotion.SHAKE -> {
                val n = t * 24
                MotionState(dx = 0.008 * a * (sin(n * 1.7) + sin(n * 2.9) * 0.5), dy = 0.008 * a * (sin(n * 2.3) + sin(n * 3.7) * 0.5), rotation = 0.6 * a * sin(n * 1.3))
            }
            LoopMotion.FLICKER -> MotionState(alpha = if (hash(floor(t * 12)) < 0.12 * a.coerceAtMost(4.0)) 0.35 else 1.0)
        }
    }
}
