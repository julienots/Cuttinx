package com.superessence.cuttinx.core

import com.superessence.cuttinx.core.audio.*
import com.superessence.cuttinx.core.model.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10

class AudioTest {
    private val sr = 48000

    @Test fun biquadLowpassResponse() {
        val lp = Biquad(Biquad.Type.LOWPASS, 1000.0, 0.7071).also { it.configure(sr, 1) }
        assertEquals(-3.0, lp.responseDb(1000.0), 0.2)
        assertTrue(lp.responseDb(100.0) > -0.2)
        assertTrue(lp.responseDb(10000.0) < -35)
        val peak = Biquad(Biquad.Type.PEAK, 2000.0, 1.0, 6.0).also { it.configure(sr, 1) }
        assertEquals(6.0, peak.responseDb(2000.0), 0.1)
        // filtering a 10 kHz sine through the LPF strongly attenuates it
        val s = T.sine(10000.0, 0.5)
        lp.process(s, s.size, 0)
        assertTrue(T.power(s.copyOfRange(2000, s.size)) < T.power(T.sine(10000.0, 0.5)) * 0.001)
    }

    @Test fun compressorReducesLoudPartsOnly() {
        val loud = T.sine(440.0, 1.0, amp = 0.9)
        val quiet = T.sine(440.0, 1.0, amp = 0.02)
        val comp = Compressor(-20.0, 4.0, 5.0, 50.0, 0.0).also { it.configure(sr, 1) }
        val l2 = loud.copyOf(); comp.process(l2, l2.size, 0)
        comp.reset()
        val q2 = quiet.copyOf(); comp.process(q2, q2.size, 0)
        val lossLoud = 10 * log10(T.power(l2.copyOfRange(sr / 2, sr)) / T.power(loud.copyOfRange(sr / 2, sr)))
        val lossQuiet = 10 * log10(T.power(q2.copyOfRange(sr / 2, sr)) / T.power(quiet.copyOfRange(sr / 2, sr)))
        assertTrue("loud reduced by $lossLoud dB", lossLoud < -8)
        assertTrue("quiet untouched: $lossQuiet dB", abs(lossQuiet) < 0.5)
    }

    @Test fun limiterNeverExceedsCeiling() {
        val s = T.sine(100.0, 1.0, amp = 1.8)
        val lim = Limiter(-1.0).also { it.configure(sr, 1) }
        lim.process(s, s.size, 0)
        val ceil = dbToLin(-1.0)
        assertTrue(s.all { abs(it) <= ceil + 1e-6 })
    }

    @Test fun delayProducesEchoAtRightTime() {
        val impulse = FloatArray(sr).also { it[0] = 1f }
        val d = Delay(250.0, 0.0, 0.5).also { it.configure(sr, 1) }
        d.process(impulse, impulse.size, 0)
        val idx = sr / 4
        assertEquals(0.5f, impulse[idx], 0.05f)
        assertEquals(0f, impulse[idx / 2], 1e-6f)
    }

    @Test fun reverbAddsTail() {
        val buf = T.concat(T.sine(500.0, 0.1, channels = 2), T.silence(1.0, channels = 2))
        val rv = Reverb(0.8, 0.3, 0.5).also { it.configure(sr, 2) }
        rv.process(buf, buf.size / 2, 0)
        val tail = buf.copyOfRange((0.3 * sr).toInt() * 2, (0.6 * sr).toInt() * 2)
        assertTrue("reverb tail energy", T.power(tail) > 1e-6)
    }

