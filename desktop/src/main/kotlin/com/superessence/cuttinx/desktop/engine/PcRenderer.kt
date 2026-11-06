package com.superessence.cuttinx.desktop.engine

import com.superessence.cuttinx.core.anim.KeyframeEvaluator
import com.superessence.cuttinx.core.effects.EffectKind
import com.superessence.cuttinx.core.effects.EffectRegistry
import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.render.FrameEvaluator
import com.superessence.cuttinx.core.render.LayerState
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.jetbrains.skia.BlendMode as SkBlend

/**
 * PC compositor: draws one frame of a project with Skia, following exactly the same rules as the
 * Android engine (placement, keyframes, animations, effects, transitions, masks, chroma key,
 * texts). Used by the preview and by the export.
 */
class PcRenderer(val pool: DecoderPool, val text: PcText, private val fileOf: (MediaAsset) -> File? = PcMedia::fileOf) {
    private val sampling = SamplingMode.LINEAR

    /** Draws the frame at [timeUs] on [surface] (its size is the output size). */
    fun render(p: Project, timeUs: Long, surface: Surface) {
        val w = surface.width; val h = surface.height
        val c = surface.canvas
        c.clear(p.format.backgroundColor.toInt())
        for (layer in FrameEvaluator.visualLayers(p, timeUs)) {
            runCatching { drawLayer(p, layer, surface, c, w, h) }
        }
    }

    private fun drawLayer(p: Project, l: LayerState, surface: Surface, c: Canvas, w: Int, h: Int) {
        val clip = l.clip
        val mod = l.transition
        val t = l.transform
        when {
            clip.kind == ClipKind.ADJUSTMENT -> {
                val fx = clip.effects.filter { it.enabled && EffectRegistry[it.effectId]?.kind == EffectKind.VIDEO }
                if (fx.isEmpty()) return
                val snap = surface.makeImageSnapshot()
                val out = applyEffects(snap, clip, fx, l.localUs)
                c.drawImage(out, 0f, 0f, Paint().apply { alpha = (255 * t.opacity * (mod?.alpha ?: 1.0)).roundToInt().coerceIn(0, 255) })
                if (out !== snap) out.close()
                snap.close()
            }
            clip.text != null && (clip.kind == ClipKind.TEXT || clip.kind == ClipKind.SUBTITLE) -> {
                val a = FrameEvaluator.textAnimation(clip, l.localUs)
                val img = text.render(clip.text!!, w, h, a.reveal)
                val sx = t.scaleX * a.scale * (if (clip.transform.flipH) -1 else 1) * (mod?.scale ?: 1.0)
                val sy = t.scaleY * a.scale * (if (clip.transform.flipV) -1 else 1) * (mod?.scale ?: 1.0)
                val alpha = t.opacity * a.alpha * (mod?.alpha ?: 1.0)
                draw(c, img, (t.posX + a.dx + (mod?.dx ?: 0.0)) * w, (t.posY + a.dy + (mod?.dy ?: 0.0)) * h, sx, sy, t.rotation + a.rotation + (mod?.rotation ?: 0.0), alpha, clip.blendMode)
            }
            else -> {
                val src = source(p, clip, l.sourceUs, w, h) ?: return
                val (iw, ih) = itemSize(p, clip, w, h)
                val prepared = prepare(src, p.asset(clip.assetId), clip, l, iw, ih)
                val pl = FrameEvaluator.placement(iw, ih, w, h, clip.transform, t, mod)
                draw(c, prepared, pl.centerX, pl.centerY, pl.scaleX * iw / prepared.width, pl.scaleY * ih / prepared.height, pl.rotation, t.opacity * (mod?.alpha ?: 1.0), clip.blendMode)
                if (prepared !== src) prepared.close()
            }
        }
    }

