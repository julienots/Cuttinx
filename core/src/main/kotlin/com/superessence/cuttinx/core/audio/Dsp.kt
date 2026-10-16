package com.superessence.cuttinx.core.audio

import kotlin.math.*

fun dbToLin(db: Double) = 10.0.pow(db / 20.0)
fun linToDb(lin: Double) = if (lin <= 1e-10) -200.0 else 20 * log10(lin)

/**
 * A streaming processor working in place on interleaved float samples (-1..1).
 * [frameOffset] is the index of the first frame of the buffer relative to the clip start, so that
 * time-dependent processing (automation) stays sample-accurate after seeks.
 */
interface DspProcessor {
    fun configure(sampleRate: Int, channels: Int)
    fun process(buf: FloatArray, frames: Int, frameOffset: Long)
    fun reset() {}
}

/** RBJ cookbook biquad, one state per channel. */
class Biquad(var type: Type, var freq: Double, var q: Double = 0.707, var gainDb: Double = 0.0) : DspProcessor {
    enum class Type { LOWPASS, HIGHPASS, BANDPASS, PEAK, LOWSHELF, HIGHSHELF, NOTCH }
    private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0; private var a1 = 0.0; private var a2 = 0.0
    private var ch = 1
    private var sr = 48000
    private var z1 = DoubleArray(1); private var z2 = DoubleArray(1)

    override fun configure(sampleRate: Int, channels: Int) {
        sr = sampleRate; ch = channels
        z1 = DoubleArray(channels); z2 = DoubleArray(channels)
        update()
    }

    fun update() {
        val f = freq.coerceIn(10.0, sr * 0.45)
        val w0 = 2 * PI * f / sr
        val cw = cos(w0); val sw = sin(w0)
        val alpha = sw / (2 * q.coerceAtLeast(0.05))
        val a = 10.0.pow(gainDb / 40)
        var nb0: Double; var nb1: Double; var nb2: Double; var na0: Double; var na1: Double; var na2: Double
        when (type) {
            Type.LOWPASS -> { nb0 = (1 - cw) / 2; nb1 = 1 - cw; nb2 = (1 - cw) / 2; na0 = 1 + alpha; na1 = -2 * cw; na2 = 1 - alpha }
            Type.HIGHPASS -> { nb0 = (1 + cw) / 2; nb1 = -(1 + cw); nb2 = (1 + cw) / 2; na0 = 1 + alpha; na1 = -2 * cw; na2 = 1 - alpha }
            Type.BANDPASS -> { nb0 = alpha; nb1 = 0.0; nb2 = -alpha; na0 = 1 + alpha; na1 = -2 * cw; na2 = 1 - alpha }
            Type.NOTCH -> { nb0 = 1.0; nb1 = -2 * cw; nb2 = 1.0; na0 = 1 + alpha; na1 = -2 * cw; na2 = 1 - alpha }
            Type.PEAK -> { nb0 = 1 + alpha * a; nb1 = -2 * cw; nb2 = 1 - alpha * a; na0 = 1 + alpha / a; na1 = -2 * cw; na2 = 1 - alpha / a }
            Type.LOWSHELF -> {
                val s = 2 * sqrt(a) * alpha
                nb0 = a * ((a + 1) - (a - 1) * cw + s); nb1 = 2 * a * ((a - 1) - (a + 1) * cw); nb2 = a * ((a + 1) - (a - 1) * cw - s)
                na0 = (a + 1) + (a - 1) * cw + s; na1 = -2 * ((a - 1) + (a + 1) * cw); na2 = (a + 1) + (a - 1) * cw - s
            }
            Type.HIGHSHELF -> {
                val s = 2 * sqrt(a) * alpha
                nb0 = a * ((a + 1) + (a - 1) * cw + s); nb1 = -2 * a * ((a - 1) + (a + 1) * cw); nb2 = a * ((a + 1) + (a - 1) * cw - s)
                na0 = (a + 1) - (a - 1) * cw + s; na1 = 2 * ((a - 1) - (a + 1) * cw); na2 = (a + 1) - (a - 1) * cw - s
            }
        }
        b0 = nb0 / na0; b1 = nb1 / na0; b2 = nb2 / na0; a1 = na1 / na0; a2 = na2 / na0
    }

