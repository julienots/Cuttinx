package com.superessence.cuttinx.core.util

import java.util.UUID

object Ids {
    fun new(prefix: String = ""): String = prefix + UUID.randomUUID().toString().replace("-", "").take(16)
}

/** Microsecond helpers. All timeline times in CUTTINX are expressed in microseconds. */
object Time {
    const val US_PER_MS = 1_000L
    const val US_PER_SECOND = 1_000_000L
    fun seconds(s: Double): Long = (s * US_PER_SECOND).toLong()
    fun ms(ms: Long): Long = ms * US_PER_MS
    fun toSeconds(us: Long): Double = us / US_PER_SECOND.toDouble()

    /** Formats as HH:MM:SS.mmm (or MM:SS.mmm when under one hour). */
    fun format(us: Long, withMillis: Boolean = true): String {
        val neg = us < 0
        val t = kotlin.math.abs(us)
        val totalMs = t / 1000
        val ms = totalMs % 1000
        val totalS = totalMs / 1000
        val s = totalS % 60
        val m = (totalS / 60) % 60
        val h = totalS / 3600
        val base = if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
        return (if (neg) "-" else "") + if (withMillis) "$base.%03d".format(ms) else base
    }
}

fun Double.clamp(min: Double, max: Double) = if (this < min) min else if (this > max) max else this
fun Float.clamp(min: Float, max: Float) = if (this < min) min else if (this > max) max else this
fun Long.clamp(min: Long, max: Long) = if (this < min) min else if (this > max) max else this
