package com.superessence.cuttinx.core.effects

import com.superessence.cuttinx.core.effects.EffectCategory as C

private fun p(key: String, label: String, min: Double, max: Double, def: Double, unit: String = "") =
    ParamDef(key, label, min, max, def, unit = unit)
private fun hue(key: String, label: String, def: Double) = ParamDef(key, label, 0.0, 360.0, def, ParamType.HUE, unit = "°")
private fun toggle(key: String, label: String, def: Boolean) = ParamDef(key, label, 0.0, 1.0, if (def) 1.0 else 0.0, ParamType.TOGGLE)
private fun choice(key: String, label: String, vararg options: String) =
    ParamDef(key, label, 0.0, (options.size - 1).toDouble(), 0.0, ParamType.CHOICE, options.toList())

private fun video(id: String, name: String, cat: C, params: List<ParamDef>, animated: Boolean = false, desc: String = "", glsl: () -> String) =
    EffectDefinition(id, name, cat, EffectKind.VIDEO, params, glsl(), animated, desc)

private fun audio(id: String, name: String, cat: C, params: List<ParamDef>, desc: String = "") =
    EffectDefinition(id, name, cat, EffectKind.AUDIO, params, "", false, desc)

/**
 * Registry of every effect available in CUTTINX. All shaders are original work written for
 * CUTTINX. New effects can be added by calling [register] (e.g. from an imported pack) without
 * touching the engine: the engine only needs the definition.
 */
object EffectRegistry {
    private val effects = LinkedHashMap<String, EffectDefinition>()

    fun register(def: EffectDefinition) { effects[def.id] = def }
    operator fun get(id: String): EffectDefinition? = effects[id]
    val all: List<EffectDefinition> get() = effects.values.toList()
    fun video(includeInternal: Boolean = false) = all.filter { it.kind == EffectKind.VIDEO && (includeInternal || it.category != C.INTERNAL) }
    fun audio() = all.filter { it.kind == EffectKind.AUDIO }
    fun byCategory(cat: C) = all.filter { it.category == cat }

