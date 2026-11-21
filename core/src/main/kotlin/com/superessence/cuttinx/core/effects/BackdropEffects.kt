package com.superessence.cuttinx.core.effects

import com.superessence.cuttinx.core.effects.EffectCategory as C

private fun p(key: String, label: String, min: Double, max: Double, def: Double, unit: String = "") =
    ParamDef(key, label, min, max, def, unit = unit)
private fun hue(key: String, label: String, def: Double) = ParamDef(key, label, 0.0, 360.0, def, ParamType.HUE, unit = "°")

/**
 * « Fonds animés » (CUTTINX 4.0) : arrière-plans procéduraux en mouvement, calculés par le GPU à
 * n'importe quelle résolution. Posés sur un plan couleur (ou sur une vidéo avec « Mélange »), ils
 * servent de décor aux textes, au Studio Script et aux stories. Les paramètres communs sont
 * toujours dans le même ordre : Couleur 1, Couleur 2, Vitesse, Mélange avec l'image.
 */
internal object BackdropEffects {
    private fun bg(id: String, name: String, h1: Double, h2: Double, extra: List<ParamDef> = emptyList(), desc: String, glsl: () -> String) =
        EffectDefinition(id, name, C.BACKDROP, EffectKind.VIDEO,
            listOf(hue("hue1", "Couleur 1", h1), hue("hue2", "Couleur 2", h2), p("speed", "Vitesse", 0.0, 3.0, 1.0), p("mix", "Mélange avec l'image", 0.0, 1.0, 1.0)) + extra,
            glsl(), animated = true, description = desc)

    /** Body footer shared by every backdrop: [col] is mixed over the source by uP[3]. */
    private fun out(body: String) = body.trimIndent() + "\nvec4 src = tex(uv);\nreturn vec4(mix(src.rgb, clamp(col, 0.0, 1.0), uP[3]), 1.0);\n"

    /** Ids of the backdrops, in the order they are shown. */
    val ids: List<String> get() = all().map { it.id }

