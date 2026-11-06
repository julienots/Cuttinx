package com.superessence.cuttinx.core.tutorial

import com.superessence.cuttinx.core.gfx.*
import com.superessence.cuttinx.core.overlay.OverlayConfig
import com.superessence.cuttinx.core.overlay.OverlayThemes
import com.superessence.cuttinx.core.overlay.SceneKind
import com.superessence.cuttinx.core.overlay.StingerStyle
import com.superessence.cuttinx.core.overlay.render.OverlayRenderer
import com.superessence.cuttinx.core.overlay.render.txt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws a frame of a tutorial film: the simulated CUTTINX screen, the finger with its tap ripples,
 * the step captions, and the title / end cards. Everything is vector drawing, so a film looks sharp
 * at any size and costs nothing to ship.
 */
class FilmRenderer(private val overlays: OverlayRenderer = OverlayRenderer()) {
    private val yellow = 0xFFFFCC1F
    private val blue = 0xFF3D8BFF
    private val pink = 0xFFFF4FA3
    private val bg = 0xFF0E0F12
    private val panel = 0xFF17181D
    private val panel2 = 0xFF22242B
    private val dim = 0xFF9AA0AC

    fun accentOf(f: Film) = when (f.space) { TutoSpace.EDITOR -> yellow; TutoSpace.TWITCH -> blue; TutoSpace.BRAND -> pink }

    /** Draws [film] at time [t] on a [w]×[h] surface (16:9 recommended). */
    fun draw(c: Gfx, film: Film, t: Double, w: Int, h: Int) {
        val k = min(w / L.W, h / L.H)
        c.save()
        c.clear(col(bg))
        c.translate((w - L.W * k) / 2, (h - L.H * k) / 2)
        c.scale(k, k)
        val u = film.state(t)
        val acc = accentOf(film)
        when (u.screen) {
            Screen.EDITOR -> editor(c, u, t, acc)
            Screen.TWITCH -> twitch(c, u, t)
            Screen.OBS -> obs(c, u, t)
            Screen.BRAND -> brand(c, u)
        }
        u.toast?.let { toast(c, it) }
        caption(c, film, t, acc)
        finger(c, film, t, acc)
        cards(c, film, t, acc)
        c.restore()
    }

    // ------------------------------------------------------------------ common pieces

    private fun text(c: Gfx, s: String, x: Float, y: Float, size: Float, color: Long, font: FontId = FontId.BOLD, align: Align = Align.LEFT, alpha: Float = 1f) =
        c.txt.draw(s, x, y, font, size, fill(col(color, alpha)), align)

    private fun button(c: Gfx, label: String, cx: Float, cy: Float, w: Float, h: Float, fillC: Long, textC: Long, pressed: Boolean, icon: IconDef? = null) {
        val s = if (pressed) 0.92f else 1f
        val ww = w * s; val hh = h * s
        if (pressed) c.rrect(cx - ww / 2 - 4, cy - hh / 2 - 4, ww + 8, hh + 8, hh / 2 + 4, fill(col(fillC, 0.35f)).glow(8f))
        c.rrect(cx - ww / 2, cy - hh / 2, ww, hh, hh / 2, fill(col(fillC)))
        if (icon == null) { c.txt.draw(label, cx, c.txt.baselineFor(cy, FontId.BOLD, 14f * s), FontId.BOLD, 14f * s, fill(col(textC))); return }
        val tw = c.txt.width(label, FontId.BOLD, 14f * s); val isz = 16f * s
        val x0 = cx - (tw + isz + 6f) / 2
        c.icon(icon, x0, cy - isz / 2, isz, col(textC))
        c.txt.draw(label, x0 + isz + 6f, c.txt.baselineFor(cy, FontId.BOLD, 14f * s), FontId.BOLD, 14f * s, fill(col(textC)), Align.LEFT)
    }

    private fun toast(c: Gfx, s: String) {
        val w = c.txt.width(s, FontId.BOLD, 16f) + 40f
        c.rrect(640f - w / 2, 372f, w, 34f, 17f, fill(col(0xEE000000)))
        text(c, s, 640f, c.txt.baselineFor(389f, FontId.BOLD, 16f), 16f, 0xFFFFFFFF, align = Align.CENTER)
    }

    private fun caption(c: Gfx, film: Film, t: Double, acc: Long) {
        val cap = film.caption(t) ?: return
        val (i, n) = film.stepOf(t)
        val y = L.CAP_Y
        val fadeIn = ((t - cap.t0) / 0.25).toFloat().coerceIn(0f, 1f)
        c.rrect(20f, y, L.W - 40f, 48f, 14f, fill(col(0xF2000000)))
        c.rrect(20f, y, 6f, 48f, 3f, fill(col(acc)))
        val badge = "$i/$n"
        c.rrect(40f, y + 11f, 54f, 26f, 13f, fill(col(acc)))
        text(c, badge, 67f, c.txt.baselineFor(y + 24f, FontId.BOLD, 15f), 15f, if (acc == yellow) 0xFF000000 else 0xFFFFFFFF, align = Align.CENTER)
        val size = c.txt.fit(cap.text, FontId.BOLD, 19f, L.W - 160f)
        text(c, cap.text, 110f, c.txt.baselineFor(y + 24f, FontId.BOLD, size), size, 0xFFFFFFFF, alpha = fadeIn)
    }

