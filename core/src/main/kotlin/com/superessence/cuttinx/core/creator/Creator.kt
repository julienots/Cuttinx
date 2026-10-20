package com.superessence.cuttinx.core.creator

import com.superessence.cuttinx.core.brand.BrandKit
import com.superessence.cuttinx.core.gfx.Gfx
import com.superessence.cuttinx.core.gfx.col
import com.superessence.cuttinx.core.gfx.fill
import com.superessence.cuttinx.core.gfx.linear
import com.superessence.cuttinx.core.gfx.radial
import com.superessence.cuttinx.core.gfx.shaded
import com.superessence.cuttinx.core.gfx.stroke
import com.superessence.cuttinx.core.overlay.*
import com.superessence.cuttinx.core.overlay.render.Motifs
import com.superessence.cuttinx.core.overlay.render.OverlayRenderer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.hypot
import kotlin.math.max

/**
 * Formats of the "Studio Créateur": the visuals a creator needs on every network, at the exact
 * size each platform asks for. [safe] is the area always visible (fractions x, y, w, h) — e.g. the
 * centre of a YouTube banner, the part of a TikTok cover not hidden by the buttons.
 */
@Serializable
enum class SocialFormat(val label: String, val platform: String, val width: Int, val height: Int, val safe: List<Float>? = null, val safeLabel: String = "") {
    YT_THUMB("Miniature YouTube", "YouTube", 1280, 720, listOf(0f, 0f, 0.84f, 0.86f), "la durée s'affiche en bas à droite"),
    YT_BANNER("Bannière YouTube", "YouTube", 2560, 1440, listOf(0.198f, 0.353f, 0.604f, 0.294f), "zone visible sur tous les écrans"),
    TIKTOK("Couverture TikTok / Reels / Shorts", "TikTok", 1080, 1920, listOf(0.06f, 0.12f, 0.74f, 0.62f), "hors boutons et légende"),
    IG_POST("Post carré", "Instagram", 1080, 1080),
    IG_PORTRAIT("Post portrait 4:5", "Instagram", 1080, 1350),
    STORY("Story", "Instagram", 1080, 1920, listOf(0f, 0.13f, 1f, 0.74f), "hors barre du haut et réponse"),
    TWITCH_BANNER("Bannière de profil Twitch", "Twitch", 1200, 480),
    TWITCH_OFFLINE("Écran hors ligne Twitch", "Twitch", 1920, 1080),
    PROFILE("Photo de profil", "Tous", 800, 800, listOf(0.146f, 0.146f, 0.708f, 0.708f), "cercle visible"),
    X_HEADER("En-tête X / Twitter", "X", 1500, 500, listOf(0f, 0.18f, 1f, 0.64f), "zone visible"),
    LINKEDIN("Bannière LinkedIn", "LinkedIn", 1584, 396),
    PODCAST("Pochette podcast / musique", "Spotify", 1400, 1400);

    val ratio get() = width.toFloat() / height
}

@Serializable
enum class BgKind(val label: String) { GRADIENT("Dégradé"), SOLID("Couleur"), MOTIF("Motif animé"), IMAGE("Photo"), SPLIT("Diagonale") }

@Serializable
data class DesignBg(
    val kind: BgKind = BgKind.GRADIENT,
    /** Main colour (null = palette). */
    val color: Long? = null,
    val color2: Long? = null,
    val motif: Motif = Motif.SPARKLES,
    /** Background photo (base64) and its height / width ratio. */
    val image: String = "",
    val imageAspect: Float = 1f,
    /** Darkens the photo so the texts stay readable (0 … 0.8). */
    val dim: Float = 0.25f,
    /** Soft light in the corners + vignette. */
    val vignette: Boolean = true,
)

/** One visual of the Studio Créateur: a format, a background and free elements. */
@Serializable
data class CreatorDesign(
    val id: String,
    val name: String,
    val format: SocialFormat,
    val themeId: String = OverlayThemes.all.first().id,
    val palette: OverlayPalette? = null,
    val bg: DesignBg = DesignBg(),
    val items: List<OverlayItem> = emptyList(),
    val updatedAt: Long = 0,
) {
    val theme get() = OverlayThemes[themeId]
    val pal get() = palette ?: theme.palette

    /** The design seen as an overlay of its own size: the element editor of the Twitch Editor works on it as is. */
    fun asConfig() = OverlayConfig(themeId = themeId, palette = palette, width = format.width, height = format.height, items = items)

    fun withConfig(c: OverlayConfig) = copy(items = c.items, palette = c.palette, themeId = c.themeId)
}

