package com.superessence.cuttinx.core

import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.timeline.*
import org.junit.Assert.*
import org.junit.Test

class TimelineTest {
    private fun base(): Project {
        var p = T.project()
        p = TimelineEditor.appendAsset(p, T.video("a", 10.0)).first
        p = TimelineEditor.appendAsset(p, T.video("b", 6.0)).first
        p = TimelineEditor.appendAsset(p, T.image("i")).first
        return p
    }
    private fun main(p: Project) = p.tracks.first { it.kind == TrackKind.VIDEO }.sortedClips()

    @Test fun appendBuildsContiguousMainTrack() {
        val p = base()
        val m = main(p)
        assertEquals(3, m.size)
        assertEquals(0, m[0].startUs)
        assertEquals(10_000_000, m[1].startUs)
        assertEquals(16_000_000, m[2].startUs)
        assertEquals(19_000_000, p.durationUs)
    }

    @Test fun splitKeepsDurationAndSourceContinuity() {
        var p = base()
        val first = main(p)[0]
        val (np, rightId) = TimelineEditor.split(p, first.id, 4_000_000)
        p = np
        val m = main(p)
        assertEquals(4, m.size)
        assertEquals(19_000_000, p.durationUs)
        val l = m[0]; val r = m[1]
        assertEquals(rightId, r.id)
        assertEquals(4_000_000, l.sourceOutUs)
        assertEquals(4_000_000, r.sourceInUs)
        assertEquals(l.endUs, r.startUs)
        // merging them back restores one clip
        val merged = TimelineEditor.merge(p, l.id, r.id)
        assertEquals(3, main(merged).size)
        assertEquals(10_000_000, main(merged)[0].sourceOutUs)
    }

    @Test fun splitNearEdgeRejected() {
        val p = base()
        val c = main(p)[0]
        assertThrows(EditException::class.java) { TimelineEditor.split(p, c.id, 10_000) }
    }

    @Test fun trimRespectsSourceLimits() {
        var p = base()
        val c = main(p)[1] // b: 6 s video starting at 10 s
        p = TimelineEditor.trimEnd(p, c.id, c.startUs + 60_000_000) // try to extend beyond source
        assertEquals(6_000_000, p.findClip(c.id)!!.second.sourceOutUs)
        p = TimelineEditor.trimEnd(p, c.id, c.startUs + 2_000_000)
        assertEquals(2_000_000, p.findClip(c.id)!!.second.durationUs)
        // magnetic main track: image follows
        assertEquals(12_000_000, main(p)[2].startUs)
        p = TimelineEditor.trimStart(p, c.id, c.startUs + 500_000)
        val t = p.findClip(c.id)!!.second
        assertEquals(500_000, t.sourceInUs)
        assertEquals(1_500_000, t.durationUs)
        // cannot extend the start before source 0
        p = TimelineEditor.trimStart(p, c.id, 0)
        assertEquals(0, p.findClip(c.id)!!.second.sourceInUs)
    }

    @Test fun overlayTrackMovesAndPushes() {
        var p = base()
        val (p2, ov) = TimelineEditor.addTrack(p, TrackKind.VIDEO); p = p2
        val c1 = TimelineEditor.clipFromAsset(p.asset("i")!!, 1_000_000)
        val c2 = TimelineEditor.clipFromAsset(p.asset("i")!!, 5_000_000)
        p = TimelineEditor.insertClip(p, ov.id, c1)
        p = TimelineEditor.insertClip(p, ov.id, c2)
        // move c2 onto c1 -> c2 is pushed after c1? anchor gets priority, c1 pushed after c2
        p = TimelineEditor.moveClipTo(p, c2.id, 2_000_000)
        val clips = p.track(ov.id)!!.sortedClips()
        for (i in 1 until clips.size) assertTrue(clips[i].startUs >= clips[i - 1].endUs)
        // gaps allowed on overlay tracks
        p = TimelineEditor.moveClipTo(p, clips[1].id, 15_000_000)
        assertEquals(15_000_000, p.findClip(clips[1].id)!!.second.startUs)
    }

    @Test fun transitionsOverlapAndRestore() {
        var p = base()
        val m = main(p)
        p = TimelineEditor.setTransition(p, m[1].id, TransitionInstance("fade", 1_000_000))
        val m2 = main(p)
        assertEquals(m2[0].endUs - 1_000_000, m2[1].startUs)
        assertEquals(18_000_000, p.durationUs)
        assertNotNull(m2[1].transitionIn)
        p = TimelineEditor.setTransition(p, m[1].id, null)
        assertEquals(19_000_000, p.durationUs)
        assertNull(main(p)[1].transitionIn)
        // first clip cannot have a transition
        assertThrows(EditException::class.java) { TimelineEditor.setTransition(p, m[0].id, TransitionInstance("fade", 500_000)) }
        // transition duration clamped to half of the shortest clip
        p = TimelineEditor.setTransition(p, m[2].id, TransitionInstance("fade", 10_000_000))
        assertEquals(1_500_000, main(p)[2].transitionIn!!.durationUs)
    }

    @Test fun deleteRippleAndDuplicate() {
        var p = base()
        val m = main(p)
        p = TimelineEditor.deleteClips(p, listOf(m[0].id))
        assertEquals(2, main(p).size)
        assertEquals(0, main(p)[0].startUs) // magnetic
        val (p2, dupId) = TimelineEditor.duplicate(p, main(p)[0].id); p = p2
        assertEquals(3, main(p).size)
        assertEquals(main(p)[0].endUs, p.findClip(dupId)!!.second.startUs)
    }

