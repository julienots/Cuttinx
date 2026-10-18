package com.superessence.cuttinx.desktop.render

import com.superessence.cuttinx.core.brand.BrandKit
import org.jetbrains.skia.*

/** Visual summary of a brand kit (palette, monogram, fonts, taglines), exported as a PNG "planche". */
class BrandBoard(private val fonts: Fonts = Fonts()) {
    private val text = TextDrawer(fonts)

    /** Typeface matching a font name of the kit (bundled fonts, Montserrat otherwise). */
    fun typeface(name: String): Typeface {
        val file = when {
            name.startsWith("Playfair") -> com.superessence.cuttinx.core.overlay.TitleFont.ELEGANT
            name.startsWith("Righteous") -> com.superessence.cuttinx.core.overlay.TitleFont.TECH
            name.startsWith("Press Start") -> com.superessence.cuttinx.core.overlay.TitleFont.PIXEL
            name.startsWith("Anton") -> com.superessence.cuttinx.core.overlay.TitleFont.IMPACT
            name.startsWith("Bangers") -> com.superessence.cuttinx.core.overlay.TitleFont.COMIC
            name.startsWith("Fredoka") -> com.superessence.cuttinx.core.overlay.TitleFont.ROUNDED
            name.startsWith("Pacifico") -> com.superessence.cuttinx.core.overlay.TitleFont.SCRIPT
            name.startsWith("Caveat") || name.startsWith("Permanent") -> com.superessence.cuttinx.core.overlay.TitleFont.BRUSH
            name.startsWith("Bebas") || name.startsWith("Oswald") -> com.superessence.cuttinx.core.overlay.TitleFont.CONDENSED
            else -> null
        }
        return file?.let { fonts.title(it) } ?: fonts.bold
    }

    /** Round monogram logo (profile picture) of [size] px centred on (cx, cy). */
    fun drawMonogram(c: Canvas, kit: BrandKit, cx: Float, cy: Float, size: Float) {
        val pal = kit.palette.associateBy { it.role }
        val prim = pal["Principale"]!!.argb; val acc = pal["Accent"]!!.argb; val bg = pal["Fond"]!!.argb
        val r = size / 2
        c.drawCircle(cx, cy, r, Paint().apply { isAntiAlias = true; shader = linear(cx - r, cy - r, cx + r, cy + r, intArrayOf(col(prim), col(acc))) })
        c.drawCircle(cx, cy, r * 0.86f, fill(col(bg)))
        c.drawCircle(cx, cy, r * 0.86f, stroke(col(prim), r * 0.04f))
        val tf = typeface(kit.fonts.title)
        val s = text.fit(kit.monogram, tf, r * 0.9f, r * 1.3f)
        text.draw(c, kit.monogram, cx, text.baselineFor(cy, tf, s), tf, s, fill(col(prim)))
    }

    fun draw(c: Canvas, kit: BrandKit, w: Float = 1600f, h: Float = 1000f) {
        val pal = kit.palette.associateBy { it.role }
        val bg = pal["Fond"]!!.argb; val bg2 = pal["Fond secondaire"]!!.argb; val ink = pal["Texte"]!!.argb; val prim = pal["Principale"]!!.argb
        c.drawRect(Rect.makeWH(w, h), Paint().apply { shader = linear(0f, 0f, w, h, intArrayOf(col(bg), col(bg2))) })
        val titleTf = typeface(kit.fonts.title); val bodyTf = typeface(kit.fonts.body).let { if (it == fonts.bold) fonts.regular else it }
        // header
        drawMonogram(c, kit, 150f, 150f, 200f)
        text.draw(c, kit.brief.name, 290f, 150f, titleTf, text.fit(kit.brief.name, titleTf, 92f, w - 340f), fill(col(ink)), Align.LEFT)
        text.draw(c, "${kit.archetype} · ${kit.brief.niche.label}", 294f, 205f, fonts.bold, 30f, fill(col(prim)), Align.LEFT)
        // palette
        val sw = (w - 120f) / kit.palette.size
        kit.palette.forEachIndexed { i, bc ->
            val x = 60f + i * sw
            c.drawRRect(RRect.makeXYWH(x + 8f, 300f, sw - 16f, 220f, 24f), fill(col(bc.argb)))
            c.drawRRect(RRect.makeXYWH(x + 8f, 300f, sw - 16f, 220f, 24f), stroke(col(ink, 0.25f), 2f))
            text.draw(c, bc.name, x + sw / 2, 560f, fonts.bold, text.fit(bc.name, fonts.bold, 24f, sw - 20f), fill(col(ink)))
            text.draw(c, "${bc.role} · ${bc.hex}", x + sw / 2, 592f, fonts.regular, text.fit("${bc.role} · ${bc.hex}", fonts.regular, 19f, sw - 20f), fill(col(ink, 0.7f)))
        }
        // fonts
        text.draw(c, "Titres : ${kit.fonts.title}", 60f, 680f, titleTf, 48f, fill(col(ink)), Align.LEFT)
        text.draw(c, "Texte : ${kit.fonts.body} — ${kit.fonts.why}", 60f, 730f, bodyTf, text.fit("Texte : ${kit.fonts.body} — ${kit.fonts.why}", bodyTf, 24f, w - 120f), fill(col(ink, 0.8f)), Align.LEFT)
        // taglines + hashtags
        kit.taglines.take(3).forEachIndexed { i, tl -> text.draw(c, "« $tl »", 60f, 810f + i * 44f, bodyTf, text.fit("« $tl »", bodyTf, 30f, w * 0.6f), fill(col(ink)), Align.LEFT) }
        val tags = kit.hashtags.take(6).joinToString("  ")
        text.draw(c, tags, w - 60f, h - 50f, fonts.bold, text.fit(tags, fonts.bold, 26f, w * 0.45f), fill(col(prim)), Align.RIGHT)
        text.draw(c, "Identité générée avec CUTTINX PRO", w - 60f, 60f, fonts.regular, 18f, fill(col(ink, 0.5f)), Align.RIGHT)
    }
}
