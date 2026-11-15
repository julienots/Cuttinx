package com.superessence.cuttinx.core.effects

import com.superessence.cuttinx.core.effects.EffectCategory as C

private fun p(key: String, label: String, min: Double, max: Double, def: Double, unit: String = "") =
    ParamDef(key, label, min, max, def, unit = unit)
private fun hue(key: String, label: String, def: Double) = ParamDef(key, label, 0.0, 360.0, def, ParamType.HUE, unit = "°")
private fun choice(key: String, label: String, vararg options: String) =
    ParamDef(key, label, 0.0, (options.size - 1).toDouble(), 0.0, ParamType.CHOICE, options.toList())

private fun fx(id: String, name: String, cat: C, params: List<ParamDef>, animated: Boolean = false, desc: String = "", glsl: () -> String) =
    EffectDefinition(id, name, cat, EffectKind.VIDEO, params, glsl(), animated, desc)

/**
 * « Ultra technologies » (CUTTINX 3.4) : looks de montage haut de gamme — rendu volumétrique,
 * interfaces HUD, visions « IA » et effets en 3 dimensions. Shaders originaux écrits pour CUTTINX,
 * en GLSL ES 1.00 compatible SkSL (boucles à bornes constantes, aucune fonction locale), donc
 * identiques sur Android et sur PC.
 */
internal object UltraEffects {
    fun all(): List<EffectDefinition> = ultra + hud + vision + dimension + transitions

