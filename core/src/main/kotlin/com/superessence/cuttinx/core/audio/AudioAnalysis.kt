package com.superessence.cuttinx.core.audio

import kotlin.math.*

data class ClipEvent(val startUs: Long, val endUs: Long, val peak: Double)

data class AudioReport(
    val durationUs: Long,
    val peakDb: Double,
    val rmsDb: Double,
    val integratedLufs: Double,
    val loudnessRange: Double,
    val clippedSamplesPercent: Double,
    val clipEvents: List<ClipEvent>,
    val noiseFloorDb: Double,
    val snrDb: Double,
    val dcOffset: Double,
    val stereoCorrelation: Double?,
    val lowEnergyRatio: Double,
    val highEnergyRatio: Double,
    val silencePercent: Double,
    /** 0..100 global score. */
    val score: Int,
    val recommendations: List<String>,
)

/** Measurements on decoded PCM (interleaved floats). */
object AudioAnalysis {
    fun toMono(samples: FloatArray, channels: Int): FloatArray {
        if (channels <= 1) return samples
        val frames = samples.size / channels
        return FloatArray(frames) { f -> var s = 0f; for (c in 0 until channels) s += samples[f * channels + c]; s / channels }
    }

    fun peak(samples: FloatArray): Double = samples.maxOfOrNull { abs(it).toDouble() } ?: 0.0

    fun rms(samples: FloatArray): Double = if (samples.isEmpty()) 0.0 else sqrt(samples.sumOf { it.toDouble() * it } / samples.size)

    /** Integrated loudness per ITU-R BS.1770-4 (K-weighting, 400 ms blocks, absolute and relative gates). */
    fun integratedLoudness(samples: FloatArray, sampleRate: Int, channels: Int): Double {
        val blocks = loudnessBlocks(samples, sampleRate, channels)
        val abs = blocks.filter { it > -70.0 }
        if (abs.isEmpty()) return -70.0
        val relGate = energyMean(abs) - 10.0
        val rel = abs.filter { it > relGate }
        return if (rel.isEmpty()) -70.0 else energyMean(rel)
    }

    /** Loudness range (LRA) approximation: 10th..95th percentile of short-term gated blocks. */
    fun loudnessRange(samples: FloatArray, sampleRate: Int, channels: Int): Double {
        val blocks = loudnessBlocks(samples, sampleRate, channels, blockMs = 3000, overlap = 0.66).filter { it > -70.0 }
        if (blocks.size < 2) return 0.0
        val gate = energyMean(blocks) - 20.0
        val s = blocks.filter { it > gate }.sorted()
        if (s.size < 2) return 0.0
        return s[(s.size * 0.95).toInt().coerceAtMost(s.size - 1)] - s[(s.size * 0.10).toInt()]
    }

    private fun energyMean(lufs: List<Double>) = -0.691 + 10 * log10(lufs.sumOf { 10.0.pow((it + 0.691) / 10) } / lufs.size)

    fun loudnessBlocks(samples: FloatArray, sampleRate: Int, channels: Int, blockMs: Int = 400, overlap: Double = 0.75): List<Double> {
        val frames = samples.size / max(channels, 1)
        // K-weighting: stage 1 high shelf, stage 2 high pass (RLB)
        val (shelf, hp) = kWeighting(sampleRate, channels)
        val sq = DoubleArray(frames)
        var i = 0
        for (f in 0 until frames) {
            var acc = 0.0
            for (c in 0 until channels) {
                val y = hp.processSample(shelf.processSample(samples[i].toDouble(), c), c)
                acc += y * y * (if (c >= 3) 1.41 else 1.0) // surround weighting, irrelevant for stereo
                i++
            }
            sq[f] = acc
        }
        val block = (blockMs / 1000.0 * sampleRate).toInt().coerceAtLeast(1)
        val step = max(1, (block * (1 - overlap)).toInt())
        val out = ArrayList<Double>()
        var s = 0
        // prefix sums for speed
        val pre = DoubleArray(frames + 1)
        for (f in 0 until frames) pre[f + 1] = pre[f] + sq[f]
        while (s + block <= frames) {
            val ms = (pre[s + block] - pre[s]) / block
            out += -0.691 + 10 * log10(ms + 1e-12)
            s += step
        }
        if (out.isEmpty() && frames > 0) out += -0.691 + 10 * log10(pre[frames] / frames + 1e-12)
        return out
    }

