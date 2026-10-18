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
    ENDING("Fin du live", "MERCI D'AVOIR REGARDÉ", true, true),
    GAMEPLAY("Jeu (écran + caméra + chat)", "", false, false),
    JUST_CHATTING("Discussion (caméra + chat)", "", false, false),
    SCREEN_ONLY("Écran seul + caméra", "", false, false),
    WEBCAM("Cadre caméra seul", "", false, false),
    CHAT("Cadre du chat seul", "", false, false),
    ALERT("Alerte (nouveau follow)", "NOUVEAU FOLLOW !", true, false),
}

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
) {
    fun titleOf(s: SceneKind) = sceneTitles[s]?.takeIf { it.isNotBlank() } ?: s.title
}