    private fun finger(c: Gfx, film: Film, t: Double, acc: Long) {
        val p = film.script.cursor(t) ?: return
        val lastTap = film.script.taps.filter { it <= t }.maxOrNull()
        val since = if (lastTap == null) 99.0 else t - lastTap
        if (since < 0.45) {
            val r = (10 + since / 0.45 * 34).toFloat(); val a = (1 - since / 0.45).toFloat()
            c.circle(p.x, p.y, r, stroke(col(acc, a), 4f))
            c.circle(p.x, p.y, r * 0.5f, fill(col(acc, a * 0.35f)))
        }
        val s = if (since < 0.15) 0.85f else 1f
        c.save(); c.translate(p.x, p.y); c.scale(s, s)
        val arrow = Path().moveTo(0f, 0f).lineTo(0f, 30f).lineTo(8f, 23f).lineTo(14f, 36f).lineTo(20f, 33f).lineTo(14f, 21f).lineTo(24f, 21f).closePath()
        c.path(arrow, fill(col(0xFF000000, 0.35f)).glow(4f))
        c.path(arrow, fill(col(0xFFFFFFFF)))
        c.path(arrow, stroke(col(0xFF000000), 2f))
        c.restore()
    }

    /** Title card (first 1.2 s) and end card (last 1.2 s). */
    private fun cards(c: Gfx, film: Film, t: Double, acc: Long) {
        val inA = (1 - (t - 0.9) / 0.4).toFloat().coerceIn(0f, 1f)
        val outA = ((t - (film.duration - 1.3)) / 0.4).toFloat().coerceIn(0f, 1f)
        if (inA > 0f) {
            c.rect(0f, 0f, L.W, L.H, fill(col(0xFF000000, 0.82f * inA)))
            text(c, "CUTTINX PRO · TUTO", 640f, 300f, 20f, acc, align = Align.CENTER, alpha = inA)
            val s = c.txt.fit(film.title, FontId.IMPACT, 56f, 1100f)
            text(c, film.title, 640f, 372f, s, 0xFFFFFFFF, FontId.IMPACT, Align.CENTER, inA)
            text(c, film.summary, 640f, 420f, 18f, dim, FontId.REGULAR, Align.CENTER, inA)
        }
        if (outA > 0f) {
            c.rect(0f, 0f, L.W, L.H, fill(col(0xFF000000, 0.8f * outA)))
            text(c, "À TOI DE JOUER !", 640f, 350f, 54f, acc, FontId.IMPACT, Align.CENTER, outA)
            text(c, film.title, 640f, 400f, 20f, 0xFFFFFFFF, FontId.REGULAR, Align.CENTER, outA)
        }
    }

    // ------------------------------------------------------------------ editor screen

