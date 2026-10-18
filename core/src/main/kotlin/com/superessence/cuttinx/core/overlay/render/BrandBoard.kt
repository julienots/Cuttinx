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
}
