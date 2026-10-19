package com.superessence.cuttinx.core.overlay

import com.superessence.cuttinx.core.overlay.FrameStyle as F
import com.superessence.cuttinx.core.overlay.Motif as M
import com.superessence.cuttinx.core.overlay.StingerStyle as S
import com.superessence.cuttinx.core.overlay.ThemeCategory as C
import com.superessence.cuttinx.core.overlay.TitleFont as T

private fun pal(bg: Long, bg2: Long, p: Long, s: Long, a: Long, text: Long = 0xFFFFFFFF, glow: Long = p) = OverlayPalette(bg, bg2, p, s, a, text, glow)

/** Catalogue of original overlay themes. */
object OverlayThemes {
    val all: List<OverlayTheme> = listOf(
        // ---------------------------------------------------------------- super-héros
        OverlayTheme("armor", "Armure high-tech", C.HERO, "Interface HUD rouge et or, réacteur lumineux et anneaux de visée, façon armure de super-héros",
            pal(0xFF120406, 0xFF3A0A0E, 0xFFE1262C, 0xFFF2B33D, 0xFF5CE1FF, glow = 0xFF5CE1FF), T.TECH, F.HUD, listOf(M.HUD_RINGS, M.HEX_GRID), "SYSTÈME EN LIGNE", S.HEX_WIPE),
        OverlayTheme("comics", "Super-héros comics", C.HERO, "Cases de BD, trames, lignes de vitesse et onomatopées explosives",
            pal(0xFF1B3A8C, 0xFF0E1F52, 0xFFFFD21F, 0xFFE8222E, 0xFFFFFFFF, glow = 0xFFFFD21F), T.COMIC, F.COMIC, listOf(M.HALFTONE, M.SPEED_LINES), "POW !", S.COMIC_BURST),
        OverlayTheme("spider", "Toile urbaine", C.HERO, "Nuit en ville, toiles d'araignée et rouge vif pour un héros acrobate",
            pal(0xFF0B0F24, 0xFF1A0A2E, 0xFFE3262F, 0xFF2B59D9, 0xFFFFFFFF), T.IMPACT, F.SHARP, listOf(M.HALFTONE, M.LIGHTNING), "EN ACTION", S.SPIDER_WEB),
        OverlayTheme("dark_knight", "Justicier nocturne", C.HERO, "Noir profond, jaune signal et éclairs d'orage au-dessus de la ville",
            pal(0xFF07080C, 0xFF151821, 0xFFF5C518, 0xFF4A5060, 0xFFFFFFFF), T.CONDENSED, F.SHARP, listOf(M.LIGHTNING, M.GRAIN), "LA NUIT TOMBE", S.LIGHTNING_STRIKE),
        // ---------------------------------------------------------------- geek & rétro
        OverlayTheme("retro8", "Arcade 8-bit", C.GEEK, "Pixels, étoiles clignotantes et couleurs de borne d'arcade",
            pal(0xFF0D0221, 0xFF1F0A3D, 0xFF39FF14, 0xFFFF2E88, 0xFF00E5FF, glow = 0xFF39FF14), T.PIXEL, F.PIXEL, listOf(M.PIXEL_STARS, M.SCANLINES), "INSERT COIN", S.PIXEL_DISSOLVE),
        OverlayTheme("synthwave", "Synthwave 80s", C.GEEK, "Soleil couchant rayé, grille néon et chrome rose",
            pal(0xFF12002B, 0xFF3D0B5C, 0xFFFF3EA5, 0xFF00F0FF, 0xFFFFB800, glow = 0xFFFF3EA5), T.SCRIPT, F.NEON, listOf(M.SUN_GRID, M.PIXEL_STARS), "RETRO WAVE", S.GLITCH),
        OverlayTheme("cyber", "Cyber néon", C.GEEK, "Circuits imprimés, glitch et néons cyan/magenta",
            pal(0xFF020611, 0xFF071B2C, 0xFF00F0FF, 0xFFFF00C8, 0xFFE7FF2E, glow = 0xFF00F0FF), T.TECH, F.NEON, listOf(M.CIRCUIT, M.SCANLINES), "ONLINE", S.GLITCH),
        OverlayTheme("terminal", "Terminal hacker", C.GEEK, "Écran vert phosphore, lignes de code et curseur clignotant",
            pal(0xFF010701, 0xFF031A06, 0xFF33FF66, 0xFF1C8C3A, 0xFFB6FFC9, 0xFFB6FFC9), T.PIXEL, F.SHARP, listOf(M.SCANLINES, M.CIRCUIT), "> LIVE_", S.PIXEL_DISSOLVE),
        // ---------------------------------------------------------------- anime, manga, japon
        OverlayTheme("shonen", "Anime shōnen", C.ANIME, "Lignes d'action, aura enflammée et couleurs explosives",
            pal(0xFF1A0610, 0xFF3D0D12, 0xFFFF6B1A, 0xFFFFD21F, 0xFF2BD9FF), T.IMPACT, F.BRUSH, listOf(M.SPEED_LINES, M.SPARKLES), "PLUS ULTRA !", S.INK_SPLASH),
        OverlayTheme("manga", "Manga noir & blanc", C.ANIME, "Trames, encre et onomatopées comme dans un tome papier",
            pal(0xFFF7F4EC, 0xFFE4E0D4, 0xFF111111, 0xFF555555, 0xFFE8222E, 0xFF111111, 0xFF111111), T.BRUSH, F.COMIC, listOf(M.HALFTONE, M.SPEED_LINES), "ドン！", S.INK_SPLASH),
        OverlayTheme("japan", "Japon traditionnel", C.ANIME, "Vagues seigaiha, sakura qui tombent et vermillon des torii",
            pal(0xFF1B1530, 0xFF2E2347, 0xFFE8483A, 0xFFF5D3C8, 0xFFF2C14E, 0xFFFFF6EC, 0xFFF5B7C5), T.JAPANESE, F.ELEGANT, listOf(M.SEIGAIHA, M.SAKURA), "配信中", S.SAKURA_STORM),
        OverlayTheme("tokyo", "Tokyo by night", C.ANIME, "Néons de Shibuya, pluie fine et enseignes en katakana",
            pal(0xFF070718, 0xFF1A0E33, 0xFFFF2D95, 0xFF22E3FF, 0xFFFFE14D, glow = 0xFFFF2D95), T.JAPANESE, F.NEON, listOf(M.SCANLINES, M.PIXEL_STARS), "ライブ", S.GLITCH),
        // ---------------------------------------------------------------- mignon & aesthetic
        OverlayTheme("kawaii", "Mignon rose", C.CUTE, "Cœurs, bulles et rose bonbon tout doux",
            pal(0xFFFFD6E8, 0xFFFFB3D4, 0xFFFF5FA2, 0xFFFFFFFF, 0xFFA77BFF, 0xFF6B2350, 0xFFFF8FC0), T.ROUNDED, F.BUBBLE, listOf(M.HEARTS, M.BUBBLES), "♡ LIVE ♡", S.HEARTS_RAIN),
        OverlayTheme("pastel", "Pastel bonbon", C.CUTE, "Nuages, étoiles et dégradé lilas / menthe",
            pal(0xFFE8DDFF, 0xFFC9F2E7, 0xFFA48BFF, 0xFF7ED8C3, 0xFFFFB5D8, 0xFF4B3B7A, 0xFFFFFFFF), T.ROUNDED, F.SOFT, listOf(M.CLOUDS, M.SPARKLES), "✦ en direct ✦", S.HEARTS_RAIN),
        OverlayTheme("aesthetic", "Aesthetic beige", C.CUTE, "Tons sable, grain photo, arches et typographie élégante",
            pal(0xFFEFE6DA, 0xFFDCCBB5, 0xFFA67C52, 0xFF7A5C45, 0xFFFFFFFF, 0xFF4A3628, 0xFFFFF4E4), T.ELEGANT, F.ELEGANT, listOf(M.GRAIN, M.FLOWERS), "live session", S.PORTAL),
        OverlayTheme("vaporwave", "Vaporwave", C.CUTE, "Dégradés rose-bleu, statues et grille rétro-futuriste",
            pal(0xFF2B1055, 0xFF7597DE, 0xFFFF71CE, 0xFF01CDFE, 0xFFFFFB96, glow = 0xFFFF71CE), T.SCRIPT, F.NEON, listOf(M.SUN_GRID, M.CLOUDS), "ａｅｓｔｈｅｔｉｃ", S.GLITCH),
        OverlayTheme("cozy", "Cozy café", C.CUTE, "Chocolat chaud, lumières douces et bois pour des lives tranquilles",
            pal(0xFF2A1C14, 0xFF4A3021, 0xFFE8B07A, 0xFFF5E1C8, 0xFFFF9466, 0xFFFFF4E6, 0xFFFFC98A), T.ROUNDED, F.WOOD, listOf(M.SPARKLES, M.GRAIN), "cozy stream", S.PORTAL),
        // ---------------------------------------------------------------- gaming & pro
        OverlayTheme("esport", "Esport pro", C.GAMING, "Angles nets, bandes diagonales et couleurs d'équipe",
            pal(0xFF0A0E1A, 0xFF141B2E, 0xFF00D1FF, 0xFFFF3B5C, 0xFFFFFFFF), T.CONDENSED, F.SHARP, listOf(M.HEX_GRID, M.SPEED_LINES), "GG", S.SHUTTER),
        OverlayTheme("minimal", "Minimal pro", C.GAMING, "Sobre, aéré, une couleur d'accent : idéal pour les lives sérieux",
            pal(0xFF111214, 0xFF1C1E22, 0xFFFFCC1F, 0xFF8A8F99, 0xFFFFFFFF), T.CONDENSED, F.SHARP, listOf(M.GRAIN), "EN DIRECT", S.SHUTTER),
        OverlayTheme("fantasy", "Fantasy médiéval", C.GAMING, "Or ancien, parchemin et étincelles magiques",
            pal(0xFF1A1208, 0xFF3A2812, 0xFFD9A441, 0xFF7A3E1C, 0xFF7FE0FF, 0xFFFFF1D6, 0xFFFFD27A), T.ELEGANT, F.WOOD, listOf(M.SPARKLES, M.GRAIN), "L'AVENTURE COMMENCE", S.PORTAL),
        // ---------------------------------------------------------------- nature & espace
        OverlayTheme("galaxy", "Galaxie", C.NATURE, "Nébuleuses, étoiles filantes et violet cosmique",
            pal(0xFF05031A, 0xFF241052, 0xFF8E6BFF, 0xFF3FD8FF, 0xFFFF7BE5, glow = 0xFFB59CFF), T.TECH, F.NEON, listOf(M.GALAXY, M.SPARKLES), "EN ORBITE", S.PORTAL),
        OverlayTheme("ocean", "Océan", C.NATURE, "Vagues, bulles et bleu lagon apaisant",
            pal(0xFF021B33, 0xFF0B4F6C, 0xFF20C4E0, 0xFFA8F0FF, 0xFFFFD166), T.ROUNDED, F.SOFT, listOf(M.WAVES, M.BUBBLES), "EN PLONGÉE", S.PORTAL),
        OverlayTheme("forest", "Forêt enchantée", C.NATURE, "Vert mousse, lucioles et feuilles qui virevoltent",
            pal(0xFF0B1F14, 0xFF173A26, 0xFF7BD389, 0xFFE9F5DB, 0xFFFFE08A), T.ELEGANT, F.SOFT, listOf(M.LEAVES, M.SPARKLES), "AU CŒUR DES BOIS", S.PORTAL),
        // ---------------------------------------------------------------- saisons & fêtes
        OverlayTheme("spring", "Printemps", C.SEASON, "Fleurs, pétales et verts tendres",
            pal(0xFFEAF7E1, 0xFFFFE3EC, 0xFF6CC56E, 0xFFFF8FB1, 0xFFFFD45C, 0xFF2F4A2A, 0xFFFFFFFF), T.ROUNDED, F.SOFT, listOf(M.FLOWERS, M.SAKURA), "printemps", S.SAKURA_STORM),
        OverlayTheme("summer", "Été", C.SEASON, "Soleil, rayons chauds et ambiance plage",
            pal(0xFFFFB347, 0xFFFF6B6B, 0xFFFFE66D, 0xFF2EC4B6, 0xFFFFFFFF, 0xFF3A1C00, 0xFFFFF3B0), T.ROUNDED, F.SOFT, listOf(M.SUN_RAYS, M.WAVES), "summer vibes", S.SHUTTER),
        OverlayTheme("autumn", "Automne", C.SEASON, "Feuilles orangées, bois et lumière dorée",
            pal(0xFF2B140A, 0xFF5A2A12, 0xFFE8862E, 0xFFC0392B, 0xFFF2D16B, 0xFFFFF1E0, 0xFFFFB25C), T.ELEGANT, F.WOOD, listOf(M.LEAVES, M.GRAIN), "automne", S.SHUTTER),
        OverlayTheme("winter", "Hiver", C.SEASON, "Flocons, givre et bleu glacier",
            pal(0xFF0B1E3A, 0xFF1E3F66, 0xFFBDE6FF, 0xFFFFFFFF, 0xFF7FD1FF, glow = 0xFFDDF3FF), T.ELEGANT, F.SOFT, listOf(M.SNOW, M.SPARKLES), "❄ hiver ❄", S.SNOW_SWIRL),
        OverlayTheme("halloween", "Halloween", C.SEASON, "Chauves-souris, orange citrouille et violet sorcière",
            pal(0xFF120A1A, 0xFF2D1340, 0xFFFF7A1A, 0xFF8E3FFF, 0xFF9CFF3D, glow = 0xFFFF7A1A), T.BRUSH, F.SHARP, listOf(M.BATS, M.GRAIN), "BOUH !", S.SPIDER_WEB),
        OverlayTheme("christmas", "Noël", C.SEASON, "Rouge, vert sapin, neige et étoiles dorées",
            pal(0xFF0E2A1A, 0xFF5A0E14, 0xFFE3262F, 0xFF2E8B57, 0xFFFFD45C, glow = 0xFFFFE9A8), T.SCRIPT, F.SOFT, listOf(M.SNOW, M.SPARKLES), "Joyeux Noël", S.SNOW_SWIRL),
        OverlayTheme("valentine", "Saint-Valentin", C.SEASON, "Cœurs, rouge passion et rose poudré",
            pal(0xFF3A0A1A, 0xFF7A1238, 0xFFFF4D7D, 0xFFFFC2D4, 0xFFFFFFFF, glow = 0xFFFF8FB0), T.SCRIPT, F.BUBBLE, listOf(M.HEARTS, M.SPARKLES), "love live", S.HEARTS_RAIN),
        OverlayTheme("newyear", "Nouvel An", C.SEASON, "Confettis, or et noir chic pour le compte à rebours",
            pal(0xFF0A0A12, 0xFF1F1A0A, 0xFFFFD45C, 0xFFFFFFFF, 0xFFFF5FA2, glow = 0xFFFFE9A8), T.ELEGANT, F.ELEGANT, listOf(M.CONFETTI, M.SPARKLES), "BONNE ANNÉE", S.SHUTTER),
        // ---------------------------------------------------------------- nouveaux univers
        OverlayTheme("horror", "Horreur", C.GAMING, "Rouge sang, brume, éclairs et encre qui coule : pour les jeux qui font peur",
            pal(0xFF070205, 0xFF1F0508, 0xFFB3121E, 0xFF5A0A12, 0xFFE8E2D0, 0xFFF2EDE4, 0xFFFF2A2A), T.BRUSH, F.BRUSH, listOf(M.LIGHTNING, M.GRAIN), "NE CRIE PAS", S.INK_SPLASH),
        OverlayTheme("pirate", "Pirates", C.GAMING, "Bois, or et carte au trésor, cap sur l'aventure",
            pal(0xFF0E1B26, 0xFF2B1D12, 0xFFD9A441, 0xFF8B2E1E, 0xFF3FB8C9, 0xFFFFF1D6, 0xFFFFD27A), T.ELEGANT, F.WOOD, listOf(M.WAVES, M.GRAIN), "À L'ABORDAGE", S.PORTAL),
        OverlayTheme("racing", "Course automobile", C.GAMING, "Damier, vitesse et orange piste pour les jeux de course",
            pal(0xFF0B0B0F, 0xFF1C1C24, 0xFFFF6A00, 0xFFFFFFFF, 0xFF00D1FF), T.CONDENSED, F.SHARP, listOf(M.SPEED_LINES, M.GRAIN), "PLEIN GAZ", S.SHUTTER),
        OverlayTheme("football", "Football", C.GAMING, "Pelouse, craie et tableau de score pour le foot et les jeux de sport",
            pal(0xFF07230F, 0xFF0E3D1D, 0xFF2FD15B, 0xFFFFFFFF, 0xFFFFD21F), T.CONDENSED, F.SHARP, listOf(M.SPEED_LINES, M.CONFETTI), "COUP D'ENVOI", S.SHUTTER),
        OverlayTheme("steampunk", "Steampunk", C.GEEK, "Laiton, engrenages et vapeur rétro-futuriste",
            pal(0xFF1A120A, 0xFF33230F, 0xFFC98B3B, 0xFF6E4A24, 0xFF7FE0D0, 0xFFFFF0D6, 0xFFFFC27A), T.ELEGANT, F.WOOD, listOf(M.CIRCUIT, M.GRAIN), "SOUS PRESSION", S.SHUTTER),
        OverlayTheme("magical", "Magical girl", C.ANIME, "Étoiles, rubans et paillettes pastel façon anime de magie",
            pal(0xFF2A1240, 0xFF4B1F6B, 0xFFFF8AD8, 0xFFFFE680, 0xFF8AE6FF, 0xFFFFFFFF, 0xFFFFB3EC), T.ROUNDED, F.BUBBLE, listOf(M.SPARKLES, M.HEARTS), "✧ magie ✧", S.HEARTS_RAIN),
        OverlayTheme("luxe", "Noir & or", C.CUTE, "Noir profond, or et lignes fines : élégant et premium",
            pal(0xFF070707, 0xFF16130C, 0xFFD4AF37, 0xFF8C7A4A, 0xFFFFFFFF, 0xFFF7EBD0, 0xFFFFE08A), T.ELEGANT, F.ELEGANT, listOf(M.GRAIN, M.SPARKLES), "PRIVATE LIVE", S.PORTAL),
        OverlayTheme("lofi", "Lo-fi nuit", C.CUTE, "Chambre la nuit, violet doux et lumières de la ville pour chiller",
            pal(0xFF151127, 0xFF2A2048, 0xFF9C8CFF, 0xFFFFB38A, 0xFF7FE0FF, 0xFFF1ECFF, 0xFFB9AEFF), T.ROUNDED, F.SOFT, listOf(M.PIXEL_STARS, M.CLOUDS), "lofi beats", S.PORTAL),
        OverlayTheme("jungle", "Jungle", C.NATURE, "Feuillage tropical, vert émeraude et lumière dorée",
            pal(0xFF062014, 0xFF0F3B22, 0xFF3FCF6A, 0xFFFFC94A, 0xFFFF6B4A, 0xFFEFFFF2, 0xFF9BFFB4), T.IMPACT, F.SOFT, listOf(M.LEAVES, M.SUN_RAYS), "EXPLORATION", S.SHUTTER),
    )

    operator fun get(id: String): OverlayTheme = all.firstOrNull { it.id == id } ?: all.first()
    fun byCategory(c: C) = all.filter { it.category == c }
}
