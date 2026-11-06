package com.superessence.cuttinx.desktop.engine

import com.superessence.cuttinx.core.model.TextAlign
import com.superessence.cuttinx.core.model.TextContent
import com.superessence.cuttinx.core.model.TextStyle
import org.jetbrains.skia.*
import org.jetbrains.skia.paragraph.Alignment
import org.jetbrains.skia.paragraph.FontCollection
import org.jetbrains.skia.paragraph.Paragraph
import org.jetbrains.skia.paragraph.ParagraphBuilder
import org.jetbrains.skia.paragraph.ParagraphStyle
import org.jetbrains.skia.paragraph.Shadow
import org.jetbrains.skia.paragraph.TypefaceFontProvider
import java.io.File
import kotlin.math.ceil
import kotlin.math.max
import org.jetbrains.skia.paragraph.TextStyle as SkTextStyle

/**
 * Renders text clips exactly like the Android TextRenderer (same size rule, padding, stroke,
 * shadow, gradient and box), with system fallback for emoji and every alphabet.
 */
class PcText(private val fontFile: (String) -> File? = { null }) {
    private val provider = TypefaceFontProvider()
    private val registered = HashSet<String>()
    private val collection = FontCollection().apply {
        setAssetFontManager(provider)
        setDefaultFontManager(FontMgr.default)
        setEnableFallback(true)
    }
    private val cache = object : LinkedHashMap<String, Image>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Image>?) = size > 48
    }

    /** Family name to use for [st]: bundled fonts and imported font files are registered on first use. */
    private fun family(st: TextStyle): String {
        st.fontAssetId?.let { id ->
            val key = "asset_$id"
            if (key in registered) return key
            fontFile(id)?.takeIf { it.exists() }?.let { f ->
                FontMgr.default.makeFromFile(f.path)?.let { provider.registerTypeface(it, key); registered += key; return key }
            }
        }
        val fam = st.fontFamily
        if (fam.startsWith("asset:")) {
            val res = "/" + fam.removePrefix("asset:")
            if (fam in registered) return fam
            PcText::class.java.getResourceAsStream(res)?.use { it.readBytes() }?.let { b ->
                FontMgr.default.makeFromData(Data.makeFromBytes(b))?.let { provider.registerTypeface(it, fam); registered += fam; return fam }
            }
            return SANS
        }
        return when {
            fam.contains("mono") -> MONO
            fam.startsWith("serif") -> SERIF
            fam.contains("condensed") -> "Arial Narrow"
            fam.contains("black") -> "Arial Black"
            else -> SANS
        }
    }

    private fun style(st: TextStyle, size: Float, scale: Float, fill: Boolean, height: Float): SkTextStyle = SkTextStyle().apply {
        setFontFamilies(arrayOf(family(st), SANS, "Segoe UI Emoji", "Noto Color Emoji", "Apple Color Emoji"))
        fontSize = size
        setHeight(height)
        val w = if (st.bold) max(st.weight, 600) else st.weight.coerceAtMost(500)
        fontStyle = FontStyle(w, FontWidth.NORMAL, if (st.italic) FontSlant.ITALIC else FontSlant.UPRIGHT)
        letterSpacing = (st.letterSpacing * size).toFloat()
        val p = Paint().apply { isAntiAlias = true }
        if (fill) {
            p.color = st.color.toInt()
        } else {
            p.mode = PaintMode.STROKE; p.strokeWidth = (st.strokeWidth * scale * 2).toFloat(); p.strokeJoin = PaintStrokeJoin.ROUND
            p.color = st.strokeColor.toInt()
        }
        foreground = p
        val wantsShadow = st.shadowRadius > 0 && (!fill || st.strokeWidth <= 0)
        if (wantsShadow) addShadow(Shadow(st.shadowColor.toInt(), (st.shadowDx * scale).toFloat(), (st.shadowDy * scale).toFloat(), st.shadowRadius * scale / 2.0))
    }

    private fun paragraph(text: String, st: TextStyle, size: Float, scale: Float, fill: Boolean, width: Float, gradientH: Float = 0f): Paragraph {
        val ps = ParagraphStyle().apply {
            alignment = when (st.align) { TextAlign.LEFT -> Alignment.LEFT; TextAlign.CENTER -> Alignment.CENTER; TextAlign.RIGHT -> Alignment.RIGHT }
        }
        val ts = style(st, size, scale, fill, st.lineSpacing.toFloat())
        if (fill && st.gradientEnabled && gradientH > 0) {
            ts.foreground = Paint().apply {
                isAntiAlias = true
                shader = Shader.makeLinearGradient(0f, 0f, 0f, gradientH, Gradient(Gradient.Colors(arrayOf(Color4f(st.gradientStart.toInt()), Color4f(st.gradientEnd.toInt())), floatArrayOf(0f, 1f), FilterTileMode.CLAMP, ColorSpace.sRGB)))
            }
        }
        return ParagraphBuilder(ps, collection).pushStyle(ts).addText(text).build().layout(width)
    }

    /** Text size in pixels for a canvas whose short side is [shortSide] px (same rule as Android). */
    fun pixelSize(style: TextStyle, shortSide: Int) = (style.sizeSp * shortSide / 1080.0 * 1.6).toFloat()

    /** Image of [content] sized to fit the text, at canvas size [canvasW]x[canvasH]. */
    @Synchronized fun render(content: TextContent, canvasW: Int, canvasH: Int, reveal: Double = 1.0): Image {
        val key = "${content.hashCode()}|$canvasW|$canvasH|${if (reveal >= 1.0) -1 else (reveal * 50).toInt()}"
        cache[key]?.let { return it }
        val st = content.style
        val shortSide = minOf(canvasW, canvasH)
        val scale = shortSide / 1080f
        val size = pixelSize(st, shortSide)
        val raw = if (st.uppercase) content.text.uppercase() else content.text
        val text = if (reveal >= 1.0) raw else raw.take(ceil(raw.length * reveal.coerceIn(0.0, 1.0)).toInt())
        val maxW = max(1, (canvasW * content.maxWidth).toInt()).toFloat()
        // measure with the full text so the box doesn't jump while typing
        val full = paragraph(raw.ifEmpty { " " }, st, size, scale, true, maxW)
        val w = max(1f, ceil(full.longestLine + 1))
        val pad = (st.backgroundPadding * scale).toFloat() + (st.strokeWidth * scale).toFloat() + (st.shadowRadius * scale).toFloat() * 2 + 4
        val bw = (w + pad * 2).toInt().coerceAtLeast(2)
        val bh = (full.height + pad * 2).toInt().coerceAtLeast(2)
        val surface = Surface.makeRasterN32Premul(bw, bh)
        val c = surface.canvas
        c.clear(0)
        val bgA = (st.backgroundColor ushr 24) and 0xFF
        if (bgA > 0) {
            val inset = (st.shadowRadius * scale).toFloat() + 2
            val r = (st.backgroundCorner * scale).toFloat()
            c.drawRRect(RRect.makeLTRB(inset, inset, bw - inset, bh - inset, r), Paint().apply { isAntiAlias = true; color = st.backgroundColor.toInt() })
        }
        if (text.isNotEmpty()) {
            if (st.strokeWidth > 0) paragraph(text, st, size, scale, false, w).paint(c, pad, pad)
            paragraph(text, st, size, scale, true, w, full.height).paint(c, pad, pad)
        }
        val img = surface.makeImageSnapshot()
        surface.close()
        cache[key] = img
        return img
    }

    companion object {
        private val os = System.getProperty("os.name").lowercase()
        val SANS = if (os.contains("win")) "Segoe UI" else if (os.contains("mac")) "Helvetica Neue" else "DejaVu Sans"
        val SERIF = if (os.contains("win")) "Times New Roman" else "DejaVu Serif"
        val MONO = if (os.contains("win")) "Consolas" else "DejaVu Sans Mono"
    }
}
