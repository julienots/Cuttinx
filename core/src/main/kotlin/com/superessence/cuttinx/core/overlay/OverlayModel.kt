package com.superessence.cuttinx.core.overlay

import kotlinx.serialization.Serializable

/** Colours of an overlay theme (0xAARRGGBB). */
@Serializable
data class OverlayPalette(
    val background: Long,
    val backgroundAlt: Long,
    val primary: Long,
    val secondary: Long,
    val accent: Long,
    val text: Long,
    val glow: Long,
)

/** Typographic personality, mapped to a bundled font by each renderer. */
@Serializable
enum class TitleFont { TECH, COMIC, PIXEL, IMPACT, ROUNDED, ELEGANT, SCRIPT, BRUSH, CONDENSED, JAPANESE }

/** Decorative frame drawn around zones (camera, chat, screen) and title plates. */
@Serializable
enum class FrameStyle { HUD, COMIC, PIXEL, NEON, SOFT, ELEGANT, BRUSH, SHARP, BUBBLE, WOOD }

/** Animated background pattern / particles. */
@Serializable
enum class Motif {
    HEX_GRID, CIRCUIT, HUD_RINGS, HALFTONE, SPEED_LINES, PIXEL_STARS, SCANLINES, SUN_GRID, SAKURA, SEIGAIHA,
    HEARTS, SPARKLES, BUBBLES, LEAVES, SNOW, BATS, CONFETTI, FLOWERS, SUN_RAYS, CLOUDS, GRAIN, GALAXY, WAVES, LIGHTNING,
}

@Serializable
enum class ThemeCategory(val label: String) {
    HERO("Super-héros"), GEEK("Geek & rétro"), ANIME("Anime & Japon"), CUTE("Mignon & aesthetic"),
    GAMING("Gaming & pro"), NATURE("Nature & espace"), SEASON("Saisons & fêtes"),
}

/**
 * A complete visual identity for a Twitch channel. Themes are original designs; the names only
 * describe a style (no franchise artwork is reproduced).
 */
@Serializable
data class OverlayTheme(
    val id: String,
    val name: String,
    val category: ThemeCategory,
    val description: String,
    val palette: OverlayPalette,
    val font: TitleFont,
    val frame: FrameStyle,
    val motifs: List<Motif>,
    /** Short decorative word shown on scenes (e.g. "LIVE", "配信"). */
    val badge: String = "LIVE",
    /** Default stinger transition matching the theme. */
    val stinger: StingerStyle = StingerStyle.SHUTTER,
)

/** Scenes a streamer switches between in OBS. */
@Serializable
enum class SceneKind(val label: String, val title: String, val animated: Boolean, val fullBackground: Boolean) {
    STARTING("Le live va commencer", "LE LIVE VA COMMENCER", true, true),
    BRB("Je reviens", "JE REVIENS VITE", true, true),
    PAUSE("Pause", "PETITE PAUSE", true, true),
    TECH("Problème technique", "PROBLÈME TECHNIQUE", true, true),
    ENDING("Fin du live", "MERCI D'AVOIR REGARDÉ", true, true),
    OFFLINE("Hors ligne", "ACTUELLEMENT HORS LIGNE", true, true),
    GAMEPLAY("Jeu (écran + caméra + chat)", "", false, false),
    JUST_CHATTING("Discussion (caméra + chat)", "", false, false),
    SCREEN_ONLY("Écran seul + caméra", "", false, false),
    WEBCAM("Cadre caméra seul", "", false, false),
    CHAT("Cadre du chat seul", "", false, false),
    GOAL("Barre d'objectif", "OBJECTIF FOLLOWS", false, false),
    ALERT("Alerte nouveau follow", "NOUVEAU FOLLOW !", true, false),
    ALERT_SUB("Alerte abonnement", "NOUVEL ABONNÉ !", true, false),
    ALERT_RAID("Alerte raid", "RAID EN APPROCHE !", true, false),
    ALERT_DONATION("Alerte don", "MERCI POUR LE SOUTIEN !", true, false),
    ;

    val isAlert get() = this == ALERT || this == ALERT_SUB || this == ALERT_RAID || this == ALERT_DONATION

