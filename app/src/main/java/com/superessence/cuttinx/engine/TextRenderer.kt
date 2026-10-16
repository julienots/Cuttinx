package com.superessence.cuttinx.engine

import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.superessence.cuttinx.core.model.TextAlign
import com.superessence.cuttinx.core.model.TextContent
import com.superessence.cuttinx.core.model.TextStyle
import java.io.File
import kotlin.math.ceil
import kotlin.math.max

/** Renders CUTTINX text clips to bitmaps with the full typographic style. */
object TextRenderer {
    private val typefaceCache = HashMap<String, Typeface>()

    /** Resolves a font: a family name, a bundled asset font ("asset:fonts/x.ttf") or a file path. */
    fun typeface(style: TextStyle, fontFile: (String) -> File? = { null }, assets: android.content.res.AssetManager? = null): Typeface {
        val key = "${style.fontFamily}|${style.fontAssetId}|${style.bold}|${style.italic}|${style.weight}"
        typefaceCache[key]?.let { return it }
        val base: Typeface = run {
            style.fontAssetId?.let { id -> fontFile(id)?.takeIf { it.exists() }?.let { f -> runCatching { Typeface.createFromFile(f) }.getOrNull()?.let { return@run it } } }
            if (style.fontFamily.startsWith("asset:") && assets != null) runCatching { Typeface.createFromAsset(assets, style.fontFamily.removePrefix("asset:")) }.getOrNull()?.let { return@run it }
            Typeface.create(style.fontFamily, Typeface.NORMAL)
        }
        val tf = if (android.os.Build.VERSION.SDK_INT >= 28) Typeface.create(base, if (style.bold) max(style.weight, 600) else style.weight.coerceAtMost(500), style.italic)
        else Typeface.create(base, (if (style.bold) Typeface.BOLD else 0) or (if (style.italic) Typeface.ITALIC else 0))
        typefaceCache[key] = tf
        return tf
    }

    /** Text size in pixels for a canvas whose short side is [shortSide] px. */
    fun pixelSize(style: TextStyle, shortSide: Int) = (style.sizeSp * shortSide / 1080.0 * 1.6).toFloat()

    /**
     * Draws [content] (optionally only the first [reveal] fraction of characters) into a bitmap
     * sized to fit the text. [colorOverride] replaces the fill colour (keyframed colour).
     */
    fun render(content: TextContent, canvasW: Int, canvasH: Int, reveal: Double = 1.0, typeface: Typeface, colorOverride: Int? = null): Bitmap {
        val st = content.style
        val shortSide = minOf(canvasW, canvasH)
        val scale = shortSide / 1080f
        val raw = if (st.uppercase) content.text.uppercase() else content.text
        val text = if (reveal >= 1.0) raw else raw.take(ceil(raw.length * reveal.coerceIn(0.0, 1.0)).toInt())
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            this.typeface = typeface
            textSize = pixelSize(st, shortSide)
            color = colorOverride ?: st.color.toInt()
            letterSpacing = st.letterSpacing.toFloat()
        }
        val maxW = max(1, (canvasW * content.maxWidth).toInt())
        val measureText = raw.ifEmpty { " " }
        val align = when (st.align) { TextAlign.LEFT -> Layout.Alignment.ALIGN_NORMAL; TextAlign.CENTER -> Layout.Alignment.ALIGN_CENTER; TextAlign.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE }
        fun layout(s: CharSequence, w: Int) = StaticLayout.Builder.obtain(s, 0, s.length, paint, w).setAlignment(align)
            .setLineSpacing(0f, st.lineSpacing.toFloat()).setIncludePad(true).build()
        // measure with the full text so the box doesn't jump while typing
        val full = layout(measureText, maxW)
        var textW = 0f
        for (i in 0 until full.lineCount) textW = max(textW, full.getLineWidth(i))
        val w = max(1, ceil(textW).toInt())
        val pad = (st.backgroundPadding * scale).toFloat() + (st.strokeWidth * scale).toFloat() + (st.shadowRadius * scale).toFloat() * 2 + 4
        val bw = (w + pad * 2).toInt().coerceAtLeast(2)
        val bh = (full.height + pad * 2).toInt().coerceAtLeast(2)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val bgA = (st.backgroundColor ushr 24) and 0xFF
        if (bgA > 0) {
            val bp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = st.backgroundColor.toInt() }
            val inset = (st.shadowRadius * scale).toFloat() + 2
            val r = (st.backgroundCorner * scale).toFloat()
            canvas.drawRoundRect(RectF(inset, inset, bw - inset, bh - inset), r, r, bp)
        }
        val lay = layout(text, w)
        canvas.save()
        canvas.translate(pad, pad)
        if (st.strokeWidth > 0) {
            val sp = TextPaint(paint).apply {
                style = Paint.Style.STROKE; strokeWidth = (st.strokeWidth * scale * 2).toFloat(); strokeJoin = Paint.Join.ROUND
                color = st.strokeColor.toInt(); shader = null
                if (st.shadowRadius > 0) setShadowLayer((st.shadowRadius * scale).toFloat(), (st.shadowDx * scale).toFloat(), (st.shadowDy * scale).toFloat(), st.shadowColor.toInt())
            }
            StaticLayout.Builder.obtain(text, 0, text.length, sp, w).setAlignment(align).setLineSpacing(0f, st.lineSpacing.toFloat()).setIncludePad(true).build().draw(canvas)
            paint.clearShadowLayer()
        } else if (st.shadowRadius > 0) {
            paint.setShadowLayer((st.shadowRadius * scale).toFloat(), (st.shadowDx * scale).toFloat(), (st.shadowDy * scale).toFloat(), st.shadowColor.toInt())
        }
        if (st.gradientEnabled && colorOverride == null) {
            paint.shader = LinearGradient(0f, 0f, 0f, full.height.toFloat(), st.gradientStart.toInt(), st.gradientEnd.toInt(), Shader.TileMode.CLAMP)
        }
        lay.draw(canvas)
        canvas.restore()
        return bmp
    }
}
