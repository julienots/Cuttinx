package com.superessence.cuttinx.core

import com.superessence.cuttinx.core.analysis.Frame
import com.superessence.cuttinx.core.analysis.FrameStats
import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.smart.*
import com.superessence.cuttinx.core.timeline.TimelineEditor
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

class HyperDirectorTest {
    /** A smooth random texture, so shifted crops can be matched. */
    private val world: Pair<Int, IntArray> by lazy {
        val w = 260; val h = 200; val r = java.util.Random(7)
        val v = DoubleArray(w * h) { r.nextDouble() }
        repeat(3) { val c = v.copyOf(); for (y in 1 until h - 1) for (x in 1 until w - 1) v[y * w + x] = (c[y * w + x] * 4 + c[y * w + x - 1] + c[y * w + x + 1] + c[(y - 1) * w + x] + c[(y + 1) * w + x]) / 8 }
        val lo = v.min(); val hi = v.max()
        w to IntArray(w * h) { val g = ((v[it] - lo) / (hi - lo) * 255).toInt(); (0xFF shl 24) or (g shl 16) or (g shl 8) or g }
    }

    private fun crop(ox: Int, oy: Int, t: Long, w: Int = 64, h: Int = 48): Frame {
        val (ww, px) = world
        return Frame(t, w, h, IntArray(w * h) { i -> px[(oy + i / w) * ww + ox + i % w] })
    }

    @Test fun cameraShiftIsMeasured() {
        // the camera moves right by 3 px per frame: the picture moves left
        val frames = List(8) { crop(60 + it * 3, 70, it * 100_000L) }
        val steps = CameraMotion.estimate(frames)
        assertEquals(7, steps.size)
        for (s in steps) {
            assertEquals(-3.0 / 64, s.dx, 0.6 / 64)
            assertEquals(0.0, s.dy, 0.6 / 48)
            assertTrue(s.confidence > 0.6)
        }
        val v = CameraMotion.velocity(steps, 0, 700_000)
        assertTrue(v.first < -0.3)
        val dir = CameraMotion.cutDirection(v, v)!!
        assertTrue("direction $dir", abs(dir - 180.0) < 10)  // picture moving left
        assertNull(CameraMotion.cutDirection(0.0 to 0.0, 0.01 to 0.0))
    }

    @Test fun stabilizationCompensatesShakeNotPans() {
        // slow pan + strong jitter
        val r = java.util.Random(3)
        val xs = List(40) { (40 + it * 1.5 + (r.nextDouble() - 0.5) * 8).roundToInt() }
        val ys = List(40) { (60 + (r.nextDouble() - 0.5) * 8).roundToInt() }
        val frames = List(40) { crop(xs[it], ys[it], it * 33_333L) }
        val steps = CameraMotion.estimate(frames)
        val st = CameraMotion.stabilization(steps, 0, strength = 0.9)
        assertTrue("shaky ${st.shakiness}", st.shakiness > 0.01)
        assertTrue(st.zoom in 1.01..1.25)
        // corrected path = raw + correction is much smoother than raw
        val raw = CameraMotion.path(0, steps)
        val corrected = raw.indices.map { i -> raw[i].x + st.keysX.first { it.first == raw[i].timeUs }.second }
        fun jitter(v: List<Double>) = (2 until v.size).sumOf { abs(v[it] - 2 * v[it - 1] + v[it - 2]) }
        assertTrue("${jitter(corrected)} vs ${jitter(raw.map { it.x })}", jitter(corrected) < jitter(raw.map { it.x }) * 0.5)
        // a steady shot is not touched
        val calm = CameraMotion.stabilization(CameraMotion.estimate(List(20) { crop(80, 80, it * 33_333L) }), 0)
        assertTrue(calm.shakiness < 0.002)
        assertEquals(1.0, calm.zoom, 1e-6)

        var p = T.project()
        p = TimelineEditor.appendAsset(p, T.video("a", 2.0)).first
        val id = SmartTools.mainTrack(p)!!.clips.first().id
        val q = CameraMotion.applyStabilization(p, id, st)
        val c = q.findClip(id)!!.second
        assertTrue(c.transform.fill && c.transform.scaleX > 1.0)
        assertTrue(c.keyframes[Prop.POS_X]!!.keyframes.size > 10)
    }

    // ------------------------------------------------------------------ the director

    private fun stats(durS: Double, seed: Int, peakAtS: Double): List<FrameStats> {
        val n = (durS * 10).toInt()
        return List(n) { i ->
            val t = i / 10.0
            val near = kotlin.math.exp(-((t - peakAtS) * (t - peakAtS)) / 2.0)
            FrameStats((t * 1e6).toLong(), 0.45 + 0.05 * sin(i + seed.toDouble()), 0.3 + 0.4 * near, 0.3, 0.02 + 0.2 * near, 0.0)
        }
    }

