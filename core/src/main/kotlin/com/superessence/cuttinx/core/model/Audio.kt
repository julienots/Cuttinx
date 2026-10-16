package com.superessence.cuttinx.core.model

import kotlinx.serialization.Serializable

@Serializable
data class EqBand(val frequencyHz: Double, val gainDb: Double = 0.0, val q: Double = 1.0)

@Serializable
data class AudioSettings(
    /** Linear gain, 1.0 = unity, up to 4.0 (+12 dB). */
    val volume: Double = 1.0,
    /** -1 = left, 0 = centre, +1 = right. */
    val pan: Double = 0.0,
    val fadeInUs: Long = 0,
    val fadeOutUs: Long = 0,
    val muted: Boolean = false,
    /** Gain (dB) computed by the normaliser, applied on top of [volume]. */
    val normalizeGainDb: Double = 0.0,
    val eqEnabled: Boolean = false,
    val eqBands: List<EqBand> = DEFAULT_EQ,
    /** Audio effects (compressor, reverb, delay, voice effects, noise reduction...). */
    val effects: List<EffectInstance> = emptyList(),
    /** Keep pitch when speed changes (handled by the engine when possible). */
    val preservePitch: Boolean = true,
) {
    companion object {
        val DEFAULT_EQ = listOf(
            EqBand(60.0), EqBand(170.0), EqBand(310.0), EqBand(600.0), EqBand(1000.0),
            EqBand(3000.0), EqBand(6000.0), EqBand(12000.0),
        )
    }
}
