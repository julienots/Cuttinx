package com.superessence.cuttinx.desktop.render

import com.superessence.cuttinx.core.gfx.FontId
import com.superessence.cuttinx.core.gfx.Frame
import com.superessence.cuttinx.core.gfx.Raster
import org.jetbrains.skia.*
import org.jetbrains.skia.shaper.Shaper
import com.superessence.cuttinx.core.gfx.Gfx as CxGfx
import com.superessence.cuttinx.core.gfx.Paint as CxPaint
import com.superessence.cuttinx.core.gfx.Path as CxPath
import com.superessence.cuttinx.core.gfx.Shader as CxShader

/** Bundled fonts (same files as the Android app) with per-character fallback for emoji / CJK. */
class Fonts {
    private val cache = HashMap<FontId, Typeface>()
    private val fallback = HashMap<Int, Typeface?>()

    fun bytes(f: FontId): ByteArray? = Fonts::class.java.getResourceAsStream("/fonts/${f.file}")?.use { it.readBytes() }

    fun typeface(f: FontId): Typeface = cache.getOrPut(f) {
        bytes(f)?.let { FontMgr.default.makeFromData(Data.makeFromBytes(it)) } ?: FontMgr.default.matchFamilyStyle(null, FontStyle.BOLD) ?: Typeface.makeEmpty()
    }

    private val shaper by lazy { Shaper.makeShapeDontWrapOrReorder() }
    private val lines = object : LinkedHashMap<Triple<String, Typeface, Float>, TextLine>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Triple<String, Typeface, Float>, TextLine>?) = size > 4000
    }

    /** Shaped line, cached: shaping (HarfBuzz) is by far the most expensive part of drawing text. */
    @Synchronized fun line(text: String, font: Font): TextLine = lines.getOrPut(Triple(text, font.typeface!!, font.size)) {
        shaper.shapeLine(text, font)
    }

    private val images = java.util.WeakHashMap<ByteArray, Image?>()

    /** Decoded picture, cached per byte array (custom logos are decoded once). */
    @Synchronized fun image(data: ByteArray): Image? = images.getOrPut(data) { runCatching { Image.makeFromEncoded(data) }.getOrNull() }

    /** Typeface able to draw [cp] (system font lookup, cached). */
    @Synchronized fun fallbackFor(cp: Int): Typeface? = fallback.getOrPut(cp) { FontMgr.default.matchFamilyStyleCharacter(null, FontStyle.BOLD, null, cp) }
}

/** Skia implementation of the CUTTINX drawing API (PC preview and exports). */
class SkiaGfx(var canvas: Canvas, private val fonts: Fonts) : CxGfx {
    private class Run(val text: String, val font: Font)

