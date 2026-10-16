package com.superessence.cuttinx.core.anim

import com.superessence.cuttinx.core.model.Clip
import com.superessence.cuttinx.core.model.SpeedPoint

/**
 * Speed handling. A progressive speed curve is rendered as [STEPS] piecewise-constant segments
 * of equal *source* length. The exact same discretisation is used by the timeline model, the
 * preview and the export so that durations always match.
 */
object SpeedMath {
    const val STEPS = 48
    const val MIN_SPEED = 0.1
    const val MAX_SPEED = 100.0

    fun clampSpeed(s: Double) = s.coerceIn(MIN_SPEED, MAX_SPEED)

    /** Linear interpolation of the user curve at normalised source [position]. */
    fun curveSpeedAt(curve: List<SpeedPoint>, position: Double): Double {
        if (curve.isEmpty()) return 1.0
        val pts = curve.sortedBy { it.position }
        if (position <= pts.first().position) return clampSpeed(pts.first().speed)
        if (position >= pts.last().position) return clampSpeed(pts.last().speed)
        for (i in 0 until pts.size - 1) {
            val a = pts[i]; val b = pts[i + 1]
            if (position >= a.position && position <= b.position) {
                val span = b.position - a.position
                val t = if (span <= 0) 0.0 else (position - a.position) / span
                return clampSpeed(a.speed + (b.speed - a.speed) * t)
            }
        }
        return 1.0
    }

    /** The speed of each of the [STEPS] segments (sampled at segment centre). */
    fun steps(curve: List<SpeedPoint>): DoubleArray =
        DoubleArray(STEPS) { i -> curveSpeedAt(curve, (i + 0.5) / STEPS) }

    fun timelineDuration(clip: Clip): Long {
        val src = clip.sourceDurationUs
        if (clip.speedCurve.isEmpty()) return (src / clampSpeed(clip.speed)).toLong()
        val seg = src.toDouble() / STEPS
        return steps(clip.speedCurve).sumOf { seg / it }.toLong()
    }

    /** Converts a time relative to the clip start on the timeline into an absolute source time. */
    fun timelineToSource(clip: Clip, relUs: Long): Long {
        val src = clip.sourceDurationUs
        if (clip.speedCurve.isEmpty()) {
            return clip.sourceInUs + (relUs * clampSpeed(clip.speed)).toLong().coerceIn(0, src)
        }
        val seg = src.toDouble() / STEPS
        var remaining = relUs.toDouble().coerceAtLeast(0.0)
        var source = 0.0
        for (s in steps(clip.speedCurve)) {
            val segTimeline = seg / s
            if (remaining <= segTimeline) { source += remaining * s; return clip.sourceInUs + source.toLong().coerceIn(0, src) }
            remaining -= segTimeline
            source += seg
        }
        return clip.sourceOutUs
    }

    /** Inverse of [timelineToSource]: absolute source time -> time relative to clip start. */
    fun sourceToTimeline(clip: Clip, sourceUs: Long): Long {
        val rel = (sourceUs - clip.sourceInUs).coerceIn(0, clip.sourceDurationUs).toDouble()
        if (clip.speedCurve.isEmpty()) return (rel / clampSpeed(clip.speed)).toLong()
        val seg = clip.sourceDurationUs.toDouble() / STEPS
        var acc = 0.0
        var consumed = 0.0
        for (s in steps(clip.speedCurve)) {
            if (rel <= consumed + seg) return (acc + (rel - consumed) / s).toLong()
            acc += seg / s
            consumed += seg
        }
        return acc.toLong()
    }

    /** Speed at an absolute source time — used by the engine's speed provider. */
    fun speedAtSource(clip: Clip, sourceUs: Long): Double {
        if (clip.speedCurve.isEmpty()) return clampSpeed(clip.speed)
        val src = clip.sourceDurationUs
        if (src <= 0) return 1.0
        val idx = (((sourceUs - clip.sourceInUs).toDouble() / src) * STEPS).toInt().coerceIn(0, STEPS - 1)
        return steps(clip.speedCurve)[idx]
    }

    /** Next source time at which the speed changes, or null after the last step. */
    fun nextSpeedChangeSource(clip: Clip, sourceUs: Long): Long? {
        if (clip.speedCurve.isEmpty()) return null
        val src = clip.sourceDurationUs
        val seg = src.toDouble() / STEPS
        val idx = ((sourceUs - clip.sourceInUs) / seg).toInt() + 1
        if (idx >= STEPS) return null
        return clip.sourceInUs + (idx * seg).toLong()
    }

    /** Built-in progressive speed presets (original curves). */
    val presets: Map<String, List<SpeedPoint>> = linkedMapOf(
        "Montée" to listOf(SpeedPoint(0.0, 0.5), SpeedPoint(1.0, 3.0)),
        "Descente" to listOf(SpeedPoint(0.0, 3.0), SpeedPoint(1.0, 0.5)),
        "Impact" to listOf(SpeedPoint(0.0, 2.5), SpeedPoint(0.45, 2.5), SpeedPoint(0.5, 0.25), SpeedPoint(0.6, 0.25), SpeedPoint(0.7, 2.5), SpeedPoint(1.0, 2.5)),
        "Héros" to listOf(SpeedPoint(0.0, 1.0), SpeedPoint(0.3, 0.3), SpeedPoint(0.7, 0.3), SpeedPoint(1.0, 1.0)),
        "Pulsation" to listOf(SpeedPoint(0.0, 0.5), SpeedPoint(0.25, 2.0), SpeedPoint(0.5, 0.5), SpeedPoint(0.75, 2.0), SpeedPoint(1.0, 0.5)),
        "Flash avant" to listOf(SpeedPoint(0.0, 1.0), SpeedPoint(0.4, 1.0), SpeedPoint(0.5, 6.0), SpeedPoint(0.6, 1.0), SpeedPoint(1.0, 1.0)),
    )
}
