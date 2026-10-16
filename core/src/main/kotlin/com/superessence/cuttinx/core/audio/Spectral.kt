package com.superessence.cuttinx.core.audio

import kotlin.math.*

/**
 * Generic streaming STFT engine (sqrt-Hann analysis/synthesis, 75 % overlap) with a per-frame
 * spectral callback. Latency = [size] - [hop] frames.
 */
class StftProcessor(val size: Int = 1024, val hop: Int = size / 4, private val channels: Int, private val onSpectrum: (ch: Int, re: DoubleArray, im: DoubleArray) -> Unit) {
    private val fft = Fft(size)
    private val win = Fft.sqrtHann(size)
    private val latency = size - hop
    private val inFifo = Array(channels) { DoubleArray(size) }
    private val outFifo = Array(channels) { DoubleArray(size) }
    private val ola = Array(channels) { DoubleArray(size * 2) }
    private var rover = latency
    private val re = DoubleArray(size)
    private val im = DoubleArray(size)
    // sqrt-hann * sqrt-hann = hann, hann at 75 % overlap sums to 2
    private val olaScale = hop.toDouble() * 2 / size

    val latencyFrames get() = latency

    fun process(buf: FloatArray, frames: Int) {
        var i = 0
        for (f in 0 until frames) {
            for (c in 0 until channels) {
                inFifo[c][rover] = buf[i + c].toDouble()
                buf[i + c] = outFifo[c][rover - latency].toFloat()
            }
            rover++
            if (rover >= size) {
                rover = latency
                for (c in 0 until channels) frame(c)
            }
            i += channels
        }
    }

    private fun frame(c: Int) {
        for (k in 0 until size) { re[k] = inFifo[c][k] * win[k]; im[k] = 0.0 }
        fft.transform(re, im)
        onSpectrum(c, re, im)
        fft.transform(re, im, inverse = true)
        val o = ola[c]
        for (k in 0 until size) o[k] += re[k] * win[k] * olaScale
        for (k in 0 until hop) outFifo[c][k] = o[k]
        System.arraycopy(o, hop, o, 0, size)
        java.util.Arrays.fill(o, size, size + hop, 0.0)
        for (k in 0 until latency) inFifo[c][k] = inFifo[c][k + hop]
    }

    fun reset() {
        inFifo.forEach { it.fill(0.0) }; outFifo.forEach { it.fill(0.0) }; ola.forEach { it.fill(0.0) }; rover = latency
    }
}

/**
 * Spectral noise reduction with minimum-statistics noise tracking (no noise profile needed) and
 * temporally smoothed Wiener-like gains to limit "musical noise".
 */
class SpectralDenoiser(var amount: Double = 0.7, var sensitivity: Double = 1.5) : DspProcessor {
    private var stft: StftProcessor? = null
    private var ch = 1
    private val n = 1024
    private lateinit var smoothP: Array<DoubleArray>
    private lateinit var minP: Array<DoubleArray>
    private lateinit var tmpMin: Array<DoubleArray>
    private lateinit var noise: Array<DoubleArray>
    private lateinit var gains: Array<DoubleArray>
    private var frameCount = IntArray(1)
    private var minWindowFrames = 100
    /** Optional fixed noise profile (power per bin), e.g. learnt from a silent part. */
    var profile: DoubleArray? = null

    override fun configure(sampleRate: Int, channels: Int) {
        ch = channels
        val bins = n / 2 + 1
        smoothP = Array(channels) { DoubleArray(bins) }
        minP = Array(channels) { DoubleArray(bins) { 1e9 } }
        tmpMin = Array(channels) { DoubleArray(bins) { 1e9 } }
        noise = Array(channels) { DoubleArray(bins) { 1e-9 } }
        gains = Array(channels) { DoubleArray(bins) { 1.0 } }
        frameCount = IntArray(channels)
        minWindowFrames = (0.8 * sampleRate / (n / 4)).toInt().coerceAtLeast(10)
        stft = StftProcessor(n, n / 4, channels) { c, re, im -> spectrum(c, re, im) }
    }