    /** BS.1770 K-weighting filters, coefficients derived for any sample rate (bilinear transform of the reference design). */
    fun kWeighting(sampleRate: Int, channels: Int): Pair<Biquad, Biquad> {
        val shelf = Biquad(Biquad.Type.HIGHSHELF, 1000.0).also { it.configure(sampleRate, channels) }
        run {
            val f0 = 1681.974450955533; val g = 3.999843853973347; val q = 0.7071752369554196
            val k = tan(PI * f0 / sampleRate); val vh = 10.0.pow(g / 20); val vb = vh.pow(0.4996667741545416)
            val a0 = 1 + k / q + k * k
            shelf.setCoefficients((vh + vb * k / q + k * k) / a0, 2 * (k * k - vh) / a0, (vh - vb * k / q + k * k) / a0, 2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0)
        }
        val hp = Biquad(Biquad.Type.HIGHPASS, 1000.0).also { it.configure(sampleRate, channels) }
        run {
            val f0 = 38.13547087602444; val q = 0.5003270373238773
            val k = tan(PI * f0 / sampleRate); val a0 = 1 + k / q + k * k
            hp.setCoefficients(1.0, -2.0, 1.0, 2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0)
        }
        return shelf to hp
    }

    /** Gain (dB) to reach [targetLufs] without letting the true peak exceed [ceilingDb]. */
    fun normalizationGain(samples: FloatArray, sampleRate: Int, channels: Int, targetLufs: Double = -14.0, ceilingDb: Double = -1.0): Double {
        val l = integratedLoudness(samples, sampleRate, channels)
        if (l <= -69.0) return 0.0
        val pk = linToDb(peak(samples))
        return min(targetLufs - l, ceilingDb - pk).coerceIn(-30.0, 30.0)
    }

    /** Runs of clipped samples (|x| >= threshold) — "saturations". */
    fun clipping(samples: FloatArray, sampleRate: Int, channels: Int, threshold: Double = 0.999, minRun: Int = 3): List<ClipEvent> {
        val out = ArrayList<ClipEvent>()
        var run = 0; var runStart = 0; var pk = 0.0
        val frames = samples.size / channels
        for (f in 0 until frames) {
            var m = 0.0
            for (c in 0 until channels) m = max(m, abs(samples[f * channels + c].toDouble()))
            if (m >= threshold) { if (run == 0) { runStart = f; pk = 0.0 }; run++; pk = max(pk, m) }
            else {
                if (run >= minRun) out += ClipEvent(runStart * 1_000_000L / sampleRate, f * 1_000_000L / sampleRate, pk)
                run = 0
            }
        }
        if (run >= minRun) out += ClipEvent(runStart * 1_000_000L / sampleRate, frames * 1_000_000L / sampleRate, pk)
        // merge close events
        val merged = ArrayList<ClipEvent>()
        for (e in out) {
            val last = merged.lastOrNull()
            if (last != null && e.startUs - last.endUs < 50_000) merged[merged.size - 1] = last.copy(endUs = e.endUs, peak = max(last.peak, e.peak)) else merged += e
        }
        return merged
    }

    /** Peaks above [thresholdDb] (local maxima of a 10 ms envelope). */
    fun peaks(samples: FloatArray, sampleRate: Int, channels: Int, thresholdDb: Double = -3.0): List<Pair<Long, Double>> {
        val mono = toMono(samples, channels).map { abs(it) }
        val win = max(1, sampleRate / 100)
        val env = mono.chunked(win).map { it.maxOrNull()?.toDouble() ?: 0.0 }
        val thr = dbToLin(thresholdDb)
        val out = ArrayList<Pair<Long, Double>>()
        for (i in env.indices) {
            val v = env[i]
            if (v >= thr && (i == 0 || v >= env[i - 1]) && (i == env.size - 1 || v > env[i + 1])) out += (i * 10_000L) to linToDb(v)
        }
        return out
    }

    /** Waveform overview: [buckets] (min,max) pairs of the mono mix. */
    fun waveform(samples: FloatArray, channels: Int, buckets: Int): FloatArray {
        val mono = toMono(samples, channels)
        val out = FloatArray(buckets * 2)
        if (mono.isEmpty()) return out
        for (b in 0 until buckets) {
            val s = (b.toLong() * mono.size / buckets).toInt()
            val e = ((b + 1).toLong() * mono.size / buckets).toInt().coerceAtLeast(s + 1).coerceAtMost(mono.size)
            var mn = 0f; var mx = 0f
            for (i in s until e) { val v = mono[i]; if (v < mn) mn = v; if (v > mx) mx = v }
            out[b * 2] = mn; out[b * 2 + 1] = mx
        }
        return out
    }

