package com.superessence.cuttinx.core.overlay

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Geometry, animation and editing of the free elements ([OverlayItem]) of a scene. Shared by the
 * renderer and by the Android / PC editors, so a click lands on exactly what is drawn.
 */
object OverlayItems {
    /** Box of an element on a [w]×[h] canvas: centre, size and rotation in degrees. */
    data class Box(val cx: Float, val cy: Float, val w: Float, val h: Float, val rotation: Float) {
        val left get() = cx - w / 2
        val top get() = cy - h / 2
    }

    /** Animated offset of an element at time t (pixels / factor / degrees / opacity). */
    data class Pose(val dx: Float = 0f, val dy: Float = 0f, val scaleX: Float = 1f, val scaleY: Float = 1f, val rotation: Float = 0f, val alpha: Float = 1f)

    const val MIN_W = 0.02f
    const val MAX_W = 2f

    fun box(item: OverlayItem, w: Float, h: Float) = Box(item.x * w, item.y * h, item.w * w, item.w * w * item.aspect, item.rotation)

    /** True when ([px], [py]) is inside the (rotated) box, with [pad] pixels of tolerance. */
    fun contains(b: Box, px: Float, py: Float, pad: Float = 0f): Boolean {
        val a = -b.rotation * PI.toFloat() / 180f
        val dx = px - b.cx; val dy = py - b.cy
        val lx = dx * cos(a) - dy * sin(a); val ly = dx * sin(a) + dy * cos(a)
        return abs(lx) <= b.w / 2 + pad && abs(ly) <= b.h / 2 + pad
    }

    /** Topmost visible, unlocked element of [scene] under the point (canvas pixels). */
    fun hit(cfg: OverlayConfig, scene: SceneKind, x: Float, y: Float, w: Float, h: Float, pad: Float = 0f): OverlayItem? =
        cfg.itemsOf(scene).asReversed().firstOrNull { !it.hidden && !it.locked && contains(box(it, w, h), x, y, pad) }

    /** Position of a corner (sx, sy = ±1) of the rotated box, in canvas pixels. */
    fun corner(b: Box, sx: Int, sy: Int): Pair<Float, Float> {
        val a = b.rotation * PI.toFloat() / 180f
        val lx = sx * b.w / 2; val ly = sy * b.h / 2
        return (b.cx + lx * cos(a) - ly * sin(a)) to (b.cy + lx * sin(a) + ly * cos(a))
    }

    /** Rotation handle above the top edge of the box. */
    fun rotateHandle(b: Box, distance: Float): Pair<Float, Float> {
        val a = b.rotation * PI.toFloat() / 180f
        val ly = -b.h / 2 - distance
        return (b.cx - ly * sin(a)) to (b.cy + ly * cos(a))
    }

    fun pose(item: OverlayItem, t: Double, boxH: Float): Pose {
        if (item.anim == ItemAnim.NONE || t == 0.0) return Pose()
        val n = item.animSpeed.coerceIn(1, 8)
        val ph = (t / OverlayAnim.LOOP_SEC) * n
        val s = sin(2 * PI * ph).toFloat()
        return when (item.anim) {
            ItemAnim.NONE -> Pose()
            ItemAnim.FLOAT -> Pose(dy = -s * boxH * 0.08f, rotation = sin(2 * PI * ph + 1.0).toFloat() * 2f)
            ItemAnim.PULSE -> (1f + 0.07f * s).let { Pose(scaleX = it, scaleY = it) }
            ItemAnim.HEARTBEAT -> {
                val f = (ph % 1.0).toFloat()
                val beat = (if (f < 0.12f) sin(f / 0.12f * PI.toFloat()) else 0f) + (if (f in 0.18f..0.30f) 0.6f * sin((f - 0.18f) / 0.12f * PI.toFloat()) else 0f)
                (1f + 0.12f * beat).let { Pose(scaleX = it, scaleY = it) }
            }
            ItemAnim.SPIN -> Pose(rotation = (ph % 1.0).toFloat() * 360f)
            ItemAnim.SWING -> Pose(rotation = s * 12f)
            ItemAnim.BOUNCE -> {
                val b = abs(sin(PI * ph)).toFloat()
                val squash = 1f - 0.08f * (1f - b) * (1f - b)
                Pose(dy = -b * boxH * 0.18f, scaleX = 2f - squash, scaleY = squash)
            }
            ItemAnim.SHAKE -> Pose(dx = sin(2 * PI * ph * 7).toFloat() * boxH * 0.025f, dy = sin(2 * PI * ph * 5 + 1.3).toFloat() * boxH * 0.02f, rotation = sin(2 * PI * ph * 6).toFloat() * 2.5f)
            ItemAnim.BLINK -> Pose(alpha = 0.3f + 0.7f * (0.5f + 0.5f * cos(2 * PI * ph).toFloat()))
            ItemAnim.WOBBLE -> Pose(scaleX = 1f + 0.08f * s, scaleY = 1f - 0.08f * s)
            ItemAnim.ORBIT -> Pose(dx = s * boxH * 0.12f, dy = (cos(2 * PI * ph).toFloat() - 1f) * boxH * 0.12f)
            ItemAnim.NEON -> {
                val f = (ph % 1.0).toFloat()
                val off = (f in 0.52f..0.56f) || (f in 0.60f..0.62f) || (f in 0.9f..0.93f)
                Pose(alpha = if (off) 0.25f else 0.92f + 0.08f * s)
            }
        }
    }

