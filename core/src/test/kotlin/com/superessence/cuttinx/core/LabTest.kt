package com.superessence.cuttinx.core

import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.smart.*
import com.superessence.cuttinx.core.timeline.TimelineEditor
import org.junit.Assert.*
import org.junit.Test

class LabTest {
    private fun main(p: Project) = SmartTools.mainTrack(p)!!.sortedClips()

    private fun three(): Project {
        var p = T.project()
        p = TimelineEditor.appendAsset(p, T.video("a", 10.0)).first
        p = TimelineEditor.appendAsset(p, T.video("b", 6.0)).first
        p = TimelineEditor.appendAsset(p, T.video("c", 4.0)).first
        return p
    }

    @Test fun vaultUnlocksForADayAndStacks() {
        var now = 1_000_000L
        TechVault.clock = { now }
        try {
            assertFalse(TechVault.isUnlocked(Tech.ECHO))
            assertTrue(QuickActions["lab_echo"]!!.locked)
            TechVault.unlock(Tech.ECHO)
            assertTrue(TechVault.isUnlocked(Tech.ECHO)); assertFalse(QuickActions["lab_echo"]!!.locked)
            assertEquals("encore 24 h", TechVault.remaining(Tech.ECHO))
            now += 23 * 3_600_000L; TechVault.unlock(Tech.ECHO)          // watching again adds a day
            now += 2 * 3_600_000L
            assertTrue(TechVault.isUnlocked(Tech.ECHO))
            now += 24 * 3_600_000L
            assertFalse(TechVault.isUnlocked(Tech.ECHO)); assertNull(TechVault.remaining(Tech.ECHO))
            assertFalse(QuickActions["vfx_vhs"]!!.locked)                  // free tools are never locked
            assertTrue(Tech.entries.all { QuickActions.byTech(it).isNotEmpty() })
        } finally { TechVault.clock = { System.currentTimeMillis() } }
    }

    @Test fun kineticSplitsTextIntoTimedWords() {
        val (p0, tr) = TimelineEditor.ensureTrack(three(), TrackKind.TEXT)
        val t = TimelineEditor.textClip("Le montage le plus INCROYABLE", 1_000_000, 2_000_000)
        val p = TimelineEditor.insertClip(p0, tr.id, t)
        val k = Lab.kinetic(p, listOf(t.id))
        assertNull(k.findClip(t.id))
        val words = k.allClips.filter { it.text != null }.sortedBy { it.startUs }
        assertEquals(listOf("Le", "montage", "le", "plus", "INCROYABLE"), words.map { it.text!!.text })
        assertEquals(1_000_000, words.first().startUs); assertEquals(3_000_000, words.last().endUs)
        assertEquals(0xFFFFD21F, words[4].text!!.style.color)                // strong word highlighted
        assertTrue(words.zipWithNext().all { (a, b) -> b.startUs >= a.endUs })
    }

    @Test fun echoClonesAndWall() {
        val p = three()
        val a = main(p)[0]
        val e = Lab.echo(p, listOf(a.id))
        val echoes = e.tracks.filter { it.name.startsWith("Écho") }.flatMap { it.clips }
        assertEquals(3, echoes.size)
        assertTrue(echoes.all { it.blendMode == BlendMode.SCREEN && it.audio.muted && it.endUs <= a.endUs + 1_000 })
        assertEquals(listOf(70_000L, 140_000L, 210_000L), echoes.map { it.startUs }.sorted())
        val cl = Lab.clones(p, listOf(a.id))
        assertEquals(3, cl.allClips.count { it.assetId == "a" })
        val w = Lab.wall(p)
        assertEquals(2_500_000 + p.durationUs, w.durationUs)
        assertEquals(ClipKind.COLOR, main(w)[0].kind)
        assertEquals(3, w.tracks.count { it.name.startsWith("Mur") })        // 3 shots -> 2x2 grid with 3 tiles
    }

    @Test fun cutEffectsUseEffectLayers() {
        val p = three()
        val i = Lab.impacts(p)
        val layers = i.tracks.filter { it.kind == TrackKind.EFFECT }.flatMap { it.clips }
        assertEquals(2, layers.size)
        assertTrue(layers.all { it.kind == ClipKind.ADJUSTMENT && it.effects.any { e -> e.effectId == "zoom_blur" } })
        val z = Lab.zoomFlow(p)
        assertEquals(2.4, z.findClip(main(p)[0].id)!!.second.keyframes[Prop.SCALE]!!.keyframes.last().value, 1e-9)
        val b = Lab.beatFx(p, 120.0)
        assertEquals(40, b.tracks.filter { it.kind == TrackKind.EFFECT }.sumOf { it.clips.size })   // 20 s at 120 BPM
        assertTrue(Lab.glitchStorm(p, 12).tracks.any { it.name.startsWith("Glitch") })
    }

    @Test fun pacingTempoRemixAndEndings() {
        val p = three()
        val cres = main(Lab.pacing(p, Pacing.CRESCENDO))
        assertEquals(listOf(2_600_000L, 1_525_000L, 450_000L), cres.map { it.durationUs })
        var m = p
        for (t in listOf(1_000_000L, 1_500_000L, 2_000_000L, 2_500_000L)) m = TimelineEditor.addMarker(m, t)
        assertEquals(120.0, Lab.bpmFromMarkers(m)!!, 1e-6)
        assertTrue(main(Lab.tapTempo(m)).size > 3)
        val r1 = Lab.remix(p, 1); val r2 = Lab.remix(p, 2)
        assertNotEquals(main(r1).map { it.effects.map { e -> e.effectId } }, main(r2).map { it.effects.map { e -> e.effectId } } + listOf(listOf("x")))
        val rw = Lab.rewind(p)
        assertEquals(3, main(rw).count { it.name == "Rembobinage" })
        assertTrue(Lab.credits(p).allClips.any { it.name == "Générique déroulant" && it.keyframes[Prop.POS_Y] != null })
    }

    @Test fun catalogueCoversEveryEffectAndTransition() {
        val vfx = QuickActions.all.count { it.group == "Effets vidéo" }
        assertTrue("$vfx effets", vfx >= 75)
        assertTrue(com.superessence.cuttinx.core.effects.TransitionRegistry.all.all { d -> QuickActions["tr_${d.id}"] != null })
        val p = three()
        val layered = Catalog.videoEffect(p, emptyList(), "vhs")
        assertEquals(p.durationUs, layered.tracks.first { it.kind == TrackKind.EFFECT }.clips.single().durationUs)
        val onShot = Catalog.videoEffect(p, listOf(main(p)[0].id), "vhs")
        assertEquals(1, Catalog.videoEffect(onShot, listOf(main(p)[0].id), "vhs").findClip(main(p)[0].id)!!.second.effects.count { it.effectId == "vhs" })
        val black = Catalog.insertColor(p, 5_000_000, 0xFF000000, 1_000_000)
        assertEquals(p.durationUs + 1_000_000, black.durationUs)
        assertEquals(ClipKind.COLOR, main(black)[1].kind)
    }
}
