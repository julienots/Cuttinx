package com.superessence.cuttinx.core

import com.superessence.cuttinx.core.effects.EffectRegistry
import com.superessence.cuttinx.core.effects.ShaderLibrary
import com.superessence.cuttinx.core.effects.TransitionRegistry
import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.render.FrameEvaluator
import com.superessence.cuttinx.core.render.LanePlanner
import com.superessence.cuttinx.core.timeline.TimelineEditor
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test
import java.io.File

class RenderTest {
    @Test fun lanesAlternateOnTransitions() {
        var p = T.project()
        repeat(4) { p = TimelineEditor.appendAsset(p, T.video("v$it", 4.0)).first }
        val m = p.tracks.first().sortedClips()
        p = TimelineEditor.setTransition(p, m[1].id, TransitionInstance("fade", 1_000_000))
        p = TimelineEditor.setTransition(p, m[2].id, TransitionInstance("fade", 1_000_000))
        val plan = LanePlanner.plan(p)
        assertEquals(2, plan.videoLanes.size)
        for (lane in plan.videoLanes) for (i in 1 until lane.clips.size) assertTrue(lane.clips[i].startUs >= lane.clips[i - 1].endUs)
        val gaps = LanePlanner.withGaps(plan.videoLanes[1], plan.durationUs)
        assertEquals(plan.durationUs, gaps.sumOf { it.second })
        assertEquals(0L, gaps.first().first)
    }

    @Test fun crossFadeIsOrderIndependent() {
        for (p in listOf(0.0, 0.25, 0.5, 1.0)) {
            val (a1, b1) = TransitionRegistry.evaluate("fade", p, incomingOnTop = true)
            val (a2, b2) = TransitionRegistry.evaluate("fade", p, incomingOnTop = false)
            // visible amount of B over A: top alpha
            assertEquals(p, b1.alpha, 1e-9)
            assertEquals(1 - p, a2.alpha, 1e-9)
            assertEquals(1.0, a1.alpha, 1e-9); assertEquals(1.0, b2.alpha, 1e-9)
        }
        // every transition evaluates without error at the boundaries
        for (t in TransitionRegistry.all) for (p in listOf(0.0, 0.5, 1.0)) for (top in listOf(true, false)) {
            val (a, b) = TransitionRegistry.evaluate(t.id, p, top)
            assertTrue(a.alpha in 0.0..1.0 && b.alpha in 0.0..1.0)
            a.shaderId?.let { assertNotNull("shader $it", EffectRegistry[it]) }
            b.shaderId?.let { assertNotNull("shader $it", EffectRegistry[it]) }
        }
        // at the end of a transition only B remains visible (no unexpected black frame)
        val (a, b) = TransitionRegistry.evaluate("fade_black", 1.0, true)
        assertEquals(0.0, a.alpha, 1e-9); assertEquals(1.0, b.alpha, 1e-9)
    }

    @Test fun visualLayersDuringTransition() {
        var p = T.project()
        p = TimelineEditor.appendAsset(p, T.video("a", 4.0)).first
        p = TimelineEditor.appendAsset(p, T.video("b", 4.0)).first
        val m = p.tracks.first().sortedClips()
        p = TimelineEditor.setTransition(p, m[1].id, TransitionInstance("fade", 1_000_000, easing = Easing.Linear))
        val mid = FrameEvaluator.visualLayers(p, 3_500_000)
        assertEquals(2, mid.size)
        val inc = mid.first { it.clip.id == m[1].id }
        assertEquals(0.5, inc.transition!!.alpha, 1e-6)
        assertEquals(1, FrameEvaluator.visualLayers(p, 1_000_000).size)
        assertEquals(1, FrameEvaluator.visualLayers(p, 5_000_000).size)
    }