    private fun editor(c: Gfx, u: Ui, t: Double, acc: Long) {
        // top bar
        c.rect(0f, 0f, L.W, 56f, fill(col(panel)))
        text(c, "CUTTINX", 20f, 36f, 24f, yellow, FontId.IMPACT)
        c.rrect(122f, 16f, 40f, 20f, 5f, fill(col(yellow))); text(c, "PRO", 142f, 31f, 12f, 0xFF000000, align = Align.CENTER)
        text(c, "Mon montage", 190f, 35f, 16f, 0xFFFFFFFF)
        // keyframe ◇+ / undo / redo / export
        val kfOn = u.clips.firstOrNull { it.id == u.selected }?.keyframes?.any { abs(it - u.playhead) < 0.15f } == true
        val kfc = if (u.pressed == "kf" || kfOn) acc else 0xFFFFFFFF
        if (u.pressed == "kf") c.circle(L.KF_X, L.TOP_Y, 22f, fill(col(acc, 0.2f)))
        c.icon(if (kfOn) IconSet.KeyframeRemove else IconSet.KeyframeAdd, L.KF_X - 13f, L.TOP_Y - 13f, 26f, col(kfc))
        c.icon(IconSet.Undo, L.UNDO_X - 12f, L.TOP_Y - 12f, 24f, col(0xFFFFFFFF))
        c.icon(IconSet.Redo, L.REDO_X - 12f, L.TOP_Y - 12f, 24f, col(0xFFFFFFFF))
        button(c, "Exporter", L.EXPORT_X, L.TOP_Y, 120f, 34f, yellow, 0xFF000000, u.pressed == "export")
        // library
        c.rect(0f, 56f, 322f, 400f, fill(col(panel)))
        c.rect(0f, 56f, 82f, 400f, fill(col(0xFF131419)))
        L.tabs.forEachIndexed { i, ic ->
            val sel = i == u.leftTab
            if (sel) c.rrect(6f, L.tabY(i) - 16f, 70f, 32f, 8f, fill(col(panel2)))
            c.icon(IconSet.all.getValue(ic), 20f, L.tabY(i) - 10f, 20f, col(if (sel) acc else 0xFFFFFFFF))
            text(c, L.tabNames[i].take(6), 58f, L.tabY(i) + 5f, 9f, if (sel) acc else dim, FontId.REGULAR, Align.CENTER)
        }
        text(c, L.tabNames.getOrNull(u.leftTab) ?: "", 96f, 84f, 16f, 0xFFFFFFFF)
        u.leftItems.forEachIndexed { i, s ->
            val y = L.itemY(i)
            c.rrect(94f, y - 16f, 216f, 32f, 8f, fill(col(if (i == u.leftHi) acc else panel2, if (i == u.leftHi) 0.35f else 1f)))
            text(c, s, 106f, c.txt.baselineFor(y, FontId.REGULAR, 13f), c.txt.fit(s, FontId.REGULAR, 13f, 170f), 0xFFFFFFFF, FontId.REGULAR)
            c.icon(IconSet.Add, 288f, y - 8f, 16f, col(acc))
        }
        if (u.leftItems.isEmpty()) {
            c.rrect(94f, 100f, 216f, 40f, 10f, fill(col(yellow)))
            c.icon(IconSet.Upload, 150f, 110f, 18f, col(0xFF000000)); text(c, "Importer", 176f, 126f, 15f, 0xFF000000)
        }
        // preview
        c.rect(322f, 56f, 640f, 400f, fill(col(0xFF08090B)))
        preview(c, u, t)
        // inspector
        c.rect(962f, 56f, 318f, 400f, fill(col(panel)))
        text(c, u.panel, L.INS_X, 90f, 16f, 0xFFFFFFFF)
        u.sliders.forEachIndexed { i, s ->
            val y = L.sliderY(i)
            text(c, s.label, L.INS_X, y + 8f, 12f, dim, FontId.REGULAR)
            text(c, s.display, L.INS_X + L.INS_W, y + 8f, 12f, 0xFFFFFFFF, FontId.REGULAR, Align.RIGHT)
            c.rrect(L.INS_X, y + 22f, L.INS_W, 6f, 3f, fill(col(panel2)))
            c.rrect(L.INS_X, y + 22f, L.INS_W * s.value, 6f, 3f, fill(col(acc)))
            c.circle(L.INS_X + L.INS_W * s.value, y + 25f, 9f, fill(col(acc)))
        }
        u.toggles.entries.forEachIndexed { i, (label, on) ->
            val y = L.toggleY(i, u.sliders.size)
            text(c, label, L.INS_X, y + 17f, 13f, 0xFFFFFFFF, FontId.REGULAR)
            c.rrect(L.INS_X + L.INS_W - 48f, y + 2f, 40f, 22f, 11f, fill(col(if (on) acc else panel2)))
            c.circle(L.INS_X + L.INS_W - (if (on) 19f else 37f), y + 13f, 8f, fill(col(0xFFFFFFFF)))
        }
        if (u.text != null && u.panel == "Texte") {
            c.rrect(L.INS_X, 270f, L.INS_W, 60f, 8f, stroke(col(acc), 2f))
            text(c, u.text ?: "", L.INS_X + 10f, 306f, c.txt.fit(u.text ?: "", FontId.BOLD, 16f, L.INS_W - 20f), 0xFFFFFFFF)
            text(c, "Animation : Pop · Fondu · Glisse · Karaoké", L.INS_X, 372f, 11f, dim, FontId.REGULAR)
            c.rrect(L.INS_X, 380f, 60f, 22f, 11f, fill(col(acc))); text(c, "Pop", L.INS_X + 30f, 396f, 12f, 0xFF000000, align = Align.CENTER)
        }
        // tool bar
        c.rect(0f, 412f, L.W, 44f, fill(col(panel)))
        L.tools.forEachIndexed { i, s ->
            val label = if (i == 3 && u.playing) "Pause" else s
            val ic = if (i == 3 && u.playing) IconSet.Pause else IconSet.all.getValue(L.toolIcons[i])
            button(c, label, L.toolX(i), L.TOOL_Y, 130f, 30f, if (i == 3) acc else panel2, if (i == 3 && acc == yellow) 0xFF000000 else 0xFFFFFFFF, u.pressed == "tool$i", ic)
        }
        timeline(c, u, acc)
        u.dialog?.let { exportDialog(c, u, acc) }
    }

