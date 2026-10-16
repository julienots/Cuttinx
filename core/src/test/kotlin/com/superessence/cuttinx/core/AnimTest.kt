package com.superessence.cuttinx.core

import com.superessence.cuttinx.core.anim.Interpolation
import com.superessence.cuttinx.core.anim.KeyframeEvaluator
import com.superessence.cuttinx.core.anim.SpeedMath
import com.superessence.cuttinx.core.model.*
import org.junit.Assert.*
import org.junit.Test

class AnimTest {
    @Test fun bezierEndpointsAndMonotonic() {
        assertEquals(0.0, Interpolation.cubicBezier(0.42, 0.0, 0.58, 1.0, 0.0), 1e-9)
        assertEquals(1.0, Interpolation.cubicBezier(0.42, 0.0, 0.58, 1.0, 1.0), 1e-9)
        assertEquals(0.5, Interpolation.cubicBezier(0.42, 0.0, 0.58, 1.0, 0.5), 1e-4)
        var prev = -1.0
        for (i in 0..100) { val v = Interpolation.ease(Easing.Smooth, i / 100.0); assertTrue(v >= prev - 1e-9); prev = v }
        // linear bezier equals identity
        for (i in 0..10) assertEquals(i / 10.0, Interpolation.cubicBezier(0.0, 0.0, 1.0, 1.0, i / 10.0), 1e-4)
        assertTrue(Interpolation.ease(Easing.EaseIn, 0.25) < 0.25)
        assertTrue(Interpolation.ease(Easing.EaseOut, 0.25) > 0.25)
    }

    @Test fun keyframeEvaluation() {
        val k = KeyframeTrack(listOf(Keyframe(0, 0.0, Easing.Linear), Keyframe(1_000_000, 10.0, Easing.Hold), Keyframe(2_000_000, 20.0)))
        assertEquals(0.0, KeyframeEvaluator.evaluate(k, -5, 99.0), 1e-9)
        assertEquals(5.0, KeyframeEvaluator.evaluate(k, 500_000, 99.0), 1e-9)
        assertEquals(10.0, KeyframeEvaluator.evaluate(k, 1_500_000, 99.0), 1e-9) // hold
        assertEquals(20.0, KeyframeEvaluator.evaluate(k, 3_000_000, 99.0), 1e-9)
        assertEquals(99.0, KeyframeEvaluator.evaluate(null, 0, 99.0), 1e-9)
        val up = KeyframeEvaluator.upsert(k, Keyframe(1_000_200, 3.0))
        assertEquals(3, up.keyframes.size)
        assertEquals(3.0, up.keyframes[1].value, 1e-9)
        val moved = KeyframeEvaluator.move(k, 2_000_000, 3_000_000)
        assertEquals(3_000_000, moved.keyframes.last().timeUs)
    }

    @Test fun speedConstant() {
        val c = Clip("c", sourceInUs = 1_000_000, sourceOutUs = 5_000_000, speed = 2.0)
        assertEquals(2_000_000, c.durationUs)
        assertEquals(3_000_000, SpeedMath.timelineToSource(c, 1_000_000))
        assertEquals(1_000_000, SpeedMath.sourceToTimeline(c, 3_000_000))
    }

    @Test fun speedCurveRoundTrip() {
        val c = Clip("c", sourceInUs = 0, sourceOutUs = 10_000_000, speedCurve = SpeedMath.presets["Héros"]!!)
        val d = c.durationUs
        assertTrue("slow-motion middle makes the clip longer", d > 10_000_000)
        for (t in listOf(0L, d / 7, d / 3, d / 2, d * 4 / 5, d)) {
            val s = SpeedMath.timelineToSource(c, t)
            val back = SpeedMath.sourceToTimeline(c, s)
            assertEquals("t=$t", t.toDouble(), back.toDouble(), 2_000.0)
        }
        assertEquals(10_000_000, SpeedMath.timelineToSource(c, d + 1_000_000))
        // stepped speed provider coherent with the curve
        assertEquals(SpeedMath.curveSpeedAt(c.speedCurve, 0.5), SpeedMath.speedAtSource(c, 5_000_000), 0.05)
        assertNotNull(SpeedMath.nextSpeedChangeSource(c, 0))
    }
}
