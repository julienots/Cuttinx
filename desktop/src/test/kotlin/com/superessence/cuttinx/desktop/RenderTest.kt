package com.superessence.cuttinx.desktop

import com.superessence.cuttinx.core.brand.*
import com.superessence.cuttinx.core.overlay.*
import com.superessence.cuttinx.core.overlay.export.ObsExporter
import com.superessence.cuttinx.core.overlay.export.PackOptions
import com.superessence.cuttinx.core.overlay.render.BrandBoard
import com.superessence.cuttinx.core.overlay.render.OverlayRenderer
import com.superessence.cuttinx.desktop.render.SkiaRaster
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

class RenderTest {
    private val r = OverlayRenderer()
    private val raster = SkiaRaster()
    private val ex = ObsExporter(raster, r)
    private val small = OverlayConfig(channel = "Testeur 配信 ♡", width = 480, height = 270)

    @Test fun everyThemeSceneAndStingerRenders() {
        val s = raster.frame(small.width, small.height).gfx
        for (t in OverlayThemes.all) {
            val cfg = small.copy(themeId = t.id)
            for (sc in SceneKind.entries) for (time in listOf(0.0, 1.7)) for (layer in OverlayRenderer.Layer.entries) { s.clear(0); r.drawScene(s, t, cfg, sc, time, layer) }
            for (st in StingerStyle.entries) for (time in listOf(0.2, OverlayAnim.STINGER_COVER_SEC + 0.02, 1.0)) { s.clear(0); r.drawStinger(s, t, cfg, st, time) }
        }
    }

    @Test fun stingersCoverTheWholeScreenAtTheTransitionPoint() {
        for (st in StingerStyle.entries) {
            val png = ex.renderPng(small) { c -> r.drawStinger(c, OverlayThemes["armor"], small, st, OverlayAnim.STINGER_COVER_SEC + 0.03) }
            val img = javax.imageio.ImageIO.read(png.inputStream())
            var transparent = 0
            for (y in 0 until img.height step 9) for (x in 0 until img.width step 9) if ((img.getRGB(x, y) ushr 24) < 250) transparent++
            assertEquals("$st leaves holes when OBS switches scenes", 0, transparent)
        }
    }

    @Test fun layoutsKeepZonesTransparent() {
        val cfg = small.copy(themeId = "cyber")
        val png = ex.renderPng(cfg) { c -> r.drawScene(c, OverlayThemes["cyber"], cfg, SceneKind.GAMEPLAY, 0.0) }
        val img = javax.imageio.ImageIO.read(png.inputStream())
        val screen = OverlayLayout.zone(SceneKind.GAMEPLAY, cfg, ZoneKind.SCREEN)!!
        assertEquals(0, img.getRGB((screen.x + screen.w / 2).toInt(), (screen.y + screen.h / 2).toInt()) ushr 24)
    }

    @Test fun apngIsValidAndLoops() {
        val f = File.createTempFile("stinger", ".png")
        ex.renderApng(small, f, OverlayAnim.STINGER_SEC, 10) { c, t -> r.drawStinger(c, OverlayThemes["kawaii"], small, StingerStyle.HEARTS_RAIN, t) }
        val b = f.readBytes()
        assertEquals(0x89.toByte(), b[0]); assertEquals('P'.code.toByte(), b[1])
        val s = String(b, Charsets.ISO_8859_1)
        val ac = s.indexOf("acTL")
        assertTrue(ac > 0 && ac < s.indexOf("IDAT"))
        assertEquals(12, ByteBuffer.wrap(b, ac + 4, 4).int) // 1.2 s at 10 fps
        assertEquals(0, ByteBuffer.wrap(b, ac + 8, 4).int)  // loops forever
        assertEquals(12, Regex("fcTL").findAll(s).count())
        assertTrue(s.endsWith("IEND" + String(b.copyOfRange(b.size - 4, b.size), Charsets.ISO_8859_1)))
        // the first frame is a regular PNG for viewers without APNG support
        assertNotNull(javax.imageio.ImageIO.read(f))
        f.delete()
    }

    @Test fun packContainsEverythingObsNeeds() {
        val dir = kotlin.io.path.createTempDirectory("pack").toFile()
        val out = ex.exportPack(OverlayThemes["japan"], small.copy(channel = "Luna"), dir, PackOptions(stingers = setOf(StingerStyle.SPIDER_WEB), fps = 4))
        val files = out.walkTopDown().filter { it.isFile }.map { it.relativeTo(out).path.replace('\\', '/') }.toSet()
        assertTrue("LISEZ-MOI.txt" in files)
        assertTrue(files.any { it.startsWith("scenes/") && it.endsWith("scene.html") })
        assertTrue(files.count { it.startsWith("cadres/") } == 5)
        assertTrue("transitions/toile-d-araignee.png" in files && "transitions/tempete-de-sakura.png" in files)
        assertTrue("alertes/nouveau-follow.png" in files)
        assertTrue(files.any { it.startsWith("polices/") })
        val html = File(out, files.first { it.endsWith("scene.html") && "commencer" in it }).readText()
        assertTrue("setInterval" in html && "fond.png" in html && "animation.png" in html)
        dir.deleteRecursively()
    }

    @Test fun brandBoardRenders() {
        val kit = BrandGenerator.generate(BrandBrief("Sakura Chan", Niche.ANIME, setOf(Trait.SOFT, Trait.FUN)))
        BrandBoard().draw(raster.frame(1600, 1000).gfx, kit)
        assertEquals("japan", kit.overlayThemeId)
    }
}