    fun processSample(x: Double, c: Int): Double {
        // transposed direct form II
        val y = b0 * x + z1[c]
        z1[c] = b1 * x - a1 * y + z2[c]
        z2[c] = b2 * x - a2 * y
        return y
    }

    override fun process(buf: FloatArray, frames: Int, frameOffset: Long) {
        var i = 0
        for (f in 0 until frames) for (c in 0 until ch) { buf[i] = processSample(buf[i].toDouble(), c).toFloat(); i++ }
    }

    override fun reset() { z1.fill(0.0); z2.fill(0.0) }

    /** Magnitude response in dB at [f] (for UI curves and tests). */
    fun responseDb(f: Double): Double {
        val w = 2 * PI * f / sr
        val cr = cos(w); val ci = -sin(w); val c2r = cos(2 * w); val c2i = -sin(2 * w)
        val nr = b0 + b1 * cr + b2 * c2r; val ni = b1 * ci + b2 * c2i
        val dr = 1 + a1 * cr + a2 * c2r; val di = a1 * ci + a2 * c2i
        return 10 * log10((nr * nr + ni * ni) / (dr * dr + di * di))
    }
}

class Equalizer(bands: List<com.superessence.cuttinx.core.model.EqBand>) : DspProcessor {
    val filters = bands.mapIndexed { i, b ->
        val type = when (i) { 0 -> Biquad.Type.LOWSHELF; bands.size - 1 -> Biquad.Type.HIGHSHELF; else -> Biquad.Type.PEAK }
        Biquad(type, b.frequencyHz, b.q, b.gainDb)
    }.filter { it.gainDb != 0.0 }
    override fun configure(sampleRate: Int, channels: Int) = filters.forEach { it.configure(sampleRate, channels) }
    override fun process(buf: FloatArray, frames: Int, frameOffset: Long) = filters.forEach { it.process(buf, frames, frameOffset) }
    override fun reset() = filters.forEach { it.reset() }
}

/** Feed-forward compressor with linked stereo detection and soft knee. */
class Compressor(var thresholdDb: Double = -18.0, var ratio: Double = 4.0, var attackMs: Double = 10.0, var releaseMs: Double = 120.0, var makeupDb: Double = 0.0, var kneeDb: Double = 6.0) : DspProcessor {
    private var ch = 1; private var sr = 48000
    private var env = 0.0
    private var aCoef = 0.0; private var rCoef = 0.0
    /** Last applied gain reduction (dB), for metering. */
    var gainReductionDb = 0.0; private set

    override fun configure(sampleRate: Int, channels: Int) {
        sr = sampleRate; ch = channels
        aCoef = exp(-1.0 / (attackMs.coerceAtLeast(0.05) * 0.001 * sr))
        rCoef = exp(-1.0 / (releaseMs.coerceAtLeast(1.0) * 0.001 * sr))
    }

    fun gainComputer(inDb: Double): Double {
        val over = inDb - thresholdDb
        return when {
            2 * over < -kneeDb -> 0.0
            2 * abs(over) <= kneeDb -> (1 / ratio - 1) * (over + kneeDb / 2).pow(2) / (2 * kneeDb)
            else -> (1 / ratio - 1) * over
        }
    }

    override fun process(buf: FloatArray, frames: Int, frameOffset: Long) {
        val makeup = dbToLin(makeupDb)
        var i = 0
        for (f in 0 until frames) {
            var peak = 0.0
            for (c in 0 until ch) peak = max(peak, abs(buf[i + c].toDouble()))
            env = if (peak > env) aCoef * env + (1 - aCoef) * peak else rCoef * env + (1 - rCoef) * peak
            val gr = gainComputer(linToDb(env))
            gainReductionDb = gr
            val g = dbToLin(gr) * makeup
            for (c in 0 until ch) { buf[i] = (buf[i] * g).toFloat(); i++ }
        }
    }

    override fun reset() { env = 0.0 }
}

