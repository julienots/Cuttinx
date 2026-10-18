package com.superessence.cuttinx.core.overlay.render

import com.superessence.cuttinx.core.overlay.Motif
import com.superessence.cuttinx.core.overlay.OverlayAnim
import com.superessence.cuttinx.core.overlay.OverlayPalette
import com.superessence.cuttinx.core.gfx.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** Background patterns and particles. Every function is a pure function of the time [t]. */
object Motifs {
    /** Motifs whose look changes with time (drawn in the animated layer of split exports). */
    fun isMoving(m: Motif) = m !in setOf(Motif.HEX_GRID, Motif.CIRCUIT, Motif.HALFTONE, Motif.SEIGAIHA, Motif.GRAIN)

    private fun rnd(i: Int, s: Int): Float { val v = sin(i * 12.9898 + s * 78.233) * 43758.5453; return (v - floor(v)).toFloat() }
    private val tau = (2 * PI).toFloat()

    fun draw(c: Gfx, m: Motif, p: OverlayPalette, w: Float, h: Float, t: Double, intensity: Float = 1f) {
        val loop = (t / OverlayAnim.LOOP_SEC).toFloat() % 1f
        when (m) {
            Motif.HEX_GRID -> {
                val r = w / 38f; val pnt = stroke(col(p.primary, 0.10f * intensity), 1.5f)
                var row = 0; var y = 0f
                while (y < h + r) {
                    var x = if (row % 2 == 0) 0f else r * 1.5f
                    while (x < w + r) { c.path(Shapes.hexagon(x, y, r * 0.95f), pnt); x += r * 3f }
                    y += r * 0.866f; row++
                }
            }
            Motif.CIRCUIT -> {
                val pnt = stroke(col(p.primary, 0.16f * intensity), 2f); val dot = fill(col(p.primary, 0.3f * intensity))
                for (i in 0 until 46) {
                    var x = rnd(i, 1) * w; var y = rnd(i, 2) * h
                    val path = Path().moveTo(x, y)
                    repeat(3) { k ->
                        if ((i + k) % 2 == 0) x += (rnd(i, k + 3) - 0.5f) * w * 0.25f else y += (rnd(i, k + 7) - 0.5f) * h * 0.25f
                        path.lineTo(x, y)
                    }
                    c.path(path, pnt); c.circle(x, y, 5f, dot)
                }
            }
            Motif.HUD_RINGS -> {
                val cx = w * 0.5f; val cy = h * 0.5f
                for (k in 0 until 4) {
                    val r = h * (0.32f + k * 0.07f)
                    val rot = loop * 360f * (if (k % 2 == 0) 1 else -1) * (1 + k % 2)
                    c.save(); c.translate(cx, cy); c.rotate(rot)
                    val pnt = stroke(col(if (k % 2 == 0) p.glow else p.secondary, (0.22f - k * 0.03f) * intensity), if (k == 0) 4f else 2f)
                    for (seg in 0 until 3 + k) {
                        val start = seg * 360f / (3 + k)
                        c.arc(-r, -r, r, r, start, 360f / (3 + k) * 0.62f, pnt)
                    }
                    if (k == 1) for (tick in 0 until 72) {
                        val a = tick * 5f * PI.toFloat() / 180f
                        val len = if (tick % 6 == 0) 18f else 8f
                        c.line(cos(a) * r, sin(a) * r, cos(a) * (r + len), sin(a) * (r + len), stroke(col(p.glow, 0.18f * intensity), 2f))
                    }
                    c.restore()
                }
            }
            Motif.HALFTONE -> {
                val step = w / 64f; val pnt = fill(col(p.accent, 0.12f * intensity))
                var y = 0f; var row = 0
                while (y < h + step) {
                    var x = if (row % 2 == 0) 0f else step / 2
                    while (x < w + step) {
                        val d = (x / w + (1 - y / h)) / 2 // bigger dots towards a corner
                        c.circle(x, y, step * 0.42f * d, pnt); x += step
                    }
                    y += step * 0.866f; row++
                }
            }
            Motif.SPEED_LINES -> {
                val cx = w / 2; val cy = h / 2; val n = 90
                val flick = floor(t * 12).toInt()
                for (i in 0 until n) {
                    val a = (i + rnd(i, flick) * 0.6f) / n * tau
                    val r0 = h * (0.42f + rnd(i, flick + 1) * 0.25f)
                    val pnt = stroke(col(p.text, 0.08f * intensity), 2f + rnd(i, 5) * 6f)
                    c.line(cx + cos(a) * r0, cy + sin(a) * r0, cx + cos(a) * w, cy + sin(a) * w, pnt)
                }
            }
            Motif.SCANLINES -> {
                val pnt = fill(col(0xFF000000, 0.18f * intensity))
                var y = 0f; while (y < h) { c.rect(0f, y, w, 2f, pnt); y += 5f }
                val band = loop * (h + 200f) - 100f
                c.rect(0f, band, w, 90f, Paint().apply {
                    shader = linear(0f, band, 0f, band + 90f, intArrayOf(col(p.glow, 0f), col(p.glow, 0.10f * intensity), col(p.glow, 0f)))
                })
            }
            Motif.SUN_GRID -> {
                val horizon = h * 0.62f
                // striped sun
                val sunR = h * 0.24f; val sx = w / 2; val sy = horizon - sunR * 0.35f
                c.save()
                c.clipRect(0f, 0f, (w) - (0f), (horizon) - (0f))
                val sun = shaded(linear(0f, sy - sunR, 0f, sy + sunR, intArrayOf(col(p.accent, 0.9f * intensity), col(p.primary, 0.9f * intensity))))
                c.circle(sx, sy, sunR, sun)
                val cut = fill(col(p.background))
                for (k in 0 until 7) { val yy = sy + sunR * (0.05f + k * 0.14f); c.rect(sx - sunR, yy, sunR * 2, 4f + k * 2.2f, cut) }
                c.restore()
                // perspective grid, scrolling towards the viewer
                val g = stroke(col(p.secondary, 0.55f * intensity), 2f)
                c.rect(0f, horizon, (w) - (0f), (h) - (horizon), fill(col(p.background, 0.55f)))
                for (k in -14..14) c.line(w / 2 + k * w * 0.012f, horizon, w / 2 + k * w * 0.16f, h, g)
                for (k in 0 until 12) {
                    val z = ((k + loop) / 12f)
                    val y = horizon + (h - horizon) * z * z
                    c.line(0f, y, w, y, g)
                }
                c.line(0f, horizon, w, horizon, stroke(col(p.primary, intensity), 3f).glow(6f))
            }
            Motif.SEIGAIHA -> {
                val r = w / 22f; val band = h * 0.32f
                c.save(); c.clipRect(0f, h - band, (w) - (0f), (h) - (h - band))
                var row = 0; var y = h - band
                while (y < h + r) {
                    var x = if (row % 2 == 0) 0f else r
                    while (x < w + r * 2) {
                        for (k in 0 until 4) {
                            val rr = r * (1f - k * 0.22f)
                            c.circle(x, y, rr, fill(col(if (k % 2 == 0) p.backgroundAlt else p.background, 1f)))
                            c.circle(x, y, rr, stroke(col(p.secondary, 0.35f * intensity), 2f))
                        }
                        x += r * 2
                    }
                    y += r * 0.5f; row++
                }
                c.restore()
            }
            Motif.WAVES -> {
                for (k in 0 until 4) {
                    val base = h * (0.78f + k * 0.06f)
                    val path = Path().apply {
                        moveTo(0f, h)
                        var x = 0f
                        while (x <= w) { lineTo(x, base + sin(x / w * tau * (2 + k) + loop * tau * (if (k % 2 == 0) 1 else -1)) * h * 0.018f); x += 12f }
                        lineTo(w, h); closePath()
                    }
                    c.path(path, fill(col(if (k % 2 == 0) p.primary else p.secondary, (0.10f + k * 0.04f) * intensity)))
                }
            }
            Motif.SUN_RAYS -> {
                c.save(); c.translate(w * 0.85f, -h * 0.1f); c.rotate(loop * 360f / 6f)
                for (k in 0 until 18) {
                    val a0 = k * tau / 18; val a1 = a0 + tau / 36
                    val path = Path().apply { moveTo(0f, 0f); lineTo(cos(a0) * w * 1.6f, sin(a0) * w * 1.6f); lineTo(cos(a1) * w * 1.6f, sin(a1) * w * 1.6f); closePath() }
                    c.path(path, fill(col(p.text, 0.07f * intensity)))
                }
                c.restore()
            }
            Motif.CLOUDS -> {
                for (i in 0 until 7) {
                    val y = h * (0.1f + rnd(i, 1) * 0.6f); val s = w * (0.08f + rnd(i, 2) * 0.08f)
                    val x = ((rnd(i, 3) + loop * (if (i % 2 == 0) 1 else -1) * 0.25f) % 1f + 1f) % 1f * (w + s * 4) - s * 2
                    val pnt = fill(col(0xFFFFFFFF, 0.28f * intensity)).glow(8f)
                    c.circle(x, y, s * 0.5f, pnt); c.circle(x + s * 0.55f, y + s * 0.1f, s * 0.42f, pnt); c.circle(x - s * 0.55f, y + s * 0.12f, s * 0.36f, pnt)
                    c.rrect(x - s * 0.9f, y, s * 1.9f, s * 0.42f, s * 0.21f, pnt)
                }
            }
            Motif.GRAIN -> {
                val pnt = fill(col(p.text, 0.05f * intensity))
                for (i in 0 until 2600) c.rect(rnd(i, 1) * w, rnd(i, 2) * h, 2f, 2f, pnt)
            }
            Motif.GALAXY -> {
                for (k in 0 until 3) {
                    val cx = w * (0.25f + k * 0.28f); val cy = h * (0.35f + (k % 2) * 0.3f); val r = h * (0.45f - k * 0.08f)
                    c.circle(cx, cy, r, shaded(radial(cx, cy, r, intArrayOf(col(listOf(p.primary, p.secondary, p.accent)[k], 0.28f * intensity), 0))))
                }
                particles(c, Motif.SPARKLES, p, w, h, t, intensity, 140)
            }
            Motif.BATS -> {
                // a big moon behind the bats
                val mx = w * 0.82f; val my = h * 0.22f; val mr = h * 0.13f
                c.circle(mx, my, mr * 1.6f, fill(col(p.primary, 0.12f * intensity)).glow(30f))
                c.circle(mx, my, mr, fill(col(0xFFFFE9B8, 0.85f * intensity)))
                c.circle(mx - mr * 0.3f, my - mr * 0.2f, mr * 0.18f, fill(col(0xFFE8C98A, 0.6f * intensity)))
                particles(c, m, p, w, h, t, intensity)
            }
            Motif.LIGHTNING -> {
                val f = floor(t / OverlayAnim.LOOP_SEC * 2).toInt() // two strikes per loop
                val local = ((t / OverlayAnim.LOOP_SEC * 2) % 1.0).toFloat()
                val flash = if (local < 0.12f) 1f - local / 0.12f else 0f
                if (flash > 0f) {
                    c.rect(0f, 0f, w, h, fill(col(p.glow, 0.10f * flash * intensity)))
                    val x0 = w * (0.15f + rnd(f, 3) * 0.7f)
                    val bolt = Shapes.bolt(x0, 0f, x0 + (rnd(f, 4) - 0.5f) * 300f, h * 0.7f, f)
                    c.path(bolt, stroke(col(p.glow, flash * intensity), 12f).glow(14f))
                    c.path(bolt, stroke(col(0xFFFFFFFF, flash * intensity), 4f))
                }
            }
            else -> particles(c, m, p, w, h, t, intensity)
        }
    }