    @Test fun speedChangeRetimesKeyframes() {
        var p = base()
        val c = main(p)[0]
        p = TimelineEditor.setKeyframe(p, c.id, Prop.OPACITY, 10_000_000, 0.0)
        p = TimelineEditor.setSpeed(p, c.id, 2.0)
        val nc = p.findClip(c.id)!!.second
        assertEquals(5_000_000, nc.durationUs)
        assertEquals(5_000_000, nc.keyframes[Prop.OPACITY]!!.keyframes.last().timeUs)
        assertEquals(14_000_000, p.durationUs)
    }

    @Test fun groupsMoveTogetherAndClipboard() {
        var p = base()
        val (p2, ov) = TimelineEditor.addTrack(p, TrackKind.TEXT); p = p2
        val t1 = TimelineEditor.textClip("A", 0); val t2 = TimelineEditor.textClip("B", 4_000_000)
        p = TimelineEditor.insertClip(p, ov.id, t1); p = TimelineEditor.insertClip(p, ov.id, t2)
        p = TimelineEditor.group(p, listOf(t1.id, t2.id))
        p = TimelineEditor.moveClips(p, listOf(t1.id), 1_000_000)
        assertEquals(1_000_000, p.findClip(t1.id)!!.second.startUs)
        assertEquals(5_000_000, p.findClip(t2.id)!!.second.startUs)
        val clip = TimelineEditor.copy(p, listOf(t1.id, t2.id))
        val (p3, ids) = TimelineEditor.paste(p, clip, 20_000_000); p = p3
        assertEquals(2, ids.size)
        assertEquals(20_000_000, p.findClip(ids[0])!!.second.startUs)
        assertEquals(24_000_000, p.findClip(ids[1])!!.second.startUs)
    }

    @Test fun lockedTrackRefusesEdits() {
        var p = base()
        val t = p.tracks.first()
        p = TimelineEditor.updateTrack(p, t.id) { it.copy(locked = true) }
        assertThrows(EditException::class.java) { TimelineEditor.split(p, t.clips[0].id, 2_000_000) }
        val s = EditSession(p)
        val r = s.edit("split") { TimelineEditor.split(it, t.clips[0].id, 2_000_000).first }
        assertTrue(r is EditResult.Failed)
    }

    @Test fun undoRedoHistory() {
        val s = EditSession(base())
        val id = main(s.current)[0].id
        s.edit("Couper") { TimelineEditor.split(it, id, 3_000_000).first }
        assertEquals(4, main(s.current).size)
        s.edit("Vitesse") { TimelineEditor.setSpeed(it, id, 2.0) }
        assertEquals("Vitesse", s.undo())
        assertEquals("Couper", s.undo())
        assertEquals(3, main(s.current).size)
        assertNull(s.undo())
        assertEquals("Couper", s.redo())
        assertEquals(4, main(s.current).size)
        // coalescing slider drags
        var t = 0L
        val s2 = EditSession(base()) { t }
        val id2 = main(s2.current)[0].id
        repeat(10) { i -> t += 100; s2.edit("Opacité", "op") { p -> TimelineEditor.updateClip(p, id2) { it.copy(transform = it.transform.copy(opacity = i / 10.0)) } } }
        assertEquals(1, s2.history.entries.size)
    }

    @Test fun replaceAndRelinkMedia() {
        var p = base()
        val c = main(p)[0]
        p = TimelineEditor.replaceMedia(p, c.id, T.video("z", 4.0))
        val nc = p.findClip(c.id)!!.second
        assertEquals("z", nc.assetId)
        assertEquals(4_000_000, nc.sourceDurationUs)
        p = TimelineEditor.updateMedia(p, "b") { it.copy(missing = true) }
        assertEquals(1, p.missingMedia.size)
        p = TimelineEditor.relinkMedia(p, "b", "file:///new.mp4", 3_000_000)
        assertEquals(0, p.missingMedia.size)
        assertEquals(3_000_000, p.allClips.first { it.assetId == "b" }.sourceOutUs)
    }

    @Test fun snapping() {
        val p = base()
        val cands = Snapping.candidates(p, playheadUs = 7_000_000)
        assertEquals(10_000_000, Snapping.snap(10_080_000, cands, 100_000))
        assertEquals(10_300_000, Snapping.snap(10_300_000, cands, 100_000))
        assertEquals(7_000_000, Snapping.snapRange(6_950_000, 1_000_000, cands, 100_000))
        assertEquals(100_000L, Snapping.toFrame(100_000, 30))
    }

    @Test fun manyClipsStayFast() {
        var p = T.project()
        val a = T.video("v", 3600.0)
        p = TimelineEditor.addMedia(p, a)
        val (p1, track) = TimelineEditor.mainTrack(p); p = p1
        val start = System.nanoTime()
        val clips = (0 until 2000).map { Clip("c$it", assetId = "v", startUs = it * 1_000_000L, sourceInUs = it * 1_000_000L, sourceOutUs = it * 1_000_000L + 1_000_000) }
        p = p.copy(tracks = p.tracks.map { if (it.id == track.id) it.copy(clips = clips) else it })
        repeat(50) { i -> p = TimelineEditor.split(p, "c${i * 20}", i * 20 * 1_000_000L + 500_000).first }
        val ms = (System.nanoTime() - start) / 1e6
        assertEquals(2050, main(p).size)
        assertTrue("50 splits on 2000 clips took $ms ms", ms < 5000)
    }
}
