package com.superessence.cuttinx.core

import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.smart.*
import com.superessence.cuttinx.core.timeline.TimelineEditor
import org.junit.Assert.*
import org.junit.Test

class QuickActionsTest {
    private fun main(p: Project) = SmartTools.mainTrack(p)!!.sortedClips()

    private fun sample(): Project {
        var p = T.project().copy(format = ProjectFormat.forAspect(AspectPreset.LANDSCAPE_16_9))
        p = TimelineEditor.appendAsset(p, T.video("a", 10.0)).first
        p = TimelineEditor.appendAsset(p, T.video("b", 6.0)).first
        p = TimelineEditor.appendAsset(p, T.image("i")).first
        p = TimelineEditor.appendAsset(p, T.audio("m", 12.0)).first
        val (p2, tr) = TimelineEditor.ensureTrack(p, TrackKind.TEXT)
        val t = TimelineEditor.textClip("Bonjour à tous, voici mon nouveau montage", 1_000_000, 2_000_000)
        return TimelineEditor.addMarker(p2.copy(tracks = p2.tracks.map { if (it.id == tr.id) it.copy(clips = listOf(t)) else it }), 5_000_000)
    }

    @Test fun splitEqualEveryRepeatAndFill() {
        val p = T.project().let { TimelineEditor.appendAsset(it, T.video("a", 10.0)).first }
        val a = main(p)[0]
        val four = main(ClipOps.splitEqual(p, a.id, 4))
        assertEquals(listOf(0L, 2_500_000L, 5_000_000L, 7_500_000L), four.map { it.startUs })
        assertTrue(four.all { it.durationUs == 2_500_000L })
        assertEquals(listOf(0L, 2_500_000L, 5_000_000L, 7_500_000L), four.map { it.sourceInUs })
        assertEquals(4, main(ClipOps.splitEvery(p, a.id, 3_000_000)).size)      // 3 + 3 + 3 + 1 s
        assertEquals(3, main(ClipOps.splitEvery(p.let { TimelineEditor.updateClip(it, a.id) { c -> c.copy(sourceOutUs = 9_200_000) } }, a.id, 3_000_000)).size) // 0.2 s remainder is glued
        assertEquals(30_000_000, main(ClipOps.repeat(p, a.id, 3)).last().endUs)
        // looping a 12 s song to 40 s
        val withSong = TimelineEditor.appendAsset(p, T.audio("m", 12.0)).first
        val song = withSong.tracks.first { it.kind == TrackKind.AUDIO }.clips.single()
        val looped = ClipOps.fillTo(withSong, song.id, 40_000_000)
        val clips = looped.tracks.first { it.kind == TrackKind.AUDIO }.sortedClips()
        assertEquals(40_000_000, clips.last().endUs); assertEquals(4, clips.size)
        assertSame(withSong, ClipOps.fillTo(withSong, song.id, 5_000_000))      // already long enough
    }

    @Test fun equalShotsAndReorder() {
        var p = T.project()
        p = TimelineEditor.appendAsset(p, T.video("a", 10.0)).first
        p = TimelineEditor.appendAsset(p, T.video("b", 3.0)).first
        p = TimelineEditor.appendAsset(p, T.video("c", 6.0)).first
        val tid = SmartTools.mainTrack(p)!!.id
        val eq = main(ClipOps.equalShots(p, tid, 4_000_000))
        assertEquals(listOf(4_000_000L, 3_000_000L, 4_000_000L), eq.map { it.durationUs })    // the short one is left alone
        assertEquals(3_000_000, eq[0].sourceInUs)                                              // the middle of the 10 s shot is kept
        assertEquals(listOf(0L, 4_000_000L, 7_000_000L), eq.map { it.startUs })
        val rev = main(ClipOps.reverseOrder(p, tid))
        assertEquals(listOf("c", "b", "a"), rev.map { it.assetId })
        assertEquals(listOf(0L, 6_000_000L, 9_000_000L), rev.map { it.startUs })
        val sh = main(ClipOps.shuffle(p, tid, 7))
        assertEquals(setOf("a", "b", "c"), sh.map { it.assetId }.toSet())
        assertNotEquals(listOf("a", "b", "c"), sh.map { it.assetId })
        assertEquals(19_000_000, sh.last().endUs)
    }

    @Test fun searchFindsActionsWithoutAccentsAndPrefersAvailableOnes() {
        val p = sample()
        val none = EditContext(p, emptyList(), 3_000_000)
        val r = QuickActions.search("DECOUPER", none).map { it.id }
        assertTrue(r.contains("split_2") && r.contains("every_2"))
        assertEquals("split_markers", QuickActions.search("couper marqueurs", none).first().id)
        assertTrue(QuickActions.search("tiktok", none).any { it.id == "for_tiktok" })
        assertTrue(QuickActions.search("zzzzqq", none).isEmpty())
        // with nothing selected, "découper en 2" is listed after the available actions
        val sel = EditContext(p, listOf(main(p)[0].id), 3_000_000)
        val a = QuickActions["split_2"]!!
        assertFalse(a.available(none)); assertTrue(a.available(sel))
        assertTrue(QuickActions.search("couper", sel).first().available(sel))
    }

    @Test fun everyActionRunsAndLeavesAValidMontage() {
        val p = sample()
        val vids = main(p)
        val ctx = EditContext(p, listOf(vids[0].id, vids[1].id), 3_000_000)
        var ran = 0
        for (a in QuickActions.all) {
            if (!a.available(ctx)) continue
            val out = try { a.run(ctx) } catch (e: Exception) { fail("${a.id} : ${e.message}"); return }
            ran++
            for (t in out.tracks) {
                val s = t.sortedClips()
                assertTrue("${a.id} : durée négative", s.all { it.durationUs >= 0 })
                if (t.kind == TrackKind.VIDEO) s.zipWithNext().forEach { (x, y) -> assertTrue("${a.id} : plans superposés", y.startUs >= x.endUs - (y.transitionIn?.durationUs ?: 0) - 1_000) }
            }
        }
        assertTrue("$ran actions lancées", ran >= 55)
        assertTrue(QuickActions.all.map { it.id }.toSet().size == QuickActions.all.size)      // unique ids
        assertTrue(QuickActions.all.all { IconSetLookup.exists(it.icon) })
    }
}

private object IconSetLookup { fun exists(n: String) = com.superessence.cuttinx.core.gfx.IconSet.all.containsKey(n) }
