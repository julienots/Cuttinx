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
        // ---- creator pack (dynamic transitions popular on short-form platforms)
        register(TransitionDefinition("spin_zoom", "Tourbillon zoom", listOf(ParamDef("turns", "Tours", 0.25, 3.0, 0.5)), defaultDurationUs = 450_000))
        register(TransitionDefinition("zoom_blur", "Zoom flou", listOf(ParamDef("radius", "Flou", 5.0, 60.0, 35.0)), defaultDurationUs = 400_000))
        register(TransitionDefinition("bounce", "Rebond", listOf(dirChoice), defaultDurationUs = 600_000))
        register(TransitionDefinition("drop", "Chute", defaultDurationUs = 600_000))
        register(TransitionDefinition("swipe_up", "Swipe vertical", defaultDurationUs = 350_000))
        register(TransitionDefinition("push_zoom", "Poussée zoom", listOf(dirChoice), defaultDurationUs = 450_000))
        register(TransitionDefinition("glitch_slide", "Glissement glitch", listOf(dirChoice), defaultDurationUs = 400_000))
        register(TransitionDefinition("flash_zoom", "Flash zoom", defaultDurationUs = 350_000))
        register(TransitionDefinition("rotate_slide", "Pivot", listOf(dirChoice), defaultDurationUs = 500_000))
        register(TransitionDefinition("shake", "Secousse", listOf(ParamDef("amount", "Intensité", 0.1, 1.0, 0.6)), defaultDurationUs = 400_000))
        // ---- shapes & classics
        register(TransitionDefinition("heart_wipe", "Cœur", listOf(ParamDef("softness", "Douceur", 0.0, 0.3, 0.03)), defaultDurationUs = 700_000))
        register(TransitionDefinition("star_wipe", "Étoile", listOf(ParamDef("softness", "Douceur", 0.0, 0.3, 0.02)), defaultDurationUs = 700_000))
        register(TransitionDefinition("clock_wipe", "Horloge", defaultDurationUs = 700_000))
        register(TransitionDefinition("diamond_wipe", "Losange", defaultDurationUs = 600_000))
        register(TransitionDefinition("blinds", "Stores", listOf(ParamDef("count", "Lames", 3.0, 30.0, 10.0)), defaultDurationUs = 600_000))
        register(TransitionDefinition("doors", "Portes", defaultDurationUs = 600_000))
        register(TransitionDefinition("checker", "Damier", listOf(ParamDef("count", "Cases", 4.0, 24.0, 10.0)), defaultDurationUs = 700_000))
        register(TransitionDefinition("wheel", "Roue", listOf(ParamDef("count", "Rayons", 2.0, 16.0, 6.0)), defaultDurationUs = 700_000))
        register(TransitionDefinition("pixelate", "Pixelisation", listOf(ParamDef("size", "Taille", 8.0, 120.0, 60.0)), defaultDurationUs = 600_000))
        register(TransitionDefinition("ripple", "Ondulation", listOf(ParamDef("amount", "Force", 0.0, 0.1, 0.04)), defaultDurationUs = 800_000))
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
            "spin_zoom" -> {
                val turns = prm("turns", 0.5) * 360.0
                cross(
                    LayerMod(rotation = turns * q, scale = 1 + q * 1.5, shaderId = "_tr_blur", shaderProgress = 1 - q, shaderParams = mapOf("radius" to 25.0)),
                    LayerMod(rotation = -turns * (1 - q), scale = 0.4 + 0.6 * q, shaderId = "_tr_blur", shaderProgress = q, shaderParams = mapOf("radius" to 25.0)),
                )
            }
            "zoom_blur" -> {
                val r = mapOf("radius" to prm("radius", 35.0))
                cross(LayerMod(scale = 1 + q * 0.8, shaderId = "_tr_blur", shaderProgress = 1 - q, shaderParams = r), LayerMod(scale = 1.5 - 0.5 * q, shaderId = "_tr_blur", shaderProgress = q, shaderParams = r))
            }
            "bounce" -> {
                val (x, y) = dirVec(prm("direction", 0.0))
                val k = 1 - bounceOut(q)
                // when A is composited above B it must fade away to reveal the incoming clip
                LayerMod(dx = x * q * 0.25, dy = y * q * 0.25, scale = 1 - 0.1 * q, alpha = if (incomingOnTop) 1.0 else 1 - q) to LayerMod(dx = -x * k, dy = -y * k)
            }
            "drop" -> {
                val k = 1 - bounceOut(q)
                LayerMod(scale = 1 - 0.15 * q, alpha = if (incomingOnTop) 1 - 0.4 * q else 1 - q) to LayerMod(dy = -k)
            }
            "swipe_up" -> {
                val k = 1 - (1 - q).let { it * it * it }
                LayerMod(dy = -k) to LayerMod(dy = 1 - k)
            }
            "push_zoom" -> {
                val (x, y) = dirVec(prm("direction", 0.0))
                LayerMod(dx = x * q, dy = y * q, scale = 1 - 0.3 * q) to LayerMod(dx = -x * (1 - q), dy = -y * (1 - q), scale = 1.3 - 0.3 * q)
            }
            "glitch_slide" -> {
                val (x, y) = dirVec(prm("direction", 0.0))
                val i = mapOf("intensity" to 1.0)
                LayerMod(dx = x * q, dy = y * q, shaderId = "_tr_glitch", shaderProgress = 1 - q, shaderParams = i) to
                    LayerMod(dx = -x * (1 - q), dy = -y * (1 - q), shaderId = "_tr_glitch", shaderProgress = q, shaderParams = i)
            }
            "flash_zoom" -> {
                val a = LayerMod(alpha = if (q < 0.5) 1.0 else 0.0, scale = 1 + q * 0.5, shaderId = "_tr_flash", shaderProgress = 1 - (q * 2).coerceIn(0.0, 1.0))
                val b = LayerMod(alpha = if (q >= 0.5) 1.0 else 0.0, scale = 1.25 - 0.25 * q, shaderId = "_tr_flash", shaderProgress = (q * 2 - 1).coerceIn(0.0, 1.0))
                a to b
            }
            "rotate_slide" -> {
                val (x, y) = dirVec(prm("direction", 0.0))
                LayerMod(dx = x * q, dy = y * q, rotation = 25 * q * (if (x + y >= 0) 1 else -1)) to
                    LayerMod(dx = -x * (1 - q), dy = -y * (1 - q), rotation = -25 * (1 - q) * (if (x + y >= 0) 1 else -1))
            }
            "shake" -> {
                val amt = prm("amount", 0.6) * 0.06 * sin(q * kotlin.math.PI)
                val jx = sin(q * 61.0) * amt; val jy = sin(q * 47.0 + 1.3) * amt
                cross(LayerMod(dx = jx, dy = jy, scale = 1 + amt), LayerMod(dx = -jy, dy = jx, scale = 1 + amt))
            }
            "heart_wipe" -> reveal("_tr_heart", mapOf("softness" to prm("softness", 0.03)))
            "star_wipe" -> reveal("_tr_star", mapOf("softness" to prm("softness", 0.02)))
            "clock_wipe" -> reveal("_tr_clock", mapOf("softness" to 0.01))
            "diamond_wipe" -> reveal("_tr_diamond", mapOf("softness" to 0.03))
            "blinds" -> reveal("_tr_blinds", mapOf("count" to prm("count", 10.0)))
            "doors" -> reveal("_tr_doors", mapOf("softness" to 0.01))
            "checker" -> reveal("_tr_checker", mapOf("count" to prm("count", 10.0)))
            "wheel" -> reveal("_tr_wheel", mapOf("count" to prm("count", 6.0)))
            "pixelate" -> {
                val i = mapOf("size" to prm("size", 60.0))
                // both clips pixelate, the switch happens at the coarsest point
                LayerMod(alpha = if (q < 0.5) 1.0 else 0.0, shaderId = "_tr_pixel", shaderProgress = 1 - (q * 2).coerceIn(0.0, 1.0), shaderParams = i) to
                    LayerMod(alpha = if (q >= 0.5) 1.0 else 0.0, shaderId = "_tr_pixel", shaderProgress = (q * 2 - 1).coerceIn(0.0, 1.0), shaderParams = i)
            }
            "ripple" -> {
                val i = mapOf("amount" to prm("amount", 0.04))
                cross(LayerMod(shaderId = "_tr_ripple", shaderProgress = 1 - q, shaderParams = i), LayerMod(shaderId = "_tr_ripple", shaderProgress = q, shaderParams = i))
            }
            else -> cross()
        }
    }

    private fun sin(v: Double) = kotlin.math.sin(v)

    private fun bounceOut(p0: Double): Double {
        val n = 7.5625; val d = 2.75
        var p = p0.coerceIn(0.0, 1.0)
        return when {
            p < 1 / d -> n * p * p
            p < 2 / d -> { p -= 1.5 / d; n * p * p + 0.75 }
            p < 2.5 / d -> { p -= 2.25 / d; n * p * p + 0.9375 }
            else -> { p -= 2.625 / d; n * p * p + 0.984375 }
        }
    }
}