/** Brick-wall style peak limiter with instant attack and smooth release (no lookahead → no latency). */
class Limiter(var ceilingDb: Double = -1.0, var releaseMs: Double = 80.0) : DspProcessor {
    private var ch = 1; private var gain = 1.0; private var rCoef = 0.0
    override fun configure(sampleRate: Int, channels: Int) { ch = channels; rCoef = exp(-1.0 / (releaseMs * 0.001 * sampleRate)) }
    override fun process(buf: FloatArray, frames: Int, frameOffset: Long) {
        val ceil = dbToLin(ceilingDb)
        var i = 0
        for (f in 0 until frames) {
            var peak = 0.0
            for (c in 0 until ch) peak = max(peak, abs(buf[i + c].toDouble()))
            val target = if (peak * gain > ceil) ceil / peak else 1.0
            gain = if (target < gain) target else rCoef * gain + (1 - rCoef) * target
            for (c in 0 until ch) {
                val v = (buf[i] * gain).coerceIn(-ceil, ceil)
                buf[i] = v.toFloat(); i++
            }
        }
    }
    override fun reset() { gain = 1.0 }
}

/** Downward expander / noise gate. */
class NoiseGate(var thresholdDb: Double = -45.0, var releaseMs: Double = 100.0, var attackMs: Double = 2.0, var rangeDb: Double = -60.0) : DspProcessor {
    private var ch = 1; private var env = 0.0; private var g = 1.0; private var aC = 0.0; private var rC = 0.0; private var eC = 0.0
    override fun configure(sampleRate: Int, channels: Int) {
        ch = channels
        aC = exp(-1.0 / (attackMs * 0.001 * sampleRate)); rC = exp(-1.0 / (releaseMs * 0.001 * sampleRate)); eC = exp(-1.0 / (0.005 * sampleRate))
    }
    override fun process(buf: FloatArray, frames: Int, frameOffset: Long) {
        val thr = dbToLin(thresholdDb); val floor = dbToLin(rangeDb)
        var i = 0
        for (f in 0 until frames) {
            var peak = 0.0
            for (c in 0 until ch) peak = max(peak, abs(buf[i + c].toDouble()))
            env = max(peak, eC * env)
            val target = if (env >= thr) 1.0 else floor
            g = if (target > g) aC * g + (1 - aC) * target else rC * g + (1 - rC) * target
            for (c in 0 until ch) { buf[i] = (buf[i] * g).toFloat(); i++ }
        }
    }
    override fun reset() { env = 0.0; g = 1.0 }
}

/** Feedback delay / echo with a damping low-pass in the loop. */
class Delay(var timeMs: Double = 320.0, var feedback: Double = 0.4, var wet: Double = 0.35) : DspProcessor {
    private var ch = 1; private var line = FloatArray(1); private var pos = 0; private var len = 1
    private val lp = DoubleArray(8)
    override fun configure(sampleRate: Int, channels: Int) {
        ch = channels
        len = max(1, (timeMs * 0.001 * sampleRate).toInt())
        line = FloatArray(len * channels); pos = 0
    }
    override fun process(buf: FloatArray, frames: Int, frameOffset: Long) {
        var i = 0
        val fb = feedback.coerceIn(0.0, 0.95)
        for (f in 0 until frames) {
            for (c in 0 until ch) {
                val idx = pos * ch + c
                val d = line[idx].toDouble()
                lp[c % 8] = lp[c % 8] * 0.3 + d * 0.7
                val x = buf[i].toDouble()
                line[idx] = (x + lp[c % 8] * fb).toFloat()
                buf[i] = (x + d * wet).toFloat()
                i++
            }
            pos = (pos + 1) % len
        }
    }
    override fun reset() { line.fill(0f); lp.fill(0.0) }
}

/**
 * Stereo reverb based on the classic parallel comb + series all-pass topology
 * (Schroeder/Moorer). Original implementation.
 */
class Reverb(var room: Double = 0.6, var damping: Double = 0.4, var wet: Double = 0.3, var width: Double = 1.0) : DspProcessor {
    private val combTuning = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
    private val apTuning = intArrayOf(556, 441, 341, 225)
    private val spread = 23
    private var ch = 1
    private lateinit var combs: Array<Array<FloatArray>>
    private lateinit var combPos: Array<IntArray>
    private lateinit var combLp: Array<DoubleArray>
    private lateinit var aps: Array<Array<FloatArray>>
    private lateinit var apPos: Array<IntArray>

