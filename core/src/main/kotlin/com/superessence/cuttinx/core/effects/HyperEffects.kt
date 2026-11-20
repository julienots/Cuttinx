package com.superessence.cuttinx.core.effects

import com.superessence.cuttinx.core.effects.EffectCategory as C

private fun p(key: String, label: String, min: Double, max: Double, def: Double, unit: String = "") =
    ParamDef(key, label, min, max, def, unit = unit)
private fun hue(key: String, label: String, def: Double) = ParamDef(key, label, 0.0, 360.0, def, ParamType.HUE, unit = "°")

private fun fx(id: String, name: String, cat: C, params: List<ParamDef>, animated: Boolean = false, desc: String = "", glsl: () -> String) =
    EffectDefinition(id, name, cat, EffectKind.VIDEO, params, glsl(), animated, desc)

/**
 * « Hyper réalité » (CUTTINX 3.5) : deux nouvelles familles (Cosmos & énergie, Art génératif) et
 * de nouveaux effets dans chacune des familles existantes, plus 6 transitions. Shaders originaux
 * écrits pour CUTTINX, en GLSL ES 1.00 compatible SkSL (boucles à bornes constantes, aucune
 * fonction locale, pas de round/tanh), donc identiques sur Android et sur PC.
 */
internal object HyperEffects {
    fun all(): List<EffectDefinition> = cosmos + generative + everywhere + transitions

    /** Ids of the effects added in 3.5 that belong to the families that already existed. */
    val spreadIds: List<String> get() = everywhere.map { it.id }

    // ------------------------------------------------------------------ COSMOS & ÉNERGIE
    private val cosmos = listOf(
        fx("cos_aura", "Aura d'énergie", C.COSMOS, listOf(hue("hue", "Couleur", 200.0), p("amount", "Intensité", 0.0, 2.0, 1.0), p("size", "Rayonnement", 1.0, 12.0, 5.0, "px"), p("speed", "Pulsation", 0.0, 3.0, 1.0)),
            animated = true, desc = "Les contours du sujet s'embrasent d'une aura qui ondule, façon super-pouvoir") {
            """
            vec4 c = tex(uv);
            vec2 px = uP[2] / uResolution;
            float e = 0.0;
            for (int i = 0; i < 8; i++) {
                float a = float(i) * 0.785398;
                vec2 o = vec2(cos(a), sin(a)) * px;
                e += abs(luma(tex(uv + o).rgb) - luma(tex(uv - o).rgb));
            }
            float flick = 0.6 + 0.4 * noise(uv * 9.0 + vec2(0.0, uTime * uP[3] * 2.0));
            float pulse = 0.8 + 0.2 * sin(uTime * uP[3] * 4.0);
            vec3 glow = hueColor(uP[0] + 30.0 * noise(uv * 4.0 + uTime * 0.3)) * smoothstep(0.08, 0.6, e) * flick * pulse * uP[1];
            return vec4(clamp(c.rgb + glow, 0.0, 1.0), c.a);
            """
        },
        fx("cos_lightning", "Éclairs", C.COSMOS, listOf(hue("hue", "Couleur", 210.0), p("amount", "Intensité", 0.0, 2.0, 1.0), p("rate", "Fréquence", 0.2, 6.0, 1.5), p("x", "Origine X", 0.0, 1.0, 0.5)),
            animated = true, desc = "Des éclairs ramifiés zèbrent l'image et l'illuminent à chaque impact") {
            """
            vec4 c = tex(uv);
            float slot = floor(uTime * uP[2]);
            float life = fract(uTime * uP[2]);
            float on = step(0.2, hash(vec2(slot, 4.1))) * (1.0 - smoothstep(0.0, 0.35, life));
            float x0 = uP[3] + (hash(vec2(slot, 1.7)) - 0.5) * 0.4;
            float y = 1.0 - uv.y;
            float path = x0;
            float amp = 0.12;
            float f = 3.0;
            for (int i = 0; i < 5; i++) {
                path += (noise(vec2(y * f, slot * 3.1 + float(i))) - 0.5) * amp;
                amp *= 0.55; f *= 2.1;
            }
            float d = abs(uv.x - path);
            float bolt = 0.0015 / (d + 0.0015) * smoothstep(0.0, 0.05, y);
            float branchX = path + (y - 0.4) * (hash(vec2(slot, 9.3)) - 0.5) * 0.8;
            float branch = 0.0008 / (abs(uv.x - branchX) + 0.0008) * step(0.4, y) * step(y, 0.75);
            vec3 col = hueColor(uP[0]);
            vec3 add = (col * 0.6 + 0.4) * (bolt + branch * 0.6) * on * uP[1];
            vec3 flash = col * on * 0.25 * uP[1];
            return vec4(clamp(c.rgb + add + flash, 0.0, 1.0), c.a);
            """
        },
        fx("cos_nebula", "Nébuleuse", C.COSMOS, listOf(hue("hue", "Teinte", 280.0), p("amount", "Densité", 0.0, 1.0, 0.55), p("stars", "Étoiles", 0.0, 1.0, 0.6), p("speed", "Dérive", 0.0, 2.0, 0.3)),
            animated = true, desc = "Gaz cosmiques colorés et champ d'étoiles qui dérivent derrière les zones sombres") {
            """
            vec4 c = tex(uv);
            vec2 q = uv * vec2(uResolution.x / uResolution.y, 1.0) * 2.5 + vec2(uTime * uP[3] * 0.05, 0.0);
            float f = 0.0; float a = 0.5; vec2 r = q;
            for (int i = 0; i < 5; i++) { f += a * noise(r); r = r * 2.03 + vec2(1.7, 9.2); a *= 0.5; }
            float g = 0.0; a = 0.5; r = q * 1.3 + f * 1.5;
            for (int i = 0; i < 4; i++) { g += a * noise(r); r *= 2.1; a *= 0.5; }
            vec3 gas = mix(hueColor(uP[0]), hueColor(uP[0] + 70.0), g) * pow(f, 1.6) * 1.6;
            vec2 sc = floor(uv * uResolution / 2.0);
            float s = step(0.996, hash(sc)) * (0.5 + 0.5 * sin(uTime * 3.0 + hash(sc + 1.0) * 40.0));
            vec3 sky = gas + vec3(s) * uP[2];
            float dark = 1.0 - smoothstep(0.05, 0.6, luma(c.rgb));
            return vec4(clamp(c.rgb + sky * dark * uP[1] * 1.4, 0.0, 1.0), c.a);
            """
        },
        fx("cos_aurora", "Aurores boréales", C.COSMOS, listOf(hue("hue", "Teinte", 140.0), p("amount", "Intensité", 0.0, 1.5, 0.8), p("height", "Hauteur", 0.0, 1.0, 0.35), p("speed", "Vitesse", 0.0, 3.0, 0.8)),
            animated = true, desc = "Rideaux de lumière verte et violette qui ondulent dans le ciel") {
            """
            vec4 c = tex(uv);
            float y = 1.0 - uv.y;
            vec3 acc = vec3(0.0);
            for (int i = 0; i < 4; i++) {
                float fi = float(i);
                float wave = sin(uv.x * (3.0 + fi * 1.7) + uTime * uP[3] * (0.4 + fi * 0.15) + fi * 2.0) * 0.06
                    + (noise(vec2(uv.x * 6.0 + fi * 3.0, uTime * uP[3] * 0.3)) - 0.5) * 0.08;
                float band = y - (uP[2] + fi * 0.05 + wave);
                float curtain = smoothstep(0.0, 0.02, -band) * exp(band * 9.0) * (0.6 + 0.4 * noise(vec2(uv.x * 40.0, fi + uTime * 0.5)));
                acc += hueColor(uP[0] + fi * 45.0) * curtain;
            }
            float sky = 1.0 - smoothstep(0.1, 0.7, luma(c.rgb)) * 0.5;
            return vec4(clamp(c.rgb + acc * uP[1] * 0.6 * sky, 0.0, 1.0), c.a);
            """
        },
        fx("cos_black_hole", "Trou noir", C.COSMOS, listOf(p("cx", "Centre X", 0.0, 1.0, 0.5), p("cy", "Centre Y", 0.0, 1.0, 0.5), p("mass", "Masse", 0.0, 1.0, 0.45), hue("hue", "Disque d'accrétion", 30.0), p("speed", "Rotation", 0.0, 3.0, 1.0)),
            animated = true, desc = "Lentille gravitationnelle : la lumière se courbe autour d'un horizon noir et d'un disque brûlant") {
            """
            float ar = uResolution.x / uResolution.y;
            vec2 ctr = vec2(uP[0], 1.0 - uP[1]);
            vec2 d = (uv - ctr) * vec2(ar, 1.0);
            float r = length(d) + 0.0001;
            float rs = 0.03 + uP[2] * 0.12;
            float bend = rs * rs / r;
            vec2 q = ctr + (d - normalize(d) * bend * 1.8) / vec2(ar, 1.0);
            q = rotate2(q - ctr, bend * uP[4] * 4.0 + uTime * 0.0) + ctr;
            vec4 c = tex(q);
            float a = atan(d.y, d.x);
            float ring = smoothstep(rs * 2.6, rs * 1.3, r) * smoothstep(rs * 0.95, rs * 1.25, r);
            float swirl = 0.6 + 0.4 * sin(a * 6.0 - uTime * uP[4] * 4.0 + r * 60.0);
            vec3 disk = mix(hueColor(uP[3]), vec3(1.0, 0.95, 0.8), 0.5) * ring * swirl * 1.6;
            float hole = smoothstep(rs * 0.9, rs, r);
            return vec4(clamp(c.rgb * hole + disk, 0.0, 1.0), c.a);
            """
        },
        fx("cos_supernova", "Supernova", C.COSMOS, listOf(p("cx", "Centre X", 0.0, 1.0, 0.5), p("cy", "Centre Y", 0.0, 1.0, 0.5), hue("hue", "Couleur", 40.0), p("amount", "Intensité", 0.0, 2.0, 1.0), p("rate", "Explosions par seconde", 0.1, 3.0, 0.6)),
            animated = true, desc = "Une étoile explose : onde de choc lumineuse, rayons et souffle sur toute l'image") {
            """
            float ar = uResolution.x / uResolution.y;
            vec2 ctr = vec2(uP[0], 1.0 - uP[1]);
            vec2 d = (uv - ctr) * vec2(ar, 1.0);
            float r = length(d);
            float t = fract(uTime * uP[4]);
            float front = t * 1.4;
            float shock = smoothstep(0.06, 0.0, abs(r - front)) * (1.0 - t);
            vec2 q = uv - normalize(d + 0.0001) / vec2(ar, 1.0) * shock * 0.03;
            vec4 c = tex(q);
            float a = atan(d.y, d.x);
            float rays = pow(abs(sin(a * 12.0 + hash(vec2(floor(uTime * uP[4]), 2.0)) * 6.0)), 18.0) * exp(-r * 4.0) * (1.0 - t);
            float core = exp(-r * r * 60.0) * (1.0 - t * 0.7);
            vec3 col = hueColor(uP[2]);
            vec3 add = (col * shock * 1.4 + mix(col, vec3(1.0), 0.6) * (rays + core * 1.5)) * uP[3];
            return vec4(clamp(c.rgb + add + col * (1.0 - t) * 0.12 * uP[3], 0.0, 1.0), c.a);
            """
        },
        fx("cos_warp_stars", "Vitesse lumière", C.COSMOS, listOf(p("speed", "Vitesse", 0.0, 4.0, 1.5), p("amount", "Densité", 0.0, 1.0, 0.7), hue("hue", "Teinte", 210.0), p("blend", "Mélange avec l'image", 0.0, 1.0, 0.6)),
            animated = true, desc = "Les étoiles s'étirent en traits de lumière : on passe en vitesse lumière") {
            """
            vec4 c = tex(uv);
            vec2 d = (uv - 0.5) * vec2(uResolution.x / uResolution.y, 1.0);
            float r = length(d) + 0.0001;
            float a = atan(d.y, d.x);
            vec3 acc = vec3(0.0);
            for (int i = 0; i < 3; i++) {
                float fi = float(i);
                float lane = floor(a * (40.0 + fi * 23.0));
                float h = hash(vec2(lane, fi));
                float z = fract(h * 7.0 + uTime * uP[0] * (0.3 + h * 0.4));
                float len = 0.02 + z * z * 0.4 * uP[0] / 2.0;
                float pos = z * z * 1.2;
                float on = step(1.0 - uP[1], h) * smoothstep(len, 0.0, abs(r - pos)) * smoothstep(0.0, 0.1, r);
                acc += mix(hueColor(uP[2] + h * 60.0), vec3(1.0), 0.6) * on * z;
            }
            vec3 zoom = tex(0.5 + (uv - 0.5) * (1.0 - 0.04 * uP[0])).rgb;
            vec3 base = mix(c.rgb, zoom, 0.5) * uP[3];
            return vec4(clamp(base + acc * 1.5, 0.0, 1.0), c.a);
            """
        },
        fx("cos_plasma", "Plasma électrique", C.COSMOS, listOf(hue("hue", "Teinte", 290.0), p("amount", "Intensité", 0.0, 1.5, 0.6), p("scale", "Échelle", 1.0, 12.0, 4.0), p("speed", "Vitesse", 0.0, 3.0, 1.0)),
            animated = true, desc = "Filaments de plasma qui crépitent dans les zones sombres, comme une boule à plasma") {
            """
            vec4 c = tex(uv);
            vec2 q = (uv - 0.5) * vec2(uResolution.x / uResolution.y, 1.0) * uP[2];
            float t = uTime * uP[3];
            float v = 0.0;
            for (int i = 0; i < 3; i++) {
                float fi = float(i) + 1.0;
                v += abs(noise(q * fi + vec2(t * 0.7 * fi, -t * 0.5)) - 0.5);
            }
            float fil = smoothstep(0.1, 0.0, v * 0.33) * 1.3 + exp(-v * 5.0) * 0.25;
            vec3 col = hueColor(uP[0] + 40.0 * sin(t + q.x)) * fil;
            float dark = 1.0 - smoothstep(0.2, 0.9, luma(c.rgb)) * 0.6;
            return vec4(clamp(c.rgb + col * uP[1] * dark, 0.0, 1.0), c.a);
            """
        },
    )

