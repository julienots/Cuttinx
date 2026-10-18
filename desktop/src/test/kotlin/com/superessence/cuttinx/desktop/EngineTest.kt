package com.superessence.cuttinx.desktop

import com.superessence.cuttinx.core.effects.EffectRegistry
import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.resources.Synth
import com.superessence.cuttinx.core.timeline.TimelineEditor
import com.superessence.cuttinx.desktop.engine.*
import org.jetbrains.skia.Surface
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

class EngineTest {
    @Test fun everyEffectRunsOnThePc() {
        val failed = EffectRegistry.all.filter { it.glsl.isNotBlank() }.mapNotNull { d -> Sksl.error(d.id)?.let { "${d.id}: $it" } }
        assertTrue(failed.joinToString("\n"), failed.isEmpty())
    }

    private fun pixel(s: Surface, x: Int, y: Int): Int {
        val bmp = org.jetbrains.skia.Bitmap().apply { allocN32Pixels(s.width, s.height) }
        s.readPixels(bmp, 0, 0)
        return bmp.getColor(x, y)
    }

    private fun sample(): Project {
        var p = Project("t", "Test", format = ProjectFormat.forAspect(AspectPreset.PORTRAIT_9_16, 360))
        val (p1, main) = TimelineEditor.mainTrack(p); p = p1
        p = TimelineEditor.insertClip(p, main.id, TimelineEditor.colorClip(0xFFFF0000, 0, 1_000_000))
        val b = TimelineEditor.colorClip(0xFF0000FF, 1_000_000, 1_000_000)
        p = TimelineEditor.insertClip(p, main.id, b)
        p = TimelineEditor.setTransition(p, b.id, TransitionInstance("heart_wipe", 400_000))
        val (p2, tt) = TimelineEditor.addTrack(p, TrackKind.TEXT); p = p2
        return TimelineEditor.insertClip(p, tt.id, TimelineEditor.textClip("Salut", 0, 1_500_000))
    }

    @Test fun compositorDrawsClipsTransitionsAndTexts() {
        val p = sample()
        val r = PcRenderer(DecoderPool(640), PcText())
        val s = Surface.makeRasterN32Premul(180, 320)
        r.render(p, 200_000, s)
        assertEquals(0xFFFF0000.toInt(), pixel(s, 5, 5))
        r.render(p, 1_500_000, s)
        assertEquals(0xFF0000FF.toInt(), pixel(s, 5, 5))
        // the title is drawn in the middle of the frame (after its 0.4 s fade-in)
        r.render(p, 800_000, s)
        val bmp = org.jetbrains.skia.Bitmap().apply { allocN32Pixels(180, 320) }; s.readPixels(bmp, 0, 0)
        assertTrue((40..140).any { x -> (150..170).any { y -> bmp.getColor(x, y).let { c -> (c shr 8 and 0xFF) > 200 && (c and 0xFF) > 200 } } })
    }

    @Test fun exportsAPlayableVideoWithSound() {
        val dir = Files.createTempDirectory("cx").toFile()
        val wav = File(dir, "s.wav"); val a = Synth.sfx("whoosh"); PcMedia.writeWav(wav, a.samples, a.sampleRate, a.channels)
        var p = sample()
        val asset = PcMedia.probe(wav)
        assertTrue(asset.hasAudio)
        val (p1, at) = TimelineEditor.addTrack(p, TrackKind.AUDIO); p = TimelineEditor.insertClip(TimelineEditor.addMedia(p1, asset), at.id, TimelineEditor.clipFromAsset(asset, 0))
        val out = File(dir, "o.mp4")
        val res = PcExporter(PcText()).export(p, out, 360, 25, AtomicBoolean(false)) { }
        val probed = PcMedia.probe(res.file)
        assertEquals(MediaType.VIDEO, probed.type)
        assertTrue(probed.hasAudio)
        // the transition overlaps the two clips by 0.4 s
        assertEquals(1_600_000.0, probed.durationUs.toDouble(), 150_000.0)
        // decode it back: the second half is blue
        val v = VideoDecoder(out, 640)
        val img = v.frameAt(1_450_000)!!
        val bmp = org.jetbrains.skia.Bitmap.makeFromImage(img)
        val c = bmp.getColor(4, 4)
        assertTrue("blue expected, got %08x".format(c), (c and 0xFF) > 180 && ((c shr 16) and 0xFF) < 60)
        v.close(); dir.deleteRecursively()
    }
}
