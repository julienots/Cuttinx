package com.superessence.cuttinx.core.gfx

import com.superessence.cuttinx.core.overlay.TitleFont

/**
 * Minimal 2D drawing API used by the overlay / identity renderers. Implemented with Skia on PC
 * and android.graphics on Android, so both apps draw exactly the same designs.
 */
interface Gfx {
    fun save()
    fun restore()
    fun translate(dx: Float, dy: Float)
    fun rotate(degrees: Float)
    fun scale(sx: Float, sy: Float)
    fun clipRect(x: Float, y: Float, w: Float, h: Float)
    fun clipRRect(x: Float, y: Float, w: Float, h: Float, r: Float)
    fun clipPath(path: Path)
    fun clear(color: Int)
    fun rect(x: Float, y: Float, w: Float, h: Float, p: Paint)
    fun rrect(x: Float, y: Float, w: Float, h: Float, r: Float, p: Paint)
    fun circle(cx: Float, cy: Float, r: Float, p: Paint)
    fun line(x0: Float, y0: Float, x1: Float, y1: Float, p: Paint)
    fun path(path: Path, p: Paint)
    fun arc(left: Float, top: Float, right: Float, bottom: Float, startDeg: Float, sweepDeg: Float, p: Paint)
    /** Draws [text] with its baseline at [y], starting at [x] (font fallback is handled by the backend). */
    fun text(text: String, x: Float, y: Float, font: FontId, size: Float, p: Paint)
    fun textWidth(text: String, font: FontId, size: Float): Float
    /** (ascent, descent) of the font: ascent is negative (above the baseline). */
    fun metrics(font: FontId, size: Float): Pair<Float, Float>
    /** Draws an encoded picture (PNG / JPEG) fitted inside the box, keeping its proportions (custom logos). */
    fun image(data: ByteArray, x: Float, y: Float, w: Float, h: Float, alpha: Float = 1f) {}
}

/** An offscreen surface able to encode itself as PNG (exports). */
interface Frame {
    val gfx: Gfx
    val width: Int
    val height: Int
    fun png(): ByteArray
}

/** Platform services: offscreen surfaces and bundled font files. */
interface Raster {
    fun frame(width: Int, height: Int): Frame
    fun fontBytes(font: FontId): ByteArray?
}

/** Bundled fonts (same files on Android and PC, see assets/fonts). */
enum class FontId(val file: String) {
    TECH("Righteous_400Regular.ttf"), COMIC("Bangers_400Regular.ttf"), PIXEL("PressStart2P_400Regular.ttf"),
    IMPACT("Anton_400Regular.ttf"), ROUNDED("Fredoka_600SemiBold.ttf"), ELEGANT("PlayfairDisplay_700Bold_Italic.ttf"),
    SCRIPT("Pacifico_400Regular.ttf"), BRUSH("PermanentMarker_400Regular.ttf"), CONDENSED("BebasNeue_400Regular.ttf"),
    BOLD("Montserrat_800ExtraBold.ttf"), REGULAR("Montserrat_500Medium.ttf");

    companion object {
        fun of(f: TitleFont) = when (f) {
            TitleFont.TECH, TitleFont.JAPANESE -> TECH; TitleFont.COMIC -> COMIC; TitleFont.PIXEL -> PIXEL; TitleFont.IMPACT -> IMPACT
            TitleFont.ROUNDED -> ROUNDED; TitleFont.ELEGANT -> ELEGANT; TitleFont.SCRIPT -> SCRIPT; TitleFont.BRUSH -> BRUSH; TitleFont.CONDENSED -> CONDENSED
        }
    }
}

sealed interface Shader {
    class Linear(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val colors: IntArray, val positions: FloatArray? = null) : Shader
    class Radial(val cx: Float, val cy: Float, val r: Float, val colors: IntArray, val positions: FloatArray? = null) : Shader
}

class Shadow(val dx: Float, val dy: Float, val blur: Float, val color: Int)

/** Paint settings; [alpha] multiplies the colour / shader opacity. */
class Paint(
    var color: Int = 0xFF000000.toInt(),
    var stroke: Boolean = false,
    var strokeWidth: Float = 1f,
    /** Gaussian blur sigma (glows), 0 = sharp. */
    var blur: Float = 0f,
    var shader: Shader? = null,
    var shadow: Shadow? = null,
    var alpha: Float = 1f,
) {
    fun copy() = Paint(color, stroke, strokeWidth, blur, shader, shadow, alpha)
    fun glow(sigma: Float) = apply { blur = sigma }
}

