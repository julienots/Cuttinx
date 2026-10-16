package com.superessence.cuttinx.core.analysis

import com.superessence.cuttinx.core.audio.AudioAnalysis

data class TimeRange(val startUs: Long, val endUs: Long) {
    val durationUs get() = endUs - startUs
    fun overlaps(o: TimeRange) = startUs < o.endUs && o.startUs < endUs
}

/** Detects silent ranges from an RMS envelope with an adaptive threshold. */
object SilenceDetector {
    data class Params(
        /** Absolute threshold (dBFS); null = adaptive (noise floor + [marginDb]). */
        val thresholdDb: Double? = null,
        val marginDb: Double = 10.0,
        val minSilenceUs: Long = 450_000,
        /** Audio kept on each side of a silence so cuts don't clip words. */
        val paddingUs: Long = 120_000,
        val windowMs: Int = 20,
    )

    fun detect(samples: FloatArray, sampleRate: Int, channels: Int, params: Params = Params()): List<TimeRange> =
        detectFromEnvelope(AudioAnalysis.envelopeDb(samples, sampleRate, channels, params.windowMs), params)

    fun adaptiveThreshold(env: DoubleArray, marginDb: Double): Double {
        val valid = env.filter { it > -100 }.sorted()
        if (valid.isEmpty()) return -60.0
        val floor = valid[(valid.size * 0.1).toInt()]
        val loud = valid[(valid.size * 0.9).toInt().coerceAtMost(valid.size - 1)]
        // threshold between noise floor and speech level, never above -20 dBFS
        return minOf(floor + marginDb, (floor + loud) / 2, -20.0).coerceAtLeast(-70.0)
    }

    fun detectFromEnvelope(env: DoubleArray, params: Params = Params()): List<TimeRange> {
        val thr = params.thresholdDb ?: adaptiveThreshold(env, params.marginDb)
        val winUs = params.windowMs * 1000L
        val out = ArrayList<TimeRange>()
        var start = -1
        for (i in env.indices) {
            val silent = env[i] < thr
            if (silent && start < 0) start = i
            if ((!silent || i == env.size - 1) && start >= 0) {
                val end = if (silent) i + 1 else i
                val r = TimeRange(start * winUs, end * winUs)
                if (r.durationUs >= params.minSilenceUs) {
                    val s = if (start == 0) 0 else r.startUs + params.paddingUs
                    val e = if (end == env.size) r.endUs else r.endUs - params.paddingUs
                    if (e - s > 0) out += TimeRange(s, e)
                }
                start = -1
            }
        }
        return out
    }

    /** Complement of [silences] within [0, durationUs]: the ranges to keep. */
    fun keepRanges(silences: List<TimeRange>, durationUs: Long): List<TimeRange> {
        val out = ArrayList<TimeRange>()
        var cursor = 0L
        for (s in silences.sortedBy { it.startUs }) {
            if (s.startUs > cursor) out += TimeRange(cursor, s.startUs)
            cursor = maxOf(cursor, s.endUs)
        }
        if (cursor < durationUs) out += TimeRange(cursor, durationUs)
        return out.filter { it.durationUs > 50_000 }
    }
}