    // ------------------------------------------------------------------ ART GÉNÉRATIF
    private val generative = listOf(
        fx("gen_stained_glass", "Vitrail", C.GENERATIVE, listOf(p("cells", "Morceaux de verre", 6.0, 60.0, 14.0), p("lead", "Plomb", 0.0, 1.0, 0.5), p("glow", "Lumière traversante", 0.0, 1.0, 0.5)),
            desc = "Cellules de Voronoï façon vitrail de cathédrale, avec les joints en plomb") {
            """
            float ar = uResolution.x / uResolution.y;
            vec2 g = uv * vec2(uP[0] * ar, uP[0]);
            vec2 id = floor(g);
            vec2 f = fract(g);
            float d1 = 8.0; float d2 = 8.0; vec2 best = vec2(0.0);
            for (int j = -1; j <= 1; j++) for (int i = -1; i <= 1; i++) {
                vec2 o = vec2(float(i), float(j));
                vec2 pt = o + vec2(hash(id + o), hash(id + o + 17.3));
                float d = length(pt - f);
                if (d < d1) { d2 = d1; d1 = d; best = id + o; } else if (d < d2) { d2 = d; }
            }
            vec2 cuv = (best + 0.5) / vec2(uP[0] * ar, uP[0]);
            vec3 col = tex(cuv).rgb;
            col = clamp((col - 0.5) * 1.3 + 0.5, 0.0, 1.0) * (1.0 + uP[2] * 0.3 * (1.0 - d1));
            float edge = smoothstep(0.02 + uP[1] * 0.08, 0.0, d2 - d1);
            return vec4(mix(col, vec3(0.05), edge), tex(uv).a);
            """
        },
        fx("gen_fluid", "Peinture fluide", C.GENERATIVE, listOf(p("amount", "Mélange", 0.0, 1.0, 0.5), p("scale", "Taille des volutes", 1.0, 10.0, 3.0), p("speed", "Écoulement", 0.0, 2.0, 0.5)),
            animated = true, desc = "L'image coule comme de la peinture liquide entraînée par des tourbillons") {
            """
            vec2 q = uv;
            float t = uTime * uP[2];
            for (int i = 0; i < 6; i++) {
                float n = noise(q * uP[1] + vec2(t, -t * 0.7)) * 6.2831;
                q += vec2(cos(n), sin(n)) * 0.006 * uP[0];
            }
            vec4 c = tex(q);
            return vec4(clamp(c.rgb * (1.0 + 0.15 * uP[0]), 0.0, 1.0), c.a);
            """
        },
        fx("gen_topo", "Carte topographique", C.GENERATIVE, listOf(p("lines", "Courbes de niveau", 4.0, 40.0, 14.0), hue("hue", "Couleur des lignes", 30.0), p("bg", "Image visible", 0.0, 1.0, 0.35)),
            desc = "La luminosité devient un relief tracé en courbes de niveau, comme une carte IGN") {
            """
            vec4 c = tex(uv);
            float h = luma(tex(uv + vec2(1.5) / uResolution).rgb) * 0.5 + luma(c.rgb) * 0.5;
            float v = h * uP[0];
            float l = abs(fract(v) - 0.5);
            float line = smoothstep(0.43, 0.49, l);
            float major = smoothstep(0.47, 0.495, abs(fract(v / 5.0) - 0.5));
            vec3 paper = mix(vec3(0.95, 0.92, 0.85), c.rgb, uP[2]);
            vec3 ink = hueColor(uP[1]) * 0.55;
            return vec4(mix(paper, ink, clamp(line * 0.8 + major, 0.0, 1.0)), c.a);
            """
        },
        fx("gen_led_wall", "Mur de LED", C.GENERATIVE, listOf(p("size", "Taille des LED", 4.0, 40.0, 10.0, "px"), p("glow", "Halo", 0.0, 1.0, 0.6), p("gap", "Espacement", 0.0, 0.8, 0.35)),
            desc = "L'image affichée sur un écran géant de stade, LED rondes et halo") {
            """
            vec2 cell = vec2(uP[0]) / uResolution;
            vec2 id = floor(uv / cell);
            vec2 f = fract(uv / cell) - 0.5;
            vec3 col = tex((id + 0.5) * cell).rgb;
            col = floor(col * 12.0 + 0.5) / 12.0;
            float d = length(f);
            float r = 0.5 - uP[2] * 0.4;
            float dot1 = smoothstep(r, r - 0.08, d);
            float halo = exp(-d * d * 10.0) * uP[1] * 0.6;
            return vec4(clamp(col * (dot1 * 1.15 + halo), 0.0, 1.0), tex(uv).a);
            """
        },
        fx("gen_mandala", "Mandala", C.GENERATIVE, listOf(p("segments", "Branches", 3.0, 16.0, 8.0), p("speed", "Rotation", 0.0, 2.0, 0.3), p("zoom", "Zoom", 0.5, 3.0, 1.2)),
            animated = true, desc = "Symétrie radiale parfaite qui tourne lentement : l'image devient un mandala vivant") {
            """
            float ar = uResolution.x / uResolution.y;
            vec2 d = (uv - 0.5) * vec2(ar, 1.0);
            float r = length(d);
            float a = atan(d.y, d.x) + uTime * uP[1];
            float seg = 6.2831 / floor(uP[0]);
            a = mod(a, seg);
            a = abs(a - seg * 0.5);
            vec2 q = vec2(cos(a), sin(a)) * r / uP[2];
            q = abs(fract(q / vec2(ar, 1.0) + 0.5) * 2.0 - 1.0);
            vec4 c = tex(q);
            float ring = 0.5 + 0.5 * sin(r * 40.0 - uTime * 2.0);
            return vec4(clamp(c.rgb * (0.92 + 0.12 * ring), 0.0, 1.0), c.a);
            """
        },
        fx("gen_shards", "Éclats de verre", C.GENERATIVE, listOf(p("cells", "Éclats", 4.0, 40.0, 12.0), p("offset", "Décalage", 0.0, 0.06, 0.02), p("shine", "Reflets", 0.0, 1.0, 0.6)),
            desc = "L'image vue à travers un miroir brisé : chaque éclat décale et reflète la lumière") {
            """
            float ar = uResolution.x / uResolution.y;
            vec2 g = uv * vec2(uP[0] * ar, uP[0]);
            vec2 id = floor(g); vec2 f = fract(g);
            float d1 = 8.0; float d2 = 8.0; vec2 best = vec2(0.0);
            for (int j = -1; j <= 1; j++) for (int i = -1; i <= 1; i++) {
                vec2 o = vec2(float(i), float(j));
                vec2 pt = o + vec2(hash(id + o + 3.1), hash(id + o + 7.7));
                float d = length(pt - f);
                if (d < d1) { d2 = d1; d1 = d; best = id + o; } else if (d < d2) { d2 = d; }
            }
            vec2 off = (vec2(hash(best), hash(best + 5.0)) - 0.5) * uP[1] * 2.0;
            vec4 c = tex(uv + off);
            float shine = pow(hash(best + 9.0), 6.0) * uP[2] * 0.5;
            float crack = smoothstep(0.03, 0.0, d2 - d1);
            return vec4(clamp(c.rgb + shine + crack * 0.7, 0.0, 1.0), c.a);
            """
        },
        fx("gen_engraving", "Gravure", C.GENERATIVE, listOf(p("density", "Densité des traits", 40.0, 300.0, 140.0), hue("hue", "Encre", 25.0), p("paper", "Papier", 0.0, 1.0, 0.8)),
            desc = "Hachures croisées comme une gravure de billet de banque ou une illustration ancienne") {
            """
            vec4 c = tex(uv);
            float l = luma(c.rgb);
            vec2 q = uv * vec2(uResolution.x / uResolution.y, 1.0) * uP[0];
            float h1 = abs(fract(q.x + q.y) - 0.5);
            float h2 = abs(fract(q.x - q.y) - 0.5);
            float h3 = abs(fract(q.y * 1.5) - 0.5);
            float ink = 0.0;
            ink = max(ink, step(h1, 0.5 - l) * step(l, 0.8));
            ink = max(ink, step(h2, 0.45 - l) * step(l, 0.55));
            ink = max(ink, step(h3, 0.4 - l) * step(l, 0.3));
            vec3 paper = mix(c.rgb, vec3(0.96, 0.93, 0.86), uP[2]);
            return vec4(mix(paper, hueColor(uP[1]) * 0.2, ink), c.a);
            """
        },
        fx("gen_tiles", "Carreaux de céramique", C.GENERATIVE, listOf(p("size", "Taille des carreaux", 8.0, 80.0, 28.0, "px"), p("glaze", "Émail", 0.0, 1.0, 0.6), p("grout", "Joints", 0.0, 1.0, 0.4)),
            desc = "Mosaïque de carreaux émaillés avec joints et reflets de glaçure") {
            """
            vec2 cell = vec2(uP[0]) / uResolution;
            vec2 id = floor(uv / cell);
            vec2 f = fract(uv / cell);
            vec3 col = tex((id + 0.5) * cell).rgb;
            col = floor(col * 6.0 + 0.5) / 6.0;
            float g = 0.04 + uP[2] * 0.08;
            float joint = 1.0 - step(g, f.x) * step(g, f.y) * step(f.x, 1.0 - g) * step(f.y, 1.0 - g);
            float spec = pow(max(0.0, 1.0 - length(f - vec2(0.3, 0.7)) * 2.2), 4.0) * uP[1];
            vec3 tile = col * (0.9 + 0.2 * hash(id)) + spec * 0.6;
            return vec4(clamp(mix(tile, vec3(0.85, 0.83, 0.78), joint), 0.0, 1.0), tex(uv).a);
            """
        },
    )