    private fun timeline(c: Gfx, u: Ui, acc: Long) {
        c.rect(0f, 456f, L.W, 204f, fill(col(bg)))
        // ruler
        c.rect(0f, L.RULER_Y - 6f, L.W, 26f, fill(col(panel)))
        var s = 0
        while (L.x(s.toFloat()) < L.W) {
            val x = L.x(s.toFloat())
            c.line(x, L.RULER_Y + 8f, x, L.RULER_Y + 20f, stroke(col(0xFF4A4D57), 1f))
            text(c, "0:%02d".format(s), x + 3f, L.RULER_Y + 8f, 10f, dim, FontId.REGULAR)
            s++
        }
        val names = listOf("Vidéo", "Incrust.", "Texte", "Son")
        val icons = listOf(IconSet.Video, IconSet.Image, IconSet.Text, IconSet.Music)
        for (r in 0 until 4) {
            c.rect(0f, L.rowY[r], L.W, L.rowH[r], fill(col(if (r % 2 == 0) 0xFF14151A else 0xFF121317)))
            c.rect(0f, L.rowY[r], 112f, L.rowH[r], fill(col(panel)))
            c.icon(icons[r], 8f, L.rowY[r] + L.rowH[r] / 2 - 7f, 14f, col(dim))
            text(c, names[r], 28f, c.txt.baselineFor(L.rowY[r] + L.rowH[r] / 2, FontId.REGULAR, 11f), 11f, 0xFFFFFFFF, FontId.REGULAR)
        }
        for (cl in u.clips) {
            val x = L.x(cl.start); val w = max(4f, cl.len * L.PPS); val y = L.rowY[cl.track] + 3f; val hh = L.rowH[cl.track] - 6f
            c.save(); c.clipRRect(x, y, w, hh, 6f)
            c.rect(x, y, w, hh, fill(col(cl.color, 0.88f)))
            if (cl.track == 3) wave(c, x, y, w, hh, if (cl.id == "v") u.noise else 0.1f)
            if (cl.track == 0) for (i in 0 until (w / 46f).toInt() + 1) c.rect(x + i * 46f + 2f, y + hh - 14f, 40f, 10f, fill(col(0xFF000000, 0.18f)))
            text(c, cl.label, x + 8f, y + 15f, 11f, 0xFFFFFFFF)
            cl.badge?.let { IconSet.all[it]?.let { ic -> c.icon(ic, x + w - 20f, y + 4f, 13f, col(0xFFFFFFFF)) } }
            cl.transition?.let { c.rect(x, y, 26f, hh, fill(col(0xFFFFFFFF, 0.3f))); c.icon(IconSet.Transitions, x + 5f, y + hh / 2 - 8f, 16f, col(0xFFFFFFFF)) }
            c.restore()
            for (kf in cl.keyframes) {
                val kx = L.x(kf); val ky = y + hh - 8f
                c.save(); c.translate(kx, ky); c.rotate(45f); c.rect(-5f, -5f, 10f, 10f, fill(col(acc))); c.rect(-5f, -5f, 10f, 10f, stroke(col(0xFF000000), 1.5f)); c.restore()
            }
            if (cl.id == u.selected) c.rrect(x, y, w, hh, 6f, stroke(col(0xFFFFFFFF), 2.5f))
        }
        val px = L.x(u.playhead)
        c.line(px, L.RULER_Y - 6f, px, 658f, stroke(col(acc), 2f))
        c.path(Path().moveTo(px - 7f, L.RULER_Y - 6f).lineTo(px + 7f, L.RULER_Y - 6f).lineTo(px, L.RULER_Y + 4f).closePath(), fill(col(acc)))
    }

    private fun wave(c: Gfx, x: Float, y: Float, w: Float, h: Float, noise: Float) {
        var i = 0f
        val mid = y + h / 2
        while (i < w) {
            val n = i / 4f
            val speech = (abs(sin(n * 0.21f)) * abs(sin(n * 0.047f + 1f))).let { if (it < 0.25f) 0f else it }
            val fuzz = noise * 0.18f * (0.5f + 0.5f * sin(n * 2.7f))
            val a = (speech * 0.9f + fuzz).coerceAtMost(1f) * h * 0.45f
            c.rect(x + i, mid - a, 2.2f, a * 2 + 1f, fill(col(0xFFFFFFFF, 0.7f)))
            i += 4f
        }
    }

    // ------------------------------------------------------------------ the "video" in the preview

    private fun preview(c: Gfx, u: Ui, t: Double) {
        val x = L.PX; val y = L.PY; val w = L.PW; val h = L.PH
        c.save(); c.clipRect(x, y, w, h)
        fun look(id: Int) {
            c.save()
            c.translate(x + w / 2 + u.panX * w, y + h / 2 + u.panY * h); c.rotate(u.rot); c.scale(u.zoom, u.zoom); c.translate(-w / 2, -h / 2)
            scenery(c, id, w, h, t)
            c.restore()
        }
        look(u.look)
        if (u.transition in 0f..1f) {
            c.save()
            val s = u.transition * w * 1.6f
            c.clipPath(Shapes.heart(x + w / 2, y + h / 2 + s * 0.05f, s.coerceAtLeast(1f)))
            look(1)
            c.restore()
        }
        // green-screen facecam on the overlay track
        u.clips.firstOrNull { it.look == 2 && u.playhead >= it.start && u.playhead < it.end }?.let { facecam(c, u, x, y, w, h) }
        // tint effects
        when (u.tint) {
            "vhs" -> { var yy = y; while (yy < y + h) { c.rect(x, yy, w, 1.5f, fill(col(0xFF000000, 0.25f))); yy += 4f } }
            else -> {}
        }
        // title
        val tx = u.text
        if (tx != null && u.textAlpha > 0f) {
            c.save(); c.translate(x + w / 2, y + h * 0.42f); c.scale(u.textScale, u.textScale)
            val s = c.txt.fit(tx, FontId.IMPACT, 54f, w - 60f)
            c.txt.draw(tx, 0f, 18f, FontId.IMPACT, s, stroke(col(0xFF000000, u.textAlpha), 9f))
            c.txt.draw(tx, 0f, 18f, FontId.IMPACT, s, fill(col(0xFFFFFFFF, u.textAlpha)))
            c.restore()
        }
        c.restore()
        if (u.playing) text(c, "● LECTURE", x + 10f, y + 22f, 12f, 0xFFFF5A5A)
    }

