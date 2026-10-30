package com.superessence.cuttinx.core

import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.smart.*
import com.superessence.cuttinx.core.timeline.TimelineEditor
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class SmartTest {
    /** 9:16 project: three 10 s talking videos, background music, one long subtitle. */
    private fun montage(): Project {
        var p = T.project().copy(format = ProjectFormat.forAspect(AspectPreset.PORTRAIT_9_16))
        for (i in 1..3) p = TimelineEditor.appendAsset(p, T.video("v$i", 10.0, 1080, 1920)).first
        p = TimelineEditor.appendAsset(p, T.audio("music", 40.0)).first
        val (p2, st) = TimelineEditor.ensureTrack(p, TrackKind.SUBTITLE)
        val sub = TimelineEditor.textClip("Aujourd'hui je vous montre comment j'ai construit cette cabane dans la forêt en seulement trois jours avec des palettes récupérées", 2_000_000, 3_000_000, kind = ClipKind.SUBTITLE)
            .let { it.copy(transform = it.transform.copy(posY = 0.93)) }
        return p2.copy(tracks = p2.tracks.map { if (it.id == st.id) it.copy(clips = listOf(sub)) else it })
    }

    @Test fun fitToDurationKeepsTheMiddleOfEveryShot() {
        val p = montage()
        val out = SmartTools.fitToDuration(p, 15_000_000)
        val main = SmartTools.mainTrack(out)!!.sortedClips()
        assertTrue(abs(main.last().endUs - 15_000_000) < 50_000)
        // each shot lost the same amount on both sides
        main.forEach { c -> assertTrue(abs((10_000_000 - c.sourceOutUs) - c.sourceInUs) <= 2) }
        // music cut at the new end with a fade, subtitle still inside the video
        val music = out.tracks.first { it.audioRole == AudioRole.MUSIC }.clips.single()
        assertTrue(music.endUs <= 15_000_000 && music.audio.fadeOutUs >= 1_000_000)
        assertTrue(out.tracks.first { it.kind == TrackKind.SUBTITLE }.clips.all { it.endUs <= 15_000_000 })
        // already short enough: unchanged
        assertSame(out, SmartTools.fitToDuration(out, 20_000_000))
    }

    @Test fun duckingLowersTheMusicOnlyUnderTheVoice() {
        var p = montage()
        // mute the 2nd video: nobody speaks between 10 and 20 s
        val v2 = SmartTools.mainTrack(p)!!.sortedClips()[1]
        p = TimelineEditor.updateClip(p, v2.id) { it.copy(audio = it.audio.copy(muted = true)) }
        val out = SmartTools.autoDuck(p, duckTo = 0.25)
        val music = out.tracks.first { it.audioRole == AudioRole.MUSIC }.clips.single()
        val kf = music.keyframes[Prop.VOLUME]!!
        fun at(s: Double) = com.superessence.cuttinx.core.anim.KeyframeEvaluator.evaluate(kf, (s * 1e6).toLong(), 1.0)
        assertEquals(0.25, at(5.0), 0.01)
        assertEquals(1.0, at(15.0), 0.01)
        assertEquals(0.25, at(25.0), 0.01)
        assertEquals(1.0, at(35.0), 0.01)
    }

    @Test fun subtitlesAreSplitIntoReadableBlocksOutOfTheButtons() {
        val p = SmartTools.safeZones(SmartTools.readableSubtitles(montage()), Platform.TIKTOK)
        val subs = p.tracks.first { it.kind == TrackKind.SUBTITLE }.sortedClips()
        assertTrue(subs.size >= 3)
        subs.forEach { c ->
            val lines = c.text!!.text.lines()
            assertTrue(lines.size <= 2 && lines.all { it.length <= 32 })
            assertTrue(c.transform.posY <= 1 - Platform.TIKTOK.safeBottom)
        }
        // same total time, back to back
        assertEquals(2_000_000, subs.first().startUs)
        assertEquals(5_000_000, subs.last().endUs)
        subs.zipWithNext().forEach { (a, b) -> assertEquals(a.endUs, b.startUs) }
    }

    @Test fun qualityCoachFindsProblemsAndFixesThem() {
        var p = montage()
        // a 1-frame fragment left by a cut
        val main = SmartTools.mainTrack(p)!!
        val v1 = main.sortedClips()[0]
        val frag = v1.copy(id = "frag", startUs = 9_980_000, sourceInUs = 9_980_000, sourceOutUs = 10_000_000)
        p = p.copy(tracks = p.tracks.map { t -> if (t.id != main.id) t else t.copy(clips = t.clips.map { if (it.id == v1.id) it.copy(sourceOutUs = 9_980_000) else it } + frag) })
        val before = QualityCoach.analyze(p, Platform.TIKTOK)
        val fixes = before.items.mapNotNull { it.fix }.toSet()
        assertTrue(QuickFix.HOOK in fixes && QuickFix.DUCK in fixes && QuickFix.CLEAN in fixes && QuickFix.SAFE_ZONES in fixes && QuickFix.READABLE in fixes)
        val fixed = QualityCoach.fixAll(p, Platform.TIKTOK)
        val after = QualityCoach.analyze(fixed, Platform.TIKTOK)
        assertTrue("${before.score} -> ${after.score}", after.score > before.score + 25)
        assertTrue(after.items.none { it.severity == Severity.CRITICAL })
    }

    @Test fun directorUnderstandsPlainFrench() {
        var p = montage()
        p = TimelineEditor.changeFormat(p, ProjectFormat.forAspect(AspectPreset.LANDSCAPE_16_9))
        val plan = SmartDirector.plan("Optimise pour TikTok en 20 secondes, look cinéma, transitions glitch, baisse la musique quand je parle, accroche \"Regarde ça\" et boucle parfaite", p)
        val labels = plan.steps.joinToString("\n") { it.label }
        for (w in listOf("TikTok", "00:20", "Blockbuster", "Glitch", "Ducking", "Regarde ça", "Boucle")) assertTrue("$w in\n$labels", w in labels)
        val out = plan.apply(p)
        assertEquals(AspectPreset.PORTRAIT_9_16, out.format.aspect)
        val main = SmartTools.mainTrack(out)!!.sortedClips()
        assertTrue(main.any { it.transitionIn?.transitionId == "glitch" })
        assertTrue(main.any { c -> c.effects.any { it.effectId == "teal_orange" } })
        assertTrue(out.tracks.any { t -> t.kind == TrackKind.TEXT && t.clips.any { it.text?.text?.contains("Regarde", true) == true } })
        assertEquals("Boucle", main.last().name)
        assertTrue(out.durationUs <= 21_500_000)
        // nonsense is reported, not applied
        val none = SmartDirector.plan("blablabla patati", p)
        assertTrue(none.steps.isEmpty() && none.notUnderstood.isNotEmpty())
    }

    @Test fun teaserLoopAndRampsAreRegularEditableClips() {
        val p = montage()
        val t = SmartTools.teaser(p)
        val main = SmartTools.mainTrack(t)!!.sortedClips()
        assertEquals("Teaser", main.first().name)
        assertEquals(4, main.size)
        assertEquals("flash", main[1].transitionIn?.transitionId)
        val ramp = SmartTools.speedRamp(p, main[1].id.let { SmartTools.mainTrack(p)!!.clips[0].id }, RampPreset.HERO)
        assertTrue(SmartTools.mainTrack(ramp)!!.sortedClips()[0].speedCurve.isNotEmpty())
        val tempo = SmartTools.tempoCut(p, SmartTools.beatsFromBpm(p, 120.0), everyBeats = 4)
        val shots = SmartTools.mainTrack(tempo)!!.sortedClips()
        assertTrue(shots.size > 10)
        assertTrue(shots.zipWithNext().any { (a, b) -> a.transform.scaleX != b.transform.scaleX })
        assertTrue(SmartTools.chapters(p).startsWith("00:00 Introduction"))
    }
}
