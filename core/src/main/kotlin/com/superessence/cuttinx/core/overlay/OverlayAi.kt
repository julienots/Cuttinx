package com.superessence.cuttinx.core.overlay

import java.text.Normalizer

/** One proposal of the keyword assistant: a complete overlay setup and why it was chosen. */
data class AiProposal(val config: OverlayConfig, val themeName: String, val reasons: List<String>)

/**
 * "Mini IA" of the Twitch Editor: from a few keywords ("horreur rouge", "cosy anime pastel",
 * "minecraft", "esport bleu"…) it picks the theme, recolours it, chooses decorations, font and frame,
 * and writes the titles, tagline, badge, panels and emotes. Runs entirely on the device, instantly,
 * and always gives the same proposals for the same words (change the seed for others).
 */
object OverlayAi {
    private fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")

    /** Theme tags: games, genres, moods and synonyms (French + English). */
    private val themeTags: Map<String, List<String>> = mapOf(
        "armor" to listOf("iron", "armure", "robot", "tech", "hud", "marvel", "super heros", "superhero", "futur", "mecha"),
        "comics" to listOf("comics", "bd", "bande dessinee", "super heros", "superhero", "pow", "cartoon", "dessin anime"),
        "spider" to listOf("spider", "araignee", "toile", "web", "acrobate", "ville", "urbain"),
        "dark_knight" to listOf("batman", "chevalier", "nuit", "sombre", "dark", "justicier", "gotham", "orage"),
        "retro8" to listOf("retro", "8bit", "8 bit", "pixel", "arcade", "minecraft", "terraria", "mario", "nes", "snes", "nintendo", "old school"),
        "synthwave" to listOf("synthwave", "80", "annees 80", "outrun", "retrowave", "miami", "neon rose"),
        "cyber" to listOf("cyber", "cyberpunk", "neon", "hacker", "futuriste", "valorant", "apex", "fps", "tech", "sci fi", "science fiction"),
        "terminal" to listOf("code", "dev", "programmation", "hacker", "terminal", "matrix", "linux", "informatique"),
        "shonen" to listOf("shonen", "anime", "naruto", "one piece", "dragon ball", "manga action", "fight", "combat", "baston"),
        "manga" to listOf("manga", "noir et blanc", "encre", "lecture", "scan"),
        "japan" to listOf("japon", "japan", "japonais", "sakura", "zen", "samourai", "samurai", "ghost of tsushima", "traditionnel"),
        "tokyo" to listOf("tokyo", "shibuya", "japon", "anime", "ville", "nuit", "kpop", "jpop"),
        "kawaii" to listOf("mignon", "cute", "kawaii", "rose", "pink", "girly", "coeur", "doux", "animal crossing", "sims"),
        "pastel" to listOf("pastel", "bonbon", "nuage", "doux", "licorne", "kawaii", "lilas"),
        "aesthetic" to listOf("aesthetic", "beige", "minimaliste", "art", "dessin", "lifestyle", "mode", "lecture", "booktok", "chill"),
        "vaporwave" to listOf("vaporwave", "aesthetic", "retro", "rose bleu", "statue"),
        "cozy" to listOf("cozy", "cosy", "chill", "cafe", "detente", "stardew", "calme", "tranquille", "just chatting", "discussion", "talk"),
        "esport" to listOf("esport", "e sport", "competitif", "ranked", "tournoi", "fortnite", "valorant", "cs", "counter strike", "rocket league", "overwatch", "lol", "league of legends", "pro"),
        "minimal" to listOf("minimal", "sobre", "pro", "professionnel", "simple", "business", "podcast", "clean"),
        "fantasy" to listOf("fantasy", "medieval", "rpg", "zelda", "elden ring", "skyrim", "wow", "warcraft", "dragon", "magie", "donjon", "baldur", "lol", "league of legends"),
        "galaxy" to listOf("galaxie", "espace", "space", "etoile", "cosmos", "star", "planete", "astronomie", "no man sky", "starfield"),
        "ocean" to listOf("ocean", "mer", "plage", "eau", "subnautica", "bleu", "plongee", "poisson"),
        "forest" to listOf("foret", "nature", "vert", "bois", "enchante", "zelda", "fee", "ghibli"),
        "spring" to listOf("printemps", "spring", "fleur", "avril", "mai", "paques"),
        "summer" to listOf("ete", "summer", "soleil", "plage", "vacances", "juillet", "aout"),
        "autumn" to listOf("automne", "autumn", "fall", "octobre", "novembre", "feuilles"),
        "winter" to listOf("hiver", "winter", "neige", "froid", "decembre", "janvier", "glace", "ski"),
        "halloween" to listOf("halloween", "citrouille", "sorciere", "chauve souris", "octobre", "bouh"),
        "christmas" to listOf("noel", "christmas", "sapin", "cadeau", "decembre", "fete"),
        "valentine" to listOf("saint valentin", "valentin", "amour", "love", "fevrier", "couple"),
        "newyear" to listOf("nouvel an", "new year", "reveillon", "31 decembre", "fete", "paillettes"),
        "horror" to listOf("horreur", "horror", "peur", "flippant", "phasmophobia", "resident evil", "outlast", "zombie", "sang", "dead by daylight", "fnaf", "creepy", "survival horror"),
        "pirate" to listOf("pirate", "sea of thieves", "tresor", "bateau", "one piece", "mer", "aventure"),
        "racing" to listOf("course", "racing", "voiture", "auto", "forza", "gran turismo", "f1", "mario kart", "gta", "vitesse", "drift"),
        "football" to listOf("foot", "football", "fifa", "ea fc", "fc 25", "sport", "match", "stade", "ballon", "pes"),
        "steampunk" to listOf("steampunk", "engrenage", "vapeur", "victorien", "bioshock", "laiton"),
        "magical" to listOf("magical girl", "magie", "etoile", "sailor", "precure", "anime fille", "paillette", "fee"),
        "luxe" to listOf("luxe", "luxury", "or", "gold", "premium", "elegant", "chic", "noir et or", "poker", "casino"),
        "lofi" to listOf("lofi", "lo fi", "chill", "etude", "study", "musique", "nuit", "relax", "calme", "dessin", "art"),
        "jungle" to listOf("jungle", "tropical", "aventure", "exploration", "ark", "dinosaure", "uncharted", "tomb raider", "survie"),
    )