    /** Particle motifs (snow, sakura, hearts...). */
    fun particles(c: Gfx, m: Motif, p: OverlayPalette, w: Float, h: Float, t: Double, intensity: Float = 1f, count: Int = OverlayAnim.defaultCount(m)) {
        val colors = listOf(p.primary, p.secondary, p.accent, p.glow)
        for ((i, q) in OverlayAnim.particles(m, t, w.toInt(), h.toInt(), count).withIndex()) {
            val a = q.alpha * intensity
            c.save(); c.translate(q.x, q.y); c.rotate(q.rotation)
            when (m) {
                Motif.SNOW -> if (q.variant == 0) {
                    for (k in 0 until 3) { c.rotate(60f); c.line(-q.size, 0f, q.size, 0f, stroke(col(0xFFFFFFFF, a), 2f)) }
                } else c.circle(0f, 0f, q.size * 0.4f, fill(col(0xFFFFFFFF, a * 0.9f)).glow(1.5f))
                Motif.PIXEL_STARS -> { c.rotate(-q.rotation); val s = (q.size / 4).coerceAtLeast(2f).let { floor(it) }; c.rect(-s, -s * 3, s * 2, s * 6, fill(col(colors[q.variant % 4], a))); c.rect(-s * 3, -s, s * 6, s * 2, fill(col(colors[q.variant % 4], a))) }
                Motif.SPARKLES -> c.path(Shapes.sparkle(0f, 0f, q.size), fill(col(if (q.variant % 2 == 0) 0xFFFFFFFF else p.glow, a)).also { if (q.size > 9f) it.glow(1.2f) })
                Motif.SAKURA -> { c.path(Shapes.sakura(q.size), fill(col(if (q.variant % 2 == 0) 0xFFFFC4D6 else 0xFFFFE4EC, a))); c.path(Shapes.sakura(q.size), stroke(col(0xFFE88AA8, a * 0.6f), 1.2f)) }
                Motif.HEARTS -> c.path(Shapes.heart(0f, 0f, q.size), fill(col(colors[q.variant % 3], a)))
                Motif.BUBBLES -> { c.circle(0f, 0f, q.size / 2, stroke(col(0xFFFFFFFF, a * 0.7f), 2f)); c.circle(-q.size * 0.15f, -q.size * 0.15f, q.size * 0.1f, fill(col(0xFFFFFFFF, a))) }
                Motif.LEAVES -> { c.path(Shapes.leaf(q.size), fill(col(listOf(0xFFE8862EL, 0xFFC0392BL, 0xFFF2B33DL, 0xFF9C4F1DL)[q.variant % 4], a))); c.line(0f, -q.size * 0.45f, 0f, q.size * 0.45f, stroke(col(0xFF5A2A12, a * 0.6f), 1.2f)) }
                Motif.BATS -> { c.rotate(-q.rotation + sin(q.rotation / 20f) * 10f); c.scale(1f, 0.75f + 0.25f * kotlin.math.abs(sin(q.rotation / 8f))); val bat = Shapes.bat(q.size * 2); c.path(bat, fill(col(0xFF0A0510, a))); c.path(bat, stroke(col(p.primary, a * 0.7f), 1.5f)) }
                Motif.CONFETTI -> c.rect(-q.size / 2, -q.size / 4, q.size, q.size / 2, fill(col(colors[i % 4], a)))
                Motif.FLOWERS -> {
                    for (k in 0 until 5) { c.rotate(72f); c.save(); c.translate(0f, -q.size * 0.32f); c.path(Shapes.petal(q.size * 0.7f), fill(col(if (q.variant % 2 == 0) 0xFFFFFFFF else p.secondary, a))); c.restore() }
                    c.circle(0f, 0f, q.size * 0.16f, fill(col(0xFFFFD45C, a)))
                }
                else -> {}
            }
            c.restore()
        }
    }
}
