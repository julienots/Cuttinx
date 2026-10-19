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

    @Test fun keywordAssistantUnderstandsTheStreamer() {
        val base = OverlayConfig(channel = "Luna")
        assertEquals("horror", OverlayAi.propose("live horreur rouge sur phasmophobia", base).first().config.themeId)
        assertEquals("retro8", OverlayAi.propose("minecraft entre potes", base).first().config.themeId)
        assertEquals("cozy", OverlayAi.propose("just chatting cosy", base).first().config.themeId)
        val red = OverlayAi.propose("esport rouge", base).first().config
        assertEquals(0xFFE53935, red.palette!!.primary)
        val snow = OverlayAi.propose("noel avec de la neige", base).first().config
        assertTrue(Motif.SNOW in (snow.motifs ?: emptyList()) || OverlayThemes[snow.themeId].motifs.contains(Motif.SNOW))
        // always 3 different proposals, even for unknown words, and the channel is kept
        val p = OverlayAi.propose("xyzzy", base)
        assertEquals(3, p.size); assertEquals(3, p.map { it.config.themeId }.toSet().size)
        assertTrue(p.all { it.config.channel == "Luna" && it.reasons.isNotEmpty() })
    }

    @Test fun zonesCanBeMovedAndResized() {
        val cfg = OverlayConfig()
        val cam = OverlayLayout.zone(SceneKind.GAMEPLAY, cfg, ZoneKind.CAMERA)!!
        assertEquals(ZoneKind.CAMERA, OverlayLayout.hit(SceneKind.GAMEPLAY, cfg, cam.x + 10, cam.y + 10))
        val moved = OverlayLayout.move(cfg, SceneKind.GAMEPLAY, ZoneKind.CAMERA, -100f, 50f, 1.2f)
        val cam2 = OverlayLayout.zone(SceneKind.GAMEPLAY, moved, ZoneKind.CAMERA)!!
        assertEquals(cam.w * 1.2f, cam2.w, 0.5f)
        assertEquals(cam.x + cam.w / 2 - 100f, cam2.x + cam2.w / 2, 1f)
        // never leaves the frame, and other scenes are untouched
        val far = OverlayLayout.move(cfg, SceneKind.GAMEPLAY, ZoneKind.CAMERA, 5000f, 5000f)
        val cam3 = OverlayLayout.zone(SceneKind.GAMEPLAY, far, ZoneKind.CAMERA)!!
        assertTrue(cam3.right <= 1920.5f && cam3.bottom <= 1080.5f)
        assertEquals(OverlayLayout.zone(SceneKind.SCREEN_ONLY, cfg, ZoneKind.CAMERA), OverlayLayout.zone(SceneKind.SCREEN_ONLY, moved, ZoneKind.CAMERA))
        assertEquals(cam, OverlayLayout.zone(SceneKind.GAMEPLAY, OverlayLayout.reset(moved, SceneKind.GAMEPLAY), ZoneKind.CAMERA))
    }

    @Test fun socialKitIsComplete() {
        val k = BrandGenerator.generate(BrandBrief("Sakura Chan", Niche.ANIME, setOf(Trait.SOFT)))
        assertEquals(7, k.weekPlan.size)
        assertTrue(k.handles.size == 5 && k.handles.all { it.startsWith("@sakurachan") || it.startsWith("@thesakurachan") })
        assertEquals(5, k.highlights.size)
    }
}