    private val colorWords: List<Pair<List<String>, Long>> = listOf(
        listOf("rouge", "red", "sang") to 0xFFE53935, listOf("orange") to 0xFFFF7A1A, listOf("jaune", "yellow") to 0xFFFFD21F,
        listOf("or", "gold", "dore") to 0xFFD4AF37, listOf("vert", "green") to 0xFF2FD15B, listOf("cyan", "turquoise") to 0xFF00E5FF,
        listOf("bleu", "blue") to 0xFF3D8BFF, listOf("violet", "purple", "mauve") to 0xFF9146FF, listOf("rose", "pink") to 0xFFFF5FA2,
        listOf("blanc", "white") to 0xFFF2F2F2, listOf("noir", "black") to 0xFF111111,
    )

    private val motifWords: List<Pair<List<String>, Motif>> = listOf(
        listOf("neige", "snow", "flocon") to Motif.SNOW, listOf("coeur", "heart", "love") to Motif.HEARTS, listOf("etoile", "star") to Motif.PIXEL_STARS,
        listOf("paillette", "sparkle", "brillant") to Motif.SPARKLES, listOf("sakura", "cerisier") to Motif.SAKURA, listOf("feuille", "leaf") to Motif.LEAVES,
        listOf("bulle", "bubble") to Motif.BUBBLES, listOf("confetti", "fete") to Motif.CONFETTI, listOf("eclair", "orage", "lightning") to Motif.LIGHTNING,
        listOf("galaxie", "nebuleuse") to Motif.GALAXY, listOf("vague", "wave") to Motif.WAVES, listOf("fleur", "flower") to Motif.FLOWERS,
        listOf("nuage", "cloud") to Motif.CLOUDS, listOf("chauve souris", "bat") to Motif.BATS, listOf("hexagone") to Motif.HEX_GRID,
        listOf("circuit") to Motif.CIRCUIT, listOf("pixel") to Motif.PIXEL_STARS, listOf("vitesse", "speed") to Motif.SPEED_LINES,
    )

    private class Mood(val words: List<String>, val font: TitleFont?, val frame: FrameStyle?, val taglines: List<String>, val titles: Map<SceneKind, String>, val badge: String?)