    // ------------------------------------------------------------------ ULTRA TECHNOLOGIES
    private val ultra = listOf(
        fx("ultra_hyperzoom", "Hyper-zoom neuronal", C.ULTRA, listOf(p("amount", "Intensité", 0.0, 1.0, 0.6), p("cx", "Centre X", 0.0, 1.0, 0.5), p("cy", "Centre Y", 0.0, 1.0, 0.5), p("speed", "Pulsation", 0.0, 3.0, 1.0)),
            animated = true, desc = "Zoom radial à 16 échantillons avec dispersion des couleurs, qui pulse comme une aspiration") {
            """
            vec2 ctr = vec2(uP[1], 1.0 - uP[2]);
            vec2 d = uv - ctr;
            float k = uP[0] * (0.75 + 0.25 * sin(uTime * uP[3] * 3.0));
            vec3 acc = vec3(0.0);
            for (int i = 0; i < 16; i++) {
                float t = float(i) / 15.0;
                float s = 1.0 - t * 0.18 * k;
                acc.r += tex(ctr + d * (s - 0.012 * k)).r;
                acc.g += tex(ctr + d * s).g;
                acc.b += tex(ctr + d * (s + 0.012 * k)).b;
            }
            vec4 c = tex(uv);
            return vec4(mix(c.rgb, acc / 16.0, clamp(k * 1.3, 0.0, 1.0)), c.a);
            """
        },
        fx("ultra_volumetric", "Rayons volumétriques", C.ULTRA, listOf(p("x", "Source X", 0.0, 1.0, 0.5), p("y", "Source Y", 0.0, 1.0, 0.2), p("amount", "Intensité", 0.0, 2.0, 0.9), p("decay", "Portée", 0.5, 1.0, 0.94), p("threshold", "Seuil de lumière", 0.0, 1.0, 0.75)),
            desc = "Lumière volumétrique : les zones claires projettent de vrais faisceaux à travers l'image") {
            """
            vec2 src = vec2(uP[0], 1.0 - uP[1]);
            vec2 step1 = (uv - src) / 24.0;
            vec2 q = uv;
            float w = 1.0;
            vec3 rays = vec3(0.0);
            for (int i = 0; i < 24; i++) {
                q -= step1;
                vec3 s = tex(q).rgb;
                rays += s * smoothstep(uP[4], 1.0, luma(s)) * w;
                w *= uP[3];
            }
            vec4 c = tex(uv);
            return vec4(clamp(c.rgb + rays / 24.0 * uP[2] * 1.2, 0.0, 1.0), c.a);
            """
        },
        fx("ultra_prism", "Dispersion spectrale", C.ULTRA, listOf(p("amount", "Dispersion", 0.0, 1.0, 0.5), p("speed", "Mouvement", 0.0, 3.0, 0.5)),
            animated = true, desc = "Décompose la lumière en 7 longueurs d'onde, comme à travers un vrai prisme") {
            """
            vec2 d = uv - 0.5;
            float k = uP[0] * 0.03 * (1.0 + 0.3 * sin(uTime * uP[1]));
            vec3 acc = vec3(0.0);
            vec3 wsum = vec3(0.0);
            for (int i = 0; i < 7; i++) {
                float t = float(i) / 6.0;
                vec3 w = clamp(vec3(1.5 - abs(t - 0.0) * 3.0, 1.5 - abs(t - 0.5) * 3.0, 1.5 - abs(t - 1.0) * 3.0), 0.0, 1.0);
                acc += tex(uv + d * (t - 0.5) * k * 4.0).rgb * w;
                wsum += w;
            }
            vec4 c = tex(uv);
            return vec4(clamp(acc / wsum, 0.0, 1.0), c.a);
            """
        },
        fx("ultra_depth_focus", "Mise au point IA", C.ULTRA, listOf(p("focus", "Plan net (hauteur)", 0.0, 1.0, 0.5), p("range", "Profondeur de champ", 0.02, 0.6, 0.18), p("blur", "Bokeh", 0.0, 20.0, 9.0, "px"), p("subject", "Priorité au sujet", 0.0, 1.0, 0.5)),
            desc = "Profondeur estimée (position + luminosité) : le sujet reste net, l'arrière-plan part en bokeh") {
            """
            vec4 c = tex(uv);
            float depth = mix(1.0 - uv.y, luma(c.rgb), uP[3] * 0.35);
            float coc = smoothstep(uP[1], uP[1] * 2.5 + 0.05, abs(depth - uP[0]));
            vec2 px = uP[2] * coc / uResolution;
            vec3 acc = c.rgb;
            float n = 1.0;
            for (int i = 0; i < 12; i++) {
                float a = float(i) * 2.39996;
                float r = sqrt(float(i) + 0.5) / 3.5;
                vec3 s = tex(uv + vec2(cos(a), sin(a)) * r * px).rgb;
                float w = 1.0 + luma(s) * 2.0;
                acc += s * w; n += w;
            }
            return vec4(acc / n, c.a);
            """
        },
        fx("ultra_light_trails", "Traînées lumineuses", C.ULTRA, listOf(hue("angle", "Direction", 0.0), p("length", "Longueur", 0.0, 0.3, 0.12), p("threshold", "Seuil", 0.0, 1.0, 0.6), p("amount", "Intensité", 0.0, 2.0, 1.0)),
            desc = "Seules les lumières laissent une longue traînée, façon pose longue de nuit") {
            """
            vec2 dir = vec2(cos(radians(uP[0])), sin(radians(uP[0]))) * uP[1];
            vec3 acc = vec3(0.0);
            for (int i = 0; i < 20; i++) {
                float t = float(i) / 19.0;
                vec3 s = tex(uv - dir * t).rgb;
                acc += s * smoothstep(uP[2], 1.0, luma(s)) * (1.0 - t);
            }
            vec4 c = tex(uv);
            return vec4(clamp(c.rgb + acc / 10.0 * uP[3], 0.0, 1.0), c.a);
            """
        },
        fx("ultra_quantum", "Distorsion quantique", C.ULTRA, listOf(p("amount", "Intensité", 0.0, 1.0, 0.5), p("blocks", "Taille des quanta", 4.0, 60.0, 18.0), p("speed", "Vitesse", 0.0, 3.0, 1.0)),
            animated = true, desc = "L'image se téléporte par blocs instables, avec superpositions de couleurs") {
            """
            float t = floor(uTime * (4.0 + uP[2] * 8.0));
            vec2 cell = floor(uv * vec2(uP[1] * uResolution.x / uResolution.y, uP[1]));
            float h = hash(cell + t);
            float on = step(1.0 - uP[0] * 0.5, h);
            vec2 jump = (vec2(hash(cell * 1.3 + t), hash(cell * 2.1 - t)) - 0.5) * 0.15 * on;
            vec4 a = tex(uv + jump);
            vec4 b = tex(uv - jump * 0.5);
            vec3 col = mix(a.rgb, vec3(a.r, b.g, a.b), on);
            col = mix(col, col.brg, on * step(0.8, hash(cell + t * 0.7)));
            col += vec3(0.1, 0.6, 1.0) * on * 0.15 * uP[0];
            return vec4(clamp(col, 0.0, 1.0), a.a);
            """
        },
        fx("ultra_hdr", "HDR ultra", C.ULTRA, listOf(p("amount", "Détail local", 0.0, 2.0, 0.5), p("radius", "Rayon", 2.0, 30.0, 12.0, "px"), p("saturation", "Couleur", 0.0, 1.0, 0.3), p("glow", "Éclat", 0.0, 1.0, 0.2)),
            desc = "Tone mapping local : ombres débouchées, ciel tenu, micro-contraste façon capteur haut de gamme") {
            """
            vec4 c = tex(uv);
            vec2 px = uP[1] / uResolution;
            vec3 blur = vec3(0.0);
            for (int i = 0; i < 12; i++) {
                float a = float(i) * 0.5236;
                blur += tex(uv + vec2(cos(a), sin(a)) * px).rgb + tex(uv + vec2(cos(a), sin(a)) * px * 0.5).rgb;
            }
            blur /= 24.0;
            float l = luma(c.rgb);
            float lb = luma(blur);
            vec3 col = c.rgb + (c.rgb - blur) * uP[0];
            col *= mix(1.0, (0.5 + 0.5 * (1.0 - lb)) * 1.4, 0.5 * uP[0]);
            col = mix(vec3(luma(col)), col, 1.0 + uP[2]);
            col += blur * smoothstep(0.6, 1.0, l) * uP[3];
            col = col / (1.0 + col * 0.15);
            return vec4(clamp(col * 1.1, 0.0, 1.0), c.a);
            """
        },
        fx("ultra_super_res", "Super-résolution", C.ULTRA, listOf(p("amount", "Netteté", 0.0, 3.0, 1.2), p("protect", "Protection du bruit", 0.0, 0.3, 0.06)),
            desc = "Renforce les contours sans grossir le bruit : l'image paraît filmée en plus haute définition") {
            """
            vec4 c = tex(uv);
            vec2 px = 1.0 / uResolution;
            vec3 n = tex(uv + vec2(0.0, px.y)).rgb;
            vec3 s = tex(uv - vec2(0.0, px.y)).rgb;
            vec3 e = tex(uv + vec2(px.x, 0.0)).rgb;
            vec3 w = tex(uv - vec2(px.x, 0.0)).rgb;
            vec3 avg = (n + s + e + w) * 0.25;
            vec3 detail = c.rgb - avg;
            float edge = smoothstep(uP[1], uP[1] * 3.0 + 0.01, length(detail));
            vec3 mn = min(min(n, s), min(e, w));
            vec3 mx = max(max(n, s), max(e, w));
            vec3 col = clamp(c.rgb + detail * uP[0] * 2.0 * edge, mn - 0.05, mx + 0.05);
            return vec4(clamp(col, 0.0, 1.0), c.a);
            """
        },
        fx("ultra_time_slice", "Tranches temporelles", C.ULTRA, listOf(p("slices", "Tranches", 4.0, 40.0, 12.0), p("amount", "Décalage", 0.0, 0.2, 0.05), p("speed", "Vitesse", 0.0, 3.0, 1.0), choice("dir", "Sens", "Vertical", "Horizontal")),
            animated = true, desc = "L'image se découpe en lamelles qui ondulent chacune à leur propre tempo (slit-scan)") {
            """
            float axis = uP[3] > 0.5 ? uv.y : uv.x;
            float id = floor(axis * uP[0]);
            float ph = hash(vec2(id, 3.7)) * 6.2831;
            float off = sin(uTime * uP[2] * 2.0 + ph + id * 0.5) * uP[1];
            vec2 q = uP[3] > 0.5 ? vec2(uv.x + off, uv.y) : vec2(uv.x, uv.y + off);
            vec4 c = tex(q);
            float edge = abs(fract(axis * uP[0]) - 0.5);
            return vec4(c.rgb * (0.85 + 0.15 * smoothstep(0.5, 0.42, edge)), c.a);
            """
        },
        fx("ultra_bloom", "Bloom cinéma", C.ULTRA, listOf(p("threshold", "Seuil", 0.0, 1.0, 0.75), p("amount", "Intensité", 0.0, 2.0, 0.6), p("radius", "Diffusion", 2.0, 40.0, 16.0, "px"), hue("tint", "Teinte", 30.0), p("tintAmt", "Teinte (dose)", 0.0, 1.0, 0.2)),
            desc = "Halo lumineux physique autour des hautes lumières, comme sur une optique de cinéma") {
            """
            vec4 c = tex(uv);
            vec2 px = uP[2] / uResolution;
            vec3 acc = vec3(0.0);
            for (int i = 0; i < 16; i++) {
                float a = float(i) * 2.39996;
                float r = sqrt(float(i) + 0.5) / 4.0;
                vec3 s = tex(uv + vec2(cos(a), sin(a)) * r * px).rgb;
                acc += s * smoothstep(uP[0], 1.0, luma(s));
            }
            acc /= 16.0;
            acc = mix(acc, acc * hueColor(uP[3]) * 1.5, uP[4]);
            return vec4(clamp(c.rgb + acc * uP[1] * 2.0, 0.0, 1.0), c.a);
            """
        },
    )

