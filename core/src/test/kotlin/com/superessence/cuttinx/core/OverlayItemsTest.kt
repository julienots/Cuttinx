package com.superessence.cuttinx.core

import com.superessence.cuttinx.core.gfx.FontId
import com.superessence.cuttinx.core.gfx.Gfx
import com.superessence.cuttinx.core.gfx.Paint
import com.superessence.cuttinx.core.gfx.Path
import com.superessence.cuttinx.core.overlay.*
import com.superessence.cuttinx.core.overlay.render.OverlayRenderer
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class OverlayItemsTest {
    /** Records the pictures drawn (one entry per [image] call). */
    private class Recorder : Gfx {
        val images = ArrayList<ByteArray>()
        override fun save() {}
        override fun restore() {}
        override fun translate(dx: Float, dy: Float) {}
        override fun rotate(degrees: Float) {}
        override fun scale(sx: Float, sy: Float) {}
        override fun clipRect(x: Float, y: Float, w: Float, h: Float) {}
        override fun clipRRect(x: Float, y: Float, w: Float, h: Float, r: Float) {}
        override fun clipPath(path: Path) {}
        override fun clear(color: Int) {}
        override fun rect(x: Float, y: Float, w: Float, h: Float, p: Paint) {}
        override fun rrect(x: Float, y: Float, w: Float, h: Float, r: Float, p: Paint) {}
        override fun circle(cx: Float, cy: Float, r: Float, p: Paint) {}
        override fun line(x0: Float, y0: Float, x1: Float, y1: Float, p: Paint) {}
        override fun path(path: Path, p: Paint) {}
        override fun arc(left: Float, top: Float, right: Float, bottom: Float, startDeg: Float, sweepDeg: Float, p: Paint) {}
        override fun text(text: String, x: Float, y: Float, font: FontId, size: Float, p: Paint) {}
        override fun textWidth(text: String, font: FontId, size: Float) = text.length * size * 0.5f
        override fun metrics(font: FontId, size: Float) = -size * 0.8f to size * 0.2f
        override fun image(data: ByteArray, x: Float, y: Float, w: Float, h: Float, alpha: Float) { images += data }
    }

    private val pic = java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4))

    private fun drawn(cfg: OverlayConfig, scene: SceneKind, layer: OverlayRenderer.Layer = OverlayRenderer.Layer.ALL, t: Double = 0.0): Int {
        val g = Recorder()
        OverlayRenderer().drawScene(g, OverlayThemes[cfg.themeId], cfg, scene, t, layer)
        return g.images.size
    }

    @Test fun picturesStayOnTheirOwnScene() {
        val base = OverlayConfig()
        val img = OverlayItems.image(base, SceneKind.BRB, pic, 400, 200, "photo")
        val cfg = OverlayItems.add(base, img)
        assertEquals(1, drawn(cfg, SceneKind.BRB))
        // the other scenes do not show it (the bug: pictures came back on every scene)
        for (s in SceneKind.entries.filter { it != SceneKind.BRB }) assertEquals("$s", 0, drawn(cfg, s))
        // "every scene" is an explicit choice
        val all = cfg.updateItem(img.id) { it.copy(scene = null) }
        for (s in listOf(SceneKind.STARTING, SceneKind.GAMEPLAY, SceneKind.ALERT, SceneKind.TICKER)) assertEquals("$s", 1, drawn(all, s))
        // hidden / deleted pictures are not drawn
        assertEquals(0, drawn(cfg.updateItem(img.id) { it.copy(hidden = true) }, SceneKind.BRB))
        assertEquals(0, drawn(OverlayItems.remove(cfg, img.id), SceneKind.BRB))
    }

    @Test fun manyPicturesAndLayersSplitForObs() {
        var cfg = OverlayConfig()
        repeat(25) { i -> cfg = OverlayItems.add(cfg, OverlayItems.image(cfg, SceneKind.STARTING, pic, 100, 100, "img$i").let { if (i % 5 == 0) it.copy(anim = ItemAnim.FLOAT) else it }) }
        assertEquals(25, drawn(cfg, SceneKind.STARTING))
        // HTML export: still pictures in the background, animated ones in the animation layer
        assertEquals(20, drawn(cfg, SceneKind.STARTING, OverlayRenderer.Layer.STATIC))
        assertEquals(5, drawn(cfg, SceneKind.STARTING, OverlayRenderer.Layer.MOVING, 1.0))
        // the logo can be hidden on one scene only
        val logo = cfg.copy(items = emptyList(), logo = pic)
        assertEquals(1, drawn(logo, SceneKind.STARTING))
        val hidden = logo.copy(hiddenZones = setOf(OverlayLayout.key(SceneKind.STARTING, ZoneKind.LOGO)))
        assertEquals(0, drawn(hidden, SceneKind.STARTING))
        assertEquals(1, drawn(hidden, SceneKind.BRB))
    }

    @Test fun hitTestFollowsRotationAndOrder() {
        val cfg0 = OverlayConfig()
        val a = OverlayItem("a", x = 0.5f, y = 0.5f, w = 0.2f, aspect = 0.25f, scene = SceneKind.STARTING)
        val b = a.copy(id = "b", w = 0.05f, aspect = 1f)
        val cfg = cfg0.copy(items = listOf(a, b))
        // the topmost element wins
        assertEquals("b", OverlayItems.hit(cfg, SceneKind.STARTING, 960f, 540f, 1920f, 1080f)?.id)
        assertEquals("a", OverlayItems.hit(cfg, SceneKind.STARTING, 960f + 150f, 540f, 1920f, 1080f)?.id)
        // rotated by 90°, the wide box becomes tall
        val r = cfg.copy(items = listOf(a.copy(rotation = 90f)))
        assertNull(OverlayItems.hit(r, SceneKind.STARTING, 960f + 150f, 540f, 1920f, 1080f))
        assertEquals("a", OverlayItems.hit(r, SceneKind.STARTING, 960f, 540f + 150f, 1920f, 1080f)?.id)
        // locked elements cannot be grabbed
        assertNull(OverlayItems.hit(cfg.copy(items = listOf(a.copy(locked = true))), SceneKind.STARTING, 960f, 540f, 1920f, 1080f))
        // other scenes: nothing to grab
        assertNull(OverlayItems.hit(cfg, SceneKind.BRB, 960f, 540f, 1920f, 1080f))
    }

    @Test fun alignAndOrder() {
        val it = OverlayItem("a", x = 0.3f, y = 0.3f, w = 0.2f, aspect = 0.5f)
        val left = OverlayItems.align(it, 1920f, 1080f, -1, null)
        assertEquals(0.1f, left.x, 1e-4f)
        val bottom = OverlayItems.align(it, 1920f, 1080f, null, 1)
        assertEquals(1f - 0.2f * 1920f * 0.5f / 2f / 1080f, bottom.y, 1e-4f)
        val cfg = OverlayConfig(items = listOf(it, it.copy(id = "b"), it.copy(id = "c")))
        assertEquals(listOf("b", "c", "a"), OverlayItems.reorder(cfg, "a", Int.MAX_VALUE).items.map { x -> x.id })
        assertEquals(listOf("c", "a", "b"), OverlayItems.reorder(cfg, "c", Int.MIN_VALUE).items.map { x -> x.id })
        assertEquals(listOf("b", "a", "c"), OverlayItems.reorder(cfg, "a", 1).items.map { x -> x.id })
    }

    @Test fun animationsAreStillAtZeroAndLoop() {
        for (a in ItemAnim.entries) {
            val it = OverlayItem("a", anim = a, animSpeed = 2)
            assertEquals("$a", OverlayItems.Pose(), OverlayItems.pose(it, 0.0, 100f))
            val p0 = OverlayItems.pose(it, 0.37, 100f); val p1 = OverlayItems.pose(it, 0.37 + OverlayAnim.LOOP_SEC, 100f)
            assertEquals("$a", p0.dx, p1.dx, 0.01f); assertEquals("$a", p0.dy, p1.dy, 0.01f); assertEquals("$a", p0.alpha, p1.alpha, 0.01f)
            assertEquals("$a", p0.rotation % 360f, p1.rotation % 360f, 0.05f)
        }
    }

    @Test fun storeKeepsEverythingAndStaysSmall() {
        val dir = Files.createTempDirectory("cx-store").toFile()
        try {
            val big = java.util.Base64.getEncoder().encodeToString(ByteArray(300_000) { (it % 251).toByte() })
            var cfg = OverlayConfig(channel = "Luna", logo = pic)
            repeat(6) { i -> cfg = OverlayItems.add(cfg, OverlayItems.image(cfg, SceneKind.entries[i], big + i, 640, 360, "photo $i")) }
            cfg = OverlayItems.add(cfg, OverlayItems.text(cfg, SceneKind.BRB, "BIENTÔT"))
            val store = OverlayStore(dir)
            store.save(cfg)
            assertEquals(cfg, OverlayStore(dir).load())
            // the settings file only references the pictures
            assertTrue(java.io.File(dir, "overlay.json").length() < 20_000)
            // deleted pictures leave the disk
            val fewer = OverlayItems.remove(cfg, cfg.items.first().id)
            store.save(fewer)
            assertEquals(fewer, OverlayStore(dir).load())
            assertEquals(6, java.io.File(dir, "images").listFiles()!!.size) // 5 photos + logo
            // a damaged file falls back to the previous save
            java.io.File(dir, "overlay.json").writeText("{ broken")
            assertNotNull(OverlayStore(dir).load())
        } finally { dir.deleteRecursively() }
    }

    @Test fun zoneDragFollowsThePointer() {
        for (right in listOf(true, false)) {
            val cfg = OverlayConfig(cameraRight = right)
            val cam = OverlayLayout.zone(SceneKind.GAMEPLAY, cfg, ZoneKind.CAMERA)!!
            // dragging right moves right, camera on the left or on the right
            val r = OverlayLayout.zone(SceneKind.GAMEPLAY, OverlayLayout.move(cfg, SceneKind.GAMEPLAY, ZoneKind.CAMERA, -30f, 30f), ZoneKind.CAMERA)!!
            assertEquals("right=$right", cam.x - 30f, r.x, 0.5f)
            assertEquals(cam.y + 30f, r.y, 0.5f)
            // pushed far past the edge then brought back: the element moves back at once (no offset with the mouse)
            val far = OverlayLayout.move(cfg, SceneKind.GAMEPLAY, ZoneKind.CAMERA, 5000f, 0f)
            val back = OverlayLayout.move(far, SceneKind.GAMEPLAY, ZoneKind.CAMERA, -100f, 0f)
            val edge = OverlayLayout.zone(SceneKind.GAMEPLAY, far, ZoneKind.CAMERA)!!
            assertEquals("right=$right", edge.x - 100f, OverlayLayout.zone(SceneKind.GAMEPLAY, back, ZoneKind.CAMERA)!!.x, 0.5f)
        }
    }

    @Test fun undoGroupsQuickChanges() {
        val h = UndoHistory<Int>()
        h.record(0, "drag", now = 1000); h.record(1, "drag", now = 1100); h.record(2, "drag", now = 1200)
        h.record(3, "size", now = 1300)
        assertEquals(3, h.undo(4))
        assertEquals(0, h.undo(3))
        assertFalse(h.canUndo)
        assertEquals(3, h.redo(0))
        assertTrue(h.canRedo)
    }
}