    /** Second line of the scene when the streamer did not write one. */
    val defaultSubtitle: String get() = when (this) {
        BRB -> "Le live reprend dans un instant"
        PAUSE -> "On souffle et on revient !"
        TECH -> "On règle un petit souci, on revient très vite"
        ENDING -> "À très vite"
        OFFLINE -> "Prochain live"
        ALERT -> "Bienvenue dans la team !"
        ALERT_SUB -> "s'abonne pour 3 mois"
        ALERT_RAID -> "arrive avec 42 viewers"
        ALERT_DONATION -> "a envoyé 5 €"
        else -> ""
    }
}

/** Shape of the camera window in the layouts. */
@Serializable
enum class CameraShape(val label: String) { THEME("Cadre du thème"), ROUNDED("Arrondi"), CIRCLE("Rond") }

/** Stinger (scene transition) animations, exported as transparent animated PNG for OBS. */
@Serializable
enum class StingerStyle(val label: String) {
    SPIDER_WEB("Toile d'araignée"), SHUTTER("Volets"), PIXEL_DISSOLVE("Pixels"), SAKURA_STORM("Tempête de sakura"),
    COMIC_BURST("Explosion BD"), HEX_WIPE("Hexagones"), HEARTS_RAIN("Pluie de cœurs"), GLITCH("Glitch"),
    PORTAL("Portail"), SNOW_SWIRL("Tourbillon de neige"), LIGHTNING_STRIKE("Éclair"), INK_SPLASH("Éclaboussure d'encre"),
}

/** A rectangular zone in a 1920×1080 overlay (camera, chat, screen...). */
@Serializable
data class Zone(val kind: ZoneKind, val x: Float, val y: Float, val w: Float, val h: Float) {
    val right get() = x + w
    val bottom get() = y + h
}

@Serializable
enum class ZoneKind(val label: String) { SCREEN("Écran / jeu"), CAMERA("Caméra"), CHAT("Chat"), TITLE("Titre"), INFO("Infos"), SOCIALS("Réseaux") }

/** What the streamer types once and finds on every scene. */
@Serializable
data class OverlayConfig(
    val themeId: String = OverlayThemes.all.first().id,
    val channel: String = "MaChaîne",
    val tagline: String = "Bienvenue sur le live !",
    val socials: List<String> = listOf("@machaine"),
    val schedule: String = "Lun · Mer · Ven — 20h",
    /** Camera on the right (default) or on the left in layouts. */
    val cameraRight: Boolean = true,
    val width: Int = 1920,
    val height: Int = 1080,
    /** Optional palette override (e.g. from the brand generator). */
    val palette: OverlayPalette? = null,
    val particles: Boolean = true,
    /** Custom texts per scene (empty = default title). */
    val sceneTitles: Map<SceneKind, String> = emptyMap(),
    /** Custom second lines per scene (empty = default). */
    val sceneSubtitles: Map<SceneKind, String> = emptyMap(),
    // ---- style overrides (null = the theme's choice)
    val font: TitleFont? = null,
    val frame: FrameStyle? = null,
    val motifs: List<Motif>? = null,
    val badge: String? = null,
    /** Size of the big titles (1 = default). */
    val textScale: Float = 1f,
    val cameraShape: CameraShape = CameraShape.THEME,
    /** Channel name on the camera frame. */
    val nameOnCamera: Boolean = true,
    // ---- alerts & widgets
    /** Name shown in the alert previews / exports. */
    val alertUser: String = "Pseudo",
    val goalCurrent: Int = 120,
    val goalTarget: Int = 200,
    /** Titles of the Twitch profile panels (exported as 320×100 images). */
    val panels: List<String> = DEFAULT_PANELS,
) {
    fun titleOf(s: SceneKind) = sceneTitles[s]?.takeIf { it.isNotBlank() } ?: s.title
    fun subtitleOf(s: SceneKind) = sceneSubtitles[s]?.takeIf { it.isNotBlank() } ?: when (s) {
        SceneKind.ENDING -> "À très vite — $schedule"
        SceneKind.OFFLINE -> "Prochain live : $schedule"
        else -> s.defaultSubtitle
    }

    /** The theme with the streamer's style choices applied. */
    fun styled(t: OverlayTheme) = t.copy(
        font = font ?: t.font, frame = frame ?: t.frame, motifs = motifs ?: t.motifs,
        badge = badge?.takeIf { it.isNotBlank() } ?: t.badge,
    )

    companion object {
        val DEFAULT_PANELS = listOf("À PROPOS", "PLANNING", "RÈGLES", "RÉSEAUX", "SOUTENIR", "MON SETUP")
    }
}