    // ------------------------------------------------------------------ INTERFACE HUD
    private val hud = listOf(
        fx("hud_targeting", "Viseur de ciblage", C.HUD, listOf(p("x", "Cible X", 0.0, 1.0, 0.5), p("y", "Cible Y", 0.0, 1.0, 0.5), p("size", "Taille", 0.05, 0.5, 0.18), hue("hue", "Couleur", 140.0), p("amount", "Opacité", 0.0, 1.0, 0.9)),
            animated = true, desc = "Réticule animé qui verrouille une cible : anneaux tournants, crochets et graduations") {
            """
            vec4 c = tex(uv);
            float ar = uResolution.x / uResolution.y;
            vec2 d = (uv - vec2(uP[0], 1.0 - uP[1])) * vec2(ar, 1.0);
            float r = length(d);
            float a = atan(d.y, d.x);
            float sz = uP[2];
            float lw = 1.5 / uResolution.y;
            float ring1 = smoothstep(lw * 2.0, 0.0, abs(r - sz)) * step(0.5, fract((a + uTime * 1.2) * 3.0 / 6.2831 * 4.0));
            float ring2 = smoothstep(lw * 2.0, 0.0, abs(r - sz * 0.7)) * step(0.3, fract((a - uTime * 2.0) * 6.0 / 6.2831));
            float ticks = smoothstep(lw * 2.0, 0.0, abs(r - sz * 1.15)) * step(0.85, fract(a * 36.0 / 6.2831));
            vec2 ad = abs(d);
            float cross1 = (smoothstep(lw * 2.0, 0.0, ad.x) * step(sz * 0.15, ad.y) * step(ad.y, sz * 0.45)) + (smoothstep(lw * 2.0, 0.0, ad.y) * step(sz * 0.15, ad.x) * step(ad.x, sz * 0.45));
            float blink = 0.5 + 0.5 * sin(uTime * 6.0);
            float sq = sz * 1.35;
            float brk = smoothstep(lw * 3.0, 0.0, abs(max(ad.x, ad.y) - sq)) * step(sq * 0.6, min(ad.x, ad.y));
            float m = clamp(ring1 + ring2 + ticks + cross1 + brk * (0.6 + 0.4 * blink), 0.0, 1.0);
            vec3 col = hueColor(uP[3]);
            return vec4(mix(c.rgb, col, m * uP[4]), c.a);
            """
        },
        fx("hud_lidar", "Scan LiDAR", C.HUD, listOf(p("density", "Densité des points", 40.0, 240.0, 120.0), p("speed", "Vitesse du scan", 0.0, 2.0, 0.5), p("amount", "Intensité", 0.0, 1.0, 0.85)),
            animated = true, desc = "Un faisceau balaie la scène et la reconstruit en nuage de points coloré par la profondeur") {
            """
            vec4 c = tex(uv);
            vec2 g = vec2(uP[0] * uResolution.x / uResolution.y, uP[0]);
            vec2 id = floor(uv * g);
            vec2 f = fract(uv * g) - 0.5;
            vec4 s = tex((id + 0.5) / g);
            float depth = clamp(luma(s.rgb) * 0.7 + (1.0 - uv.y) * 0.3, 0.0, 1.0);
            float dotm = smoothstep(0.35, 0.15, length(f)) * (0.3 + depth);
            vec3 pc = 0.5 + 0.5 * cos(6.2831 * (vec3(depth * 0.8) + vec3(0.0, 0.33, 0.67)));
            float scan = fract(uTime * uP[1] * 0.5);
            float dist = uv.y - (1.0 - scan);
            float beam = smoothstep(0.012, 0.0, abs(dist));
            float revealed = step(0.0, dist);
            vec3 cloud = vec3(0.0, 0.02, 0.05) + pc * dotm;
            vec3 col = mix(c.rgb, cloud, revealed * uP[2]);
            col += vec3(0.4, 1.0, 1.0) * beam * uP[2];
            return vec4(clamp(col, 0.0, 1.0), c.a);
            """
        },
        fx("hud_hologram", "Projection holographique", C.HUD, listOf(hue("hue", "Couleur", 195.0), p("amount", "Intensité", 0.0, 1.0, 0.9), p("lines", "Lignes", 80.0, 600.0, 260.0), p("flicker", "Scintillement", 0.0, 1.0, 0.4)),
            animated = true, desc = "Le sujet devient une projection lumineuse translucide, avec lignes et instabilités") {
            """
            float t = uTime;
            float jitter = (hash(vec2(floor(t * 20.0), floor(uv.y * 40.0))) - 0.5) * 0.01 * uP[3] * step(0.9, hash(vec2(floor(t * 7.0), 1.0)));
            vec2 q = vec2(uv.x + jitter, uv.y);
            vec4 c = tex(q);
            float l = luma(c.rgb);
            vec2 px = 1.0 / uResolution;
            float edge = length(vec2(luma(tex(q + vec2(px.x, 0.0)).rgb) - luma(tex(q - vec2(px.x, 0.0)).rgb), luma(tex(q + vec2(0.0, px.y)).rgb) - luma(tex(q - vec2(0.0, px.y)).rgb))) * 4.0;
            float lines = 0.65 + 0.35 * sin(uv.y * uP[2] * 3.1416 - t * 8.0);
            float band = smoothstep(0.0, 0.03, abs(fract(uv.y - t * 0.15) - 0.5)) * 0.4 + 0.6;
            float fl = 1.0 - uP[3] * 0.3 * hash(vec2(floor(t * 15.0), 2.0));
            vec3 hc = hueColor(uP[0]);
            vec3 holo = hc * (l * 1.2 + edge) * lines * band * fl + hc * 0.08;
            return vec4(clamp(mix(c.rgb, holo, uP[1]), 0.0, 1.0), c.a);
            """
        },
        fx("hud_radar", "Radar", C.HUD, listOf(p("x", "Position X", 0.0, 1.0, 0.82), p("y", "Position Y", 0.0, 1.0, 0.8), p("size", "Taille", 0.05, 0.5, 0.14), p("speed", "Vitesse", 0.0, 3.0, 1.0), hue("hue", "Couleur", 130.0)),
            animated = true, desc = "Radar à balayage avec cibles clignotantes, incrusté où tu veux dans l'image") {
            """
            vec4 c = tex(uv);
            float ar = uResolution.x / uResolution.y;
            vec2 d = (uv - vec2(uP[0], 1.0 - uP[1])) * vec2(ar, 1.0);
            float r = length(d) / uP[2];
            if (r > 1.05) return c;
            float a = atan(d.y, d.x);
            float sweep = fract((a / 6.2831) - uTime * uP[3] * 0.25);
            float trail = pow(1.0 - sweep, 6.0) * step(r, 1.0);
            float rings = smoothstep(0.03, 0.0, abs(fract(r * 3.0) - 0.0)) * step(r, 1.0) + smoothstep(0.03, 0.0, abs(r - 1.0));
            float axes = (smoothstep(0.012, 0.0, abs(d.x / uP[2])) + smoothstep(0.012, 0.0, abs(d.y / uP[2]))) * step(r, 1.0);
            vec2 cell = floor(d / uP[2] * 5.0);
            float blip = step(0.93, hash(cell)) * smoothstep(0.35, 0.0, length(fract(d / uP[2] * 5.0) - 0.5)) * pow(1.0 - sweep, 2.0) * step(r, 0.95);
            vec3 hc = hueColor(uP[4]);
            vec3 col = mix(c.rgb, c.rgb * 0.35 + vec3(0.0, 0.05, 0.02), step(r, 1.0) * 0.7);
            col += hc * (trail * 0.6 + rings * 0.7 + axes * 0.3 + blip * 1.5);
            return vec4(clamp(col, 0.0, 1.0), c.a);
            """
        },
        fx("hud_neon_grid", "Grille néon 3D", C.HUD, listOf(p("horizon", "Horizon", 0.2, 0.8, 0.55), hue("hue", "Couleur", 300.0), p("speed", "Vitesse", 0.0, 3.0, 1.0), p("amount", "Intensité", 0.0, 1.0, 0.8)),
            animated = true, desc = "Sol quadrillé en perspective qui défile à l'infini, ambiance synthwave futuriste") {
            """
            vec4 c = tex(uv);
            float hz = 1.0 - uP[0];
            if (uv.y > hz) return c;
            float y = (hz - uv.y) / hz;
            float z = 1.0 / max(y, 0.001);
            float x = (uv.x - 0.5) * z * 2.0;
            float gz = abs(fract(z * 0.5 - uTime * uP[2]) - 0.5);
            float gx = abs(fract(x) - 0.5);
            float grid = max(smoothstep(0.03 * z, 0.0, gz), smoothstep(0.02 * z, 0.0, gx));
            float fade = smoothstep(0.0, 0.3, y);
            vec3 hc = hueColor(uP[1]);
            float glow = exp(-y * 8.0) * 0.6;
            vec3 col = mix(c.rgb, c.rgb * 0.3, uP[3] * 0.6) + hc * (grid * fade + glow) * uP[3];
            return vec4(clamp(col, 0.0, 1.0), c.a);
            """
        },
        fx("hud_data_stream", "Flux de données", C.HUD, listOf(hue("hue", "Couleur", 180.0), p("amount", "Intensité", 0.0, 1.0, 0.8), p("width", "Largeur des colonnes", 0.05, 0.35, 0.16), p("speed", "Vitesse", 0.0, 3.0, 1.0)),
            animated = true, desc = "Colonnes de données, jauges et graphes qui défilent sur les bords de l'écran") {
            """
            vec4 c = tex(uv);
            float side = min(uv.x, 1.0 - uv.x);
            if (side > uP[2]) return c;
            float t = uTime * uP[3];
            vec2 g = vec2(uResolution.x / 10.0, uResolution.y / 14.0);
            vec2 id = floor(uv * g + vec2(0.0, t * 6.0 * (0.5 + hash(vec2(floor(uv.x * g.x), 0.0)))));
            float bitv = step(0.55, hash(id + floor(t * 3.0)));
            vec2 f = fract(uv * g + vec2(0.0, t * 6.0 * (0.5 + hash(vec2(floor(uv.x * g.x), 0.0)))));
            float glyph = bitv * step(0.2, f.x) * step(f.x, 0.8) * step(0.15, f.y) * step(f.y, 0.85);
            float bars = step(fract(uv.y * 6.0), 0.12) * step(side / uP[2], 0.5 + 0.5 * sin(t * 2.0 + floor(uv.y * 6.0)));
            float wave = smoothstep(0.004, 0.0, abs(fract(uv.y * 3.0) - 0.5 - 0.15 * sin(side * 60.0 + t * 4.0)) - 0.002) * step(0.7, fract(uv.y * 1.5 + 0.2));
            vec3 hc = hueColor(uP[0]);
            float fade = smoothstep(uP[2], uP[2] * 0.4, side);
            vec3 col = mix(c.rgb, c.rgb * 0.4, 0.5 * fade * uP[1]) + hc * (glyph * 0.6 + bars * 0.8 + wave) * fade * uP[1];
            return vec4(clamp(col, 0.0, 1.0), c.a);
            """
        },
        fx("hud_biometric", "Analyse biométrique", C.HUD, listOf(p("x", "Centre X", 0.0, 1.0, 0.5), p("y", "Centre Y", 0.0, 1.0, 0.42), p("size", "Taille", 0.1, 0.6, 0.3), hue("hue", "Couleur", 170.0), p("speed", "Vitesse", 0.0, 3.0, 1.0)),
            animated = true, desc = "Maillage de reconnaissance faciale et barre de scan qui analysent le visage") {
            """
            vec4 c = tex(uv);
            float ar = uResolution.x / uResolution.y;
            vec2 d = (uv - vec2(uP[0], 1.0 - uP[1])) * vec2(ar, 1.0);
            vec2 e = d / vec2(uP[2] * 0.75, uP[2]);
            float r = length(e);
            float inside = smoothstep(1.02, 0.98, r);
            vec2 g = fract(e * 6.0 + 0.5);
            float mesh = (smoothstep(0.06, 0.0, abs(g.x - 0.5)) + smoothstep(0.06, 0.0, abs(g.y - 0.5)) + smoothstep(0.08, 0.0, abs(fract((e.x + e.y) * 4.0) - 0.5))) * inside;
            float nodes = smoothstep(0.12, 0.0, length(g - 0.5)) * inside;
            float outline = smoothstep(0.03, 0.0, abs(r - 1.0));
            float scanY = sin(uTime * uP[4] * 1.5);
            float scan = smoothstep(0.08, 0.0, abs(e.y - scanY)) * inside;
            vec3 hc = hueColor(uP[3]);
            vec3 col = c.rgb + hc * (mesh * 0.18 + nodes * 0.6 + outline * 0.9 + scan * 0.5);
            col = mix(col, col * vec3(0.8, 1.0, 1.0), scan * 0.3);
            return vec4(clamp(col, 0.0, 1.0), c.a);
            """
        },
        fx("hud_visor", "Visière cyber", C.HUD, listOf(hue("hue", "Teinte", 190.0), p("amount", "Intensité", 0.0, 1.0, 0.7), p("curve", "Courbure", 0.0, 1.0, 0.5)),
            animated = true, desc = "Vue à travers un casque high-tech : verre bombé teinté, graduations et reflets") {
            """
            vec2 d = uv - 0.5;
            float k = uP[2] * 0.25;
            vec2 q = 0.5 + d * (1.0 + k * dot(d, d) * 2.0) / (1.0 + k * 0.5);
            vec4 c = tex(q);
            float ca = 0.004 * uP[2];
            vec3 col = vec3(tex(q + d * ca).r, c.g, tex(q - d * ca).b);
            vec3 hc = hueColor(uP[0]);
            col = mix(col, col * (0.6 + hc * 0.6), uP[1] * 0.5);
            float vig = smoothstep(0.75, 0.35, length(d * vec2(1.0, 1.3)));
            col *= mix(1.0, vig, uP[1]);
            float ticks = step(0.92, fract(uv.x * 40.0)) * smoothstep(0.03, 0.0, abs(uv.y - 0.92)) + step(0.92, fract(uv.y * 30.0)) * smoothstep(0.012, 0.0, abs(uv.x - 0.06));
            float frame = smoothstep(0.004, 0.0, abs(max(abs(d.x) * 2.0, abs(d.y) * 2.0) - 0.88)) * step(0.6, abs(d.x) * 2.0 + abs(d.y) * 2.0 - 0.6);
            float glare = smoothstep(0.08, 0.0, abs(d.x + d.y * 0.6 - 0.35 + 0.1 * sin(uTime * 0.5))) * 0.12;
            col += hc * (ticks + frame) * uP[1] + vec3(glare) * uP[1];
            return vec4(clamp(col, 0.0, 1.0), c.a);
            """
        },
    )