    /** The demo footage: 0 = sunset over hills, 1 = city at night. Lightly animated. */
    private fun scenery(c: Gfx, id: Int, w: Float, h: Float, t: Double) {
        if (id == 1) {
            c.rect(0f, 0f, w, h, shaded(linear(0f, 0f, 0f, h, intArrayOf(col(0xFF0B1030), col(0xFF2B2E6B)))))
            c.circle(w * 0.8f, h * 0.2f, 26f, fill(col(0xFFF5F0D0)))
            for (i in 0 until 40) c.circle((i * 97 % w.toInt()).toFloat(), (i * 53 % (h * 0.5f).toInt()).toFloat(), 1.4f, fill(col(0xFFFFFFFF, 0.5f + 0.5f * sin(t.toFloat() * 2 + i))))
            var bx = 0f; var i = 0
            while (bx < w) {
                val bw = 40f + (i * 37 % 50); val bh = h * (0.3f + (i * 23 % 40) / 100f)
                c.rect(bx, h - bh, bw - 4f, bh, fill(col(0xFF141834)))
                var wy = h - bh + 10f
                while (wy < h - 10f) { var wx = bx + 6f; while (wx < bx + bw - 12f) { if ((wx.toInt() * 7 + wy.toInt() * 3 + i) % 5 != 0) c.rect(wx, wy, 5f, 7f, fill(col(0xFFFFD27A, 0.8f))); wx += 11f }; wy += 14f }
                bx += bw; i++
            }
        } else {
            c.rect(0f, 0f, w, h, shaded(linear(0f, 0f, 0f, h, intArrayOf(col(0xFF3A1C71), col(0xFFD76D77), col(0xFFFFAF7B)))))
            c.circle(w * 0.5f, h * 0.62f, 60f, fill(col(0xFFFFE08A)))
            c.circle(w * 0.5f, h * 0.62f, 90f, fill(col(0xFFFFE08A, 0.25f)).glow(18f))
            c.path(Path().moveTo(0f, h * 0.7f).quadTo(w * 0.25f, h * 0.55f, w * 0.5f, h * 0.72f).quadTo(w * 0.75f, h * 0.88f, w, h * 0.66f).lineTo(w, h).lineTo(0f, h).closePath(), fill(col(0xFF2A1240)))
            c.path(Path().moveTo(0f, h * 0.85f).quadTo(w * 0.4f, h * 0.72f, w, h * 0.9f).lineTo(w, h).lineTo(0f, h).closePath(), fill(col(0xFF170A26)))
            val bird = (t * 40 % (w + 100)).toFloat() - 50f
            c.path(Path().moveTo(bird, h * 0.3f).quadTo(bird + 8f, h * 0.27f, bird + 16f, h * 0.3f).quadTo(bird + 24f, h * 0.27f, bird + 32f, h * 0.3f), stroke(col(0xFF1A0A2A), 2.5f))
        }
    }

    private fun facecam(c: Gfx, u: Ui, x: Float, y: Float, w: Float, h: Float) {
        val fw = w * 0.32f; val fh = h * 0.5f
        val fx = x + w * 0.62f + u.panX * w; val fy = y + h * 0.36f + u.panY * h
        c.save()
        if (u.mask > 0f) {
            val r = min(fw, fh) * 0.48f
            c.clipPath(Path().addCircle(fx + fw / 2, fy + fh / 2, r))
        }
        if (!u.keyed) c.rect(fx, fy, fw, fh, fill(col(0xFF27C93F)))
        else if (u.mask > 0f) c.rect(fx, fy, fw, fh, fill(col(0xFF5B3A8C, 0.85f)))
        // person: head + shoulders
        c.circle(fx + fw / 2, fy + fh * 0.4f, fh * 0.17f, fill(col(0xFFE8B48A)))
        c.path(Path().moveTo(fx + fw * 0.18f, fy + fh).quadTo(fx + fw / 2, fy + fh * 0.5f, fx + fw * 0.82f, fy + fh).closePath(), fill(col(0xFF3D8BFF)))
        c.rect(fx + fw * 0.36f, fy + fh * 0.22f, fw * 0.28f, fh * 0.07f, fill(col(0xFF2B1A12)))
        c.restore()
        if (u.mask > 0f) {
            val r = min(fw, fh) * 0.48f
            c.circle(fx + fw / 2, fy + fh / 2, r, stroke(col(0xFFFFFFFF, 1f - u.feather * 0.8f), 3f + u.feather * 6f))
        }
    }

