package com.superessence.cuttinx.core

import com.superessence.cuttinx.core.create.*
import org.junit.Assert.*
import org.junit.Test

class CreateKitTest {
    private fun inside(b: Box, w: Int, h: Int) = b.x >= 0 && b.y >= 0 && b.x + b.w <= w && b.y + b.h <= h && b.w > 0 && b.h > 0
    private fun overlap(a: Box, b: Box) = a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h

    @Test fun cropsKeepTheRatio() {
        val c = CreateKit.centerCrop(4000, 3000, PhotoFrame.SQUARE)
        assertEquals(Box(500, 0, 3000, 3000), c)
        val s = CreateKit.centerCrop(1080, 1920, PhotoFrame.PORTRAIT)
        assertEquals(1080, s.w); assertEquals(1350, s.h); assertEquals(285, s.y)
        assertEquals(Box(0, 0, 640, 480), CreateKit.centerCrop(640, 480, PhotoFrame.ORIGINAL))
        assertEquals(1080 to 1350, CreateKit.outputSize(4000, 3000, PhotoFrame.PORTRAIT, 1350))
        assertEquals(2048 to 1536, CreateKit.outputSize(4000, 3000, PhotoFrame.ORIGINAL, 2048))
    }

    @Test fun collagesFillTheCanvasWithoutOverlap() {
        for (style in CollageStyle.entries) for (n in 1..9) {
            val cells = CreateKit.collage(n, 2160, 2700, 24, style)
            assertEquals("$style $n", n, cells.size)
            assertTrue("$style $n inside", cells.all { inside(it, 2160, 2700) })
            for (i in cells.indices) for (j in i + 1 until cells.size) assertFalse("$style $n overlap $i $j", overlap(cells[i], cells[j]))
        }
        assertEquals(listOf(2, 3), CreateKit.splitRows(5, 3))
        assertEquals(listOf(3, 3, 3), CreateKit.splitRows(9, 3))
        val hero = CreateKit.collage(4, 1000, 1000, 0, CollageStyle.HERO)
        assertTrue(hero[0].w * hero[0].h > hero.drop(1).maxOf { it.w * it.h })
    }

    @Test fun carouselSlicesJoinSeamlessly() {
        val s = CreateKit.carousel(6000, 1500, 3, PhotoFrame.PORTRAIT)
        assertEquals(3, s.size)
        assertTrue(s.zipWithNext().all { (a, b) -> a.x + a.w == b.x && a.y == b.y && a.h == b.h })
        assertTrue(s.all { kotlin.math.abs(it.w.toDouble() / it.h - 0.8) < 0.01 })
        assertTrue(s.all { inside(it, 6000, 1500) })
    }

    @Test fun audioHelpers() {
        val sr = 1000
        val buf = FloatArray(2 * sr * 2) { 0.5f }     // 2 s stereo
        val w = CreateKit.waveform(buf, 2, 10)
        assertEquals(10, w.size); assertTrue(w.all { it == 0.5f })
        val t = CreateKit.trim(buf, sr, 2, 0.5, 1.5)
        assertEquals(sr * 2, t.size)
        CreateKit.fades(t, sr, 2, 0.1, 0.1)
        assertEquals(0f, t[0]); assertEquals(0.5f, t[t.size / 2]); assertTrue(t.last() < 0.01f)
        assertEquals("3.3", Releases.current.version)
    }
}

class PhotoKitTest {
    @Test fun effectChainAndAuto() {
        assertTrue(PhotoKit.effects(null, PhotoAdjust()).isEmpty())
        val look = com.superessence.cuttinx.core.effects.Presets.builtIn.first { it.name == "Heure dorée" }
        val chain = PhotoKit.effects(look, PhotoAdjust(contrast = 0.2, vignette = 0.3, skin = 0.4))
        val ids = chain.map { it.effectId }
        assertEquals(look.effects.map { it.effectId }, ids.take(look.effects.size))
        assertTrue(ids.containsAll(listOf("skin_smooth", "adjust", "vignette")))
        assertEquals(0.2, chain.last { it.effectId == "adjust" }.params["contrast"]!!, 1e-9)
        val weak = PhotoKit.effects(look, PhotoAdjust(), 0.5)
        assertTrue(weak.zip(look.effects).all { (a, b) -> (a.params["amount"] ?: 0.0) <= (b.params["amount"] ?: 0.0) })
        val dark = com.superessence.cuttinx.core.smart.Magic.Look(0.12, 0.1, 0.2, 0.15)
        val a = PhotoKit.auto(dark)
        assertTrue(a.exposure > 0.3); assertTrue(a.temperature > 0); assertTrue(a.shadows > 0)
        val bright = com.superessence.cuttinx.core.smart.Magic.Look(0.85, 0.82, 0.8, 0.2)
        assertTrue(PhotoKit.auto(bright).exposure < 0)
    }
}