/** Resolution-independent path (converted by each backend). */
class Path {
    sealed interface Op
    class MoveTo(val x: Float, val y: Float) : Op
    class LineTo(val x: Float, val y: Float) : Op
    class QuadTo(val x1: Float, val y1: Float, val x: Float, val y: Float) : Op
    class CubicTo(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val x: Float, val y: Float) : Op
    class Circle(val cx: Float, val cy: Float, val r: Float) : Op
    class RectOp(val x: Float, val y: Float, val w: Float, val h: Float) : Op
    object Close : Op

    val ops = ArrayList<Op>()
    fun moveTo(x: Float, y: Float) = apply { ops += MoveTo(x, y) }
    fun lineTo(x: Float, y: Float) = apply { ops += LineTo(x, y) }
    fun quadTo(x1: Float, y1: Float, x: Float, y: Float) = apply { ops += QuadTo(x1, y1, x, y) }
    fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) = apply { ops += CubicTo(x1, y1, x2, y2, x, y) }
    fun addCircle(cx: Float, cy: Float, r: Float) = apply { ops += Circle(cx, cy, r) }
    fun addRect(x: Float, y: Float, w: Float, h: Float) = apply { ops += RectOp(x, y, w, h) }
    fun closePath() = apply { ops += Close }
}

/** ARGB (Long, as stored in the model) to an ARGB Int with an extra opacity factor. */
fun col(argb: Long, alpha: Float = 1f): Int {
    val a = (((argb ushr 24) and 0xFF) * alpha.coerceIn(0f, 1f)).toInt()
    return (a shl 24) or (argb and 0xFFFFFF).toInt()
}

fun fill(color: Int) = Paint(color)
fun stroke(color: Int, width: Float) = Paint(color, stroke = true, strokeWidth = width)
fun linear(x0: Float, y0: Float, x1: Float, y1: Float, colors: IntArray, pos: FloatArray? = null): Shader = Shader.Linear(x0, y0, x1, y1, colors, pos)
fun radial(cx: Float, cy: Float, r: Float, colors: IntArray, pos: FloatArray? = null): Shader = Shader.Radial(cx, cy, r, colors, pos)
fun shaded(s: Shader) = Paint(0xFFFFFFFF.toInt(), shader = s)

enum class Align { LEFT, CENTER, RIGHT }

/** Text helpers on top of [Gfx]: alignment, letter spacing, fitting, vertical centring. */
class TextDrawer(private val g: Gfx) {
    fun width(text: String, font: FontId, size: Float): Float = g.textWidth(text, font, size)

    /** Largest size ≤ [max] such that [text] fits in [maxWidth]. */
    fun fit(text: String, font: FontId, max: Float, maxWidth: Float): Float {
        val w = width(text, font, max)
        return if (w <= maxWidth || w <= 0f) max else (max * maxWidth / w).coerceAtLeast(8f)
    }

    fun draw(text: String, x: Float, y: Float, font: FontId, size: Float, paint: Paint, align: Align = Align.CENTER, spacing: Float = 0f) {
        if (text.isEmpty()) return
        if (spacing == 0f) {
            val w = width(text, font, size)
            val sx = when (align) { Align.LEFT -> x; Align.CENTER -> x - w / 2; Align.RIGHT -> x - w }
            g.text(text, sx, y, font, size, paint)
            return
        }
        val chars = ArrayList<String>()
        var i = 0
        while (i < text.length) { val cp = text.codePointAt(i); chars += String(Character.toChars(cp)); i += Character.charCount(cp) }
        val widths = chars.map { width(it, font, size) }
        val total = widths.sum() + spacing * (chars.size - 1)
        var cx = when (align) { Align.LEFT -> x; Align.CENTER -> x - total / 2; Align.RIGHT -> x - total }
        for ((k, ch) in chars.withIndex()) { g.text(ch, cx, y, font, size, paint); cx += widths[k] + spacing }
    }

    /** Baseline that visually centres a line of [size] on [centerY]. */
    fun baselineFor(centerY: Float, font: FontId, size: Float): Float {
        val (a, d) = g.metrics(font, size)
        return centerY - (a + d) / 2
    }
}

// ---------------------------------------------------------------------- shapes

object Shapes {
    fun heart(cx: Float, cy: Float, s: Float) = Path()
        .moveTo(cx, cy + s * 0.35f)
        .cubicTo(cx - s * 0.55f, cy - s * 0.05f, cx - s * 0.45f, cy - s * 0.55f, cx, cy - s * 0.25f)
        .cubicTo(cx + s * 0.45f, cy - s * 0.55f, cx + s * 0.55f, cy - s * 0.05f, cx, cy + s * 0.35f)
        .closePath()

