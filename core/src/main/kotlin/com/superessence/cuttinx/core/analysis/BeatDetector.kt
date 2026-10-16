package com.superessence.cuttinx.core.analysis

import com.superessence.cuttinx.core.audio.AudioAnalysis
import com.superessence.cuttinx.core.audio.Fft
import kotlin.math.*

data class BeatResult(val bpm: Double, val beatsUs: List<Long>, val onsetsUs: List<Long>, val downbeatsUs: List<Long>, val confidence: Double)

/**
 * Onset detection (spectral flux) + tempo estimation (autocorrelation with a perceptual prior)
 * + beat tracking by dynamic programming.
 */
object BeatDetector {
    private const val N = 1024
    private const val HOP = 512

    fun onsetEnvelope(samples: FloatArray, sampleRate: Int, channels: Int): Pair<DoubleArray, Double> {
        val mono = AudioAnalysis.toMono(samples, channels)
        val fft = Fft(N); val win = Fft.hann(N)
        val re = DoubleArray(N); val im = DoubleArray(N)
        var prev = DoubleArray(N / 2 + 1)
        val frames = max(0, (mono.size - N) / HOP + 1)
        val env = DoubleArray(frames)
        for (f in 0 until frames) {
            val off = f * HOP
            for (k in 0 until N) { re[k] = mono[off + k] * win[k]; im[k] = 0.0 }
            fft.transform(re, im)
            val mag = DoubleArray(N / 2 + 1) { ln(1 + 100 * hypot(re[it], im[it])) }
            var flux = 0.0
            for (k in mag.indices) flux += max(0.0, mag[k] - prev[k])
            env[f] = flux
            prev = mag
        }
        // high-pass the envelope (remove slow trends) and normalise
        val smoothed = movingAverage(env, 16)
        for (i in env.indices) env[i] = max(0.0, env[i] - smoothed[i])
        val mx = env.maxOrNull() ?: 0.0
        if (mx > 0) for (i in env.indices) env[i] /= mx
        return env to (sampleRate.toDouble() / HOP)
    }

    private fun movingAverage(a: DoubleArray, half: Int): DoubleArray {
        val pre = DoubleArray(a.size + 1)
        for (i in a.indices) pre[i + 1] = pre[i] + a[i]
        return DoubleArray(a.size) { i -> val s = max(0, i - half); val e = min(a.size, i + half + 1); (pre[e] - pre[s]) / (e - s) }
    }

    fun detect(samples: FloatArray, sampleRate: Int, channels: Int, minBpm: Double = 60.0, maxBpm: Double = 190.0): BeatResult {
        val (env, fps) = onsetEnvelope(samples, sampleRate, channels)
        if (env.size < 16) return BeatResult(0.0, emptyList(), emptyList(), emptyList(), 0.0)
        // onsets: peaks above adaptive threshold
        val avg = movingAverage(env, 8)
        val onsets = ArrayList<Long>()
        var last = -100
        for (i in 1 until env.size - 1) {
            if (env[i] > env[i - 1] && env[i] >= env[i + 1] && env[i] > avg[i] + 0.1 && i - last > fps * 0.08) {
                onsets += (i / fps * 1e6).toLong(); last = i
            }
        }
        // tempo by autocorrelation weighted by log-gaussian prior around 120 BPM
        val minLag = (60.0 / maxBpm * fps).toInt().coerceAtLeast(1)
        val maxLag = (60.0 / minBpm * fps).toInt().coerceAtMost(env.size - 1)
        var bestLag = minLag; var bestScore = -1.0
        val scores = DoubleArray(maxLag + 1)
        for (lag in minLag..maxLag) {
            var acc = 0.0
            for (i in lag until env.size) acc += env[i] * env[i - lag]
            acc /= (env.size - lag)
            val bpm = 60 * fps / lag
            val prior = exp(-0.5 * (log2(bpm / 120.0) / 0.9).pow(2))
            scores[lag] = acc * prior
            if (scores[lag] > bestScore) { bestScore = scores[lag]; bestLag = lag }
        }
        // parabolic refinement
        var lagF = bestLag.toDouble()
        if (bestLag in (minLag + 1) until maxLag) {
            val a = scores[bestLag - 1]; val b = scores[bestLag]; val c = scores[bestLag + 1]
            val d = a - 2 * b + c
            if (d != 0.0) lagF += 0.5 * (a - c) / d
        }
        val bpm = 60 * fps / lagF
        val mean = scores.filter { it > 0 }.average().takeIf { !it.isNaN() } ?: 0.0
        val confidence = if (bestScore > 0) (1 - mean / bestScore).coerceIn(0.0, 1.0) else 0.0
        // dynamic programming beat tracking (Ellis 2007)
        val period = lagF
        val tightness = 100.0
        val score = DoubleArray(env.size)
        val back = IntArray(env.size) { -1 }
        for (i in env.indices) {
            var best = 0.0; var bi = -1
            val from = (i - 2 * period).toInt(); val to = (i - period / 2).toInt()
            for (j in max(0, from)..to.coerceAtMost(i - 1)) {
                if (j < 0) continue
                val pen = -tightness * ln((i - j) / period).pow(2)
                val v = score[j] + pen
                if (v > best || bi == -1) { best = v; bi = j }
            }
            score[i] = env[i] + (if (bi >= 0) max(0.0, best) else 0.0)
            back[i] = if (bi >= 0 && best > 0) bi else -1
        }
        // start from best score in the last period
        val tailStart = max(0, env.size - period.toInt() - 1)
        var idx = (tailStart until env.size).maxByOrNull { score[it] } ?: env.size - 1
        val beats = ArrayList<Long>()
        while (idx >= 0) { beats += (idx / fps * 1e6).toLong(); idx = back[idx] }
        beats.reverse()
        // fill gaps at the start using the period
        val filled = ArrayList<Long>()
        if (beats.isNotEmpty()) {
            val pUs = (period / fps * 1e6).toLong()
            var t = beats.first() - pUs
            val pre = ArrayList<Long>()
            while (t >= 0) { pre += t; t -= pUs }
            filled += pre.reversed(); filled += beats
        }
        // downbeats: every 4th beat, phase chosen by strongest onset sum
        val phase = (0 until 4).maxByOrNull { ph -> filled.filterIndexed { i, _ -> i % 4 == ph }.sumOf { env.getOrElse((it / 1e6 * fps).toInt()) { 0.0 } } } ?: 0
        val downs = filled.filterIndexed { i, _ -> i % 4 == phase }
        return BeatResult(bpm, filled, onsets, downs, confidence)
    }
}