    private val moods = listOf(
        Mood(listOf("horreur", "horror", "peur", "flippant", "creepy", "zombie"), TitleFont.BRUSH, FrameStyle.BRUSH,
            listOf("Ce soir, on a peur ensemble", "Éteins la lumière…", "Survivre jusqu'au bout du live"),
            mapOf(SceneKind.STARTING to "LE CAUCHEMAR COMMENCE", SceneKind.BRB to "NE BOUGE PAS…", SceneKind.ENDING to "VOUS AVEZ SURVÉCU"), "NE CRIE PAS"),
        Mood(listOf("cosy", "cozy", "chill", "calme", "detente", "relax", "lofi", "lo fi"), TitleFont.ROUNDED, FrameStyle.SOFT,
            listOf("Pose-toi, on est bien ici", "Un plaid, un thé et du live", "Ambiance douce ce soir"),
            mapOf(SceneKind.STARTING to "ON SE POSE ENSEMBLE", SceneKind.BRB to "PETIT THÉ, JE REVIENS", SceneKind.ENDING to "BONNE NUIT LA TEAM"), "cozy live"),
        Mood(listOf("esport", "competitif", "ranked", "tournoi", "pro", "fps"), TitleFont.CONDENSED, FrameStyle.SHARP,
            listOf("Objectif : top 1", "Focus, aim, victoire", "Ranked jusqu'au sommet"),
            mapOf(SceneKind.STARTING to "WARM-UP EN COURS", SceneKind.BRB to "PAUSE TACTIQUE", SceneKind.ENDING to "GG WP À TOUS"), "RANKED"),
        Mood(listOf("mignon", "cute", "kawaii", "rose", "pastel", "doux"), TitleFont.ROUNDED, FrameStyle.BUBBLE,
            listOf("Bienvenue dans mon petit monde", "Que des good vibes ici ♡", "Câlins virtuels pour tout le monde"),
            mapOf(SceneKind.STARTING to "LE LIVE ARRIVE ♡", SceneKind.BRB to "JE REVIENS VITE ♡", SceneKind.ENDING to "MERCI LES CHOUPIS"), "♡ live ♡"),
        Mood(listOf("retro", "8bit", "pixel", "arcade", "minecraft"), TitleFont.PIXEL, FrameStyle.PIXEL,
            listOf("Press start pour jouer", "Old school mais jamais old", "Un crédit, une partie"),
            mapOf(SceneKind.STARTING to "PRESS START", SceneKind.BRB to "PAUSE", SceneKind.ENDING to "GAME OVER"), "INSERT COIN"),
        Mood(listOf("anime", "manga", "japon", "shonen", "otaku"), TitleFont.JAPANESE, null,
            listOf("Nakama du soir, bienvenue", "Mode anime activé", "Un épisode de plus ?"),
            mapOf(SceneKind.STARTING to "L'ÉPISODE VA COMMENCER", SceneKind.BRB to "À SUIVRE…", SceneKind.ENDING to "FIN DE L'ÉPISODE"), "配信中"),
        Mood(listOf("luxe", "chic", "elegant", "premium", "or", "gold"), TitleFont.ELEGANT, FrameStyle.ELEGANT,
            listOf("Une soirée d'exception", "Entrée sur invitation", "Le live, version premium"),
            mapOf(SceneKind.STARTING to "LA SOIRÉE VA COMMENCER", SceneKind.ENDING to "MERCI POUR CETTE SOIRÉE"), "PRIVATE LIVE"),
        Mood(listOf("fun", "drole", "humour", "delire", "fou"), TitleFont.COMIC, FrameStyle.COMIC,
            listOf("Ici on rigole, on rage, on recommence", "Zéro sérieux, 100 % fun", "Le chat a toujours raison"),
            mapOf(SceneKind.STARTING to "ÇA VA PARTIR EN LIVE", SceneKind.BRB to "PAUSE PIPI", SceneKind.ENDING to "C'ÉTAIT N'IMPORTE QUOI"), "LOL"),
    )

    private val genericTaglines = listOf("Bienvenue sur le live !", "Installe-toi, ça commence", "Merci d'être là ce soir", "La team est au complet")