    private fun spectrum(c: Int, re: DoubleArray, im: DoubleArray) {
        val bins = n / 2 + 1
        val floor = 1.0 - amount.coerceIn(0.0, 1.0) * 0.94
        val sp = smoothP[c]; val mp = minP[c]; val tm = tmpMin[c]; val nz = noise[c]; val g = gains[c]
        frameCount[c]++
        val resetWindow = frameCount[c] % minWindowFrames == 0
        for (k in 0 until bins) {
            val p = re[k] * re[k] + im[k] * im[k]
            sp[k] = if (frameCount[c] == 1) p else 0.8 * sp[k] + 0.2 * p
            tm[k] = min(tm[k], sp[k])
            mp[k] = min(mp[k], sp[k])
            if (resetWindow) { mp[k] = tm[k]; tm[k] = sp[k] }
            nz[k] = profile?.getOrNull(k) ?: (mp[k] * 1.5)
            val snr = p / (nz[k] * sensitivity + 1e-12)
            var gain = (1 - 1 / max(snr, 1e-6)).coerceAtLeast(0.0)
            gain = max(gain, floor)
            // temporal smoothing: fast attack, slower release
            g[k] = if (gain > g[k]) 0.5 * g[k] + 0.5 * gain else 0.85 * g[k] + 0.15 * gain
            re[k] *= g[k]; im[k] *= g[k]
            if (k in 1 until n / 2) { re[n - k] *= g[k]; im[n - k] *= g[k] }
        }
    }

    override fun process(buf: FloatArray, frames: Int, frameOffset: Long) { stft?.process(buf, frames) }
    override fun reset() { stft?.reset(); frameCount.fill(0) }
    val latencyFrames get() = stft?.latencyFrames ?: 0
}

/** Offline helpers that write new audio (never in place on the source file). */
object OfflineAudio {
    /** Runs [p] over a whole interleaved buffer and compensates the processor latency. */
    fun run(samples: FloatArray, sampleRate: Int, channels: Int, p: DspProcessor, latencyFrames: Int = 0): FloatArray {
        p.configure(sampleRate, channels)
        val padded = FloatArray(samples.size + latencyFrames * channels)
        System.arraycopy(samples, 0, padded, 0, samples.size)
        val block = 4096
        var f = 0
        val frames = padded.size / channels
        val tmp = FloatArray(block * channels)
        while (f < frames) {
            val n = min(block, frames - f)
            System.arraycopy(padded, f * channels, tmp, 0, n * channels)
            p.process(tmp, n, f.toLong())
            System.arraycopy(tmp, 0, padded, f * channels, n * channels)
            f += n
        }
        return padded.copyOfRange(latencyFrames * channels, latencyFrames * channels + samples.size)
    }

    fun denoise(samples: FloatArray, sampleRate: Int, channels: Int, amount: Double = 0.8, learnProfile: Boolean = true): FloatArray {
        val d = SpectralDenoiser(amount)
        if (learnProfile) d.profile = noiseProfile(samples, sampleRate, channels)
        d.configure(sampleRate, channels)
        return run(samples, sampleRate, channels, d, d.latencyFrames)
    }

    /** Average power spectrum of the 10 % quietest frames — a noise profile. */
    fun noiseProfile(samples: FloatArray, sampleRate: Int, channels: Int, n: Int = 1024): DoubleArray {
        val mono = AudioAnalysis.toMono(samples, channels)
        val hop = n / 2
        val fft = Fft(n); val win = Fft.sqrtHann(n)
        data class F(val energy: Double, val spec: DoubleArray)
        val frames = ArrayList<F>()
        var pos = 0
        val re = DoubleArray(n); val im = DoubleArray(n)
        while (pos + n <= mono.size) {
            var e = 0.0
            for (k in 0 until n) { re[k] = mono[pos + k] * win[k]; im[k] = 0.0; e += mono[pos + k] * mono[pos + k] }
            fft.transform(re, im)
            frames += F(e, DoubleArray(n / 2 + 1) { re[it] * re[it] + im[it] * im[it] })
            pos += hop
        }
        if (frames.isEmpty()) return DoubleArray(n / 2 + 1) { 1e-9 }
        val quiet = frames.sortedBy { it.energy }.take(max(1, frames.size / 10))
        return DoubleArray(n / 2 + 1) { k -> quiet.sumOf { it.spec[k] } / quiet.size * 1.3 }
    }

    /** Karaoke-style vocal removal for stereo: keeps side + centred low end. */
    fun removeVocals(samples: FloatArray, sampleRate: Int, channels: Int): FloatArray {
        if (channels < 2) return separateMono(samples, sampleRate, keepVoice = false)
        val lp = Biquad(Biquad.Type.LOWPASS, 160.0, 0.7).also { it.configure(sampleRate, 1) }
        val out = FloatArray(samples.size)
        var i = 0
        while (i + 1 < samples.size) {
            val l = samples[i].toDouble(); val r = samples[i + 1].toDouble()
            val mid = (l + r) / 2; val side = (l - r) / 2
            val bass = lp.processSample(mid, 0)
            out[i] = (side + bass).toFloat(); out[i + 1] = (-side + bass).toFloat()
            for (c in 2 until channels) out[i + c] = samples[i + c]
            i += channels
        }
        return out
    }

