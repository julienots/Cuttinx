package com.superessence.cuttinx.core

import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.render.FrameEvaluator
import com.superessence.cuttinx.core.smart.*
import com.superessence.cuttinx.core.timeline.TimelineEditor
import org.junit.Assert.*
import org.junit.Test

class V2Test {
    private fun main(p: Project) = SmartTools.mainTrack(p)!!.sortedClips()

    private fun two(vertical: Boolean = true): Project {
        var p = T.project().copy(format = ProjectFormat.forAspect(if (vertical) AspectPreset.PORTRAIT_9_16 else AspectPreset.LANDSCAPE_16_9))
        p = TimelineEditor.appendAsset(p, T.video("a", 10.0)).first
        p = TimelineEditor.appendAsset(p, T.video("b", 6.0)).first
        return p
    }

    @Test fun cameraMovesAreRealKeyframesThatTheRendererUses() {
        val p = two()
        val a = main(p)[0]
        val z = Animator.camera(p, listOf(a.id), CameraMove.ZOOM_IN)
        val c = z.findClip(a.id)!!.second
        assertEquals(1.0, FrameEvaluator.transform(c, 0).scaleX, 1e-6)
        assertEquals(1.22, FrameEvaluator.transform(c, c.durationUs).scaleX, 1e-6)
        val pan = Animator.camera(p, listOf(a.id), CameraMove.PAN_RIGHT).findClip(a.id)!!.second
        assertTrue(FrameEvaluator.transform(pan, 0).posX > FrameEvaluator.transform(pan, pan.durationUs).posX)
        // a second move replaces the first one
        val again = Animator.camera(z, listOf(a.id), CameraMove.TILT_UP).findClip(a.id)!!.second
        assertNotNull(again.keyframes[Prop.POS_Y]); assertEquals(1.25, again.keyframes[Prop.SCALE]!!.keyframes.single().value, 1e-9)
        assertTrue(Animator.clear(z, listOf(a.id)).findClip(a.id)!!.second.keyframes.isEmpty())
    }

    @Test fun zoomHitPulseAndShake() {
        val p = two()
        val a = main(p)[0]
        val hit = Animator.zoomHit(p, listOf(a.id), 4_000_000).findClip(a.id)!!.second
        assertEquals(1.3, FrameEvaluator.transform(hit, 4_200_000).scaleX, 1e-6)
        assertEquals(1.0, FrameEvaluator.transform(hit, 6_000_000).scaleX, 1e-6)
        val pulse = Animator.pulseOnBeats(p, listOf(a.id), 120.0).findClip(a.id)!!.second
        assertEquals(40, pulse.keyframes[Prop.SCALE]!!.keyframes.size)           // 20 beats in 10 s, peak + rest
        val sh = Animator.shakeAt(p, listOf(a.id), 2_000_000).findClip(a.id)!!.second
        assertNotEquals(0.5, FrameEvaluator.transform(sh, 2_100_000).posX, 1e-4)
        assertEquals(0.5, FrameEvaluator.transform(sh, 3_000_000).posX, 1e-9)
        assertEquals(p, Animator.zoomHit(p, listOf(a.id), 20_000_000))
    }

    @Test fun textLooksAndTitlesLandOnTheirOwnTracks() {
        var p = two()
        p = Titles.countdown(p, 1_000_000)
        val cd = p.tracks.first { it.name == "Compte à rebours" }.sortedClips()
        assertEquals(listOf("3", "2", "1"), cd.map { it.text!!.text }); assertEquals(listOf(1_000_000L, 2_000_000L, 3_000_000L), cd.map { it.startUs })
        p = Titles.ranking(p)
        assertEquals(listOf("#2", "#1"), p.tracks.first { it.name == "Classement" }.sortedClips().map { it.text!!.text })
        p = Titles.lowerThird(p, 500_000)
        p = Titles.endCard(p)
        val end = p.tracks.filter { it.name.startsWith("Fin") }.flatMap { it.clips }.sortedBy { it.startUs }
        assertEquals(2, end.size); assertEquals(13_000_000, end[0].startUs)
        p = Titles.applyLook(p, emptyList(), TextLook.NEON)
        assertTrue(p.allClips.filter { it.text != null }.all { it.text!!.style.fontFamily.contains("Righteous") })
        assertEquals(16_000_000, p.durationUs)                                   // titles never lengthen the video
        // a short video gets a card to hold its end screen
        val short = TimelineEditor.appendAsset(T.project(), T.video("s", 1.0)).first
        assertEquals(4_000_000, Titles.endCard(short).durationUs)
    }

