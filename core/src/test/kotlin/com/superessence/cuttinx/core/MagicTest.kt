package com.superessence.cuttinx.core

import com.superessence.cuttinx.core.analysis.Frame
import com.superessence.cuttinx.core.analysis.Highlight
import com.superessence.cuttinx.core.analysis.TimeRange
import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.smart.*
import com.superessence.cuttinx.core.timeline.TimelineEditor
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

class MagicTest {
    private fun three(): Project {
        var p = T.project()
        p = TimelineEditor.appendAsset(p, T.video("a", 10.0)).first
        p = TimelineEditor.appendAsset(p, T.video("b", 6.0)).first
        p = TimelineEditor.appendAsset(p, T.video("c", 8.0)).first
        return p
    }
    private fun main(p: Project) = SmartTools.mainTrack(p)!!.sortedClips()
    private fun solid(r: Int, g: Int, b: Int) = Frame(0, 8, 8, IntArray(64) { (0xFF shl 24) or (r shl 16) or (g shl 8) or b })

    /** What the "adjust" shader does with exposure / temperature / tint (see EffectRegistry). */
    private fun applyAdjust(l: Magic.Look, m: Map<String, Double>): Magic.Look {
        val k = 2.0.pow(m["exposure"]!!)
        val t = m["temperature"]!!; val ti = m["tint"]!!
        return Magic.Look(l.r * k + t * 0.12 + ti * 0.05, l.g * k - ti * 0.10, l.b * k - t * 0.12 + ti * 0.05, l.sat)
    }

    @Test fun shotMatchingBringsShotsTogether() {
        val dark = Magic.measure(listOf(solid(40, 50, 90)))!!          // dark and blue
        val warm = Magic.measure(listOf(solid(220, 170, 120)))!!       // bright and orange
        assertTrue(dark.luma < 0.25 && warm.luma > 0.6)
        val t = Magic.target(listOf(dark, warm))
        val a = applyAdjust(dark, Magic.correction(dark, t, 1.0)); val b = applyAdjust(warm, Magic.correction(warm, t, 1.0))
        assertTrue("luma ${a.luma} / ${b.luma}", abs(a.luma - b.luma) < abs(dark.luma - warm.luma) / 3)
        assertTrue("cast ${a.r - a.b} / ${b.r - b.b}", abs((a.r - a.b) - (b.r - b.b)) < abs((dark.r - dark.b) - (warm.r - warm.b)) * 0.75)
        assertNull(Magic.measure(listOf(solid(0, 0, 0))))               // nothing usable in a black frame
        val p = three(); val ids = main(p).map { it.id }
        val q = Magic.matchShots(p, mapOf(ids[0] to dark, ids[1] to warm))
        assertEquals("adjust", q.findClip(ids[0])!!.second.effects.first().effectId)
        val again = Magic.matchShots(q, mapOf(ids[0] to dark, ids[1] to warm))
        assertEquals(1, again.findClip(ids[0])!!.second.effects.count { it.id.startsWith("match_") })   // replaced, not stacked
    }

    @Test fun jumpCutRemovesSilencesAndPunchesIn() {
        val p = three(); val a = main(p)[0]
        val q = Magic.jumpCut(p, mapOf(a.id to listOf(TimeRange(2_000_000, 3_000_000), TimeRange(6_000_000, 6_500_000))))
        assertEquals(p.durationUs - 1_500_000, q.durationUs)
        val parts = main(q).filter { it.assetId == "a" }
        assertEquals(3, parts.size)
        assertTrue(parts.any { it.transform.scaleX > 1.0 })              // alternate zoom on the jump cuts
        assertSame(p, Magic.jumpCut(p, emptyMap()))
    }

    @Test fun bestOfKeepsTheTopMoments() {
        val p = three(); val (a, b, c) = main(p)
        val moments = mapOf(
            a.id to listOf(Highlight(1_000_000, 4_000_000, 0.9, emptyList()), Highlight(6_000_000, 9_000_000, 0.2, emptyList())),
            b.id to listOf(Highlight(0, 3_000_000, 0.8, emptyList())),
            c.id to listOf(Highlight(2_000_000, 5_000_000, 0.1, emptyList())),
        )
        val q = Magic.bestOf(p, moments, 6_000_000)
        val kept = main(q)
        assertEquals(listOf("a", "b"), kept.map { it.assetId })
        assertTrue(q.tracks.first { it.kind == TrackKind.VIDEO }.endUs in 5_000_000..6_000_000)
        assertEquals(1_000_000, kept[0].sourceInUs)
    }

    @Test fun beatEditCutsOnTheDetectedBeats() {
        val p = three()
        val beats = (0 until 48).map { it * 500_000L }
        val q = Magic.beatEdit(p, beats, beats.filterIndexed { i, _ -> i % 4 == 0 }, 120.0)
        val cuts = main(q).drop(1).map { it.startUs }
        assertTrue(cuts.size > 6)
        assertTrue(cuts.all { c -> beats.any { abs(it - c) <= 34_000 } })
        assertTrue(q.markers.size >= 6)
        assertSame(p, Magic.beatEdit(p, beats.take(2), emptyList(), 120.0))
    }

    @Test fun instantGesturesAndPlusGating() {
        val p = three()
        for ((id, _) in listOf("fades" to 0, "hook" to 0, "progress" to 0, "transitions" to 0)) assertNotEquals(id, p, Magic.instant(id, p))
        assertTrue(Tech.entries.filter { it.pipeline }.size >= 6)
        assertTrue(Tech.entries.filter { it.pipeline }.none { TechVault.isUnlocked(it) })
    }
}
