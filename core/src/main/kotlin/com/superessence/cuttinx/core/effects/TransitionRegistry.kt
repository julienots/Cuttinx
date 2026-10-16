package com.superessence.cuttinx.core.effects

import kotlinx.serialization.Serializable

/** Modification applied to one layer during a transition. Offsets are fractions of canvas size. */
data class LayerMod(
    val alpha: Double = 1.0,
    val dx: Double = 0.0,
    val dy: Double = 0.0,
    val scale: Double = 1.0,
    val rotation: Double = 0.0,
    /** Optional transition shader (an internal `_tr_*` effect) and its progress uniform. */
    val shaderId: String? = null,
    val shaderProgress: Double = 1.0,
    val shaderParams: Map<String, Double> = emptyMap(),
)

@Serializable
data class TransitionDefinition(
    val id: String,
    val name: String,
    val params: List<ParamDef> = emptyList(),
    val defaultDurationUs: Long = 500_000,
)

/**
 * Original transitions. A transition is evaluated per layer: the outgoing clip (A) and the
 * incoming clip (B). [incomingOnTop] tells which of the two is composited above the other, so that
 * every transition looks identical whatever the compositing order is.
 */
object TransitionRegistry {
    private val defs = LinkedHashMap<String, TransitionDefinition>()
    val all: List<TransitionDefinition> get() = defs.values.toList()
    operator fun get(id: String) = defs[id]
    fun register(d: TransitionDefinition) { defs[d.id] = d }

    private val dirChoice = ParamDef("direction", "Direction", 0.0, 3.0, 0.0, ParamType.CHOICE, listOf("Gauche", "Droite", "Haut", "Bas"))

    init {
        register(TransitionDefinition("fade", "Fondu enchaîné"))
        register(TransitionDefinition("fade_black", "Fondu au noir", defaultDurationUs = 800_000))
        register(TransitionDefinition("fade_white", "Fondu au blanc", defaultDurationUs = 700_000))
        register(TransitionDefinition("dissolve", "Dissolution", listOf(ParamDef("grain", "Grain", 2.0, 200.0, 60.0))))
        register(TransitionDefinition("slide", "Glissement", listOf(dirChoice)))
        register(TransitionDefinition("cover", "Recouvrement", listOf(dirChoice)))
        register(TransitionDefinition("zoom_in", "Zoom avant"))
        register(TransitionDefinition("zoom_out", "Zoom arrière"))
        register(TransitionDefinition("spin", "Rotation", listOf(ParamDef("turns", "Tours", 0.25, 3.0, 1.0))))
        register(TransitionDefinition("wipe", "Balayage", listOf(ParamDef("angle", "Angle", 0.0, 360.0, 0.0, unit = "°"), ParamDef("softness", "Douceur", 0.0, 0.3, 0.05))))
        register(TransitionDefinition("iris", "Masque iris", listOf(ParamDef("softness", "Douceur", 0.0, 0.3, 0.04))))
        register(TransitionDefinition("blur", "Flou", listOf(ParamDef("radius", "Rayon", 5.0, 60.0, 30.0))))
        register(TransitionDefinition("flash", "Flash", defaultDurationUs = 400_000))
        register(TransitionDefinition("glitch", "Glitch", listOf(ParamDef("intensity", "Intensité", 0.1, 1.0, 1.0)), defaultDurationUs = 400_000))
        register(TransitionDefinition("distort", "Distorsion", listOf(ParamDef("amount", "Intensité", 0.1, 1.0, 1.0))))
        register(TransitionDefinition("light", "Lumière", listOf(ParamDef("hue", "Teinte", 0.0, 360.0, 35.0, ParamType.HUE))))
        register(TransitionDefinition("whip", "Panoramique filé", listOf(dirChoice), defaultDurationUs = 350_000))
    }

    private fun dirVec(d: Double): Pair<Double, Double> = when (d.toInt()) {
        0 -> -1.0 to 0.0; 1 -> 1.0 to 0.0; 2 -> 0.0 to -1.0; else -> 0.0 to 1.0
    }

