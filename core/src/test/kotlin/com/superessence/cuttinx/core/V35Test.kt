package com.superessence.cuttinx.core

import com.superessence.cuttinx.core.audio.AudioChainFactory
import com.superessence.cuttinx.core.create.Releases
import com.superessence.cuttinx.core.effects.EffectCategory
import com.superessence.cuttinx.core.effects.EffectKind
import com.superessence.cuttinx.core.effects.EffectRegistry
import com.superessence.cuttinx.core.effects.Presets
import com.superessence.cuttinx.core.effects.ShaderLibrary
import com.superessence.cuttinx.core.effects.TransitionRegistry
import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.smart.*
import com.superessence.cuttinx.core.timeline.TimelineEditor
import org.junit.Assert.*
import org.junit.Test

/** CUTTINX 3.5 « Hyper réalité ». */
class V35Test {
    private fun three(): Project {
        var p = T.project()
        p = TimelineEditor.appendAsset(p, T.video("a", 10.0)).first
        p = TimelineEditor.appendAsset(p, T.video("b", 6.0)).first
        p = TimelineEditor.appendAsset(p, T.video("c", 8.0)).first
        return p
    }
    private fun main(p: Project) = SmartTools.mainTrack(p)!!.sortedClips()

    @Test fun newFamiliesAreFilledAndValid() {
        for (cat in listOf(EffectCategory.COSMOS, EffectCategory.GENERATIVE)) {
            val list = EffectRegistry.byCategory(cat)
            assertTrue("$cat : ${list.size}", list.size >= 8)
        }
        for (d in EffectRegistry.video().filter { it.category in setOf(EffectCategory.COSMOS, EffectCategory.GENERATIVE) || it.id.startsWith("trend_") }) {
            assertTrue(d.id, d.glsl.isNotBlank() && d.description.isNotBlank())
            assertTrue(d.id, d.params.size <= ShaderLibrary.MAX_PARAMS)
            for (p in d.params) assertTrue("${d.id}.${p.key}", p.default in p.min..p.max)
        }
        assertEquals(EffectRegistry.all.size, EffectRegistry.all.map { it.id }.toSet().size)
    }

    @Test fun everyVisibleFamilyGotSomethingNew() {
        val spread = com.superessence.cuttinx.core.effects.HyperEffects.spreadIds.mapNotNull { EffectRegistry[it] }.map { it.category }.toSet()
        val families = EffectCategory.entries.filter { it !in setOf(EffectCategory.INTERNAL, EffectCategory.AUDIO, EffectCategory.VOICE, EffectCategory.COSMOS, EffectCategory.GENERATIVE, EffectCategory.ADJUST) }
        for (f in families) assertTrue("$f n'a rien de nouveau", f in spread)
    }

    @Test fun newTransitionsUseRegisteredShaders() {
        for (id in HyperKit.TRANSITIONS) {
            assertNotNull(id, TransitionRegistry[id])
            for (q in listOf(0.0, 0.3, 0.5, 0.7, 1.0)) for (top in listOf(true, false)) {
                val (a, b) = TransitionRegistry.evaluate(id, q, top)
                for (m in listOf(a, b)) {
                    assertTrue("$id alpha", m.alpha in 0.0..1.0)
                    m.shaderId?.let { assertNotNull("$id → $it", EffectRegistry[it]) }
                }
            }
        }
    }

    @Test fun hyperFilters() {
        val f = Presets.builtIn.filter { it.category == "Hyper réalité" }
        assertTrue("${f.size} filtres", f.size >= 20)
        assertTrue("Hyper réalité" in Presets.CATEGORIES)
        assertTrue(f.all { pr -> pr.effects.all { EffectRegistry[it.effectId] != null } })
    }

    @Test fun newAudioEffectsBuildAndStayFinite() {
        val ids = listOf("a_8d", "a_bass_boost", "a_lofi", "a_chorus", "a_phaser", "a_tremolo", "a_vinyl", "a_next_room", "a_master", "v_choir", "v_announcer", "v_asmr")
        for (id in ids) {
            val def = EffectRegistry[id]
            assertNotNull(id, def); assertEquals(EffectKind.AUDIO, def!!.kind)
            val proc = AudioChainFactory.effect(EffectInstance("x", id, def.defaults()))
            assertNotNull("$id sans DSP", proc)
            proc!!.configure(48000, 2)
            val buf = T.sine(440.0, 0.5, channels = 2)
            proc.process(buf, buf.size / 2, 0)
            assertTrue("$id : NaN/inf", buf.all { it.isFinite() })
            assertTrue("$id : saturation", buf.all { kotlin.math.abs(it) < 4f })
            assertTrue("$id : silence", T.power(buf) > 1e-5)
        }
    }

    @Test fun sound8dMovesTheSoundBetweenTheEars() {
        val def = EffectRegistry["a_8d"]!!
        val proc = AudioChainFactory.effect(EffectInstance("x", "a_8d", def.defaults() + ("rate" to 0.5)))!!
        proc.configure(48000, 2)
        val buf = T.sine(300.0, 2.0, channels = 2)
        proc.process(buf, buf.size / 2, 0)
        // at t = 0.5 s the source is fully right, at t = 1.5 s fully left
        fun energy(t0: Double, ch: Int): Double { val s = (t0 * 48000).toInt(); var e = 0.0; for (f in s until s + 2400) e += buf[f * 2 + ch] * buf[f * 2 + ch].toDouble(); return e }
        assertTrue(energy(0.45, 1) > energy(0.45, 0) * 3)
        assertTrue(energy(1.45, 0) > energy(1.45, 1) * 3)
    }

    @Test fun hyperGestures() {
        val p = three()
        val h = HyperKit.hyperMode(HyperKit.hyperMode(p))
        assertEquals(1, h.tracks.filter { it.name == HyperKit.HYPER_TRACK }.sumOf { it.clips.size })
        val z = HyperKit.zoomCuts(p)
        val m = main(z)
        assertEquals(1.0, m[0].transform.scaleX, 1e-9)
        assertTrue(m[1].transform.scaleX > 1.1)
        assertEquals(z, HyperKit.zoomCuts(z))       // already zoomed: nothing changes
        val t = HyperKit.hyperTransitions(p)
        assertEquals(listOf("supernova", "lightning"), main(t).drop(1).map { it.transitionIn?.transitionId })
        val f = HyperKit.flashCuts(HyperKit.flashCuts(p))
        assertEquals(2, f.tracks.filter { it.name == HyperKit.FLASH_TRACK }.sumOf { it.clips.size })
        val s = HyperKit.sound8d(p)
        assertTrue(main(s).all { c -> c.audio.effects.count { it.effectId == "a_8d" } == 1 })
        val l = HyperKit.loudMaster(HyperKit.loudMaster(p))
        assertTrue(main(l).all { c -> c.audio.effects.count { it.effectId == "a_master" } == 1 })
        assertNotEquals(p, HyperKit.run("golden_hour", p))
        // the gestures are in the toolbar list and in Actions rapides
        for ((id, _) in HyperKit.gestures) {
            assertTrue(MontageKit.gestures.any { it.first == id })
            assertTrue(QuickActions.all.any { it.id == "m33_$id" })
            assertNotNull(MontageKit.run(id, p))
        }
    }

    @Test fun whatsNew() {
        assertEquals("3.5", Releases.current.version)
    }
}
