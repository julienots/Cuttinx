package com.superessence.cuttinx.desktop.render

import com.superessence.cuttinx.core.overlay.TitleFont
import org.jetbrains.skia.*

/** ARGB (Long, as stored in the core model) to a Skia colour with an extra opacity factor. */
fun col(argb: Long, alpha: Float = 1f): Int {
    val a = (((argb ushr 24) and 0xFF) * alpha.coerceIn(0f, 1f)).toInt()
    return (a shl 24) or (argb and 0xFFFFFF).toInt()
}

/** Builds an (immutable) Skia path. */
inline fun path(block: PathBuilder.() -> Unit): Path = PathBuilder().apply(block).detach()

private fun gradient(colors: IntArray, pos: FloatArray?) =
    Gradient(Gradient.Colors(colors.map { Color4f(it) }.toTypedArray(), pos ?: FloatArray(colors.size) { i -> i / (colors.size - 1f).coerceAtLeast(1f) }, FilterTileMode.CLAMP, ColorSpace.sRGB))

fun linear(x0: Float, y0: Float, x1: Float, y1: Float, colors: IntArray, pos: FloatArray? = null): Shader = Shader.makeLinearGradient(x0, y0, x1, y1, gradient(colors, pos))
fun radial(cx: Float, cy: Float, r: Float, colors: IntArray, pos: FloatArray? = null): Shader = Shader.makeRadialGradient(cx, cy, r, gradient(colors, pos))

fun fill(color: Int) = Paint().apply { this.color = color; isAntiAlias = true }
fun stroke(color: Int, width: Float) = Paint().apply { this.color = color; isAntiAlias = true; mode = PaintMode.STROKE; strokeWidth = width; strokeCap = PaintStrokeCap.ROUND; strokeJoin = PaintStrokeJoin.ROUND }
fun Paint.glow(sigma: Float): Paint = apply { maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, sigma) }

/** Bundled fonts (same files as the Android app) with per-character fallback for emoji / CJK. */
class Fonts {
    private val cache = HashMap<String, Typeface>()

    private fun load(file: String): Typeface = cache.getOrPut(file) {
        val bytes = Fonts::class.java.getResourceAsStream("/fonts/$file")?.use { it.readBytes() }
        bytes?.let { FontMgr.default.makeFromData(Data.makeFromBytes(it)) } ?: FontMgr.default.matchFamilyStyle(null, FontStyle.BOLD) ?: Typeface.makeEmpty()
    }

    fun title(f: TitleFont): Typeface = load(when (f) {
        TitleFont.TECH, TitleFont.JAPANESE -> "Righteous_400Regular.ttf"
        TitleFont.COMIC -> "Bangers_400Regular.ttf"
        TitleFont.PIXEL -> "PressStart2P_400Regular.ttf"
        TitleFont.IMPACT -> "Anton_400Regular.ttf"
        TitleFont.ROUNDED -> "Fredoka_600SemiBold.ttf"
        TitleFont.ELEGANT -> "PlayfairDisplay_700Bold_Italic.ttf"
        TitleFont.SCRIPT -> "Pacifico_400Regular.ttf"
        TitleFont.BRUSH -> "PermanentMarker_400Regular.ttf"
        TitleFont.CONDENSED -> "BebasNeue_400Regular.ttf"
    })

    val bold: Typeface get() = load("Montserrat_800ExtraBold.ttf")
    val regular: Typeface get() = load("Montserrat_500Medium.ttf")

    private val fallback = HashMap<Int, Typeface?>()

    /** Typeface able to draw [cp] (system font lookup, cached). */
    fun fallbackFor(cp: Int): Typeface? = fallback.getOrPut(cp) { FontMgr.default.matchFamilyStyleCharacter(null, FontStyle.BOLD, null, cp) }
}

enum class Align { LEFT, CENTER, RIGHT }