/** French names of the style choices (shared by the Android and PC editors). */
object OverlayLabels {
    fun font(f: TitleFont) = when (f) {
        TitleFont.TECH -> "Tech"; TitleFont.COMIC -> "BD"; TitleFont.PIXEL -> "Pixel 8-bit"; TitleFont.IMPACT -> "Impact"
        TitleFont.ROUNDED -> "Arrondie"; TitleFont.ELEGANT -> "Élégante"; TitleFont.SCRIPT -> "Manuscrite"; TitleFont.BRUSH -> "Pinceau"
        TitleFont.CONDENSED -> "Condensée"; TitleFont.JAPANESE -> "Japon"
    }

    fun frame(f: FrameStyle) = when (f) {
        FrameStyle.HUD -> "HUD high-tech"; FrameStyle.COMIC -> "BD"; FrameStyle.PIXEL -> "Pixel"; FrameStyle.NEON -> "Néon"
        FrameStyle.SOFT -> "Doux"; FrameStyle.ELEGANT -> "Élégant"; FrameStyle.BRUSH -> "Pinceau"; FrameStyle.SHARP -> "Anguleux"
        FrameStyle.BUBBLE -> "Bulles & cœurs"; FrameStyle.WOOD -> "Bois cosy"
    }

    fun motif(m: Motif) = when (m) {
        Motif.HEX_GRID -> "Hexagones"; Motif.CIRCUIT -> "Circuits"; Motif.HUD_RINGS -> "Anneaux HUD"; Motif.HALFTONE -> "Trame BD"
        Motif.SPEED_LINES -> "Lignes de vitesse"; Motif.PIXEL_STARS -> "Étoiles pixel"; Motif.SCANLINES -> "Lignes TV"; Motif.SUN_GRID -> "Soleil synthwave"
        Motif.SAKURA -> "Sakura"; Motif.SEIGAIHA -> "Vagues japonaises"; Motif.HEARTS -> "Cœurs"; Motif.SPARKLES -> "Paillettes"
        Motif.BUBBLES -> "Bulles"; Motif.LEAVES -> "Feuilles"; Motif.SNOW -> "Neige"; Motif.BATS -> "Chauves-souris"
        Motif.CONFETTI -> "Confettis"; Motif.FLOWERS -> "Fleurs"; Motif.SUN_RAYS -> "Rayons"; Motif.CLOUDS -> "Nuages"
        Motif.GRAIN -> "Grain"; Motif.GALAXY -> "Galaxie"; Motif.WAVES -> "Vagues"; Motif.LIGHTNING -> "Éclairs"
    }

    /** Roles of the palette, in editing order. */
    val paletteRoles = listOf("Fond", "Fond 2", "Principale", "Secondaire", "Accent", "Texte", "Lueur")

    fun get(p: OverlayPalette, i: Int) = when (i) { 0 -> p.background; 1 -> p.backgroundAlt; 2 -> p.primary; 3 -> p.secondary; 4 -> p.accent; 5 -> p.text; else -> p.glow }

    fun set(p: OverlayPalette, i: Int, c: Long) = when (i) {
        0 -> p.copy(background = c); 1 -> p.copy(backgroundAlt = c); 2 -> p.copy(primary = c); 3 -> p.copy(secondary = c)
        4 -> p.copy(accent = c); 5 -> p.copy(text = c); else -> p.copy(glow = c)
    }

    /** Colour swatches offered in the editors. */
    val swatches = listOf(
        0xFF000000, 0xFF0E0F14, 0xFF1B1D2A, 0xFF2B1F3D, 0xFF3A0D16, 0xFF0D2B3A, 0xFF12301F, 0xFF4A3A2A,
        0xFFFFFFFF, 0xFFF5E6C8, 0xFFFFD6E8, 0xFFD8F3FF, 0xFFE6FFD8, 0xFFB0B4BF,
        0xFFE53935, 0xFFFF6A00, 0xFFFFCC1F, 0xFFFFE14D, 0xFF9CFF3D, 0xFF2FB67C, 0xFF00E5FF, 0xFF3D8BFF,
        0xFF6C4DFF, 0xFFB45CFF, 0xFFFF3D8B, 0xFFFF8AC6, 0xFF9146FF,
    )
}