    @Test fun transformKeyframesAndPlacement() {
        val c = Clip("c", sourceOutUs = 2_000_000, keyframes = mapOf(
            Prop.POS_X to KeyframeTrack(listOf(Keyframe(0, 0.0, Easing.Linear), Keyframe(2_000_000, 1.0))),
            Prop.SCALE to KeyframeTrack(listOf(Keyframe(0, 1.0, Easing.Linear), Keyframe(2_000_000, 2.0))),
        ))
        val t = FrameEvaluator.transform(c, 1_000_000)
        assertEquals(0.5, t.posX, 1e-9)
        assertEquals(1.5, t.scaleX, 1e-9)
        // 1920x1080 media in a 1080x1920 canvas: contain -> width fits
        val pl = FrameEvaluator.placement(1920, 1080, 1080, 1920, Transform(), FrameEvaluator.transform(Clip("x"), 0))
        assertEquals(1080.0 / 1920, pl.scaleX, 1e-9)
        val fill = FrameEvaluator.placement(1920, 1080, 1080, 1920, Transform(fill = true), FrameEvaluator.transform(Clip("x"), 0))
        assertEquals(1920.0 / 1080, fill.scaleX, 1e-9)
    }

    @Test fun audioGainWithFades() {
        val c = Clip("c", sourceOutUs = 10_000_000, audio = AudioSettings(volume = 0.8, fadeInUs = 1_000_000, fadeOutUs = 2_000_000))
        assertEquals(0.0, FrameEvaluator.audioGain(c, 0), 1e-9)
        assertEquals(0.4, FrameEvaluator.audioGain(c, 500_000), 1e-9)
        assertEquals(0.8, FrameEvaluator.audioGain(c, 5_000_000), 1e-9)
        assertEquals(0.4, FrameEvaluator.audioGain(c, 9_000_000), 1e-9)
        assertEquals(0.0, FrameEvaluator.audioGain(c.copy(audio = c.audio.copy(muted = true)), 5_000_000), 1e-9)
    }

    @Test fun textAnimations() {
        val c = TimelineEditor.textClip("Salut", 0, 3_000_000).let { it.copy(text = it.text!!.copy(animIn = TextAnimation.TYPEWRITER, animInUs = 1_000_000)) }
        assertEquals(0.5, FrameEvaluator.textAnimation(c, 500_000).reveal, 1e-9)
        assertEquals(1.0, FrameEvaluator.textAnimation(c, 1_500_000).reveal, 1e-9)
        for (a in TextAnimation.values()) {
            val cc = c.copy(text = c.text!!.copy(animIn = a, animOut = a))
            val mid = FrameEvaluator.textAnimation(cc, 1_500_000)
            assertEquals("$a fully visible in the middle", 1.0, mid.alpha, 1e-6)
        }
    }

    @Test fun effectParamsKeyframed() {
        val fx = EffectInstance("e1", "blur", mapOf("radius" to 4.0))
        val c = Clip("c", sourceOutUs = 1_000_000, effects = listOf(fx), keyframes = mapOf(Prop.effect("e1", "radius") to KeyframeTrack(listOf(Keyframe(0, 0.0, Easing.Linear), Keyframe(1_000_000, 20.0)))))
        assertEquals(10.0, FrameEvaluator.effectParams(c, fx, 500_000)["radius"]!!, 1e-9)
        val arr = FrameEvaluator.shaderParams("blur", mapOf("radius" to 7.0))
        assertEquals(7f, arr[0])
    }

    /** Compiles every effect shader with the Khronos reference compiler (GLSL ES 1.00). */
    @Test fun allShadersCompile() {
        val validator = listOf("/usr/bin/glslangValidator", "/usr/local/bin/glslangValidator").map(::File).firstOrNull { it.canExecute() }
        Assume.assumeTrue("glslangValidator not installed", validator != null)
        val dir = kotlin.io.path.createTempDirectory("shaders").toFile()
        val vert = File(dir, "v.vert").apply { writeText("#version 100\n" + ShaderLibrary.VERTEX) }
        val failures = ArrayList<String>()
        fun check(f: File) {
            val proc = ProcessBuilder(validator!!.path, f.path).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            if (proc.waitFor() != 0) failures += "${f.name}:\n$out"
        }
        check(vert)
        for (e in EffectRegistry.video(includeInternal = true)) {
            val f = File(dir, "${e.id}.frag")
            f.writeText("#version 100\n" + ShaderLibrary.wrap(e.glsl))
            check(f)
            assertTrue("${e.id} uses too many params", e.params.size <= ShaderLibrary.MAX_PARAMS)
        }
        assertTrue(failures.joinToString("\n\n"), failures.isEmpty())
    }
}