    private fun draw(c: Canvas, img: Image, cx: Double, cy: Double, sx: Double, sy: Double, rot: Double, alpha: Double, blend: BlendMode) {
        if (alpha <= 0.002) return
        c.save()
        c.translate(cx.toFloat(), cy.toFloat())
        if (rot != 0.0) c.rotate(rot.toFloat())
        c.scale(sx.toFloat(), sy.toFloat())
        val paint = Paint().apply {
            this.alpha = (255 * alpha).roundToInt().coerceIn(0, 255)
            blendMode = when (blend) {
                BlendMode.NORMAL -> SkBlend.SRC_OVER; BlendMode.ADD -> SkBlend.PLUS; BlendMode.MULTIPLY -> SkBlend.MULTIPLY
                BlendMode.SCREEN -> SkBlend.SCREEN; BlendMode.OVERLAY -> SkBlend.OVERLAY; BlendMode.DARKEN -> SkBlend.DARKEN
                BlendMode.LIGHTEN -> SkBlend.LIGHTEN; BlendMode.DIFFERENCE -> SkBlend.DIFFERENCE
            }
        }
        val iw = img.width.toFloat(); val ih = img.height.toFloat()
        c.drawImageRect(img, Rect.makeWH(iw, ih), Rect.makeXYWH(-iw / 2, -ih / 2, iw, ih), sampling, paint, true)
        c.restore()
    }

    /** Raw picture of a media / colour clip at source time [sourceUs]. */
    private fun source(p: Project, clip: Clip, sourceUs: Long, w: Int, h: Int): Image? {
        if (clip.kind == ClipKind.COLOR) return solid(clip.color.toInt(), w, h)
        val a = p.asset(clip.assetId) ?: return null
        if (a.missing) return null
        val f = fileOf(a) ?: return null
        return when (a.type) {
            MediaType.IMAGE, MediaType.STICKER -> PcMedia.loadImage(f)
            MediaType.VIDEO -> pool.video(clip.id, f).frameAt(sourceUs)
            else -> null
        }
    }

    private var solidCache: Triple<Int, Long, Image>? = null
    private fun solid(color: Int, w: Int, h: Int): Image {
        val key = w.toLong() shl 32 or h.toLong()
        solidCache?.let { (col, k, img) -> if (col == color && k == key) return img }
        val s = Surface.makeRasterN32Premul(max(2, w), max(2, h)); s.canvas.clear(color)
        return s.makeImageSnapshot().also { s.close(); solidCache = Triple(color, key, it) }
    }

