package com.superessence.cuttinx.core

import com.superessence.cuttinx.core.model.*
import kotlin.math.PI
import kotlin.math.sin

object T {
    fun video(id: String, durS: Double, w: Int = 1920, h: Int = 1080) = MediaAsset(id, "file:///$id.mp4", "$id.mp4", MediaType.VIDEO, "video/mp4", (durS * 1e6).toLong(), w, h, hasAudio = true, hasVideo = true)
    fun image(id: String) = MediaAsset(id, "file:///$id.png", "$id.png", MediaType.IMAGE, "image/png", 0, 1080, 1080)
    fun audio(id: String, durS: Double) = MediaAsset(id, "file:///$id.mp3", "$id.mp3", MediaType.AUDIO, "audio/mpeg", (durS * 1e6).toLong(), hasAudio = true, hasVideo = false)
    fun project() = Project("p1", "Test")

    fun sine(freq: Double, seconds: Double, sr: Int = 48000, amp: Double = 0.5, channels: Int = 1): FloatArray {
        val n = (seconds * sr).toInt()
        return FloatArray(n * channels) { i -> (amp * sin(2 * PI * freq * (i / channels) / sr)).toFloat() }
    }
    fun silence(seconds: Double, sr: Int = 48000, channels: Int = 1) = FloatArray((seconds * sr).toInt() * channels)
    fun noise(seconds: Double, amp: Double, sr: Int = 48000, seed: Long = 1) = java.util.Random(seed).let { r -> FloatArray((seconds * sr).toInt()) { (r.nextGaussian() * amp).toFloat() } }
    fun concat(vararg a: FloatArray): FloatArray { val out = FloatArray(a.sumOf { it.size }); var o = 0; for (x in a) { System.arraycopy(x, 0, out, o, x.size); o += x.size }; return out }
    fun power(a: FloatArray) = a.sumOf { it.toDouble() * it } / a.size
}