    // ------------------------------------------------------------------ VISION IA
    private val vision = listOf(
        fx("ai_thermal", "Vision thermique", C.AI_VISION, listOf(p("amount", "Intensité", 0.0, 1.0, 1.0), p("contrast", "Contraste", 0.5, 3.0, 1.3), choice("palette", "Palette", "Fer chaud", "Arc-en-ciel", "Blanc chaud")),
            desc = "Caméra infrarouge : chaque zone prend la couleur de sa « chaleur »") {
            """
            vec4 c = tex(uv);
            float v = clamp((luma(c.rgb) - 0.5) * uP[1] + 0.5, 0.0, 1.0);
            vec3 col = vec3(v);
            if (uP[2] < 0.5) col = clamp(vec3(v * 2.2 - 0.3, v * 2.0 - 0.8, sin(v * 3.1416) * 0.9 + max(0.0, v * 3.0 - 2.4)), 0.0, 1.0);
            else if (uP[2] < 1.5) col = hsv2rgb(vec3(0.7 * (1.0 - v), 1.0, 0.35 + 0.65 * v));
                        return vec4(mix(c.rgb, col, uP[0]), c.a);
            """
        },
        fx("ai_night_vision", "Vision nocturne", C.AI_VISION, listOf(p("gain", "Amplification", 1.0, 6.0, 2.8), p("noise", "Bruit", 0.0, 1.0, 0.35), p("amount", "Intensité", 0.0, 1.0, 1.0), p("scope", "Lunette", 0.0, 1.0, 1.0)),
            animated = true, desc = "Intensificateur de lumière militaire : vert phosphore, grain et lunette binoculaire") {
            """
            vec4 c = tex(uv);
            float l = luma(c.rgb) * uP[0];
            l += (hash(uv * uResolution + fract(uTime * 13.0) * 100.0) - 0.5) * uP[1] * 0.4;
            l = l / (1.0 + l * 0.35);
            vec3 col = vec3(0.12, 1.0, 0.25) * l + vec3(0.0, 0.03, 0.0);
            col *= 0.92 + 0.08 * sin(uv.y * uResolution.y * 1.5);
            float ar = uResolution.x / uResolution.y;
            vec2 d1 = (uv - vec2(0.36, 0.5)) * vec2(ar, 1.0);
            vec2 d2 = (uv - vec2(0.64, 0.5)) * vec2(ar, 1.0);
            float lens = max(smoothstep(0.46, 0.42, length(d1)), smoothstep(0.46, 0.42, length(d2)));
            col *= mix(1.0, lens, uP[3]);
            return vec4(mix(c.rgb, clamp(col, 0.0, 1.0), uP[2]), c.a);
            """
        },
        fx("ai_xray", "Rayons X", C.AI_VISION, listOf(p("amount", "Intensité", 0.0, 1.0, 1.0), p("edges", "Structures", 0.0, 3.0, 1.2), hue("hue", "Teinte", 200.0)),
            desc = "Négatif radiographique bleuté qui fait ressortir les contours comme des os") {
            """
            vec4 c = tex(uv);
            vec2 px = 1.0 / uResolution;
            float gx = luma(tex(uv + vec2(px.x, 0.0)).rgb) - luma(tex(uv - vec2(px.x, 0.0)).rgb);
            float gy = luma(tex(uv + vec2(0.0, px.y)).rgb) - luma(tex(uv - vec2(0.0, px.y)).rgb);
            float e = length(vec2(gx, gy)) * uP[1] * 3.0;
            float v = 1.0 - luma(c.rgb);
            v = smoothstep(0.1, 0.95, v) * 0.75 + e;
            vec3 col = mix(vec3(0.0, 0.02, 0.05), hueColor(uP[2]) * 0.5 + 0.5, clamp(v, 0.0, 1.0));
            return vec4(mix(c.rgb, col, uP[0]), c.a);
            """
        },
        fx("ai_neural_edges", "Contours neuronaux", C.AI_VISION, listOf(p("amount", "Intensité", 0.0, 1.0, 1.0), p("gain", "Sensibilité", 1.0, 10.0, 4.0), p("bg", "Image d'origine", 0.0, 1.0, 0.15), p("speed", "Cycle des couleurs", 0.0, 2.0, 0.3)),
            animated = true, desc = "Les contours détectés s'allument en couleur selon leur orientation, comme vus par un réseau de neurones") {
            """
            vec4 c = tex(uv);
            vec2 px = 1.0 / uResolution;
            float tl = luma(tex(uv + vec2(-px.x, px.y)).rgb); float t = luma(tex(uv + vec2(0.0, px.y)).rgb); float tr = luma(tex(uv + vec2(px.x, px.y)).rgb);
            float l = luma(tex(uv + vec2(-px.x, 0.0)).rgb); float r = luma(tex(uv + vec2(px.x, 0.0)).rgb);
            float bl = luma(tex(uv + vec2(-px.x, -px.y)).rgb); float b = luma(tex(uv + vec2(0.0, -px.y)).rgb); float br = luma(tex(uv + vec2(px.x, -px.y)).rgb);
            float gx = (tr + 2.0 * r + br) - (tl + 2.0 * l + bl);
            float gy = (tl + 2.0 * t + tr) - (bl + 2.0 * b + br);
            float m = clamp(length(vec2(gx, gy)) * uP[1], 0.0, 1.0);
            float ang = atan(gy, gx) / 6.2831 + 0.5 + uTime * uP[3] * 0.2;
            vec3 col = c.rgb * uP[2] + hsv2rgb(vec3(fract(ang), 0.85, 1.0)) * m;
            return vec4(mix(c.rgb, clamp(col, 0.0, 1.0), uP[0]), c.a);
            """
        },
        fx("ai_segmentation", "Segmentation IA", C.AI_VISION, listOf(p("amount", "Intensité", 0.0, 1.0, 0.85), p("classes", "Nombre de classes", 3.0, 12.0, 6.0), p("outline", "Contours", 0.0, 1.0, 0.8)),
            desc = "Découpe l'image en zones de couleurs franches avec contours, comme un masque de segmentation") {
            """
            vec4 c = tex(uv);
            vec3 hsv = rgb2hsv(c.rgb);
            float k = uP[1];
            float cls = floor(hsv.x * k + 0.5) + floor(hsv.z * 2.99) * 17.0 + step(hsv.y, 0.15) * 31.0;
            vec3 flat1 = hsv2rgb(vec3(fract(hash(vec2(cls, 1.0)) + 0.1), 0.65, 0.95));
            vec2 px = 1.5 / uResolution;
            vec3 h2 = rgb2hsv(tex(uv + vec2(px.x, 0.0)).rgb);
            vec3 h3 = rgb2hsv(tex(uv + vec2(0.0, px.y)).rgb);
            float c2 = floor(h2.x * k + 0.5) + floor(h2.z * 2.99) * 17.0 + step(h2.y, 0.15) * 31.0;
            float c3 = floor(h3.x * k + 0.5) + floor(h3.z * 2.99) * 17.0 + step(h3.y, 0.15) * 31.0;
            float edge = max(step(0.5, abs(c2 - cls)), step(0.5, abs(c3 - cls)));
            vec3 col = mix(c.rgb, flat1 * (0.55 + 0.45 * hsv.z), 0.75);
            col = mix(col, vec3(1.0), edge * uP[2]);
            return vec4(mix(c.rgb, col, uP[0]), c.a);
            """
        },
        fx("ai_depth_map", "Carte de profondeur", C.AI_VISION, listOf(p("amount", "Intensité", 0.0, 1.0, 1.0), p("bias", "Sol / ciel", 0.0, 1.0, 0.45), p("smooth", "Lissage", 0.0, 12.0, 4.0, "px")),
            desc = "Visualise la profondeur estimée de la scène avec une palette « turbo » de capteur 3D") {
            """
            vec4 c = tex(uv);
            vec2 px = uP[2] / uResolution;
            float l = luma(c.rgb);
            for (int i = 0; i < 8; i++) {
                float a = float(i) * 0.7854;
                l += luma(tex(uv + vec2(cos(a), sin(a)) * px).rgb);
            }
            l /= 9.0;
            float x = clamp(mix(l, 1.0 - uv.y, uP[1]), 0.0, 1.0);
            vec4 v4 = vec4(1.0, x, x * x, x * x * x);
            vec2 v2 = v4.zw * v4.z;
            vec3 col = vec3(
                dot(v4, vec4(0.13572138, 4.61539260, -42.66032258, 132.13108234)) + dot(v2, vec2(-152.94239396, 59.28637943)),
                dot(v4, vec4(0.09140261, 2.19418839, 4.84296658, -14.18503333)) + dot(v2, vec2(4.27729857, 2.82956604)),
                dot(v4, vec4(0.10667330, 12.64194608, -60.58204836, 110.36276771)) + dot(v2, vec2(-89.90310912, 27.34824973)));
            return vec4(mix(c.rgb, clamp(col, 0.0, 1.0), uP[0]), c.a);
            """
        },
        fx("ai_cloak", "Camouflage optique", C.AI_VISION, listOf(p("amount", "Invisibilité", 0.0, 1.0, 0.8), p("x", "Centre X", 0.0, 1.0, 0.5), p("y", "Centre Y", 0.0, 1.0, 0.5), p("size", "Taille", 0.1, 0.8, 0.35), p("speed", "Ondulation", 0.0, 3.0, 1.0)),
            animated = true, desc = "Une zone devient presque invisible : réfraction ondulante et contour scintillant") {
            """
            float ar = uResolution.x / uResolution.y;
            vec2 d = (uv - vec2(uP[1], 1.0 - uP[2])) * vec2(ar, 1.0);
            float r = length(d * vec2(1.0, 0.6)) / uP[3];
            float m = smoothstep(1.0, 0.85, r);
            vec2 n = vec2(noise(uv * 18.0 + uTime * uP[4]), noise(uv * 18.0 - uTime * uP[4] + 5.0)) - 0.5;
            vec2 off = (n * 0.03 + normalize(d + 0.0001) * 0.02 * (1.0 - r)) * m * uP[0];
            vec4 c = tex(uv + off);
            vec4 base = tex(uv);
            float rim = smoothstep(0.12, 0.0, abs(r - 0.95)) * (0.5 + 0.5 * sin(uTime * 8.0 + atan(d.y, d.x) * 6.0));
            vec3 col = mix(base.rgb, c.rgb * 1.05, m * uP[0]) + vec3(0.6, 0.9, 1.0) * rim * 0.25 * uP[0];
            return vec4(clamp(col, 0.0, 1.0), base.a);
            """
        },
        fx("ai_hallucination", "Hallucination IA", C.AI_VISION, listOf(p("amount", "Intensité", 0.0, 1.0, 0.6), p("swirl", "Tourbillons", 0.0, 2.0, 0.8), p("speed", "Vitesse", 0.0, 2.0, 0.4)),
            animated = true, desc = "Rêve de réseau de neurones : l'image se tord en volutes et cycles de couleurs psychédéliques") {
            """
            vec2 q = uv;
            float t = uTime * uP[2];
            for (int i = 0; i < 4; i++) {
                float fi = float(i) + 1.0;
                vec2 n = vec2(noise(q * 3.0 * fi + t), noise(q * 3.0 * fi - t + 7.1)) - 0.5;
                q += vec2(-n.y, n.x) * 0.02 * uP[1] / fi;
            }
            vec4 c = tex(q);
            vec3 hsv = rgb2hsv(c.rgb);
            hsv.x = fract(hsv.x + luma(c.rgb) * 0.5 + t * 0.1);
            hsv.y = min(1.0, hsv.y * 1.6 + 0.2);
            float eyes = smoothstep(0.1, 0.0, abs(fract(length(q - 0.5) * 18.0 - t) - 0.5) - 0.35) * 0.15;
            vec3 col = hsv2rgb(hsv) + vec3(eyes);
            return vec4(clamp(mix(tex(uv).rgb, col, uP[0]), 0.0, 1.0), c.a);
            """
        },
        fx("ai_pixel_sort_v", "Tri de pixels IA", C.AI_VISION, listOf(p("threshold", "Seuil", 0.0, 1.0, 0.55), p("length", "Longueur", 0.0, 0.5, 0.18), p("amount", "Intensité", 0.0, 1.0, 0.9)),
            desc = "Les zones claires « coulent » vers le bas en longues traînées triées par luminosité") {
            """
            vec4 c = tex(uv);
            vec3 best = c.rgb;
            float bl = luma(c.rgb);
            for (int i = 1; i < 24; i++) {
                float t = float(i) / 23.0;
                vec3 s = tex(uv + vec2(0.0, t * uP[1])).rgb;
                float l = luma(s);
                float on = step(uP[0], l) * step(bl, l);
                best = mix(best, s, on);
                bl = max(bl, l * on);
            }
            return vec4(mix(c.rgb, best, uP[2]), c.a);
            """
        },
    )

