package com.superessence.cuttinx.core

import com.superessence.cuttinx.core.brand.*
import com.superessence.cuttinx.core.overlay.*
import org.junit.Assert.*
import org.junit.Test

class OverlayBrandTest {
    @Test fun themesAreCompleteAndUnique() {
        val ids = OverlayThemes.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(OverlayThemes.all.size >= 30)
        for (c in ThemeCategory.entries) assertTrue("$c", OverlayThemes.byCategory(c).isNotEmpty())
        for (t in OverlayThemes.all) {
            assertTrue(t.motifs.isNotEmpty())
            // title text must be readable on the theme background
            assertTrue("${t.id} contrast", BrandGenerator.contrast(t.palette.text, t.palette.background) >= 3.0)
        }
    }

    @Test fun layoutsStayInsideTheFrame() {
        for (cfg in listOf(OverlayConfig(), OverlayConfig(cameraRight = false, width = 1280, height = 720))) for (s in SceneKind.entries) {
            val zones = OverlayLayout.zones(s, cfg)
            assertTrue("$s", zones.isNotEmpty())
            for (z in zones) assertTrue("$s $z", z.x >= 0 && z.y >= 0 && z.right <= cfg.width + 0.5f && z.bottom <= cfg.height + 0.5f)
        }
        val g = OverlayConfig()
        assertNotNull(OverlayLayout.zone(SceneKind.GAMEPLAY, g, ZoneKind.CHAT))
        val camR = OverlayLayout.zone(SceneKind.GAMEPLAY, g, ZoneKind.CAMERA)!!
        val camL = OverlayLayout.zone(SceneKind.GAMEPLAY, g.copy(cameraRight = false), ZoneKind.CAMERA)!!
        assertTrue(camR.x > 960 && camL.x < 960)
    }

    @Test fun animationsLoopSeamlessly() {
        for (m in Motif.entries) {
            val a = OverlayAnim.particles(m, 0.0, 1920, 1080)
            val b = OverlayAnim.particles(m, OverlayAnim.LOOP_SEC, 1920, 1080)
            assertEquals(a.size, b.size)
            for ((p, q) in a.zip(b)) { assertEquals(p.x, q.x, 0.5f); assertEquals(p.y, q.y, 0.5f); assertEquals(p.alpha, q.alpha, 0.01f) }
        }
        assertEquals(0f, OverlayAnim.stingerCover(0.0), 1e-6f)
        assertEquals(1f, OverlayAnim.stingerCover(OverlayAnim.STINGER_COVER_SEC + 0.01), 1e-6f)
        assertEquals(0f, OverlayAnim.stingerCover(OverlayAnim.STINGER_SEC), 1e-6f)
    }

    @Test fun brandKitIsDeterministicAndComplete() {
        val brief = BrandBrief("Luna Gaming", Niche.GAMING, setOf(Trait.FUN, Trait.GEEK), keywords = "fortnite, minecraft")
        val k1 = BrandGenerator.generate(brief)
        val k2 = BrandGenerator.generate(brief)
        assertEquals(k1, k2)
        assertTrue("regenerating gives another proposal", (1..5).any { BrandGenerator.generate(brief, seed = it) != k1 })
        assertEquals(6, k1.palette.size)
        assertEquals("LG", k1.monogram)
        assertTrue(k1.hashtags.first() == "#LunaGaming" && "#fortnite" in k1.hashtags)
        assertEquals(setOf("TikTok", "Instagram", "YouTube", "Twitch"), k1.bios.keys)
        assertTrue(k1.pillars.size == 4 && k1.taglines.size == 4)
        // palette text must stay readable on its background, for every niche/trait combination
        for (n in Niche.entries) for (t in Trait.entries) {
            val k = BrandGenerator.generate(BrandBrief("Test", n, setOf(t)))
            val c = k.palette.associateBy { it.role }
            assertTrue("$n $t", BrandGenerator.contrast(c["Texte"]!!.argb, c["Fond"]!!.argb) >= 7.0)
            assertTrue(k.palette.all { (it.argb ushr 24) == 0xFFL })
            k.overlayPalette()
        }
    }
}
