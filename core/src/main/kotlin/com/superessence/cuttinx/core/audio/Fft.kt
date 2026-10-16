package com.superessence.cuttinx.core.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** In-place iterative radix-2 complex FFT. */
class Fft(val size: Int) {
    init { require(size >= 2 && size and (size - 1) == 0) { "FFT size must be a power of two" } }
    private val cosT = DoubleArray(size / 2) { cos(2 * PI * it / size) }
    private val sinT = DoubleArray(size / 2) { sin(2 * PI * it / size) }
    private val rev = IntArray(size).also { r ->
        val bits = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size) r[i] = Integer.reverse(i) ushr (32 - bits)
    }

    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean = false) {
        for (i in 0 until size) {
            val j = rev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= size) {
            val half = len / 2
            val step = size / len
            var i = 0
            while (i < size) {
                for (k in 0 until half) {
                    val wr = cosT[k * step]
                    val wi = if (inverse) sinT[k * step] else -sinT[k * step]
                    val a = i + k; val b = a + half
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                }
                i += len
            }
            len *= 2
        }
        if (inverse) for (i in 0 until size) { re[i] /= size; im[i] /= size }
    }

    companion object {
        fun hann(n: Int) = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / n) }
        /** sqrt-Hann window, used for analysis+synthesis (perfect reconstruction at 75% overlap with 2/3 scaling). */
        fun sqrtHann(n: Int) = DoubleArray(n) { kotlin.math.sqrt(0.5 - 0.5 * cos(2 * PI * it / n)) }
        fun nextPow2(n: Int): Int { var p = 1; while (p < n) p = p shl 1; return p }
    }
}
