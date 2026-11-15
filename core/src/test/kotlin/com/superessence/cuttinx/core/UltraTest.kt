package com.superessence.cuttinx.core

import com.superessence.cuttinx.core.effects.EffectCategory
import com.superessence.cuttinx.core.effects.EffectRegistry
import com.superessence.cuttinx.core.effects.Presets
import com.superessence.cuttinx.core.effects.ShaderLibrary
import com.superessence.cuttinx.core.effects.TransitionRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** CUTTINX 2.8 : catégories « ultra technologies », transitions et filtres associés. */
class UltraTest {
    private val ultraCats = listOf(EffectCategory.ULTRA, EffectCategory.HUD, EffectCategory.AI_VISION, EffectCategory.DIMENSION)

    @Test fun everyUltraCategoryIsFilledAndDocumented() {
        for (cat in ultraCats) {
            val list = EffectRegistry.byCategory(cat)
            assertTrue("$cat : ${list.size} effets", list.size >= 8)
            for (d in list) {
                assertTrue(d.id, d.glsl.isNotBlank() && d.description.isNotBlank())
                assertTrue("${d.id} : trop de paramètres", d.params.size <= ShaderLibrary.MAX_PARAMS)
                assertEquals("${d.id} : clés en double", d.params.size, d.params.map { it.key }.toSet().size)
                for (p in d.params) assertTrue("${d.id}.${p.key}", p.default in p.min..p.max)
            }
        }
        // ids uniques dans tout le registre
        assertEquals(EffectRegistry.all.size, EffectRegistry.all.map { it.id }.toSet().size)
    }

    @Test fun ultraCategoriesAreShownRightAfterTrends() {
        val order = EffectRegistry.video().map { it.category }.distinct().sortedBy { it.ordinal }
        assertEquals(EffectCategory.TREND, order[0])
        assertEquals(ultraCats, order.subList(1, 5))
    }

    @Test fun ultraTransitionsUseRegisteredShaders() {
        for (id in listOf("teleport", "holo_glitch", "portal", "hyperspace")) {
            assertNotNull(id, TransitionRegistry[id])
            for (q in listOf(0.0, 0.25, 0.5, 0.75, 1.0)) for (top in listOf(true, false)) {
                val (a, b) = TransitionRegistry.evaluate(id, q, top)
                for (m in listOf(a, b)) {
                    assertTrue("$id alpha", m.alpha in 0.0..1.0)
                    m.shaderId?.let { assertNotNull("$id → $it", EffectRegistry[it]) }
                }
            }
        }
    }

    @Test fun ultraTechFilters() {
        val f = Presets.builtIn.filter { it.category == "Ultra tech" }
        assertTrue("${f.size} filtres Ultra tech", f.size >= 10)
        assertTrue(f.all { pr -> pr.effects.all { EffectRegistry[it.effectId] != null } })
    }
}