    // ------------------------------------------------------------------ dans toutes les familles existantes
    private val everywhere = listOf(
        // tendances
        fx("trend_liquid_glass", "Verre liquide", C.TREND, listOf(p("amount", "Réfraction", 0.0, 1.0, 0.5), p("blur", "Givre", 0.0, 12.0, 4.0, "px"), p("speed", "Mouvement", 0.0, 2.0, 0.6), p("shine", "Reflet", 0.0, 1.0, 0.5)),
            animated = true, desc = "La tendance des interfaces : une couche de verre liquide qui réfracte et fait briller l'image") {
            """
            vec2 n = vec2(noise(uv * 3.0 + uTime * uP[2] * 0.3), noise(uv * 3.0 + 7.0 - uTime * uP[2] * 0.25)) - 0.5;
            vec2 q = uv + n * 0.06 * uP[0];
            vec2 px = uP[1] / uResolution;
            vec3 acc = vec3(0.0);
            for (int i = 0; i < 8; i++) {
                float a = float(i) * 0.785398;
                acc += tex(q + vec2(cos(a), sin(a)) * px).rgb;
            }
            acc /= 8.0;
            float rim = pow(max(0.0, n.x + n.y + 0.3), 3.0) * uP[3] * 1.5;
            float sweep = smoothstep(0.08, 0.0, abs(fract((uv.x + uv.y) * 0.5 - uTime * uP[2] * 0.15) - 0.5)) * uP[3] * 0.25;
            return vec4(clamp(acc * 1.04 + rim + sweep, 0.0, 1.0), tex(uv).a);
            """
        },
        fx("trend_aura_blur", "Aura floue", C.TREND, listOf(hue("hue", "Couleur 1", 320.0), hue("hue2", "Couleur 2", 200.0), p("amount", "Intensité", 0.0, 1.0, 0.5), p("blur", "Flou des bords", 0.0, 20.0, 10.0, "px")),
            animated = true, desc = "Portrait « aura » à la mode : bords flous baignés de deux couleurs qui respirent") {
            """
            vec4 c = tex(uv);
            vec2 d = uv - 0.5;
            float r = length(d * vec2(1.0, 1.3));
            float edge = smoothstep(0.2, 0.7, r);
            vec2 px = uP[3] * edge / uResolution;
            vec3 acc = c.rgb;
            for (int i = 0; i < 10; i++) {
                float a = float(i) * 2.39996;
                acc += tex(uv + vec2(cos(a), sin(a)) * px * sqrt(float(i) + 0.5) / 2.2).rgb;
            }
            acc /= 11.0;
            float m = 0.5 + 0.5 * sin(atan(d.y, d.x) * 2.0 + uTime * 0.8);
            vec3 aura = mix(hueColor(uP[0]), hueColor(uP[1]), m);
            return vec4(clamp(mix(acc, acc * 0.5 + aura * 0.6, edge * uP[2] * 1.4), 0.0, 1.0), c.a);
            """
        },
        // couleur
        fx("col_gradient_map", "Carte de dégradé", C.COLOR, listOf(hue("shadow", "Ombres", 250.0), hue("mid", "Tons moyens", 330.0), hue("high", "Lumières", 45.0), p("amount", "Intensité", 0.0, 1.0, 0.8)),
            desc = "Chaque niveau de luminosité reçoit sa couleur : ombres, tons moyens et lumières en trois teintes") {
            """
            vec4 c = tex(uv);
            float l = luma(c.rgb);
            vec3 s = hueColor(uP[0]) * 0.25;
            vec3 m = hueColor(uP[1]) * 0.75;
            vec3 h = mix(hueColor(uP[2]), vec3(1.0), 0.55);
            vec3 g = l < 0.5 ? mix(s, m, l * 2.0) : mix(m, h, l * 2.0 - 1.0);
            return vec4(mix(c.rgb, g, uP[3]), c.a);
            """
        },
        fx("col_film_stock", "Pellicule portrait", C.COLOR, listOf(p("amount", "Intensité", 0.0, 1.0, 0.8), p("warmth", "Chaleur", -1.0, 1.0, 0.3), p("fade", "Noirs délavés", 0.0, 0.3, 0.08)),
            desc = "Émulation d'une pellicule couleur pour portraits : peaux douces, verts adoucis, noirs légèrement levés") {
            """
            vec4 c = tex(uv);
            vec3 x = c.rgb;
            x = x * x * (3.0 - 2.0 * x) * 0.6 + x * 0.4;
            float l = luma(x);
            vec3 hsv = rgb2hsv(x);
            float skin = smoothstep(0.12, 0.0, abs(hsv.x - 0.06));
            float green = smoothstep(0.1, 0.0, abs(hsv.x - 0.3));
            hsv.y *= 1.0 - green * 0.35 + skin * 0.05;
            x = hsv2rgb(hsv);
            x += vec3(0.04, 0.0, -0.04) * uP[1];
            x = mix(vec3(uP[2]), vec3(1.0 - uP[2] * 0.3), x);
            x = mix(x, vec3(l), 0.06);
            return vec4(mix(c.rgb, clamp(x, 0.0, 1.0), uP[0]), c.a);
            """
        },
        // flou & netteté
        fx("blur_spin", "Flou rotatif", C.BLUR, listOf(p("amount", "Angle", 0.0, 20.0, 6.0, "°"), p("cx", "Centre X", 0.0, 1.0, 0.5), p("cy", "Centre Y", 0.0, 1.0, 0.5)),
            desc = "Flou de rotation autour d'un point, comme une roue ou une caméra qui tourne") {
            """
            vec2 ctr = vec2(uP[1], 1.0 - uP[2]);
            float ar = uResolution.x / uResolution.y;
            vec2 d = (uv - ctr) * vec2(ar, 1.0);
            vec3 acc = vec3(0.0);
            for (int i = 0; i < 16; i++) {
                float a = radians(uP[0]) * (float(i) / 15.0 - 0.5);
                acc += tex(ctr + rotate2(d, a) / vec2(ar, 1.0)).rgb;
            }
            return vec4(acc / 16.0, tex(uv).a);
            """
        },
        fx("blur_frosted", "Verre dépoli", C.BLUR, listOf(p("amount", "Dépoli", 0.0, 20.0, 6.0, "px"), p("clear", "Zone nette", 0.0, 0.5, 0.0)),
            desc = "Comme derrière une vitre de douche : l'image se brouille en grains de verre") {
            """
            vec2 d = uv - 0.5;
            float k = smoothstep(uP[1], uP[1] + 0.15, length(d));
            if (uP[1] <= 0.0) k = 1.0;
            vec2 j = (vec2(hash(uv * 911.0), hash(uv * 577.0 + 3.0)) - 0.5) * uP[0] * 2.0 * k / uResolution;
            vec4 c = tex(uv + j);
            vec4 c2 = tex(uv - j * 0.6);
            return vec4(mix(c.rgb, c2.rgb, 0.5) * (1.0 + 0.04 * k), c.a);
            """
        },
        // stylisé
        fx("sty_comic_ink", "Encrage BD", C.STYLIZE, listOf(p("lines", "Épaisseur de l'encre", 0.5, 4.0, 1.5, "px"), p("levels", "Aplats", 2.0, 8.0, 4.0), p("dots", "Trame", 0.0, 1.0, 0.5)),
            desc = "Contours encrés, aplats de couleurs et trame de points : une case de bande dessinée") {
            """
            vec4 c = tex(uv);
            vec2 px = uP[0] / uResolution;
            float gx = luma(tex(uv + vec2(px.x, 0.0)).rgb) - luma(tex(uv - vec2(px.x, 0.0)).rgb);
            float gy = luma(tex(uv + vec2(0.0, px.y)).rgb) - luma(tex(uv - vec2(0.0, px.y)).rgb);
            float edge = smoothstep(0.1, 0.25, length(vec2(gx, gy)));
            vec3 hsv = rgb2hsv(c.rgb);
            hsv.z = floor(hsv.z * uP[1] + 0.5) / uP[1];
            hsv.y = min(1.0, hsv.y * 1.3);
            vec3 flat1 = hsv2rgb(hsv);
            vec2 g = fract(uv * uResolution / 6.0) - 0.5;
            float dotm = step(length(g), 0.5 * (1.0 - luma(c.rgb))) * uP[2];
            vec3 col = flat1 * (1.0 - dotm * 0.35);
            return vec4(mix(col, vec3(0.03), edge), c.a);
            """
        },
        fx("sty_emboss_metal", "Relief métal", C.STYLIZE, listOf(p("depth", "Profondeur", 0.5, 6.0, 2.0), hue("hue", "Métal", 40.0), p("amount", "Intensité", 0.0, 1.0, 1.0)),
            desc = "L'image frappée dans une plaque de métal brossé, comme une médaille") {
            """
            vec4 c = tex(uv);
            vec2 px = uP[0] / uResolution;
            float e = luma(tex(uv - px).rgb) - luma(tex(uv + px).rgb);
            float brush = noise(vec2(uv.x * 3.0, uv.y * uResolution.y * 0.5)) * 0.08;
            vec3 metal = mix(vec3(0.75), hueColor(uP[1]), 0.35);
            vec3 col = metal * (0.55 + e * 2.5 + brush);
            return vec4(mix(c.rgb, clamp(col, 0.0, 1.0), uP[2]), c.a);
            """
        },
        // rétro
        fx("ret_synth_sun", "Soleil synthwave", C.RETRO, listOf(hue("hue", "Couleur", 320.0), p("size", "Taille", 0.1, 0.6, 0.3), p("y", "Hauteur", 0.0, 1.0, 0.55), p("amount", "Intensité", 0.0, 1.0, 0.8)),
            animated = true, desc = "Un soleil rétro années 80 à bandes, en dégradé néon, se couche derrière l'image") {
            """
            vec4 c = tex(uv);
            float ar = uResolution.x / uResolution.y;
            vec2 ctr = vec2(0.5, 1.0 - uP[2]);
            vec2 d = (uv - ctr) * vec2(ar, 1.0);
            float r = length(d);
            float yy = (uv.y - ctr.y) / uP[1];
            float bands = step(0.5, fract(yy * 8.0 - uTime * 0.4)) + step(0.0, yy);
            float sun = smoothstep(uP[1], uP[1] - 0.005, r) * clamp(bands, 0.0, 1.0);
            vec3 grad = mix(hueColor(uP[0]), vec3(1.0, 0.85, 0.2), clamp(yy * 0.5 + 0.5, 0.0, 1.0));
            float glow = exp(-max(r - uP[1], 0.0) * 8.0) * 0.35;
            float dark = 1.0 - smoothstep(0.15, 0.6, luma(c.rgb));
            return vec4(clamp(c.rgb + (grad * sun + hueColor(uP[0]) * glow) * dark * uP[3], 0.0, 1.0), c.a);
            """
        },
        fx("ret_faded_print", "Tirage années 70", C.RETRO, listOf(p("amount", "Intensité", 0.0, 1.0, 0.8), p("fade", "Délavé", 0.0, 0.4, 0.15), p("border", "Bord blanc", 0.0, 0.08, 0.03)),
            desc = "Vieille photo de famille : couleurs jaunies, noirs délavés, coins arrondis et bord blanc") {
            """
            vec4 c = tex(uv);
            vec3 x = c.rgb;
            x = vec3(x.r * 1.05 + 0.04, x.g * 0.98 + 0.03, x.b * 0.82 + 0.02);
            x = mix(vec3(uP[1]), vec3(0.97, 0.93, 0.85), x);
            float v = smoothstep(0.85, 0.3, length(uv - 0.5));
            x *= 0.85 + 0.15 * v;
            vec2 b = min(uv, 1.0 - uv);
            float border = 1.0 - step(uP[2], min(b.x, b.y));
            vec3 col = mix(c.rgb, x, uP[0]);
            return vec4(mix(col, vec3(0.97, 0.96, 0.92), border), c.a);
            """
        },
        // cinéma
        fx("cin_day_for_night", "Nuit américaine", C.CINEMA, listOf(p("amount", "Intensité", 0.0, 1.0, 0.85), p("moon", "Clair de lune", 0.0, 1.0, 0.5)),
            desc = "La technique des tournages : une scène de jour transformée en nuit bleutée au clair de lune") {
            """
            vec4 c = tex(uv);
            float l = luma(c.rgb);
            vec3 night = vec3(0.12, 0.2, 0.42) * pow(l, 1.4) * 1.6 + vec3(0.6, 0.75, 1.0) * pow(l, 5.0) * uP[1];
            night *= 0.7 + 0.3 * smoothstep(0.9, 0.2, length(uv - 0.5));
            return vec4(mix(c.rgb, clamp(night, 0.0, 1.0), uP[0]), c.a);
            """
        },
        fx("cin_film_gate", "Fenêtre de pellicule", C.CINEMA, listOf(p("amount", "Intensité", 0.0, 1.0, 1.0), p("weave", "Instabilité", 0.0, 1.0, 0.4)),
            animated = true, desc = "On voit les perforations et les bords de la pellicule 35 mm qui défile") {
            """
            float t = floor(uTime * 24.0);
            vec2 j = vec2(0.0, (hash(vec2(t, 1.0)) - 0.5) * 0.004 * uP[1]);
            vec4 c = tex(uv + j);
            float side = 0.07;
            float inGate = step(side, uv.x) * step(uv.x, 1.0 - side);
            float px = uv.x < 0.5 ? uv.x : 1.0 - uv.x;
            float holes = step(0.018, px) * step(px, 0.05) * step(0.35, fract((uv.y + uTime * 0.0 + j.y) * 14.0)) * step(fract((uv.y + j.y) * 14.0), 0.75);
            vec3 strip = vec3(0.06, 0.05, 0.04) + vec3(0.9, 0.85, 0.7) * holes;
            vec3 col = mix(strip, c.rgb, inGate);
            col *= 1.0 - 0.25 * smoothstep(0.0, 0.03, abs(uv.x - side)) * 0.0;
            return vec4(mix(c.rgb, col, uP[0]), c.a);
            """
        },
        // lumière
        fx("lt_caustics", "Caustiques d'eau", C.LIGHT, listOf(p("amount", "Intensité", 0.0, 1.5, 0.6), p("scale", "Taille", 2.0, 20.0, 7.0), p("speed", "Vitesse", 0.0, 3.0, 1.0), hue("hue", "Teinte", 185.0)),
            animated = true, desc = "Reflets dansants de la lumière au fond d'une piscine") {
            """
            vec4 c = tex(uv);
            vec2 q = uv * vec2(uResolution.x / uResolution.y, 1.0) * uP[1];
            float t = uTime * uP[2] * 0.5;
            float v = 0.0;
            for (int i = 0; i < 3; i++) {
                float fi = float(i);
                vec2 r = q * (1.0 + fi * 0.4) + vec2(t * (1.0 + fi * 0.3), -t * 0.8);
                v += pow(abs(sin(r.x + sin(r.y * 1.3 + t)) * sin(r.y + sin(r.x * 1.1 - t))), 6.0);
            }
            vec3 light = mix(vec3(1.0), hueColor(uP[3]), 0.4) * v * 0.6;
            return vec4(clamp(c.rgb * (0.9 + 0.1 * uP[0]) + light * uP[0], 0.0, 1.0), c.a);
            """
        },
        fx("lt_golden_hour", "Heure dorée", C.LIGHT, listOf(p("amount", "Intensité", 0.0, 1.0, 0.7), p("x", "Soleil X", 0.0, 1.0, 0.8), p("y", "Soleil Y", 0.0, 1.0, 0.2), p("haze", "Brume", 0.0, 1.0, 0.4)),
            desc = "Lumière chaude du coucher de soleil, halo rasant et brume dorée") {
            """
            vec4 c = tex(uv);
            float ar = uResolution.x / uResolution.y;
            vec2 s = vec2(uP[1], 1.0 - uP[2]);
            float r = length((uv - s) * vec2(ar, 1.0));
            vec3 warm = c.rgb * vec3(1.1, 0.98, 0.8) + vec3(0.04, 0.02, -0.02);
            vec3 sun = vec3(1.0, 0.7, 0.35) * (exp(-r * 3.0) * 0.6 + exp(-r * 18.0) * 0.6);
            vec3 col = warm + sun * uP[0] + vec3(1.0, 0.8, 0.5) * uP[3] * 0.12 * (1.0 - r);
            return vec4(mix(c.rgb, clamp(col, 0.0, 1.0), uP[0]), c.a);
            """
        },
        // distorsion
        fx("dis_magnifier", "Loupe", C.DISTORT, listOf(p("cx", "Centre X", 0.0, 1.0, 0.5), p("cy", "Centre Y", 0.0, 1.0, 0.5), p("radius", "Rayon", 0.05, 0.5, 0.2), p("zoom", "Grossissement", 1.0, 4.0, 2.0)),
            desc = "Une loupe de verre grossit un détail, avec bord brillant et léger fish-eye") {
            """
            float ar = uResolution.x / uResolution.y;
            vec2 ctr = vec2(uP[0], 1.0 - uP[1]);
            vec2 d = (uv - ctr) * vec2(ar, 1.0);
            float r = length(d);
            float R = uP[2];
            float inside = smoothstep(R, R - 0.004, r);
            float k = r / R;
            vec2 q = ctr + d / vec2(ar, 1.0) / uP[3] * (0.7 + 0.3 * k * k);
            vec4 c = tex(mix(uv, q, inside));
            float rim = smoothstep(0.012, 0.0, abs(r - R));
            float spec = smoothstep(0.03, 0.0, abs(r - R * 0.75)) * step(0.0, d.y) * step(d.x, 0.0) * inside;
            return vec4(clamp(c.rgb * (1.0 - rim * 0.6) + spec * 0.35 + rim * 0.15, 0.0, 1.0), c.a);
            """
        },
        fx("dis_jelly", "Gelée", C.DISTORT, listOf(p("amount", "Mollesse", 0.0, 1.0, 0.5), p("speed", "Vitesse", 0.0, 4.0, 1.5)),
            animated = true, desc = "L'image tremble et rebondit comme de la gelée") {
            """
            vec2 d = uv - 0.5;
            float t = uTime * uP[1] * 3.0;
            vec2 q = 0.5 + d * vec2(1.0 + sin(t) * 0.05 * uP[0], 1.0 - sin(t) * 0.05 * uP[0]);
            q.x += sin(uv.y * 8.0 + t * 1.3) * 0.01 * uP[0];
            q.y += sin(uv.x * 7.0 - t * 1.1) * 0.01 * uP[0];
            return tex(q);
            """
        },
        // mouvement & vitesse
        fx("mot_ghost_trail", "Traînée fantôme", C.MOTION, listOf(hue("angle", "Direction", 0.0), p("length", "Longueur", 0.0, 0.2, 0.06), p("copies", "Opacité des copies", 0.0, 1.0, 0.5), p("speed", "Vibration", 0.0, 3.0, 1.0)),
            animated = true, desc = "Des copies translucides suivent l'image, comme un mouvement trop rapide pour l'œil") {
            """
            vec4 c = tex(uv);
            vec2 dir = vec2(cos(radians(uP[0])), sin(radians(uP[0]))) * uP[1] * (0.8 + 0.2 * sin(uTime * uP[3] * 5.0));
            vec3 acc = vec3(0.0);
            float w = 0.0;
            for (int i = 1; i <= 6; i++) {
                float t = float(i) / 6.0;
                float k = (1.0 - t) * uP[2];
                acc += tex(uv - dir * t).rgb * k;
                w += k;
            }
            return vec4(clamp((c.rgb + acc) / (1.0 + w), 0.0, 1.0) * (1.0 + w * 0.15), c.a);
            """
        },
        fx("mot_rgb_drift", "Dérive RVB", C.MOTION, listOf(p("amount", "Écart", 0.0, 30.0, 10.0, "px"), p("speed", "Vitesse", 0.0, 4.0, 1.0)),
            animated = true, desc = "Les trois couleurs se séparent et tournent autour de l'image en rythme") {
            """
            float t = uTime * uP[1] * 2.0;
            vec2 o = vec2(cos(t), sin(t)) * uP[0] / uResolution;
            vec4 c = tex(uv);
            return vec4(tex(uv + o).r, c.g, tex(uv - o).b, c.a);
            """
        },
        // particules
        fx("part_fireflies", "Lucioles", C.PARTICLES, listOf(p("amount", "Nombre", 0.0, 1.0, 0.6), hue("hue", "Couleur", 60.0), p("size", "Taille", 1.0, 6.0, 2.5, "px"), p("speed", "Vitesse", 0.0, 2.0, 0.6)),
            animated = true, desc = "Des lucioles flottent et clignotent doucement dans la scène") {
            """
            vec4 c = tex(uv);
            vec3 acc = vec3(0.0);
            float ar = uResolution.x / uResolution.y;
            for (int i = 0; i < 24; i++) {
                float fi = float(i);
                float on = step(fi / 24.0, uP[0]);
                vec2 base = vec2(hash(vec2(fi, 1.0)), hash(vec2(fi, 2.0)));
                vec2 pos = base + vec2(sin(uTime * uP[3] * (0.3 + base.y) + fi), cos(uTime * uP[3] * (0.4 + base.x) + fi * 1.7)) * 0.06;
                vec2 d = (uv - pos) * vec2(ar, 1.0) * uResolution.y;
                float blink = 0.5 + 0.5 * sin(uTime * (1.5 + base.x * 2.0) + fi * 3.0);
                acc += hueColor(uP[1] + base.x * 30.0) * exp(-dot(d, d) / (uP[2] * uP[2] * 6.0)) * blink * on;
            }
            return vec4(clamp(c.rgb + acc * 1.4, 0.0, 1.0), c.a);
            """
        },
        fx("part_embers", "Braises", C.PARTICLES, listOf(p("amount", "Densité", 0.0, 1.0, 0.8), p("speed", "Montée", 0.0, 3.0, 1.0), hue("hue", "Couleur", 22.0)),
            animated = true, desc = "Des braises incandescentes s'élèvent et tourbillonnent, comme près d'un feu de camp") {
            """
            vec4 c = tex(uv);
            vec3 acc = vec3(0.0);
            for (int l = 0; l < 3; l++) {
                float fl = float(l);
                float sc = 18.0 + fl * 14.0;
                vec2 q = uv * vec2(sc * uResolution.x / uResolution.y, sc);
                q.y -= uTime * uP[1] * (2.0 + fl);
                q.x += sin(q.y * 0.3 + uTime) * 0.6;
                vec2 id = floor(q); vec2 f = fract(q) - 0.5;
                float h = hash(id + fl * 13.0);
                vec2 off = (vec2(hash(id + 3.0), hash(id + 5.0)) - 0.5) * 0.6;
                float d = length(f - off);
                float spark = step(1.0 - uP[0] * 0.25, h) * smoothstep(0.12, 0.0, d) * (0.5 + 0.5 * sin(uTime * 8.0 + h * 50.0));
                acc += mix(hueColor(uP[2]), vec3(1.0, 0.9, 0.5), h) * spark * (1.0 - fl * 0.25);
            }
            return vec4(clamp(c.rgb + acc * 1.6, 0.0, 1.0), c.a);
            """
        },
        fx("part_petals", "Pétales de sakura", C.PARTICLES, listOf(p("amount", "Densité", 0.0, 1.0, 0.5), p("speed", "Chute", 0.0, 3.0, 1.0), hue("hue", "Couleur", 340.0)),
            animated = true, desc = "Des pétales roses tombent en virevoltant") {
            """
            vec4 c = tex(uv);
            vec3 col = c.rgb;
            for (int l = 0; l < 3; l++) {
                float fl = float(l);
                float sc = 6.0 + fl * 4.0;
                vec2 q = uv * vec2(sc * uResolution.x / uResolution.y, sc);
                q.y += uTime * uP[1] * (0.5 + fl * 0.2);
                q.x += uTime * 0.3 + sin(q.y * 0.8 + fl) * 0.5;
                vec2 id = floor(q); vec2 f = fract(q) - 0.5;
                float h = hash(id + fl * 7.0);
                vec2 p2 = rotate2(f - (vec2(hash(id + 1.0), hash(id + 2.0)) - 0.5) * 0.4, uTime * (1.0 + h * 2.0) + h * 6.0);
                p2.x *= 1.0 + 0.7 * sin(uTime * 3.0 + h * 9.0);
                float petal = smoothstep(0.02, 0.0, length(p2 * vec2(1.0, 2.0)) - 0.12 + abs(p2.y) * 0.4);
                float on = step(1.0 - uP[0] * 0.6, h);
                vec3 pc = mix(hueColor(uP[2]), vec3(1.0), 0.55 + 0.2 * h);
                col = mix(col, pc, petal * on * (1.0 - fl * 0.2));
            }
            return vec4(col, c.a);
            """
        },
        // superposition
        fx("ov_camcorder", "Caméscope REC", C.OVERLAY, listOf(p("amount", "Intensité", 0.0, 1.0, 1.0), p("blink", "Clignotement", 0.0, 1.0, 1.0)),
            animated = true, desc = "Cadre de caméscope : coins, point rouge REC qui clignote, batterie et légère saturation vidéo") {
            """
            vec4 c = tex(uv);
            vec3 col = c.rgb * 1.05 + 0.02;
            vec2 q = uv; float ar = uResolution.x / uResolution.y;
            vec2 m = vec2(0.06, 0.06 * ar);
            vec2 b = min(q, 1.0 - q);
            float cornerX = step(b.x, m.x * 0.5) * step(b.x, m.x * 0.5 + 0.004);
            float lineW = 0.004;
            float hx = step(m.x * 0.5, b.x) * step(b.x, m.x * 0.5 + lineW) * step(b.y, m.x * 2.0) * step(m.x * 0.5, b.y);
            float hy = step(m.x * 0.5, b.y) * step(b.y, m.x * 0.5 + lineW * ar) * step(b.x, m.x * 2.0 / ar * ar) * step(m.x * 0.5, b.x);
            float frame = max(hx, hy) * (1.0 - cornerX * 0.0);
            vec2 dotc = vec2(0.09, 0.9);
            float dot1 = smoothstep(0.016, 0.012, length((q - dotc) * vec2(ar, 1.0))) * (uP[1] > 0.5 ? step(0.5, fract(uTime)) : 1.0);
            vec2 bt = (q - vec2(0.88, 0.9)) * vec2(ar, 1.0);
            float batt = step(abs(bt.x), 0.045) * step(abs(bt.y), 0.018) * (1.0 - step(abs(bt.x), 0.038) * step(abs(bt.y), 0.011));
            batt += step(abs(bt.x + 0.012), 0.022) * step(abs(bt.y), 0.008);
            col = mix(col, vec3(1.0), clamp(frame + batt, 0.0, 1.0) * uP[0]);
            col = mix(col, vec3(1.0, 0.1, 0.1), dot1 * uP[0]);
            return vec4(clamp(col, 0.0, 1.0), c.a);
            """
        },
        fx("ov_glitter", "Paillettes", C.OVERLAY, listOf(p("amount", "Densité", 0.0, 1.0, 0.5), hue("hue", "Couleur", 45.0), p("size", "Taille", 1.0, 6.0, 2.0, "px")),
            animated = true, desc = "Des paillettes scintillent sur les zones claires, comme un maquillage de fête") {
            """
            vec4 c = tex(uv);
            vec2 g = uv * uResolution / (uP[2] * 3.0);
            vec2 id = floor(g); vec2 f = fract(g) - 0.5;
            float h = hash(id);
            float tw = pow(0.5 + 0.5 * sin(uTime * (4.0 + h * 6.0) + h * 40.0), 8.0);
            float star = max(smoothstep(0.08, 0.0, abs(f.x)) * smoothstep(0.5, 0.0, abs(f.y)), smoothstep(0.08, 0.0, abs(f.y)) * smoothstep(0.5, 0.0, abs(f.x)));
            float on = step(1.0 - uP[0] * 0.3, h) * smoothstep(0.25, 0.8, luma(c.rgb) + 0.2);
            vec3 col = mix(hueColor(uP[1] + h * 40.0), vec3(1.0), 0.5) * star * tw * on;
            return vec4(clamp(c.rgb + col * 1.5, 0.0, 1.0), c.a);
            """
        },
        // 3.5 : complète aussi les familles 3.4
        fx("ultra_lens_pro", "Objectif cinéma pro", C.ULTRA, listOf(p("amount", "Intensité", 0.0, 1.0, 0.7), p("breathing", "Respiration de mise au point", 0.0, 1.0, 0.3), p("flare", "Flare anamorphique", 0.0, 1.0, 0.5)),
            animated = true, desc = "Distorsion en barillet douce, vignettage optique, aberration en bord de cadre et flare bleu horizontal") {
            """
            vec2 d = uv - 0.5;
            float r2 = dot(d, d);
            float br = 1.0 + sin(uTime * 0.7) * 0.004 * uP[1];
            vec2 q = 0.5 + d * (1.0 - 0.08 * r2 * uP[0]) * br;
            float ca = r2 * 0.012 * uP[0];
            vec3 col = vec3(tex(q + d * ca).r, tex(q).g, tex(q - d * ca).b);
            vec3 acc = vec3(0.0);
            for (int i = -6; i <= 6; i++) {
                vec3 s = tex(vec2(uv.x + float(i) * 0.03, uv.y)).rgb;
                acc += s * smoothstep(0.8, 1.0, luma(s)) * (1.0 - abs(float(i)) / 7.0);
            }
            col += vec3(0.3, 0.55, 1.0) * acc * 0.12 * uP[2];
            col *= 1.0 - r2 * 0.9 * uP[0];
            return vec4(clamp(col, 0.0, 1.0), tex(uv).a);
            """
        },
        fx("hud_face_scan", "Scan facial", C.HUD, listOf(hue("hue", "Couleur", 160.0), p("amount", "Intensité", 0.0, 1.0, 0.8), p("speed", "Vitesse", 0.0, 3.0, 1.0), p("cx", "Centre X", 0.0, 1.0, 0.5), p("cy", "Centre Y", 0.0, 1.0, 0.4)),
            animated = true, desc = "Maillage de points et ligne de scan qui balaie le visage, comme une reconnaissance faciale") {
            """
            vec4 c = tex(uv);
            float ar = uResolution.x / uResolution.y;
            vec2 ctr = vec2(uP[3], 1.0 - uP[4]);
            vec2 d = (uv - ctr) * vec2(ar, 1.0);
            float oval = length(d * vec2(1.25, 0.95));
            float inside = smoothstep(0.3, 0.28, oval);
            vec2 g = fract(uv * uResolution / 14.0) - 0.5;
            float mesh = smoothstep(0.12, 0.0, length(g)) * inside;
            float scanY = fract(uTime * uP[2] * 0.4);
            float scan = smoothstep(0.012, 0.0, abs((1.0 - uv.y) - mix(ctr.y - 0.3, ctr.y + 0.3, scanY) * 1.0 + 0.0)) * inside;
            float ring = smoothstep(0.006, 0.0, abs(oval - 0.29));
            vec3 h = hueColor(uP[0]);
            vec3 col = c.rgb + h * (mesh * 0.5 * (0.5 + 0.5 * luma(c.rgb)) + scan * 0.9 + ring * 0.8) * uP[1];
            return vec4(clamp(col, 0.0, 1.0), c.a);
            """
        },
        fx("ai_style_transfer", "Style peinture IA", C.AI_VISION, listOf(p("amount", "Intensité", 0.0, 1.0, 0.85), p("strokes", "Taille des touches", 1.0, 8.0, 3.0, "px"), p("vivid", "Couleurs vives", 0.0, 1.0, 0.5)),
            desc = "Rendu « transfert de style » : touches de pinceau orientées selon les formes et couleurs exaltées") {
            """
            vec4 c = tex(uv);
            vec2 px = uP[1] / uResolution;
            float gx = luma(tex(uv + vec2(px.x, 0.0)).rgb) - luma(tex(uv - vec2(px.x, 0.0)).rgb);
            float gy = luma(tex(uv + vec2(0.0, px.y)).rgb) - luma(tex(uv - vec2(0.0, px.y)).rgb);
            vec2 dir = normalize(vec2(-gy, gx) + vec2(0.0001, 0.0));
            vec3 acc = vec3(0.0);
            for (int i = -4; i <= 4; i++) acc += tex(uv + dir * float(i) * px * 0.7).rgb;
            acc /= 9.0;
            vec3 hsv = rgb2hsv(acc);
            hsv.y = min(1.0, hsv.y * (1.0 + uP[2]));
            hsv.z = floor(hsv.z * 7.0 + 0.5) / 7.0 * 0.85 + hsv.z * 0.15;
            vec3 col = hsv2rgb(hsv) * (0.92 + 0.12 * noise(uv * uResolution / (uP[1] * 4.0)));
            return vec4(mix(c.rgb, clamp(col, 0.0, 1.0), uP[0]), c.a);
            """
        },
        fx("dim_hologram_cube", "Cube holographique", C.DIMENSION, listOf(p("speed", "Rotation", 0.0, 3.0, 0.6), p("size", "Taille", 0.2, 1.0, 0.8), hue("hue", "Lueur", 190.0)),
            animated = true, desc = "L'image projetée sur les faces d'un cube 3D qui tourne, avec arêtes lumineuses") {
            """
            float ar = uResolution.x / uResolution.y;
            vec2 d = (uv - 0.5) * vec2(ar, 1.0) / uP[1];
            float a = uTime * uP[0];
            float ca = cos(a); float sa = sin(a);
            vec3 col = tex(uv).rgb * 0.3 + hueColor(uP[2]) * 0.08 * (1.0 - length(d) * 0.5);
            float hit = 0.0;
            for (int f = 0; f < 4; f++) {
                float fa = a + float(f) * 1.5708;
                float nx = sin(fa);
                float cx0 = cos(fa);
                float w = abs(cx0) * 0.5;
                float xc = nx * 0.5;
                float lx = (d.x - xc) / max(w, 0.001);
                float vis = step(0.0, cx0) * step(abs(lx), 1.0) * step(abs(d.y), 0.5 + 0.0 * ca * sa);
                vec2 fuv = vec2(lx * 0.5 + 0.5, d.y + 0.5);
                vec3 s = tex(fuv).rgb * (0.55 + 0.45 * cx0);
                float edge = (smoothstep(0.04, 0.0, 1.0 - abs(lx)) + smoothstep(0.015, 0.0, 0.5 - abs(d.y))) * vis;
                col = mix(col, s, vis * (1.0 - hit));
                col += hueColor(uP[2]) * edge * 0.8;
                hit = max(hit, vis);
            }
            return vec4(clamp(col, 0.0, 1.0), tex(uv).a);
            """
        },
    )