    @Test fun splitScreenStacksTheShotsInBoxes() {
        val p = two(vertical = true)
        val (a, b) = main(p)
        val s = Layouts.splitScreen(p, listOf(a.id, b.id))
        val a2 = s.findClip(a.id)!!.second; val (tb, b2) = s.findClip(b.id)!!
        assertNotEquals(SmartTools.mainTrack(s)!!.id, tb.id)
        assertEquals(a2.startUs, b2.startUs)
        assertEquals(0.25, a2.transform.posY, 1e-9); assertEquals(0.75, b2.transform.posY, 1e-9)
        // a 16:9 video in a 9:16 canvas fits the full width: the half-height box does not shrink it
        assertEquals(1.0, a2.transform.scaleX, 1e-6)
        val w0 = two(vertical = false)
        val wide = Layouts.splitScreen(w0, main(w0).map { it.id })
        assertTrue(wide.allClips.all { it.transform.scaleX <= 0.5 + 1e-6 })
    }

    @Test fun pipLiftsTheShotAndBlurredBackgroundDoublesItSilently() {
        val p = two()
        val (a, b) = main(p)
        val pip = Layouts.pictureInPicture(p, b.id, Corner.TOP_RIGHT)
        val (t, c) = pip.findClip(b.id)!!
        assertEquals("Incrustation", t.name); assertEquals(0.8, c.transform.posX, 1e-9)
        assertEquals(1, main(pip).size)
        val bg = Layouts.blurredBackground(p)
        val top = bg.tracks.first { it.name == "Premier plan net" }
        assertEquals(2, top.clips.size)
        assertTrue(top.clips.all { it.audio.muted && !it.transform.fill })
        assertTrue(main(bg).all { it.transform.fill && it.effects.any { e -> e.effectId == "blur" } })
        assertSame(bg, Layouts.blurredBackground(bg))
        assertTrue(Layouts.fullScreen(bg).tracks.none { it.name == "Premier plan net" })
        assertEquals(listOf(a.id, b.id), main(bg).map { it.id })
    }

    @Test fun soundTools() {
        var p = two()
        p = TimelineEditor.appendAsset(p, T.audio("m", 8.0)).first
        p = TimelineEditor.appendAsset(p, T.audio("n", 8.0)).first
        val vo = AudioPro.voices(p, emptyList())
        assertEquals(2, vo.size)
        val pod = AudioPro.podcastVoice(p, vo)
        assertTrue(pod.findClip(vo[0])!!.second.audio.effects.map { it.effectId }.containsAll(listOf("a_compressor", "a_deesser")))
        assertTrue(pod.findClip(vo[0])!!.second.audio.eqEnabled)
        val twice = AudioPro.podcastVoice(pod, vo)
        assertEquals(pod.findClip(vo[0])!!.second.audio.effects.size, twice.findClip(vo[0])!!.second.audio.effects.size)  // no stacking
        val m = AudioPro.level(p, AudioPro.music(p), -12.0)
        assertEquals(0.251, m.findClip(AudioPro.music(p)[0])!!.second.audio.volume, 1e-3)
        val x = AudioPro.crossfadeMusic(p)
        val music = x.tracks.filter { it.kind == TrackKind.AUDIO }.flatMap { it.clips }.sortedBy { it.startUs }
        assertEquals(2, music.size); assertEquals(6_500_000, music[1].startUs)
        assertNotEquals(x.findClip(music[0].id)!!.first.id, x.findClip(music[1].id)!!.first.id)
        assertTrue(AudioPro.muteVideos(p).tracks.first { it.kind == TrackKind.VIDEO }.clips.all { it.audio.muted })
    }

    @Test fun organiserAndTemplates() {
        var p = two()
        p = Organizer.markersEvery(p, 5_000_000)
        assertEquals(listOf(5_000_000L, 10_000_000L, 15_000_000L), p.markers.map { it.timeUs })
        p = Organizer.numberShots(p)
        assertEquals(listOf("Plan 1", "Plan 2"), main(p).map { it.name })
        assertEquals(listOf(1, 1), main(Organizer.labelByType(p)).map { it.label })
        val tr = SmartTools.transitionsEverywhere(p, "fade")
        assertTrue(main(tr)[1].transitionIn != null)
        val cleared = Organizer.removeTransitions(tr)
        assertNull(main(cleared)[1].transitionIn); assertEquals(16_000_000, cleared.durationUs)
        for (t in Template.entries) {
            val out = Templates.apply(p, t)
            assertNotSame("${t.name} n'a rien fait", p, out)
            assertTrue(t.name, out.tracks.all { tk -> tk.clips.all { it.durationUs > 0 } })
        }
    }
}
