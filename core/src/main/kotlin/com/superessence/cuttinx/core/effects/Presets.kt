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
        EffectPreset("builtin_" + name.lowercase().replace(Regex("[^a-z0-9]+"), "_"), name, effects.toList(), builtIn = true, category = CATEGORY_OF[name] ?: "Essentiels")
    private fun preset(cat: String, name: String, vararg effects: EffectInstance) = preset(name, *effects).copy(category = cat)

    /** Families shown as tabs above the filters. */
    val CATEGORIES = listOf("Essentiels", "Tendances", "Portrait", "Cinéma", "Rétro", "Nature", "Nuit", "Noir & blanc", "Créatif")
    private val CATEGORY_OF = mapOf(
        "Blockbuster" to "Cinéma", "Contraste dramatique" to "Cinéma", "Pellicule cinéma" to "Cinéma", "Froid cinéma" to "Cinéma",
        "Nostalgie" to "Rétro", "Super 8" to "Rétro", "Rétro VHS" to "Rétro", "Chaud vintage" to "Rétro", "Vidéo vintage" to "Rétro",
        "Polar" to "Noir & blanc", "Noir intense" to "Noir & blanc", "Néon nocturne" to "Nuit", "Bleu nuit" to "Nuit", "Cyberpunk" to "Nuit",
        "Coucher de soleil" to "Nature", "Forêt" to "Nature", "Été" to "Nature", "Automne" to "Nature",
        "Rêve" to "Créatif", "Clip musical" to "Créatif", "Clip néon" to "Créatif", "Lumière douce" to "Portrait",
    )

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
            // ---- 2.0
            preset("Pellicule cinéma", fx("film_look"), fx("halation"), fx("grain", "amount" to 0.12), fx("letterbox")),
            preset("Bleu nuit", fx("adjust", "temperature" to -0.6, "exposure" to -0.3, "saturation" to -0.2, "contrast" to 0.15), fx("vignette", "amount" to 0.45)),
            preset("Été", fx("adjust", "temperature" to 0.3, "saturation" to 0.3, "brightness" to 0.06, "vibrance" to 0.3)),
            preset("Automne", fx("adjust", "temperature" to 0.4, "tint" to 0.1, "saturation" to -0.05, "shadows" to -0.1), fx("vignette", "amount" to 0.25)),
            preset("Cyberpunk", fx("adjust", "tint" to 0.45, "temperature" to -0.35, "contrast" to 0.3, "saturation" to 0.35), fx("glow", "intensity" to 0.6), fx("chroma_aberration", "amount" to 2.0)),
            preset("Noir intense", fx("bw", "contrast" to 0.7), fx("adjust", "shadows" to -0.3), fx("vignette", "amount" to 0.7)),
            preset("Lumière douce", fx("dreamy"), fx("adjust", "contrast" to -0.1, "brightness" to 0.08, "saturation" to -0.05)),
            preset("Mat", fx("adjust", "fade" to 0.25, "contrast" to -0.15, "saturation" to -0.1)),
            preset("Chaud vintage", fx("vintage_warm")),
            preset("Froid cinéma", fx("cool_cinema"), fx("letterbox")),
            preset("Vidéo vintage", fx("vhs"), fx("adjust", "fade" to 0.2, "saturation" to -0.15), fx("scanlines")),
            preset("Clip néon", fx("neon_edges"), fx("adjust", "contrast" to 0.2)),
            // ---- 2.5
            preset("Portrait", "Portrait doux", fx("skin_smooth", "amount" to 0.5), fx("orton", "amount" to 0.2, "radius" to 5.0), fx("adjust", "brightness" to 0.04, "saturation" to -0.05)),
            preset("Portrait", "Peau dorée", fx("skin_smooth", "amount" to 0.4), fx("split_tone", "shadowHue" to 25.0, "highlightHue" to 40.0, "amount" to 0.3), fx("adjust", "temperature" to 0.15)),
            preset("Portrait", "Studio net", fx("clarity", "amount" to 0.4), fx("adjust", "contrast" to 0.1, "vibrance" to 0.15)),
            preset("Cinéma", "Sans blanchiment", fx("bleach_bypass", "amount" to 0.7), fx("adjust", "contrast" to 0.1), fx("grain", "amount" to 0.1)),
            preset("Cinéma", "Anamorphique", fx("anamorphic_streak", "amount" to 0.6), fx("teal_orange", "amount" to 0.4), fx("letterbox")),
            preset("Cinéma", "Film d'auteur", fx("lift_gamma_gain", "lift" to 0.03, "liftHue" to 200.0, "liftSat" to 0.5, "gain" to 1.05, "gainHue" to 35.0, "gainSat" to 0.4),
                fx("grain", "amount" to 0.1), fx("vignette", "amount" to 0.3)),
            preset("Cinéma", "Thriller vert", fx("split_tone", "shadowHue" to 140.0, "highlightHue" to 60.0, "amount" to 0.5), fx("adjust", "saturation" to -0.2, "contrast" to 0.2)),
            preset("Rétro", "Traitement croisé", fx("cross_process", "amount" to 0.7)),
            preset("Rétro", "Console 8 bits", fx("dither_retro", "levels" to 4.0, "pixel" to 3.0)),
            preset("Rétro", "Bobine 16 mm", fx("gate_weave", "amount" to 0.6), fx("old_film", "amount" to 0.3), fx("grain", "amount" to 0.25)),
            preset("Rétro", "Instantané", fx("adjust", "fade" to 0.25, "temperature" to 0.2), fx("split_tone", "shadowHue" to 190.0, "highlightHue" to 45.0, "amount" to 0.3), fx("vignette", "amount" to 0.35)),
            preset("Nature", "Ciel profond", fx("selective_hue", "target" to 210.0, "range" to 40.0, "saturation" to 0.4, "lightness" to -0.15)),
            preset("Nature", "Vert luxuriant", fx("selective_hue", "target" to 110.0, "range" to 45.0, "saturation" to 0.35), fx("clarity", "amount" to 0.3)),
            preset("Nature", "Heure dorée", fx("split_tone", "shadowHue" to 220.0, "highlightHue" to 35.0, "amount" to 0.55), fx("orton", "amount" to 0.25), fx("adjust", "temperature" to 0.25)),
            preset("Nuit", "Néon bleu", fx("selective_hue", "target" to 220.0, "range" to 50.0, "saturation" to 0.5), fx("glow", "intensity" to 0.5), fx("adjust", "contrast" to 0.2)),
            preset("Nuit", "Nuit américaine", fx("lift_gamma_gain", "lift" to -0.02, "gain" to 0.8, "gainHue" to 220.0, "gainSat" to 0.6), fx("adjust", "saturation" to -0.4, "exposure" to -0.2)),
            preset("Noir & blanc", "Argentique", fx("bw", "contrast" to 0.4), fx("clarity", "amount" to 0.3), fx("grain", "amount" to 0.2)),
            preset("Noir & blanc", "N&B doux", fx("bw", "contrast" to 0.1), fx("adjust", "fade" to 0.2)),
            preset("Créatif", "3D rétro", fx("anaglyph", "depth" to 8.0), fx("adjust", "contrast" to 0.15)),
            preset("Créatif", "Rêve éveillé", fx("orton", "amount" to 0.7), fx("adjust", "saturation" to 0.1)),
            preset("Créatif", "Pop vibrant", fx("clarity", "amount" to 0.5), fx("adjust", "saturation" to 0.35, "vibrance" to 0.3)),
            preset("Essentiels", "Naturel net", fx("clarity", "amount" to 0.25), fx("adjust", "vibrance" to 0.15)),
            // ---- 3.3
            preset("Tendances", "Soirée club", fx("club_lights", "amount" to 0.6), fx("adjust", "contrast" to 0.2, "saturation" to 0.25), fx("glow", "intensity" to 0.4)),
            preset("Tendances", "Carte premium", fx("light_sweep", "amount" to 0.6), fx("clarity", "amount" to 0.3), fx("adjust", "contrast" to 0.1, "vibrance" to 0.2)),
            preset("Tendances", "Pop sérigraphie", fx("pop_art")),
            preset("Tendances", "Jour de pluie", fx("rain_window", "amount" to 0.7), fx("adjust", "temperature" to -0.35, "saturation" to -0.2, "contrast" to 0.1), fx("vignette", "amount" to 0.3)),
            preset("Tendances", "Fête", fx("confetti", "amount" to 0.8), fx("adjust", "saturation" to 0.3, "brightness" to 0.05)),
            preset("Tendances", "Paillettes", fx("gold_dust", "amount" to 0.7), fx("split_tone", "shadowHue" to 30.0, "highlightHue" to 45.0, "amount" to 0.3), fx("orton", "amount" to 0.2)),
            preset("Tendances", "Souvenir instantané", fx("instant_frame"), fx("adjust", "fade" to 0.15, "temperature" to 0.15)),
            preset("Tendances", "Néon arcade", fx("neon_frame", "hue" to 190.0), fx("selective_hue", "target" to 300.0, "range" to 50.0, "saturation" to 0.4), fx("adjust", "contrast" to 0.2)),
            preset("Tendances", "Arc-en-ciel vivant", fx("color_cycle", "amount" to 0.6, "speed" to 0.6), fx("adjust", "saturation" to 0.2)),
            preset("Tendances", "Bulles de rêve", fx("bubbles", "amount" to 0.7), fx("dreamy"), fx("adjust", "brightness" to 0.05)),
            preset("Portrait", "Sujet net", fx("focus_blur", "radius" to 10.0, "focus" to 0.22), fx("skin_smooth", "amount" to 0.3), fx("adjust", "vibrance" to 0.1)),
            preset("Cinéma", "Étoiles de nuit", fx("star_filter", "amount" to 0.6), fx("adjust", "temperature" to -0.25, "contrast" to 0.15), fx("letterbox")),
            preset("Cinéma", "Grand angle", fx("lens_distortion", "amount" to 0.35), fx("teal_orange", "amount" to 0.35), fx("vignette", "amount" to 0.35)),
            preset("Rétro", "Bobine poussiéreuse", fx("film_dust"), fx("sepia", "amount" to 0.5), fx("vignette", "amount" to 0.5)),
            preset("Créatif", "Terminal", fx("ascii_blocks")),
            preset("Créatif", "Radio", fx("xray")),
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
