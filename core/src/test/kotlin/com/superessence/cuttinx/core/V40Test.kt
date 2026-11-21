package com.superessence.cuttinx.core

import com.superessence.cuttinx.core.create.Releases
import com.superessence.cuttinx.core.create.ScriptOptions
import com.superessence.cuttinx.core.create.ScriptStudio
import com.superessence.cuttinx.core.create.ScriptStyle
import com.superessence.cuttinx.core.effects.EffectCategory
import com.superessence.cuttinx.core.effects.EffectRegistry
import com.superessence.cuttinx.core.effects.ShaderLibrary
import com.superessence.cuttinx.core.effects.TransitionRegistry
import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.smart.*
import com.superessence.cuttinx.core.timeline.TimelineEditor
import org.junit.Assert.*
import org.junit.Test

/** CUTTINX 4.0 : Studio Script, fonds animés, styles de texte et modèles. */
class V40Test {
    private val script = """
        Trois secrets pour réussir
        Le premier, c'est de commencer avant d'être prêt. Personne ne l'est vraiment.
        # Le deuxième
        Entoure-toi de gens qui te tirent vers le haut, qui croient en toi même quand tu doutes, et qui te disent la vérité quand tu en as besoin pour avancer encore plus loin.
        Et le troisième ? Ne jamais abandonner !
    """.trimIndent()

    private fun base(aspect: AspectPreset = AspectPreset.PORTRAIT_9_16) = Project("p", "", format = ProjectFormat.forAspect(aspect))

    @Test fun backdropsFamily() {
        val list = EffectRegistry.byCategory(EffectCategory.BACKDROP)
        assertTrue("${list.size} fonds", list.size >= 10)
        for (d in list) {
            assertTrue(d.id, d.animated && d.description.isNotBlank() && d.params.size <= ShaderLibrary.MAX_PARAMS)
            assertEquals(listOf("hue1", "hue2", "speed", "mix"), d.params.take(4).map { it.key })
        }
    }

    @Test fun scriptIsSplitIntoReadableScreens() {
        val s = ScriptStudio.scenes(script, ScriptStyle.MOTIVATION)
        assertTrue(s.first().title)
        assertEquals("Trois secrets pour réussir", s.first().text)
        assertTrue(s.any { it.chapter && it.text == "Le deuxième" })
        assertTrue("phrases séparées", s.any { it.text == "Personne ne l'est vraiment." })
        assertTrue("phrases longues coupées", s.all { it.words <= 14 })
        assertTrue(s.all { it.durationUs in ScriptStudio.MIN_SCENE_US..ScriptStudio.MAX_SCENE_US })
        // longer sentences stay longer on screen
        val short = s.first { it.text == "Personne ne l'est vraiment." }
        val long = s.maxBy { it.words }
        assertTrue(long.durationUs > short.durationUs)
    }

    @Test fun lyricsFollowTheBars() {
        val s = ScriptStudio.scenes("Je danse sous la pluie\nEt la ville s'allume pour moi ce soir", ScriptStyle.LYRICS, 120)
        assertEquals(2, s.size)
        assertTrue(s.all { it.durationUs % 2_000_000L == 0L })     // 1 bar = 2 s at 120 BPM
        assertEquals(4_000_000L, s[1].durationUs)                  // 8 words: 2 bars
    }

    @Test fun buildsACompleteEditableVideo() {
        val music = T.audio("m", 20.0)
        for (style in ScriptStyle.entries) {
            val p = ScriptStudio.build(base(), script, style, ScriptOptions(music = music))
            val scenes = ScriptStudio.scenes(script, style)
            val main = SmartTools.mainTrack(p)!!.sortedClips()
            assertEquals("$style : un fond par écran + la fin", scenes.size + 1, main.size)
            main.forEach { c -> c.effects.forEach { e -> assertNotNull(e.effectId, EffectRegistry[e.effectId]) } }
            main.drop(1).forEach { c -> assertNotNull("$style transition", c.transitionIn); assertNotNull(TransitionRegistry[c.transitionIn!!.transitionId]) }
            val texts = p.tracks.filter { it.kind == TrackKind.TEXT && it.name.startsWith("Script") }.flatMap { it.clips }.sortedBy { it.startUs }
            assertEquals(scenes.map { it.text }, texts.map { it.text!!.text })
            // every text is inside its own scene, never over a transition
            texts.forEachIndexed { i, t -> assertTrue(t.startUs >= main[i].startUs + (main[i].transitionIn?.durationUs ?: 0) / 2); assertTrue(t.endUs <= main[i + 1].startUs + main[i + 1].transitionIn!!.durationUs / 2) }
            // the music covers the whole video and fades out
            val mus = p.tracks.first { it.audioRole == AudioRole.MUSIC }.sortedClips()
            assertEquals(p.durationUs, mus.last().endUs)
            assertTrue(mus.last().audio.fadeOutUs > 0)
            assertTrue(p.tracks.any { it.clips.any { c -> c.name == "Carte de fin" } })
            assertTrue(p.markers.size >= 2)
            assertTrue(p.durationUs in 15_000_000..90_000_000)
        }
    }

    @Test fun photosBecomeBackgrounds() {
        val photos = listOf(T.image("i1"), T.image("i2"))
        val p = ScriptStudio.build(base(AspectPreset.LANDSCAPE_16_9), script, ScriptStyle.STORY, ScriptOptions(media = photos, endCard = false, progressBar = false))
        val main = SmartTools.mainTrack(p)!!.sortedClips()
        assertEquals(listOf("i1", "i2", "i1"), main.take(3).map { it.assetId })
        assertTrue(main.all { Prop.SCALE in it.keyframes })
        assertTrue(p.media.any { it.id == "i1" })
    }

    @Test fun emptyScriptIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { ScriptStudio.build(base(), "   \n  ", ScriptStyle.FACTS) }
    }

    @Test fun newTextLooksAndTemplates() {
        for (lk in listOf(TextLook.HOLO, TextLook.GLITCH, TextLook.BUBBLE, TextLook.CREDITS, TextLook.Y2K, TextLook.GOLD, TextLook.TERMINAL, TextLook.EDITORIAL)) {
            val st = Titles.style(lk)
            assertTrue(lk.name, st.fontFamily.startsWith("asset:fonts/"))
        }
        var p = T.project()
        p = TimelineEditor.appendAsset(p, T.video("a", 6.0)).first
        p = TimelineEditor.appendAsset(p, T.video("b", 6.0)).first
        p = TimelineEditor.appendAsset(p, T.audio("m", 12.0)).first
        for (t in listOf(Template.COSMIC, Template.SCI_FI, Template.LOFI, Template.STORYTIME, Template.CAR, Template.NATURE, Template.CONCERT, Template.RECAP)) {
            val q = Templates.apply(p, t)
            assertNotEquals(t.name, p, q)
        }
    }

    @Test fun whatsNew() {
        assertEquals("4.0", Releases.current.version)
    }
}