    private fun exportDialog(c: Gfx, u: Ui, acc: Long) {
        c.rect(0f, 0f, L.W, L.H, fill(col(0xAA000000)))
        c.rrect(400f, 170f, 480f, 290f, 18f, fill(col(panel)))
        text(c, "Exporter la vidéo", 430f, 212f, 22f, 0xFFFFFFFF, FontId.IMPACT)
        text(c, "RÉSOLUTION", 430f, 254f, 12f, acc)
        listOf("720p", "1080p", "2K", "4K").forEachIndexed { i, s ->
            val sel = i == u.dialogChoice
            c.rrect(430f + i * 106f, 272f, 96f, 30f, 15f, fill(col(if (sel) acc else panel2)))
            text(c, s, 478f + i * 106f, 292f, 13f, if (sel && acc == yellow) 0xFF000000 else 0xFFFFFFFF, align = Align.CENTER)
        }
        text(c, "MP4 · H.264 + AAC · 30 i/s", 430f, 336f, 13f, dim, FontId.REGULAR)
        if (u.progress >= 0f) {
            c.rrect(430f, 370f, 420f, 12f, 6f, fill(col(panel2)))
            c.rrect(430f, 370f, 420f * u.progress, 12f, 6f, fill(col(acc)))
            text(c, "Export ${(u.progress * 100).toInt()} %", 430f, 410f, 14f, 0xFFFFFFFF)
        } else button(c, "Exporter", 640f, 420f, 200f, 38f, acc, if (acc == yellow) 0xFF000000 else 0xFFFFFFFF, false)
    }

    // ------------------------------------------------------------------ Twitch editor screen

    private fun cfgFor(u: Ui, w: Int, h: Int) = OverlayConfig(
        themeId = OverlayThemes.all[u.themeIdx % OverlayThemes.all.size].id, channel = u.channel, width = w, height = h,
        palette = if (u.colorEdit < 0) null else OverlayThemes.all[u.themeIdx % OverlayThemes.all.size].palette.let { p ->
            when (u.colorEdit) { 2 -> p.copy(primary = 0xFFFF3D8B, glow = 0xFFFF3D8B); else -> p.copy(primary = 0xFF3DE0FF, glow = 0xFF3DE0FF, accent = 0xFFFFE14D) }
        },
    )

    private fun twitch(c: Gfx, u: Ui, t: Double) {
        c.rect(0f, 0f, L.W, 56f, fill(col(panel)))
        c.icon(IconSet.Twitch, 20f, 16f, 24f, col(blue)); text(c, "Twitch Editor", 52f, 36f, 20f, blue)
        // theme grid
        c.rect(0f, 56f, 430f, 664f, fill(col(panel)))
        text(c, "Thèmes", 20f, 90f, 16f, 0xFFFFFFFF)
        for (i in 0 until 8) {
            val th = OverlayThemes.all[i]
            val gx = 20f + (i % 2) * 200f; val gy = 110f + (i / 2) * 130f
            c.rrect(gx, gy, 190f, 116f, 10f, shaded(linear(gx, gy, gx + 190f, gy + 116f, intArrayOf(col(th.palette.background), col(th.palette.backgroundAlt)))))
            c.rrect(gx + 30f, gy + 34f, 130f, 30f, 6f, fill(col(th.palette.primary, 0.9f)))
            text(c, th.name, gx + 95f, gy + 100f, c.txt.fit(th.name, FontId.BOLD, 12f, 170f), th.palette.text, align = Align.CENTER)
            if (i == u.themeIdx) c.rrect(gx - 2f, gy - 2f, 194f, 120f, 12f, stroke(col(blue), 4f))
        }
        // live preview (real overlay engine)
        val pw = 820; val ph = 461
        c.save(); c.translate(445f, 66f); c.clipRect(0f, 0f, pw.toFloat(), ph.toFloat())
        overlays.drawScene(c, OverlayThemes.all[u.themeIdx % OverlayThemes.all.size], cfgFor(u, pw, ph), SceneKind.STARTING, t)
        c.restore()
        // fields under the preview
        text(c, "Nom de la chaîne", 450f, 548f, 12f, blue)
        c.rrect(450f, 556f, 340f, 40f, 8f, stroke(col(blue), 2f))
        text(c, u.channel + if ((t * 2).toInt() % 2 == 0) "|" else "", 464f, 582f, 17f, 0xFFFFFFFF, FontId.REGULAR)
        text(c, "Couleurs", 830f, 548f, 12f, blue)
        listOf(0xFFFF3D8B, 0xFF3DE0FF, 0xFFFFE14D, 0xFF9CFF3D).forEachIndexed { i, cc ->
            c.rrect(830f + i * 46f, 556f, 38f, 40f, 8f, fill(col(cc)))
            if (u.colorEdit - 2 == i) c.rrect(828f + i * 46f, 554f, 42f, 44f, 9f, stroke(col(0xFFFFFFFF), 3f))
        }
        button(c, "Exporter le pack OBS", 1150f, 576f, 220f, 40f, blue, 0xFFFFFFFF, false)
        if (u.progress >= 0f) {
            c.rrect(450f, 612f, 815f, 10f, 5f, fill(col(panel2))); c.rrect(450f, 612f, 815f * u.progress, 10f, 5f, fill(col(blue)))
            text(c, "Scène « Je reviens » (animation)… ${(u.progress * 100).toInt()} %", 450f, 644f, 12f, dim, FontId.REGULAR)
        }
    }