    init {
        // ------------------------------------------------------------------ ADJUST
        register(video("adjust", "Réglages couleur", C.ADJUST, listOf(
            p("brightness", "Luminosité", -1.0, 1.0, 0.0),
            p("contrast", "Contraste", -1.0, 1.0, 0.0),
            p("saturation", "Saturation", -1.0, 1.0, 0.0),
            p("exposure", "Exposition", -2.0, 2.0, 0.0, "EV"),
            p("temperature", "Température", -1.0, 1.0, 0.0),
            p("tint", "Teinte (vert/magenta)", -1.0, 1.0, 0.0),
            p("highlights", "Hautes lumières", -1.0, 1.0, 0.0),
            p("shadows", "Ombres", -1.0, 1.0, 0.0),
            p("vibrance", "Vibrance", -1.0, 1.0, 0.0),
            p("hue", "Rotation de teinte", -180.0, 180.0, 0.0, "°"),
            p("gamma", "Gamma", -1.0, 1.0, 0.0),
            p("fade", "Délavé", 0.0, 1.0, 0.0),
        ), desc = "Correction colorimétrique complète") {
            """
            vec4 c = tex(uv);
            vec3 col = c.rgb;
            col *= pow(2.0, uP[3]);
            col += uP[0] * 0.5;
            col = (col - 0.5) * (1.0 + uP[1]) + 0.5;
            col.r += uP[4] * 0.12; col.b -= uP[4] * 0.12;
            col.g -= uP[5] * 0.10; col.r += uP[5] * 0.05; col.b += uP[5] * 0.05;
            float l = luma(col);
            float hiMask = smoothstep(0.45, 1.0, l);
            float loMask = 1.0 - smoothstep(0.0, 0.55, l);
            col += uP[6] * 0.35 * hiMask;
            col += uP[7] * 0.35 * loMask;
            l = luma(col);
            col = mix(vec3(l), col, 1.0 + uP[2]);
            float mx = max(col.r, max(col.g, col.b)); float mn = min(col.r, min(col.g, col.b));
            float sat = mx - mn;
            col = mix(vec3(luma(col)), col, 1.0 + uP[8] * (1.0 - sat));
            if (abs(uP[9]) > 0.01) { vec3 h = rgb2hsv(clamp(col, 0.0, 1.0)); h.x = fract(h.x + uP[9] / 360.0); col = hsv2rgb(h); }
            col = pow(max(col, 0.0), vec3(1.0 / (1.0 + uP[10] * 0.8)));
            col = mix(col, vec3(0.12) + col * 0.8, uP[11]);
            return vec4(clamp(col, 0.0, 1.0), c.a);
            """
        })
        register(video("sharpen", "Netteté", C.BLUR, listOf(p("amount", "Intensité", 0.0, 3.0, 0.8), p("radius", "Rayon", 0.5, 4.0, 1.0))) {
            """
            vec2 px = uP[1] / uResolution;
            vec4 c = tex(uv);
            vec3 n = tex(uv + vec2(px.x, 0.0)).rgb + tex(uv - vec2(px.x, 0.0)).rgb + tex(uv + vec2(0.0, px.y)).rgb + tex(uv - vec2(0.0, px.y)).rgb;
            vec3 col = c.rgb + (c.rgb * 4.0 - n) * uP[0];
            return vec4(clamp(col, 0.0, 1.0), c.a);
            """
        })
        register(video("blur", "Flou", C.BLUR, listOf(p("radius", "Rayon", 0.0, 40.0, 8.0, "px"))) {
            """
            vec2 px = max(uP[0], 0.001) / uResolution;
            vec4 acc = vec4(0.0); float wsum = 0.0;
            for (int x = -3; x <= 3; x++) {
              for (int y = -3; y <= 3; y++) {
                vec2 o = vec2(float(x), float(y)) / 3.0;
                float w = exp(-dot(o, o) * 2.0);
                acc += tex(uv + o * px) * w; wsum += w;
              }
            }
            return acc / wsum;
            """
        })
        register(video("motion_blur", "Flou de mouvement", C.MOTION, listOf(p("amount", "Longueur", 0.0, 60.0, 16.0, "px"), p("angle", "Angle", 0.0, 360.0, 0.0, "°"))) {
            """
            vec2 dir = vec2(cos(radians(uP[1])), sin(radians(uP[1]))) * uP[0] / uResolution;
            vec4 acc = vec4(0.0);
            for (int i = 0; i < 16; i++) { float t = float(i) / 15.0 - 0.5; acc += tex(uv + dir * t); }
            return acc / 16.0;
            """
        })
        register(video("zoom_blur", "Flou radial (vitesse)", C.MOTION, listOf(p("amount", "Intensité", 0.0, 0.5, 0.12), p("cx", "Centre X", 0.0, 1.0, 0.5), p("cy", "Centre Y", 0.0, 1.0, 0.5))) {
            """
            vec2 center = vec2(uP[1], 1.0 - uP[2]);
            vec2 d = uv - center;
            vec4 acc = vec4(0.0);
            for (int i = 0; i < 16; i++) { float s = 1.0 - uP[0] * float(i) / 15.0; acc += tex(center + d * s); }
            return acc / 16.0;
            """
        })
        register(video("vignette", "Vignettage", C.CINEMA, listOf(p("amount", "Intensité", 0.0, 1.0, 0.5), p("size", "Taille", 0.1, 1.5, 0.75), p("softness", "Douceur", 0.01, 1.0, 0.45), hue("hue", "Couleur", 0.0), p("colorMix", "Teinte colorée", 0.0, 1.0, 0.0))) {
            """
            vec4 c = tex(uv);
            vec2 d = (uv - 0.5) * vec2(uResolution.x / uResolution.y, 1.0);
            float r = length(d) / 0.7071;
            float v = smoothstep(uP[1], uP[1] - uP[2], r);
            vec3 vc = mix(vec3(0.0), hueColor(uP[3]) * 0.6, uP[4]);
            return vec4(mix(mix(c.rgb, vc, uP[0]), c.rgb, v), c.a);
            """
        })
        register(video("grain", "Grain de film", C.RETRO, listOf(p("amount", "Intensité", 0.0, 1.0, 0.25), p("size", "Taille", 0.5, 4.0, 1.5), toggle("color", "Grain coloré", false)), animated = true) {
            """
            vec4 c = tex(uv);
            vec2 g = floor(uv * uResolution / uP[1]);
            float t = floor(uTime * 24.0);
            float n = hash(g + t * 1.37) - 0.5;
            vec3 nc = vec3(n);
            if (uP[2] > 0.5) nc = vec3(n, hash(g + t * 2.11) - 0.5, hash(g + t * 3.07) - 0.5);
            float l = luma(c.rgb);
            float w = 1.0 - abs(l - 0.5) * 1.2;
            return vec4(clamp(c.rgb + nc * uP[0] * w * 0.5, 0.0, 1.0), c.a);
            """
        })
        register(video("chroma_aberration", "Aberration chromatique", C.DISTORT, listOf(p("amount", "Décalage", 0.0, 30.0, 6.0, "px"), toggle("radial", "Radiale", true))) {
            """
            vec2 off;
            if (uP[1] > 0.5) off = (uv - 0.5) * uP[0] * 2.0 / uResolution.x;
            else off = vec2(uP[0] / uResolution.x, 0.0);
            vec4 c = tex(uv);
            return vec4(tex(uv + off).r, c.g, tex(uv - off).b, c.a);
            """
        })
        register(video("glitch", "Glitch numérique", C.STYLIZE, listOf(p("intensity", "Intensité", 0.0, 1.0, 0.5), p("blocks", "Blocs", 2.0, 60.0, 18.0), p("speed", "Vitesse", 0.1, 10.0, 4.0)), animated = true) {
            """
            float t = floor(uTime * uP[2] * 4.0);
            float row = floor(uv.y * uP[1]);
            float r = hash(vec2(row, t));
            float active = step(1.0 - uP[0] * 0.6, r);
            float shift = (hash(vec2(t, row * 3.1)) - 0.5) * 0.25 * uP[0] * active;
            vec2 u2 = vec2(fract(uv.x + shift), uv.y);
            float split = 0.02 * uP[0] * (hash(vec2(t, 7.0)) - 0.5);
            vec4 c = tex(u2);
            vec3 col = vec3(tex(u2 + vec2(split, 0.0)).r, c.g, tex(u2 - vec2(split, 0.0)).b);
            float blk = step(0.985 - uP[0] * 0.05, hash(floor(uv * vec2(uP[1], uP[1] * 0.5)) + t));
            col = mix(col, vec3(hash(uv + t)), blk * 0.6 * uP[0]);
            return vec4(col, c.a);
            """
        })
        register(video("rgb_split", "Séparation RVB", C.STYLIZE, listOf(p("amount", "Écart", 0.0, 40.0, 10.0, "px"), p("angle", "Angle", 0.0, 360.0, 0.0, "°"), toggle("animate", "Animer", true)), animated = true) {
            """
            float a = radians(uP[1]) + (uP[2] > 0.5 ? uTime * 2.0 : 0.0);
            vec2 off = vec2(cos(a), sin(a)) * uP[0] / uResolution;
            vec4 c = tex(uv);
            return vec4(tex(uv + off).r, c.g, tex(uv - off).b, c.a);
            """
        })
        register(video("vhs", "Cassette VHS", C.RETRO, listOf(p("intensity", "Intensité", 0.0, 1.0, 0.6), p("scanlines", "Lignes", 0.0, 1.0, 0.5), p("noise", "Bruit", 0.0, 1.0, 0.3)), animated = true) {
            """
            float wob = (noise(vec2(uv.y * 20.0, uTime * 3.0)) - 0.5) * 0.006 * uP[0];
            vec2 u2 = uv + vec2(wob, 0.0);
            float bleed = 3.0 * uP[0] / uResolution.x;
            vec3 col = vec3(tex(u2 + vec2(bleed, 0.0)).r, tex(u2).g, tex(u2 - vec2(bleed, 0.0)).b);
            col = mix(vec3(luma(col)), col, 1.0 - 0.25 * uP[0]);
            float sl = 0.5 + 0.5 * sin(uv.y * uResolution.y * 1.5);
            col *= 1.0 - uP[1] * 0.25 * sl;
            float band = smoothstep(0.98, 1.0, fract(uv.y * 0.5 - uTime * 0.2));
            col += (hash(uv * uResolution + uTime) - 0.5) * uP[2] * 0.35 + band * 0.15 * uP[2];
            col = col * vec3(1.05, 0.98, 1.08);
            return vec4(clamp(col, 0.0, 1.0), tex(uv).a);
            """
        })
        register(video("old_film", "Vieux film", C.RETRO, listOf(p("amount", "Intensité", 0.0, 1.0, 0.8), p("scratches", "Rayures", 0.0, 1.0, 0.5), p("flicker", "Scintillement", 0.0, 1.0, 0.4)), animated = true) {
            """
            vec4 c = tex(uv);
            float l = luma(c.rgb);
            vec3 sep = vec3(l * 1.07, l * 0.86, l * 0.62);
            vec3 col = mix(c.rgb, sep, uP[0]);
            float t = floor(uTime * 18.0);
            col *= 1.0 - uP[2] * 0.15 * hash(vec2(t, 1.0));
            float x = hash(vec2(t, 3.0));
            float scratch = (1.0 - smoothstep(0.0, 0.0015, abs(uv.x - x))) * step(0.6, hash(vec2(t, 5.0)));
            col = mix(col, vec3(0.9), scratch * uP[1]);
            float dust = step(0.9993, hash(floor(uv * uResolution / 3.0) + t));
            col = mix(col, vec3(0.05), dust * uP[1]);
            vec2 d = uv - 0.5;
            col *= 1.0 - dot(d, d) * 1.3 * uP[0];
            return vec4(clamp(col, 0.0, 1.0), c.a);
            """
        })
        register(video("bw", "Noir & blanc", C.COLOR, listOf(p("amount", "Intensité", 0.0, 1.0, 1.0), p("contrast", "Contraste", -1.0, 1.0, 0.15), hue("toneHue", "Virage", 30.0), p("tone", "Virage (force)", 0.0, 1.0, 0.0))) {
            """
            vec4 c = tex(uv);
            float l = luma(c.rgb);
            l = (l - 0.5) * (1.0 + uP[1]) + 0.5;
            vec3 g = mix(vec3(l), vec3(l) * mix(vec3(1.0), hueColor(uP[2]) * 0.5 + 0.5, uP[3]), uP[3]);
            return vec4(mix(c.rgb, clamp(g, 0.0, 1.0), uP[0]), c.a);
            """
        })
        register(video("sepia", "Sépia", C.COLOR, listOf(p("amount", "Intensité", 0.0, 1.0, 0.8))) {
            """
            vec4 c = tex(uv);
            vec3 s = vec3(dot(c.rgb, vec3(0.393, 0.769, 0.189)), dot(c.rgb, vec3(0.349, 0.686, 0.168)), dot(c.rgb, vec3(0.272, 0.534, 0.131)));
            return vec4(mix(c.rgb, clamp(s, 0.0, 1.0), uP[0]), c.a);
            """
        })
        register(video("teal_orange", "Look ciné sarcelle/orange", C.CINEMA, listOf(p("amount", "Intensité", 0.0, 1.0, 0.6), p("contrast", "Contraste", 0.0, 1.0, 0.3))) {
            """
            vec4 c = tex(uv);
            float l = luma(c.rgb);
            vec3 shadowTint = vec3(0.0, 0.45, 0.55);
            vec3 hiTint = vec3(1.0, 0.6, 0.3);
            vec3 graded = c.rgb + (mix(shadowTint, hiTint, smoothstep(0.2, 0.8, l)) - 0.5) * 0.35;
            graded = (graded - 0.5) * (1.0 + uP[1] * 0.5) + 0.5;
            return vec4(mix(c.rgb, clamp(graded, 0.0, 1.0), uP[0]), c.a);
            """
        })
        register(video("letterbox", "Bandes cinéma", C.CINEMA, listOf(p("ratio", "Ratio", 1.0, 3.0, 2.39), p("opacity", "Opacité", 0.0, 1.0, 1.0))) {
            """
            vec4 c = tex(uv);
            float frameRatio = uResolution.x / uResolution.y;
            float visible = clamp(frameRatio / uP[0], 0.0, 1.0);
            float bar = step(0.5 + visible * 0.5, uv.y) + step(uv.y, 0.5 - visible * 0.5);
            return vec4(mix(c.rgb, vec3(0.0), clamp(bar, 0.0, 1.0) * uP[1]), c.a);
            """
        })
        register(video("glow", "Lueur (bloom)", C.LIGHT, listOf(p("threshold", "Seuil", 0.0, 1.0, 0.65), p("intensity", "Intensité", 0.0, 3.0, 1.0), p("radius", "Rayon", 1.0, 40.0, 14.0, "px"))) {
            """
            vec4 c = tex(uv);
            vec2 px = uP[2] / uResolution;
            vec3 acc = vec3(0.0); float ws = 0.0;
            for (int i = 0; i < 12; i++) {
              float a = float(i) * 0.5236;
              for (int r = 1; r <= 3; r++) {
                vec2 o = vec2(cos(a), sin(a)) * float(r) / 3.0;
                vec3 s = tex(uv + o * px).rgb;
                float w = 1.0 / float(r);
                acc += max(s - uP[0], 0.0) * w; ws += w;
              }
            }
            return vec4(clamp(c.rgb + acc / ws * uP[1] * 3.0, 0.0, 1.0), c.a);
            """
        })
        register(video("light_leak", "Fuite de lumière", C.LIGHT, listOf(p("intensity", "Intensité", 0.0, 1.0, 0.5), hue("hue", "Couleur", 25.0), p("speed", "Vitesse", 0.0, 3.0, 0.6)), animated = true) {
            """
            vec4 c = tex(uv);
            float t = uTime * uP[2];
            vec2 ctr = vec2(0.15 + 0.7 * (0.5 + 0.5 * sin(t * 0.7)), 0.8 + 0.2 * sin(t * 1.3));
            float d = length((uv - ctr) * vec2(1.0, 1.6));
            float leak = smoothstep(0.9, 0.0, d) * (0.6 + 0.4 * noise(uv * 3.0 + t));
            vec3 lc = hueColor(uP[1]) * 0.8 + 0.2;
            vec3 col = 1.0 - (1.0 - c.rgb) * (1.0 - lc * leak * uP[0]);
            return vec4(col, c.a);
            """
        })
        register(video("pixelate", "Pixelisation", C.STYLIZE, listOf(p("size", "Taille", 1.0, 80.0, 12.0, "px"))) {
            """
            vec2 cell = max(uP[0], 1.0) / uResolution;
            return tex((floor(uv / cell) + 0.5) * cell);
            """
        })
        register(video("posterize", "Postérisation", C.STYLIZE, listOf(p("levels", "Niveaux", 2.0, 16.0, 5.0))) {
            """
            vec4 c = tex(uv);
            return vec4(floor(c.rgb * uP[0] + 0.5) / uP[0], c.a);
            """
        })
        register(video("invert", "Négatif", C.COLOR, listOf(p("amount", "Intensité", 0.0, 1.0, 1.0))) {
            """
            vec4 c = tex(uv);
            return vec4(mix(c.rgb, 1.0 - c.rgb, uP[0]), c.a);
            """
        })
        register(video("duotone", "Bichromie", C.COLOR, listOf(hue("dark", "Couleur sombre", 250.0), hue("light", "Couleur claire", 45.0), p("amount", "Intensité", 0.0, 1.0, 1.0))) {
            """
            vec4 c = tex(uv);
            float l = luma(c.rgb);
            vec3 d = hueColor(uP[0]) * 0.35;
            vec3 li = hueColor(uP[1]) * 0.5 + 0.5;
            return vec4(mix(c.rgb, mix(d, li, l), uP[2]), c.a);
            """
        })
        register(video("mirror", "Miroir", C.DISTORT, listOf(choice("mode", "Mode", "Gauche→Droite", "Droite→Gauche", "Haut→Bas", "Bas→Haut", "Quadruple"))) {
            """
            vec2 u = uv;
            float m = uP[0];
            if (m < 0.5) { if (u.x > 0.5) u.x = 1.0 - u.x; }
            else if (m < 1.5) { if (u.x < 0.5) u.x = 1.0 - u.x; }
            else if (m < 2.5) { if (u.y < 0.5) u.y = 1.0 - u.y; }
            else if (m < 3.5) { if (u.y > 0.5) u.y = 1.0 - u.y; }
            else { u = abs(u * 2.0 - 1.0); }
            return tex(u);
            """
        })
        register(video("kaleidoscope", "Kaléidoscope", C.DISTORT, listOf(p("segments", "Segments", 2.0, 16.0, 6.0), p("rotation", "Rotation", 0.0, 360.0, 0.0, "°"), toggle("spin", "Tourner", false)), animated = true) {
            """
            vec2 d = uv - 0.5;
            float r = length(d);
            float a = atan(d.y, d.x) + radians(uP[1]) + (uP[2] > 0.5 ? uTime * 0.5 : 0.0);
            float seg = 2.0 * PI / floor(uP[0]);
            a = mod(a, seg); a = abs(a - seg * 0.5);
            return tex(vec2(cos(a), sin(a)) * r + 0.5);
            """
        })
        register(video("fisheye", "Fisheye / Bulle", C.DISTORT, listOf(p("amount", "Intensité", -1.0, 1.0, 0.5), p("radius", "Rayon", 0.1, 1.0, 0.7))) {
            """
            vec2 d = uv - 0.5;
            float r = length(d) / uP[1];
            vec2 u = uv;
            if (r < 1.0) { float f = mix(1.0, smoothstep(0.0, 1.0, r) / max(r, 0.0001), uP[0]); u = 0.5 + d * mix(1.0, f, 1.0 - r); }
            return tex(u);
            """
        })
        register(video("wave", "Ondulation", C.DISTORT, listOf(p("amplitude", "Amplitude", 0.0, 0.08, 0.015), p("frequency", "Fréquence", 1.0, 60.0, 12.0), p("speed", "Vitesse", 0.0, 10.0, 2.0)), animated = true) {
            """
            vec2 u = uv;
            u.x += sin(uv.y * uP[1] + uTime * uP[2]) * uP[0];
            u.y += cos(uv.x * uP[1] * 0.8 + uTime * uP[2] * 1.1) * uP[0] * 0.5;
            return tex(u);
            """
        })
        register(video("swirl", "Tourbillon", C.DISTORT, listOf(p("angle", "Angle", -720.0, 720.0, 180.0, "°"), p("radius", "Rayon", 0.05, 1.0, 0.45))) {
            """
            vec2 d = (uv - 0.5) * vec2(uResolution.x / uResolution.y, 1.0);
            float r = length(d);
            float f = max(0.0, 1.0 - r / uP[1]);
            d = rotate2(d, radians(uP[0]) * f * f);
            return tex(d / vec2(uResolution.x / uResolution.y, 1.0) + 0.5);
            """
        })
        register(video("shake", "Tremblement caméra", C.MOTION, listOf(p("intensity", "Intensité", 0.0, 0.08, 0.02), p("speed", "Vitesse", 1.0, 40.0, 14.0), p("rotation", "Rotation", 0.0, 6.0, 1.0, "°")), animated = true) {
            """
            float t = uTime * uP[1];
            vec2 off = vec2(noise(vec2(t, 1.3)) - 0.5, noise(vec2(t, 7.9)) - 0.5) * uP[0] * 2.0;
            float rot = (noise(vec2(t, 4.2)) - 0.5) * radians(uP[2]) * 2.0;
            vec2 d = rotate2(uv - 0.5, rot) * (1.0 - uP[0] * 2.0) + 0.5 + off;
            return tex(d);
            """
        })
        register(video("zoom_pulse", "Pulsation zoom", C.MOTION, listOf(p("amount", "Amplitude", 0.0, 0.3, 0.06), p("bpm", "Tempo", 30.0, 200.0, 120.0, "BPM")), animated = true) {
            """
            float beat = fract(uTime * uP[1] / 60.0);
            float s = 1.0 - uP[0] * exp(-beat * 6.0);
            return tex((uv - 0.5) * s + 0.5);
            """
        })
        register(video("speed_lines", "Lignes de vitesse", C.MOTION, listOf(p("density", "Densité", 10.0, 200.0, 80.0), p("intensity", "Intensité", 0.0, 1.0, 0.6), p("inner", "Zone libre", 0.0, 0.8, 0.35)), animated = true) {
            """
            vec4 c = tex(uv);
            vec2 d = (uv - 0.5) * vec2(uResolution.x / uResolution.y, 1.0);
            float a = atan(d.y, d.x);
            float r = length(d);
            float t = floor(uTime * 12.0);
            float line = step(0.82, hash(vec2(floor(a / (2.0 * PI) * uP[0]), t)));
            float fadeR = smoothstep(uP[2], uP[2] + 0.35, r);
            return vec4(mix(c.rgb, vec3(1.0), line * fadeR * uP[1]), c.a);
            """
        })
        register(video("sparkles", "Étincelles", C.PARTICLES, listOf(p("density", "Densité", 5.0, 80.0, 30.0), p("size", "Taille", 0.5, 4.0, 1.5), hue("hue", "Couleur", 48.0), p("speed", "Vitesse", 0.0, 4.0, 1.0)), animated = true) {
            """
            vec4 c = tex(uv);
            vec2 g = uv * vec2(uResolution.x / uResolution.y, 1.0) * uP[0];
            vec2 id = floor(g); vec2 f = fract(g) - 0.5;
            float rnd = hash(id);
            vec2 pos = vec2(hash(id + 1.7), hash(id + 3.1)) - 0.5;
            float tw = 0.5 + 0.5 * sin(uTime * uP[3] * 6.0 + rnd * 40.0);
            float d = length(f - pos * 0.7);
            float star = smoothstep(0.06 * uP[1], 0.0, d) * step(0.55, rnd) * tw;
            float cross = (smoothstep(0.01 * uP[1], 0.0, abs(f.x - pos.x * 0.7)) + smoothstep(0.01 * uP[1], 0.0, abs(f.y - pos.y * 0.7))) * smoothstep(0.2 * uP[1], 0.0, d) * step(0.55, rnd) * tw;
            vec3 sc = mix(vec3(1.0), hueColor(uP[2]), 0.5);
            return vec4(clamp(c.rgb + sc * (star + cross * 0.6), 0.0, 1.0), c.a);
            """
        })
        register(video("snow", "Neige", C.PARTICLES, listOf(p("density", "Densité", 5.0, 60.0, 25.0), p("speed", "Vitesse", 0.0, 3.0, 0.8), p("size", "Taille", 0.5, 3.0, 1.0)), animated = true) {
            """
            vec4 c = tex(uv);
            vec3 col = c.rgb;
            for (int layer = 0; layer < 3; layer++) {
              float fl = float(layer);
              float sc = uP[0] * (1.0 + fl * 0.6);
              vec2 u = vec2(uv.x * uResolution.x / uResolution.y, 1.0 - uv.y) * sc;
              u.y -= uTime * uP[1] * (1.0 + fl * 0.5) * 1.5;
              u.x += sin(u.y * 0.7 + fl) * 0.3;
              vec2 id = floor(u); vec2 f = fract(u) - 0.5;
              vec2 pos = vec2(hash(id + fl), hash(id + fl + 9.1)) - 0.5;
              float flake = smoothstep(0.08 * uP[2] / (1.0 + fl * 0.5), 0.0, length(f - pos * 0.6)) * step(0.5, hash(id + 2.3));
              col = mix(col, vec3(1.0), flake * (0.9 - fl * 0.2));
            }
            return vec4(col, c.a);
            """
        })
        register(video("crt", "Écran CRT", C.RETRO, listOf(p("curvature", "Courbure", 0.0, 0.4, 0.12), p("scanlines", "Lignes", 0.0, 1.0, 0.6), p("mask", "Masque RVB", 0.0, 1.0, 0.4))) {
            """
            vec2 d = uv - 0.5;
            d *= 1.0 + dot(d, d) * uP[0] * 2.0;
            vec2 u = d + 0.5;
            if (u.x < 0.0 || u.x > 1.0 || u.y < 0.0 || u.y > 1.0) return vec4(0.0, 0.0, 0.0, 1.0);
            vec4 c = tex(u);
            float sl = 0.5 + 0.5 * cos(u.y * uResolution.y * PI * 0.5);
            vec3 col = c.rgb * (1.0 - uP[1] * 0.35 * sl);
            float m = mod(floor(u.x * uResolution.x), 3.0);
            vec3 mask = m < 1.0 ? vec3(1.0, 0.7, 0.7) : (m < 2.0 ? vec3(0.7, 1.0, 0.7) : vec3(0.7, 0.7, 1.0));
            col *= mix(vec3(1.0), mask, uP[2]);
            return vec4(col * 1.1, c.a);
            """
        })
        register(video("sketch", "Croquis", C.STYLIZE, listOf(p("amount", "Intensité", 0.0, 1.0, 1.0), p("thickness", "Épaisseur", 0.5, 4.0, 1.0), toggle("invert", "Fond sombre", false))) {
            """
            vec2 px = uP[1] / uResolution;
            float tl = luma(tex(uv + vec2(-px.x, px.y)).rgb); float t = luma(tex(uv + vec2(0.0, px.y)).rgb); float tr = luma(tex(uv + px).rgb);
            float l = luma(tex(uv - vec2(px.x, 0.0)).rgb); float r = luma(tex(uv + vec2(px.x, 0.0)).rgb);
            float bl = luma(tex(uv - px).rgb); float b = luma(tex(uv - vec2(0.0, px.y)).rgb); float br = luma(tex(uv + vec2(px.x, -px.y)).rgb);
            float gx = -tl - 2.0 * l - bl + tr + 2.0 * r + br;
            float gy = -tl - 2.0 * t - tr + bl + 2.0 * b + br;
            float e = clamp(length(vec2(gx, gy)) * 1.5, 0.0, 1.0);
            vec3 s = uP[2] > 0.5 ? vec3(e) : vec3(1.0 - e);
            vec4 c = tex(uv);
            return vec4(mix(c.rgb, s, uP[0]), c.a);
            """
        })
        register(video("halftone", "Trame BD", C.STYLIZE, listOf(p("size", "Taille", 3.0, 30.0, 8.0, "px"), p("amount", "Intensité", 0.0, 1.0, 1.0))) {
            """
            vec4 c = tex(uv);
            vec2 g = uv * uResolution / uP[0];
            vec2 rg = rotate2(g, 0.785);
            vec2 f = fract(rg) - 0.5;
            float l = luma(tex((floor(uv * uResolution / uP[0]) + 0.5) * uP[0] / uResolution).rgb);
            float dotv = smoothstep(0.05, -0.05, length(f) - (1.0 - l) * 0.6);
            vec3 col = mix(vec3(1.0), c.rgb * 0.6, dotv);
            return vec4(mix(c.rgb, col, uP[1]), c.a);
            """
        })
        register(video("thermal", "Vision thermique", C.STYLIZE, listOf(p("amount", "Intensité", 0.0, 1.0, 1.0))) {
            """
            vec4 c = tex(uv);
            float l = luma(c.rgb);
            vec3 a = vec3(0.0, 0.0, 0.3); vec3 b = vec3(0.6, 0.0, 0.8); vec3 cc = vec3(1.0, 0.2, 0.0); vec3 d = vec3(1.0, 1.0, 0.2);
            vec3 th = l < 0.33 ? mix(a, b, l / 0.33) : (l < 0.66 ? mix(b, cc, (l - 0.33) / 0.33) : mix(cc, d, (l - 0.66) / 0.34));
            return vec4(mix(c.rgb, th, uP[0]), c.a);
            """
        })
        register(video("gradient_overlay", "Dégradé", C.OVERLAY, listOf(hue("hueA", "Couleur A", 280.0), hue("hueB", "Couleur B", 30.0), p("angle", "Angle", 0.0, 360.0, 90.0, "°"), p("opacity", "Opacité", 0.0, 1.0, 0.4), choice("blend", "Fusion", "Normal", "Écran", "Produit", "Incrustation"))) {
            """
            vec4 c = tex(uv);
            vec2 dir = vec2(cos(radians(uP[2])), sin(radians(uP[2])));
            float t = clamp(dot(uv - 0.5, dir) + 0.5, 0.0, 1.0);
            vec3 g = mix(hueColor(uP[0]), hueColor(uP[1]), t);
            vec3 b;
            if (uP[4] < 0.5) b = g;
            else if (uP[4] < 1.5) b = 1.0 - (1.0 - c.rgb) * (1.0 - g);
            else if (uP[4] < 2.5) b = c.rgb * g;
            else b = mix(2.0 * c.rgb * g, 1.0 - 2.0 * (1.0 - c.rgb) * (1.0 - g), step(0.5, c.rgb));
            return vec4(mix(c.rgb, b, uP[3]), c.a);
            """
        })
        register(video("color_overlay", "Filtre coloré", C.OVERLAY, listOf(hue("hue", "Couleur", 200.0), p("opacity", "Opacité", 0.0, 1.0, 0.3), p("saturation", "Saturation", 0.0, 1.0, 1.0))) {
            """
            vec4 c = tex(uv);
            vec3 col = mix(vec3(1.0), hueColor(uP[0]), uP[2]);
            return vec4(mix(c.rgb, c.rgb * col * 1.2, uP[1]), c.a);
            """
        })
        register(video("strobe", "Stroboscope", C.LIGHT, listOf(p("frequency", "Fréquence", 1.0, 20.0, 6.0, "Hz"), p("intensity", "Intensité", 0.0, 1.0, 0.7)), animated = true) {
            """
            vec4 c = tex(uv);
            float f = step(0.5, fract(uTime * uP[0]));
            return vec4(mix(c.rgb, vec3(1.0), f * uP[1] * 0.8), c.a);
            """
        })
        register(video("rainbow", "Arc-en-ciel", C.COLOR, listOf(p("amount", "Intensité", 0.0, 1.0, 0.5), p("speed", "Vitesse", 0.0, 3.0, 0.5)), animated = true) {
            """
            vec4 c = tex(uv);
            vec3 h = rgb2hsv(c.rgb);
            h.x = fract(h.x + uv.x * 0.5 + uTime * uP[1]);
            h.y = max(h.y, 0.6);
            return vec4(mix(c.rgb, hsv2rgb(h), uP[0]), c.a);
            """
        })
        register(video("night_vision", "Vision nocturne", C.STYLIZE, listOf(p("amount", "Intensité", 0.0, 1.0, 1.0), p("noise", "Bruit", 0.0, 1.0, 0.4)), animated = true) {
            """
            vec4 c = tex(uv);
            float l = luma(c.rgb) * 1.6;
            l += (hash(uv * uResolution + uTime * 60.0) - 0.5) * uP[1] * 0.4;
            vec2 d = uv - 0.5;
            l *= 1.0 - dot(d, d) * 2.0;
            return vec4(mix(c.rgb, vec3(0.1, 1.0, 0.2) * l, uP[0]), c.a);
            """
        })
        register(video("dreamy", "Rêveur", C.LIGHT, listOf(p("amount", "Intensité", 0.0, 1.0, 0.6), p("radius", "Diffusion", 1.0, 30.0, 10.0, "px"))) {
            """
            vec4 c = tex(uv);
            vec2 px = uP[1] / uResolution;
            vec3 acc = vec3(0.0);
            for (int i = 0; i < 8; i++) { float a = float(i) * 0.785; acc += tex(uv + vec2(cos(a), sin(a)) * px).rgb; }
            acc /= 8.0;
            vec3 screen = 1.0 - (1.0 - c.rgb) * (1.0 - acc);
            vec3 col = mix(c.rgb, screen * vec3(1.03, 0.98, 1.05), uP[0]);
            return vec4(col, c.a);
            """
        })
        register(video("film_burn", "Brûlure de pellicule", C.LIGHT, listOf(p("intensity", "Intensité", 0.0, 1.0, 0.6), p("speed", "Vitesse", 0.1, 3.0, 1.0)), animated = true) {
            """
            vec4 c = tex(uv);
            float n = noise(uv * 3.0 + vec2(uTime * uP[1], uTime * uP[1] * 0.6));
            float n2 = noise(uv * 7.0 - uTime * uP[1]);
            float burn = smoothstep(0.55, 1.0, n * 0.7 + n2 * 0.3 + (uv.x - 0.5) * 0.4);
            vec3 bc = mix(vec3(1.0, 0.3, 0.0), vec3(1.0, 0.95, 0.7), burn);
            return vec4(mix(c.rgb, 1.0 - (1.0 - c.rgb) * (1.0 - bc), burn * uP[0]), c.a);
            """
        })

        // ------------------------------------------------------------------ INTERNAL (engine)
        register(video("_chroma_key", "Incrustation (fond vert)", C.INTERNAL, listOf(
            p("r", "R", 0.0, 1.0, 0.0), p("g", "V", 0.0, 1.0, 1.0), p("b", "B", 0.0, 1.0, 0.0),
            p("tolerance", "Tolérance", 0.0, 1.0, 0.35), p("softness", "Douceur", 0.0, 1.0, 0.1), p("spill", "Suppression des franges", 0.0, 1.0, 0.5),
        )) {
            """
            vec4 c = tex(uv);
            vec3 key = vec3(uP[0], uP[1], uP[2]);
            vec2 cbcr = vec2(dot(c.rgb, vec3(-0.169, -0.331, 0.5)), dot(c.rgb, vec3(0.5, -0.419, -0.081)));
            vec2 kcbcr = vec2(dot(key, vec3(-0.169, -0.331, 0.5)), dot(key, vec3(0.5, -0.419, -0.081)));
            float d = distance(cbcr, kcbcr) * 2.0;
            float a = smoothstep(uP[3] * 0.5, uP[3] * 0.5 + max(uP[4], 0.001) * 0.5, d);
            vec3 col = c.rgb;
            float keyCh = max(key.g, max(key.r, key.b));
            if (key.g >= key.r && key.g >= key.b) col.g = mix(col.g, min(col.g, (col.r + col.b) * 0.5), uP[5]);
            else if (key.b >= key.r) col.b = mix(col.b, min(col.b, (col.r + col.g) * 0.5), uP[5]);
            else col.r = mix(col.r, min(col.r, (col.g + col.b) * 0.5), uP[5]);
            return vec4(col, c.a * a);
            """
        })
        register(video("_mask", "Masque", C.INTERNAL, listOf(
            p("shape", "Forme", 0.0, 4.0, 1.0), p("cx", "X", 0.0, 1.0, 0.5), p("cy", "Y", 0.0, 1.0, 0.5),
            p("w", "Largeur", 0.0, 2.0, 0.6), p("h", "Hauteur", 0.0, 2.0, 0.6), p("rotation", "Rotation", -360.0, 360.0, 0.0),
            p("feather", "Contour progressif", 0.0, 0.5, 0.05), p("invert", "Inverser", 0.0, 1.0, 0.0),
        )) {
            """
            vec4 c = tex(uv);
            vec2 q = vec2(uv.x, 1.0 - uv.y) - vec2(uP[1], uP[2]);
            q.x *= uResolution.x / uResolution.y;
            q = rotate2(q, -radians(uP[5]));
            float ar = uResolution.x / uResolution.y;
            float m = 1.0;
            float f = max(uP[6], 0.0005);
            if (uP[0] < 1.5) { vec2 hs = vec2(uP[3] * ar, uP[4]) * 0.5; vec2 dd = abs(q) - hs; float sd = max(dd.x, dd.y); m = 1.0 - smoothstep(-f, f, sd); }
            else if (uP[0] < 2.5) { vec2 hs = vec2(uP[3] * ar, uP[4]) * 0.5; float sd = length(q / max(hs, vec2(0.0001))) - 1.0; m = 1.0 - smoothstep(-f * 4.0, f * 4.0, sd); }
            else if (uP[0] < 3.5) { m = 1.0 - smoothstep(-f, f, q.y); }
            else { m = 1.0 - smoothstep(uP[4] * 0.5 - f, uP[4] * 0.5 + f, abs(q.y)); }
            if (uP[7] > 0.5) m = 1.0 - m;
            return vec4(c.rgb, c.a * m);
            """
        })
        register(video("_color", "Couleur", C.INTERNAL, listOf(p("r", "R", 0.0, 1.0, 0.0), p("g", "G", 0.0, 1.0, 0.0), p("b", "B", 0.0, 1.0, 0.0), p("a", "A", 0.0, 1.0, 1.0))) {
            """
            return vec4(uP[0], uP[1], uP[2], uP[3]);
            """
        })
        // Transition layer shaders. uProgress = 0..1 visibility progress of the layer.
        register(video("_tr_wipe", "Balayage", C.INTERNAL, listOf(p("angle", "Direction", 0.0, 360.0, 0.0), p("softness", "Douceur", 0.0, 0.3, 0.05), p("invert", "Inverser", 0.0, 1.0, 0.0))) {
            """
            vec4 c = tex(uv);
            vec2 dir = vec2(cos(radians(uP[0])), -sin(radians(uP[0])));
            float x = dot(vec2(uv.x, 1.0 - uv.y) - 0.5, dir) / (abs(dir.x) * 0.5 + abs(dir.y) * 0.5) * 0.5 + 0.5;
            float s = max(uP[1], 0.0001);
            float edge = uProgress * (1.0 + 2.0 * s) - s;
            float m = 1.0 - smoothstep(edge - s, edge + s, x);
            if (uP[2] > 0.5) m = 1.0 - m;
            return vec4(c.rgb, c.a * m);
            """
        })
        register(video("_tr_circle", "Iris", C.INTERNAL, listOf(p("softness", "Douceur", 0.0, 0.3, 0.04), p("invert", "Inverser", 0.0, 1.0, 0.0))) {
            """
            vec4 c = tex(uv);
            vec2 d = (uv - 0.5) * vec2(uResolution.x / uResolution.y, 1.0);
            float maxR = length(vec2(uResolution.x / uResolution.y, 1.0)) * 0.5 + uP[0];
            float m = 1.0 - smoothstep(uProgress * maxR - uP[0], uProgress * maxR + uP[0], length(d));
            if (uP[1] > 0.5) m = 1.0 - m;
            return vec4(c.rgb, c.a * m);
            """
        })
        register(video("_tr_dissolve", "Dissolution", C.INTERNAL, listOf(p("grain", "Grain", 2.0, 200.0, 60.0), p("invert", "Inverser", 0.0, 1.0, 0.0))) {
            """
            vec4 c = tex(uv);
            float n = noise(uv * uP[0]) * 0.7 + hash(floor(uv * uResolution / 2.0)) * 0.3;
            float m = step(n, uProgress * 1.02);
            if (uP[1] > 0.5) m = 1.0 - m;
            return vec4(c.rgb, c.a * m);
            """
        })
        register(video("_tr_blur", "Flou de transition", C.INTERNAL, listOf(p("radius", "Rayon", 0.0, 60.0, 30.0))) {
            """
            float r = uP[0] * (1.0 - uProgress);
            vec2 px = max(r, 0.001) / uResolution;
            vec4 acc = vec4(0.0); float ws = 0.0;
            for (int x = -3; x <= 3; x++) for (int y = -3; y <= 3; y++) {
              vec2 o = vec2(float(x), float(y)) / 3.0; float w = exp(-dot(o, o) * 2.0);
              acc += tex(uv + o * px) * w; ws += w;
            }
            return acc / ws;
            """
        })
        register(video("_tr_flash", "Flash", C.INTERNAL, listOf(p("r", "R", 0.0, 1.0, 1.0), p("g", "G", 0.0, 1.0, 1.0), p("b", "B", 0.0, 1.0, 1.0))) {
            """
            vec4 c = tex(uv);
            float f = 1.0 - uProgress;
            return vec4(mix(c.rgb, vec3(uP[0], uP[1], uP[2]), f), c.a);
            """
        })
        register(video("_tr_glitch", "Glitch de transition", C.INTERNAL, listOf(p("intensity", "Intensité", 0.0, 1.0, 1.0))) {
            """
            float k = (1.0 - uProgress) * uP[0];
            float row = floor(uv.y * 24.0);
            float t = floor(uProgress * 30.0);
            float shift = (hash(vec2(row, t)) - 0.5) * 0.3 * k * step(0.4, hash(vec2(t, row + 1.0)));
            vec2 u2 = vec2(fract(uv.x + shift), uv.y);
            vec4 c = tex(u2);
            float s = 0.04 * k;
            return vec4(tex(u2 + vec2(s, 0.0)).r, c.g, tex(u2 - vec2(s, 0.0)).b, c.a);
            """
        })
        register(video("_tr_distort", "Distorsion de transition", C.INTERNAL, listOf(p("amount", "Intensité", 0.0, 1.0, 1.0))) {
            """
            float k = (1.0 - uProgress) * uP[0];
            vec2 d = uv - 0.5;
            float r = length(d);
            vec2 u = 0.5 + d * (1.0 + k * 0.6 * sin(r * 18.0 - uProgress * 10.0));
            return tex(u);
            """
        })
        register(video("_tr_light", "Lumière de transition", C.INTERNAL, listOf(p("hue", "Teinte", 0.0, 360.0, 35.0))) {
            """
            vec4 c = tex(uv);
            float k = 1.0 - uProgress;
            vec3 lc = hueColor(uP[0]) * 0.6 + 0.4;
            float sweep = smoothstep(0.6, 0.0, abs(uv.x - (1.0 - uProgress)));
            vec3 col = 1.0 - (1.0 - c.rgb) * (1.0 - lc * (k * 0.9 + sweep * k));
            return vec4(col, c.a);
            """
        })

        // ------------------------------------------------------------------ AUDIO (core DSP)
        register(audio("a_compressor", "Compresseur", C.AUDIO, listOf(p("threshold", "Seuil", -60.0, 0.0, -18.0, "dB"), p("ratio", "Ratio", 1.0, 20.0, 4.0, ":1"), p("attack", "Attaque", 0.1, 100.0, 10.0, "ms"), p("release", "Relâchement", 10.0, 1000.0, 120.0, "ms"), p("makeup", "Gain", 0.0, 24.0, 4.0, "dB"))))
        register(audio("a_limiter", "Limiteur", C.AUDIO, listOf(p("ceiling", "Plafond", -12.0, 0.0, -1.0, "dB"), p("release", "Relâchement", 10.0, 500.0, 80.0, "ms"))))
        register(audio("a_reverb", "Réverbération", C.AUDIO, listOf(p("room", "Taille de pièce", 0.0, 1.0, 0.6), p("damping", "Amortissement", 0.0, 1.0, 0.4), p("wet", "Mix", 0.0, 1.0, 0.3), p("width", "Largeur", 0.0, 1.0, 1.0))))
        register(audio("a_delay", "Écho / Retard", C.AUDIO, listOf(p("time", "Temps", 10.0, 2000.0, 320.0, "ms"), p("feedback", "Répétitions", 0.0, 0.95, 0.4), p("wet", "Mix", 0.0, 1.0, 0.35))))
        register(audio("a_denoise", "Réduction de bruit", C.AUDIO, listOf(p("amount", "Intensité", 0.0, 1.0, 0.7), p("sensitivity", "Sensibilité", 0.5, 4.0, 1.5))))
        register(audio("a_gate", "Porte de bruit", C.AUDIO, listOf(p("threshold", "Seuil", -80.0, -10.0, -45.0, "dB"), p("release", "Relâchement", 10.0, 500.0, 100.0, "ms"))))
        register(audio("a_highpass", "Filtre passe-haut", C.AUDIO, listOf(p("freq", "Fréquence", 20.0, 2000.0, 90.0, "Hz"))))
        register(audio("a_lowpass", "Filtre passe-bas", C.AUDIO, listOf(p("freq", "Fréquence", 200.0, 20000.0, 8000.0, "Hz"))))
        register(audio("a_deesser", "De-esser", C.AUDIO, listOf(p("amount", "Intensité", 0.0, 1.0, 0.5), p("freq", "Fréquence", 3000.0, 10000.0, 6500.0, "Hz"))))
        register(audio("a_stereo_width", "Largeur stéréo", C.AUDIO, listOf(p("width", "Largeur", 0.0, 2.0, 1.3))))
        register(audio("v_robot", "Voix robot", C.VOICE, listOf(p("freq", "Fréquence", 20.0, 400.0, 90.0, "Hz"), p("mix", "Mix", 0.0, 1.0, 0.8))))
        register(audio("v_telephone", "Téléphone", C.VOICE, listOf(p("drive", "Saturation", 0.0, 1.0, 0.3))))
        register(audio("v_radio", "Radio", C.VOICE, listOf(p("drive", "Saturation", 0.0, 1.0, 0.5), p("noise", "Souffle", 0.0, 1.0, 0.2))))
        register(audio("v_megaphone", "Mégaphone", C.VOICE, listOf(p("drive", "Saturation", 0.0, 1.0, 0.7))))
        register(audio("v_cave", "Caverne", C.VOICE, listOf(p("size", "Taille", 0.0, 1.0, 0.8))))
        register(audio("v_pitch", "Hauteur de voix", C.VOICE, listOf(p("semitones", "Demi-tons", -12.0, 12.0, 0.0, "st")), desc = "Appliqué par le moteur (Sonic)"))
        register(audio("v_bitcrush", "Rétro 8-bit", C.VOICE, listOf(p("bits", "Bits", 2.0, 16.0, 6.0), p("downsample", "Sous-échantillonnage", 1.0, 32.0, 6.0))))
    }
}
