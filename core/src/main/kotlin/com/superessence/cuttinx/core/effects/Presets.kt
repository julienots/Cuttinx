package com.superessence.cuttinx.core.effects

import com.superessence.cuttinx.core.model.EffectInstance
import com.superessence.cuttinx.core.util.Ids
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A saved combination of effects ("look"). Presets can be exported / imported as JSON. */
@Serializable
data class EffectPreset(
    val id: String,
    val name: String,
    val effects: List<EffectInstance>,
    val builtIn: Boolean = false,
    val category: String = "Filtres",
)

@Serializable
data class PresetPack(val format: String = "cuttinx-presets", val version: Int = 1, val presets: List<EffectPreset>)

object Presets {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    private fun fx(effect: String, vararg params: Pair<String, Double>) =
        EffectInstance(Ids.new("fx"), effect, EffectRegistry[effect]!!.defaults() + params.toMap())

    private fun preset(name: String, vararg effects: EffectInstance) =
        EffectPreset("builtin_" + name.lowercase().replace(Regex("[^a-z0-9]+"), "_"), name, effects.toList(), builtIn = true)

    /** Original colour looks designed for CUTTINX. */
    val builtIn: List<EffectPreset> by lazy {
        listOf(
            preset("Éclat", fx("adjust", "brightness" to 0.05, "contrast" to 0.15, "saturation" to 0.2, "vibrance" to 0.3)),
            preset("Doré", fx("adjust", "temperature" to 0.45, "saturation" to 0.1, "highlights" to -0.1), fx("vignette", "amount" to 0.3)),
            preset("Glacier", fx("adjust", "temperature" to -0.5, "contrast" to 0.1, "saturation" to -0.15)),
            preset("Blockbuster", fx("teal_orange", "amount" to 0.7), fx("adjust", "contrast" to 0.2), fx("letterbox")),
            preset("Nostalgie", fx("adjust", "fade" to 0.35, "temperature" to 0.25, "saturation" to -0.2), fx("grain", "amount" to 0.2)),
            preset("Polar", fx("bw", "contrast" to 0.45), fx("vignette", "amount" to 0.6), fx("grain", "amount" to 0.15)),
            preset("Néon nocturne", fx("adjust", "contrast" to 0.25, "saturation" to 0.4, "temperature" to -0.3, "tint" to 0.3), fx("glow", "intensity" to 0.8)),
            preset("Super 8", fx("old_film", "amount" to 0.5), fx("adjust", "fade" to 0.2), fx("vignette", "amount" to 0.5)),
            preset("Rétro VHS", fx("vhs"), fx("chroma_aberration", "amount" to 3.0)),
            preset("Pastel", fx("adjust", "fade" to 0.3, "saturation" to -0.25, "brightness" to 0.08, "highlights" to 0.1)),
            preset("Contraste dramatique", fx("adjust", "contrast" to 0.45, "shadows" to -0.25, "highlights" to 0.15, "saturation" to -0.1)),
            preset("Rêve", fx("dreamy"), fx("adjust", "saturation" to -0.1, "brightness" to 0.05)),
            preset("Clip musical", fx("rgb_split", "amount" to 6.0), fx("adjust", "contrast" to 0.2, "saturation" to 0.3)),
            preset("Coucher de soleil", fx("gradient_overlay", "hueA" to 25.0, "hueB" to 300.0, "opacity" to 0.25, "blend" to 1.0), fx("adjust", "temperature" to 0.3)),
            preset("Forêt", fx("adjust", "tint" to -0.3, "saturation" to 0.15, "shadows" to 0.1, "temperature" to 0.1)),
        )
    }

    fun export(presets: List<EffectPreset>): String = json.encodeToString(PresetPack(presets = presets))

    /** Parses a preset pack; unknown effects are dropped so that a pack never breaks the app. */
    fun import(text: String): List<EffectPreset> {
        val pack = json.decodeFromString<PresetPack>(text)
        require(pack.format == "cuttinx-presets") { "Ce fichier n'est pas un pack de préréglages CUTTINX" }
        return pack.presets.map { pr ->
            pr.copy(id = Ids.new("preset"), builtIn = false, effects = pr.effects.filter { EffectRegistry[it.effectId] != null }.map { it.copy(id = Ids.new("fx")) })
        }
    }

    /** Fresh copy of a preset's effects with new instance ids, ready to be appended to a clip. */
    fun instantiate(preset: EffectPreset): List<EffectInstance> = preset.effects.map { it.copy(id = Ids.new("fx")) }
}
