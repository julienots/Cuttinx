package com.superessence.cuttinx.engine

import android.app.Application
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.transformer.EditedMediaItem
import androidx.test.core.app.ApplicationProvider
import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.timeline.TimelineEditor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private val GAP_ID = run { val f = EditedMediaItem::class.java.getDeclaredField("GAP_MEDIA_ID"); f.isAccessible = true; f.get(null) as String }

/** Builds real Media3 compositions from CUTTINX projects (no decoding, construction + compositor logic). */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CompositionFactoryTest {
    private val ctx: Application = ApplicationProvider.getApplicationContext()
    private val factory = CompositionFactory(ctx, MediaResolver { Uri.parse(it.uri) })

    private fun video(id: String, s: Double) = MediaAsset(id, "file:///sdcard/$id.mp4", "$id.mp4", MediaType.VIDEO, "video/mp4", (s * 1e6).toLong(), 1920, 1080, hasAudio = true, hasVideo = true)
    private fun image(id: String) = MediaAsset(id, "file:///sdcard/$id.png", "$id.png", MediaType.IMAGE, "image/png", 0, 1000, 1000)

    private fun project(): Project {
        var p = Project("p", "t", format = ProjectFormat())
        p = TimelineEditor.appendAsset(p, video("a", 5.0)).first
        p = TimelineEditor.appendAsset(p, video("b", 5.0)).first
        p = TimelineEditor.appendAsset(p, image("i")).first
        val m = p.tracks[0].sortedClips()
        p = TimelineEditor.setTransition(p, m[1].id, TransitionInstance("fade", 1_000_000, easing = Easing.Linear))
        p = TimelineEditor.updateClip(p, m[0].id) { it.copy(effects = listOf(EffectInstance("fx1", "vignette", mapOf("amount" to 0.5)))) }
        val (p2, ov) = TimelineEditor.addTrack(p, TrackKind.VIDEO); p = p2
        p = TimelineEditor.insertClip(p, ov.id, TimelineEditor.clipFromAsset(image("i"), 2_000_000).copy(transform = Transform(scaleX = 0.3, scaleY = 0.3, posX = 0.8)))
        val (p3, tt) = TimelineEditor.addTrack(p, TrackKind.TEXT); p = p3
        p = TimelineEditor.insertClip(p, tt.id, TimelineEditor.textClip("Bonjour", 500_000))
        val (p4, at) = TimelineEditor.addTrack(p, TrackKind.AUDIO, role = AudioRole.MUSIC); p = p4
        p = TimelineEditor.insertClip(p, at.id, TimelineEditor.clipFromAsset(MediaAsset("m", "file:///sdcard/m.mp3", "m.mp3", MediaType.AUDIO, "audio/mpeg", 20_000_000, hasAudio = true, hasVideo = false), 0).copy(sourceOutUs = 8_000_000))
        return TimelineEditor.addMedia(p, MediaAsset("m", "file:///sdcard/m.mp3", "m.mp3", MediaType.AUDIO, "audio/mpeg", 20_000_000, hasAudio = true, hasVideo = false))
    }

    private fun seqDuration(items: List<EditedMediaItem>): Long = items.sumOf { e ->
        val clip = e.mediaItem.clippingConfiguration
        if ((e.mediaItem.mediaId == GAP_ID) || clip.endPositionUs == C.TIME_END_OF_SOURCE) e.durationUs else clip.endPositionUs - clip.startPositionUs
    }

    @Test fun buildsSequencesInDrawOrder() {
        val p = project()
        val built = factory.build(LiveProject(p), 540, 960, 30, preview = true)
        val seqs = built.composition.sequences
        // time base + 2 main lanes (transition) + overlay lane + music
        assertEquals(5, seqs.size)
        assertEquals(p.durationUs, built.durationUs)
        // every video sequence covers the whole project duration
        for (s in seqs.dropLast(1)) assertEquals(p.durationUs.toDouble(), seqDuration(s.editedMediaItems).toDouble(), 2000.0)
        // the time base is sequence 0 (drawn on top), with video + audio tracks
        assertTrue(seqs[0].trackTypes.contains(C.TRACK_TYPE_VIDEO) && seqs[0].trackTypes.contains(C.TRACK_TYPE_AUDIO))
        // last is audio only
        assertEquals(setOf(C.TRACK_TYPE_AUDIO), seqs.last().trackTypes)
        // the clip with a vignette carries a GPU effect
        val lane0Items = seqs.flatMap { it.editedMediaItems }.filter { it.mediaItem.mediaId != GAP_ID }
        assertTrue(lane0Items.any { item -> item.effects.videoEffects.any { it is ShaderEffect } })
    }

    @Test fun compositorFollowsTimeline() {
        val p = project()
        val built = factory.build(LiveProject(p), 540, 960, 30, preview = true)
        val cs = built.composition.videoCompositorSettings
        assertEquals(androidx.media3.common.util.Size(540, 960), cs.getOutputSize(mutableListOf(androidx.media3.common.util.Size(4, 4))))
        // overlay lane (index 1, top-most video track) is hidden before 2 s and visible after
        val before = cs.getOverlaySettings(1, 1_000_000) as StaticOverlaySettings
        val after = cs.getOverlaySettings(1, 2_500_000) as StaticOverlaySettings
        assertEquals(0f, before.alphaScale, 1e-6f)
        assertEquals(1f, after.alphaScale, 1e-6f)
        assertTrue("overlay placed on the right", after.backgroundFrameAnchor.first > 0.5f)
        // during the 1 s cross-fade (starts at 4 s) exactly one of the two main lanes is partially transparent
        val a = (cs.getOverlaySettings(2, 4_500_000) as StaticOverlaySettings).alphaScale
        val b = (cs.getOverlaySettings(3, 4_500_000) as StaticOverlaySettings).alphaScale
        assertTrue("a=$a b=$b", (kotlin.math.abs(a - 0.5f) < 0.05f && b == 1f) || (kotlin.math.abs(b - 0.5f) < 0.05f && a == 1f))
        // after the transition only one main lane is visible
        val a2 = (cs.getOverlaySettings(2, 6_000_000) as StaticOverlaySettings).alphaScale
        val b2 = (cs.getOverlaySettings(3, 6_000_000) as StaticOverlaySettings).alphaScale
        assertEquals(1f, a2 + b2, 1e-6f)
    }

    @Test fun liveEditsDoNotRebuild() {
        val p = project()
        val live = LiveProject(p)
        val built = factory.build(live, 540, 960, 30, preview = true)
        val cs = built.composition.videoCompositorSettings
        val ov = p.tracks.first { it.kind == TrackKind.VIDEO && it.id != p.tracks[0].id }.clips[0]
        val k1 = StructureKey.of(p, 540, 960, 30)
        val moved = TimelineEditor.updateClip(p, ov.id) { it.copy(transform = it.transform.copy(posX = 0.2, opacity = 0.5)) }
        assertEquals("transform is a live edit", k1, StructureKey.of(moved, 540, 960, 30))
        live.project = moved
        val s = cs.getOverlaySettings(1, 2_500_000) as StaticOverlaySettings
        assertTrue(s.backgroundFrameAnchor.first < 0f)
        assertEquals(0.5f, s.alphaScale, 1e-6f)
        val split = TimelineEditor.split(p, p.tracks[0].clips[0].id, 2_000_000).first
        assertNotEquals("split is structural", k1, StructureKey.of(split, 540, 960, 30))
    }

    @Test fun missingMediaBecomesGap() {
        var p = project()
        p = TimelineEditor.updateMedia(p, "b") { it.copy(missing = true) }
        val built = factory.build(LiveProject(p), 540, 960, 30, preview = false)
        assertTrue(built.skippedAssets.contains("b"))
        for (s in built.composition.sequences.dropLast(1)) assertEquals(p.durationUs.toDouble(), seqDuration(s.editedMediaItems).toDouble(), 2000.0)
    }

    @Test fun speedCurveProducesSpeedProvider() {
        var p = project()
        val c = p.tracks[0].sortedClips()[0]
        p = TimelineEditor.setSpeedCurve(p, c.id, listOf(SpeedPoint(0.0, 0.5), SpeedPoint(1.0, 2.0)))
        val built = factory.build(LiveProject(p), 540, 960, 30, preview = false)
        val item = built.composition.sequences.flatMap { it.editedMediaItems }.first { it.mediaItem.localConfiguration?.uri?.toString()?.endsWith("a.mp4") == true }
        assertNotEquals(androidx.media3.common.audio.SpeedProvider.DEFAULT, item.speedProvider)
        assertTrue(item.speedProvider.getSpeed(0) < 1f)
    }

    @Test fun textBitmapRenders() {
        val tc = TextContent("Bonjour CUTTINX", TextStyle(strokeWidth = 4.0, backgroundColor = 0xAA000000, gradientEnabled = true))
        val b = TextRenderer.render(tc, 1080, 1920, 1.0, android.graphics.Typeface.DEFAULT)
        assertTrue(b.width > 10 && b.height > 10)
        val half = TextRenderer.render(tc, 1080, 1920, 0.5, android.graphics.Typeface.DEFAULT)
        assertEquals("box keeps its size while typing", b.width, half.width)
    }
}
