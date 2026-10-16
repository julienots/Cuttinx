package com.superessence.cuttinx.core.anim

import com.superessence.cuttinx.core.model.Easing
import com.superessence.cuttinx.core.model.EasingType
import com.superessence.cuttinx.core.model.Keyframe
import com.superessence.cuttinx.core.model.KeyframeTrack
import kotlin.math.abs

object Interpolation {
    /** Maps linear progress [t] (0..1) through [easing]. */
    fun ease(easing: Easing, t: Double): Double {
        val x = t.coerceIn(0.0, 1.0)
        return when (easing.type) {
            EasingType.HOLD -> 0.0
            EasingType.LINEAR -> x
            EasingType.EASE_IN -> cubicBezier(0.42, 0.0, 1.0, 1.0, x)
            EasingType.EASE_OUT -> cubicBezier(0.0, 0.0, 0.58, 1.0, x)
            EasingType.EASE_IN_OUT -> cubicBezier(0.42, 0.0, 0.58, 1.0, x)
            EasingType.BEZIER -> cubicBezier(easing.x1, easing.y1, easing.x2, easing.y2, x)
        }
    }

    /**
     * CSS-style cubic Bézier timing function through (0,0),(x1,y1),(x2,y2),(1,1).
     * Solves x(s)=t with Newton iterations falling back to bisection, returns y(s).
     */
    fun cubicBezier(x1: Double, y1: Double, x2: Double, y2: Double, t: Double): Double {
        if (t <= 0.0) return 0.0
        if (t >= 1.0) return 1.0
        val cx1 = x1.coerceIn(0.0, 1.0)
        val cx2 = x2.coerceIn(0.0, 1.0)
        fun bx(s: Double) = 3 * (1 - s) * (1 - s) * s * cx1 + 3 * (1 - s) * s * s * cx2 + s * s * s
        fun by(s: Double) = 3 * (1 - s) * (1 - s) * s * y1 + 3 * (1 - s) * s * s * y2 + s * s * s
        fun dbx(s: Double) = 3 * (1 - s) * (1 - s) * cx1 + 6 * (1 - s) * s * (cx2 - cx1) + 3 * s * s * (1 - cx2)
        var s = t
        repeat(8) {
            val err = bx(s) - t
            if (abs(err) < 1e-7) return by(s)
            val d = dbx(s)
            if (abs(d) < 1e-6) return@repeat
            s -= err / d
        }
        var lo = 0.0
        var hi = 1.0
        s = t
        repeat(60) {
            val v = bx(s)
            if (abs(v - t) < 1e-7) return by(s)
            if (v < t) lo = s else hi = s
            s = (lo + hi) / 2
        }
        return by(s)
    }

    fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t
}

object KeyframeEvaluator {
    /**
     * Value of [track] at clip-relative [timeUs]. Before the first keyframe the first value holds,
     * after the last keyframe the last value holds. Returns [default] when there are no keyframes.
     */
    fun evaluate(track: KeyframeTrack?, timeUs: Long, default: Double): Double {
        val kfs = track?.keyframes ?: return default
        if (kfs.isEmpty()) return default
        if (timeUs <= kfs.first().timeUs) return kfs.first().value
        if (timeUs >= kfs.last().timeUs) return kfs.last().value
        // binary search for segment
        var lo = 0
        var hi = kfs.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (kfs[mid].timeUs <= timeUs) lo = mid else hi = mid
        }
        val a = kfs[lo]
        val b = kfs[hi]
        val span = (b.timeUs - a.timeUs).toDouble()
        if (span <= 0) return b.value
        val p = Interpolation.ease(a.easing, (timeUs - a.timeUs) / span)
        return Interpolation.lerp(a.value, b.value, p)
    }

    /** Inserts or replaces (same time within [toleranceUs]) a keyframe. */
    fun upsert(track: KeyframeTrack?, kf: Keyframe, toleranceUs: Long = 1_000): KeyframeTrack {
        val list = (track?.keyframes ?: emptyList()).filterNot { abs(it.timeUs - kf.timeUs) <= toleranceUs } + kf
        return KeyframeTrack(list.sortedBy { it.timeUs })
    }

    fun remove(track: KeyframeTrack?, timeUs: Long, toleranceUs: Long = 1_000): KeyframeTrack =
        KeyframeTrack((track?.keyframes ?: emptyList()).filterNot { abs(it.timeUs - timeUs) <= toleranceUs })

    /** Moves the keyframe at [fromUs] to [toUs]. */
    fun move(track: KeyframeTrack, fromUs: Long, toUs: Long, toleranceUs: Long = 1_000): KeyframeTrack {
        val k = track.keyframes.firstOrNull { abs(it.timeUs - fromUs) <= toleranceUs } ?: return track
        return upsert(remove(track, fromUs, toleranceUs), k.copy(timeUs = toUs.coerceAtLeast(0)), toleranceUs)
    }

    /** Scales all keyframe times by [factor] (used when a clip is trimmed/sped up proportionally). */
    fun retime(track: KeyframeTrack, factor: Double) =
        KeyframeTrack(track.keyframes.map { it.copy(timeUs = (it.timeUs * factor).toLong()) })

    /** Shifts keyframes by [deltaUs], dropping those that fall before 0 or after [maxUs]. */
    fun shift(track: KeyframeTrack, deltaUs: Long, maxUs: Long): KeyframeTrack =
        KeyframeTrack(track.keyframes.map { it.copy(timeUs = it.timeUs + deltaUs) }.filter { it.timeUs in 0..maxUs })
}

/** Keyframe helpers for clip splitting. */
fun KeyframeEvaluator.splitLeft(track: KeyframeTrack, atRelUs: Long): KeyframeTrack {
    if (track.isEmpty) return track
    val v = evaluate(track, atRelUs, 0.0)
    val kept = track.keyframes.filter { it.timeUs < atRelUs }
    return KeyframeTrack(kept + Keyframe(atRelUs, v, kept.lastOrNull()?.easing ?: com.superessence.cuttinx.core.model.Easing.Linear))
}

fun KeyframeEvaluator.splitRight(track: KeyframeTrack, atRelUs: Long): KeyframeTrack {
    if (track.isEmpty) return track
    val v = evaluate(track, atRelUs, 0.0)
    val prevEasing = track.keyframes.lastOrNull { it.timeUs <= atRelUs }?.easing ?: com.superessence.cuttinx.core.model.Easing.Linear
    val kept = track.keyframes.filter { it.timeUs > atRelUs }.map { it.copy(timeUs = it.timeUs - atRelUs) }
    return KeyframeTrack(listOf(Keyframe(0, v, prevEasing)) + kept)
}