    // ------------------------------------------------------------------ 3D & DIMENSIONS
    private val dimension = listOf(
        fx("dim_parallax", "Parallaxe 3D", C.DIMENSION, listOf(p("amount", "Profondeur", 0.0, 0.08, 0.025), p("speed", "Vitesse", 0.0, 3.0, 0.8), choice("motion", "Mouvement", "Cercle", "Gauche-droite", "Haut-bas")),
            animated = true, desc = "Effet 2,5D : la caméra semble tourner autour de la scène grâce à une profondeur estimée") {
            """
            vec4 c0 = tex(uv);
            float depth = luma(c0.rgb) * 0.6 + (1.0 - uv.y) * 0.4;
            float t = uTime * uP[1];
            vec2 cam = vec2(sin(t), cos(t) * 0.6);
            if (uP[2] > 0.5 && uP[2] < 1.5) cam = vec2(sin(t), 0.0);
            else if (uP[2] > 1.5) cam = vec2(0.0, sin(t));
            vec2 q = uv;
            for (int i = 0; i < 4; i++) {
                float dd = luma(tex(q).rgb) * 0.6 + (1.0 - q.y) * 0.4;
                q = uv + cam * (dd - 0.5) * uP[0];
            }
            vec2 z = 0.5 + (q - 0.5) / (1.0 + uP[0] * 2.0);
            return tex(z);
            """
        },
        fx("dim_rotate3d", "Rotation 3D", C.DIMENSION, listOf(p("ax", "Inclinaison X", -60.0, 60.0, 0.0, "°"), p("ay", "Inclinaison Y", -60.0, 60.0, 20.0, "°"), p("persp", "Perspective", 0.0, 2.0, 1.0), p("wobble", "Oscillation", 0.0, 1.0, 0.0), p("bg", "Fond", 0.0, 1.0, 0.0)),
            animated = true, desc = "L'image devient un panneau qui pivote dans l'espace avec une vraie perspective") {
            """
            float wob = uP[3] * sin(uTime * 1.5);
            float ax = radians(uP[0] + wob * 20.0);
            float ay = radians(uP[1] + wob * 30.0);
            float ar = uResolution.x / uResolution.y;
            float dist = 2.6 / max(uP[2], 0.05);
            vec2 p0 = (uv - 0.5) * 2.0 * vec2(ar, 1.0);
            vec3 ro = vec3(0.0, 0.0, -dist);
            vec3 rd = normalize(vec3(p0, dist * 0.77));
            vec3 nrm = vec3(sin(ay) * cos(ax), -sin(ax), -cos(ay) * cos(ax));
            vec3 ux = vec3(cos(ay), 0.0, sin(ay));
            vec3 uy = cross(ux, nrm);
            vec4 outside = mix(vec4(0.0), vec4(0.03, 0.03, 0.04, 1.0), step(0.5, uP[4]));
            float den = dot(rd, nrm);
            if (abs(den) < 0.0001) return outside;
            float tt = dot(-ro, nrm) / den;
            if (tt < 0.0) return outside;
            vec3 hit = ro + rd * tt;
            vec2 lp = vec2(dot(hit, ux) / ar, dot(hit, uy)) * 0.5 * 0.77 + 0.5;
            if (lp.x < 0.0 || lp.x > 1.0 || lp.y < 0.0 || lp.y > 1.0) return outside;
            vec4 c = tex(lp);
            float shade = 0.75 + 0.25 * abs(nrm.z);
            return vec4(c.rgb * shade, c.a);
            """
        },
        fx("dim_crystal_ball", "Boule de cristal", C.DIMENSION, listOf(p("x", "Centre X", 0.0, 1.0, 0.5), p("y", "Centre Y", 0.0, 1.0, 0.5), p("size", "Taille", 0.1, 0.7, 0.35), p("refract", "Réfraction", 0.0, 1.0, 0.7)),
            desc = "Une sphère de verre inverse et magnifie la scène avec reflets et halo") {
            """
            float ar = uResolution.x / uResolution.y;
            vec2 ctr = vec2(uP[0], 1.0 - uP[1]);
            vec2 d = (uv - ctr) * vec2(ar, 1.0) / uP[2];
            float r = length(d);
            vec4 c = tex(uv);
            if (r >= 1.0) return vec4(c.rgb * (1.0 - 0.35 * smoothstep(1.25, 1.0, r)), c.a);
            float z = sqrt(1.0 - r * r);
            vec2 inv = ctr - d * uP[2] / vec2(ar, 1.0) * (0.35 + 0.65 * (1.0 - z));
            vec4 s = tex(mix(uv, inv, uP[3]));
            float spec = pow(max(0.0, dot(normalize(vec3(d, z)), normalize(vec3(-0.4, 0.5, 0.8)))), 40.0);
            float fres = pow(1.0 - z, 3.0);
            return vec4(clamp(s.rgb * (0.9 + 0.1 * z) + vec3(spec) * 0.8 + vec3(0.6, 0.8, 1.0) * fres * 0.4, 0.0, 1.0), c.a);
            """
        },
        fx("dim_fractal_kaleido", "Kaléidoscope fractal", C.DIMENSION, listOf(p("segments", "Segments", 3.0, 16.0, 6.0), p("depth", "Profondeur fractale", 0.0, 1.0, 0.5), p("speed", "Rotation", 0.0, 2.0, 0.3), p("zoom", "Zoom", 0.5, 3.0, 1.0)),
            animated = true, desc = "Miroirs repliés plusieurs fois sur eux-mêmes : un motif fractal infini qui tourne") {
            """
            float ar = uResolution.x / uResolution.y;
            vec2 d = (uv - 0.5) * vec2(ar, 1.0) / uP[3];
            d = rotate2(d, uTime * uP[2]);
            float seg = 6.2831 / floor(uP[0]);
            for (int i = 0; i < 3; i++) {
                float a = abs(mod(atan(d.y, d.x), seg) - seg * 0.5);
                d = vec2(cos(a), sin(a)) * length(d);
                if (float(i) < uP[1] * 3.0) d = rotate2(abs(d) * 1.35 - vec2(0.22, 0.12), 0.4 + uTime * uP[2] * 0.2);
            }
            vec2 q = d / vec2(ar, 1.0) + 0.5;
            q = abs(fract(q * 0.5 + 0.5) * 2.0 - 1.0);
            return tex(q);
            """
        },
        fx("dim_wormhole", "Trou de ver", C.DIMENSION, listOf(p("speed", "Vitesse", 0.0, 3.0, 1.0), p("twist", "Torsion", 0.0, 3.0, 1.0), hue("hue", "Lueur", 260.0), p("glow", "Intensité de la lueur", 0.0, 1.0, 0.5)),
            animated = true, desc = "L'image s'enroule dans un tunnel spatio-temporel en spirale qui aspire vers le centre") {
            """
            vec2 d = (uv - 0.5) * vec2(uResolution.x / uResolution.y, 1.0);
            float r = length(d) + 0.0001;
            float a = atan(d.y, d.x);
            float z = 0.25 / r + uTime * uP[0] * 0.4;
            float ang = a / 6.2831 + z * 0.15 * uP[1];
            vec2 q = vec2(fract(ang), fract(z));
            q = abs(q * 2.0 - 1.0);
            vec4 c = tex(q);
            float fog = smoothstep(0.0, 0.35, r);
            vec3 col = c.rgb * fog + hueColor(uP[2]) * pow(1.0 - min(r * 2.5, 1.0), 2.0) * uP[3] * 1.5;
            col += hueColor(uP[2] + 40.0) * smoothstep(0.02, 0.0, abs(fract(z * 2.0) - 0.5) - 0.47) * 0.4 * uP[3] * fog;
            return vec4(clamp(col, 0.0, 1.0), c.a);
            """
        },
        fx("dim_voxels", "Voxels 3D", C.DIMENSION, listOf(p("size", "Taille des cubes", 6.0, 60.0, 18.0, "px"), p("bevel", "Relief", 0.0, 1.0, 0.6), p("amount", "Intensité", 0.0, 1.0, 1.0)),
            desc = "L'image reconstruite en petits cubes éclairés, comme dans un monde en blocs") {
            """
            vec2 cell = vec2(uP[0]) / uResolution;
            vec2 id = floor(uv / cell);
            vec2 f = fract(uv / cell);
            vec4 c = tex((id + 0.5) * cell);
            float h = luma(c.rgb);
            float b = 0.12 + 0.12 * uP[1];
            float light = 1.0;
            if (f.y > 1.0 - b) light = 1.0 + 0.35 * uP[1];
            else if (f.x < b) light = 1.0 + 0.15 * uP[1];
            else if (f.x > 1.0 - b) light = 1.0 - 0.3 * uP[1];
            else if (f.y < b) light = 1.0 - 0.45 * uP[1];
            vec3 col = c.rgb * light * (0.85 + 0.3 * h);
            return vec4(mix(tex(uv).rgb, clamp(col, 0.0, 1.0), uP[2]), c.a);
            """
        },
        fx("dim_lenticular", "Carte lenticulaire", C.DIMENSION, listOf(p("lines", "Lentilles", 20.0, 200.0, 70.0), p("shift", "Décalage 3D", 0.0, 0.05, 0.015), p("speed", "Vitesse", 0.0, 3.0, 1.0)),
            animated = true, desc = "Effet carte postale 3D : fines lentilles, reflets irisés et image qui bascule") {
            """
            float tilt = sin(uTime * uP[2]);
            float f = fract(uv.x * uP[0]);
            float lensN = (f - 0.5) * 2.0;
            vec2 q = uv + vec2(tilt * uP[1] * (1.0 + lensN * 0.5), 0.0);
            vec4 c = tex(q);
            vec3 irid = 0.5 + 0.5 * cos(6.2831 * (vec3(f * 0.5 + tilt * 0.3) + vec3(0.0, 0.33, 0.67)));
            float ridge = pow(1.0 - abs(lensN), 6.0);
            vec3 col = c.rgb * (0.88 + 0.12 * (1.0 - abs(lensN))) + irid * ridge * 0.12;
            return vec4(clamp(col, 0.0, 1.0), c.a);
            """
        },
        fx("dim_droste", "Effet Droste infini", C.DIMENSION, listOf(p("scale", "Réduction", 1.5, 4.0, 2.2), p("speed", "Zoom infini", 0.0, 2.0, 0.4), p("border", "Cadre", 0.0, 0.05, 0.012)),
            animated = true, desc = "L'image contient l'image qui contient l'image… dans un zoom infini sans fin") {
            """
            vec2 d = uv - 0.5;
            float s = uP[0];
            float z = pow(s, fract(uTime * uP[1]));
            d /= z;
            float m = max(abs(d.x), abs(d.y)) * 2.0;
            float k = floor(-log(max(m, 0.0001)) / log(s));
            k = max(k, 0.0);
            vec2 q = d * pow(s, k);
            vec4 c = tex(q + 0.5);
            float mm = max(abs(q.x), abs(q.y)) * 2.0;
            float frame = step(1.0 - uP[2] * 2.0 * s, mm) * step(0.001, uP[2]);
            return vec4(mix(c.rgb, vec3(1.0), frame), c.a);
            """
        },
    )

