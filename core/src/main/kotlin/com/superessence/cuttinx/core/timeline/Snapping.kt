package com.superessence.cuttinx.core.timeline

import com.superessence.cuttinx.core.model.Project
import kotlin.math.abs

/** Magnetism: snaps a time to nearby clip edges, markers, the playhead or beats. */
object Snapping {
    fun candidates(p: Project, excludeClipIds: Set<String> = emptySet(), playheadUs: Long? = null, beats: List<Long> = emptyList()): List<Long> {
        val list = ArrayList<Long>()
        list += 0L
        for (t in p.tracks) for (c in t.clips) if (c.id !in excludeClipIds) { list += c.startUs; list += c.endUs }
        p.markers.forEach { list += it.timeUs }
        playheadUs?.let { list += it }
        list += beats
        return list.distinct().sorted()
    }

    /** Returns the snapped value, or [timeUs] when nothing is within [thresholdUs]. */
    fun snap(timeUs: Long, candidates: List<Long>, thresholdUs: Long): Long {
        var best = timeUs
        var bestD = thresholdUs + 1
        for (c in candidates) {
            val d = abs(c - timeUs)
            if (d < bestD) { bestD = d; best = c }
        }
        return if (bestD <= thresholdUs) best else timeUs
    }

    /** Snaps a moving range [start, start+duration): whichever edge is closest wins. */
    fun snapRange(startUs: Long, durationUs: Long, candidates: List<Long>, thresholdUs: Long): Long {
        val s = snap(startUs, candidates, thresholdUs)
        val e = snap(startUs + durationUs, candidates, thresholdUs)
        val ds = abs(s - startUs)
        val de = abs(e - (startUs + durationUs))
        return when {
            s != startUs && (e == startUs + durationUs || ds <= de) -> s
            e != startUs + durationUs -> e - durationUs
            else -> startUs
        }
    }

    /** Frame-accurate rounding. */
    fun toFrame(timeUs: Long, fps: Int): Long {
        val frame = 1_000_000.0 / fps
        return (Math.round(timeUs / frame) * frame).toLong()
    }
}
