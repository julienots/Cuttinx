package com.superessence.cuttinx.core.audio

import kotlin.math.*

/*
 * CUTTINX 3.5 audio blocks: original streaming processors (time-based modulations are driven by
 * [frameOffset], so a seek always lands on the same phase in the preview and in the export).
 */

/**
 * « Son 8D » : the sound turns around the listener's head. Equal-power rotation, a little
 * interaural delay and a darker far ear, so it really sounds like it goes behind you on headphones.
 */
class Rotate8D(var rateHz: Double = 0.12, var depth: Double = 1.0) : DspProcessor {
    private var ch = 1; private var sr = 48000
    private val farLp = DoubleArray(2)
    private var line = FloatArray(2048); private var pos = 0
    override fun configure(sampleRate: Int, channels: Int) { ch = channels; sr = sampleRate; line = FloatArray(2048); pos = 0 }
    override fun process(buf: FloatArray, frames: Int, frameOffset: Long) {
        if (ch < 2) return
        val maxDelay = (0.0007 * sr).toInt().coerceIn(1, 1000)
        var i = 0
        for (f in 0 until frames) {
            val t = (frameOffset + f).toDouble() / sr
            val a = 2 * PI * rateHz * t
            val pan = sin(a) * depth                        // -1 left … +1 right
            val behind = (1 - cos(a)) / 2 * depth           // 0 front … 1 behind
            val m = (buf[i] + buf[i + 1]) * 0.5f
            line[pos] = m.toFloat()
            val dl = (max(0.0, pan) * maxDelay).toInt()     // left ear further when the source is right
            val dr = (max(0.0, -pan) * maxDelay).toInt()
            val sl = line[(pos - dl + line.size) % line.size].toDouble()
            val sr2 = line[(pos - dr + line.size) % line.size].toDouble()
            val gl = cos((pan + 1) * PI / 4); val gr = sin((pan + 1) * PI / 4)
            // far ear and "behind" are darker (one-pole low-pass)
            val kl = 0.25 + 0.75 * (1 - max(0.0, pan) * 0.6) * (1 - behind * 0.35)
            val kr = 0.25 + 0.75 * (1 - max(0.0, -pan) * 0.6) * (1 - behind * 0.35)
            farLp[0] += (sl - farLp[0]) * kl; farLp[1] += (sr2 - farLp[1]) * kr
            val dry = 1 - depth.coerceIn(0.0, 1.0)
            buf[i] = (buf[i] * dry + farLp[0] * gl * 1.41 * (1 - dry)).toFloat()
            buf[i + 1] = (buf[i + 1] * dry + farLp[1] * gr * 1.41 * (1 - dry)).toFloat()
            pos = (pos + 1) % line.size
            i += ch
        }
    }
    override fun reset() { line.fill(0f); farLp.fill(0.0) }
}

/** Volume modulation (sine), [depth] 0..1. */
class Tremolo(var rateHz: Double = 5.0, var depth: Double = 0.5) : DspProcessor {
    private var ch = 1; private var sr = 48000
    override fun configure(sampleRate: Int, channels: Int) { ch = channels; sr = sampleRate }
    override fun process(buf: FloatArray, frames: Int, frameOffset: Long) {
        var i = 0
        for (f in 0 until frames) {
            val g = 1 - depth * (0.5 + 0.5 * sin(2 * PI * rateHz * (frameOffset + f) / sr))
            for (c in 0 until ch) { buf[i] = (buf[i] * g).toFloat(); i++ }
        }
    }
}

/**
 * Modulated delay line: chorus (several detuned voices), flanger-like when short, tape wow & flutter
 * when [mix] is 1 (no dry signal).
 */
class ModDelay(var baseMs: Double = 18.0, var depthMs: Double = 4.0, var rateHz: Double = 0.8, var mix: Double = 0.5, var voices: Int = 2) : DspProcessor {
    private var ch = 1; private var sr = 48000
    private var line = FloatArray(1); private var len = 1; private var pos = 0
    override fun configure(sampleRate: Int, channels: Int) {
        ch = channels; sr = sampleRate
        len = ((baseMs + depthMs + 5) * 0.001 * sampleRate).toInt().coerceAtLeast(4)
        line = FloatArray(len * channels); pos = 0
    }
    override fun process(buf: FloatArray, frames: Int, frameOffset: Long) {
        var i = 0
        val nv = voices.coerceIn(1, 4)
        for (f in 0 until frames) {
            val t = (frameOffset + f).toDouble() / sr
            for (c in 0 until ch) line[pos * ch + c] = buf[i + c]
            for (c in 0 until ch) {
                var wet = 0.0
                for (v in 0 until nv) {
                    val ph = 2 * PI * (rateHz * (1 + v * 0.13) * t) + v * 2.1 + c * 0.7
                    val dSamples = (baseMs + depthMs * (0.5 + 0.5 * sin(ph))) * 0.001 * sr
                    val di = floor(dSamples).toInt(); val fr = dSamples - di
                    val a = line[((pos - di + len * 4) % len) * ch + c]
                    val b = line[((pos - di - 1 + len * 4) % len) * ch + c]
                    wet += a + (b - a) * fr
                }
                wet /= nv
                buf[i + c] = (buf[i + c] * (1 - mix) + wet * mix).toFloat()
            }
            pos = (pos + 1) % len
            i += ch
        }
    }
    override fun reset() { line.fill(0f) }
}

