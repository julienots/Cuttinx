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
        // ------------------------------------------------------------------ TREND (signature looks of CUTTINX)
        register(video("holo_foil", "Hologramme irisé", C.TREND, listOf(p("amount", "Intensité", 0.0, 1.0, 0.7), p("scale", "Finesse des reflets", 0.5, 5.0, 2.0), p("speed", "Vitesse", 0.0, 3.0, 1.0)),
            animated = true, desc = "Reflets arc-en-ciel de carte holographique qui glissent sur l'image") {
            """
            vec4 c = tex(uv);
            float l = luma(c.rgb);
            float a = (uv.x * 1.3 + uv.y * 0.7) * uP[1] + l * 2.2 + uTime * uP[2] * 0.25;
            vec3 irid = 0.5 + 0.5 * cos(6.2831 * (vec3(a) + vec3(0.0, 0.33, 0.67)));
            float spark = pow(max(0.0, sin((uv.x + uv.y) * 90.0 + uTime * uP[2] * 4.0)), 24.0) * smoothstep(0.55, 1.0, l);
            vec3 col = mix(c.rgb, c.rgb * 0.55 + irid * (0.35 + 0.65 * l), uP[0]);
            col += vec3(spark * 0.6 * uP[0]);
            return vec4(clamp(col, 0.0, 1.0), c.a);
            """
        })
        register(video("liquid_chrome", "Chrome liquide", C.TREND, listOf(p("amount", "Intensité", 0.0, 1.0, 0.85), p("flow", "Mouvement", 0.0, 2.0, 0.6)),
            animated = true, desc = "L'image devient du métal liquide qui ondule") {
            """
            vec2 w = vec2(noise(uv * 4.0 + vec2(uTime * uP[1] * 0.4)), noise(uv * 4.0 + vec2(7.3) - vec2(uTime * uP[1] * 0.3))) - 0.5;
            vec4 c = tex(uv + w * 0.03 * uP[0]);
            float l = luma(c.rgb);
            float m = 0.5 + 0.5 * sin((l * 3.0 + w.x * 2.0 + uv.y * 1.5) * 6.2831);
            vec3 chrome = mix(vec3(0.08, 0.09, 0.12), vec3(0.95, 0.97, 1.0), smoothstep(0.15, 0.95, m));
            chrome += vec3(0.25, 0.3, 0.45) * pow(m, 8.0);
            return vec4(clamp(mix(c.rgb, chrome * (0.6 + 0.6 * l), uP[0]), 0.0, 1.0), c.a);
            """
        })
        register(video("glitch_slices", "Tranches glitch", C.TREND, listOf(p("amount", "Intensité", 0.0, 1.0, 0.6), p("slices", "Nombre de tranches", 4.0, 60.0, 18.0), p("speed", "Vitesse", 0.0, 3.0, 1.0)),
            animated = true, desc = "Bandes horizontales qui sautent avec séparation RVB, façon clip") {
            """
            float t = floor(uTime * (6.0 + uP[2] * 10.0));
            float row = floor(uv.y * uP[1]);
            float on = step(1.0 - uP[0] * 0.7, hash(vec2(row, t)));
            float shift = (hash(vec2(row * 1.7, t + 3.0)) - 0.5) * 0.25 * uP[0] * on;
            vec2 q = vec2(fract(uv.x + shift), uv.y);
            float ca = 0.012 * uP[0] * (0.3 + on);
            vec4 c = tex(q);
            vec3 col = vec3(tex(q + vec2(ca, 0.0)).r, c.g, tex(q - vec2(ca, 0.0)).b);
            float block = hash(vec2(floor(uv.x * 12.0), floor(uv.y * 8.0) + t));
            col = mix(col, vec3(1.0) - col, step(0.985, block) * uP[0]);
            return vec4(col, c.a);
            """
        })
        register(video("manga_impact", "Impact manga", C.TREND, listOf(p("amount", "Intensité", 0.0, 1.0, 0.8), p("lines", "Densité des traits", 20.0, 160.0, 90.0), p("speed", "Vitesse", 0.0, 3.0, 1.0)),
            animated = true, desc = "Traits de vitesse radiaux et encrage noir et blanc, comme une case de manga") {
            """
            vec4 c = tex(uv);
            vec2 d = uv - 0.5;
            d.x *= uResolution.x / uResolution.y;
            float a = atan(d.y, d.x);
            float r = length(d);
            float n = hash(vec2(floor(a / 6.2831 * uP[1]), floor(uTime * 12.0 * uP[2])));
            float lines = step(0.55, n) * smoothstep(0.18 + 0.2 * n, 0.75, r);
            vec3 ink = vec3(smoothstep(0.32, 0.6, luma(c.rgb)));
            vec3 col = mix(c.rgb, mix(ink, c.rgb, 0.35), uP[0] * 0.6);
            col = mix(col, vec3(1.0), lines * uP[0]);
            return vec4(col, c.a);
            """
        })
        register(video("matrix_code", "Code matrice", C.TREND, listOf(p("amount", "Intensité", 0.0, 1.0, 0.85), p("cell", "Taille des signes", 6.0, 30.0, 12.0, "px"), p("speed", "Vitesse", 0.0, 3.0, 1.0)),
            animated = true, desc = "L'image redessinée en pluie de signes verts lumineux") {
            """
            vec2 cell = vec2(uP[1]) / uResolution;
            vec2 id = floor(uv / cell);
            vec2 f = fract(uv / cell);
            vec4 c = tex((id + 0.5) * cell);
            float l = luma(c.rgb);
            float drop = fract(uv.y * 0.9 + uTime * uP[2] * (0.15 + 0.3 * hash(vec2(id.x, 2.0))) + hash(vec2(id.x, 1.0)));
            float head = pow(drop, 10.0);
            float g = step(0.5, hash(id + floor(f * 3.0) * 0.37 + vec2(floor(uTime * 8.0 * uP[2] + hash(id) * 10.0))));
            float glyph = g * step(0.15, f.x) * step(f.x, 0.85) * step(0.1, f.y) * step(f.y, 0.9);
            vec3 m = vec3(0.1, 1.0, 0.35) * glyph * (0.25 + 1.2 * l) * (0.25 + 0.75 * drop) + vec3(0.8, 1.0, 0.85) * head * glyph;
            return vec4(clamp(mix(c.rgb, m, uP[0]), 0.0, 1.0), c.a);
            """
        })
        register(video("retro_console", "Console 4 couleurs", C.TREND, listOf(p("pixel", "Taille des pixels", 1.0, 8.0, 3.0, "px"), p("amount", "Intensité", 0.0, 1.0, 1.0), choice("palette", "Palette", "Vert console", "Rose néon", "Bleu glace", "Sépia")),
            desc = "Pixels tramés et palette de 4 couleurs des vieilles consoles portables") {
            """
            vec2 px = vec2(uP[0]) / uResolution;
            vec2 cellId = floor(uv / px);
            vec4 c = tex((cellId + 0.5) * px);
            vec2 g = mod(cellId, 4.0);
            vec2 g1 = mod(g, 2.0); vec2 g2 = floor(g / 2.0);
            float bayer = (4.0 * mod(2.0 * g1.x + 3.0 * g1.y, 4.0) + mod(2.0 * g2.x + 3.0 * g2.y, 4.0)) / 16.0;
            float v = clamp(luma(c.rgb) + (bayer - 0.5) * 0.25, 0.0, 1.0);
            float lvl = floor(v * 3.999);
            vec3 c0 = vec3(0.06, 0.22, 0.06); vec3 c1 = vec3(0.19, 0.38, 0.19); vec3 c2 = vec3(0.55, 0.67, 0.06); vec3 c3 = vec3(0.61, 0.74, 0.06);
            if (uP[2] > 0.5 && uP[2] < 1.5) { c0 = vec3(0.10, 0.02, 0.15); c1 = vec3(0.45, 0.05, 0.45); c2 = vec3(1.0, 0.25, 0.6); c3 = vec3(1.0, 0.8, 0.95); }
            else if (uP[2] > 1.5 && uP[2] < 2.5) { c0 = vec3(0.02, 0.05, 0.15); c1 = vec3(0.1, 0.3, 0.6); c2 = vec3(0.4, 0.75, 0.95); c3 = vec3(0.9, 0.97, 1.0); }
            else if (uP[2] > 2.5) { c0 = vec3(0.15, 0.09, 0.05); c1 = vec3(0.42, 0.28, 0.16); c2 = vec3(0.75, 0.6, 0.4); c3 = vec3(0.96, 0.9, 0.75); }
            vec3 col = lvl < 0.5 ? c0 : (lvl < 1.5 ? c1 : (lvl < 2.5 ? c2 : c3));
            return vec4(mix(tex(uv).rgb, col, uP[1]), c.a);
            """
        })
        register(video("bass_drop", "Coup de basse", C.TREND, listOf(p("bpm", "Tempo", 60.0, 180.0, 120.0, "BPM"), p("amount", "Intensité", 0.0, 1.0, 0.7)),
            animated = true, desc = "Zoom, aberration et flash qui frappent sur chaque temps de la musique") {
            """
            float beat = fract(uTime * uP[0] / 60.0);
            float k = exp(-beat * 7.0) * uP[1];
            vec2 d = uv - 0.5;
            vec2 q = 0.5 + d / (1.0 + 0.12 * k);
            float ca = 0.02 * k;
            vec4 c = tex(q);
            vec3 col = vec3(tex(q + d * ca).r, c.g, tex(q - d * ca).b);
            col *= 1.0 - smoothstep(0.3, 0.85, length(d)) * 0.6 * k;
            col += vec3(0.12 * k);
            return vec4(clamp(col, 0.0, 1.0), c.a);
            """
        })
        register(video("aerochrome", "Infrarouge rose", C.TREND, listOf(p("amount", "Intensité", 0.0, 1.0, 0.9)),
            desc = "La végétation devient rose et rouge, comme la pellicule infrarouge") {
            """
            vec4 c = tex(uv);
            vec3 h = rgb2hsv(c.rgb);
            float green = smoothstep(0.15, 0.25, h.x) * (1.0 - smoothstep(0.45, 0.55, h.x)) * h.y;
            vec3 ir = vec3(c.g * 1.1 + c.r * 0.2, c.r * 0.6, c.b * 0.9 + c.g * 0.25);
            ir = mix(ir, vec3(1.0, 0.25, 0.45) * (0.4 + luma(c.rgb)), green * 0.8);
            return vec4(mix(c.rgb, clamp(ir, 0.0, 1.0), uP[0]), c.a);
            """
        })
        register(video("droste", "Tunnel miroir infini", C.TREND, listOf(p("speed", "Vitesse", -2.0, 2.0, 0.5), p("scale", "Profondeur", 0.3, 0.85, 0.6), p("twist", "Torsion", -1.0, 1.0, 0.2)),
            animated = true, desc = "L'image se répète à l'infini dans elle-même et zoome sans fin") {
            """
            vec2 p = uv - 0.5;
            float m = max(max(abs(p.x), abs(p.y)) * 2.0, 0.0001);
            float s = uP[1];
            float T = uTime * uP[0];
            float k = floor(log(m) / log(s) + T);
            vec2 q = rotate2(p * pow(s, T - k), uP[2] * k);
            return tex(q + 0.5);
            """
        })
        register(video("god_rays", "Rayons divins", C.TREND, listOf(p("amount", "Intensité", 0.0, 2.0, 0.8), p("x", "Source horizontale", 0.0, 1.0, 0.5), p("y", "Source verticale", 0.0, 1.0, 0.85), p("length", "Longueur", 0.1, 1.0, 0.6)),
            desc = "Faisceaux de lumière volumétriques qui partent des zones claires") {
            """
            vec2 dir = (uv - vec2(uP[1], uP[2])) * uP[3] / 24.0;
            vec4 c = tex(uv);
            vec3 acc = vec3(0.0);
            float w = 1.0;
            vec2 q = uv;
            for (int i = 0; i < 24; i++) {
              q -= dir;
              vec3 s = tex(q).rgb;
              acc += s * smoothstep(0.55, 1.0, luma(s)) * w;
              w *= 0.94;
            }
            return vec4(clamp(c.rgb + acc / 10.0 * uP[0], 0.0, 1.0), c.a);
            """
        })
        register(video("y2k_glints", "Étoiles Y2K", C.TREND, listOf(p("amount", "Intensité", 0.0, 1.5, 0.8), p("size", "Taille", 0.0, 1.0, 0.5), p("threshold", "Seuil de lumière", 0.3, 1.0, 0.7)),
            animated = true, desc = "Étoiles scintillantes sur les reflets, look clip des années 2000") {
            """
            vec4 c = tex(uv);
            vec2 cellPx = vec2(28.0);
            vec2 g = uv * uResolution / cellPx;
            vec2 id = floor(g);
            vec3 add = vec3(0.0);
            for (int y = -1; y <= 1; y++) {
              for (int x = -1; x <= 1; x++) {
                vec2 cid = id + vec2(float(x), float(y));
                float h = hash(cid);
                float on = step(uP[2], luma(tex((cid + 0.5) * cellPx / uResolution).rgb)) * step(0.55, h);
                vec2 d = (g - cid - 0.5) / (0.6 + uP[1] * 1.6);
                float tw = 0.5 + 0.5 * sin(uTime * 3.0 + h * 40.0);
                float star = exp(-abs(d.x) * 22.0) * exp(-abs(d.y) * 2.2) + exp(-abs(d.y) * 22.0) * exp(-abs(d.x) * 2.2) + exp(-length(d) * 6.0);
                add += vec3(1.0, 0.95, 0.85) * star * on * tw;
              }
            }
            return vec4(clamp(c.rgb + add * uP[0], 0.0, 1.0), c.a);
            """
        })
        register(video("tiny_planet", "Petite planète", C.TREND, listOf(p("zoom", "Taille de la planète", 0.5, 3.0, 1.2), p("spin", "Rotation", -2.0, 2.0, 0.2), p("amount", "Intensité", 0.0, 1.0, 1.0)),
            animated = true, desc = "Le paysage s'enroule en une petite planète ronde") {
            """
            vec2 p = uv - 0.5;
            p.x *= uResolution.x / uResolution.y;
            float r = length(p);
            float u = fract(atan(p.y, p.x) / 6.2831 + 0.25 + uTime * uP[1] * 0.05);
            float v = 1.0 - exp(-r * uP[0] * 2.5);
            return mix(tex(uv), tex(vec2(u, v)), uP[2]);
            """
        })
        register(video("halation", "Halation ciné", C.TREND, listOf(p("amount", "Intensité", 0.0, 1.0, 0.6), p("radius", "Rayon", 1.0, 8.0, 4.0), p("warmth", "Chaleur", 0.0, 1.0, 0.5)),
            desc = "Halo rouge orangé autour des lumières, comme sur la pellicule de cinéma") {
            """
            vec4 c = tex(uv);
            vec2 px = vec2(uP[1] * 2.0) / uResolution;
            vec3 glow = vec3(0.0);
            for (int i = 0; i < 12; i++) {
              float ang = float(i) * 0.5236;
              vec3 s = tex(uv + vec2(cos(ang), sin(ang)) * px * (1.0 + mod(float(i), 3.0))).rgb;
              glow += s * smoothstep(0.6, 1.0, luma(s));
            }
            glow /= 12.0;
            vec3 col = c.rgb + glow * vec3(1.0, 0.25 + 0.2 * (1.0 - uP[2]), 0.1) * uP[0] * 1.6;
            col = mix(col, col * vec3(1.03, 1.0, 0.95), uP[2]);
            return vec4(clamp(col, 0.0, 1.0), c.a);
            """
        })
        register(video("pixel_sort", "Tri de pixels", C.TREND, listOf(p("threshold", "Seuil", 0.1, 1.0, 0.55), p("length", "Longueur des coulures", 0.02, 0.6, 0.25), choice("direction", "Direction", "Verticale", "Horizontale")),
            desc = "Coulures de pixels lumineux, effet glitch-art") {
            """
            vec2 dir = uP[2] < 0.5 ? vec2(0.0, 1.0) : vec2(1.0, 0.0);
            vec4 c = tex(uv);
            vec3 best = c.rgb;
            float bl = luma(c.rgb);
            for (int i = 1; i <= 24; i++) {
              vec3 s = tex(uv + dir * uP[1] * float(i) / 24.0).rgb;
              float l = luma(s);
              float take = step(uP[0], l) * step(bl, l);
              best = mix(best, s, take);
              bl = max(bl, l * take);
            }
            return vec4(mix(c.rgb, best, step(uP[0] * 0.6, luma(c.rgb))), c.a);
            """
        })
        register(video("fluted_glass", "Verre cannelé", C.TREND, listOf(p("ribs", "Nombre de cannelures", 5.0, 80.0, 28.0), p("strength", "Déformation", 0.0, 1.0, 0.5), p("blur", "Flou vertical", 0.0, 1.0, 0.4)),
            desc = "Comme vu à travers une vitre en verre strié, très tendance") {
            """
            float f = fract(uv.x * uP[0]);
            vec2 q = vec2(uv.x + (f - 0.5) * uP[1] * 0.12, uv.y);
            vec2 py = vec2(0.0, uP[2] * 0.01);
            vec4 c = tex(q);
            vec3 col = (c.rgb * 2.0 + tex(q + py).rgb + tex(q - py).rgb) / 4.0;
            col *= 0.85 + 0.25 * sin(f * 3.14159);
            col += vec3(smoothstep(0.75, 1.0, 1.0 - abs(f - 0.25) * 4.0) * 0.18);
            return vec4(clamp(col, 0.0, 1.0), c.a);
            """
        })
        register(video("cyber_scan", "Scanner cyber", C.TREND, listOf(p("speed", "Vitesse", 0.1, 3.0, 0.6), p("amount", "Intensité", 0.0, 1.0, 0.8), hue("hue", "Couleur", 185.0)),
            animated = true, desc = "Un faisceau balaie l'image et révèle ses contours en néon sur une grille") {
            """
            vec4 c = tex(uv);
            vec2 px = 1.0 / uResolution;
            float gx = luma(tex(uv + vec2(px.x, 0.0)).rgb) - luma(tex(uv - vec2(px.x, 0.0)).rgb);
            float gy = luma(tex(uv + vec2(0.0, px.y)).rgb) - luma(tex(uv - vec2(0.0, px.y)).rgb);
            float edge = clamp(length(vec2(gx, gy)) * 6.0, 0.0, 1.0);
            float line = fract(uTime * uP[0] * 0.35);
            float y = 1.0 - uv.y;
            float behind = smoothstep(line - 0.35, line, y) * step(y, line);
            float beam = exp(-abs(y - line) * 120.0);
            vec3 neon = hueColor(uP[2]);
            vec2 grid = abs(fract(uv * vec2(24.0, 13.5)) - 0.5);
            float gl = step(0.47, max(grid.x, grid.y)) * 0.25;
            vec3 col = mix(c.rgb, c.rgb * 0.35 + neon * (edge + gl), behind * uP[1]);
            col += neon * beam * uP[1];
            return vec4(clamp(col, 0.0, 1.0), c.a);
            """
        })
        register(video("rgb_echo", "Écho chromatique", C.TREND, listOf(p("amount", "Intensité", 0.0, 1.0, 0.7), p("speed", "Pulsation", 0.0, 3.0, 1.0)),
            animated = true, desc = "Copies rouge, verte et bleue qui s'échappent en zoom, effet clip musical") {
            """
            vec2 d = uv - 0.5;
            vec4 c = tex(uv);
            float k = uP[0] * (0.55 + 0.45 * sin(uTime * uP[1] * 3.0));
            vec3 echo = vec3(tex(0.5 + d * (1.0 - 0.12 * k)).r, tex(0.5 + d * (1.0 - 0.06 * k)).g, tex(0.5 + d * (1.0 - 0.03 * k)).b);
            return vec4(mix(c.rgb, max(c.rgb, echo), 0.85), c.a);
            """
        })

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
            float act = step(1.0 - uP[0] * 0.6, r);
            float shift = (hash(vec2(t, row * 3.1)) - 0.5) * 0.25 * uP[0] * act;
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

        // ------------------------------------------------------------------ CREATOR PACK
        register(video("color_pop", "Couleur isolée", C.COLOR, listOf(hue("hue", "Teinte conservée", 0.0), p("range", "Plage", 5.0, 120.0, 30.0, "°"), p("softness", "Douceur", 0.0, 60.0, 15.0, "°")), desc = "Garde une seule couleur, le reste en noir et blanc") {
            """
            vec4 c = tex(uv);
            vec3 h = rgb2hsv(c.rgb);
            float d = abs(fract(h.x - uP[0] / 360.0 + 0.5) - 0.5) * 360.0;
            float keep = 1.0 - smoothstep(uP[1], uP[1] + uP[2] + 0.001, d);
            keep *= smoothstep(0.05, 0.2, h.y);
            return vec4(mix(vec3(luma(c.rgb)), c.rgb, keep), c.a);
            """
        })
        register(video("tilt_shift", "Tilt-shift (miniature)", C.BLUR, listOf(p("center", "Position", 0.0, 1.0, 0.5), p("width", "Zone nette", 0.02, 0.8, 0.18), p("radius", "Flou", 0.0, 30.0, 10.0, "px"))) {
            """
            float dist = abs((1.0 - uv.y) - uP[0]);
            float k = smoothstep(uP[1] * 0.5, uP[1] * 0.5 + 0.25, dist);
            vec2 px = uP[2] * k / uResolution;
            vec4 acc = vec4(0.0); float ws = 0.0;
            for (int x = -2; x <= 2; x++) { for (int y = -2; y <= 2; y++) {
              vec2 o = vec2(float(x), float(y)) * 0.5; float w = exp(-dot(o, o));
              acc += tex(uv + o * px) * w; ws += w; } }
            vec4 col = acc / ws;
            col.rgb = mix(vec3(luma(col.rgb)), col.rgb, 1.0 + 0.25 * k);
            return vec4(clamp(col.rgb, 0.0, 1.0), col.a);
            """
        })
        register(video("neon_edges", "Contours néon", C.STYLIZE, listOf(hue("hue", "Teinte", 300.0), p("intensity", "Intensité", 0.0, 4.0, 1.6), p("mix", "Image d'origine", 0.0, 1.0, 0.25), toggle("rainbow", "Arc-en-ciel animé", false)), animated = true) {
            """
            vec2 px = 1.0 / uResolution;
            float tl = luma(tex(uv + vec2(-px.x, px.y)).rgb); float t = luma(tex(uv + vec2(0.0, px.y)).rgb); float tr = luma(tex(uv + px).rgb);
            float l = luma(tex(uv - vec2(px.x, 0.0)).rgb); float r = luma(tex(uv + vec2(px.x, 0.0)).rgb);
            float bl = luma(tex(uv - px).rgb); float b = luma(tex(uv - vec2(0.0, px.y)).rgb); float br = luma(tex(uv + vec2(px.x, -px.y)).rgb);
            float gx = -tl - 2.0 * l - bl + tr + 2.0 * r + br;
            float gy = -bl - 2.0 * b - br + tl + 2.0 * t + tr;
            float e = clamp(length(vec2(gx, gy)) * uP[1], 0.0, 1.0);
            float hh = uP[0];
            if (uP[3] > 0.5) hh = mod(uP[0] + uv.x * 180.0 + uTime * 90.0, 360.0);
            vec4 c = tex(uv);
            return vec4(clamp(c.rgb * uP[2] + hueColor(hh) * e * 1.4, 0.0, 1.0), c.a);
            """
        })
        register(video("rgb_shake", "Secousse RVB", C.MOTION, listOf(p("amount", "Décalage", 0.0, 40.0, 12.0, "px"), p("speed", "Vitesse", 0.5, 20.0, 8.0)), animated = true) {
            """
            float t = floor(uTime * uP[1]);
            vec2 o1 = (vec2(hash(vec2(t, 1.0)), hash(vec2(t, 2.0))) - 0.5) * 2.0 * uP[0] / uResolution;
            vec2 o2 = (vec2(hash(vec2(t, 3.0)), hash(vec2(t, 4.0))) - 0.5) * 2.0 * uP[0] / uResolution;
            vec4 c = tex(uv);
            return vec4(tex(uv + o1).r, c.g, tex(uv + o2).b, c.a);
            """
        })
        register(video("beat_flash", "Flash rythmé", C.LIGHT, listOf(p("bpm", "Tempo", 40.0, 200.0, 120.0, "BPM"), p("intensity", "Intensité", 0.0, 1.0, 0.5), p("decay", "Décroissance", 1.0, 20.0, 8.0)), animated = true) {
            """
            vec4 c = tex(uv);
            float ph = fract(uTime * uP[0] / 60.0);
            float f = exp(-ph * uP[2]) * uP[1];
            return vec4(clamp(c.rgb + f, 0.0, 1.0), c.a);
            """
        })
        register(video("scanlines", "Lignes de balayage", C.RETRO, listOf(p("density", "Densité", 50.0, 800.0, 300.0), p("intensity", "Intensité", 0.0, 1.0, 0.35), p("speed", "Défilement", 0.0, 5.0, 0.5)), animated = true) {
            """
            vec4 c = tex(uv);
            float s = 0.5 + 0.5 * sin((uv.y + uTime * uP[2] * 0.05) * uP[0] * PI);
            return vec4(c.rgb * (1.0 - uP[1] * s), c.a);
            """
        })
        register(video("cartoon", "Dessin animé", C.STYLIZE, listOf(p("levels", "Niveaux de couleur", 2.0, 12.0, 5.0), p("edges", "Contours", 0.0, 4.0, 1.5))) {
            """
            vec2 px = 1.0 / uResolution;
            vec4 c = tex(uv);
            vec3 q = floor(c.rgb * uP[0] + 0.5) / uP[0];
            float gx = luma(tex(uv + vec2(px.x, 0.0)).rgb) - luma(tex(uv - vec2(px.x, 0.0)).rgb);
            float gy = luma(tex(uv + vec2(0.0, px.y)).rgb) - luma(tex(uv - vec2(0.0, px.y)).rgb);
            float e = smoothstep(0.08, 0.3, length(vec2(gx, gy)) * uP[1]);
            return vec4(mix(q, vec3(0.0), e), c.a);
            """
        })
        register(video("heat_haze", "Brume de chaleur", C.DISTORT, listOf(p("amount", "Intensité", 0.0, 0.03, 0.006), p("scale", "Échelle", 1.0, 60.0, 20.0), p("speed", "Vitesse", 0.0, 5.0, 1.2)), animated = true) {
            """
            float n = noise(uv * uP[1] + vec2(0.0, uTime * uP[2])) - 0.5;
            float m = noise(uv * uP[1] * 1.3 - vec2(uTime * uP[2] * 0.7, 0.0)) - 0.5;
            return tex(uv + vec2(n, m) * uP[0] * 2.0);
            """
        })
        register(video("triple_split", "Écran triple", C.STYLIZE, listOf(toggle("vertical", "Vertical", true), p("gap", "Séparation", 0.0, 0.03, 0.004))) {
            """
            float a = uP[0] > 0.5 ? uv.y : uv.x;
            float seg = floor(a * 3.0);
            float local = fract(a * 3.0);
            float g = uP[1] * 3.0;
            float border = step(local, g) + step(1.0 - g, local);
            vec2 st = uv;
            if (uP[0] > 0.5) st.y = 1.0 / 3.0 + local / 3.0; else st.x = 1.0 / 3.0 + local / 3.0;
            vec4 c = tex(st);
            return vec4(mix(c.rgb, vec3(0.0), clamp(border, 0.0, 1.0)), c.a);
            """
        })
        register(video("prism", "Prisme", C.LIGHT, listOf(p("amount", "Dispersion", 0.0, 0.05, 0.012), p("angle", "Angle", 0.0, 360.0, 45.0, "°"), p("glow", "Éclat", 0.0, 1.0, 0.25))) {
            """
            vec2 dir = vec2(cos(radians(uP[1])), sin(radians(uP[1]))) * uP[0];
            vec4 c = tex(uv);
            vec3 col = vec3(tex(uv + dir).r, c.g, tex(uv - dir).b);
            float l = luma(col);
            col += hueColor(mod(uv.x * 360.0 + uv.y * 200.0, 360.0)) * smoothstep(0.6, 1.0, l) * uP[2];
            return vec4(clamp(col, 0.0, 1.0), c.a);
            """
        })
        register(video("film_look", "Look film (Kodak)", C.CINEMA, listOf(p("amount", "Intensité", 0.0, 1.0, 0.8), p("warmth", "Chaleur", -1.0, 1.0, 0.3), p("halation", "Halation", 0.0, 1.0, 0.3))) {
            """
            vec4 c = tex(uv);
            vec3 col = c.rgb;
            vec3 graded = col;
            graded = graded * graded * (3.0 - 2.0 * graded);
            graded.r += uP[1] * 0.06; graded.b -= uP[1] * 0.06;
            float l = luma(graded);
            graded = mix(vec3(l), graded, 0.88);
            graded = mix(vec3(0.04, 0.03, 0.05), vec3(0.97, 0.95, 0.9), graded);
            float hl = smoothstep(0.75, 1.0, l);
            graded += vec3(1.0, 0.35, 0.2) * hl * uP[2] * 0.25;
            return vec4(clamp(mix(col, graded, uP[0]), 0.0, 1.0), c.a);
            """
        })
        register(video("vhs_rewind", "Rembobinage VHS", C.RETRO, listOf(p("intensity", "Intensité", 0.0, 1.0, 0.7)), animated = true) {
            """
            float band = step(0.92, fract(uv.y * 3.0 - uTime * 2.5));
            float jitter = (hash(vec2(floor(uv.y * 120.0), floor(uTime * 30.0))) - 0.5) * 0.02 * uP[0];
            vec2 st = uv + vec2(jitter + band * 0.03 * uP[0], 0.0);
            vec4 c = tex(st);
            vec3 col = vec3(tex(st + vec2(0.004 * uP[0], 0.0)).r, c.g, tex(st - vec2(0.004 * uP[0], 0.0)).b);
            col = mix(col, vec3(1.0), band * 0.35 * uP[0]);
            col += (hash(uv * uResolution + uTime) - 0.5) * 0.12 * uP[0];
            return vec4(clamp(col, 0.0, 1.0), c.a);
            """
        })
        register(video("spotlight", "Projecteur", C.LIGHT, listOf(p("x", "Centre X", 0.0, 1.0, 0.5), p("y", "Centre Y", 0.0, 1.0, 0.45), p("radius", "Rayon", 0.05, 1.0, 0.35), p("darkness", "Obscurité", 0.0, 1.0, 0.75))) {
            """
            vec4 c = tex(uv);
            vec2 d = (uv - vec2(uP[0], 1.0 - uP[1])) * vec2(uResolution.x / uResolution.y, 1.0);
            float k = smoothstep(uP[2], uP[2] * 1.6 + 0.02, length(d));
            return vec4(c.rgb * (1.0 - k * uP[3]), c.a);
            """
        })

        // ------------------------------------------------------------------ YOUTUBE / TIKTOK PACK
        register(video("vintage_warm", "Chaud vintage", C.COLOR, listOf(p("amount", "Intensité", 0.0, 1.0, 0.7))) {
            """
            vec4 c = tex(uv);
            vec3 w = vec3(c.r * 1.08 + 0.04, c.g * 0.98 + 0.02, c.b * 0.82);
            w = mix(vec3(luma(w)), w, 0.85);
            w = mix(vec3(0.08, 0.05, 0.02), vec3(1.0, 0.96, 0.88), w);
            return vec4(clamp(mix(c.rgb, w, uP[0]), 0.0, 1.0), c.a);
            """
        })
        register(video("cool_cinema", "Froid cinéma", C.CINEMA, listOf(p("amount", "Intensité", 0.0, 1.0, 0.7))) {
            """
            vec4 c = tex(uv);
            float l = luma(c.rgb);
            vec3 g = mix(vec3(0.02, 0.06, 0.12), vec3(0.92, 0.97, 1.0), smoothstep(0.0, 1.0, l));
            g = mix(g, c.rgb * vec3(0.9, 1.0, 1.12), 0.45);
            return vec4(clamp(mix(c.rgb, g, uP[0]), 0.0, 1.0), c.a);
            """
        })
        register(video("rain", "Pluie", C.PARTICLES, listOf(p("amount", "Densité", 0.0, 1.0, 0.6), p("speed", "Vitesse", 0.2, 4.0, 1.5), p("angle", "Inclinaison", -0.5, 0.5, 0.12)), animated = true) {
            """
            vec4 c = tex(uv);
            vec2 st = vec2(uv.x + uv.y * uP[2], uv.y);
            float col = floor(st.x * 160.0);
            float h = hash(vec2(col, 7.0));
            float y = fract(st.y * (1.0 + h) + uTime * uP[1] * (1.2 + h));
            float drop = step(1.0 - 0.07 * uP[0], h) * smoothstep(0.0, 0.08, y) * (1.0 - smoothstep(0.08, 0.2, y));
            return vec4(mix(c.rgb * (1.0 - 0.15 * uP[0]), vec3(0.85, 0.9, 1.0), drop * 0.6), c.a);
            """
        })
        register(video("fire", "Flammes", C.LIGHT, listOf(p("height", "Hauteur", 0.05, 0.8, 0.3), p("intensity", "Intensité", 0.0, 1.5, 1.0)), animated = true) {
            """
            vec4 c = tex(uv);
            float y = uv.y / max(uP[0], 0.01);
            float n = noise(vec2(uv.x * 9.0, uv.y * 6.0 - uTime * 3.0)) * 0.6 + noise(vec2(uv.x * 23.0, uv.y * 14.0 - uTime * 5.0)) * 0.4;
            float f = clamp((1.0 - y) * 1.4 - n * 0.9, 0.0, 1.0) * uP[1];
            vec3 fc = mix(vec3(0.9, 0.15, 0.0), vec3(1.0, 0.85, 0.3), f);
            return vec4(clamp(c.rgb + fc * f, 0.0, 1.0), c.a);
            """
        })
        register(video("bokeh", "Bokeh lumineux", C.LIGHT, listOf(p("amount", "Intensité", 0.0, 1.0, 0.5), hue("hue", "Teinte", 40.0), p("size", "Taille", 0.02, 0.15, 0.06)), animated = true) {
            """
            vec4 c = tex(uv);
            vec3 acc = vec3(0.0);
            vec2 ar = vec2(uResolution.x / uResolution.y, 1.0);
            for (int i = 0; i < 14; i++) {
              float fi = float(i);
              vec2 p = vec2(hash(vec2(fi, 1.0)), fract(hash(vec2(fi, 2.0)) + uTime * 0.03 * (0.5 + hash(vec2(fi, 3.0)))));
              float r = uP[2] * (0.6 + hash(vec2(fi, 4.0)));
              float d = length((uv - p) * ar);
              acc += hueColor(uP[1] + fi * 9.0) * smoothstep(r, r * 0.8, d) * 0.35;
            }
            return vec4(clamp(c.rgb + acc * uP[0], 0.0, 1.0), c.a);
            """
        })
        register(video("hearts_float", "Cœurs flottants", C.PARTICLES, listOf(p("amount", "Quantité", 0.0, 1.0, 0.6), p("size", "Taille", 0.02, 0.1, 0.045)), animated = true) {
            """
            vec4 c = tex(uv);
            vec2 ar = vec2(uResolution.x / uResolution.y, 1.0);
            float m = 0.0;
            for (int i = 0; i < 16; i++) {
              float fi = float(i);
              if (hash(vec2(fi, 9.0)) > uP[0]) continue;
              vec2 p = vec2(hash(vec2(fi, 1.0)) + sin(uTime + fi) * 0.02, fract(hash(vec2(fi, 2.0)) + uTime * 0.08 * (0.6 + hash(vec2(fi, 3.0)))));
              vec2 q = (uv - p) * ar / uP[1];
              q.y = -q.y + 0.25;
              float h = q.x * q.x + pow(q.y - pow(abs(q.x), 0.66), 2.0);
              m = max(m, smoothstep(0.42, 0.36, h));
            }
            return vec4(mix(c.rgb, vec3(1.0, 0.3, 0.5), m * 0.9), c.a);
            """
        })
        register(video("lens_flare", "Reflet d'objectif", C.LIGHT, listOf(p("x", "Position X", 0.0, 1.0, 0.25), p("y", "Position Y", 0.0, 1.0, 0.25), p("intensity", "Intensité", 0.0, 1.5, 0.8)), animated = true) {
            """
            vec4 c = tex(uv);
            vec2 ar = vec2(uResolution.x / uResolution.y, 1.0);
            vec2 sp = vec2(uP[0] + sin(uTime * 0.4) * 0.03, 1.0 - uP[1]);
            vec2 d = (uv - sp) * ar;
            float core = 0.02 / (length(d) + 0.02);
            vec3 col = vec3(1.0, 0.85, 0.6) * core * 0.6;
            vec2 axis = (vec2(0.5) - sp);
            for (int i = 1; i < 5; i++) {
              vec2 gp = sp + axis * float(i) * 0.45;
              float g = smoothstep(0.05, 0.0, length((uv - gp) * ar) - 0.01 * float(i));
              col += hueColor(float(i) * 70.0) * g * 0.25;
            }
            col += vec3(1.0, 0.8, 0.5) * smoothstep(0.004, 0.0, abs(d.y)) * 0.3 * smoothstep(0.6, 0.0, abs(d.x));
            return vec4(clamp(c.rgb + col * uP[2], 0.0, 1.0), c.a);
            """
        })
        register(video("datamosh", "Datamosh", C.STYLIZE, listOf(p("amount", "Intensité", 0.0, 1.0, 0.5), p("block", "Blocs", 8.0, 80.0, 28.0)), animated = true) {
            """
            float t = floor(uTime * 8.0);
            vec2 b = floor(uv * uP[1]) / uP[1];
            float r = hash(b + t);
            vec2 off = vec2(hash(b + t + 1.0) - 0.5, hash(b + t + 2.0) - 0.5) * 0.08 * uP[0] * step(0.6, r);
            vec4 c = tex(uv + off);
            vec4 s = tex(b + off);
            return mix(c, s, step(0.85, r) * uP[0]);
            """
        })
        register(video("cracked", "Écran fissuré", C.DISTORT, listOf(p("x", "Impact X", 0.0, 1.0, 0.6), p("y", "Impact Y", 0.0, 1.0, 0.4), p("amount", "Fissures", 0.0, 1.0, 0.7))) {
            """
            vec2 ar = vec2(uResolution.x / uResolution.y, 1.0);
            vec2 d = (uv - vec2(uP[0], 1.0 - uP[1])) * ar;
            float a = atan(d.y, d.x);
            float r = length(d);
            float sector = floor(a * 3.0);
            float jag = hash(vec2(sector, 1.0)) * 0.6;
            float line = smoothstep(0.012, 0.0, abs(fract(a * 3.0 + jag * r) - 0.5) * r * 2.0) * step(r, 0.9 * uP[2] + 0.1);
            float ring = smoothstep(0.004, 0.0, abs(fract(r * 6.0 + hash(vec2(sector, 3.0))) - 0.5) * 0.12) * step(r, 0.5 * uP[2]);
            vec2 off = normalize(d + 0.0001) * 0.004 * hash(vec2(sector, 5.0)) * uP[2];
            vec4 c = tex(uv + off);
            float crack = clamp(line + ring * 0.6, 0.0, 1.0) * uP[2];
            return vec4(mix(c.rgb, vec3(0.95), crack * 0.7), c.a);
            """
        })
        register(video("cctv", "Caméra de surveillance", C.RETRO, listOf(p("amount", "Intensité", 0.0, 1.0, 0.8)), animated = true) {
            """
            vec4 c = tex(uv);
            float l = luma(c.rgb);
            vec3 g = vec3(l * 0.85, l, l * 0.85);
            g *= 0.9 + 0.1 * sin(uv.y * uResolution.y * 1.5);
            g += (hash(uv * uResolution + uTime) - 0.5) * 0.12;
            float vig = smoothstep(0.85, 0.3, length(uv - 0.5));
            float rec = step(0.92, uv.x) * step(uv.x, 0.95) * step(0.9, uv.y) * step(uv.y, 0.94) * step(0.5, fract(uTime));
            vec3 col = mix(c.rgb, g * vig, uP[0]);
            return vec4(mix(col, vec3(1.0, 0.1, 0.1), rec), c.a);
            """
        })
        register(video("oil_paint", "Peinture à l'huile", C.STYLIZE, listOf(p("radius", "Coup de pinceau", 1.0, 6.0, 3.0, "px"))) {
            """
            vec2 px = uP[0] / uResolution;
            vec3 m0 = vec3(0.0); vec3 m1 = vec3(0.0); vec3 s0 = vec3(0.0); vec3 s1 = vec3(0.0);
            for (int x = -2; x <= 0; x++) { for (int y = -2; y <= 0; y++) { vec3 c = tex(uv + vec2(float(x), float(y)) * px).rgb; m0 += c; s0 += c * c; } }
            for (int x = 0; x <= 2; x++) { for (int y = 0; y <= 2; y++) { vec3 c = tex(uv + vec2(float(x), float(y)) * px).rgb; m1 += c; s1 += c * c; } }
            m0 /= 9.0; m1 /= 9.0;
            float v0 = dot(abs(s0 / 9.0 - m0 * m0), vec3(1.0));
            float v1 = dot(abs(s1 / 9.0 - m1 * m1), vec3(1.0));
            return vec4(v0 < v1 ? m0 : m1, tex(uv).a);
            """
        })
        register(video("watercolor", "Aquarelle", C.STYLIZE, listOf(p("amount", "Intensité", 0.0, 1.0, 0.8))) {
            """
            vec2 w = vec2(noise(uv * 30.0), noise(uv * 30.0 + 7.0)) - 0.5;
            vec4 c = tex(uv + w * 0.006);
            vec3 q = floor(c.rgb * 6.0 + 0.5) / 6.0;
            float paper = 0.92 + 0.08 * noise(uv * uResolution * 0.05);
            vec3 col = mix(q, vec3(1.0), 0.18) * paper;
            return vec4(mix(c.rgb, col, uP[0]), c.a);
            """
        })
        register(video("hex_mosaic", "Mosaïque hexagonale", C.STYLIZE, listOf(p("size", "Taille", 4.0, 60.0, 18.0, "px"))) {
            """
            vec2 p = uv * uResolution / uP[0];
            vec2 r = vec2(1.0, 1.732);
            vec2 h = r * 0.5;
            vec2 a = mod(p, r) - h;
            vec2 b = mod(p - h, r) - h;
            vec2 g = dot(a, a) < dot(b, b) ? a : b;
            vec2 center = (p - g) * uP[0] / uResolution;
            return tex(center);
            """
        })
        register(video("shockwave", "Onde de choc", C.DISTORT, listOf(p("x", "Centre X", 0.0, 1.0, 0.5), p("y", "Centre Y", 0.0, 1.0, 0.5), p("speed", "Vitesse", 0.2, 3.0, 1.0), p("amount", "Force", 0.0, 0.1, 0.03)), animated = true) {
            """
            vec2 ar = vec2(uResolution.x / uResolution.y, 1.0);
            vec2 d = (uv - vec2(uP[0], 1.0 - uP[1])) * ar;
            float r = fract(uTime * uP[2] * 0.5) * 1.2;
            float dist = length(d);
            float w = smoothstep(0.08, 0.0, abs(dist - r)) * (1.0 - r / 1.2);
            vec2 off = normalize(d + 0.0001) * w * uP[3];
            vec4 c = tex(uv - off / ar);
            return vec4(c.rgb + w * 0.15, c.a);
            """
        })
        register(video("quad_split", "Écran x4", C.STYLIZE, listOf(toggle("tint", "Couleurs différentes", true))) {
            """
            vec2 q = fract(uv * 2.0);
            vec4 c = tex(q);
            float idx = floor(uv.x * 2.0) + floor(uv.y * 2.0) * 2.0;
            vec3 tint = idx < 0.5 ? vec3(1.0, 0.75, 0.75) : idx < 1.5 ? vec3(0.75, 1.0, 0.8) : idx < 2.5 ? vec3(0.75, 0.85, 1.0) : vec3(1.0, 1.0, 0.7);
            return vec4(c.rgb * mix(vec3(1.0), tint, uP[0]), c.a);
            """
        })
        register(video("tunnel_zoom", "Tunnel hypnotique", C.MOTION, listOf(p("speed", "Vitesse", 0.1, 3.0, 0.8), p("amount", "Intensité", 0.0, 1.0, 0.6)), animated = true) {
            """
            vec2 d = uv - 0.5;
            float r = length(d);
            float a = atan(d.y, d.x);
            vec2 st = vec2(a / (2.0 * PI) + 0.5, fract(0.25 / (r + 0.05) + uTime * uP[0] * 0.3));
            vec4 t = tex(st);
            vec4 c = tex(uv);
            return mix(c, t, uP[1] * smoothstep(0.05, 0.5, r));
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
        register(video("_tr_heart", "Cœur", C.INTERNAL, listOf(p("softness", "Douceur", 0.0, 0.3, 0.03), p("invert", "Inverser", 0.0, 1.0, 0.0))) {
            """
            vec4 c = tex(uv);
            vec2 q = (uv - vec2(0.5, 0.45)) * vec2(uResolution.x / uResolution.y, 1.0) / max(uProgress * 1.6, 0.0001);
            float h = q.x * q.x + pow(q.y - pow(abs(q.x), 0.66) * 0.6, 2.0) - 0.16;
            float m = 1.0 - smoothstep(-uP[0], uP[0], h);
            if (uProgress >= 0.999) m = 1.0;
            if (uP[1] > 0.5) m = 1.0 - m;
            return vec4(c.rgb, c.a * m);
            """
        })
        register(video("_tr_star", "Étoile", C.INTERNAL, listOf(p("softness", "Douceur", 0.0, 0.3, 0.02), p("invert", "Inverser", 0.0, 1.0, 0.0))) {
            """
            vec4 c = tex(uv);
            vec2 d = (uv - 0.5) * vec2(uResolution.x / uResolution.y, 1.0);
            float a = atan(d.y, d.x);
            float k = 0.65 + 0.35 * cos(a * 5.0);
            float r = length(d) / k;
            float maxR = 3.2;
            float m = 1.0 - smoothstep(uProgress * maxR - uP[0], uProgress * maxR + uP[0], r);
            if (uP[1] > 0.5) m = 1.0 - m;
            return vec4(c.rgb, c.a * m);
            """
        })
        register(video("_tr_clock", "Horloge", C.INTERNAL, listOf(p("softness", "Douceur", 0.0, 0.3, 0.01), p("invert", "Inverser", 0.0, 1.0, 0.0))) {
            """
            vec4 c = tex(uv);
            vec2 d = uv - 0.5;
            float a = fract(atan(d.x, d.y) / (2.0 * PI) + 1.0);
            float m = 1.0 - smoothstep(uProgress - uP[0], uProgress + uP[0], a);
            if (uProgress >= 0.999) m = 1.0;
            if (uP[1] > 0.5) m = 1.0 - m;
            return vec4(c.rgb, c.a * m);
            """
        })
        register(video("_tr_diamond", "Losange", C.INTERNAL, listOf(p("softness", "Douceur", 0.0, 0.3, 0.03), p("invert", "Inverser", 0.0, 1.0, 0.0))) {
            """
            vec4 c = tex(uv);
            vec2 d = abs(uv - 0.5);
            float r = d.x + d.y;
            float m = 1.0 - smoothstep(uProgress * 1.05 - uP[0], uProgress * 1.05 + uP[0], r);
            if (uP[1] > 0.5) m = 1.0 - m;
            return vec4(c.rgb, c.a * m);
            """
        })
        register(video("_tr_blinds", "Stores", C.INTERNAL, listOf(p("count", "Lames", 3.0, 30.0, 10.0), p("invert", "Inverser", 0.0, 1.0, 0.0))) {
            """
            vec4 c = tex(uv);
            float f = fract(uv.y * uP[0]);
            float m = step(f, uProgress);
            if (uP[1] > 0.5) m = 1.0 - m;
            return vec4(c.rgb, c.a * m);
            """
        })
        register(video("_tr_doors", "Portes", C.INTERNAL, listOf(p("softness", "Douceur", 0.0, 0.2, 0.01), p("invert", "Inverser", 0.0, 1.0, 0.0))) {
            """
            vec4 c = tex(uv);
            float x = abs(uv.x - 0.5) * 2.0;
            float m = 1.0 - smoothstep(uProgress - uP[0], uProgress + uP[0], x);
            if (uProgress >= 0.999) m = 1.0;
            if (uP[1] > 0.5) m = 1.0 - m;
            return vec4(c.rgb, c.a * m);
            """
        })
        register(video("_tr_checker", "Damier", C.INTERNAL, listOf(p("count", "Cases", 4.0, 24.0, 10.0), p("invert", "Inverser", 0.0, 1.0, 0.0))) {
            """
            vec4 c = tex(uv);
            vec2 cell = floor(uv * vec2(uP[0] * uResolution.x / uResolution.y, uP[0]));
            float order = hash(cell) * 0.7 + mod(cell.x + cell.y, 2.0) * 0.3;
            float m = step(order, uProgress * 1.01);
            if (uP[1] > 0.5) m = 1.0 - m;
            return vec4(c.rgb, c.a * m);
            """
        })
        register(video("_tr_wheel", "Roue", C.INTERNAL, listOf(p("count", "Rayons", 2.0, 16.0, 6.0), p("invert", "Inverser", 0.0, 1.0, 0.0))) {
            """
            vec4 c = tex(uv);
            vec2 d = uv - 0.5;
            float a = fract(atan(d.y, d.x) / (2.0 * PI) * uP[0]);
            float m = step(a, uProgress);
            if (uP[1] > 0.5) m = 1.0 - m;
            return vec4(c.rgb, c.a * m);
            """
        })
        register(video("_tr_pixel", "Pixelisation", C.INTERNAL, listOf(p("size", "Taille max", 8.0, 120.0, 60.0))) {
            """
            float b = max(1.0, mix(uP[0], 1.0, uProgress));
            vec2 px = b / uResolution;
            return tex((floor(uv / px) + 0.5) * px);
            """
        })
        register(video("_tr_ripple", "Ondulation", C.INTERNAL, listOf(p("amount", "Force", 0.0, 0.1, 0.04))) {
            """
            vec2 d = uv - 0.5;
            float r = length(d);
            float k = (1.0 - uProgress);
            vec2 off = normalize(d + 0.0001) * sin(r * 40.0 - uProgress * 20.0) * uP[0] * k;
            return tex(uv + off);
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
        // voice presets (pitch handled by the engine + a DSP colour), "amount" scales the colour
        val amount = listOf(p("amount", "Intensité", 0.0, 1.0, 1.0))
        register(audio("v_helium", "Hélium", C.VOICE, amount, desc = "Voix très aiguë (+12 demi-tons)"))
        register(audio("v_chipmunk", "Écureuil", C.VOICE, amount, desc = "Voix de dessin animé (+8)"))
        register(audio("v_child", "Enfant", C.VOICE, amount, desc = "Voix plus jeune (+5)"))
        register(audio("v_deep", "Voix grave", C.VOICE, amount, desc = "Voix profonde de bande-annonce (-4)"))
        register(audio("v_giant", "Géant", C.VOICE, amount, desc = "Énorme et résonnante (-10)"))
        register(audio("v_monster", "Monstre", C.VOICE, amount, desc = "Grave et saturée (-7)"))
        register(audio("v_alien", "Alien", C.VOICE, amount, desc = "Modulée et métallique (+4)"))
        register(audio("v_dark_mask", "Masque sombre", C.VOICE, amount, desc = "Voix de méchant mécanique (-5)"))
        register(audio("v_underwater", "Sous l'eau", C.VOICE, amount, desc = "Étouffée, comme sous l'eau"))
        register(audio("v_ghost", "Fantôme", C.VOICE, amount, desc = "Écho lointain et spectral"))
        register(audio("v_stadium", "Stade", C.VOICE, amount, desc = "Grande salle / annonce de stade"))
        register(audio("v_walkie", "Talkie-walkie", C.VOICE, amount, desc = "Radio portative grésillante"))
    }
}