/** Draws a design (preview and PNG export): background, then the elements. */
class CreatorRenderer(private val renderer: OverlayRenderer = OverlayRenderer()) {
    fun draw(c: Gfx, d: CreatorDesign, w: Float, h: Float, t: Double) {
        val p = d.pal
        val bg = d.bg
        val main = bg.color ?: p.background
        val second = bg.color2 ?: p.backgroundAlt
        when (bg.kind) {
            BgKind.SOLID -> c.rect(0f, 0f, w, h, fill(col(main)))
            BgKind.GRADIENT, BgKind.MOTIF -> {
                c.rect(0f, 0f, w, h, shaded(linear(0f, 0f, w * 0.4f, h, intArrayOf(col(main), col(second)))))
                c.circle(w * 0.5f, h * 0.42f, max(w, h) * 0.6f, shaded(radial(w * 0.5f, h * 0.42f, max(w, h) * 0.6f, intArrayOf(col(p.primary, 0.28f), 0))))
                if (bg.kind == BgKind.MOTIF) Motifs.draw(c, bg.motif, p, w, h, t)
            }
            BgKind.SPLIT -> {
                c.rect(0f, 0f, w, h, fill(col(main)))
                c.path(com.superessence.cuttinx.core.gfx.Path().moveTo(w * 0.58f, 0f).lineTo(w, 0f).lineTo(w, h).lineTo(w * 0.38f, h).closePath(), fill(col(second)))
                c.line(w * 0.58f, 0f, w * 0.38f, h, stroke(col(p.accent), max(4f, w * 0.006f)).glow(w * 0.006f))
            }
            BgKind.IMAGE -> {
                c.rect(0f, 0f, w, h, fill(col(main)))
                val bytes = if (bg.image.isNotEmpty()) LogoMemo.decode(bg.image) else null
                if (bytes != null) {
                    // cover: the photo fills the whole format, centred
                    val ih = w * bg.imageAspect
                    val (dw, dh) = if (ih >= h) w to ih else (h / bg.imageAspect) to h
                    c.save(); c.clipRect(0f, 0f, w, h)
                    c.image(bytes, (w - dw) / 2, (h - dh) / 2, dw, dh)
                    c.restore()
                    if (bg.dim > 0f) c.rect(0f, 0f, w, h, fill(col(0xFF000000, bg.dim.coerceIn(0f, 0.9f))))
                }
            }
        }
        if (bg.vignette) c.rect(0f, 0f, w, h, shaded(radial(w / 2, h / 2, hypot(w, h) / 2, intArrayOf(0, 0, col(0xFF000000, 0.4f)), floatArrayOf(0f, 0.62f, 1f))))
        val theme = d.theme
        for (it in d.items) if (!it.hidden) renderer.drawItem(c, theme, p, it, w, h, t)
    }
}

/** Ready-made layouts, filled with the creator's name, slogan and colours when an identity exists. */
object CreatorTemplates {
    data class Template(val name: String, val build: (SocialFormat, BrandKit?) -> CreatorDesign)

    private fun base(f: SocialFormat, name: String, kit: BrandKit?, bg: DesignBg = DesignBg()) = CreatorDesign(
        OverlayItems.newId(), name, f, themeId = kit?.overlayThemeId ?: OverlayThemes.all.first().id, palette = kit?.overlayPalette(), bg = bg, updatedAt = System.currentTimeMillis(),
    )

    private fun text(t: String, x: Float, y: Float, w: Float, aspect: Float, color: Long? = null, rot: Float = 0f, font: TitleFont? = null, anim: ItemAnim = ItemAnim.NONE) =
        OverlayItem(OverlayItems.newId(), ItemType.TEXT, "Texte", null, text = t, x = x, y = y, w = w, aspect = aspect, color = color, rotation = rot, font = font, border = true, shadow = true, anim = anim)

    private fun sticker(s: Sticker, x: Float, y: Float, w: Float, color: Long? = null, rot: Float = 0f, aspect: Float = 1f) =
        OverlayItem(OverlayItems.newId(), ItemType.STICKER, s.label, null, sticker = s, x = x, y = y, w = w, aspect = aspect, color = color, rotation = rot, glow = true, shadow = true)