    fun all(): List<EffectDefinition> = listOf(
        bg("bd_gradient_mesh", "Dégradé vivant", 280.0, 200.0, desc = "Quatre taches de couleur qui dérivent et se mélangent, comme les fonds des nouvelles interfaces") {
            out("""
            float t = uTime * uP[2] * 0.3;
            vec2 q = uv * vec2(uResolution.x / uResolution.y, 1.0);
            vec2 a = vec2(0.3 + 0.25 * sin(t), 0.3 + 0.2 * cos(t * 1.3));
            vec2 b = vec2(0.8 + 0.2 * cos(t * 0.8), 0.7 + 0.2 * sin(t * 1.1));
            vec2 c = vec2(0.5 + 0.3 * sin(t * 0.6 + 2.0), 0.9 + 0.1 * cos(t));
            vec2 d = vec2(0.2 + 0.2 * cos(t * 1.4), 0.8 + 0.15 * sin(t * 0.7));
            float wa = 1.0 / (0.05 + dot(q - a, q - a)); float wb = 1.0 / (0.05 + dot(q - b, q - b));
            float wc = 1.0 / (0.05 + dot(q - c, q - c)); float wd = 1.0 / (0.05 + dot(q - d, q - d));
            vec3 col = (hueColor(uP[0]) * wa + hueColor(uP[1]) * wb + hueColor(uP[0] + 60.0) * 0.8 * wc + hueColor(uP[1] - 50.0) * 0.9 * wd) / (wa + wb + wc + wd);
            col = mix(col, col * col, 0.25) + (hash(uv * uResolution) - 0.5) * 0.015;
            """)
        },
        bg("bd_waves", "Vagues fluides", 210.0, 170.0, desc = "Couches de vagues douces superposées qui ondulent en boucle") {
            out("""
            float t = uTime * uP[2];
            vec3 col = mix(hueColor(uP[0]) * 0.25, hueColor(uP[1]) * 0.15, uv.y);
            for (int i = 0; i < 5; i++) {
                float fi = float(i);
                float y = 0.2 + fi * 0.15 + 0.05 * sin(uv.x * (4.0 + fi) + t * (0.6 + fi * 0.2) + fi * 1.7) + 0.02 * sin(uv.x * 11.0 - t * 1.3 + fi);
                float m = smoothstep(y + 0.004, y - 0.004, uv.y);
                vec3 lc = mix(hueColor(uP[0]), hueColor(uP[1]), fi / 4.0) * (0.45 + fi * 0.12);
                col = mix(col, lc, m * 0.55);
                col += lc * smoothstep(0.012, 0.0, abs(uv.y - y)) * 0.35;
            }
            """)
        },
        bg("bd_lava", "Lampe à lave", 330.0, 30.0, desc = "Grosses bulles molles qui montent, fusionnent et se séparent") {
            out("""
            float t = uTime * uP[2] * 0.4;
            vec2 q = (uv - 0.5) * vec2(uResolution.x / uResolution.y, 1.0);
            float f = 0.0;
            for (int i = 0; i < 7; i++) {
                float fi = float(i);
                vec2 c = vec2(sin(t * (0.5 + fi * 0.13) + fi * 2.1) * 0.35, fract(t * (0.08 + fi * 0.015) + fi * 0.23) * 1.6 - 0.8);
                float r = 0.08 + 0.05 * sin(fi * 3.7);
                f += r * r / (dot(q - c, q - c) + 0.0001);
            }
            float blob = smoothstep(0.9, 1.1, f);
            vec3 col = mix(hueColor(uP[1]) * 0.18, mix(hueColor(uP[0]), hueColor(uP[1]), clamp(f * 0.3, 0.0, 1.0)), blob);
            col += hueColor(uP[0]) * smoothstep(0.6, 0.95, f) * (1.0 - blob) * 0.5;
            """)
        },
        bg("bd_retro_grid", "Grille rétro", 300.0, 190.0, desc = "Horizon néon et sol quadrillé qui défile vers toi, ambiance années 80") {
            out("""
            float t = uTime * uP[2];
            float hz = 0.42;
            vec3 col = mix(hueColor(uP[0]) * 0.35, vec3(0.02, 0.0, 0.06), smoothstep(hz, 1.0, uv.y));
            col += hueColor(uP[0]) * exp(-abs(uv.y - hz) * 18.0) * 0.8;
            if (uv.y < hz) {
                float z = 0.12 / (hz - uv.y + 0.001);
                float x = (uv.x - 0.5) * z * 2.0;
                float gx = smoothstep(0.43, 0.49, abs(fract(x) - 0.5));
                float gz = smoothstep(0.40, 0.49, abs(fract(z - t) - 0.5));
                float fade = smoothstep(0.0, 0.25, hz - uv.y);
                col = vec3(0.03, 0.0, 0.07) + hueColor(uP[1]) * max(gx, gz) * fade * 1.2 + hueColor(uP[0]) * 0.15 * (1.0 - fade);
            }
            """)
        },
        bg("bd_sunburst", "Rayons", 45.0, 20.0, listOf(p("rays", "Nombre de rayons", 6.0, 40.0, 16.0)), desc = "Rayons de soleil qui tournent lentement depuis le centre, style affiche") {
            out("""
            vec2 d = (uv - 0.5) * vec2(uResolution.x / uResolution.y, 1.0);
            float a = atan(d.y, d.x) + uTime * uP[2] * 0.2;
            float ray = step(0.5, fract(a / 6.2831 * floor(uP[4])));
            vec3 col = mix(hueColor(uP[0]), hueColor(uP[1]) * 0.85, ray);
            col *= 1.15 - length(d) * 0.7;
            col += vec3(1.0, 0.95, 0.8) * exp(-length(d) * 6.0) * 0.4;
            """)
        },
        bg("bd_bokeh_field", "Champ de bokeh", 40.0, 320.0, desc = "Disques de lumière floue qui flottent, comme des guirlandes hors mise au point") {
            out("""
            float t = uTime * uP[2];
            vec3 col = mix(hueColor(uP[1]) * 0.08, hueColor(uP[0]) * 0.12, uv.y);
            vec2 q = uv * vec2(uResolution.x / uResolution.y, 1.0);
            for (int i = 0; i < 28; i++) {
                float fi = float(i);
                vec2 c = vec2(hash(vec2(fi, 1.0)) * uResolution.x / uResolution.y, fract(hash(vec2(fi, 2.0)) + t * 0.02 * (0.5 + hash(vec2(fi, 3.0)))));
                c.x += sin(t * 0.3 + fi) * 0.03;
                float r = 0.03 + hash(vec2(fi, 4.0)) * 0.08;
                float d = length(q - c);
                float disk = smoothstep(r, r * 0.85, d) * (0.6 + 0.4 * smoothstep(r * 0.6, r, d));
                col += mix(hueColor(uP[0]), hueColor(uP[1]), hash(vec2(fi, 5.0))) * disk * (0.15 + 0.2 * hash(vec2(fi, 6.0)));
            }
            """)
        },
        bg("bd_starfield", "Ciel étoilé", 230.0, 280.0, desc = "Voûte étoilée profonde avec scintillement et voie lactée") {
            out("""
            float t = uTime * uP[2];
            vec3 col = mix(hueColor(uP[0]) * 0.03, hueColor(uP[1]) * 0.09, uv.y);
            vec2 q = uv * vec2(uResolution.x / uResolution.y, 1.0);
            float band = exp(-pow((q.y - 0.5 - (q.x - 0.8) * 0.4) * 4.0, 2.0));
            float n = 0.0; float amp = 0.5; vec2 r = q * 4.0 + t * 0.01;
            for (int i = 0; i < 4; i++) { n += amp * noise(r); r *= 2.1; amp *= 0.5; }
            col += mix(hueColor(uP[1]), vec3(1.0), 0.4) * band * n * 0.35;
            for (int l = 0; l < 3; l++) {
                float sc = 60.0 + float(l) * 70.0;
                vec2 g = q * sc; vec2 id = floor(g); vec2 f = fract(g) - 0.5;
                float h = hash(id + float(l) * 10.0);
                float tw = 0.6 + 0.4 * sin(t * (2.0 + h * 4.0) + h * 30.0);
                col += vec3(smoothstep(0.08, 0.0, length(f)) * step(0.985 - float(l) * 0.004, h) * tw);
            }
            """)
        },
        bg("bd_stripes", "Rayures en mouvement", 0.0, 50.0, listOf(p("count", "Rayures", 2.0, 40.0, 12.0)), desc = "Bandes diagonales qui défilent, idéal derrière un titre ou une promo") {
            out("""
            float s = (uv.x * uResolution.x / uResolution.y + uv.y) * uP[4] * 0.5 - uTime * uP[2];
            float b = smoothstep(0.48, 0.52, fract(s));
            vec3 col = mix(hueColor(uP[0]), hueColor(uP[1]), b);
            col *= 0.85 + 0.15 * uv.y;
            """)
        },
        bg("bd_aurora_sky", "Ciel d'aurore", 150.0, 270.0, desc = "Nuit polaire, rideaux de lumière et étoiles : un décor apaisant") {
            out("""
            float t = uTime * uP[2];
            vec3 col = mix(vec3(0.01, 0.02, 0.05), hueColor(uP[1]) * 0.12, uv.y);
            for (int i = 0; i < 4; i++) {
                float fi = float(i);
                float y = 0.55 + fi * 0.08 + 0.06 * sin(uv.x * (3.0 + fi) + t * 0.4 + fi * 2.0) + (noise(vec2(uv.x * 5.0 + fi, t * 0.2)) - 0.5) * 0.1;
                float c = exp(-abs(uv.y - y) * 14.0) * (0.5 + 0.5 * noise(vec2(uv.x * 30.0, fi + t * 0.3)));
                col += mix(hueColor(uP[0]), hueColor(uP[1]), fi / 3.0) * c * 0.45;
            }
            vec2 g = uv * uResolution / 3.0;
            col += vec3(step(0.997, hash(floor(g)))) * smoothstep(0.3, 0.9, uv.y) * (0.6 + 0.4 * sin(t * 3.0 + hash(floor(g)) * 40.0));
            """)
        },
        bg("bd_liquid_metal", "Métal liquide", 220.0, 40.0, desc = "Surface chromée qui ondule avec des reflets colorés") {
            out("""
            float t = uTime * uP[2] * 0.4;
            vec2 q = uv * 3.0;
            float n = 0.0; float amp = 0.5; vec2 r = q;
            for (int i = 0; i < 5; i++) { n += amp * noise(r + vec2(t, -t * 0.7)); r = r * 2.0 + n; amp *= 0.5; }
            float s = sin(n * 12.0 + t * 2.0);
            vec3 col = mix(vec3(0.15), vec3(0.95), 0.5 + 0.5 * s);
            col = mix(col, col * mix(hueColor(uP[0]), hueColor(uP[1]), n), 0.45);
            col += pow(max(s, 0.0), 12.0) * 0.4;
            """)
        },
        bg("bd_halftone_pop", "Pop art", 50.0, 190.0, desc = "Trame de gros points en mouvement sur deux couleurs vives, façon comics") {
            out("""
            float ar = uResolution.x / uResolution.y;
            vec2 q = rotate2((uv - 0.5) * vec2(ar, 1.0), 0.4) * 24.0;
            vec2 f = fract(q) - 0.5;
            float wave = 0.5 + 0.5 * sin(length(uv - 0.5) * 8.0 - uTime * uP[2] * 2.0);
            float dot1 = smoothstep(0.05, 0.0, length(f) - 0.18 - 0.22 * wave);
            vec3 col = mix(hueColor(uP[0]), hueColor(uP[1]) * 0.9, dot1);
            """)
        },
        bg("bd_tunnel", "Tunnel de lumière", 190.0, 300.0, desc = "On fonce dans un tunnel d'anneaux lumineux") {
            out("""
            vec2 d = (uv - 0.5) * vec2(uResolution.x / uResolution.y, 1.0);
            float r = length(d) + 0.001;
            float a = atan(d.y, d.x);
            float z = 0.3 / r + uTime * uP[2];
            float ring = smoothstep(0.36, 0.48, abs(fract(z) - 0.5));
            float seg = smoothstep(0.46, 0.5, abs(fract(a / 6.2831 * 12.0) - 0.5));
            vec3 col = hueColor(uP[1]) * 0.06 + mix(hueColor(uP[0] + z * 20.0), hueColor(uP[1]), 0.5 + 0.5 * sin(z)) * (ring + seg * 0.4) * smoothstep(0.0, 0.4, r);
            col += hueColor(uP[0]) * exp(-r * 6.0) * 0.6;
            """)
        },
    )
}
