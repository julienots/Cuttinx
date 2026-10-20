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
    fun drawScene(c: Gfx, theme0: OverlayTheme, cfg: OverlayConfig, scene: SceneKind, t: Double, layer: Layer = Layer.ALL, countdown: String? = "BIENTÔT") {
        val theme = cfg.styled(theme0)
        val w = cfg.width.toFloat(); val h = cfg.height.toFloat()
        if (scene == SceneKind.VERTICAL && w > h) {
            // landscape preview of the vertical layout: centred 9:16 frame
            val pw = (h * 9f / 16f).toInt().coerceAtLeast(2)
            c.save(); c.translate((w - pw) / 2f, 0f)
            drawScene(c, theme, cfg.copy(width = pw, height = cfg.height), scene, t, layer, countdown)
            c.restore(); return
        }
        val p = pal(theme, cfg)
        val k = if (scene == SceneKind.VERTICAL) w / 1080f else w / OverlayLayout.REF_W
        when {
            scene == SceneKind.TICKER -> drawTicker(c, theme, cfg, t, layer)
            scene == SceneKind.SOCIALS -> if (layer != Layer.STATIC) drawSocialsPop(c, theme, cfg, t)
            scene.isAlert -> if (layer != Layer.STATIC) drawAlert(c, theme, cfg, t, scene)
            scene == SceneKind.GOAL -> if (layer != Layer.MOVING) drawGoal(c, theme, cfg)
            scene.fullBackground -> {
                val static = layer != Layer.MOVING; val moving = layer != Layer.STATIC
                val bg = cfg.backgroundOf(scene)
                if (static) sceneBackground(c, p, bg, w, h)
                for (m in theme.motifs) {
                    val mv = Motifs.isMoving(m)
                    if ((mv && moving && cfg.particles && bg.particles) || (!mv && static && bg.motifs)) Motifs.draw(c, m, p, w, h, if (layer == Layer.STATIC) 0.0 else t)
                }
                drawItems(c, theme, cfg, scene, p, t, layer, behind = true)
                if (static) fullSceneTexts(c, theme, cfg, scene, p, w, h, k, countdown)
                if (moving) fullSceneAnimation(c, theme, cfg, scene, p, w, h, k, t)
            }
            else -> {
                drawItems(c, theme, cfg, scene, p, t, layer, behind = true)
                // every built-in element is drawn at its own size: its texts follow when it is resized
                if (layer != Layer.MOVING) for (z in OverlayLayout.zones(scene, cfg)) if (!cfg.isHidden(scene, z.kind)) zone(c, theme, cfg, p, z, k * cfg.zoneScale(scene, z.kind))
            }
        }
        drawItems(c, theme, cfg, scene, p, t, layer, behind = false)
    }

    // ================================================================== free elements (pictures, texts, stickers)

    /** Draws the free elements of [scene]: still ones on the static layer, animated ones on the moving layer. */
    private fun drawItems(c: Gfx, theme: OverlayTheme, cfg: OverlayConfig, scene: SceneKind, p: OverlayPalette, t: Double, layer: Layer, behind: Boolean) {
        val w = cfg.width.toFloat(); val h = cfg.height.toFloat()
        for (it in cfg.itemsOf(scene)) {
            if (it.hidden || it.behind != behind) continue
            val animated = it.anim != ItemAnim.NONE
            if ((layer == Layer.STATIC && animated) || (layer == Layer.MOVING && !animated)) continue
            drawItem(c, theme, p, it, w, h, t)
        }
    }

    /** One free element on a [w]×[h] canvas at time [t]. */
    fun drawItem(c: Gfx, theme: OverlayTheme, p: OverlayPalette, it: OverlayItem, w: Float, h: Float, t: Double) {
        val b = OverlayItems.box(it, w, h)
        val pose = OverlayItems.pose(it, t, b.h)
        val alpha = (it.opacity * pose.alpha).coerceIn(0f, 1f)
        if (alpha <= 0.002f || b.w < 0.5f || b.h < 0.5f) return
        c.save()
        c.translate(b.cx + pose.dx, b.cy + pose.dy)
        c.rotate(b.rotation + pose.rotation)
        c.scale(pose.scaleX * (if (it.flipX) -1f else 1f), pose.scaleY * (if (it.flipY) -1f else 1f))
        val x0 = -b.w / 2; val y0 = -b.h / 2
        val m = max(b.w, b.h)
        val tint = it.color ?: p.primary
        if (it.glow) c.circle(0f, 0f, m * 0.7f, shaded(radial(0f, 0f, m * 0.7f, intArrayOf(col(p.glow, 0.6f * alpha), col(p.glow, 0.18f * alpha), 0), floatArrayOf(0f, 0.55f, 1f))))
        val bw = max(2f, min(b.w, b.h) * 0.035f)
        when (it.type) {
            ItemType.IMAGE -> {
                val r = when (it.shape) { ItemShape.FREE -> 0f; ItemShape.ROUNDED -> min(b.w, b.h) * 0.14f; ItemShape.CIRCLE -> min(b.w, b.h) / 2 }
                val cw = if (it.shape == ItemShape.CIRCLE) min(b.w, b.h) else b.w
                val ch = if (it.shape == ItemShape.CIRCLE) min(b.w, b.h) else b.h
                if (it.shadow && it.shape != ItemShape.FREE) c.rrect(-cw / 2 + bw, -ch / 2 + bw * 1.6f, cw, ch, r, fill(col(0xFF000000, 0.45f * alpha)).glow(bw * 2f))
                val bytes = it.bytes
                c.save()
                if (it.shape != ItemShape.FREE) c.clipRRect(-cw / 2, -ch / 2, cw, ch, r)
                if (bytes != null) c.image(bytes, x0, y0, b.w, b.h, alpha)
                else c.rect(x0, y0, b.w, b.h, fill(col(p.backgroundAlt, 0.6f * alpha)))
                c.restore()
                if (it.border) c.rrect(-cw / 2, -ch / 2, cw, ch, r, stroke(col(tint, alpha), bw))
            }
            ItemType.TEXT -> {
                val text = it.text.ifBlank { " " }
                val f = FontId.of(it.font ?: theme.font)
                val size = c.txt.fit(text, f, b.h * 0.8f, b.w * 0.96f)
                val base = c.txt.baselineFor(0f, f, size)
                if (it.shadow) c.txt.draw(text, size * 0.05f, base + size * 0.06f, f, size, fill(col(0xFF000000, 0.55f * alpha)).glow(size * 0.06f))
                if (it.border) c.txt.draw(text, 0f, base, f, size, stroke(col(p.background, alpha), size * 0.12f))
                c.txt.draw(text, 0f, base, f, size, fill(col(it.color ?: p.text, alpha)))
            }
            ItemType.STICKER -> {
                val s = min(b.w, b.h)
                val path = stickerPath(it.sticker, b.w, b.h, s)
                if (it.shadow) { c.save(); c.translate(s * 0.04f, s * 0.06f); c.path(path, fill(col(0xFF000000, 0.45f * alpha)).glow(s * 0.05f)); c.restore() }
                if (it.sticker == Sticker.CIRCLE) c.circle(0f, 0f, s * 0.48f, shaded(radial(-s * 0.15f, -s * 0.15f, s * 0.7f, intArrayOf(col(lighten(tint, 0.45f), alpha), col(tint, alpha)))))
                else c.path(path, Paint().apply { shader = linear(0f, -b.h / 2, 0f, b.h / 2, intArrayOf(col(lighten(tint, 0.35f), alpha), col(tint, alpha))) })
                if (it.border) c.path(path, stroke(col(0xFFFFFFFF, alpha), max(2f, s * 0.04f)))
            }
        }
        c.restore()
    }

    private fun stickerPath(st: Sticker, w: Float, h: Float, s: Float): Path = when (st) {
        Sticker.HEART -> Shapes.heart(0f, s * 0.06f, s * 1.05f)
        Sticker.STAR -> Shapes.star(0f, s * 0.04f, s * 0.5f)
        Sticker.SPARKLE -> Shapes.sparkle(0f, 0f, s * 0.5f)
        Sticker.HEXAGON -> Shapes.hexagon(0f, 0f, s * 0.48f)
        Sticker.SAKURA -> Shapes.sakura(s * 0.95f)
        Sticker.LEAF -> Shapes.leaf(s * 0.95f)
        Sticker.BAT -> Shapes.bat(s * 0.9f)
        Sticker.CIRCLE -> Path().addCircle(0f, 0f, s * 0.48f)
        Sticker.BURST -> Shapes.star(0f, 0f, s * 0.5f, 12, 0.62f)
        Sticker.BOLT -> Path().moveTo(s * 0.12f, -s * 0.5f).lineTo(-s * 0.3f, s * 0.06f).lineTo(-s * 0.02f, s * 0.06f).lineTo(-s * 0.14f, s * 0.5f)
            .lineTo(s * 0.32f, -s * 0.1f).lineTo(s * 0.04f, -s * 0.1f).closePath()
        Sticker.ARROW -> Path().moveTo(-w / 2, -h * 0.18f).lineTo(w * 0.12f, -h * 0.18f).lineTo(w * 0.12f, -h / 2).lineTo(w / 2, 0f)
            .lineTo(w * 0.12f, h / 2).lineTo(w * 0.12f, h * 0.18f).lineTo(-w / 2, h * 0.18f).closePath()
        Sticker.CROWN -> Path().moveTo(-s * 0.48f, s * 0.36f).lineTo(-s * 0.48f, -s * 0.3f).lineTo(-s * 0.22f, -s * 0.02f).lineTo(0f, -s * 0.42f)
            .lineTo(s * 0.22f, -s * 0.02f).lineTo(s * 0.48f, -s * 0.3f).lineTo(s * 0.48f, s * 0.36f).closePath()
    }

    /** Background of an animated scene: the theme's, or the colour / gradient / photo chosen for this scene. */
    private fun sceneBackground(c: Gfx, p: OverlayPalette, bg: SceneBackground, w: Float, h: Float) {
        when (bg.kind) {
            SceneBgKind.THEME -> { background(c, p, w, h, bg.vignette); return }
            SceneBgKind.COLOR -> c.rect(0f, 0f, w, h, fill(col(bg.color ?: p.background)))
            SceneBgKind.GRADIENT -> c.rect(0f, 0f, w, h, shaded(linear(0f, 0f, w * 0.4f, h, intArrayOf(col(bg.color ?: p.background), col(bg.color2 ?: p.primary)))))
            SceneBgKind.IMAGE -> {
                c.rect(0f, 0f, w, h, fill(col(bg.color ?: p.background)))
                val bytes = if (bg.image.isNotEmpty()) LogoMemo.decode(bg.image) else null
                if (bytes != null) {
                    // cover: the photo fills the whole scene, centred
                    val ih = w * bg.imageAspect
                    val dw = if (ih >= h) w else h / bg.imageAspect
                    val dh = if (ih >= h) ih else h
                    c.save(); c.clipRect(0f, 0f, w, h); c.image(bytes, (w - dw) / 2, (h - dh) / 2, dw, dh); c.restore()
                }
                if (bg.dim > 0f) c.rect(0f, 0f, w, h, fill(col(0xFF000000, bg.dim.coerceIn(0f, 0.9f))))
            }
        }
        if (bg.vignette) c.rect(0f, 0f, w, h, shaded(radial(w / 2, h / 2, hypot(w, h) / 2, intArrayOf(0, 0, col(0xFF000000, 0.45f)), floatArrayOf(0f, 0.65f, 1f))))
    }

    private fun background(c: Gfx, p: OverlayPalette, w: Float, h: Float, vignette: Boolean = true) {
        c.rect(0f, 0f, w, h, shaded(linear(0f, 0f, w * 0.3f, h, intArrayOf(col(p.background), col(p.backgroundAlt)))))
        c.circle(w / 2, h * 0.45f, h * 0.75f, shaded(radial(w / 2, h * 0.45f, h * 0.75f, intArrayOf(col(p.primary, 0.22f), 0))))
        // vignette
        if (vignette) c.rect(0f, 0f, w, h, shaded(radial(w / 2, h / 2, hypot(w, h) / 2, intArrayOf(0, 0, col(0xFF000000, 0.45f)), floatArrayOf(0f, 0.65f, 1f))))
    }

    private fun fullSceneTexts(c: Gfx, theme: OverlayTheme, cfg: OverlayConfig, scene: SceneKind, p: OverlayPalette, w: Float, h: Float, k: Float, countdown: String?) {
        val title = OverlayLayout.zone(scene, cfg, ZoneKind.TITLE)!!
        val info = OverlayLayout.zone(scene, cfg, ZoneKind.INFO)!!
        val socials = OverlayLayout.zone(scene, cfg, ZoneKind.SOCIALS)!!
        val tf = FontId.of(theme.font)
        val showTitle = !cfg.isHidden(scene, ZoneKind.TITLE); val showInfo = !cfg.isHidden(scene, ZoneKind.INFO)
        // texts follow their element: moved with it, and sized with it (zone scale)
        val sT = k * cfg.zoneScale(scene, ZoneKind.TITLE); val sI = k * cfg.zoneScale(scene, ZoneKind.INFO); val sS = k * cfg.zoneScale(scene, ZoneKind.SOCIALS)
        // badge + channel
        if (!cfg.isHidden(scene, ZoneKind.BADGE)) OverlayLayout.zone(scene, cfg, ZoneKind.BADGE)?.let { z -> badge(c, theme, p, z.x, z.y, theme.badge, k * cfg.zoneScale(scene, ZoneKind.BADGE)) }
        if (!cfg.isHidden(scene, ZoneKind.CHANNEL)) OverlayLayout.zone(scene, cfg, ZoneKind.CHANNEL)?.let { z ->
            val kc = k * cfg.zoneScale(scene, ZoneKind.CHANNEL)
            c.txt.draw(cfg.channel.uppercase(), z.right, c.txt.baselineFor(z.y + z.h / 2, FontId.BOLD, 34f * kc), FontId.BOLD, c.txt.fit(cfg.channel.uppercase(), FontId.BOLD, 34f * kc, z.w - 4f * kc * cfg.channel.length), fill(col(p.text, 0.9f)), Align.RIGHT, spacing = 4f * kc)
        }
        // title plate + title
        if (showTitle) {
        plate(c, theme, p, title, sT, strong = true)
        val titleText = cfg.titleOf(scene)
        val tx = title.x + title.w / 2
        // letter spacing is added on top of the measured width: keep room for it
        val size = c.txt.fit(titleText, tf, (if (theme.font == TitleFont.PIXEL) 74f else 118f) * sT * cfg.textScale.coerceIn(0.5f, 1.6f), title.w - 140f * sT - titleSpacing(theme, sT) * titleText.length)
        val base = c.txt.baselineFor(title.y + title.h / 2, tf, size)
        c.txt.draw(titleText, tx + 6f * sT, base + 6f * sT, tf, size, fill(col(0xFF000000, 0.35f)).glow(6f * sT), spacing = titleSpacing(theme, sT))
        c.txt.draw(titleText, tx, base, tf, size, titlePaint(theme, p, title), spacing = titleSpacing(theme, sT))
        }
        // info line
        val ix = info.x + info.w / 2
        val line = if (scene == SceneKind.STARTING || !showInfo) null else cfg.subtitleOf(scene)
        if (scene == SceneKind.STARTING && showInfo) {
            plate(c, theme, p, info, sI, strong = false)
            c.txt.draw("DÉBUT DANS", ix, info.y + 30f * sI, FontId.BOLD, 20f * sI, fill(col(p.text, 0.7f)), spacing = 6f * sI)
            if (countdown != null) c.txt.draw(countdown, ix, info.y + info.h - 26f * sI, tf, c.txt.fit(countdown, tf, 58f * sI, info.w), fill(col(p.text)))
        } else if (line != null) {
            val ls = c.txt.fit(line, FontId.REGULAR, 40f * sI, info.w)
            c.txt.draw(line, ix, c.txt.baselineFor(info.y + info.h / 2, FontId.REGULAR, ls), FontId.REGULAR, ls, fill(col(p.text, 0.9f)))
        }
        // socials + schedule
        val soc = (cfg.socials.filter { it.isNotBlank() } + if (scene == SceneKind.STARTING) listOf(cfg.schedule) else emptyList()).joinToString("   •   ")
        if (soc.isNotBlank() && !cfg.isHidden(scene, ZoneKind.SOCIALS)) c.txt.draw(soc, socials.x + socials.w / 2, c.txt.baselineFor(socials.y + socials.h / 2, FontId.BOLD, 30f * sS), FontId.BOLD, c.txt.fit(soc, FontId.BOLD, 30f * sS, socials.w), fill(col(p.text, 0.85f)))
        if (cfg.backgroundOf(scene).decor) cornerDecorations(c, theme, p, w, h, k)
        if (!cfg.isHidden(scene, ZoneKind.LOGO)) OverlayLayout.zone(scene, cfg, ZoneKind.LOGO)?.let { logo(c, cfg, it) }
    }

    /** The streamer's own logo, fitted in the zone (nothing when no logo was imported). */
    fun logo(c: Gfx, cfg: OverlayConfig, z: Zone, alpha: Float = 1f) {
        val b = cfg.logoBytes ?: return
        c.image(b, z.x, z.y, z.w, z.h, alpha)
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
        if (cfg.isHidden(scene, ZoneKind.TITLE)) return
        val title = OverlayLayout.zone(scene, cfg, ZoneKind.TITLE)!!
        val pulse = OverlayAnim.pulse(t)
        // breathing glow line under the title plate
        val y = title.bottom + 8f * k
        val len = title.w * (0.35f + 0.25f * pulse)
        val tx = title.x + title.w / 2
        c.line(tx - len / 2, y, tx + len / 2, y, stroke(col(p.glow, 0.85f), 6f * k).glow(10f * k))
        c.line(tx - len / 2, y, tx + len / 2, y, stroke(col(0xFFFFFFFF, 0.9f), 2f * k))
        // loading dots
        if (scene == SceneKind.STARTING || scene == SceneKind.BRB || scene == SceneKind.PAUSE || scene == SceneKind.TECH || scene == SceneKind.INTERMISSION) {
            val phase = (t / OverlayAnim.LOOP_SEC * 4) % 1.0
            for (i in 0 until 3) {
                val a = (sin((phase - i * 0.15) * 2 * PI) * 0.5 + 0.5).toFloat()
                c.circle(tx + (i - 1) * 34f * k, title.y - 34f * k, (7f + 5f * a) * k, fill(col(p.accent, 0.4f + 0.6f * a)))
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
            ZoneKind.LOGO -> logo(c, cfg, z)
            ZoneKind.CAMERA, ZoneKind.CAMERA2 -> {
                val name = if (z.kind == ZoneKind.CAMERA2) cfg.guest else cfg.channel
                when (cfg.cameraShape) {
                    CameraShape.THEME -> frame(c, theme, p, z, k)
                    CameraShape.ROUNDED -> roundFrame(c, p, RR(z.x, z.y, z.w, z.h, min(z.w, z.h) * 0.12f), k)
                    CameraShape.CIRCLE -> {
                        val r = min(z.w, z.h) / 2
                        roundFrame(c, p, RR(z.x + z.w / 2 - r, z.y + z.h / 2 - r, 2 * r, 2 * r, r), k)
                    }
                }
                if (!cfg.nameOnCamera) return
                // name plate overlapping the bottom edge of the camera
                val pw = min(z.w * 0.8f, max(260f * k, c.txt.width(name, FontId.of(theme.font), 36f * k) + 80f * k)); val ph = 54f * k
                val plateZone = Zone(ZoneKind.TITLE, z.x + (z.w - pw) / 2, z.bottom - ph / 2, pw, ph)
                plate(c, theme, p, plateZone, k, strong = true)
                val tf = FontId.of(theme.font); val s = c.txt.fit(name, tf, 34f * k, pw - 40f * k)
                c.txt.draw(name, plateZone.x + pw / 2, c.txt.baselineFor(plateZone.y + ph / 2, tf, s), tf, s, fill(col(p.text)), spacing = titleSpacing(theme, k) / 2)
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
            // only on the animated scenes (drawn with their texts)
            ZoneKind.BADGE, ZoneKind.CHANNEL -> {}
        }
    }

    /** Rounded / round camera window in the palette colours (glow + double ring). */
    private fun roundFrame(c: Gfx, p: OverlayPalette, rr: RR, k: Float) {
        c.rrect(rr.inflate(6f * k), stroke(col(p.glow, 0.8f), 10f * k).glow(14f * k))
        c.rrect(rr, stroke(col(p.primary), 7f * k))
        c.rrect(rr.inflate(9f * k), stroke(col(p.accent, 0.8f), 2.5f * k))
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

    fun drawAlert(c: Gfx, theme0: OverlayTheme, cfg: OverlayConfig, t: Double, scene: SceneKind = SceneKind.ALERT, user: String = cfg.alertUser) {
        val theme = cfg.styled(theme0)
        val p = pal(theme, cfg); val k = cfg.width / OverlayLayout.REF_W
        val z = OverlayLayout.zone(scene, cfg, ZoneKind.TITLE)!!
        val dur = OverlayAnim.LOOP_SEC
        val tt = t % dur
        val inP = (tt / 0.45).coerceIn(0.0, 1.0).toFloat(); val outP = ((dur - tt) / 0.45).coerceIn(0.0, 1.0).toFloat()
        val s = backOut(min(inP, 1f)) * min(1f, outP * 1.2f)
        if (s <= 0.001f) return
        c.save(); c.translate(z.x + z.w / 2, z.y + z.h / 2); c.scale(s, s); c.translate(-(z.x + z.w / 2), -(z.y + z.h / 2))
        // burst of particles
        for (i in 0 until 18) {
            val a = i / 18f * 2 * PI.toFloat(); val r = z.h * (0.6f + inP * 0.9f)
            val px = z.x + z.w / 2 + cos(a) * r * 2.2f; val py = z.y + z.h / 2 + sin(a) * r
            val pc = fill(col(listOf(p.primary, p.accent, p.glow)[i % 3], (1f - inP * 0.6f) * outP))
            when (scene) {
                SceneKind.ALERT_SUB -> c.path(Shapes.star(px, py, 16f * k, 5, 0.45f), pc)
                SceneKind.ALERT_DONATION -> c.path(Shapes.heart(px, py, 26f * k), pc)
                SceneKind.ALERT_RAID -> c.path(Shapes.star(px, py, 18f * k, 4, 0.25f), pc)
                else -> c.path(Shapes.sparkle(px, py, 14f * k), pc)
            }
        }
        // raid: speed lines rushing in
        if (scene == SceneKind.ALERT_RAID) for (i in 0 until 10) {
            val yy = z.y + z.h * (i + 0.5f) / 10f; val len = z.w * (0.25f + 0.2f * ((i * 37) % 10) / 10f)
            val x0 = z.x - 40f * k - len * (1f - inP)
            c.line(x0 - len, yy, x0, yy, stroke(col(p.glow, 0.6f * outP), 4f * k))
        }
        plate(c, theme, p, z, k, strong = true)
        val tf = FontId.of(theme.font)
        val title = cfg.titleOf(scene)
        val sub = cfg.subtitleOf(scene)
        c.txt.draw(title, z.x + z.w / 2, z.y + z.h * 0.36f, tf, c.txt.fit(title, tf, 52f * k, z.w - 60f * k), fill(col(p.accent)), spacing = titleSpacing(theme, k))
        val us = c.txt.fit(user, FontId.BOLD, 58f * k, z.w - 60f * k)
        c.txt.draw(user, z.x + z.w / 2, z.y + z.h * (if (sub.isBlank()) 0.8f else 0.68f), FontId.BOLD, us, fill(col(p.text)))
        if (sub.isNotBlank()) c.txt.draw(sub, z.x + z.w / 2, z.y + z.h * 0.9f, FontId.REGULAR, c.txt.fit(sub, FontId.REGULAR, 28f * k, z.w - 60f * k), fill(col(p.text, 0.85f)))
        c.restore()
    }

    // ================================================================== widgets & Twitch profile

    /** Follower / sub goal bar (transparent overlay). */
    fun drawGoal(c: Gfx, theme0: OverlayTheme, cfg: OverlayConfig) {
        val theme = cfg.styled(theme0)
        val p = pal(theme, cfg); val k = cfg.width / OverlayLayout.REF_W * cfg.zoneScale(SceneKind.GOAL, ZoneKind.INFO)
        val z = OverlayLayout.zone(SceneKind.GOAL, cfg, ZoneKind.INFO)!!
        plate(c, theme, p, z, k, strong = true)
        val tf = FontId.of(theme.font)
        val label = cfg.titleOf(SceneKind.GOAL)
        val count = "${cfg.goalCurrent} / ${cfg.goalTarget}"
        val pad = 28f * k
        c.txt.draw(label, z.x + pad, z.y + 44f * k, tf, c.txt.fit(label, tf, 32f * k, z.w * 0.6f), fill(col(p.text)), Align.LEFT, titleSpacing(theme, k) / 2)
        c.txt.draw(count, z.right - pad, z.y + 44f * k, FontId.BOLD, 30f * k, fill(col(p.accent)), Align.RIGHT)
        val bx = z.x + pad; val by = z.y + z.h - 44f * k; val bw = z.w - 2 * pad; val bh = 22f * k
        val frac = if (cfg.goalTarget <= 0) 0f else (cfg.goalCurrent.toFloat() / cfg.goalTarget).coerceIn(0f, 1f)
        c.rrect(RR(bx, by, bw, bh, bh / 2), fill(col(0xFF000000, 0.45f)))
        if (frac > 0f) {
            val fw = max(bh, bw * frac)
            c.rrect(RR(bx, by, fw, bh, bh / 2), Paint().apply { shader = linear(bx, 0f, bx + fw, 0f, intArrayOf(col(p.primary), col(p.accent))) }.glow(6f * k))
            c.rrect(RR(bx, by, fw, bh, bh / 2), Paint().apply { shader = linear(bx, 0f, bx + fw, 0f, intArrayOf(col(p.primary), col(p.accent))) })
        }
        c.rrect(RR(bx, by, bw, bh, bh / 2), stroke(col(p.text, 0.5f), 2f * k))
        c.txt.draw("${(frac * 100).toInt()} %", bx + bw / 2, by + bh - 4f * k, FontId.BOLD, 18f * k, fill(col(0xFFFFFFFF)))
    }

    /** Background shared by the panels and the banner: gradient, theme pattern, vignette. */
    private fun cardBackground(c: Gfx, theme: OverlayTheme, p: OverlayPalette, w: Float, h: Float, density: Float) {
        c.rect(0f, 0f, w, h, shaded(linear(0f, 0f, w, h, intArrayOf(col(p.background), col(p.backgroundAlt)))))
        c.circle(w * 0.5f, h * 0.5f, max(w, h) * 0.6f, shaded(radial(w * 0.5f, h * 0.5f, max(w, h) * 0.6f, intArrayOf(col(p.primary, 0.25f), 0))))
        for (m in theme.motifs) Motifs.draw(c, m, p, w, h, 0.9, density)
    }

    /** One Twitch profile panel (the boxes under the stream). Reference size 320×100. */
    fun drawPanel(c: Gfx, theme0: OverlayTheme, cfg: OverlayConfig, label: String, w: Float = 320f, h: Float = 100f) {
        val theme = cfg.styled(theme0)
        val p = pal(theme, cfg); val k = w / 320f
        c.save(); c.clipRRect(0f, 0f, w, h, radius(theme, k * 0.5f))
        cardBackground(c, theme, p, w, h, 0.45f)
        c.restore()
        val z = Zone(ZoneKind.TITLE, 10f * k, 14f * k, w - 20f * k, h - 28f * k)
        plate(c, theme, p, z, k * 0.45f, strong = true)
        val tf = FontId.of(theme.font)
        val lb = cfg.logoBytes
        val lw = if (lb != null) z.h - 8f * k else 0f
        if (lb != null) c.image(lb, z.x + 8f * k, z.y + 4f * k, lw, lw)
        val s = c.txt.fit(label, tf, 40f * k, z.w - 30f * k - lw)
        c.txt.draw(label, w / 2 + lw / 2, c.txt.baselineFor(z.y + z.h / 2, tf, s), tf, s, titlePaint(theme, p, z), spacing = titleSpacing(theme, k * 0.4f))
    }

    /** Twitch profile banner (1200×480 reference). */
    fun drawBanner(c: Gfx, theme0: OverlayTheme, cfg: OverlayConfig, w: Float = 1200f, h: Float = 480f) {
        val theme = cfg.styled(theme0)
        val p = pal(theme, cfg); val k = w / 1200f
        cardBackground(c, theme, p, w, h, 0.9f)
        c.rect(0f, 0f, w, h, shaded(radial(w / 2, h / 2, hypot(w, h) / 2, intArrayOf(0, 0, col(0xFF000000, 0.45f)), floatArrayOf(0f, 0.6f, 1f))))
        val tf = FontId.of(theme.font)
        val z = Zone(ZoneKind.TITLE, 200f * k, 150f * k, 800f * k, 150f * k)
        plate(c, theme, p, z, k * 0.8f, strong = true)
        val s = c.txt.fit(cfg.channel, tf, 96f * k * cfg.textScale.coerceIn(0.5f, 1.6f), z.w - 60f * k)
        c.txt.draw(cfg.channel, w / 2, c.txt.baselineFor(z.y + z.h / 2, tf, s), tf, s, titlePaint(theme, p, z), spacing = titleSpacing(theme, k))
        val ts = c.txt.fit(cfg.tagline, FontId.REGULAR, 34f * k, w - 200f * k)
        c.txt.draw(cfg.tagline, w / 2, z.bottom + 56f * k, FontId.REGULAR, ts, fill(col(p.text, 0.92f)).also { it.shadow = Shadow(0f, 2f * k, 4f * k, col(0xFF000000, 0.7f)) })
        val soc = (cfg.socials.filter { it.isNotBlank() } + cfg.schedule).joinToString("   •   ")
        c.txt.draw(soc, w / 2, h - 40f * k, FontId.BOLD, c.txt.fit(soc, FontId.BOLD, 26f * k, w - 160f * k), fill(col(p.text, 0.85f)))
        badge(c, theme, p, 40f * k, 40f * k, theme.badge, k * 0.9f)
        cfg.logoBytes?.let { b -> c.image(b, 1030f * k, 140f * k, 150f * k, 170f * k) }
    }

    /** Bottom ticker: channel tag on the left, socials / schedule / tagline scrolling in a seamless loop. */
    fun drawTicker(c: Gfx, theme0: OverlayTheme, cfg: OverlayConfig, t: Double, layer: Layer = Layer.ALL) {
        val theme = cfg.styled(theme0)
        val p = pal(theme, cfg); val k = cfg.width / OverlayLayout.REF_W
        val z = OverlayLayout.zone(SceneKind.TICKER, cfg, ZoneKind.INFO)!!
        val tagW = min(z.w * 0.3f, 360f * k)
        if (layer != Layer.MOVING) plate(c, theme, p, z, k * 0.7f, strong = false)
        if (layer != Layer.STATIC) {
            val items = (listOf(cfg.tagline, cfg.schedule) + cfg.socials.filter { it.isNotBlank() }).filter { it.isNotBlank() }
            val line = items.joinToString("     •     ") + "     •     "
            val ts = z.h * 0.42f
            val seg = max(1f, c.txt.width(line, FontId.BOLD, ts))
            val off = ((t / OverlayAnim.LOOP_SEC) % 1.0).toFloat() * seg
            c.save(); c.clipRect(z.x + tagW, z.y, z.w - tagW, z.h)
            var x = z.x + tagW - off
            while (x < z.right) { c.txt.draw(line, x, c.txt.baselineFor(z.y + z.h / 2, FontId.BOLD, ts), FontId.BOLD, ts, fill(col(p.text, 0.92f)), Align.LEFT); x += seg }
            c.restore()
        }
        if (layer != Layer.MOVING) {
            val tag = Zone(ZoneKind.TITLE, z.x, z.y, tagW, z.h)
            c.rect(tag.x, tag.y, tag.w, tag.h, Paint().apply { shader = linear(tag.x, 0f, tag.right, 0f, intArrayOf(col(p.primary), col(p.secondary))) })
            val tf = FontId.of(theme.font)
            val s = c.txt.fit(cfg.channel, tf, z.h * 0.5f, tagW - 30f * k)
            c.txt.draw(cfg.channel, tag.x + tag.w / 2, c.txt.baselineFor(tag.y + tag.h / 2, tf, s), tf, s, fill(col(contrastOn(p.primary))))
        }
    }

    /** Socials pop-up: each network slides in, stays, slides out (whole cycle = one loop). */
    fun drawSocialsPop(c: Gfx, theme0: OverlayTheme, cfg: OverlayConfig, t: Double) {
        val theme = cfg.styled(theme0)
        val p = pal(theme, cfg); val k = cfg.width / OverlayLayout.REF_W
        val z = OverlayLayout.zone(SceneKind.SOCIALS, cfg, ZoneKind.SOCIALS)!!
        val socials = cfg.socials.filter { it.isNotBlank() }.ifEmpty { listOf("@" + cfg.channel.lowercase().replace(" ", "")) }
        val per = OverlayAnim.LOOP_SEC / socials.size
        val i = ((t % OverlayAnim.LOOP_SEC) / per).toInt().coerceIn(0, socials.lastIndex)
        val lt = ((t % OverlayAnim.LOOP_SEC) - i * per) / per
        val inP = (lt / 0.2).coerceIn(0.0, 1.0).toFloat(); val outP = ((1 - lt) / 0.2).coerceIn(0.0, 1.0).toFloat()
        val slide = (1f - backOut(inP)) * -z.w + (1f - outP) * -z.w * 0.4f
        val a = min(inP * 2, outP * 2).coerceIn(0f, 1f)
        if (a <= 0.01f) return
        c.save(); c.translate(slide, 0f)
        plate(c, theme, p, z, k, strong = true)
        val r = z.h * 0.32f
        c.circle(z.x + z.h / 2, z.y + z.h / 2, r, fill(col(p.primary, a)))
        val handle = socials[i]
        val net = when {
            handle.contains("tiktok", true) -> "TikTok"; handle.contains("insta", true) -> "Instagram"; handle.contains("youtu", true) -> "YouTube"
            handle.contains("twitter", true) || handle.contains("x.com", true) -> "X"; handle.contains("discord", true) -> "Discord"; else -> "Suis-moi"
        }
        c.txt.draw(net.take(1), z.x + z.h / 2, c.txt.baselineFor(z.y + z.h / 2, FontId.BOLD, r), FontId.BOLD, r, fill(col(contrastOn(p.primary), a)))
        c.txt.draw(net.uppercase(), z.x + z.h, z.y + z.h * 0.4f, FontId.BOLD, 22f * k, fill(col(p.accent, a)), Align.LEFT, 3f * k)
        c.txt.draw(handle, z.x + z.h, z.y + z.h * 0.78f, FontId.of(theme.font), c.txt.fit(handle, FontId.of(theme.font), z.h * 0.34f, z.w - z.h - 30f * k), fill(col(p.text, a)), Align.LEFT)
        c.restore()
    }

    // ================================================================== Twitch profile assets (square)

    private fun initials(name: String): String {
        val words = name.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
        val camel = Regex("(?<=[a-z])(?=[A-Z])")
        val parts = if (words.size >= 2) words else words.firstOrNull()?.split(camel) ?: emptyList()
        return parts.take(2).joinToString("") { it.take(1).uppercase() }.ifEmpty { "TV" }
    }

    /** Subscriber badge for [months] (shape grows with seniority). Square of [s] pixels. */
    fun drawSubBadge(c: Gfx, theme0: OverlayTheme, cfg: OverlayConfig, months: Int, s: Float) {
        val theme = cfg.styled(theme0); val p = pal(theme, cfg)
        val cx = s / 2; val cy = s / 2; val r = s * 0.46f
        val tier = OverlayConfig.SUB_TIERS.indexOf(months).coerceAtLeast(0)
        val colors = listOf(p.secondary, p.primary, p.accent, p.glow, p.primary, p.accent)
        val shape = when (tier) {
            0 -> Path().addCircle(cx, cy, r)
            1 -> Shapes.hexagon(cx, cy, r)
            2 -> Path().moveTo(cx, cy - r).lineTo(cx + r * 0.85f, cy - r * 0.45f).lineTo(cx + r * 0.7f, cy + r * 0.5f).lineTo(cx, cy + r).lineTo(cx - r * 0.7f, cy + r * 0.5f).lineTo(cx - r * 0.85f, cy - r * 0.45f).closePath()
            3 -> Shapes.star(cx, cy, r, 5, 0.55f)
            4 -> Path().moveTo(cx, cy - r).lineTo(cx + r, cy).lineTo(cx, cy + r).lineTo(cx - r, cy).closePath()
            else -> Shapes.star(cx, cy, r, 8, 0.72f)
        }
        c.path(shape, Paint().apply { shader = linear(0f, 0f, s, s, intArrayOf(col(lighten(colors[tier], 0.35f)), col(colors[tier]))) })
        c.path(shape, stroke(col(0xFF000000, 0.55f), s * 0.06f))
        if (tier == 5) c.path(Path().moveTo(cx - r * 0.5f, cy - r * 0.55f).lineTo(cx - r * 0.3f, cy - r * 0.8f).lineTo(cx, cy - r * 0.6f).lineTo(cx + r * 0.3f, cy - r * 0.8f).lineTo(cx + r * 0.5f, cy - r * 0.55f).closePath(), fill(col(0xFFFFD45C)))
        val label = "$months"
        val ts = s * (if (label.length > 1) 0.4f else 0.48f)
        c.txt.draw(label, cx, c.txt.baselineFor(cy + s * 0.03f, FontId.BOLD, ts), FontId.BOLD, ts, fill(col(contrastOn(colors[tier]))))
    }

    /** Text emote in the theme style (GG, HYPE, LOL…). Square of [s] pixels, transparent. */
    fun drawEmote(c: Gfx, theme0: OverlayTheme, cfg: OverlayConfig, text: String, s: Float, index: Int = 0) {
        val theme = cfg.styled(theme0); val p = pal(theme, cfg)
        val bg = listOf(p.primary, p.secondary, p.accent, p.glow)[index % 4]
        val cx = s / 2; val cy = s / 2
        when (index % 3) {
            0 -> c.circle(cx, cy, s * 0.46f, fill(col(bg)))
            1 -> c.path(Shapes.star(cx, cy, s * 0.5f, 12, 0.8f), fill(col(bg)))
            else -> c.path(Shapes.heart(cx, cy + s * 0.04f, s * 0.95f), fill(col(bg)))
        }
        val tf = FontId.of(theme.font).let { if (it == FontId.PIXEL || it == FontId.SCRIPT) FontId.BOLD else it }
        val ts = c.txt.fit(text, tf, s * 0.42f, s * 0.82f)
        val y = c.txt.baselineFor(cy, tf, ts)
        c.txt.draw(text, cx, y, tf, ts, stroke(col(0xFF000000), s * 0.07f))
        c.txt.draw(text, cx, y, tf, ts, fill(col(0xFFFFFFFF)))
    }

    /** Channel points icon: coin with the channel initial. */
    fun drawPoints(c: Gfx, theme0: OverlayTheme, cfg: OverlayConfig, s: Float) {
        val theme = cfg.styled(theme0); val p = pal(theme, cfg)
        val cx = s / 2; val cy = s / 2; val r = s * 0.44f
        c.circle(cx, cy, r, Paint().apply { shader = radial(cx - r * 0.3f, cy - r * 0.3f, r * 1.6f, intArrayOf(col(lighten(p.accent, 0.5f)), col(p.accent), col(p.primary))) })
        c.circle(cx, cy, r * 0.78f, stroke(col(0xFFFFFFFF, 0.8f), s * 0.05f))
        val ini = initials(cfg.channel).take(1)
        val ts = r * 1.1f
        c.txt.draw(ini, cx, c.txt.baselineFor(cy, FontId.BOLD, ts), FontId.BOLD, ts, fill(col(contrastOn(p.accent))))
    }

    /** Profile picture / logo: theme background, monogram and ring. Square of [s] pixels. */
    fun drawAvatar(c: Gfx, theme0: OverlayTheme, cfg: OverlayConfig, s: Float) {
        val theme = cfg.styled(theme0); val p = pal(theme, cfg)
        c.save(); c.clipPath(Path().addCircle(s / 2, s / 2, s / 2))
        cardBackground(c, theme, p, s, s, 0.6f)
        c.restore()
        c.circle(s / 2, s / 2, s * 0.47f, stroke(col(p.glow), s * 0.03f).glow(s * 0.02f))
        cfg.logoBytes?.let { b -> c.image(b, s * 0.17f, s * 0.17f, s * 0.66f, s * 0.66f); return }
        val tf = FontId.of(theme.font)
        val ini = initials(cfg.channel)
        val ts = c.txt.fit(ini, tf, s * 0.42f, s * 0.7f)
        c.txt.draw(ini, s / 2 + s * 0.01f, c.txt.baselineFor(s / 2, tf, ts) + s * 0.01f, tf, ts, fill(col(0xFF000000, 0.35f)))
        c.txt.draw(ini, s / 2, c.txt.baselineFor(s / 2, tf, ts), tf, ts, titlePaint(theme, p, Zone(ZoneKind.TITLE, 0f, s * 0.3f, s, s * 0.4f)))
    }

    private fun backOut(x: Float): Float { val c1 = 1.70158f; val c3 = c1 + 1; return 1 + c3 * (x - 1) * (x - 1) * (x - 1) + c1 * (x - 1) * (x - 1) }

    // ================================================================== stingers

    /**
     * Full-screen transition. At [OverlayAnim.STINGER_COVER_SEC] the screen is entirely covered
     * (that is where OBS switches the scenes), before and after it is partly transparent.
     */
    fun drawStinger(c: Gfx, theme0: OverlayTheme, cfg: OverlayConfig, style: StingerStyle, t: Double) {
        val theme = cfg.styled(theme0)
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
