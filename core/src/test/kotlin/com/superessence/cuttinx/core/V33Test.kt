package com.superessence.cuttinx.core

import com.superessence.cuttinx.core.create.CaptionTone
import com.superessence.cuttinx.core.create.Captions
import com.superessence.cuttinx.core.create.CollageStyle
import com.superessence.cuttinx.core.create.CreateKit
import com.superessence.cuttinx.core.create.Network
import com.superessence.cuttinx.core.effects.EffectRegistry
import com.superessence.cuttinx.core.effects.Presets
import com.superessence.cuttinx.core.effects.TransitionRegistry
import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.overlay.OverlayThemes
import com.superessence.cuttinx.core.resources.Synth
import com.superessence.cuttinx.core.smart.*
import com.superessence.cuttinx.core.timeline.TimelineEditor
import org.junit.Assert.*
import org.junit.Test

class V33Test {
    private fun three(): Project {
        var p = T.project()
        p = TimelineEditor.appendAsset(p, T.video("a", 10.0)).first
        p = TimelineEditor.appendAsset(p, T.video("b", 6.0)).first
        p = TimelineEditor.appendAsset(p, T.video("c", 8.0)).first
        return p
    }
    private fun main(p: Project) = SmartTools.mainTrack(p)!!.sortedClips()

    @Test fun rhythmKeepsTheCentreOfLongShots() {
        val p = three()
        val q = MontageKit.rhythm(p, 2_000_000)
        val m = main(q)
        assertEquals(3, m.size)
        assertTrue(m.all { it.durationUs in 1_990_000..2_010_000 })
        assertEquals(4_000_000, m[0].sourceInUs)        // 10 s shot: the centre 4..6 s is kept
        assertEquals(0, m[1].startUs - m[0].endUs)      // magnetic main track: no gap
    }

    @Test fun velocityAndSlowMotionEnding() {
        val p = three()
        val v = MontageKit.velocity(p)
        assertTrue(main(v).all { it.speedCurve == MontageKit.VELOCITY })
        val s = MontageKit.slowMotionEnding(p)
        assertEquals(0.5, main(s).last().speed, 1e-9)
        assertEquals(16_000_000, main(s).last().durationUs)
        assertTrue(main(s).last().audio.fadeOutUs >= 1_200_000)
    }

    @Test fun stutterRepeatsTheOpening() {
        val p = three()
        val q = MontageKit.stutter(p)
        val m = main(q)
        assertEquals(5, m.size)
        assertEquals(2, m.count { it.name == "Saccade" })
        assertEquals(0L, m[0].startUs)
        assertEquals(p.durationUs + 400_000, q.durationUs)
        assertTrue(m[0].transform.scaleX > m[1].transform.scaleX)      // the zoom grows towards the real shot
    }

    @Test fun cinemaModeIsReplacedNotStacked() {
        val p = three()
        val q = MontageKit.cinemaMode(MontageKit.cinemaMode(p))
        val layers = q.tracks.filter { it.kind == TrackKind.EFFECT && it.name == MontageKit.CINEMA_TRACK }
        assertEquals(1, layers.size)
        assertEquals(1, layers[0].clips.size)
        assertEquals(p.durationUs, layers[0].clips[0].durationUs)
        assertTrue(layers[0].clips[0].effects.any { it.effectId == "letterbox" })
    }

    @Test fun everyGestureRunsAndStaysUndoableSized() {
        val p = three()
        for ((id, _) in MontageKit.gestures) {
            val q = Magic.instant(id, p)
            assertNotNull(id, MontageKit.run(id, p))
            if (id != "shorts59") assertTrue("$id changed nothing", q != p)      // an already short vertical film stays as it is
        }
        val short = MontageKit.underOneMinute(three().let { var x = it; repeat(4) { x = TimelineEditor.appendAsset(x, T.video("l$it", 20.0)).first }; x })
        assertTrue(short.durationUs <= 59_100_000)
        assertTrue(short.format.width < short.format.height)
        // nothing to do on an empty project
        assertEquals(T.project(), MontageKit.rhythm(T.project()))
    }

    @Test fun glitchCutsAndSlideshow() {
        val g = MontageKit.glitchCuts(three())
        assertEquals(listOf("glitch", "glitch_slide"), main(g).drop(1).map { it.transitionIn!!.transitionId })
        var p = T.project()
        repeat(3) { p = TimelineEditor.appendAsset(p, T.image("i$it")).first }
        val s = MontageKit.slideshow(p)
        assertTrue(main(s).all { it.durationUs == 2_500_000L })
        assertTrue(main(s).drop(1).all { it.transitionIn?.transitionId == "fade" })
    }

