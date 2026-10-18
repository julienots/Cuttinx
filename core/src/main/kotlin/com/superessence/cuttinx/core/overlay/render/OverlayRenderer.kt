package com.superessence.cuttinx.core.overlay.render

import com.superessence.cuttinx.core.overlay.*
import com.superessence.cuttinx.core.gfx.*
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws every Twitch overlay of CUTTINX PRO with Skia. The same code renders the live preview,
 * the PNG exports and every frame of the animated (APNG) exports, so what you see is what OBS shows.
 */
class OverlayRenderer {

    /** Which parts of an animated scene to draw (split exports = static background + animated layer). */
    enum class Layer { ALL, STATIC, MOVING }

    private fun pal(theme: OverlayTheme, cfg: OverlayConfig) = cfg.palette ?: theme.palette

    // ================================================================== scenes

    /**
     * @param countdown text shown in the countdown plate of the "starting" scene (null = left empty:
     *   the HTML export draws a live countdown there).
     */
    fun drawScene(c: Gfx, theme: OverlayTheme, cfg: OverlayConfig, scene: SceneKind, t: Double, layer: Layer = Layer.ALL, countdown: String? = "BIENTÔT") {
        val w = cfg.width.toFloat(); val h = cfg.height.toFloat()
        val p = pal(theme, cfg)
        val k = w / OverlayLayout.REF_W
        when {
            scene == SceneKind.ALERT -> { if (layer != Layer.STATIC) drawAlert(c, theme, cfg, t); return }
            scene.fullBackground -> {
                val static = layer != Layer.MOVING; val moving = layer != Layer.STATIC
                if (static) background(c, p, w, h)
                for (m in theme.motifs) {
                    val mv = Motifs.isMoving(m)
                    if ((mv && moving && cfg.particles) || (!mv && static)) Motifs.draw(c, m, p, w, h, if (layer == Layer.STATIC) 0.0 else t)
                }
                if (static) fullSceneTexts(c, theme, cfg, scene, p, w, h, k, countdown)
                if (moving) fullSceneAnimation(c, theme, cfg, scene, p, w, h, k, t)
            }
            else -> {
                if (layer == Layer.MOVING) return
                for (z in OverlayLayout.zones(scene, cfg)) zone(c, theme, cfg, p, z, k)
            }
        }
    }

    private fun background(c: Gfx, p: OverlayPalette, w: Float, h: Float) {
        c.rect(0f, 0f, w, h, shaded(linear(0f, 0f, w * 0.3f, h, intArrayOf(col(p.background), col(p.backgroundAlt)))))
        c.circle(w / 2, h * 0.45f, h * 0.75f, shaded(radial(w / 2, h * 0.45f, h * 0.75f, intArrayOf(col(p.primary, 0.22f), 0))))
        // vignette
        c.rect(0f, 0f, w, h, shaded(radial(w / 2, h / 2, hypot(w, h) / 2, intArrayOf(0, 0, col(0xFF000000, 0.45f)), floatArrayOf(0f, 0.65f, 1f))))
    }

    private fun fullSceneTexts(c: Gfx, theme: OverlayTheme, cfg: OverlayConfig, scene: SceneKind, p: OverlayPalette, w: Float, h: Float, k: Float, countdown: String?) {
        val title = OverlayLayout.zone(scene, cfg, ZoneKind.TITLE)!!
        val info = OverlayLayout.zone(scene, cfg, ZoneKind.INFO)!!
        val socials = OverlayLayout.zone(scene, cfg, ZoneKind.SOCIALS)!!
        val tf = FontId.of(theme.font)
        // badge + channel
        badge(c, theme, p, 60f * k, 56f * k, theme.badge, k)
        c.txt.draw(cfg.channel.uppercase(), w - 60f * k, 92f * k, FontId.BOLD, 34f * k, fill(col(p.text, 0.9f)), Align.RIGHT, spacing = 4f * k)
        // title plate + title
        plate(c, theme, p, title, k, strong = true)
        val titleText = cfg.titleOf(scene)
        val size = c.txt.fit(titleText, tf, (if (theme.font == TitleFont.PIXEL) 74f else 118f) * k, title.w - 120f * k)
        val base = c.txt.baselineFor(title.y + title.h / 2, tf, size)
        c.txt.draw(titleText, w / 2 + 6f * k, base + 6f * k, tf, size, fill(col(0xFF000000, 0.35f)).glow(6f * k), spacing = titleSpacing(theme, k))
        c.txt.draw(titleText, w / 2, base, tf, size, titlePaint(theme, p, title), spacing = titleSpacing(theme, k))
        // info line
        val line = when (scene) {
            SceneKind.STARTING -> null
            SceneKind.BRB -> "Le live reprend dans un instant"
            SceneKind.PAUSE -> "On souffle et on revient !"
            else -> "À très vite — ${cfg.schedule}"
        }
        if (scene == SceneKind.STARTING) {
            plate(c, theme, p, info, k, strong = false)
            c.txt.draw("DÉBUT DANS", w / 2, info.y + 30f * k, FontId.BOLD, 20f * k, fill(col(p.text, 0.7f)), spacing = 6f * k)
            if (countdown != null) c.txt.draw(countdown, w / 2, info.y + info.h - 26f * k, tf, 58f * k, fill(col(p.text)))
        } else if (line != null) {
            c.txt.draw(line, w / 2, c.txt.baselineFor(info.y + info.h / 2, FontId.REGULAR, 40f * k), FontId.REGULAR, 40f * k, fill(col(p.text, 0.9f)))
        }
        // socials + schedule
        val soc = (cfg.socials.filter { it.isNotBlank() } + if (scene == SceneKind.STARTING) listOf(cfg.schedule) else emptyList()).joinToString("   •   ")
        if (soc.isNotBlank()) c.txt.draw(soc, w / 2, c.txt.baselineFor(socials.y + socials.h / 2, FontId.BOLD, 30f * k), FontId.BOLD, c.txt.fit(soc, FontId.BOLD, 30f * k, socials.w), fill(col(p.text, 0.85f)))
        cornerDecorations(c, theme, p, w, h, k)
    }