    // ------------------------------------------------------------------ OBS screen

    private fun obs(c: Gfx, u: Ui, t: Double) {
        c.rect(0f, 0f, L.W, L.H, fill(col(0xFF1E1F26)))
        c.rect(0f, 0f, L.W, 30f, fill(col(0xFF2B2D36)))
        text(c, "OBS Studio — Scène : " + listOf("Jeu", "Le live va commencer", "Jeu")[u.scene.coerceIn(0, 2)], 16f, 21f, 13f, 0xFFDDDDDD, FontId.REGULAR)
        // program preview: fake game + overlay
        val px = 200f; val py = 40f; val pw = 880; val ph = 495
        c.save(); c.translate(px, py); c.clipRect(0f, 0f, pw.toFloat(), ph.toFloat())
        val th = OverlayThemes["armor"]
        val cfg = OverlayConfig(themeId = th.id, channel = "LunaGaming", width = pw, height = ph)
        if (u.scene == 1) overlays.drawScene(c, th, cfg, SceneKind.STARTING, t)
        else {
            c.rect(0f, 0f, pw.toFloat(), ph.toFloat(), shaded(linear(0f, 0f, pw.toFloat(), ph.toFloat(), intArrayOf(col(0xFF1B4D3E), col(0xFF0F2A3D)))))
            for (i in 0 until 6) c.rect(60f + i * 120f, 300f - (i % 3) * 40f, 80f, 200f, fill(col(0xFF2E6B4F, 0.8f)))
            overlays.drawScene(c, th, cfg, SceneKind.GAMEPLAY, t)
        }
        if (u.transition in 0f..1f) overlays.drawStinger(c, th, cfg, StingerStyle.SPIDER_WEB, u.transition * 1.2)
        c.restore()
        // docks
        val dy = 548f
        fun dock(x: Float, w: Float, title: String) { c.rect(x, dy, w, 172f, fill(col(0xFF2B2D36))); text(c, title, x + 10f, dy + 20f, 12f, 0xFFBBBBBB) }
        dock(0f, 300f, "Scènes"); dock(304f, 330f, "Sources"); dock(638f, 300f, "Mixeur audio"); dock(942f, 338f, "Transitions de scène")
        listOf("Jeu", "Le live va commencer", "Je reviens").forEachIndexed { i, s ->
            val sel = (u.scene == 1 && i == 1) || (u.scene != 1 && i == 0)
            c.rect(6f, dy + 32f + i * 28f, 288f, 24f, fill(col(if (sel) 0xFF3A5BA0 else 0xFF33353F)))
            text(c, s, 16f, dy + 49f + i * 28f, 12f, 0xFFFFFFFF, FontId.REGULAR)
        }
        u.obsSources.take(4).forEachIndexed { i, s -> c.rect(310f, dy + 32f + i * 28f, 318f, 24f, fill(col(0xFF33353F))); c.icon(IconSet.Eye, 316f, dy + 36f + i * 28f, 15f, col(0xFFBBBBBB)); text(c, s, 338f, dy + 49f + i * 28f, 12f, 0xFFFFFFFF, FontId.REGULAR) }
        c.icon(IconSet.Add, 549f, dy + 132f, 22f, col(0xFFFFFFFF))
        for (i in 0 until 2) { c.rect(650f, dy + 40f + i * 50f, 270f, 8f, fill(col(0xFF33353F))); c.rect(650f, dy + 40f + i * 50f, 150f + i * 40f, 8f, fill(col(0xFF4CAF50))) }
        c.rect(952f, dy + 36f, 318f, 28f, fill(col(0xFF33353F))); text(c, "Stinger ▾", 962f, dy + 55f, 13f, 0xFFFFFFFF, FontId.REGULAR)
        c.icon(IconSet.Add, 980f, dy + 42f, 20f, col(0xFFFFFFFF))
        text(c, "Durée : 1200 ms", 962f, dy + 92f, 12f, 0xFFBBBBBB, FontId.REGULAR)
        // add-source menu
        if (u.obsMenu) {
            c.rect(540f, 440f, 220f, 150f, fill(col(0xFF3A3C47)))
            listOf("Capture de jeu", "Image", "Navigateur", "Source média", "Texte").forEachIndexed { i, s ->
                if (i == 2) c.rect(540f, 444f + i * 28f, 220f, 26f, fill(col(0xFF3A5BA0)))
                text(c, s, 556f, 462f + i * 28f, 13f, 0xFFFFFFFF, FontId.REGULAR)
            }
        }
        u.obsProps?.let { kind ->
            c.rect(0f, 0f, L.W, L.H, fill(col(0x88000000)))
            c.rect(380f, 180f, 520f, 320f, fill(col(0xFF2B2D36)))
            text(c, if (kind == "browser") "Propriétés de « Le live va commencer »" else "Propriétés de « Stinger »", 400f, 212f, 14f, 0xFFFFFFFF)
            if (kind == "browser") {
                c.rect(400f, 290f, 16f, 16f, fill(col(if (u.dialogChoice == 1) blue else 0xFF55575F)))
                text(c, "Fichier local", 426f, 304f, 13f, 0xFFFFFFFF, FontId.REGULAR)
                c.rect(400f, 322f, 480f, 26f, fill(col(0xFF1E1F26))); text(c, if (u.dialogChoice == 1) "…/scenes/01_le-live-va-commencer/scene.html" else "", 408f, 340f, 12f, 0xFFDDDDDD, FontId.REGULAR)
                text(c, "Largeur 1920   Hauteur 1080", 400f, 380f, 13f, 0xFFDDDDDD, FontId.REGULAR)
            } else {
                text(c, "Fichier vidéo", 400f, 260f, 12f, 0xFFBBBBBB, FontId.REGULAR)
                c.rect(400f, 270f, 480f, 26f, fill(col(0xFF1E1F26))); text(c, "…/transitions/toile-d-araignee.png", 408f, 288f, 12f, 0xFFDDDDDD, FontId.REGULAR)
                text(c, "Point de transition : 600 ms", 400f, 330f, 13f, 0xFFFFFFFF)
            }
            c.rect(700f, 452f, 120f, 30f, fill(col(blue))); text(c, "OK", 760f, 472f, 13f, 0xFFFFFFFF, align = Align.CENTER)
        }
    }