    // ------------------------------------------------------------------ creation

    private var counter = 0
    fun newId(): String = "it_" + java.lang.Long.toString(System.currentTimeMillis(), 36) + "_" + (++counter)

    /** Small cascade so new elements never land exactly on top of each other. */
    private fun spot(cfg: OverlayConfig, scene: SceneKind): Pair<Float, Float> {
        val n = cfg.itemsOf(scene).size % 6
        return (0.5f + (n - 2.5f) * 0.04f) to (0.5f + (n - 2.5f) * 0.03f)
    }

    fun image(cfg: OverlayConfig, scene: SceneKind?, data: String, pxW: Int, pxH: Int, name: String): OverlayItem {
        val (x, y) = spot(cfg, scene ?: SceneKind.STARTING)
        val aspect = (pxH.toFloat() / pxW.coerceAtLeast(1)).coerceIn(0.05f, 20f)
        // pictures arrive at a comfortable size: a quarter of the width, never taller than half the height
        val w = minOf(0.25f, 0.5f * 9f / 16f / aspect).coerceAtLeast(0.05f)
        return OverlayItem(newId(), ItemType.IMAGE, name.take(40).ifBlank { "Image" }, scene, data = data, x = x, y = y, w = w, aspect = aspect)
    }

    fun text(cfg: OverlayConfig, scene: SceneKind?, text: String = "MON TEXTE"): OverlayItem {
        val (x, y) = spot(cfg, scene ?: SceneKind.STARTING)
        return OverlayItem(newId(), ItemType.TEXT, "Texte", scene, text = text, x = x, y = y, w = 0.36f, aspect = 0.22f, shadow = true)
    }

    fun sticker(cfg: OverlayConfig, scene: SceneKind?, s: Sticker): OverlayItem {
        val (x, y) = spot(cfg, scene ?: SceneKind.STARTING)
        return OverlayItem(newId(), ItemType.STICKER, s.label, scene, sticker = s, x = x, y = y, w = 0.1f, aspect = if (s == Sticker.ARROW) 0.6f else 1f, glow = true)
    }

    fun duplicate(item: OverlayItem) = item.copy(id = newId(), x = (item.x + 0.03f).coerceIn(0f, 1f), y = (item.y + 0.03f).coerceIn(0f, 1f), locked = false, hidden = false)

    // ------------------------------------------------------------------ editing

    fun add(cfg: OverlayConfig, item: OverlayItem) = cfg.copy(items = cfg.items + item)

    fun remove(cfg: OverlayConfig, id: String) = cfg.copy(items = cfg.items.filter { it.id != id })

    /** Keeps the centre on the canvas (an element can hang over the edge, never get lost outside). */
    fun place(item: OverlayItem, x: Float, y: Float) = item.copy(x = x.coerceIn(0f, 1f), y = y.coerceIn(0f, 1f))

    fun resize(item: OverlayItem, w: Float) = item.copy(w = w.coerceIn(MIN_W, MAX_W))

    /** Z order: +1 one step up, -1 one step down, [Int.MAX_VALUE] to the front, [Int.MIN_VALUE] to the back. */
    fun reorder(cfg: OverlayConfig, id: String, step: Int): OverlayConfig {
        val list = cfg.items.toMutableList()
        val i = list.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return cfg
        val it = list.removeAt(i)
        val j = when (step) { Int.MAX_VALUE -> list.size; Int.MIN_VALUE -> 0; else -> (i + step).coerceIn(0, list.size) }
        list.add(j, it)
        return cfg.copy(items = list)
    }

    /** Aligns the element on the canvas: [h] / [v] = -1 start, 0 centre, 1 end, null = unchanged. */
    fun align(item: OverlayItem, canvasW: Float, canvasH: Float, h: Int?, v: Int?): OverlayItem {
        val b = box(item, canvasW, canvasH)
        // half extent of the rotated box
        val a = item.rotation * PI.toFloat() / 180f
        val ex = (abs(b.w / 2 * cos(a)) + abs(b.h / 2 * sin(a))) / canvasW
        val ey = (abs(b.w / 2 * sin(a)) + abs(b.h / 2 * cos(a))) / canvasH
        val x = when (h) { null -> item.x; -1 -> ex; 0 -> 0.5f; else -> 1f - ex }
        val y = when (v) { null -> item.y; -1 -> ey; 0 -> 0.5f; else -> 1f - ey }
        return place(item, x, y)
    }
}
