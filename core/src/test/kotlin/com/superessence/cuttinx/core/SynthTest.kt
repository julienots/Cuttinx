package com.superessence.cuttinx.core

import com.superessence.cuttinx.core.analysis.BeatDetector
import com.superessence.cuttinx.core.audio.AudioAnalysis
import com.superessence.cuttinx.core.resources.Synth
import org.junit.Assert.*
import org.junit.Test

class SynthTest {
    @Test fun allSoundEffectsAreValid() {
        for (s in Synth.sfxList) {
            val a = Synth.sfx(s.id)
            assertTrue("${s.id} empty", a.samples.isNotEmpty())
            assertTrue("${s.id} NaN", a.samples.none { it.isNaN() })
            val pk = AudioAnalysis.peak(a.samples)
            assertTrue("${s.id} peak $pk", pk in 0.5..0.9)
        }
    }

    @Test fun musicHasExpectedTempoAndLevel() {
        for (t in listOf("pop", "funk")) {
            val m = Synth.music(t)
            assertEquals(Synth.musicList.first { it.id == t }.seconds.toDouble(), m.durationUs / 1e6, 0.1)
            assertTrue(m.samples.all { kotlin.math.abs(it) <= 1f })
            val lufs = AudioAnalysis.integratedLoudness(m.samples, Synth.SR, 2)
            assertTrue("$t loudness $lufs", lufs in -24.0..-8.0)
            val bpm = BeatDetector.detect(m.samples, Synth.SR, 2).bpm
            val target = Synth.musicList.first { it.id == t }.bpm.toDouble()
            val ok = listOf(target, target / 2, target * 2).any { kotlin.math.abs(bpm - it) < it * 0.04 }
            assertTrue("$t bpm $bpm vs $target", ok)
        }
    }
}