    fun forFormat(f: SocialFormat): List<Template> {
        val tall = f.height > f.width * 1.2f
        val wide = f.width > f.height * 2.2f
        val list = ArrayList<Template>()
        list += Template("Vierge") { fm, k -> base(fm, "Nouveau visuel", k) }
        list += Template(if (tall) "Gros titre" else "Choc") { fm, k ->
            val name = k?.brief?.name ?: "MA CHAÎNE"
            base(fm, "Gros titre", k, DesignBg(BgKind.SPLIT)).copy(items = listOf(
                sticker(Sticker.BURST, if (tall) 0.5f else 0.72f, if (tall) 0.42f else 0.45f, if (tall) 0.8f else 0.42f, 0xFFFFCC1F, 8f),
                text("INCROYABLE !", if (tall) 0.5f else 0.36f, if (tall) 0.3f else 0.38f, if (tall) 0.9f else 0.62f, if (tall) 0.3f else 0.24f, 0xFFFFFFFF, -4f, TitleFont.IMPACT),
                text(name.uppercase(), if (tall) 0.5f else 0.3f, if (tall) 0.62f else 0.68f, if (tall) 0.7f else 0.42f, 0.16f, 0xFFFFCC1F, -4f),
                sticker(Sticker.ARROW, if (tall) 0.5f else 0.62f, if (tall) 0.8f else 0.74f, if (tall) 0.3f else 0.16f, 0xFFE53935, if (tall) 90f else -25f, 0.6f),
            ))
        }
        list += Template("Néon") { fm, k ->
            base(fm, "Néon", k, DesignBg(BgKind.MOTIF, motif = Motif.SPARKLES)).copy(items = listOf(
                text((k?.brief?.name ?: "NOUVELLE VIDÉO").uppercase(), 0.5f, 0.45f, if (wide) 0.5f else 0.8f, if (wide) 0.16f else 0.22f, null, 0f, TitleFont.TECH, ItemAnim.NEON).copy(glow = true),
                text(k?.taglines?.firstOrNull() ?: "Abonne-toi pour la suite", 0.5f, if (wide) 0.66f else if (tall) 0.6f else 0.72f, if (wide) 0.42f else 0.7f, 0.1f, null),
                sticker(Sticker.SPARKLE, 0.18f, 0.24f, if (wide) 0.05f else 0.1f, null, 0f),
                sticker(Sticker.SPARKLE, 0.84f, 0.76f, if (wide) 0.04f else 0.08f, null, 20f),
            ))
        }
        list += Template("Citation") { fm, k ->
            base(fm, "Citation", k, DesignBg(BgKind.GRADIENT)).copy(items = listOf(
                text("“", 0.18f, 0.22f, 0.18f, 0.9f, null, 0f, TitleFont.ELEGANT),
                text("Crée ce que tu aimerais voir", 0.5f, 0.48f, 0.84f, 0.14f, 0xFFFFFFFF, 0f, TitleFont.ELEGANT),
                text("@" + (k?.handles?.firstOrNull() ?: "machaine").removePrefix("@"), 0.5f, 0.66f, 0.4f, 0.12f, null),
            ))
        }
        list += Template("Annonce live") { fm, k ->
            base(fm, "Annonce live", k, DesignBg(BgKind.MOTIF, motif = Motif.CONFETTI)).copy(items = listOf(
                sticker(Sticker.CIRCLE, 0.5f, 0.42f, if (wide) 0.16f else if (tall) 0.5f else 0.34f, 0xFFE53935),
                text("LIVE", 0.5f, 0.42f, if (wide) 0.12f else if (tall) 0.36f else 0.24f, 0.45f, 0xFFFFFFFF, 0f, TitleFont.IMPACT, ItemAnim.PULSE),
                text("CE SOIR · 20H", 0.5f, if (tall) 0.66f else 0.72f, if (wide) 0.4f else 0.7f, 0.13f, null),
            ))
        }
        return list
    }
}

/** Saves the designs of the Studio Créateur (one folder each, pictures in their own files). */
class CreatorStore(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = false }

    @Serializable
    private data class Meta(val id: String, val name: String, val format: SocialFormat, val bg: DesignBg, val updatedAt: Long)

    private fun folder(id: String) = File(dir, id.replace(Regex("[^A-Za-z0-9_]"), "_"))

    @Synchronized fun list(): List<CreatorDesign> = (dir.listFiles() ?: emptyArray()).filter { it.isDirectory }.mapNotNull { f -> runCatching { load(f) }.getOrNull() }.sortedByDescending { it.updatedAt }

    private fun load(f: File): CreatorDesign? {
        val m = json.decodeFromString(Meta.serializer(), File(f, "design.json").readText())
        val cfg = OverlayStore(f).load() ?: OverlayConfig()
        val bgImage = File(f, "fond.b64").takeIf { it.isFile }?.readText() ?: ""
        return CreatorDesign(m.id, m.name, m.format, cfg.themeId, cfg.palette, m.bg.copy(image = bgImage), cfg.items, m.updatedAt)
    }

    @Synchronized fun save(d: CreatorDesign) {
        val f = folder(d.id).also { it.mkdirs() }
        OverlayStore(f).save(d.asConfig())
        val bgFile = File(f, "fond.b64")
        if (d.bg.image.isEmpty()) bgFile.delete() else if (!bgFile.isFile || bgFile.length() != d.bg.image.length.toLong()) bgFile.writeText(d.bg.image)
        val tmp = File(f, "design.json.tmp")
        tmp.writeText(json.encodeToString(Meta.serializer(), Meta(d.id, d.name, d.format, d.bg.copy(image = ""), d.updatedAt)))
        java.nio.file.Files.move(tmp.toPath(), File(f, "design.json").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    @Synchronized fun delete(id: String) { folder(id).deleteRecursively() }
}