/**
 * Text drawing with font fallback: runs of characters missing from [tf] (emoji, ♡, 配信中…) are
 * drawn with a system font that has them.
 */
class TextDrawer(private val fonts: Fonts) {
    private data class Run(val text: String, val font: Font)

    private fun runs(text: String, tf: Typeface, size: Float): List<Run> {
        val out = ArrayList<Run>()
        val sb = StringBuilder()
        var cur: Typeface? = null
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val face = if (cp == ' '.code || tf.getUTF32Glyph(cp).toInt() != 0) tf else fonts.fallbackFor(cp) ?: tf
            if (cur != null && face != cur) { out += Run(sb.toString(), Font(cur, size)); sb.clear() }
            cur = face
            sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        if (sb.isNotEmpty() && cur != null) out += Run(sb.toString(), Font(cur, size))
        return out
    }

    fun width(text: String, tf: Typeface, size: Float): Float = runs(text, tf, size).sumOf { TextLine.make(it.text, it.font).width.toDouble() }.toFloat()

    /** Largest size ≤ [max] such that [text] fits in [maxWidth]. */
    fun fit(text: String, tf: Typeface, max: Float, maxWidth: Float): Float {
        val w = width(text, tf, max)
        return if (w <= maxWidth || w <= 0f) max else (max * maxWidth / w).coerceAtLeast(8f)
    }

    /** Draws [text] with its baseline at [y]; [x] is the left, centre or right edge depending on [align]. */
    fun draw(c: Canvas, text: String, x: Float, y: Float, tf: Typeface, size: Float, paint: Paint, align: Align = Align.CENTER, spacing: Float = 0f) {
        val rs = runs(text, tf, size)
        val lines = rs.map { TextLine.make(it.text, it.font) }
        val total = lines.sumOf { it.width.toDouble() }.toFloat() + spacing * (text.length - 1).coerceAtLeast(0)
        var cx = when (align) { Align.LEFT -> x; Align.CENTER -> x - total / 2; Align.RIGHT -> x - total }
        if (spacing == 0f) {
            for (l in lines) { c.drawTextLine(l, cx, y, paint); cx += l.width }
        } else {
            // letter-spaced titles: draw character by character
            for (r in rs) {
                var i = 0
                while (i < r.text.length) {
                    val cp = r.text.codePointAt(i); val ch = String(Character.toChars(cp))
                    val l = TextLine.make(ch, r.font)
                    c.drawTextLine(l, cx, y, paint); cx += l.width + spacing
                    i += Character.charCount(cp)
                }
            }
        }
    }

    /** Vertical metrics helper: baseline that visually centres a line of [size] on [centerY]. */
    fun baselineFor(centerY: Float, tf: Typeface, size: Float): Float {
        val m = Font(tf, size).metrics
        return centerY - (m.ascent + m.descent) / 2
    }
}

// ---------------------------------------------------------------------- shapes

object Shapes {
    fun heart(cx: Float, cy: Float, s: Float) = path {
        moveTo(cx, cy + s * 0.35f)
        cubicTo(cx - s * 0.55f, cy - s * 0.05f, cx - s * 0.45f, cy - s * 0.55f, cx, cy - s * 0.25f)
        cubicTo(cx + s * 0.45f, cy - s * 0.55f, cx + s * 0.55f, cy - s * 0.05f, cx, cy + s * 0.35f)
        closePath()
    }

