package com.superessence.cuttinx.core

import com.superessence.cuttinx.core.effects.EffectRegistry
import com.superessence.cuttinx.core.effects.Presets
import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.render.FrameEvaluator
import com.superessence.cuttinx.core.smart.*
import com.superessence.cuttinx.core.timeline.TimelineEditor
import org.junit.Assert.*
import org.junit.Test

class V25Test {
    private val newEffects = listOf("lift_gamma_gain", "split_tone", "selective_hue", "bleach_bypass", "anamorphic_streak", "clarity",
        "skin_smooth", "orton", "cross_process", "dither_retro", "gate_weave", "anaglyph")

    @Test fun newEffectsAreRegisteredWithTheirParameters() {
        for (id in newEffects) {
            val d = EffectRegistry[id]
            assertNotNull(id, d)
            assertTrue(id, d!!.glsl.isNotBlank() && d.description.isNotBlank())
            assertEquals(d.params.size, FrameEvaluator.shaderParams(id, d.defaults()).size.coerceAtMost(d.params.size))
        }
    }

    @Test fun everyFilterHasACategoryAndKnownEffects() {
        val all = Presets.builtIn
        assertTrue("${all.size} filtres", all.size >= 49)
        assertEquals(all.size, all.map { it.id }.toSet().size)
        for (pr in all) {
            assertTrue("${pr.name} : ${pr.category}", pr.category in Presets.CATEGORIES)
            for (fx in pr.effects) {
                val def = EffectRegistry[fx.effectId]!!
                assertTrue("${pr.name} / ${fx.effectId} : ${fx.params.keys - def.params.map { it.key }.toSet()}", fx.params.keys.all { k -> def.params.any { it.key == k } })
            }
        }
        assertTrue(Presets.CATEGORIES.all { c -> all.count { it.category == c } >= 2 })
    }

    @Test fun newTemplatesEditTheProject() {
        var p = T.project()
        p = TimelineEditor.appendAsset(p, T.video("a", 8.0)).first
        p = TimelineEditor.appendAsset(p, T.image("i")).first
        p = TimelineEditor.appendAsset(p, T.video("b", 6.0)).first
        for (t in listOf(Template.TRAVEL, Template.WEDDING, Template.GAMING, Template.RECIPE, Template.MUSIC_CLIP, Template.RETRO_MEMORIES, Template.NIGHT, Template.PORTRAIT)) {
            val q = Templates.apply(p, t)
            assertNotEquals(t.name, p, q)
            assertNotNull(QuickActions["tpl_${t.name.lowercase()}"])
        }
    }
}
