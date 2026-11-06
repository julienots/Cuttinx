package com.superessence.cuttinx.core

import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.smart.*
import com.superessence.cuttinx.core.timeline.TimelineEditor
import org.junit.Assert.*
import org.junit.Test

class EditToolsTest {
    private fun two(): Project {
        var p = T.project()
        p = TimelineEditor.appendAsset(p, T.video("a", 10.0)).first
        p = TimelineEditor.appendAsset(p, T.video("b", 6.0)).first
        return p
    }
    private fun main(p: Project) = SmartTools.mainTrack(p)!!.sortedClips()

    @Test fun rippleTrimRemovesTheStartOrTheEndAndClosesTheHole() {
        val p = two()
        val a = main(p)[0]
        val cut = EditTools.rippleTrim(p, 4_000_000, keepAfter = true, EditTools.mainClipsAt(p, 4_000_000))
        val m = main(cut)
        assertEquals(2, m.size)
        assertEquals(0, m[0].startUs); assertEquals(6_000_000, m[0].durationUs); assertEquals(4_000_000, m[0].sourceInUs)
        assertEquals(6_000_000, m[1].startUs)                       // the next shot followed
        assertEquals(12_000_000, cut.durationUs)
        val after = EditTools.rippleTrim(p, 4_000_000, keepAfter = false, listOf(a.id))
        assertEquals(4_000_000, main(after)[0].durationUs); assertEquals(4_000_000, main(after)[1].startUs)
        assertSame(p, EditTools.rippleTrim(p, 20_000_000, true, listOf(a.id)).let { if (it == p) p else it })  // outside: unchanged
    }

    @Test fun jAndLCutsDetachTheSoundAroundTheCut() {
        var p = two()
        val b = main(p)[1]
        p = TimelineEditor.updateClip(p, b.id) { it.copy(sourceInUs = 2_000_000, sourceOutUs = 5_000_000) }   // b plays 2 s..5 s of its media
        val out = EditTools.jlCut(p, b.id, jUs = 500_000, lUs = 700_000)
        val vb = main(out)[1]
        assertTrue(vb.audio.muted)
        val audioTrack = out.tracks.single { it.kind == TrackKind.AUDIO }
        val ac = audioTrack.clips.single()
        assertEquals(vb.startUs - 500_000, ac.startUs)
        assertEquals(1_500_000, ac.sourceInUs)
        assertEquals(vb.endUs + 700_000, ac.endUs)
        assertTrue(ac.audio.fadeInUs > 0 && !ac.audio.muted)
        // asking again re-adjusts the same detached sound instead of stacking another
        val again = EditTools.jlCut(out, vb.id, 200_000, 0)
        assertEquals(1, again.tracks.filter { it.kind == TrackKind.AUDIO }.sumOf { it.clips.size })
        assertEquals(vb.startUs - 200_000, again.tracks.first { it.kind == TrackKind.AUDIO }.clips.single().startUs)
        // limited by the media: the first shot starts at its media's beginning, no J possible
        val a = main(p)[0]
        val none = EditTools.jlCut(p, a.id, 800_000, 0)
        assertEquals(0, none.tracks.first { it.kind == TrackKind.AUDIO }.clips.single().startUs)
    }

    @Test fun pasteAttributesCopiesChosenSettingsOnly() {
        var p = two()
        val (a, b) = main(p)
        p = TimelineEditor.updateClip(p, a.id) { it.copy(effects = listOf(EffectInstance("e1", "bw", emptyMap())), transform = it.transform.copy(scaleX = 1.3, scaleY = 1.3), audio = it.audio.copy(volume = 0.4)) }
        val out = EditTools.copyAttributes(p, a.id, listOf(b.id), setOf(Attribute.EFFECTS, Attribute.TRANSFORM))
        val nb = main(out)[1]
        assertEquals("bw", nb.effects.single().effectId); assertNotEquals("e1", nb.effects.single().id)
        assertEquals(1.3, nb.transform.scaleX, 1e-9)
        assertEquals(1.0, nb.audio.volume, 1e-9)               // sound not requested
        assertEquals(main(p)[1].startUs, nb.startUs)           // timing untouched
    }

    @Test fun equalDurationsMarkersAndSolo() {
        var p = T.project()
        for (i in 1..3) p = TimelineEditor.appendAsset(p, T.image("i$i")).first
        val eq = EditTools.equalizeDurations(p, SmartTools.mainTrack(p)!!.id, 2_000_000)
        assertEquals(listOf(0L, 2_000_000L, 4_000_000L), main(eq).map { it.startUs })
        assertEquals(6_000_000, eq.durationUs)
        val mk = EditTools.markersAtCuts(eq)
        assertEquals(listOf("Plan 2", "Plan 3"), mk.markers.sortedBy { it.timeUs }.map { it.label })
        assertEquals(mk, EditTools.markersAtCuts(mk))          // idempotent
        val withMusic = TimelineEditor.appendAsset(mk, T.audio("m", 6.0)).first
        val mainId = SmartTools.mainTrack(withMusic)!!.id
        val music = withMusic.tracks.first { it.kind == TrackKind.AUDIO }
        val solo = EditTools.solo(withMusic, music.id)
        assertTrue(solo.track(mainId)!!.muted); assertFalse(solo.track(music.id)!!.muted)
        assertFalse(EditTools.solo(solo, music.id).tracks.any { it.muted })     // second call restores
        // split at the markers
        var q = two()
        q = TimelineEditor.addMarker(q, 3_000_000); q = TimelineEditor.addMarker(q, 13_000_000)
        assertEquals(4, main(EditTools.splitAtMarkers(q)).size)
    }

    @Test fun nudgeAndGaps() {
        var p = two()
        val vid = SmartTools.mainTrack(p)!!
        val (ep, tr) = TimelineEditor.ensureTrack(p, TrackKind.TEXT)
        val t1 = TimelineEditor.textClip("A", 1_000_000, 1_000_000); val t2 = TimelineEditor.textClip("B", 5_000_000, 1_000_000)
        p = ep.copy(tracks = ep.tracks.map { if (it.id == tr.id) it.copy(clips = listOf(t1, t2)) else it })
        val closed = EditTools.closeGaps(p, tr.id)
        assertEquals(listOf(0L, 1_000_000L), closed.track(tr.id)!!.sortedClips().map { it.startUs })
        val moved = EditTools.nudge(p, listOf(t2.id), 3)
        assertEquals(5_000_000 + 3 * 33_333, moved.track(tr.id)!!.sortedClips()[1].startUs)
        val aligned = EditTools.alignStart(p, listOf(t2.id), 2_000_000)
        assertEquals(2_000_000, aligned.track(tr.id)!!.sortedClips()[1].startUs)
        assertNotNull(vid)
    }
}
