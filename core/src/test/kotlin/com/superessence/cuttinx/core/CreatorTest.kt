package com.superessence.cuttinx.core

import com.superessence.cuttinx.core.brand.BrandBrief
import com.superessence.cuttinx.core.brand.BrandGenerator
import com.superessence.cuttinx.core.brand.Niche
import com.superessence.cuttinx.core.creator.*
import com.superessence.cuttinx.core.overlay.OverlayItems
import com.superessence.cuttinx.core.overlay.SceneKind
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class CreatorTest {
    @Test fun everyTemplateFitsEveryFormat() {
        val kit = BrandGenerator.generate(BrandBrief("Luna", Niche.GAMING, emptySet()))
        for (f in SocialFormat.entries) {
            f.safe?.let { s -> assertTrue("$f", s[0] >= 0f && s[1] >= 0f && s[0] + s[2] <= 1.001f && s[1] + s[3] <= 1.001f) }
            for (t in CreatorTemplates.forFormat(f)) for (k in listOf(null, kit)) {
                val d = t.build(f, k)
                assertEquals(f, d.format)
                // every element starts on the canvas, and the design opens in the element editor at its own size
                for (it in d.items) assertTrue("$f ${t.name} ${it.text}", it.x in 0f..1f && it.y in 0f..1f)
                val cfg = d.asConfig()
                assertEquals(f.width, cfg.width); assertEquals(f.height, cfg.height)
                assertEquals(d.items, d.withConfig(cfg).items)
                // the editor sees every element whatever the scene used for editing
                assertEquals(d.items.size, cfg.itemsOf(SceneKind.STARTING).size)
            }
        }
    }

    @Test fun storeKeepsDesigns() {
        val dir = Files.createTempDirectory("cx-creator").toFile()
        try {
            val s = CreatorStore(dir)
            val pic = java.util.Base64.getEncoder().encodeToString(ByteArray(5000) { it.toByte() })
            var d = CreatorTemplates.forFormat(SocialFormat.YT_THUMB)[1].build(SocialFormat.YT_THUMB, null)
            d = d.copy(bg = d.bg.copy(kind = BgKind.IMAGE, image = pic, imageAspect = 0.5f))
            d = d.withConfig(OverlayItems.add(d.asConfig(), OverlayItems.image(d.asConfig(), null, pic, 100, 50, "photo")))
            s.save(d)
            val back = CreatorStore(dir).list().single()
            assertEquals(d, back)
            s.delete(d.id)
            assertTrue(CreatorStore(dir).list().isEmpty())
        } finally { dir.deleteRecursively() }
    }
}