/** Phaser: 4 first-order all-pass stages swept by a slow sine. */
class Phaser(var rateHz: Double = 0.4, var depth: Double = 0.7, var mix: Double = 0.5) : DspProcessor {
    private var ch = 1; private var sr = 48000
    private var z = Array(2) { DoubleArray(4) }
    override fun configure(sampleRate: Int, channels: Int) { ch = channels; sr = sampleRate; z = Array(channels) { DoubleArray(4) } }
    override fun process(buf: FloatArray, frames: Int, frameOffset: Long) {
        var i = 0
        for (f in 0 until frames) {
            val lfo = 0.5 + 0.5 * sin(2 * PI * rateHz * (frameOffset + f) / sr)
            val fc = 300.0 + (2500.0 * depth) * lfo
            val tn = tan(PI * fc / sr)
            val a = (tn - 1) / (tn + 1)
            for (c in 0 until ch) {
                var x = buf[i].toDouble()
                val st = z[c]
                for (s in 0 until 4) { val y = a * x + st[s]; st[s] = x - a * y; x = y }
                buf[i] = (buf[i] * (1 - mix) + x * mix).toFloat()
                i++
            }
        }
    }
    override fun reset() { z.forEach { it.fill(0.0) } }
}

/** Vinyl surface noise: soft rumble and random clicks (deterministic from the position). */
class VinylCrackle(var amount: Double = 0.5) : DspProcessor {
    private var ch = 1; private var sr = 48000; private var rumble = 0.0
    override fun configure(sampleRate: Int, channels: Int) { ch = channels; sr = sampleRate }
    override fun process(buf: FloatArray, frames: Int, frameOffset: Long) {
        var i = 0
        val clickProb = 0.00025 + 0.0015 * amount
        for (f in 0 until frames) {
            val n = frameOffset + f
            val h = hash(n)
            rumble += ((hash(n * 31 + 7) - 0.5) * 0.02 - rumble) * 0.002
            val click = if (h < clickProb) (hash(n * 13 + 3) - 0.5) * 0.5 * amount else 0.0
            val add = click + rumble * amount + (hash(n * 7 + 1) - 0.5) * 0.004 * amount
            for (c in 0 until ch) { buf[i] = (buf[i] + add).toFloat(); i++ }
        }
    }
    private fun hash(n: Long): Double {
        var x = n * 6364136223846793005L + 1442695040888963407L
        x = x xor (x ushr 33); x *= -0x40a7b892e31b1a47L; x = x xor (x ushr 29)
        return ((x ushr 11).toDouble() / (1L shl 53).toDouble())
    }
}

/** Soft-knee "smile" EQ + glue compression + limiter: a quick master for social videos. */
object HyperChains {
    fun bassBoost(amount: Double) = DspChain(listOf(
        Biquad(Biquad.Type.LOWSHELF, 90.0, 0.7, 9.0 * amount),
        Biquad(Biquad.Type.PEAK, 55.0, 1.2, 3.0 * amount),
        Saturator(0.15 * amount),
        Limiter(-1.0),
    ))

    fun lofi(amount: Double) = DspChain(listOf(
        Biquad(Biquad.Type.HIGHPASS, 120.0, 0.7),
        Biquad(Biquad.Type.LOWPASS, 9000.0 - 5500.0 * amount, 0.7),
        ModDelay(6.0, 1.5 * amount, 0.6, 1.0, 1),
        BitCrusher(16.0 - 5.0 * amount, 1.0),
        Saturator(0.2 * amount),
        VinylCrackle(0.5 * amount),
    ))

    fun nextRoom(amount: Double) = DspChain(listOf(
        Biquad(Biquad.Type.LOWPASS, 1200.0 - 850.0 * amount, 0.8),
        Biquad(Biquad.Type.LOWSHELF, 110.0, 0.7, 4.0 * amount),
        Reverb(0.75, 0.6, 0.35 * amount, 0.6),
    ))

    fun master(amount: Double) = DspChain(listOf(
        Biquad(Biquad.Type.HIGHPASS, 30.0, 0.7),
        Biquad(Biquad.Type.LOWSHELF, 100.0, 0.7, 2.5 * amount),
        Biquad(Biquad.Type.PEAK, 350.0, 0.9, -1.5 * amount),
        Biquad(Biquad.Type.HIGHSHELF, 9000.0, 0.7, 3.0 * amount),
        Compressor(-20.0, 2.5, 15.0, 150.0, 3.0 * amount),
        StereoWidth(1.0 + 0.2 * amount),
        Limiter(-1.0),
    ))

    fun choir(amount: Double) = DspChain(listOf(
        ModDelay(22.0, 9.0, 0.5, 0.6 * amount, 4),
        Reverb(0.85, 0.4, 0.35 * amount, 1.0),
    ))

    fun announcer(amount: Double) = DspChain(listOf(
        Biquad(Biquad.Type.HIGHPASS, 70.0, 0.7),
        Biquad(Biquad.Type.LOWSHELF, 140.0, 0.7, 4.0 * amount),
        Biquad(Biquad.Type.PEAK, 3200.0, 1.0, 3.0 * amount),
        Compressor(-24.0, 4.0, 5.0, 100.0, 6.0 * amount),
        Limiter(-1.0),
    ))

    fun asmr(amount: Double) = DspChain(listOf(
        Biquad(Biquad.Type.HIGHPASS, 60.0, 0.7),
        Biquad(Biquad.Type.HIGHSHELF, 6000.0, 0.7, 5.0 * amount),
        Compressor(-30.0, 3.0, 3.0, 80.0, 8.0 * amount),
        StereoWidth(1.0 + 0.6 * amount),
        Limiter(-1.0),
    ))
}
