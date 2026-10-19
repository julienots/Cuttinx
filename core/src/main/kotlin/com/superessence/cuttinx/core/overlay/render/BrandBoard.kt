package com.superessence.cuttinx.core.overlay.render

import com.superessence.cuttinx.core.brand.BrandKit
import com.superessence.cuttinx.core.gfx.*

/** Visual summary of a brand kit (palette, monogram, fonts, taglines), exported as a PNG "planche". */
class BrandBoard {

    /** Typeface matching a font name of the kit (bundled fonts, Montserrat otherwise). */
    fun typeface(name: String): FontId {
        val file = when {
            name.startsWith("Playfair") -> FontId.ELEGANT
            name.startsWith("Righteous") -> FontId.TECH
            name.startsWith("Press Start") -> FontId.PIXEL
            name.startsWith("Anton") -> FontId.IMPACT
            name.startsWith("Bangers") -> FontId.COMIC
            name.startsWith("Fredoka") -> FontId.ROUNDED
            name.startsWith("Pacifico") -> FontId.SCRIPT
            name.startsWith("Caveat") || name.startsWith("Permanent") -> FontId.BRUSH
            name.startsWith("Bebas") || name.startsWith("Oswald") -> FontId.CONDENSED
            else -> null
        }
        return file ?: FontId.BOLD
    }

    /** Round monogram logo (profile picture) of [size] px centred on (cx, cy). */
    fun drawMonogram(c: Gfx, kit: BrandKit, cx: Float, cy: Float, size: Float) {
        val pal = kit.palette.associateBy { it.role }
        val prim = pal["Principale"]!!.argb; val acc = pal["Accent"]!!.argb; val bg = pal["Fond"]!!.argb
        val r = size / 2
        c.circle(cx, cy, r, shaded(linear(cx - r, cy - r, cx + r, cy + r, intArrayOf(col(prim), col(acc)))))
        c.circle(cx, cy, r * 0.86f, fill(col(bg)))
        c.circle(cx, cy, r * 0.86f, stroke(col(prim), r * 0.04f))
        val tf = typeface(kit.fonts.title)
        val s = c.txt.fit(kit.monogram, tf, r * 0.9f, r * 1.3f)
        c.txt.draw(kit.monogram, cx, c.txt.baselineFor(cy, tf, s), tf, s, fill(col(prim)))
    }

    fun draw(c: Gfx, kit: BrandKit, w: Float = 1600f, h: Float = 1000f) {
        val pal = kit.palette.associateBy { it.role }
        val bg = pal["Fond"]!!.argb; val bg2 = pal["Fond secondaire"]!!.argb; val ink = pal["Texte"]!!.argb; val prim = pal["Principale"]!!.argb
        c.rect(0f, 0f, w, h, shaded(linear(0f, 0f, w, h, intArrayOf(col(bg), col(bg2)))))
        val titleTf = typeface(kit.fonts.title); val bodyTf = typeface(kit.fonts.body).let { if (it == FontId.BOLD) FontId.REGULAR else it }
        // header
        drawMonogram(c, kit, 150f, 150f, 200f)
        c.txt.draw(kit.brief.name, 290f, 150f, titleTf, c.txt.fit(kit.brief.name, titleTf, 92f, w - 340f), fill(col(ink)), Align.LEFT)
        c.txt.draw("${kit.archetype} · ${kit.brief.niche.label}", 294f, 205f, FontId.BOLD, 30f, fill(col(prim)), Align.LEFT)
        // palette
        val sw = (w - 120f) / kit.palette.size
        kit.palette.forEachIndexed { i, bc ->
            val x = 60f + i * sw
            c.rrect(x + 8f, 300f, sw - 16f, 220f, 24f, fill(col(bc.argb)))
            c.rrect(x + 8f, 300f, sw - 16f, 220f, 24f, stroke(col(ink, 0.25f), 2f))
            c.txt.draw(bc.name, x + sw / 2, 560f, FontId.BOLD, c.txt.fit(bc.name, FontId.BOLD, 24f, sw - 20f), fill(col(ink)))
            c.txt.draw("${bc.role} · ${bc.hex}", x + sw / 2, 592f, FontId.REGULAR, c.txt.fit("${bc.role} · ${bc.hex}", FontId.REGULAR, 19f, sw - 20f), fill(col(ink, 0.7f)))
        }
        // fonts
        c.txt.draw("Titres : ${kit.fonts.title}", 60f, 680f, titleTf, 48f, fill(col(ink)), Align.LEFT)
        c.txt.draw("Texte : ${kit.fonts.body} — ${kit.fonts.why}", 60f, 730f, bodyTf, c.txt.fit("Texte : ${kit.fonts.body} — ${kit.fonts.why}", bodyTf, 24f, w - 120f), fill(col(ink, 0.8f)), Align.LEFT)
        // taglines + hashtags
        kit.taglines.take(3).forEachIndexed { i, tl -> c.txt.draw("« $tl »", 60f, 810f + i * 44f, bodyTf, c.txt.fit("« $tl »", bodyTf, 30f, w * 0.6f), fill(col(ink)), Align.LEFT) }
        val tags = kit.hashtags.take(6).joinToString("  ")
        c.txt.draw(tags, w - 60f, h - 50f, FontId.BOLD, c.txt.fit(tags, FontId.BOLD, 26f, w * 0.45f), fill(col(prim)), Align.RIGHT)
        c.txt.draw("Identité générée avec CUTTINX PRO", w - 60f, 60f, FontId.REGULAR, 18f, fill(col(ink, 0.5f)), Align.RIGHT)
    }