    /** Up to [count] proposals for the description [prompt]. */
    fun propose(prompt: String, base: OverlayConfig, count: Int = 3, seed: Int = 0): List<AiProposal> {
        val q = " " + norm(prompt).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ") + " "
        fun has(w: String) = q.contains(" ${norm(w)} ") || (w.length >= 5 && q.contains(norm(w)))
        // score every theme
        val scored = OverlayThemes.all.map { t ->
            val tags = themeTags[t.id].orEmpty() + norm(t.name).split(" ") + listOf(norm(t.category.label))
            var sc = tags.count { has(it) } * 3
            sc += norm(t.description).split(Regex("[^a-z0-9]+")).count { it.length >= 5 && has(it) }
            t to sc
        }.sortedByDescending { it.second }
        val rnd = java.util.Random(q.hashCode() * 31L + seed)
        val best = scored.filter { it.second > 0 }.map { it.first }.ifEmpty { scored.map { it.first }.shuffled(rnd) }
        val color = colorWords.firstOrNull { (ws, _) -> ws.any { has(it) } }?.second
        val motifs = motifWords.filter { (ws, _) -> ws.any { has(it) } }.map { it.second }.distinct().take(2)
        val mood = moods.firstOrNull { m -> m.words.any { has(it) } }
        val picks = (best + scored.map { it.first }).distinct().drop(seed % 3).take(count).ifEmpty { listOf(OverlayThemes.all.first()) }
        return picks.mapIndexed { i, th ->
            val reasons = ArrayList<String>()
            val matched = (themeTags[th.id].orEmpty()).filter { has(it) }
            reasons += if (matched.isNotEmpty()) "Thème « ${th.name} » pour : ${matched.take(3).joinToString(", ")}" else "Thème « ${th.name} » : ${th.description}"
            var palette: OverlayPalette? = null
            if (color != null) {
                palette = recolor(th.palette, color)
                reasons += "Couleur principale adaptée à ta demande"
            }
            if (motifs.isNotEmpty()) reasons += "Décors : " + motifs.joinToString(", ") { OverlayLabels.motif(it) }
            mood?.let { reasons += "Ambiance détectée : textes et style adaptés" }
            val tagline = (mood?.taglines ?: genericTaglines)[(i + seed + rnd.nextInt(3)) % (mood?.taglines ?: genericTaglines).size]
            val cfg = base.copy(
                themeId = th.id, palette = palette,
                motifs = if (motifs.isNotEmpty()) (motifs + th.motifs).distinct().take(2) else null,
                font = if (i == 0) mood?.font else null, frame = if (i == 0) mood?.frame else null,
                badge = mood?.badge, tagline = tagline,
                sceneTitles = mood?.titles ?: emptyMap(), sceneSubtitles = emptyMap(),
                panels = panelsFor(q), emotes = emotesFor(mood, has = ::has),
            )
            AiProposal(cfg, th.name, reasons)
        }
    }

    private fun panelsFor(q: String): List<String> {
        val p = mutableListOf("À PROPOS", "PLANNING", "RÈGLES", "RÉSEAUX")
        if (q.contains("setup") || q.contains("pc")) p += "MON SETUP"
        if (q.contains("discord")) p += "DISCORD"
        p += "SOUTENIR"
        if (q.contains("jeu") || q.contains("game") || q.contains("gaming")) p += "MES JEUX"
        return p.distinct()
    }

    private fun emotesFor(mood: Mood?, has: (String) -> Boolean): List<String> = when {
        mood == null -> OverlayConfig.DEFAULT_EMOTES
        has("horreur") || has("peur") -> listOf("PEUR", "AAAH", "GG", "RIP", "LOL", "HYPE")
        has("mignon") || has("kawaii") || has("cute") -> listOf("LOVE", "AWW", "MERCI", "HUG", "WOW", "GG")
        has("esport") || has("competitif") -> listOf("GG", "EZ", "CLUTCH", "HYPE", "GL", "WP")
        else -> OverlayConfig.DEFAULT_EMOTES
    }

    /** Puts [color] as primary / glow, keeps the theme's backgrounds, derives a readable accent. */
    private fun recolor(p: OverlayPalette, color: Long): OverlayPalette {
        // accent = the colour mixed half-way with white (stays in the same family, readable on dark)
        fun ch(sh: Int) = ((((color shr sh) and 0xFF) + 255) / 2) and 0xFF
        val accent = 0xFF000000L or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        return p.copy(primary = color, glow = color, accent = if (color == 0xFF111111) p.accent else accent)
    }
}