    fun star(cx: Float, cy: Float, r: Float, points: Int = 5, inner: Float = 0.45f) = path {
        for (i in 0 until points * 2) {
            val a = -Math.PI / 2 + i * Math.PI / points
            val rr = if (i % 2 == 0) r else r * inner
            val x = cx + (rr * kotlin.math.cos(a)).toFloat(); val y = cy + (rr * kotlin.math.sin(a)).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        closePath()
    }

    fun sparkle(cx: Float, cy: Float, r: Float) = star(cx, cy, r, 4, 0.22f)

    fun petal(s: Float) = path {
        moveTo(0f, -s * 0.5f)
        cubicTo(s * 0.45f, -s * 0.35f, s * 0.35f, s * 0.35f, 0f, s * 0.5f)
        cubicTo(-s * 0.35f, s * 0.35f, -s * 0.45f, -s * 0.35f, 0f, -s * 0.5f)
        closePath()
    }

    /** Cherry-blossom petal with the characteristic notch. */
    fun sakura(s: Float) = path {
        moveTo(0f, s * 0.5f)
        cubicTo(-s * 0.5f, s * 0.1f, -s * 0.35f, -s * 0.45f, -s * 0.08f, -s * 0.5f)
        lineTo(0f, -s * 0.36f)
        lineTo(s * 0.08f, -s * 0.5f)
        cubicTo(s * 0.35f, -s * 0.45f, s * 0.5f, s * 0.1f, 0f, s * 0.5f)
        closePath()
    }

    fun leaf(s: Float) = path {
        moveTo(0f, -s * 0.5f)
        quadTo(s * 0.5f, 0f, 0f, s * 0.5f)
        quadTo(-s * 0.5f, 0f, 0f, -s * 0.5f)
        closePath()
    }

    fun bat(s: Float) = path {
        moveTo(0f, -s * 0.12f)
        lineTo(s * 0.08f, -s * 0.22f); lineTo(s * 0.1f, -s * 0.08f)
        quadTo(s * 0.3f, -s * 0.3f, s * 0.55f, -s * 0.2f)
        quadTo(s * 0.45f, -s * 0.05f, s * 0.5f, s * 0.08f)
        quadTo(s * 0.38f, -s * 0.02f, s * 0.3f, s * 0.12f)
        quadTo(s * 0.2f, s * 0.02f, s * 0.12f, s * 0.15f)
        quadTo(s * 0.05f, s * 0.08f, 0f, s * 0.2f)
        quadTo(-s * 0.05f, s * 0.08f, -s * 0.12f, s * 0.15f)
        quadTo(-s * 0.2f, s * 0.02f, -s * 0.3f, s * 0.12f)
        quadTo(-s * 0.38f, -s * 0.02f, -s * 0.5f, s * 0.08f)
        quadTo(-s * 0.45f, -s * 0.05f, -s * 0.55f, -s * 0.2f)
        quadTo(-s * 0.3f, -s * 0.3f, -s * 0.1f, -s * 0.08f)
        lineTo(-s * 0.08f, -s * 0.22f)
        closePath()
    }

    fun hexagon(cx: Float, cy: Float, r: Float) = path {
        for (i in 0 until 6) {
            val a = Math.PI / 3 * i
            val x = cx + (r * kotlin.math.cos(a)).toFloat(); val y = cy + (r * kotlin.math.sin(a)).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        closePath()
    }

    /** Rectangle with cut corners (HUD / esport plates). */
    fun cutRect(x: Float, y: Float, w: Float, h: Float, cut: Float) = path {
        moveTo(x + cut, y); lineTo(x + w, y); lineTo(x + w, y + h - cut); lineTo(x + w - cut, y + h); lineTo(x, y + h); lineTo(x, y + cut); closePath()
    }

    /** Lightning bolt from (x0,y0) to (x1,y1), deterministic zig-zag. */
    fun bolt(x0: Float, y0: Float, x1: Float, y1: Float, seed: Int, segments: Int = 9) = path {
        moveTo(x0, y0)
        for (i in 1 until segments) {
            val t = i / segments.toFloat()
            val j = (kotlin.math.sin(seed * 12.9898 + i * 78.233) * 43758.5453).let { (it - kotlin.math.floor(it)).toFloat() - 0.5f }
            lineTo(x0 + (x1 - x0) * t + j * 70f, y0 + (y1 - y0) * t)
        }
        lineTo(x1, y1)
    }
}