    /** RMS envelope in dBFS with [windowMs] windows. */
    fun envelopeDb(samples: FloatArray, sampleRate: Int, channels: Int, windowMs: Int = 20): DoubleArray {
        val mono = toMono(samples, channels)
        val w = max(1, sampleRate * windowMs / 1000)
        val n = mono.size / w
        return DoubleArray(n) { i ->
            var acc = 0.0
            for (k in i * w until (i + 1) * w) acc += mono[k] * mono[k].toDouble()
            linToDb(sqrt(acc / w))
        }
    }

    fun report(samples: FloatArray, sampleRate: Int, channels: Int): AudioReport {
        val frames = samples.size / max(1, channels)
        val dur = frames * 1_000_000L / sampleRate
        val pk = peak(samples)
        val rmsV = rms(samples)
        val lufs = integratedLoudness(samples, sampleRate, channels)
        val lra = loudnessRange(samples, sampleRate, channels)
        val clips = clipping(samples, sampleRate, channels)
        val clippedCount = samples.count { abs(it) >= 0.999f }
        val env = envelopeDb(samples, sampleRate, channels, 50).filter { it > -120 }.sorted()
        val noiseFloor = if (env.isEmpty()) -120.0 else env[(env.size * 0.1).toInt()]
        val signal = if (env.isEmpty()) -120.0 else env[(env.size * 0.9).toInt().coerceAtMost(env.size - 1)]
        val dc = if (samples.isEmpty()) 0.0 else samples.sumOf { it.toDouble() } / samples.size
        val corr = if (channels >= 2) {
            var lr = 0.0; var ll = 0.0; var rr = 0.0
            for (f in 0 until frames) { val l = samples[f * channels].toDouble(); val r = samples[f * channels + 1].toDouble(); lr += l * r; ll += l * l; rr += r * r }
            if (ll * rr > 0) lr / sqrt(ll * rr) else 1.0
        } else null
        // spectral balance via simple filters
        val mono = toMono(samples, channels)
        val low = Biquad(Biquad.Type.LOWPASS, 200.0).also { it.configure(sampleRate, 1) }
        val high = Biquad(Biquad.Type.HIGHPASS, 5000.0).also { it.configure(sampleRate, 1) }
        var eL = 0.0; var eH = 0.0; var eT = 0.0
        for (v in mono) { val x = v.toDouble(); val l = low.processSample(x, 0); val h = high.processSample(x, 0); eL += l * l; eH += h * h; eT += x * x }
        val lowR = if (eT > 0) eL / eT else 0.0
        val highR = if (eT > 0) eH / eT else 0.0
        val silent = envelopeDb(samples, sampleRate, channels, 50).count { it < -50 }
        val totalW = max(1, envelopeDb(samples, sampleRate, channels, 50).size)
        val silencePct = silent * 100.0 / totalW

        val recs = ArrayList<String>()
        var score = 100
        val clippedPct = clippedCount * 100.0 / max(1, samples.size)
        if (clips.isNotEmpty()) { recs += "Saturation détectée à ${clips.size} endroit(s) : baissez le volume ou ajoutez le limiteur."; score -= min(30, 5 + clips.size) }
        if (lufs < -24 && lufs > -69) { recs += "Niveau faible (%.1f LUFS) : utilisez la normalisation (cible -14 LUFS).".format(lufs); score -= 10 }
        if (lufs > -9) { recs += "Niveau très fort (%.1f LUFS) : risque de fatigue et de saturation.".format(lufs); score -= 10 }
        val snr = signal - noiseFloor
        if (noiseFloor > -50 && snr < 25) { recs += "Bruit de fond élevé (plancher %.0f dB) : essayez la réduction de bruit.".format(noiseFloor); score -= 15 }
        if (abs(dc) > 0.01) { recs += "Décalage DC détecté : appliquez un filtre passe-haut (40 Hz)."; score -= 5 }
        if (corr != null && corr < 0) { recs += "Problème de phase stéréo (corrélation %.2f) : le son peut disparaître en mono.".format(corr); score -= 10 }
        if (lowR > 0.75) { recs += "Grave dominant : un égaliseur peut clarifier la voix (baisser 60–200 Hz)."; score -= 5 }
        if (highR < 0.002 && eT > 0) { recs += "Son étouffé : remontez les aigus (6–12 kHz)."; score -= 5 }
        if (lra > 18) { recs += "Grande dynamique (%.0f LU) : un compresseur homogénéisera le niveau.".format(lra); score -= 5 }
        if (recs.isEmpty()) recs += "Aucun problème majeur détecté."
        return AudioReport(dur, linToDb(pk), linToDb(rmsV), lufs, lra, clippedPct, clips, noiseFloor, snr, dc, corr, lowR, highR, silencePct, score.coerceIn(0, 100), recs)
    }
}