    private fun titleSpacing(theme: OverlayTheme, k: Float) = when (theme.font) { TitleFont.CONDENSED, TitleFont.TECH, TitleFont.IMPACT -> 6f * k; else -> 0f }

    private fun titlePaint(theme: OverlayTheme, p: OverlayPalette, z: Zone) = Paint().apply {
        shader = when (theme.frame) {
            FrameStyle.COMIC, FrameStyle.PIXEL, FrameStyle.SHARP -> null
            else -> linear(0f, z.y + z.h * 0.25f, 0f, z.y + z.h * 0.8f, intArrayOf(col(p.text), col(lighten(p.primary, 0.35f))))
        }
        color = col(if (theme.frame == FrameStyle.COMIC) p.primary else p.text)
    }

    private fun lighten(c: Long, f: Float): Long {
        fun ch(s: Int) = (((c shr s) and 0xFF) + ((255 - ((c shr s) and 0xFF)) * f)).toLong().coerceIn(0, 255)
        return 0xFF000000L or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Animated layer of a full scene: glow pulse, loading dots, theme-specific accents. */
    private fun fullSceneAnimation(c: Gfx, theme: OverlayTheme, cfg: OverlayConfig, scene: SceneKind, p: OverlayPalette, w: Float, h: Float, k: Float, t: Double) {
        val title = OverlayLayout.zone(scene, cfg, ZoneKind.TITLE)!!
        val pulse = OverlayAnim.pulse(t)
        // breathing glow line under the title plate
        val y = title.bottom + 8f * k
        val len = title.w * (0.35f + 0.25f * pulse)
        c.line(w / 2 - len / 2, y, w / 2 + len / 2, y, stroke(col(p.glow, 0.85f), 6f * k).glow(10f * k))
        c.line(w / 2 - len / 2, y, w / 2 + len / 2, y, stroke(col(0xFFFFFFFF, 0.9f), 2f * k))
        // loading dots
        if (scene == SceneKind.STARTING || scene == SceneKind.BRB || scene == SceneKind.PAUSE) {
            val phase = (t / OverlayAnim.LOOP_SEC * 4) % 1.0
            for (i in 0 until 3) {
                val a = (sin((phase - i * 0.15) * 2 * PI) * 0.5 + 0.5).toFloat()
                c.circle(w / 2 + (i - 1) * 34f * k, title.y - 34f * k, (7f + 5f * a) * k, fill(col(p.accent, 0.4f + 0.6f * a)))
            }
        }
        // sweeping shine over the plate
        val sx = ((t / OverlayAnim.LOOP_SEC) % 1.0).toFloat() * (title.w + 600f * k) - 300f * k + title.x
        c.save(); c.clipRect(title.x, title.y, title.w, title.h)
        c.rect(sx, title.y, 160f * k, title.h, Paint().apply {
            shader = linear(sx, 0f, sx + 160f * k, 0f, intArrayOf(0, col(0xFFFFFFFF, 0.18f), 0))
        })
        c.restore()
    }

    // ================================================================== zones (layouts)

    private fun zone(c: Gfx, theme: OverlayTheme, cfg: OverlayConfig, p: OverlayPalette, z: Zone, k: Float) {
        when (z.kind) {
            ZoneKind.SCREEN -> frame(c, theme, p, z, k)
            ZoneKind.CAMERA -> {
                frame(c, theme, p, z, k)
                // name plate overlapping the bottom edge of the camera
                val pw = min(z.w * 0.8f, max(260f * k, c.txt.width(cfg.channel, FontId.of(theme.font), 36f * k) + 80f * k)); val ph = 54f * k
                val plateZone = Zone(ZoneKind.TITLE, z.x + (z.w - pw) / 2, z.bottom - ph / 2, pw, ph)
                plate(c, theme, p, plateZone, k, strong = true)
                val tf = FontId.of(theme.font); val s = c.txt.fit(cfg.channel, tf, 34f * k, pw - 40f * k)
                c.txt.draw(cfg.channel, plateZone.x + pw / 2, c.txt.baselineFor(plateZone.y + ph / 2, tf, s), tf, s, fill(col(p.text)), spacing = titleSpacing(theme, k) / 2)
            }
            ZoneKind.CHAT -> {
                c.rrect(z.x, z.y, z.w, z.h, radius(theme, k), fill(col(p.background, 0.55f)))
                frame(c, theme, p, z, k)
                val hh = 52f * k
                // header inside the chat panel (the camera name plate may sit just above it)
                val header = Zone(ZoneKind.TITLE, z.x + 20f * k, z.y + 18f * k, min(220f * k, z.w - 40f * k), hh)
                plate(c, theme, p, header, k, strong = true)
                c.txt.draw("CHAT", header.x + header.w / 2, c.txt.baselineFor(header.y + hh / 2, FontId.BOLD, 26f * k), FontId.BOLD, 26f * k, fill(col(p.text)), spacing = 6f * k)
            }
            ZoneKind.INFO -> {
                plate(c, theme, p, z, k, strong = false)
                // theme decoration inside the info bar (pattern + a few static particles)
                c.save(); c.clipRRect(z.x, z.y, z.w, z.h, radius(theme, k))
                c.translate(z.x, z.y)
                for (m in theme.motifs) Motifs.draw(c, m, p, z.w, z.h, 0.7, 0.55f)
                c.restore()
                val tf = FontId.of(theme.font)
                val s = c.txt.fit(cfg.channel, tf, min(z.h * 0.42f, 80f * k), z.w * 0.5f)
                c.txt.draw(cfg.channel, z.x + 40f * k, c.txt.baselineFor(z.y + z.h * 0.38f, tf, s), tf, s, titlePaint(theme, p, z), Align.LEFT, titleSpacing(theme, k))
                val ts = min(z.h * 0.2f, 32f * k)
                c.txt.draw(cfg.tagline, z.x + 40f * k, c.txt.baselineFor(z.y + z.h * 0.74f, FontId.REGULAR, ts), FontId.REGULAR, c.txt.fit(cfg.tagline, FontId.REGULAR, ts, z.w * 0.5f), fill(col(p.text, 0.85f)), Align.LEFT)
                val right = (cfg.socials.filter { it.isNotBlank() } + cfg.schedule).take(3)
                right.forEachIndexed { i, s2 ->
                    val yy = z.y + z.h * (0.3f + i * 0.24f) * (if (right.size == 1) 1.6f else 1f)
                    c.txt.draw(s2, z.right - 40f * k, c.txt.baselineFor(yy, FontId.BOLD, ts), FontId.BOLD, c.txt.fit(s2, FontId.BOLD, ts, z.w * 0.4f), fill(col(if (i == 0) p.accent else p.text, 0.92f)), Align.RIGHT)
                }
                badge(c, theme, p, z.x + z.w * 0.56f, z.y - 18f * k, theme.badge, k * 0.8f)
            }
            ZoneKind.TITLE -> {
                plate(c, theme, p, z, k, strong = true)
                val tf = FontId.of(theme.font); val s = c.txt.fit(cfg.tagline, tf, z.h * 0.6f, z.w - 60f * k)
                c.txt.draw(cfg.tagline, z.x + z.w / 2, c.txt.baselineFor(z.y + z.h / 2, tf, s), tf, s, fill(col(p.text)), spacing = titleSpacing(theme, k) / 2)
            }
            ZoneKind.SOCIALS -> {
                val soc = cfg.socials.filter { it.isNotBlank() }.joinToString("   •   ")
                if (soc.isNotBlank()) {
                    val s = c.txt.fit(soc, FontId.BOLD, 30f * k, z.w - 40f * k)
                    c.txt.draw(soc, z.x + z.w / 2, c.txt.baselineFor(z.y + z.h / 2, FontId.BOLD, s), FontId.BOLD, s, fill(col(p.text, 0.9f)).also { it.shadow = Shadow(0f, 2f * k, 4f * k, col(0xFF000000, 0.7f)) })
                }
            }
        }
    }

    private fun radius(theme: OverlayTheme, k: Float) = when (theme.frame) {
        FrameStyle.SOFT, FrameStyle.BUBBLE -> 28f * k; FrameStyle.NEON -> 18f * k; FrameStyle.ELEGANT, FrameStyle.WOOD -> 8f * k; else -> 0f
    }

    /** Border drawn *around* a zone: the inside stays transparent for the camera / game capture. */
    fun frame(c: Gfx, theme: OverlayTheme, p: OverlayPalette, z: Zone, k: Float) {
        val r = radius(theme, k)
        val outer = RR(z.x, z.y, z.w, z.h, r)
        when (theme.frame) {
            FrameStyle.HUD -> {
                c.rect(z.x, z.y, z.w, z.h, stroke(col(p.primary, 0.55f), 2f * k))
                val L = min(z.w, z.h) * 0.16f
                val br = stroke(col(p.glow), 5f * k)
                for ((cx, cy, sx, sy) in listOf(Quad(z.x, z.y, 1f, 1f), Quad(z.right, z.y, -1f, 1f), Quad(z.x, z.bottom, 1f, -1f), Quad(z.right, z.bottom, -1f, -1f))) {
                    val path = Path().apply { moveTo(cx + sx * L, cy - sy * 8f * k); lineTo(cx - sx * 8f * k, cy - sy * 8f * k); lineTo(cx - sx * 8f * k, cy + sy * L) }
                    c.path(path, br.copy().glow(6f * k)); c.path(path, br)
                }
                for (i in 0 until 6) c.rect(z.x + 24f * k + i * 18f * k, z.bottom + 14f * k, 10f * k, 6f * k, fill(col(if (i < 4) p.secondary else p.primary)))
            }
            FrameStyle.COMIC -> {
                c.rect(z.x + 12f * k, z.y + 12f * k, z.w, z.h, stroke(col(p.secondary), 12f * k))
                c.rect(z.x, z.y, z.w, z.h, stroke(col(0xFF000000), 10f * k))
                c.rect(z.x - 2f * k, z.y - 2f * k, z.w + 4f * k, z.h + 4f * k, stroke(col(p.primary), 3f * k))
            }
            FrameStyle.PIXEL -> {
                val s = 8f * k
                c.rect(z.x - s, z.y - s, z.w + 2 * s, z.h + 2 * s, stroke(col(p.primary), s))
                var x = z.x - s * 2
                var i = 0
                while (x < z.right + s) { if (i % 2 == 0) { c.rect(x, z.y - s * 2.5f, s, s, fill(col(p.secondary))); c.rect(x, z.bottom + s * 1.5f, s, s, fill(col(p.accent))) }; x += s; i++ }
            }
            FrameStyle.NEON -> {
                c.rrect(outer, stroke(col(p.glow, 0.9f), 10f * k).glow(16f * k))
                c.rrect(outer, stroke(col(p.glow), 4f * k))
                c.rrect(outer.inflate(8f * k), stroke(col(p.secondary, 0.6f), 2f * k))
            }
            FrameStyle.SOFT -> {
                c.rrect(outer.inflate(6f * k), stroke(col(0xFF000000, 0.25f), 10f * k).glow(10f * k))
                c.rrect(outer, stroke(col(p.primary), 8f * k))
                c.rrect(outer.inflate(7f * k), stroke(col(0xFFFFFFFF, 0.8f), 3f * k))
            }
            FrameStyle.ELEGANT -> {
                c.rrect(outer, stroke(col(p.primary), 2.5f * k))
                c.rrect(outer.inflate(10f * k), stroke(col(p.primary, 0.6f), 1.2f * k))
                for ((cx, cy) in listOf(z.x to z.y, z.right to z.y, z.x to z.bottom, z.right to z.bottom)) {
                    c.save(); c.translate(cx, cy); c.rotate(45f)
                    c.rect(-9f * k, -9f * k, 18f * k, 18f * k, fill(col(p.accent)))
                    c.rect(-9f * k, -9f * k, 18f * k, 18f * k, stroke(col(p.primary), 2f * k))
                    c.restore()
                }
            }
            FrameStyle.BRUSH -> {
                val ink = stroke(col(if (theme.palette.background > 0xFFC0C0C0) 0xFF111111 else p.primary), 9f * k)
                for (pass in 0 until 2) {
                    val j = 5f * k * (pass + 1)
                    val path = Path().apply {
                        moveTo(z.x - j, z.y + j * 0.4f); lineTo(z.right + j * 0.6f, z.y - j * 0.3f); lineTo(z.right - j * 0.2f, z.bottom + j); lineTo(z.x + j * 0.5f, z.bottom - j * 0.4f); closePath()
                    }
                    c.path(path, ink.copy().apply { strokeWidth = if (pass == 0) 9f * k else 3f * k; color = col(if (pass == 0) p.primary else p.secondary, if (pass == 0) 1f else 0.8f) })
                }
            }
            FrameStyle.SHARP -> {
                c.rect(z.x, z.y, z.w, z.h, stroke(col(p.primary), 4f * k))
                val path = Path().apply { moveTo(z.x - 6f * k, z.y - 6f * k); lineTo(z.x + z.w * 0.3f, z.y - 6f * k); lineTo(z.x + z.w * 0.3f - 14f * k, z.y + 8f * k); lineTo(z.x - 6f * k, z.y + 8f * k); closePath() }
                c.path(path, fill(col(p.secondary)))
                c.rect(z.right - z.w * 0.18f, z.bottom - 4f * k, z.w * 0.18f + 6f * k, 10f * k, fill(col(p.primary)))
            }
            FrameStyle.BUBBLE -> {
                c.rrect(outer, stroke(col(0xFFFFFFFF), 12f * k))
                c.rrect(outer, stroke(col(p.primary), 5f * k))
                for ((i, pt) in listOf(z.x to z.y, z.right to z.y, z.x to z.bottom, z.right to z.bottom).withIndex()) {
                    c.path(Shapes.heart(pt.first, pt.second, 46f * k), fill(col(if (i % 2 == 0) p.primary else p.accent)))
                    c.path(Shapes.heart(pt.first, pt.second, 46f * k), stroke(col(0xFFFFFFFF), 3f * k))
                }
            }
            FrameStyle.WOOD -> {
                c.rrect(outer.inflate(6f * k), stroke(col(0xFF5A3A22), 14f * k))
                c.rrect(outer.inflate(6f * k), stroke(col(p.primary, 0.5f), 2f * k))
                c.rrect(outer, stroke(col(p.accent, 0.8f), 2f * k))
            }
        }
    }

    private data class Quad(val a: Float, val b: Float, val c: Float, val d: Float)

    /** Filled plate (title, name, info bar) in the theme style. */
    fun plate(c: Gfx, theme: OverlayTheme, p: OverlayPalette, z: Zone, k: Float, strong: Boolean) {
        val r = radius(theme, k)
        val bgFill = Paint().apply {
            shader = linear(z.x, z.y, z.x, z.bottom, intArrayOf(col(p.backgroundAlt, if (strong) 0.95f else 0.85f), col(p.background, if (strong) 0.95f else 0.85f)))
        }
        when (theme.frame) {
            FrameStyle.HUD, FrameStyle.SHARP -> {
                val path = Shapes.cutRect(z.x, z.y, z.w, z.h, min(z.h * 0.35f, 28f * k))
                c.path(path, bgFill)
                c.path(path, stroke(col(if (theme.frame == FrameStyle.HUD) p.glow else p.primary), 3f * k))
                c.rect(z.x, z.bottom - 6f * k, z.w * 0.25f, 6f * k, fill(col(p.secondary)))
            }
            FrameStyle.COMIC -> {
                c.rect(z.x + 10f * k, z.y + 10f * k, z.w, z.h, fill(col(0xFF000000)))
                c.rect(z.x, z.y, z.w, z.h, fill(col(p.secondary)))
                c.rect(z.x, z.y, z.w, z.h, stroke(col(0xFF000000), 6f * k))
            }
            FrameStyle.PIXEL -> {
                c.rect(z.x, z.y, z.w, z.h, fill(col(p.background, 0.92f)))
                c.rect(z.x, z.y, z.w, z.h, stroke(col(p.primary), 6f * k))
                c.rect(z.x + 8f * k, z.y + 8f * k, z.w - 16f * k, z.h - 16f * k, stroke(col(p.secondary, 0.6f), 2f * k))
            }
            FrameStyle.NEON -> {
                val rr = RR(z.x, z.y, z.w, z.h, r)
                c.rrect(rr, bgFill)
                c.rrect(rr, stroke(col(p.glow), 5f * k).glow(12f * k))
                c.rrect(rr, stroke(col(0xFFFFFFFF, 0.9f), 2f * k))
            }
            FrameStyle.BRUSH -> {
                val path = Path().apply {
                    moveTo(z.x - 10f * k, z.y + 6f * k); lineTo(z.right + 14f * k, z.y - 4f * k); lineTo(z.right + 4f * k, z.bottom + 8f * k); lineTo(z.x + 6f * k, z.bottom - 2f * k); closePath()
                }
                c.path(path, fill(col(p.primary, 0.95f)))
                c.path(path, stroke(col(0xFF000000, 0.8f), 4f * k))
            }
            else -> {
                val rr = RR(z.x, z.y, z.w, z.h, if (r == 0f) 10f * k else r)
                c.rrect(rr.inflate(4f * k), fill(col(0xFF000000, 0.25f)).glow(14f * k))
                c.rrect(rr, bgFill)
                c.rrect(rr, stroke(col(p.primary), if (theme.frame == FrameStyle.ELEGANT) 2f * k else 4f * k))
            }
        }
    }

    fun badge(c: Gfx, theme: OverlayTheme, p: OverlayPalette, x: Float, y: Float, label: String, k: Float) {
        val s = 24f * k
        val w = c.txt.width(label, FontId.BOLD, s) + 56f * k; val h = 46f * k
        val rr = RR(x, y, w, h, h / 2)
        c.rrect(rr, fill(col(p.primary)))
        c.circle(x + 22f * k, y + h / 2, 7f * k, fill(col(0xFFFFFFFF)))
        c.txt.draw(label, x + 36f * k, c.txt.baselineFor(y + h / 2, FontId.BOLD, s), FontId.BOLD, s, fill(col(contrastOn(p.primary))), Align.LEFT)
    }

    private fun contrastOn(c: Long): Long {
        val l = 0.2126 * ((c shr 16) and 0xFF) + 0.7152 * ((c shr 8) and 0xFF) + 0.0722 * (c and 0xFF)
        return if (l > 150) 0xFF111111 else 0xFFFFFFFF
    }

    private fun cornerDecorations(c: Gfx, theme: OverlayTheme, p: OverlayPalette, w: Float, h: Float, k: Float) {
        when (theme.frame) {
            FrameStyle.HUD -> {
                val pnt = stroke(col(p.glow, 0.8f), 3f * k)
                c.line(40f * k, h - 40f * k, 300f * k, h - 40f * k, pnt); c.line(40f * k, h - 40f * k, 40f * k, h - 140f * k, pnt)
                c.line(w - 40f * k, 140f * k, w - 40f * k, 40f * k + 120f * k, pnt)
                c.txt.draw("PWR 100% · SYS OK", 60f * k, h - 56f * k, FontId.BOLD, 18f * k, fill(col(p.glow, 0.8f)), Align.LEFT, 3f * k)
            }
            FrameStyle.ELEGANT -> {
                val pnt = stroke(col(p.primary, 0.7f), 1.5f * k)
                c.rect(36f * k, 36f * k, w - 72f * k, h - 72f * k, pnt)
                c.rect(48f * k, 48f * k, w - 96f * k, h - 96f * k, pnt.copy().apply { strokeWidth = 0.8f * k })
            }
            FrameStyle.COMIC -> {
                c.path(Shapes.star(w - 170f * k, h - 150f * k, 120f * k, 12, 0.7f), fill(col(p.primary)))
                c.path(Shapes.star(w - 170f * k, h - 150f * k, 120f * k, 12, 0.7f), stroke(col(0xFF000000), 5f * k))
                c.txt.draw(theme.badge, w - 170f * k, h - 132f * k, FontId.of(theme.font), 48f * k, fill(col(0xFF000000)))
            }
            FrameStyle.PIXEL, FrameStyle.NEON -> {
                for (i in 0 until 3) c.rect(w - (60f + i * 30f) * k, h - 60f * k, 18f * k, 18f * k, fill(col(listOf(p.primary, p.secondary, p.accent)[i])))
            }
            else -> {}
        }
    }

    // ================================================================== alert

    fun drawAlert(c: Gfx, theme: OverlayTheme, cfg: OverlayConfig, t: Double, user: String = "Pseudo") {
        val p = pal(theme, cfg); val k = cfg.width / OverlayLayout.REF_W
        val z = OverlayLayout.zone(SceneKind.ALERT, cfg, ZoneKind.TITLE)!!
        val dur = OverlayAnim.LOOP_SEC
        val tt = t % dur
        val inP = (tt / 0.45).coerceIn(0.0, 1.0).toFloat(); val outP = ((dur - tt) / 0.45).coerceIn(0.0, 1.0).toFloat()
        val s = backOut(min(inP, 1f)) * min(1f, outP * 1.2f)
        if (s <= 0.001f) return
        c.save(); c.translate(z.x + z.w / 2, z.y + z.h / 2); c.scale(s, s); c.translate(-(z.x + z.w / 2), -(z.y + z.h / 2))
        // burst of particles
        for (i in 0 until 18) {
            val a = i / 18f * 2 * PI.toFloat(); val r = z.h * (0.6f + inP * 0.9f)
            c.path(Shapes.sparkle(z.x + z.w / 2 + cos(a) * r * 2.2f, z.y + z.h / 2 + sin(a) * r, 14f * k), fill(col(listOf(p.primary, p.accent, p.glow)[i % 3], (1f - inP * 0.6f) * outP)))
        }
        plate(c, theme, p, z, k, strong = true)
        val tf = FontId.of(theme.font)
        val title = cfg.titleOf(SceneKind.ALERT)
        c.txt.draw(title, z.x + z.w / 2, z.y + z.h * 0.42f, tf, c.txt.fit(title, tf, 56f * k, z.w - 60f * k), fill(col(p.accent)), spacing = titleSpacing(theme, k))
        c.txt.draw(user, z.x + z.w / 2, z.y + z.h * 0.8f, FontId.BOLD, 60f * k, fill(col(p.text)))
        c.restore()
    }

    private fun backOut(x: Float): Float { val c1 = 1.70158f; val c3 = c1 + 1; return 1 + c3 * (x - 1) * (x - 1) * (x - 1) + c1 * (x - 1) * (x - 1) }

    // ================================================================== stingers

    /**
     * Full-screen transition. At [OverlayAnim.STINGER_COVER_SEC] the screen is entirely covered
     * (that is where OBS switches the scenes), before and after it is partly transparent.
     */
    fun drawStinger(c: Gfx, theme: OverlayTheme, cfg: OverlayConfig, style: StingerStyle, t: Double) {
        val p = pal(theme, cfg)
        val w = cfg.width.toFloat(); val h = cfg.height.toFloat(); val k = w / OverlayLayout.REF_W
        val cover = OverlayAnim.stingerCover(t)
        if (cover <= 0.0001f) return
        val revealing = t > OverlayAnim.STINGER_COVER_SEC
        val diag = hypot(w, h)
        val bg = shaded(linear(0f, 0f, w, h, intArrayOf(col(p.background), col(p.backgroundAlt))))
        fun logo(alpha: Float) {
            if (alpha <= 0f) return
            val tf = FontId.of(theme.font); val s = c.txt.fit(cfg.channel, tf, 130f * k, w * 0.7f)
            c.txt.draw(cfg.channel, w / 2, c.txt.baselineFor(h / 2, tf, s), tf, s, fill(col(p.text, alpha)), spacing = titleSpacing(theme, k))
        }
        val logoA = ((cover - 0.75f) / 0.25f).coerceIn(0f, 1f)
        when (style) {
            StingerStyle.SPIDER_WEB -> {
                val r = cover * diag * 0.62f
                c.save(); c.clipPath(Path().apply { addCircle(w / 2, h / 2, r) })
                c.rect(0f, 0f, w, h, bg)
                c.restore()
                // the web itself, spun from the centre
                val spokes = 16
                val webP = stroke(col(0xFFFFFFFF, 0.85f), 2.5f * k)
                for (i in 0 until spokes) {
                    val a = i * 2 * PI / spokes + 0.13 * sin(i * 1.7)
                    c.line(w / 2, h / 2, w / 2 + (cos(a) * r * 1.1f).toFloat(), h / 2 + (sin(a) * r * 1.1f).toFloat(), webP)
                }
                var rr = 40f * k; var ring = 0
                while (rr < r) {
                    val path = Path()
                    for (i in 0..spokes) {
                        val a = i * 2 * PI / spokes + 0.13 * sin((i % spokes) * 1.7)
                        val x = w / 2 + (cos(a) * rr).toFloat(); val y = h / 2 + (sin(a) * rr).toFloat()
                        if (i == 0) path.moveTo(x, y) else {
                            val am = (i - 0.5) * 2 * PI / spokes
                            path.quadTo(w / 2 + (cos(am) * rr * 0.88f).toFloat(), h / 2 + (sin(am) * rr * 0.88f).toFloat(), x, y)
                        }
                    }
                    c.path(path, webP.copy().apply { strokeWidth = (2.5f - ring * 0.08f).coerceAtLeast(1f) * k })
                    rr += 46f * k + ring * 6f * k; ring++
                }
                // a little spider dropping on its thread
                val sy = h * 0.5f * (1 - cover) + 60f * k
                c.line(w * 0.72f, 0f, w * 0.72f, sy, stroke(col(0xFFFFFFFF, 0.7f), 1.5f * k))
                c.circle(w * 0.72f, sy + 16f * k, 18f * k, fill(col(0xFF0A0A0A)))
                for (i in 0 until 4) for (sd in listOf(-1f, 1f)) c.line(w * 0.72f, sy + 16f * k, w * 0.72f + sd * (30f + i * 4f) * k, sy + (4f + i * 9f) * k, stroke(col(0xFF0A0A0A), 3f * k))
                logo(logoA)
            }
            StingerStyle.SHUTTER -> {
                val n = 8; val sw = w / n
                for (i in 0 until n) {
                    val d = OverlayAnim.easeInOut(((cover * 1.3f) - i * 0.04f).coerceIn(0f, 1f))
                    val fromTop = (i % 2 == 0) != revealing
                    val hh = h * d
                    val y = if (fromTop) 0f else h - hh
                    c.rect(i * sw - 1, y, sw + 2, hh, fill(col(if (i % 2 == 0) p.primary else p.backgroundAlt)))
                    c.rect(i * sw - 1, if (fromTop) y + hh - 8f * k else y, sw + 2, 8f * k, fill(col(p.accent)))
                }
                logo(logoA)
            }
            StingerStyle.PIXEL_DISSOLVE -> {
                val cols = 48; val rows = 27; val cw = w / cols; val ch = h / rows
                for (y in 0 until rows) for (x in 0 until cols) {
                    val r = ((sin((x * 12.9898 + y * 78.233)) * 43758.5453).let { it - floor(it) }).toFloat()
                    if (r < cover * 1.05f) c.rect(x * cw - 0.5f, y * ch - 0.5f, cw + 1, ch + 1, fill(col(listOf(p.background, p.backgroundAlt, p.primary, p.secondary)[((r * 97).toInt()) % 4].let { if (cover > 0.95f) p.background else it })))
                }
                logo(logoA)
            }
            StingerStyle.SAKURA_STORM, StingerStyle.HEARTS_RAIN, StingerStyle.SNOW_SWIRL -> {
                // growing blooms that merge into a full fill, with a swirl of particles on top
                val n = 14
                c.save()
                val clip = Path()
                for (i in 0 until n) {
                    val cx = w * (((sin(i * 4.1) * 0.5 + 0.5)).toFloat()); val cy = h * ((cos(i * 2.7) * 0.5 + 0.5).toFloat())
                    clip.addCircle(cx, cy, cover * diag * 0.42f)
                }
                c.clipPath(clip)
                c.rect(0f, 0f, w, h, bg)
                c.restore()
                val motif = when (style) { StingerStyle.SAKURA_STORM -> Motif.SAKURA; StingerStyle.HEARTS_RAIN -> Motif.HEARTS; else -> Motif.SNOW }
                c.save(); c.translate(w / 2, h / 2); c.rotate((t * 120).toFloat() * (if (revealing) 1 else -1)); c.scale(1.4f, 1.4f); c.translate(-w / 2, -h / 2)
                Motifs.particles(c, motif, p, w, h, t * 3, min(1f, cover * 2f), 150)
                c.restore()
                logo(logoA)
            }
            StingerStyle.COMIC_BURST -> {
                val r = cover * diag * 0.75f
                c.path(Shapes.star(w / 2, h / 2, r, 18, 0.72f), fill(col(p.primary)))
                c.path(Shapes.star(w / 2, h / 2, r, 18, 0.72f), stroke(col(0xFF000000), 10f * k))
                c.path(Shapes.star(w / 2, h / 2, r * 0.7f, 14, 0.7f), fill(col(p.secondary)))
                c.path(Shapes.star(w / 2, h / 2, r * 0.7f, 14, 0.7f), stroke(col(0xFF000000), 8f * k))
                if (cover > 0.5f) {
                    c.save(); c.translate(w / 2, h / 2); c.rotate(-8f); val s = 0.6f + cover * 0.5f; c.scale(s, s)
                    c.txt.draw(theme.badge, 0f, 60f * k, FontId.of(theme.font), 220f * k, fill(col(0xFFFFFFFF)))
                    c.txt.draw(theme.badge, 0f, 60f * k, FontId.of(theme.font), 220f * k, stroke(col(0xFF000000), 8f * k))
                    c.restore()
                }
            }
            StingerStyle.HEX_WIPE -> {
                val r = w / 22f
                var row = 0; var y = 0f
                while (y < h + r) {
                    var x = if (row % 2 == 0) 0f else r * 1.5f
                    while (x < w + r * 2) {
                        val order = (if (revealing) 1 - x / w else x / w) * 0.6f + ((sin(x * 0.05 + y * 0.03) * 0.5 + 0.5) * 0.25).toFloat()
                        val s = ((cover * 1.25f - order) / 0.3f).coerceIn(0f, 1f)
                        if (s > 0f) {
                            c.path(Shapes.hexagon(x, y, r * 1.02f * s), fill(col(if ((row + (x / r).toInt()) % 5 == 0) p.backgroundAlt else p.background)))
                            c.path(Shapes.hexagon(x, y, r * 1.02f * s), stroke(col(p.glow, 0.7f), 2f * k))
                        }
                        x += r * 3f
                    }
                    y += r * 0.866f; row++
                }
                logo(logoA)
            }
            StingerStyle.GLITCH -> {
                val bands = 30
                for (i in 0 until bands) {
                    val r = ((sin(i * 91.7 + floor(t * 30) * 13.1) * 43758.5453).let { it - floor(it) }).toFloat()
                    val bh = h / bands
                    val len = (w * (cover * 1.6f - r * 0.6f)).coerceIn(0f, w)
                    val fromLeft = i % 2 == 0
                    c.rect(if (fromLeft) 0f else w - len, i * bh, len, bh + 1, fill(col(if (r > 0.8f) p.secondary else if (r > 0.6f) p.primary else p.background)))
                }
                // chromatic split of the channel name
                if (logoA > 0f) {
                    val tf = FontId.of(theme.font); val s = c.txt.fit(cfg.channel, tf, 130f * k, w * 0.7f)
                    for (dx in listOf(-8f, 8f)) c.txt.draw(cfg.channel, w / 2 + dx * k, c.txt.baselineFor(h / 2, tf, s), tf, s, fill(col(if (dx < 0) p.primary else p.secondary, logoA * 0.8f)))
                }
                logo(logoA)
            }
            StingerStyle.PORTAL -> {
                val r = cover * diag * 0.6f
                c.save(); c.clipPath(Path().apply { addCircle(w / 2, h / 2, r) })
                c.rect(0f, 0f, w, h, bg)
                for (i in 0 until 8) {
                    c.save(); c.translate(w / 2, h / 2); c.rotate((t * 200 + i * 45).toFloat())
                    c.arc(-r * (0.2f + i * 0.1f), -r * (0.2f + i * 0.1f), r * (0.2f + i * 0.1f), r * (0.2f + i * 0.1f), 0f, 200f, stroke(col(if (i % 2 == 0) p.glow else p.secondary, 0.5f), 6f * k).glow(6f * k))
                    c.restore()
                }
                c.restore()
                c.circle(w / 2, h / 2, r, stroke(col(p.glow), 14f * k).glow(20f * k))
                c.circle(w / 2, h / 2, r, stroke(col(0xFFFFFFFF), 4f * k))
                logo(logoA)
            }
            StingerStyle.LIGHTNING_STRIKE -> {
                val flash = if (cover in 0.3f..0.9f) 1f else 0f
                c.rect(0f, 0f, w, h, bg.copy().apply { alpha = min(1f, cover * 1.4f) })
                for (i in 0 until 4) {
                    val x0 = w * (0.15f + i * 0.23f)
                    val bolt = Shapes.bolt(x0, 0f, x0 + (i - 1.5f) * 80f, h, i + floor(t * 10).toInt())
                    c.path(bolt, stroke(col(p.glow, cover), 18f * k).glow(20f * k))
                    c.path(bolt, stroke(col(0xFFFFFFFF, cover), 5f * k))
                }
                if (flash > 0f && cover < 0.95f) c.rect(0f, 0f, w, h, fill(col(0xFFFFFFFF, 0.25f)))
                logo(logoA)
            }
            StingerStyle.INK_SPLASH -> {
                c.save()
                val clip = Path()
                for (i in 0 until 9) {
                    val cx = w * (0.5f + 0.45f * sin(i * 2.3).toFloat()); val cy = h * (0.5f + 0.42f * cos(i * 1.9).toFloat())
                    val rr = cover * diag * (0.33f + 0.12f * abs(sin(i * 3.1).toFloat()))
                    // blobby splash: circle + drops
                    clip.addCircle(cx, cy, rr)
                    for (d in 0 until 6) { val a = d * 1.05 + i; clip.addCircle(cx + (cos(a) * rr * 1.25f).toFloat(), cy + (sin(a) * rr * 1.25f).toFloat(), rr * 0.18f) }
                }
                // fully covered at the transition point, whatever the blob layout
                if (cover > 0.98f) clip.addRect(0f, 0f, w, h)
                c.clipPath(clip)
                c.rect(0f, 0f, w, h, fill(col(if (theme.palette.background > 0xFFC0C0C0) 0xFF111111 else p.primary)))
                c.rect(0f, 0f, w, h, bg.copy().apply { alpha = ((cover - 0.6f) / 0.4f).coerceIn(0f, 1f) })
                c.restore()
                logo(logoA)
            }
        }
    }
}