    @Test fun newEffectsTransitionsAndFiltersAreRegistered() {
        val fx = listOf("light_sweep", "club_lights", "pop_art", "rain_window", "film_dust", "lens_distortion", "ascii_blocks", "xray", "focus_blur",
            "star_filter", "color_cycle", "gold_dust", "confetti", "bubbles", "instant_frame", "neon_frame")
        assertTrue(fx.all { EffectRegistry[it] != null })
        val tr = listOf("ink", "burn", "spiral", "zigzag", "slit", "hexagons", "diagonal_bars", "dots", "luma_fade", "stripes", "rgb_glitch_cut",
            "vortex", "elastic_slide", "punch_zoom", "fall_away", "film_roll")
        assertTrue(tr.all { TransitionRegistry[it] != null })
        // B fully visible and untouched at the end of every new transition
        for (id in tr) for (top in listOf(true, false)) {
            val (a, b) = TransitionRegistry.evaluate(id, 1.0, top)
            if (b.shaderId == null) assertEquals("$id alpha", 1.0, b.alpha, 1e-6)
            assertEquals("$id scale", 1.0, b.scale, 1e-6)
            assertEquals("$id dx", 0.0, b.dx, 1e-6); assertEquals("$id dy", 0.0, b.dy, 1e-6)
            val (a0, b0) = TransitionRegistry.evaluate(id, 0.0, top)
            if (a0.shaderId == null) assertEquals("$id A at start", 1.0, a0.alpha, 1e-6)
            assertNotNull(a); assertNotNull(b0)
        }
        assertTrue(Presets.CATEGORIES.contains("Tendances"))
        assertTrue(Presets.builtIn.count { it.category == "Tendances" } >= 10)
        assertTrue(Presets.builtIn.all { pr -> pr.effects.all { EffectRegistry[it.effectId] != null } })
        assertEquals(Presets.builtIn.size, Presets.builtIn.map { it.id }.distinct().size)
        // new shaders are complete bodies
        for (id in fx + listOf("_tr_ink", "_tr_burn", "_tr_spiral", "_tr_zigzag", "_tr_slit", "_tr_hex", "_tr_diag", "_tr_dots", "_tr_luma", "_tr_stripes", "_tr_rgbsplit")) {
            val d = EffectRegistry[id]!!
            assertTrue(id, d.glsl.contains("return") && d.params.size <= 16)
        }
    }

    @Test fun newTextLooksTemplatesAndStudios() {
        val p = three()
        for (t in listOf(Template.UNBOXING, Template.FITNESS, Template.MOTIVATION, Template.MEME, Template.FASHION, Template.PARTY, Template.REAL_ESTATE, Template.ASMR)) {
            assertTrue("$t", Templates.apply(p, t) != p)
        }
        val withTitle = Titles.opening(p, "TEST", TextLook.CHROME)
        for (lk in listOf(TextLook.CHROME, TextLook.STICKER, TextLook.GAMER, TextLook.POSTER, TextLook.SIGNATURE, TextLook.MARKER, TextLook.DINER)) {
            val q = Titles.applyLook(withTitle, emptyList(), lk)
            assertEquals(Titles.style(lk).fontFamily, q.allClips.first { it.text != null }.text!!.style.fontFamily)
        }
        // collage: every new layout fills the canvas without overlap
        for (st in listOf(CollageStyle.COLUMNS, CollageStyle.PYRAMID, CollageStyle.SIDE_HERO)) for (n in 1..7) {
            val boxes = CreateKit.collage(n, 1080, 1350, 10, st)
            assertEquals("$st $n", n, boxes.size)
            assertTrue(boxes.all { it.x >= 0 && it.y >= 0 && it.x + it.w <= 1080 && it.y + it.h <= 1350 && it.w > 0 && it.h > 0 })
            for (i in boxes.indices) for (j in i + 1 until boxes.size) {
                val a = boxes[i]; val b = boxes[j]
                assertFalse("$st $n overlap", a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h)
            }
        }
        val cols = CreateKit.collage(4, 1000, 1000, 0, CollageStyle.COLUMNS)
        assertTrue(cols.map { it.x }.distinct().size == 2)
        for (tone in listOf(CaptionTone.STORYTIME, CaptionTone.PROMO, CaptionTone.EDUCATIVE)) {
            val c = Captions.generate("ma routine du matin", Network.INSTAGRAM, tone)
            assertEquals(3, c.size)
            assertTrue(c.all { it.text.contains("routine") && it.text.length <= Network.INSTAGRAM.captionLimit })
        }
        val ids = listOf("sparkle", "pop_rise", "like", "alarm", "knock", "camera_zoom", "power_up", "game_over", "cartoon_run", "sad_trombone", "crickets", "heartbeat_fast", "deep_whoosh", "glitch_stutter")
        assertTrue(ids.all { id -> Synth.sfxList.any { it.id == id } })
        assertEquals(Synth.sfxList.size, Synth.sfxList.map { it.id }.distinct().size)
        assertTrue(OverlayThemes.all.size >= 46)
        assertEquals(OverlayThemes.all.size, OverlayThemes.all.map { it.id }.distinct().size)
        assertTrue(QuickActions.all.any { it.id == "m33_velocity" })
        assertEquals(QuickActions.all.size, QuickActions.all.map { it.id }.distinct().size)
    }
}
