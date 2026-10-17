package com.superessence.cuttinx.core

import com.superessence.cuttinx.core.anim.MotionEvaluator
import com.superessence.cuttinx.core.autoedit.ProTools
import com.superessence.cuttinx.core.effects.TransitionRegistry
import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.render.FrameEvaluator
import com.superessence.cuttinx.core.render.OverlayAnchors
import com.superessence.cuttinx.core.timeline.TimelineEditor
import org.junit.Assert.*
import org.junit.Test

class MotionTest {
    /** Media3 draws the overlay centre at B - A·S·o: the solved anchors must reproduce it. */
    private fun centre(a: OverlayAnchors.Anchors, halfW: Double, halfH: Double) = (a.bgX - halfW * a.ovX) to (a.bgY - halfH * a.ovY)

    @Test fun anchorsStayInRangeAndKeepPosition() {
        val cases = listOf(
            doubleArrayOf(0.0, 0.0, 0.0, 0.0, 1.0, 1.0),   // centred, full frame
            doubleArrayOf(2.0, 0.0, 0.0, 0.0, 1.0, 1.0),   // slid one frame to the right (slide transition)
            doubleArrayOf(-1.6, 0.4, 0.0, 0.0, 0.8, 0.8),
            doubleArrayOf(1.2, -1.3, 0.5, -0.5, 0.4, 0.3),
            doubleArrayOf(0.3, 0.2, -1.0, 1.0, -0.6, 0.5), // flipped
        )
        for (c in cases) {
            val px = c[0]; val py = c[1]; val ax = c[2]; val ay = c[3]; val hw = c[4]; val hh = c[5]
            val a = OverlayAnchors.solve(px, py, ax, ay, hw, hh)!!
            assertTrue(a.bgX in -1f..1f && a.bgY in -1f..1f && a.ovX in -1f..1f && a.ovY in -1f..1f)
            val (cx, cy) = centre(a, hw, hh)
            assertEquals(px - hw * ax, cx, 1e-5)
            assertEquals(py - hh * ay, cy, 1e-5)
        }
    }

    @Test fun anchorsHideLayersFullyOutside() {
        assertNull(OverlayAnchors.solve(3.5, 0.0, 0.0, 0.0, 1.0, 1.0))
        assertNull(OverlayAnchors.solve(0.0, 0.0, 0.0, 0.0, 0.0, 1.0))
        assertNull(OverlayAnchors.solve(Double.NaN, 0.0, 0.0, 0.0, 1.0, 1.0))
    }

    @Test fun everyTransitionKeepsAnchorsSolvable() {
        // regression: slide/whip/push moved layers outside [-1, 1] and crashed the video pipeline
        for (t in TransitionRegistry.all) for (q in listOf(0.0, 0.1, 0.5, 0.9, 1.0)) for (top in listOf(true, false)) {
            val (a, b) = TransitionRegistry.evaluate(t.id, q, top)
            for (m in listOf(a, b)) {
                val x = (0.5 + m.dx) * 2 - 1; val y = 1 - (0.5 + m.dy) * 2
                val s = OverlayAnchors.solve(x, y, 0.0, 0.0, m.scale, m.scale, m.rotation)
                if (s != null) assertTrue("${t.id} $q", s.bgX in -1f..1f && s.ovX in -1f..1f && s.bgY in -1f..1f && s.ovY in -1f..1f)
                assertTrue("${t.id} alpha", m.alpha in 0.0..1.0)
            }
        }
    }

    @Test fun presetsRestAtIdentity() {
        for (pr in MotionPreset.entries) {
            val s = MotionEvaluator.preset(pr, 1.0, exit = false)
            assertEquals("$pr alpha", 1.0, s.alpha, 1e-6)
            assertEquals("$pr dx", 0.0, s.dx, 1e-6); assertEquals("$pr dy", 0.0, s.dy, 1e-6)
            assertEquals("$pr sx", 1.0, s.scaleX, 1e-3); assertEquals("$pr sy", 1.0, s.scaleY, 1e-3)
            assertEquals("$pr rot", 0.0, s.rotation, 1e-6)
            for (p in listOf(0.0, 0.3, 0.7)) {
                val m = MotionEvaluator.preset(pr, p, exit = true)
                assertTrue("$pr finite", listOf(m.alpha, m.dx, m.dy, m.scaleX, m.scaleY, m.rotation).all { it.isFinite() })
            }
        }
    }

    @Test fun clipMotionAppliesInOutAndLoop() {
        val c = Clip("c", sourceOutUs = 4_000_000, motion = ClipMotion(MotionPreset.SLIDE_LEFT, 500_000, MotionPreset.FADE, 500_000))
        assertEquals(1.5, FrameEvaluator.transform(c, 0).posX, 1e-9)          // enters from the right
        assertEquals(0.5, FrameEvaluator.transform(c, 2_000_000).posX, 1e-9)  // at rest
        assertEquals(0.0, FrameEvaluator.transform(c, 4_000_000).opacity, 1e-9) // faded out at the end
        // editing tools never see animation offsets
        assertEquals(0.5, FrameEvaluator.transform(c, 0, withMotion = false).posX, 1e-9)
        for (l in LoopMotion.entries) {
            val cl = c.copy(motion = ClipMotion(loop = l))
            for (t in listOf(0L, 333_000L, 1_700_000L)) assertTrue("$l", FrameEvaluator.transform(cl, t).let { it.posX.isFinite() && it.scaleX.isFinite() && it.opacity in 0.0..1.0 })
        }
    }

    @Test fun proToolsProduceEditableClips() {
        var p = T.project()
        repeat(3) { p = TimelineEditor.appendAsset(p, T.video("v$it", 3.0)).first }
        val main = p.tracks.first()
        p = ProTools.progressBar(p)
        val bar = p.tracks.last().clips.single()
        assertEquals(ClipKind.COLOR, bar.kind)
        assertEquals(0.0, FrameEvaluator.transform(bar, 0).scaleX, 1e-9)
        assertEquals(1.0, FrameEvaluator.transform(bar, bar.durationUs).scaleX, 1e-9)
        p = ProTools.handleWatermark(p, "cuttinx")
        assertEquals("@cuttinx", p.tracks.last().clips.single().text!!.text)
        p = ProTools.autoAnimate(p, main.id)
        assertTrue(p.track(main.id)!!.clips.all { it.motion.animIn != MotionPreset.NONE })
        p = ProTools.autoTransitions(p, main.id)
        assertTrue(p.track(main.id)!!.sortedClips().drop(1).all { it.transitionIn != null })
        val first = p.track(main.id)!!.sortedClips().first()
        p = ProTools.beatZoom(p, first.id, listOf(500_000L, 1_000_000L, 1_500_000L))
        val z = p.findClip(first.id)!!.second
        assertTrue(FrameEvaluator.transform(z, 1_000_000, withMotion = false).scaleX > 1.05)
        p = ProTools.hookTitle(p, "Regarde ça")
        assertTrue(p.allClips.any { it.text?.text == "Regarde ça" && it.motion.loop == LoopMotion.ZOOM_PUNCH })
    }
}