    override fun configure(sampleRate: Int, channels: Int) {
        ch = channels
        val scale = sampleRate / 44100.0
        val outs = min(channels, 2)
        combs = Array(outs) { s -> Array(combTuning.size) { FloatArray(((combTuning[it] + s * spread) * scale).toInt().coerceAtLeast(1)) } }
        combPos = Array(outs) { IntArray(combTuning.size) }
        combLp = Array(outs) { DoubleArray(combTuning.size) }
        aps = Array(outs) { s -> Array(apTuning.size) { FloatArray(((apTuning[it] + s * spread) * scale).toInt().coerceAtLeast(1)) } }
        apPos = Array(outs) { IntArray(apTuning.size) }
    }

    override fun process(buf: FloatArray, frames: Int, frameOffset: Long) {
        val fb = 0.7 + room.coerceIn(0.0, 1.0) * 0.28
        val damp = damping.coerceIn(0.0, 1.0) * 0.4
        val outs = min(ch, 2)
        val out = DoubleArray(outs)
        var i = 0
        for (f in 0 until frames) {
            var input = 0.0
            for (c in 0 until ch) input += buf[i + c]
            input = input / ch * 0.015
            for (s in 0 until outs) {
                var acc = 0.0
                for (k in combs[s].indices) {
                    val line = combs[s][k]; val p = combPos[s][k]
                    val y = line[p].toDouble()
                    combLp[s][k] = y * (1 - damp) + combLp[s][k] * damp
                    line[p] = (input + combLp[s][k] * fb).toFloat()
                    combPos[s][k] = (p + 1) % line.size
                    acc += y
                }
                for (k in aps[s].indices) {
                    val line = aps[s][k]; val p = apPos[s][k]
                    val bufOut = line[p].toDouble()
                    val y = -acc + bufOut
                    line[p] = (acc + bufOut * 0.5).toFloat()
                    apPos[s][k] = (p + 1) % line.size
                    acc = y
                }
                out[s] = acc
            }
            if (outs == 2) {
                val w1 = wet * (width / 2 + 0.5); val w2 = wet * ((1 - width) / 2)
                val l = out[0] * w1 + out[1] * w2
                val r = out[1] * w1 + out[0] * w2
                buf[i] = (buf[i] + l * 3).toFloat(); buf[i + 1] = (buf[i + 1] + r * 3).toFloat()
                for (c in 2 until ch) buf[i + c] = (buf[i + c] + (l + r) * 1.5).toFloat()
            } else {
                buf[i] = (buf[i] + out[0] * wet * 3).toFloat()
            }
            i += ch
        }
    }

    override fun reset() {
        combs.forEach { s -> s.forEach { it.fill(0f) } }; aps.forEach { s -> s.forEach { it.fill(0f) } }
        combLp.forEach { it.fill(0.0) }
    }
}

/** Gain + equal-power pan with per-frame automation. */
class GainPan(private val gainAt: (Long) -> Double, private val panAt: (Long) -> Double) : DspProcessor {
    private var ch = 1
    private var sr = 48000
    override fun configure(sampleRate: Int, channels: Int) { ch = channels; sr = sampleRate }
    override fun process(buf: FloatArray, frames: Int, frameOffset: Long) {
        // evaluate automation every 64 frames and interpolate
        var i = 0
        var f = 0
        while (f < frames) {
            val block = min(64, frames - f)
            val t0 = (frameOffset + f) * 1_000_000L / sr
            val t1 = (frameOffset + f + block) * 1_000_000L / sr
            val g0 = gainAt(t0); val g1 = gainAt(t1)
            val pan = panAt(t0).coerceIn(-1.0, 1.0)
            // balance law: unity at centre, the opposite side is attenuated
            val lg = if (ch >= 2 && pan > 0) 1 - pan else 1.0
            val rg = if (ch >= 2 && pan < 0) 1 + pan else 1.0
            for (b in 0 until block) {
                val g = g0 + (g1 - g0) * b / block
                for (c in 0 until ch) {
                    val pg = when (c) { 0 -> lg; 1 -> rg; else -> 1.0 }
                    buf[i] = (buf[i] * g * pg).toFloat()
                    i++
                }
            }
            f += block
        }
    }
}

/** Mid/side stereo width. */
class StereoWidth(var width: Double = 1.3) : DspProcessor {
    private var ch = 1
    override fun configure(sampleRate: Int, channels: Int) { ch = channels }
    override fun process(buf: FloatArray, frames: Int, frameOffset: Long) {
        if (ch < 2) return
        var i = 0
        for (f in 0 until frames) {
            val l = buf[i].toDouble(); val r = buf[i + 1].toDouble()
            val m = (l + r) / 2; val s = (l - r) / 2 * width
            buf[i] = (m + s).toFloat(); buf[i + 1] = (m - s).toFloat()
            i += ch
        }
    }
}