    /**
     * @param p eased progress 0..1 (0 = only A visible, 1 = only B visible).
     * @return (mod for outgoing A, mod for incoming B).
     */
    fun evaluate(id: String, p: Double, incomingOnTop: Boolean, params: Map<String, Double> = emptyMap()): Pair<LayerMod, LayerMod> {
        val q = p.coerceIn(0.0, 1.0)
        fun prm(k: String, d: Double) = params[k] ?: defs[id]?.params?.firstOrNull { it.key == k }?.default ?: d
        // cross fade helper: top layer alpha only.
        fun cross(a: LayerMod = LayerMod(), b: LayerMod = LayerMod()) =
            if (incomingOnTop) a to b.copy(alpha = b.alpha * q) else a.copy(alpha = a.alpha * (1 - q)) to b
        // top-layer shader reveal helper.
        fun reveal(shader: String, extra: Map<String, Double>) =
            if (incomingOnTop) LayerMod() to LayerMod(shaderId = shader, shaderProgress = q, shaderParams = extra + ("invert" to 0.0))
            else LayerMod(shaderId = shader, shaderProgress = 1 - q, shaderParams = extra + ("invert" to 0.0)) to LayerMod()
        return when (id) {
            "fade" -> cross()
            "fade_black" -> LayerMod(alpha = (1 - q * 2).coerceIn(0.0, 1.0)) to LayerMod(alpha = (q * 2 - 1).coerceIn(0.0, 1.0))
            "fade_white" -> {
                val a = LayerMod(alpha = if (q < 0.5) 1.0 else 0.0, shaderId = "_tr_flash", shaderProgress = 1 - (q * 2).coerceIn(0.0, 1.0))
                val b = LayerMod(alpha = if (q >= 0.5) 1.0 else 0.0, shaderId = "_tr_flash", shaderProgress = (q * 2 - 1).coerceIn(0.0, 1.0))
                a to b
            }
            "dissolve" -> reveal("_tr_dissolve", mapOf("grain" to prm("grain", 60.0)))
            "slide" -> {
                val (x, y) = dirVec(prm("direction", 0.0))
                LayerMod(dx = x * q, dy = y * q) to LayerMod(dx = -x * (1 - q), dy = -y * (1 - q))
            }
            "cover" -> {
                val (x, y) = dirVec(prm("direction", 0.0))
                if (incomingOnTop) LayerMod(dx = x * q * 0.3, dy = y * q * 0.3) to LayerMod(dx = -x * (1 - q), dy = -y * (1 - q))
                else LayerMod(dx = x * q, dy = y * q) to LayerMod(dx = -x * (1 - q) * 0.3, dy = -y * (1 - q) * 0.3)
            }
            "zoom_in" -> cross(LayerMod(scale = 1 + q * 0.6), LayerMod(scale = 0.6 + 0.4 * q))
            "zoom_out" -> cross(LayerMod(scale = 1 - q * 0.4), LayerMod(scale = 1.6 - 0.6 * q))
            "spin" -> {
                val turns = prm("turns", 1.0) * 360.0
                cross(LayerMod(rotation = turns * q * 0.5, scale = 1 - q * 0.5), LayerMod(rotation = -turns * (1 - q) * 0.5, scale = 0.5 + q * 0.5))
            }
            "wipe" -> reveal("_tr_wipe", mapOf("angle" to prm("angle", 0.0), "softness" to prm("softness", 0.05)))
            "iris" -> reveal("_tr_circle", mapOf("softness" to prm("softness", 0.04)))
            "blur" -> cross(
                LayerMod(shaderId = "_tr_blur", shaderProgress = 1 - q, shaderParams = mapOf("radius" to prm("radius", 30.0))),
                LayerMod(shaderId = "_tr_blur", shaderProgress = q, shaderParams = mapOf("radius" to prm("radius", 30.0))),
            )
            "flash" -> {
                val a = LayerMod(alpha = if (q < 0.5) 1.0 else 0.0, shaderId = "_tr_flash", shaderProgress = 1 - q * 2)
                val b = LayerMod(alpha = if (q >= 0.5) 1.0 else 0.0, shaderId = "_tr_flash", shaderProgress = (q - 0.5) * 2)
                a to b
            }
            "glitch" -> {
                val i = mapOf("intensity" to prm("intensity", 1.0))
                LayerMod(alpha = if (q < 0.5) 1.0 else 0.0, shaderId = "_tr_glitch", shaderProgress = 1 - q, shaderParams = i) to
                    LayerMod(alpha = if (q >= 0.5) 1.0 else 0.0, shaderId = "_tr_glitch", shaderProgress = q, shaderParams = i)
            }
            "distort" -> {
                val i = mapOf("amount" to prm("amount", 1.0))
                cross(LayerMod(shaderId = "_tr_distort", shaderProgress = 1 - q, shaderParams = i), LayerMod(shaderId = "_tr_distort", shaderProgress = q, shaderParams = i))
            }
            "light" -> {
                val i = mapOf("hue" to prm("hue", 35.0))
                cross(LayerMod(shaderId = "_tr_light", shaderProgress = 1 - q, shaderParams = i), LayerMod(shaderId = "_tr_light", shaderProgress = q, shaderParams = i))
            }
            "whip" -> {
                val (x, y) = dirVec(prm("direction", 0.0))
                val blurA = LayerMod(dx = x * q, dy = y * q, shaderId = "_tr_blur", shaderProgress = 1 - q, shaderParams = mapOf("radius" to 40.0))
                val blurB = LayerMod(dx = -x * (1 - q), dy = -y * (1 - q), shaderId = "_tr_blur", shaderProgress = q, shaderParams = mapOf("radius" to 40.0))
                blurA to blurB
            }
            else -> cross()
        }
    }
}