    fun star(cx: Float, cy: Float, r: Float, points: Int = 5, inner: Float = 0.45f) = Path().apply {
        for (i in 0 until points * 2) {
            val a = -Math.PI / 2 + i * Math.PI / points
            val rr = if (i % 2 == 0) r else r * inner
            val x = cx + (rr * kotlin.math.cos(a)).toFloat(); val y = cy + (rr * kotlin.math.sin(a)).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        closePath()
    }

    fun sparkle(cx: Float, cy: Float, r: Float) = star(cx, cy, r, 4, 0.22f)

    fun petal(s: Float) = Path()
        .moveTo(0f, -s * 0.5f)
        .cubicTo(s * 0.45f, -s * 0.35f, s * 0.35f, s * 0.35f, 0f, s * 0.5f)
        .cubicTo(-s * 0.35f, s * 0.35f, -s * 0.45f, -s * 0.35f, 0f, -s * 0.5f)
        .closePath()

    /** Cherry-blossom petal with the characteristic notch. */
    fun sakura(s: Float) = Path()
        .moveTo(0f, s * 0.5f)
        .cubicTo(-s * 0.5f, s * 0.1f, -s * 0.35f, -s * 0.45f, -s * 0.08f, -s * 0.5f)
        .lineTo(0f, -s * 0.36f)
        .lineTo(s * 0.08f, -s * 0.5f)
        .cubicTo(s * 0.35f, -s * 0.45f, s * 0.5f, s * 0.1f, 0f, s * 0.5f)
        .closePath()

    fun leaf(s: Float) = Path().moveTo(0f, -s * 0.5f).quadTo(s * 0.5f, 0f, 0f, s * 0.5f).quadTo(-s * 0.5f, 0f, 0f, -s * 0.5f).closePath()

    fun bat(s: Float) = Path()
        .moveTo(0f, -s * 0.12f)
        .lineTo(s * 0.08f, -s * 0.22f).lineTo(s * 0.1f, -s * 0.08f)
        .quadTo(s * 0.3f, -s * 0.3f, s * 0.55f, -s * 0.2f)
        .quadTo(s * 0.45f, -s * 0.05f, s * 0.5f, s * 0.08f)
        .quadTo(s * 0.38f, -s * 0.02f, s * 0.3f, s * 0.12f)
        .quadTo(s * 0.2f, s * 0.02f, s * 0.12f, s * 0.15f)
        .quadTo(s * 0.05f, s * 0.08f, 0f, s * 0.2f)
        .quadTo(-s * 0.05f, s * 0.08f, -s * 0.12f, s * 0.15f)
        .quadTo(-s * 0.2f, s * 0.02f, -s * 0.3f, s * 0.12f)
        .quadTo(-s * 0.38f, -s * 0.02f, -s * 0.5f, s * 0.08f)
        .quadTo(-s * 0.45f, -s * 0.05f, -s * 0.55f, -s * 0.2f)
        .quadTo(-s * 0.3f, -s * 0.3f, -s * 0.1f, -s * 0.08f)
        .lineTo(-s * 0.08f, -s * 0.22f)
        .closePath()

    fun hexagon(cx: Float, cy: Float, r: Float) = Path().apply {
        for (i in 0 until 6) {
            val a = Math.PI / 3 * i
            val x = cx + (r * kotlin.math.cos(a)).toFloat(); val y = cy + (r * kotlin.math.sin(a)).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        closePath()
    }

    /** Rectangle with cut corners (HUD / esport plates). */
    fun cutRect(x: Float, y: Float, w: Float, h: Float, cut: Float) = Path()
        .moveTo(x + cut, y).lineTo(x + w, y).lineTo(x + w, y + h - cut).lineTo(x + w - cut, y + h).lineTo(x, y + h).lineTo(x, y + cut).closePath()

    /** Lightning bolt from (x0,y0) to (x1,y1), deterministic zig-zag. */
    fun bolt(x0: Float, y0: Float, x1: Float, y1: Float, seed: Int, segments: Int = 9) = Path().apply {
        moveTo(x0, y0)
        for (i in 1 until segments) {
            val t = i / segments.toFloat()
            val j = (kotlin.math.sin(seed * 12.9898 + i * 78.233) * 43758.5453).let { (it - kotlin.math.floor(it)).toFloat() - 0.5f }
            lineTo(x0 + (x1 - x0) * t + j * 70f, y0 + (y1 - y0) * t)
        }
        lineTo(x1, y1)
    }
}