    /**
     * Voice isolation (spectral): centre-panned + harmonic content in the voice band.
     * Works best on stereo music with a centred lead vocal. Quality is limited compared with
     * neural separation models; documented in the app.
     */
    fun isolateVocals(samples: FloatArray, sampleRate: Int, channels: Int): FloatArray {
        if (channels < 2) return separateMono(samples, sampleRate, keepVoice = true)
        val frames = samples.size / channels
        val mid = DoubleArray(frames); val side = DoubleArray(frames)
        for (f in 0 until frames) { val l = samples[f * channels]; val r = samples[f * channels + 1]; mid[f] = (l + r) / 2.0; side[f] = (l - r) / 2.0 }
        val n = 2048; val hop = n / 4
        val fft = Fft(n); val win = Fft.sqrtHann(n)
        val out = DoubleArray(frames + n)
        val reM = DoubleArray(n); val imM = DoubleArray(n); val reS = DoubleArray(n); val imS = DoubleArray(n)
        val binHz = sampleRate.toDouble() / n
        var pos = 0
        while (pos + n <= frames) {
            for (k in 0 until n) { reM[k] = mid[pos + k] * win[k]; imM[k] = 0.0; reS[k] = side[pos + k] * win[k]; imS[k] = 0.0 }
            fft.transform(reM, imM); fft.transform(reS, imS)
            for (k in 0..n / 2) {
                val hz = k * binHz
                val pm = reM[k] * reM[k] + imM[k] * imM[k]
                val ps = reS[k] * reS[k] + imS[k] * imS[k]
                val centre = pm / (pm + ps * 4 + 1e-12)
                val band = if (hz < 100 || hz > 9000) 0.05 else if (hz < 180) (hz - 100) / 80 else 1.0
                val m = (centre.pow(2) * band).coerceIn(0.0, 1.0)
                reM[k] *= m; imM[k] *= m
                if (k in 1 until n / 2) { reM[n - k] *= m; imM[n - k] *= m }
            }
            fft.transform(reM, imM, inverse = true)
            for (k in 0 until n) out[pos + k] += reM[k] * win[k] * (hop * 2.0 / n)
            pos += hop
        }
        val res = FloatArray(samples.size)
        for (f in 0 until frames) for (c in 0 until channels) res[f * channels + c] = out[f].toFloat()
        return res
    }

    /** Mono fallback: harmonic/percussive split with median filtering, voice band emphasis. */
    fun separateMono(samples: FloatArray, sampleRate: Int, keepVoice: Boolean): FloatArray {
        val mono = AudioAnalysis.toMono(samples, 1)
        val n = 1024; val hop = n / 4
        val fft = Fft(n); val win = Fft.sqrtHann(n)
        val specs = ArrayList<Pair<DoubleArray, DoubleArray>>()
        var pos = 0
        while (pos + n <= mono.size) {
            val re = DoubleArray(n) { mono[pos + it] * win[it] }; val im = DoubleArray(n)
            fft.transform(re, im); specs += re to im; pos += hop
        }
        val bins = n / 2 + 1
        val mag = Array(specs.size) { t -> DoubleArray(bins) { k -> hypot(specs[t].first[k], specs[t].second[k]) } }
        val binHz = sampleRate.toDouble() / n
        val out = DoubleArray(mono.size + n)
        for (t in specs.indices) {
            val (re, im) = specs[t]
            for (k in 0 until bins) {
                val h = median(DoubleArray(17) { mag[(t + it - 8).coerceIn(0, specs.size - 1)][k] })
                val p = median(DoubleArray(17) { mag[t][(k + it - 8).coerceIn(0, bins - 1)] })
                val hz = k * binHz
                val voiceBand = hz in 150.0..6000.0
                var m = h * h / (h * h + p * p + 1e-12)
                m = if (keepVoice) (if (voiceBand) m else m * 0.1) else (if (voiceBand) 1 - m * 0.85 else 1.0)
                re[k] *= m; im[k] *= m
                if (k in 1 until n / 2) { re[n - k] *= m; im[n - k] *= m }
            }
            fft.transform(re, im, inverse = true)
            for (k in 0 until n) out[t * hop + k] += re[k] * win[k] * (hop * 2.0 / n)
        }
        return FloatArray(samples.size) { out[it].toFloat() }
    }

    private fun median(a: DoubleArray): Double { a.sort(); return a[a.size / 2] }
}