    // ------------------------------------------------------------------ ID Influenceur screen

    private fun brand(c: Gfx, u: Ui) {
        c.rect(0f, 0f, L.W, 56f, fill(col(panel)))
        c.icon(IconSet.Identity, 20f, 16f, 24f, col(pink)); text(c, "ID Influenceur", 52f, 36f, 20f, pink)
        c.rect(0f, 56f, 480f, 664f, fill(col(panel)))
        text(c, "Nom de créateur", 30f, 120f, 12f, pink)
        c.rrect(30f, 130f, 420f, 40f, 8f, stroke(col(pink), 2f))
        text(c, u.brandName, 44f, 157f, 17f, 0xFFFFFFFF, FontId.REGULAR)
        text(c, "Niche", 30f, 220f, 12f, pink)
        listOf("Gaming", "Lifestyle", "Anime", "Beauté", "Sport", "Musique").forEachIndexed { i, s ->
            val x = 30f + (i % 3) * 140f; val y = 236f + (i / 3) * 44f
            c.rrect(x, y, 130f, 34f, 17f, fill(col(if (i == u.brandNiche) pink else panel2)))
            text(c, s, x + 65f, y + 22f, 13f, 0xFFFFFFFF, align = Align.CENTER)
        }
        text(c, "Personnalité", 30f, 360f, 12f, pink)
        listOf("Fun", "Doux", "Geek").forEachIndexed { i, s -> c.rrect(30f + i * 140f, 376f, 130f, 34f, 17f, fill(col(if (i != 2) pink else panel2, if (i != 2) 0.6f else 1f))); text(c, s, 95f + i * 140f, 398f, 13f, 0xFFFFFFFF, align = Align.CENTER) }
        button(c, "Créer mon identité", 240f, 520f, 300f, 44f, pink, 0xFFFFFFFF, false)
        val a = u.brandShown
        if (a <= 0f) { text(c, "Ton univers apparaîtra ici", 880f, 380f, 18f, dim, FontId.REGULAR, Align.CENTER); return }
        val pal = listOf(0xFF2B1F3D, 0xFFFF8AC6, 0xFFFFD6E8, 0xFFB45CFF, 0xFFFFE14D, 0xFFFFFFFF)
        pal.forEachIndexed { i, cc -> c.rrect(520f + i * 120f, 90f, 108f * a, 108f, 12f, fill(col(cc, a))) }
        c.circle(600f, 300f, 66f * a, fill(col(0xFFFF8AC6, a)))
        text(c, "SC", 600f, 318f, 46f * a.coerceAtLeast(0.1f), 0xFF2B1F3D, FontId.SCRIPT, Align.CENTER, a)
        text(c, u.brandName.ifEmpty { "Sakura Chan" }, 700f, 290f, 34f, 0xFFFFFFFF, FontId.SCRIPT, alpha = a)
        text(c, "« Douceur, fun et cosplay, chaque jour »", 700f, 330f, 16f, 0xFFFFD6E8, FontId.REGULAR, alpha = a)
        listOf("TikTok : ✿ cosplay & anime tous les jours", "Instagram : univers pastel, coulisses", "YouTube : tutos cosplay & vlogs Japon", "Twitch : lives cosy du soir").forEachIndexed { i, s ->
            text(c, s, 540f, 420f + i * 34f, 15f, 0xFFFFFFFF, FontId.REGULAR, alpha = a)
        }
        button(c, "Utiliser pour mes overlays Twitch", 900f, 640f, 380f, 40f, pink, 0xFFFFFFFF, false)
    }

    companion object {
        /** Seconds between two frames of the player (the films are drawn live). */
        const val FRAME_SEC = 1.0 / 30
        fun pulse(t: Double) = (0.5 + 0.5 * sin(t * 2 * PI)).toFloat()
    }
}