    /**
     * Orientation, crop and scale to the item size, then chroma key, user effects, transition
     * shader and mask. Returns [src] itself when nothing has to be done (fast path).
     */
    private fun prepare(src: Image, asset: MediaAsset?, clip: Clip, l: LayerState, iw: Int, ih: Int): Image {
        val rot = asset?.rotationDegrees ?: 0
        val cr = clip.transform.crop
        val fx = clip.effects.filter { it.enabled && EffectRegistry[it.effectId]?.kind == EffectKind.VIDEO }
        val mod = l.transition
        val needsWork = rot != 0 || !cr.isIdentity || fx.isNotEmpty() || clip.chromaKey.enabled || clip.mask.shape != MaskShape.NONE || mod?.shaderId != null
        if (!needsWork) return src
        // 1. orientation (rotation metadata of phone videos), crop and size
        var oriented = src
        if (rot != 0) {
            val ow = if (rot % 180 != 0) src.height else src.width
            val oh = if (rot % 180 != 0) src.width else src.height
            val os = Surface.makeRasterN32Premul(ow, oh)
            os.canvas.translate(ow / 2f, oh / 2f); os.canvas.rotate(rot.toFloat())
            os.canvas.drawImage(src, -src.width / 2f, -src.height / 2f)
            oriented = os.makeImageSnapshot(); os.close()
        }
        val ow = oriented.width.toFloat(); val oh = oriented.height.toFloat()
        val s = Surface.makeRasterN32Premul(iw, ih)
        s.canvas.clear(0)
        val srcRect = Rect.makeXYWH((cr.left * ow).toFloat(), (cr.top * oh).toFloat(), max(1.0, ow * (1 - cr.left - cr.right)).toFloat(), max(1.0, oh * (1 - cr.top - cr.bottom)).toFloat())
        s.canvas.drawImageRect(oriented, srcRect, Rect.makeWH(iw.toFloat(), ih.toFloat()), sampling, Paint().apply { blendMode = SkBlend.SRC }, true)
        var img = s.makeImageSnapshot()
        s.close()
        if (oriented !== src) oriented.close()
        val rel = l.localUs
        // 2. chroma key
        if (clip.chromaKey.enabled) {
            val ck = clip.chromaKey
            img = pass(img, "_chroma_key", FloatArray(16).also {
                it[0] = ((ck.color shr 16) and 0xFF) / 255f; it[1] = ((ck.color shr 8) and 0xFF) / 255f; it[2] = (ck.color and 0xFF) / 255f
                it[3] = ck.tolerance.toFloat(); it[4] = ck.softness.toFloat(); it[5] = ck.spill.toFloat()
            }, rel / 1e6f, 1f)
        }
        // 3. user effects
        img = applyEffects(img, clip, fx, rel, closeInput = true)
        // 4. transition shader
        mod?.shaderId?.let { sid -> img = pass(img, sid, FrameEvaluator.shaderParams(sid, mod.shaderParams), rel / 1e6f, mod.shaderProgress.toFloat()) }
        // 5. mask
        val m = clip.mask
        if (m.shape != MaskShape.NONE) {
            fun kf(prop: String, d: Double) = KeyframeEvaluator.evaluate(clip.keyframes[prop], rel, d).toFloat()
            val arr = FloatArray(16)
            arr[0] = when (m.shape) { MaskShape.RECTANGLE -> 1f; MaskShape.ELLIPSE -> 2f; MaskShape.LINEAR -> 3f; MaskShape.MIRROR -> 4f; else -> 1f }
            arr[1] = kf(Prop.MASK_X, m.centerX); arr[2] = kf(Prop.MASK_Y, m.centerY); arr[3] = kf(Prop.MASK_W, m.width); arr[4] = kf(Prop.MASK_H, m.height)
            arr[5] = m.rotation.toFloat(); arr[6] = kf(Prop.MASK_FEATHER, m.feather); arr[7] = if (m.invert) 1f else 0f
            img = pass(img, "_mask", arr, rel / 1e6f, 1f)
        }
        return img
    }

    private fun applyEffects(input: Image, clip: Clip, fx: List<EffectInstance>, rel: Long, closeInput: Boolean = false): Image {
        var img = input
        for (f in fx) {
            val next = pass(img, f.effectId, FrameEvaluator.shaderParams(f.effectId, FrameEvaluator.effectParams(clip, f, rel)), rel / 1e6f, 1f, close = closeInput || img !== input)
            img = next
        }
        return img
    }

    /** One shader pass; the input is closed when [close] and the pass succeeded. */
    private fun pass(img: Image, effectId: String, params: FloatArray, time: Float, progress: Float, close: Boolean = true): Image {
        val sh = Sksl.apply(effectId, img, params, time, progress) ?: return img
        val s = Surface.makeRasterN32Premul(img.width, img.height)
        s.canvas.clear(0)
        s.canvas.drawRect(Rect.makeWH(img.width.toFloat(), img.height.toFloat()), Paint().apply { shader = sh; blendMode = SkBlend.SRC })
        val out = s.makeImageSnapshot()
        s.close(); sh.close()
        if (close) img.close()
        return out
    }

    companion object {
        /** Size of the picture that a clip delivers to the compositor (same rule as Android). */
        fun itemSize(p: Project, c: Clip, outW: Int, outH: Int): Pair<Int, Int> {
            val (nw, nh) = when (c.kind) {
                ClipKind.COLOR -> outW to outH
                else -> p.asset(c.assetId)?.let { a -> (if (a.displayWidth > 0) a.displayWidth else outW) to (if (a.displayHeight > 0) a.displayHeight else outH) } ?: (outW to outH)
            }
            val cr = c.transform.crop
            val cw = max(2.0, nw * (1 - cr.left - cr.right)); val ch = max(2.0, nh * (1 - cr.top - cr.bottom))
            val fit = if (c.transform.fill) max(outW / cw, outH / ch) else min(outW / cw, outH / ch)
            val k = min(1.0, fit * 1.6)
            fun even(v: Double) = max(2, (v / 2).roundToInt() * 2)
            return even(cw * k) to even(ch * k)
        }
    }
}
