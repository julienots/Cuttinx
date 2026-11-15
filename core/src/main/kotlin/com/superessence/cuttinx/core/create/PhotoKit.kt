package com.superessence.cuttinx.core.create

import com.superessence.cuttinx.core.effects.EffectPreset
import com.superessence.cuttinx.core.effects.EffectRegistry
import com.superessence.cuttinx.core.model.EffectInstance
import com.superessence.cuttinx.core.smart.Magic

/** The sliders of the photo studio (all 0 = untouched). */
data class PhotoAdjust(
    val exposure: Double = 0.0, val brightness: Double = 0.0, val contrast: Double = 0.0, val saturation: Double = 0.0,
    val vibrance: Double = 0.0, val temperature: Double = 0.0, val tint: Double = 0.0, val highlights: Double = 0.0,
    val shadows: Double = 0.0, val clarity: Double = 0.0, val vignette: Double = 0.0, val grain: Double = 0.0, val skin: Double = 0.0,
) {
    val untouched get() = this == PhotoAdjust()
}

/** Builds the GPU effect chain of a photo: a look (filter) first, then the user's adjustments. */
object PhotoKit {
    private fun fx(id: String, params: Map<String, Double>): EffectInstance? =
        EffectRegistry[id]?.let { d -> EffectInstance("photo_$id", id, d.defaults() + params.filterKeys { k -> d.params.any { it.key == k } }) }

    fun effects(look: EffectPreset?, a: PhotoAdjust, lookAmount: Double = 1.0): List<EffectInstance> {
        val out = ArrayList<EffectInstance>()
        if (look != null && lookAmount > 0.01) out += look.effects.map { e ->
            // weaker look: pull every "amount"/"opacity"/"intensity" towards 0
            if (lookAmount >= 0.99) e else e.copy(params = e.params.mapValues { (k, v) -> if (k in setOf("amount", "opacity", "intensity", "mix")) v * lookAmount else v })
        }
        if (a.skin > 0.01) fx("skin_smooth", mapOf("amount" to a.skin))?.let { out += it }
        val adj = mapOf("exposure" to a.exposure, "brightness" to a.brightness, "contrast" to a.contrast, "saturation" to a.saturation,
            "vibrance" to a.vibrance, "temperature" to a.temperature, "tint" to a.tint, "highlights" to a.highlights, "shadows" to a.shadows)
        if (adj.values.any { kotlin.math.abs(it) > 0.001 }) fx("adjust", adj)?.let { out += it }
        if (kotlin.math.abs(a.clarity) > 0.01) fx("clarity", mapOf("amount" to a.clarity))?.let { out += it }
        if (a.vignette > 0.01) fx("vignette", mapOf("amount" to a.vignette))?.let { out += it }
        if (a.grain > 0.01) fx("grain", mapOf("amount" to a.grain * 0.4))?.let { out += it }
        return out
    }

    /**
     * Automatic enhancement from the measured colour of the photo: exposure towards a pleasant
     * brightness, neutral white balance, a little more colour and local contrast.
     */
    fun auto(look: Magic.Look): PhotoAdjust {
        val l = look.luma.coerceIn(0.05, 0.95)
        val target = Magic.Look(0.48, 0.48, 0.48, (look.sat * 1.15).coerceIn(0.18, 0.4))
        val c = Magic.correction(look, target, 0.8)
        return PhotoAdjust(
            exposure = c.getValue("exposure").coerceIn(-0.8, 0.8), temperature = c.getValue("temperature"), tint = c.getValue("tint"),
            saturation = c.getValue("saturation").coerceIn(-0.2, 0.3), vibrance = 0.15,
            contrast = if (l in 0.3..0.7) 0.08 else 0.0, shadows = if (l < 0.35) 0.15 else 0.0, highlights = if (l > 0.65) -0.15 else 0.0, clarity = 0.2,
        )
    }
}