    private fun runs(text: String, f: FontId, size: Float): List<Run> {
        val tf = fonts.typeface(f)
        val out = ArrayList<Run>(); val sb = StringBuilder(); var cur: Typeface? = null
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val face = if (cp == ' '.code || tf.getUTF32Glyph(cp).toInt() != 0) tf else fonts.fallbackFor(cp) ?: tf
            if (cur != null && face != cur) { out += Run(sb.toString(), Font(cur, size)); sb.clear() }
            cur = face; sb.appendCodePoint(cp); i += Character.charCount(cp)
        }
        if (sb.isNotEmpty() && cur != null) out += Run(sb.toString(), Font(cur, size))
        return out
    }

    private fun shader(s: CxShader): Shader {
        fun grad(colors: IntArray, pos: FloatArray?) = Gradient(Gradient.Colors(colors.map { Color4f(it) }.toTypedArray(),
            pos ?: FloatArray(colors.size) { i -> i / (colors.size - 1f).coerceAtLeast(1f) }, FilterTileMode.CLAMP, ColorSpace.sRGB))
        return when (s) {
            is CxShader.Linear -> Shader.makeLinearGradient(s.x0, s.y0, s.x1, s.y1, grad(s.colors, s.positions))
            is CxShader.Radial -> Shader.makeRadialGradient(s.cx, s.cy, s.r, grad(s.colors, s.positions))
        }
    }

    private fun paint(p: CxPaint) = Paint().apply {
        isAntiAlias = true
        color = p.color
        if (p.stroke) { mode = PaintMode.STROKE; strokeWidth = p.strokeWidth; strokeCap = PaintStrokeCap.ROUND; strokeJoin = PaintStrokeJoin.ROUND }
        p.shader?.let { shader = shader(it) }
        if (p.alpha < 1f) alpha = (alpha * p.alpha.coerceIn(0f, 1f)).toInt()
        if (p.blur > 0f) maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, p.blur)
        p.shadow?.let { imageFilter = ImageFilter.makeDropShadow(it.dx, it.dy, it.blur, it.blur, it.color) }
    }

    private fun path(p: CxPath): Path = PathBuilder().apply {
        for (op in p.ops) when (op) {
            is CxPath.MoveTo -> moveTo(op.x, op.y)
            is CxPath.LineTo -> lineTo(op.x, op.y)
            is CxPath.QuadTo -> quadTo(op.x1, op.y1, op.x, op.y)
            is CxPath.CubicTo -> cubicTo(op.x1, op.y1, op.x2, op.y2, op.x, op.y)
            is CxPath.Circle -> addCircle(op.cx, op.cy, op.r)
            is CxPath.RectOp -> addRect(Rect.makeXYWH(op.x, op.y, op.w, op.h))
            CxPath.Close -> closePath()
        }
    }.detach()

    override fun save() { canvas.save() }
    override fun restore() { canvas.restore() }
    override fun translate(dx: Float, dy: Float) { canvas.translate(dx, dy) }
    override fun rotate(degrees: Float) { canvas.rotate(degrees) }
    override fun scale(sx: Float, sy: Float) { canvas.scale(sx, sy) }
    override fun clipRect(x: Float, y: Float, w: Float, h: Float) { canvas.clipRect(Rect.makeXYWH(x, y, w, h)) }
    override fun clipRRect(x: Float, y: Float, w: Float, h: Float, r: Float) { canvas.clipRRect(RRect.makeXYWH(x, y, w, h, r), ClipMode.INTERSECT, true) }
    override fun clipPath(path: CxPath) { canvas.clipPath(path(path), ClipMode.INTERSECT, true) }
    override fun clear(color: Int) { canvas.clear(color) }
    override fun rect(x: Float, y: Float, w: Float, h: Float, p: CxPaint) { canvas.drawRect(Rect.makeXYWH(x, y, w, h), paint(p)) }
    override fun rrect(x: Float, y: Float, w: Float, h: Float, r: Float, p: CxPaint) { canvas.drawRRect(RRect.makeXYWH(x, y, w, h, r), paint(p)) }
    override fun circle(cx: Float, cy: Float, r: Float, p: CxPaint) { canvas.drawCircle(cx, cy, r, paint(p)) }
    override fun line(x0: Float, y0: Float, x1: Float, y1: Float, p: CxPaint) { canvas.drawLine(x0, y0, x1, y1, paint(p)) }
    override fun path(path: CxPath, p: CxPaint) { canvas.drawPath(path(path), paint(p)) }
    override fun arc(left: Float, top: Float, right: Float, bottom: Float, startDeg: Float, sweepDeg: Float, p: CxPaint) { canvas.drawArc(left, top, right, bottom, startDeg, sweepDeg, false, paint(p)) }

    override fun text(text: String, x: Float, y: Float, font: FontId, size: Float, p: CxPaint) {
        val pt = paint(p); var cx = x
        for (r in runs(text, font, size)) { val l = fonts.line(r.text, r.font); canvas.drawTextLine(l, cx, y, pt); cx += l.width }
    }

    override fun textWidth(text: String, font: FontId, size: Float): Float = runs(text, font, size).sumOf { fonts.line(it.text, it.font).width.toDouble() }.toFloat()

    override fun metrics(font: FontId, size: Float): Pair<Float, Float> { val m = Font(fonts.typeface(font), size).metrics; return m.ascent to m.descent }

    override fun image(data: ByteArray, x: Float, y: Float, w: Float, h: Float, alpha: Float) {
        val img = fonts.image(data) ?: return
        val k = minOf(w / img.width, h / img.height)
        val dw = img.width * k; val dh = img.height * k
        canvas.drawImageRect(img, Rect.makeWH(img.width.toFloat(), img.height.toFloat()), Rect.makeXYWH(x + (w - dw) / 2, y + (h - dh) / 2, dw, dh),
            SamplingMode.LINEAR, Paint().apply { isAntiAlias = true; this.alpha = (255 * alpha.coerceIn(0f, 1f)).toInt() }, true)
    }
}

/** Offscreen Skia surfaces for exports. */
class SkiaRaster(val fonts: Fonts = Fonts()) : Raster {
    override fun frame(width: Int, height: Int): Frame {
        val s = Surface.makeRasterN32Premul(width, height)
        return object : Frame {
            override val gfx = SkiaGfx(s.canvas, fonts)
            override val width = width
            override val height = height
            override fun png(): ByteArray = s.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
            override fun argb(): IntArray {
                val bmp = Bitmap()
                bmp.allocPixels(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL))
                s.readPixels(bmp, 0, 0)
                val b = bmp.readPixels()!!
                bmp.close()
                // BGRA bytes (little endian) = 0xAARRGGBB ints
                return IntArray(width * height) { i -> val o = i * 4; ((b[o + 3].toInt() and 0xFF) shl 24) or ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 1].toInt() and 0xFF) shl 8) or (b[o].toInt() and 0xFF) }
            }
        }
    }

    override fun fontBytes(font: FontId): ByteArray? = fonts.bytes(font)
}
