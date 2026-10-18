package com.superessence.cuttinx.overlay

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Typeface
import com.superessence.cuttinx.core.gfx.FontId
import com.superessence.cuttinx.core.gfx.Frame
import com.superessence.cuttinx.core.gfx.Gfx
import com.superessence.cuttinx.core.gfx.Paint
import com.superessence.cuttinx.core.gfx.Path
import com.superessence.cuttinx.core.gfx.Raster
import com.superessence.cuttinx.core.gfx.Shader
import java.io.ByteArrayOutputStream

/** Bundled fonts loaded from the app assets (system fallback handles emoji / CJK automatically). */
class AndroidFonts(private val assets: AssetManager) {
    private val cache = HashMap<FontId, Typeface>()
    @Synchronized fun typeface(f: FontId): Typeface = cache.getOrPut(f) { runCatching { Typeface.createFromAsset(assets, "fonts/${f.file}") }.getOrDefault(Typeface.DEFAULT_BOLD) }
    fun bytes(f: FontId): ByteArray? = runCatching { assets.open("fonts/${f.file}").use { it.readBytes() } }.getOrNull()
}

/**
 * android.graphics implementation of the CUTTINX drawing API. Used on software bitmaps (preview and
 * exports) so that blurs and shadows render exactly as on the PC version.
 */
class AndroidGfx(var canvas: Canvas, private val fonts: AndroidFonts) : Gfx {
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val ap = android.graphics.Path()
    private val rf = RectF()

    private fun paint(src: Paint): android.graphics.Paint {
        p.reset()
        p.isAntiAlias = true
        p.color = src.color
        if (src.stroke) {
            p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = src.strokeWidth
            p.strokeCap = android.graphics.Paint.Cap.ROUND; p.strokeJoin = android.graphics.Paint.Join.ROUND
        } else p.style = android.graphics.Paint.Style.FILL
        src.shader?.let { s ->
            p.shader = when (s) {
                is Shader.Linear -> LinearGradient(s.x0, s.y0, s.x1, s.y1, s.colors, s.positions, android.graphics.Shader.TileMode.CLAMP)
                is Shader.Radial -> RadialGradient(s.cx, s.cy, s.r.coerceAtLeast(0.01f), s.colors, s.positions, android.graphics.Shader.TileMode.CLAMP)
            }
        }
        if (src.alpha < 1f) p.alpha = (p.alpha * src.alpha.coerceIn(0f, 1f)).toInt()
        // Skia blur sigma → Android blur radius
        if (src.blur > 0f) p.maskFilter = BlurMaskFilter((src.blur * 1.73f).coerceAtLeast(0.1f), BlurMaskFilter.Blur.NORMAL)
        src.shadow?.let { p.setShadowLayer(it.blur.coerceAtLeast(0.1f), it.dx, it.dy, it.color) }
        return p
    }

    private fun path(src: Path): android.graphics.Path {
        ap.reset()
        for (op in src.ops) when (op) {
            is Path.MoveTo -> ap.moveTo(op.x, op.y)
            is Path.LineTo -> ap.lineTo(op.x, op.y)
            is Path.QuadTo -> ap.quadTo(op.x1, op.y1, op.x, op.y)
            is Path.CubicTo -> ap.cubicTo(op.x1, op.y1, op.x2, op.y2, op.x, op.y)
            is Path.Circle -> ap.addCircle(op.cx, op.cy, op.r, android.graphics.Path.Direction.CW)
            is Path.RectOp -> ap.addRect(op.x, op.y, op.x + op.w, op.y + op.h, android.graphics.Path.Direction.CW)
            Path.Close -> ap.close()
        }
        return ap
    }

    override fun save() { canvas.save() }
    override fun restore() { canvas.restore() }
    override fun translate(dx: Float, dy: Float) = canvas.translate(dx, dy)
    override fun rotate(degrees: Float) = canvas.rotate(degrees)
    override fun scale(sx: Float, sy: Float) = canvas.scale(sx, sy)
    override fun clipRect(x: Float, y: Float, w: Float, h: Float) { canvas.clipRect(x, y, x + w, y + h) }
    override fun clipRRect(x: Float, y: Float, w: Float, h: Float, r: Float) {
        ap.reset(); rf.set(x, y, x + w, y + h); ap.addRoundRect(rf, r, r, android.graphics.Path.Direction.CW); canvas.clipPath(ap)
    }
    override fun clipPath(path: Path) { canvas.clipPath(path(path)) }
    override fun clear(color: Int) = canvas.drawColor(color, PorterDuff.Mode.SRC)
    override fun rect(x: Float, y: Float, w: Float, h: Float, p: Paint) = canvas.drawRect(x, y, x + w, y + h, paint(p))
    override fun rrect(x: Float, y: Float, w: Float, h: Float, r: Float, p: Paint) = canvas.drawRoundRect(x, y, x + w, y + h, r, r, paint(p))
    override fun circle(cx: Float, cy: Float, r: Float, p: Paint) = canvas.drawCircle(cx, cy, r, paint(p))
    override fun line(x0: Float, y0: Float, x1: Float, y1: Float, p: Paint) {
        val pt = paint(p); if (!p.stroke) { pt.style = android.graphics.Paint.Style.STROKE; pt.strokeWidth = 1f }
        canvas.drawLine(x0, y0, x1, y1, pt)
    }
    override fun path(path: Path, p: Paint) = canvas.drawPath(path(path), paint(p))
    override fun arc(left: Float, top: Float, right: Float, bottom: Float, startDeg: Float, sweepDeg: Float, p: Paint) {
        rf.set(left, top, right, bottom); canvas.drawArc(rf, startDeg, sweepDeg, false, paint(p))
    }

    private fun textPaint(font: FontId, size: Float, src: Paint?): android.graphics.Paint {
        val pt = if (src != null) paint(src) else p.also { it.reset(); it.isAntiAlias = true }
        pt.typeface = fonts.typeface(font); pt.textSize = size
        return pt
    }

    override fun text(text: String, x: Float, y: Float, font: FontId, size: Float, p: Paint) = canvas.drawText(text, x, y, textPaint(font, size, p))
    override fun textWidth(text: String, font: FontId, size: Float): Float = textPaint(font, size, null).measureText(text)
    override fun metrics(font: FontId, size: Float): Pair<Float, Float> { val m = textPaint(font, size, null).fontMetrics; return m.ascent to m.descent }
}

/** Offscreen software bitmaps for previews and exports. */
class AndroidRaster(assets: AssetManager) : Raster {
    val fonts = AndroidFonts(assets)

    class BitmapFrame(val bitmap: Bitmap, fonts: AndroidFonts) : Frame {
        override val gfx = AndroidGfx(Canvas(bitmap), fonts)
        override val width get() = bitmap.width
        override val height get() = bitmap.height
        override fun png(): ByteArray = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    override fun frame(width: Int, height: Int): BitmapFrame = BitmapFrame(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888), fonts)
    override fun fontBytes(font: FontId): ByteArray? = fonts.bytes(font)
}