/** Voice effects built from simple DSP blocks. */
class RingModulator(var freq: Double = 90.0, var mix: Double = 0.8) : DspProcessor {
    private var ch = 1; private var sr = 48000; private var phase = 0.0
    override fun configure(sampleRate: Int, channels: Int) { ch = channels; sr = sampleRate }
    override fun process(buf: FloatArray, frames: Int, frameOffset: Long) {
        var i = 0
        val inc = 2 * PI * freq / sr
        for (f in 0 until frames) {
            val m = sin(phase); phase += inc; if (phase > 2 * PI) phase -= 2 * PI
            for (c in 0 until ch) { val x = buf[i].toDouble(); buf[i] = (x * (1 - mix) + x * m * mix).toFloat(); i++ }
        }
    }
}

class Saturator(var drive: Double = 0.5) : DspProcessor {
    private var ch = 1
    override fun configure(sampleRate: Int, channels: Int) { ch = channels }
    override fun process(buf: FloatArray, frames: Int, frameOffset: Long) {
        val k = 1 + drive * 12
        val norm = tanh(k)
        for (i in 0 until frames * ch) buf[i] = (tanh(buf[i] * k) / norm).toFloat()
    }
}

class BitCrusher(var bits: Double = 6.0, var downsample: Double = 6.0) : DspProcessor {
    private var ch = 1; private var counter = 0; private var hold = FloatArray(8)
    override fun configure(sampleRate: Int, channels: Int) { ch = channels; hold = FloatArray(channels) }
    override fun process(buf: FloatArray, frames: Int, frameOffset: Long) {
        val levels = 2.0.pow(bits.coerceIn(2.0, 16.0)) / 2
        val ds = downsample.toInt().coerceAtLeast(1)
        var i = 0
        for (f in 0 until frames) {
            if (counter % ds == 0) for (c in 0 until ch) hold[c] = (round(buf[i + c] * levels) / levels).toFloat()
            counter++
            for (c in 0 until ch) { buf[i] = hold[c]; i++ }
        }
    }
}

class Hiss(var level: Double = 0.02) : DspProcessor {
    private var ch = 1; private val rnd = java.util.Random(7)
    override fun configure(sampleRate: Int, channels: Int) { ch = channels }
    override fun process(buf: FloatArray, frames: Int, frameOffset: Long) {
        for (i in 0 until frames * ch) buf[i] = (buf[i] + rnd.nextGaussian() * level).toFloat()
    }
}

/** De-esser: compresses the high band only. */
class DeEsser(var amount: Double = 0.5, var freq: Double = 6500.0) : DspProcessor {
    private val hp = Biquad(Biquad.Type.HIGHPASS, 6500.0, 0.7)
    private var ch = 1; private var env = 0.0; private var rC = 0.0
    override fun configure(sampleRate: Int, channels: Int) { ch = channels; hp.freq = freq; hp.configure(sampleRate, channels); rC = exp(-1.0 / (0.04 * sampleRate)) }
    override fun process(buf: FloatArray, frames: Int, frameOffset: Long) {
        val thr = dbToLin(-30.0 + (1 - amount) * 20)
        var i = 0
        for (f in 0 until frames) {
            var hiPeak = 0.0
            val his = DoubleArray(ch)
            for (c in 0 until ch) { his[c] = hp.processSample(buf[i + c].toDouble(), c); hiPeak = max(hiPeak, abs(his[c])) }
            env = max(hiPeak, rC * env)
            val g = if (env > thr) (thr / env).pow(amount) else 1.0
            for (c in 0 until ch) { buf[i] = (buf[i] - his[c] * (1 - g)).toFloat(); i++ }
        }
    }
}

/** Runs processors in series. */
class DspChain(val processors: List<DspProcessor>) : DspProcessor {
    override fun configure(sampleRate: Int, channels: Int) = processors.forEach { it.configure(sampleRate, channels) }
    override fun process(buf: FloatArray, frames: Int, frameOffset: Long) = processors.forEach { it.process(buf, frames, frameOffset) }
    override fun reset() = processors.forEach { it.reset() }
    val isEmpty get() = processors.isEmpty()
}