    private fun material(): Pair<Project, List<HyperDirector.Take>> {
        var p = T.project()
        p = TimelineEditor.appendAsset(p, T.video("a", 20.0)).first
        p = TimelineEditor.appendAsset(p, T.video("b", 15.0)).first
        p = TimelineEditor.appendAsset(p, T.video("c", 25.0)).first
        val clips = SmartTools.mainTrack(p)!!.sortedClips()
        val takes = clips.mapIndexed { i, c -> HyperDirector.Take(c.id, stats(c.sourceDurationUs / 1e6, i, 5.0 + i * 3), look = Magic.Look(0.4 + i * 0.1, 0.45, 0.5 - i * 0.05, 0.25)) }
        return p to takes
    }

    private fun music(bpm: Double, seconds: Double, dropAtS: Double): HyperDirector.Music {
        val beat = (60_000_000 / bpm).toLong()
        val beats = generateSequence(0L) { it + beat }.takeWhile { it < seconds * 1e6 }.toList()
        val energy = DoubleArray((seconds * 20).toInt()) { i -> if (i / 20.0 < dropAtS) -30.0 + i / 20.0 else -8.0 }
        return HyperDirector.Music(bpm, beats, beats.filterIndexed { i, _ -> i % 4 == 0 }, energy)
    }

    @Test fun musicStructureFindsTheDrop() {
        val m = music(120.0, 40.0, 16.0)
        val bars = HyperDirector.bars(m, 30_000_000)
        assertTrue(bars.size >= 14)
        assertEquals(16_000_000L, HyperDirector.findDrop(bars, 30_000_000))
        val slots = HyperDirector.structure(m, 30_000_000, DirectorStyle.VIRAL, 1e6 / 30)
        assertEquals("Accroche", slots.first().section)
        assertEquals(0L, slots.first().startUs)
        // contiguous, every cut on a beat (frame accuracy)
        for (i in 1 until slots.size) {
            assertEquals(slots[i - 1].endUs, slots[i].startUs)
            assertTrue(m.beatsUs.any { abs(it - slots[i].startUs) <= 34_000 })
        }
        // shorter shots after the drop than before
        val before = slots.filter { it.startUs in 2_000_000 until 16_000_000 }.map { it.durationUs }.average()
        val after = slots.filter { it.startUs >= 16_000_000 }.map { it.durationUs }.average()
        assertTrue("$before / $after", after < before)
        assertTrue(slots.any { it.section == "Climax" })
        assertTrue(slots.any { it.slowMotion })
    }

    @Test fun directorWritesAFullEdit() {
        val (p, takes) = material()
        for (style in DirectorStyle.entries) {
            val r = HyperDirector.direct(p, takes, music(120.0, 60.0, 14.0), style, 30_000_000)
            val main = SmartTools.mainTrack(r.project)!!.sortedClips().filter { it.kind == ClipKind.MEDIA }
            assertTrue("$style: ${main.size} plans", main.size >= 8)
            assertTrue(r.project.durationUs in 20_000_000..32_000_000)
            // never the same moment twice
            val byTake = r.shots.groupBy { it.clipId }
            for ((_, l) in byTake) { val s = l.sortedBy { it.sourceInUs }; for (i in 1 until s.size) assertTrue(s[i].sourceInUs >= s[i - 1].sourceOutUs) }
            // all takes used, no hole on the main track
            assertEquals(3, byTake.size)
            for (i in 1 until main.size) assertTrue(abs(main[i].startUs - main[i - 1].endUs) <= 34_000)
            assertTrue(r.report.size >= 4)
            if (style == DirectorStyle.STORY) for ((_, l) in r.shots.drop(1).groupBy { it.clipId }) assertEquals(l.map { it.sourceInUs }, l.map { it.sourceInUs }.sorted())
        }
        // the hook comes from around a peak of interest
        val r = HyperDirector.direct(p, takes, music(120.0, 60.0, 14.0), DirectorStyle.VIRAL, 30_000_000)
        val hook = r.shots.first()
        val peak = 5.0 + takes.indexOfFirst { it.clipId == hook.clipId } * 3
        assertTrue("hook at ${hook.sourceInUs}", abs((hook.sourceInUs + hook.sourceOutUs) / 2e6 - peak) < 2.5)
        assertNotNull(r.dropUs)
        // works without music (narrative arc) and leaves the project alone without videos
        val noMusic = HyperDirector.direct(p, takes, null, DirectorStyle.CINEMATIC, 20_000_000)
        assertTrue(noMusic.shots.size >= 4)
        val empty = HyperDirector.direct(T.project(), emptyList(), null, DirectorStyle.VIRAL, 20_000_000)
        assertTrue(empty.shots.isEmpty())
    }
}
