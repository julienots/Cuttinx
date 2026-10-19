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
        // every layout (game, chatting, duo, full camera, vertical…) without the animated widgets
        assertEquals(9, files.count { it.startsWith("cadres/") })
        assertTrue(files.any { it.startsWith("widgets/") })
        assertTrue(files.any { it.startsWith("twitch-profil/badges-abonnes/") } && files.any { "emotes/" in it } && files.any { "points-de-chaine" in it })
        assertTrue("transitions/toile-d-araignee.png" in files && "transitions/tempete-de-sakura.png" in files)
        for (a in listOf("nouveau-follow", "nouvel-abonne", "raid", "don")) assertTrue(a, "alertes/$a.png" in files)
        assertEquals(6, files.count { it.startsWith("twitch-profil/panneaux/") })
        assertTrue("twitch-profil/banniere-profil.png" in files && "twitch-profil/banniere-hors-ligne.png" in files)
        assertEquals(1200, javax.imageio.ImageIO.read(File(out, "twitch-profil/banniere-profil.png")).width)
        assertTrue(files.any { it.startsWith("polices/") })
        val html = File(out, files.first { it.endsWith("scene.html") && "commencer" in it }).readText()
        assertTrue("setInterval" in html && "fond.png" in html && "animation.png" in html)
        dir.deleteRecursively()
    }

    @Test fun customLogoIsDrawnOnScenesAndProfile() {
        // a 64×64 opaque red logo
        val sf = org.jetbrains.skia.Surface.makeRasterN32Premul(64, 64); sf.canvas.clear(0xFFFF0000.toInt())
        val png = sf.makeImageSnapshot().encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes
        val cfg = small.copy(logo = java.util.Base64.getEncoder().encodeToString(png))
        val t = OverlayThemes["armor"]
        for (sc in listOf(SceneKind.STARTING, SceneKind.GAMEPLAY, SceneKind.DUO, SceneKind.FULLCAM, SceneKind.QNA)) {
            val z = OverlayLayout.zone(sc, cfg, ZoneKind.LOGO)!!
            val fr = raster.frame(cfg.width, cfg.height); fr.gfx.clear(0); r.drawScene(fr.gfx, t, cfg, sc, 1.0)
            val img = org.jetbrains.skia.Image.makeFromEncoded(fr.png())
            val bmp = org.jetbrains.skia.Bitmap.makeFromImage(img)
            val px = bmp.getColor((z.x + z.w / 2).toInt(), (z.y + z.h / 2).toInt())
            assertEquals(sc.name, 0xFFFF0000.toInt(), px)
        }
        val fr = raster.frame(200, 200); fr.gfx.clear(0); r.drawAvatar(fr.gfx, t, cfg, 200f)
        assertEquals(0xFFFF0000.toInt(), org.jetbrains.skia.Bitmap.makeFromImage(org.jetbrains.skia.Image.makeFromEncoded(fr.png())).getColor(100, 100))
        // no logo: no zone
        assertNull(OverlayLayout.zone(SceneKind.STARTING, small, ZoneKind.LOGO))
    }

    @Test fun everyStyleChoiceRenders() {
        val s = raster.frame(small.width, small.height).gfx
        val t = OverlayThemes["armor"]
        for (f in FrameStyle.entries) for (shape in CameraShape.entries) {
            val cfg = small.copy(frame = f, cameraShape = shape, font = TitleFont.entries[f.ordinal % TitleFont.entries.size], motifs = listOf(Motif.entries[f.ordinal]), badge = "EN DIRECT", textScale = 1.3f)
            for (sc in listOf(SceneKind.GAMEPLAY, SceneKind.STARTING, SceneKind.GOAL, SceneKind.ALERT_RAID)) { s.clear(0); r.drawScene(s, t, cfg, sc, 1.0) }
            s.clear(0); r.drawPanel(s, t, cfg, "PLANNING"); r.drawBanner(s, t, cfg, 480f, 192f)
        }
        // the style overrides really change the picture
        val a = ex.renderPng(small) { c -> r.drawScene(c, t, small, SceneKind.STARTING, 0.0) }
        val b = ex.renderPng(small) { c -> r.drawScene(c, t, small.copy(frame = FrameStyle.COMIC), SceneKind.STARTING, 0.0) }
        assertFalse(a.contentEquals(b))
    }

    @Test fun brandBoardRenders() {
        val kit = BrandGenerator.generate(BrandBrief("Sakura Chan", Niche.ANIME, setOf(Trait.SOFT, Trait.FUN)))
        BrandBoard().draw(raster.frame(1600, 1000).gfx, kit)
        assertEquals("japan", kit.overlayThemeId)
    }
}