    // ------------------------------------------------------------------ transition shaders (internal)
    private val transitions = listOf(
        // uProgress = visibilité de la couche (0 = invisible, 1 = entière)
        fx("_tr_teleport", "Téléportation", C.INTERNAL, listOf(p("grain", "Particules", 20.0, 300.0, 110.0), hue("hue", "Lueur", 190.0), p("invert", "Inverser", 0.0, 1.0, 0.0))) {
            """
            vec2 cell = floor(uv * vec2(uP[0] * uResolution.x / uResolution.y, uP[0]));
            float n = hash(cell) * 0.6 + (1.0 - uv.y) * 0.4;
            float pr = uProgress * 1.3 - 0.15;
            vec2 rise = vec2(0.0, (1.0 - uProgress) * 0.08 * hash(cell + 3.0));
            vec4 c = tex(uv - rise);
            float m = smoothstep(n - 0.05, n + 0.05, pr);
            float edge = smoothstep(0.12, 0.0, abs(n - pr)) * (1.0 - step(0.999, uProgress));
            vec3 col = c.rgb + hueColor(uP[1]) * edge * 1.2;
            return vec4(clamp(col, 0.0, 1.0), c.a * max(m, edge * 0.8));
            """
        },
        fx("_tr_holo_glitch", "Faille holographique", C.INTERNAL, listOf(p("amount", "Intensité", 0.1, 1.0, 1.0))) {
            """
            float k = (1.0 - uProgress) * uP[0];
            float row = floor(uv.y * 30.0);
            float jump = (hash(vec2(row, floor(uProgress * 24.0))) - 0.5) * 0.3 * k;
            vec2 q = vec2(uv.x + jump, uv.y);
            vec4 c = tex(q);
            vec3 col = vec3(tex(q + vec2(0.02 * k, 0.0)).r, c.g, tex(q - vec2(0.02 * k, 0.0)).b);
            col = mix(col, vec3(0.3, 0.9, 1.0) * luma(col) * (0.7 + 0.3 * sin(uv.y * 800.0)), k * 0.7);
            float vis = step(hash(vec2(row * 1.7, floor(uProgress * 30.0))), uProgress * 1.2);
            return vec4(col, c.a * max(vis, step(0.999, uProgress)));
            """
        },
        fx("_tr_portal", "Portail dimensionnel", C.INTERNAL, listOf(hue("hue", "Couleur", 280.0), p("invert", "Inverser", 0.0, 1.0, 0.0))) {
            """
            vec2 d = (uv - 0.5) * vec2(uResolution.x / uResolution.y, 1.0);
            float a = atan(d.y, d.x);
            float r = length(d);
            float maxR = length(vec2(uResolution.x / uResolution.y, 1.0)) * 0.5 + 0.1;
            float edgeR = uProgress * maxR * (1.0 + 0.08 * sin(a * 7.0 + uProgress * 20.0));
            vec2 sw = rotate2(d, (1.0 - uProgress) * 3.0 * (1.0 - smoothstep(0.0, edgeR + 0.001, r)));
            vec4 c = tex(sw / vec2(uResolution.x / uResolution.y, 1.0) + 0.5);
            float m = 1.0 - smoothstep(edgeR - 0.01, edgeR + 0.01, r);
            float ring = smoothstep(0.05, 0.0, abs(r - edgeR)) * (1.0 - step(0.999, uProgress));
            vec3 col = c.rgb + hueColor(uP[0] + a * 30.0) * ring * 1.5;
            if (uP[1] > 0.5) m = 1.0 - m;
            return vec4(clamp(col, 0.0, 1.0), c.a * max(m, ring));
            """
        },
        fx("_tr_warp", "Saut hyperespace", C.INTERNAL, listOf(p("amount", "Étirement", 0.1, 1.0, 0.8))) {
            """
            float k = (1.0 - uProgress) * uP[0];
            vec2 d = uv - 0.5;
            vec3 acc = vec3(0.0);
            for (int i = 0; i < 12; i++) {
                float t = float(i) / 11.0;
                acc += tex(0.5 + d * (1.0 - t * 0.5 * k)).rgb;
            }
            acc /= 12.0;
            float streak = pow(hash(vec2(floor(atan(d.y, d.x) * 60.0), 1.0)), 8.0) * smoothstep(0.05, 0.5, length(d)) * k;
            return vec4(clamp(acc + vec3(0.7, 0.85, 1.0) * streak + vec3(k * k * 0.6), 0.0, 1.0), tex(uv).a);
            """
        },
    )
}
