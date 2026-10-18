package com.superessence.cuttinx.desktop.engine

import com.superessence.cuttinx.core.anim.SpeedMath
import com.superessence.cuttinx.core.audio.AudioChainFactory
import com.superessence.cuttinx.core.audio.DspChain
import com.superessence.cuttinx.core.audio.DspProcessor
import com.superessence.cuttinx.core.model.*
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tanh

/** Two-tap delay-line pitch shifter (voice effects, speed changes with preserved pitch). */
class PitchShifter(var ratio: Double) : DspProcessor {
    private val size = 2048
    private var buf = Array(2) { FloatArray(size) }
    private var w = 0
    private var phase = 0.0
    private var ch = 2
    override fun configure(sampleRate: Int, channels: Int) { ch = channels.coerceIn(1, 2); buf = Array(ch) { FloatArray(size) } }
    override fun reset() { buf.forEach { it.fill(0f) }; phase = 0.0 }
    override fun process(buf: FloatArray, frames: Int, frameOffset: Long) {
        val win = size / 2.0
        val step = 1.0 - ratio
        var i = 0
        for (f in 0 until frames) {
            phase = (phase + step + win) % win
            val d1 = phase; val d2 = (phase + win / 2) % win
            val g1 = 0.5 - 0.5 * cos(2 * PI * d1 / win); val g2 = 0.5 - 0.5 * cos(2 * PI * d2 / win)
            for (c in 0 until ch) {
                val line = this.buf[c]
                line[w] = buf[i + c]
                buf[i + c] = (tap(line, d1) * g1 + tap(line, d2) * g2).toFloat()
            }
            w = (w + 1) % size
            i += ch
        }
    }
    private fun tap(line: FloatArray, delay: Double): Double {
        val pos = w - delay - 1
        val p = ((pos % size) + size) % size
        val i0 = p.toInt(); val fr = p - i0
        return line[i0] * (1 - fr) + line[(i0 + 1) % size] * fr
    }
}

/**
 * Mixes every audible clip of a project (video sound, music, voice-overs, sound effects) into
 * interleaved stereo float samples at [rate] Hz, with the same DSP chain as Android (volume
 * automation, fades, EQ, compressor, voice effects, noise reduction…).
 */
class PcAudio(private val pool: DecoderPool, val rate: Int = 48_000, private val fileOf: (MediaAsset) -> File? = PcMedia::fileOf) {
    private class State(val key: Int, val chain: DspChain, val shifter: PitchShifter?, var nextFrame: Long)
    private val states = HashMap<String, State>()
    private var tmp = FloatArray(0)
    private var res = FloatArray(0)

    /** Clips that produce sound, with their track volume. */
    private fun audible(p: Project): List<Pair<Clip, Double>> = p.tracks.filter { !it.muted && (it.kind == TrackKind.AUDIO || it.kind == TrackKind.VIDEO) }.flatMap { t ->
        t.clips.filter { c -> c.enabled && c.kind == ClipKind.MEDIA && !c.audio.muted && p.asset(c.assetId)?.let { it.hasAudio && !it.missing } == true }.map { it to t.volume }
    }

    /** Writes [frames] stereo frames starting at timeline time [startUs] into [out]. */
    @Synchronized fun mix(p: Project, startUs: Long, frames: Int, out: FloatArray) {
        java.util.Arrays.fill(out, 0, frames * 2, 0f)
        if (tmp.size < frames * 8 + 16) { tmp = FloatArray(frames * 8 + 16); res = FloatArray(frames * 2 + 4) }
        val startFrame = startUs * rate / 1_000_000
        val alive = HashSet<String>()
        for ((c, vol) in audible(p)) {
            val cs = c.startUs * rate / 1_000_000
            val ce = c.endUs * rate / 1_000_000
            val from = maxOf(startFrame, cs); val to = minOf(startFrame + frames, ce)
            if (to <= from) continue
            alive += c.id
            val n = (to - from).toInt()
            val rel = from - cs                 // frames since the clip start
            val relUs = rel * 1_000_000 / rate
            val asset = p.asset(c.assetId)!!
            val file = fileOf(asset) ?: continue
            val st = state(c, vol, rel)
            val srcUs = SpeedMath.timelineToSource(c, relUs)
            val speed = SpeedMath.speedAtSource(c, srcUs).coerceIn(0.1, 16.0)
            val srcN = if (abs(speed - 1.0) < 1e-3) n else (n * speed).roundToInt().coerceIn(1, tmp.size / 2 - 2)
            val dec = pool.audio(c.id, file)
            if (!dec.read(srcUs, srcN, tmp)) continue
            val block: FloatArray
            if (srcN == n) block = tmp else {
                for (i in 0 until n) {
                    val x = i * (srcN - 1).toDouble() / maxOf(1, n - 1)
                    val i0 = x.toInt(); val fr = (x - i0).toFloat(); val i1 = minOf(i0 + 1, srcN - 1)
                    res[i * 2] = tmp[i0 * 2] * (1 - fr) + tmp[i1 * 2] * fr
                    res[i * 2 + 1] = tmp[i0 * 2 + 1] * (1 - fr) + tmp[i1 * 2 + 1] * fr
                }
                block = res
            }
            st.shifter?.let { sh ->
                sh.ratio = pitchRatio(c, speed)
                sh.process(block, n, rel)
            }
            st.chain.process(block, n, rel)
            st.nextFrame = rel + n
            val o = ((from - startFrame) * 2).toInt()
            for (i in 0 until n * 2) out[o + i] += block[i]
        }
        states.keys.retainAll(alive)
        // soft limiter on the master bus
        for (i in 0 until frames * 2) { val v = out[i]; if (v > 0.9f || v < -0.9f) out[i] = (tanh(v.toDouble() * 1.1) / tanh(1.1)).toFloat() }
    }

    private fun pitchRatio(c: Clip, speed: Double): Double {
        var r = 2.0.pow(AudioChainFactory.pitchSemitones(c) / 12)
        if (c.audio.preservePitch && abs(speed - 1) > 1e-3) r /= speed
        return r
    }

    /** DSP state of a clip; rebuilt when its settings change, reset after a jump. */
    private fun state(c: Clip, trackVolume: Double, rel: Long): State {
        val key = c.audio.hashCode() * 31 + c.keyframes.hashCode() + trackVolume.hashCode() * 7 + c.speed.hashCode() + c.speedCurve.hashCode()
        val old = states[c.id]
        val needsShift = abs(pitchRatio(c, c.speed) - 1) > 0.01 || c.speedCurve.isNotEmpty()
        val st = if (old != null && old.key == key) old else State(key, AudioChainFactory.build(c, trackVolume).also { it.configure(rate, 2) },
            if (needsShift) PitchShifter(1.0).also { it.configure(rate, 2) } else null, rel).also { states[c.id] = it }
        if (abs(st.nextFrame - rel) > rate / 20) { st.chain.reset(); st.shifter?.reset() }
        return st
    }

    @Synchronized fun reset() { states.clear() }
}