    // ------------------------------------------------------------------ social media kit

    private fun colors(kit: BrandKit) = kit.palette.associateBy { it.role }.let { p ->
        listOf(p["Fond"]!!.argb, p["Fond secondaire"]!!.argb, p["Principale"]!!.argb, p["Accent"]!!.argb, p["Texte"]!!.argb)
    }

    /** Profile picture (square [s]): monogram on the brand gradient. */
    fun drawAvatar(c: Gfx, kit: BrandKit, s: Float) {
        val (bg, bg2, prim, acc, _) = colors(kit)
        c.rect(0f, 0f, s, s, shaded(linear(0f, 0f, s, s, intArrayOf(col(bg), col(bg2)))))
        c.circle(s * 0.8f, s * 0.2f, s * 0.5f, fill(col(acc, 0.18f)))
        drawMonogram(c, kit, s / 2, s / 2, s * 0.78f)
        c.circle(s / 2, s / 2, s * 0.47f, stroke(col(prim, 0.5f), s * 0.01f))
    }

    /** YouTube banner (2560×1440 reference, everything important in the central 1546×423 safe area). */
    fun drawYoutubeBanner(c: Gfx, kit: BrandKit, w: Float = 2560f, h: Float = 1440f) {
        val (bg, bg2, prim, acc, ink) = colors(kit)
        c.rect(0f, 0f, w, h, shaded(linear(0f, 0f, w, h, intArrayOf(col(bg), col(bg2)))))
        for (i in 0 until 7) c.circle(w * (0.08f + i * 0.15f), h * (if (i % 2 == 0) 0.25f else 0.78f), h * 0.22f, fill(col(if (i % 2 == 0) prim else acc, 0.1f)))
        val k = w / 2560f
        val safeX = (w - 1546f * k) / 2; val safeY = (h - 423f * k) / 2
        drawMonogram(c, kit, safeX + 210f * k, h / 2, 330f * k)
        val tf = typeface(kit.fonts.title)
        c.txt.draw(kit.brief.name, safeX + 420f * k, h / 2 - 10f * k, tf, c.txt.fit(kit.brief.name, tf, 150f * k, 1100f * k), fill(col(ink)), Align.LEFT)
        val tl = kit.taglines.first()
        c.txt.draw(tl, safeX + 424f * k, h / 2 + 80f * k, FontId.REGULAR, c.txt.fit(tl, FontId.REGULAR, 54f * k, 1100f * k), fill(col(prim)), Align.LEFT)
        c.txt.draw(kit.postingRhythm, safeX + 424f * k, h / 2 + 150f * k, FontId.BOLD, c.txt.fit(kit.postingRhythm, FontId.BOLD, 36f * k, 1100f * k), fill(col(ink, 0.7f)), Align.LEFT)
    }

    /** Instagram story highlight cover number [i] (square [s]) with an icon of the CUTTINX set. */
    fun drawHighlight(c: Gfx, kit: BrandKit, i: Int, s: Float) {
        val (bg, _, prim, acc, _) = colors(kit)
        val label = kit.highlights.getOrElse(i) { "" }
        c.rect(0f, 0f, s, s, fill(col(if (i % 2 == 0) prim else acc)))
        c.circle(s / 2, s / 2, s * 0.38f, fill(col(bg, 0.25f)))
        val icon = when {
            label.contains("Clip") || label.contains("Lives") -> IconSet.Video
            label.contains("Setup") -> IconSet.Twitch
            label.contains("Collab") -> IconSet.Identity
            label.contains("FAQ") -> IconSet.Help
            label.contains("Coulisses") -> IconSet.Image
            label.contains("Favoris") -> IconSet.Star
            else -> IconSet.Idea
        }
        c.icon(icon, s * 0.3f, s * 0.3f, s * 0.4f, col(0xFFFFFFFF))
    }

    /** YouTube thumbnail template (1280×720): big title, monogram, brand colours. */
    fun drawThumbnail(c: Gfx, kit: BrandKit, title: String = kit.pillars.firstOrNull()?.ideas?.firstOrNull() ?: kit.taglines.first(), w: Float = 1280f, h: Float = 720f) {
        val (bg, bg2, prim, acc, _) = colors(kit)
        c.rect(0f, 0f, w, h, shaded(linear(0f, 0f, w, h, intArrayOf(col(bg2), col(bg)))))
        c.path(Path().moveTo(w * 0.55f, 0f).lineTo(w, 0f).lineTo(w, h).lineTo(w * 0.42f, h).closePath(), fill(col(prim)))
        drawMonogram(c, kit, w * 0.76f, h * 0.5f, h * 0.55f)
        val tf = typeface(kit.fonts.title).let { if (it == FontId.SCRIPT || it == FontId.PIXEL) FontId.IMPACT else it }
        val words = title.uppercase().split(" ")
        val lines = ArrayList<String>(); var cur = ""
        for (wd in words) { val n = if (cur.isEmpty()) wd else "$cur $wd"; if (n.length > 14 && cur.isNotEmpty()) { lines += cur; cur = wd } else cur = n }
        if (cur.isNotEmpty()) lines += cur
        lines.take(3).forEachIndexed { i, l ->
            val y = h * 0.3f + i * h * 0.2f
            val sz = c.txt.fit(l, tf, h * 0.17f, w * 0.48f)
            c.txt.draw(l, w * 0.05f, y, tf, sz, stroke(col(0xFF000000), sz * 0.12f), Align.LEFT)
            c.txt.draw(l, w * 0.05f, y, tf, sz, fill(col(if (i == 1) acc else 0xFFFFFFFF)), Align.LEFT)
        }
    }
}