    // ------------------------------------------------------------------ transition shaders (internal)
    private val transitions = listOf(
        // uProgress = visibilité de la couche (0 = invisible, 1 = entière)
        fx("_tr_supernova", "Supernova", C.INTERNAL, listOf(hue("hue", "Couleur", 40.0), p("invert", "Inverser", 0.0, 1.0, 0.0))) {
            """
            vec2 d = (uv - 0.5) * vec2(uResolution.x / uResolution.y, 1.0);
            float r = length(d);
            float maxR = length(vec2(uResolution.x / uResolution.y, 1.0)) * 0.5 + 0.05;
            float front = uProgress * maxR;
            vec4 c = tex(uv - normalize(d + 0.0001) * smoothstep(0.08, 0.0, abs(r - front)) * 0.02);
            float m = 1.0 - smoothstep(front - 0.02, front + 0.02, r);
            float ring = smoothstep(0.08, 0.0, abs(r - front)) * (1.0 - step(0.999, uProgress));
            float flash = (1.0 - abs(uProgress * 2.0 - 1.0)) * 0.6 * (1.0 - step(0.999, uProgress));
            vec3 col = c.rgb + mix(hueColor(uP[0]), vec3(1.0), 0.5) * (ring * 1.6 + flash);
            return vec4(clamp(col, 0.0, 1.0), c.a * max(m, ring));
            """
        },
        fx("_tr_lightning", "Éclair", C.INTERNAL, listOf(hue("hue", "Couleur", 210.0))) {
            """
            vec4 c = tex(uv);
            float y = 1.0 - uv.y;
            float path = 0.5;
            float amp = 0.15; float f = 3.0;
            for (int i = 0; i < 5; i++) { path += (noise(vec2(y * f, 4.0 + float(i))) - 0.5) * amp; amp *= 0.55; f *= 2.1; }
            float side = uv.x - path;
            float open1 = uProgress * 1.2;
            float m = step(abs(side) * 2.0, open1);
            float bolt = 0.002 / (abs(abs(side) * 2.0 - open1) + 0.002) * (1.0 - step(0.999, uProgress));
            float flash = smoothstep(0.3, 0.0, abs(uProgress - 0.15)) * 0.8;
            vec3 col = c.rgb + mix(hueColor(uP[0]), vec3(1.0), 0.6) * (bolt + flash);
            return vec4(clamp(col, 0.0, 1.0), c.a * max(m, min(bolt, 1.0)));
            """
        },
        fx("_tr_shatter", "Bris de verre", C.INTERNAL, listOf(p("cells", "Éclats", 4.0, 30.0, 10.0))) {
            """
            float ar = uResolution.x / uResolution.y;
            float k = 1.0 - uProgress;
            vec2 g = uv * vec2(uP[0] * ar, uP[0]);
            vec2 id = floor(g); vec2 f = fract(g);
            float d1 = 8.0; float d2 = 8.0; vec2 best = vec2(0.0);
            for (int j = -1; j <= 1; j++) for (int i = -1; i <= 1; i++) {
                vec2 o = vec2(float(i), float(j));
                vec2 pt = o + vec2(hash(id + o), hash(id + o + 11.0));
                float d = length(pt - f);
                if (d < d1) { d2 = d1; d1 = d; best = id + o; } else if (d < d2) { d2 = d; }
            }
            float h = hash(best + 2.0);
            float delay = h * 0.5;
            float t = clamp((k - delay) / 0.5, 0.0, 1.0);
            vec2 fall = vec2((hash(best + 4.0) - 0.5) * 0.4, -1.2) * t * t;
            vec4 c = tex(uv - fall);
            float crack = smoothstep(0.03, 0.0, d2 - d1) * step(0.001, k);
            float vis = 1.0 - step(0.999, t);
            return vec4(clamp(c.rgb + crack * 0.8, 0.0, 1.0), c.a * vis);
            """
        },
        fx("_tr_liquid", "Morphing liquide", C.INTERNAL, listOf(p("amount", "Viscosité", 0.1, 1.0, 0.7))) {
            """
            float k = 1.0 - uProgress;
            vec2 n = vec2(noise(uv * 4.0 + 3.0), noise(uv * 4.0 + 9.0)) - 0.5;
            vec4 c = tex(uv + n * k * 0.25 * uP[0]);
            float drip = noise(vec2(uv.x * 8.0, 1.0)) * 0.4;
            float m = smoothstep(0.0, 0.15, uProgress * 1.4 - (1.0 - uv.y) * 0.5 - drip * 0.5 + 0.05);
            return vec4(c.rgb, c.a * m);
            """
        },
        fx("_tr_cells", "Cellules", C.INTERNAL, listOf(p("cells", "Cellules", 4.0, 40.0, 14.0))) {
            """
            float ar = uResolution.x / uResolution.y;
            vec2 g = uv * vec2(uP[0] * ar, uP[0]);
            vec2 id = floor(g); vec2 f = fract(g);
            float d1 = 8.0; vec2 best = vec2(0.0);
            for (int j = -1; j <= 1; j++) for (int i = -1; i <= 1; i++) {
                vec2 o = vec2(float(i), float(j));
                vec2 pt = o + vec2(hash(id + o + 1.3), hash(id + o + 8.1));
                float d = length(pt - f);
                if (d < d1) { d1 = d; best = id + o; }
            }
            float h = hash(best + 6.0) * 0.6 + 0.2;
            float grow = clamp((uProgress - h + 0.25) / 0.25, 0.0, 1.0);
            float m = step(d1, grow * 1.5);
            vec4 c = tex(uv);
            return vec4(c.rgb, c.a * max(m, step(0.999, uProgress)));
            """
        },
        fx("_tr_blackhole", "Trou noir", C.INTERNAL, listOf(p("turns", "Tours", 0.25, 3.0, 1.0))) {
            """
            float k = 1.0 - uProgress;
            vec2 d = (uv - 0.5) * vec2(uResolution.x / uResolution.y, 1.0);
            float r = length(d);
            float s = 1.0 + k * k * 6.0;
            vec2 q = rotate2(d * s, k * uP[0] * 6.2831 * (1.0 - min(r * 1.5, 1.0)));
            vec4 c = tex(q / vec2(uResolution.x / uResolution.y, 1.0) + 0.5);
            float hole = smoothstep(0.0, 0.08 + uProgress * 0.4, r) ;
            float vis = mix(hole, 1.0, uProgress);
            return vec4(c.rgb * vis, c.a * (1.0 - k * k));
            """
        },
    )
}