    @Test fun denoiserImprovesSnr() {
        val clean = T.concat(T.silence(1.0), T.sine(600.0, 2.0, amp = 0.3), T.silence(0.5))
        val noise = T.noise(3.5, 0.03)
        val noisy = FloatArray(clean.size) { clean[it] + noise[it] }
        val out = OfflineAudio.denoise(noisy, sr, 1, 0.9)
        assertEquals(noisy.size, out.size)
        fun snr(x: FloatArray): Double {
            var sig = 0.0; var err = 0.0
            for (i in sr until x.size - sr) { sig += clean[i].toDouble() * clean[i]; val e = x[i] - clean[i]; err += e.toDouble() * e }
            return 10 * log10(sig / err)
        }
        val before = snr(noisy); val after = snr(out)
        assertTrue("SNR before=$before after=$after", after > before + 4)
        // noise-only part strongly reduced
        val nb = T.power(noisy.copyOfRange(sr / 4, sr * 3 / 4)); val na = T.power(out.copyOfRange(sr / 4, sr * 3 / 4))
        assertTrue(10 * log10(na / nb) < -10)
    }

    @Test fun streamingDenoiserKeepsLength() {
        val d = SpectralDenoiser(0.8).also { it.configure(sr, 2) }
        val buf = T.noise(0.3, 0.1).let { m -> FloatArray(m.size * 2) { m[it / 2] } }
        d.process(buf, buf.size / 2, 0)
        assertEquals(1024, d.latencyFrames)
    }

    @Test fun loudnessMatchesBs1770Reference() {
        // BS.1770: a 0 dBFS 997 Hz sine in one channel reads -3.01 LKFS
        val s = T.sine(997.0, 5.0, amp = 1.0)
        assertEquals(-3.01, AudioAnalysis.integratedLoudness(s, sr, 1), 0.15)
        val q = T.sine(997.0, 5.0, amp = 0.1) // -20 dB
        assertEquals(-23.01, AudioAnalysis.integratedLoudness(q, sr, 1), 0.15)
        val g = AudioAnalysis.normalizationGain(q, sr, 1, -14.0, -1.0)
        assertEquals(9.0, g, 0.2)
    }

    @Test fun clippingDetection() {
        val s = T.concat(T.sine(200.0, 0.5, amp = 0.5), T.sine(200.0, 0.5, amp = 1.5).map { it.coerceIn(-1f, 1f) }.toFloatArray())
        val ev = AudioAnalysis.clipping(s, sr, 1)
        assertTrue(ev.isNotEmpty())
        assertTrue(ev.first().startUs >= 490_000)
        val rep = AudioAnalysis.report(s, sr, 1)
        assertTrue(rep.recommendations.any { it.contains("Saturation") })
    }

    @Test fun chainFactoryBuildsAllAudioEffects() {
        for (def in com.superessence.cuttinx.core.effects.EffectRegistry.audio()) {
            if (def.id in AudioChainFactory.engineEffects) continue
            val fx = EffectInstance("x", def.id, def.defaults())
            val proc = AudioChainFactory.effect(fx)
            assertNotNull("no DSP for ${def.id}", proc)
            proc!!.configure(sr, 2)
            val buf = T.sine(440.0, 0.2, channels = 2)
            proc.process(buf, buf.size / 2, 0)
            assertTrue("${def.id} produced NaN", buf.none { it.isNaN() || it.isInfinite() })
        }
        val clip = Clip("c", sourceOutUs = 1_000_000, audio = AudioSettings(volume = 0.5, eqEnabled = true, eqBands = AudioSettings.DEFAULT_EQ.mapIndexed { i, b -> if (i == 3) b.copy(gainDb = 6.0) else b }))
        val chain = AudioChainFactory.build(clip)
        chain.configure(sr, 2)
        val buf = T.sine(5000.0, 0.5, channels = 2, amp = 0.5)
        chain.process(buf, buf.size / 2, 0)
        assertEquals(0.25, AudioAnalysis.peak(buf), 0.03)
    }

    @Test fun vocalRemovalCancelsCentredSignal() {
        val voice = T.sine(800.0, 1.0, amp = 0.4)
        val st = FloatArray(voice.size * 2) { voice[it / 2] } // centred
        val out = OfflineAudio.removeVocals(st, sr, 2)
        assertTrue(T.power(out) < T.power(st) * 0.01)
    }
}
