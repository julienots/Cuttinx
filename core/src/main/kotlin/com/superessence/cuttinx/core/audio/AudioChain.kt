package com.superessence.cuttinx.core.audio

import com.superessence.cuttinx.core.model.Clip
import com.superessence.cuttinx.core.model.EffectInstance
import com.superessence.cuttinx.core.render.FrameEvaluator

/** Builds the DSP chain of a clip: EQ → effects (in order) → gain/pan automation. */
object AudioChainFactory {
    /** Effects handled by the platform engine rather than by this chain (pitch shifting). */
    val engineEffects = setOf("v_pitch")

    fun build(clip: Clip, trackVolume: Double = 1.0, includeGain: Boolean = true): DspChain {
        val list = ArrayList<DspProcessor>()
        if (clip.audio.eqEnabled && clip.audio.eqBands.any { it.gainDb != 0.0 }) list += Equalizer(clip.audio.eqBands)
        for (fx in clip.audio.effects) if (fx.enabled) effect(fx)?.let { list += it }
        if (includeGain) list += GainPan({ t -> FrameEvaluator.audioGain(clip, t, trackVolume) }, { t -> FrameEvaluator.audioPan(clip, t) })
        return DspChain(list)
    }

    fun effect(fx: EffectInstance): DspProcessor? {
        fun p(k: String, d: Double) = fx.params[k] ?: d
        return when (fx.effectId) {
            "a_compressor" -> Compressor(p("threshold", -18.0), p("ratio", 4.0), p("attack", 10.0), p("release", 120.0), p("makeup", 4.0))
            "a_limiter" -> Limiter(p("ceiling", -1.0), p("release", 80.0))
            "a_reverb" -> Reverb(p("room", 0.6), p("damping", 0.4), p("wet", 0.3), p("width", 1.0))
            "a_delay" -> Delay(p("time", 320.0), p("feedback", 0.4), p("wet", 0.35))
            "a_denoise" -> SpectralDenoiser(p("amount", 0.7), p("sensitivity", 1.5))
            "a_gate" -> NoiseGate(p("threshold", -45.0), p("release", 100.0))
            "a_highpass" -> Biquad(Biquad.Type.HIGHPASS, p("freq", 90.0), 0.707)
            "a_lowpass" -> Biquad(Biquad.Type.LOWPASS, p("freq", 8000.0), 0.707)
            "a_deesser" -> DeEsser(p("amount", 0.5), p("freq", 6500.0))
            "a_stereo_width" -> StereoWidth(p("width", 1.3))
            "v_robot" -> DspChain(listOf(RingModulator(p("freq", 90.0), p("mix", 0.8)), Biquad(Biquad.Type.PEAK, 1200.0, 1.0, 4.0)))
            "v_telephone" -> DspChain(listOf(Biquad(Biquad.Type.HIGHPASS, 320.0, 0.9), Biquad(Biquad.Type.LOWPASS, 3300.0, 0.9), Saturator(p("drive", 0.3))))
            "v_radio" -> DspChain(listOf(Biquad(Biquad.Type.HIGHPASS, 450.0, 0.8), Biquad(Biquad.Type.LOWPASS, 4800.0, 0.8), Saturator(p("drive", 0.5)), Hiss(p("noise", 0.2) * 0.03)))
            "v_megaphone" -> DspChain(listOf(Biquad(Biquad.Type.HIGHPASS, 650.0, 1.0), Biquad(Biquad.Type.LOWPASS, 3800.0, 1.0), Biquad(Biquad.Type.PEAK, 2000.0, 1.2, 8.0), Saturator(p("drive", 0.7)), Limiter(-3.0)))
            "v_cave" -> DspChain(listOf(Delay(140.0 + 160 * p("size", 0.8), 0.45, 0.4), Reverb(0.9 + 0.1 * p("size", 0.8), 0.2, 0.5)))
            "v_bitcrush" -> BitCrusher(p("bits", 6.0), p("downsample", 6.0))
            "v_helium" -> Biquad(Biquad.Type.HIGHSHELF, 4000.0, 0.7, 3.0 * p("amount", 1.0))
            "v_chipmunk" -> Biquad(Biquad.Type.HIGHPASS, 120.0 + 120 * p("amount", 1.0), 0.7)
            "v_child" -> Biquad(Biquad.Type.PEAK, 2500.0, 1.0, 3.0 * p("amount", 1.0))
            "v_deep" -> Biquad(Biquad.Type.LOWSHELF, 150.0, 0.7, 5.0 * p("amount", 1.0))
            "v_giant" -> DspChain(listOf(Biquad(Biquad.Type.LOWSHELF, 140.0, 0.7, 4.0 * p("amount", 1.0)), Reverb(0.85, 0.3, 0.35 * p("amount", 1.0))))
            "v_monster" -> DspChain(listOf(Saturator(0.6 * p("amount", 1.0)), Biquad(Biquad.Type.LOWPASS, 3500.0, 0.7), Biquad(Biquad.Type.LOWSHELF, 120.0, 0.7, 6.0 * p("amount", 1.0))))
            "v_alien" -> DspChain(listOf(RingModulator(30.0, 0.5 * p("amount", 1.0)), Delay(25.0, 0.5, 0.4 * p("amount", 1.0))))
            "v_dark_mask" -> DspChain(listOf(RingModulator(40.0, 0.35 * p("amount", 1.0)), Biquad(Biquad.Type.LOWPASS, 2800.0, 0.7), Reverb(0.4, 0.5, 0.15 * p("amount", 1.0))))
            "v_underwater" -> DspChain(listOf(Biquad(Biquad.Type.LOWPASS, 550.0 + 2000 * (1 - p("amount", 1.0)), 1.2), Delay(18.0, 0.6, 0.5 * p("amount", 1.0)), Reverb(0.7, 0.8, 0.3 * p("amount", 1.0))))
            "v_ghost" -> DspChain(listOf(Biquad(Biquad.Type.HIGHPASS, 300.0, 0.7), Delay(260.0, 0.55, 0.45 * p("amount", 1.0)), Reverb(0.95, 0.2, 0.6 * p("amount", 1.0))))
            "v_stadium" -> DspChain(listOf(Delay(120.0, 0.3, 0.25 * p("amount", 1.0)), Reverb(0.97, 0.3, 0.45 * p("amount", 1.0))))
            "v_walkie" -> DspChain(listOf(Biquad(Biquad.Type.HIGHPASS, 500.0, 0.9), Biquad(Biquad.Type.LOWPASS, 2600.0, 0.9), Saturator(0.8 * p("amount", 1.0)), NoiseGate(-40.0), Hiss(0.01 * p("amount", 1.0))))
            else -> null
        }
    }

    /** Pitch shift (semitones) of the voice presets, applied by the engine on top of their DSP colour. */
    val presetSemitones = mapOf("v_helium" to 12.0, "v_chipmunk" to 8.0, "v_child" to 5.0, "v_deep" to -4.0, "v_giant" to -10.0, "v_monster" to -7.0, "v_alien" to 4.0, "v_dark_mask" to -5.0)

    fun pitchSemitones(clip: Clip): Double = clip.audio.effects.filter { it.enabled }.sumOf { fx ->
        if (fx.effectId == "v_pitch") fx.params["semitones"] ?: 0.0 else presetSemitones[fx.effectId] ?: 0.0
    }.coerceIn(-24.0, 24.0)
}
