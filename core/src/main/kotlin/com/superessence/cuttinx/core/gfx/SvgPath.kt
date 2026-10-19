package com.superessence.cuttinx.core.gfx

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Minimal SVG path-data parser (M L H V C S Q T A Z, absolute and relative) producing a [Path]. */
object SvgPath {
    private val cache = HashMap<String, Path>()

    @Synchronized fun parse(d: String): Path = cache.getOrPut(d) { build(d) }

    private fun tokens(d: String): List<Any> {
        val out = ArrayList<Any>()
        val re = Regex("[A-Za-z]|[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?")
        for (m in re.findAll(d)) {
            val v = m.value
            if (v[0].isLetter() && v != "e" && v != "E") out += v[0] else out += v.toFloat()
        }
        return out
    }

    private fun build(d: String): Path {
        val p = Path()
        val t = tokens(d)
        var i = 0
        var cmd = 'M'
        var x = 0f; var y = 0f; var sx = 0f; var sy = 0f
        var lcx = 0f; var lcy = 0f // last control point (for S / T)
        var prev = ' '
        fun num(): Float = t[i++] as Float
        while (i < t.size) {
            if (t[i] is Char) { cmd = t[i] as Char; i++ }
            val rel = cmd.isLowerCase()
            val ox = if (rel) x else 0f; val oy = if (rel) y else 0f
            when (cmd.uppercaseChar()) {
                'M' -> { x = ox + num(); y = oy + num(); sx = x; sy = y; p.moveTo(x, y); cmd = if (rel) 'l' else 'L' }
                'L' -> { x = ox + num(); y = oy + num(); p.lineTo(x, y) }
                'H' -> { x = (if (rel) x else 0f) + num(); p.lineTo(x, y) }
                'V' -> { y = (if (rel) y else 0f) + num(); p.lineTo(x, y) }
                'C' -> {
                    val x1 = ox + num(); val y1 = oy + num(); val x2 = ox + num(); val y2 = oy + num(); x = ox + num(); y = oy + num()
                    p.cubicTo(x1, y1, x2, y2, x, y); lcx = x2; lcy = y2
                }
                'S' -> {
                    val x1 = if (prev.uppercaseChar() in "CS") 2 * x - lcx else x; val y1 = if (prev.uppercaseChar() in "CS") 2 * y - lcy else y
                    val x2 = ox + num(); val y2 = oy + num(); x = ox + num(); y = oy + num()
                    p.cubicTo(x1, y1, x2, y2, x, y); lcx = x2; lcy = y2
                }
                'Q' -> { val x1 = ox + num(); val y1 = oy + num(); x = ox + num(); y = oy + num(); p.quadTo(x1, y1, x, y); lcx = x1; lcy = y1 }
                'T' -> {
                    val x1 = if (prev.uppercaseChar() in "QT") 2 * x - lcx else x; val y1 = if (prev.uppercaseChar() in "QT") 2 * y - lcy else y
                    x = ox + num(); y = oy + num(); p.quadTo(x1, y1, x, y); lcx = x1; lcy = y1
                }
                'A' -> {
                    val rx = num(); val ry = num(); val rot = num(); val large = num() != 0f; val sweep = num() != 0f
                    val nx = ox + num(); val ny = oy + num()
                    arc(p, x, y, rx, ry, rot, large, sweep, nx, ny); x = nx; y = ny
                }
                'Z' -> { p.closePath(); x = sx; y = sy }
                else -> i++
            }
            prev = cmd
        }
        return p
    }

    /** SVG elliptical arc → cubic Béziers (SVG spec, appendix F.6). */
    private fun arc(p: Path, x1: Float, y1: Float, rx0: Float, ry0: Float, angleDeg: Float, large: Boolean, sweep: Boolean, x2: Float, y2: Float) {
        if (x1 == x2 && y1 == y2) return
        var rx = abs(rx0.toDouble()); var ry = abs(ry0.toDouble())
        if (rx == 0.0 || ry == 0.0) { p.lineTo(x2, y2); return }
        val phi = angleDeg * PI / 180; val cp = cos(phi); val sp = sin(phi)
        val dx = (x1 - x2) / 2.0; val dy = (y1 - y2) / 2.0
        val x1p = cp * dx + sp * dy; val y1p = -sp * dx + cp * dy
        val lambda = x1p * x1p / (rx * rx) + y1p * y1p / (ry * ry)
        if (lambda > 1) { rx *= sqrt(lambda); ry *= sqrt(lambda) }
        val num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
        val den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        var coef = sqrt((num / den).coerceAtLeast(0.0))
        if (large == sweep) coef = -coef
        val cxp = coef * rx * y1p / ry; val cyp = -coef * ry * x1p / rx
        val cx = cp * cxp - sp * cyp + (x1 + x2) / 2.0; val cy = sp * cxp + cp * cyp + (y1 + y2) / 2.0
        fun ang(ux: Double, uy: Double, vx: Double, vy: Double): Double {
            val a = acos(((ux * vx + uy * vy) / (sqrt(ux * ux + uy * uy) * sqrt(vx * vx + vy * vy))).coerceIn(-1.0, 1.0))
            return if (ux * vy - uy * vx < 0) -a else a
        }
        val t1 = ang(1.0, 0.0, (x1p - cxp) / rx, (y1p - cyp) / ry)
        var dt = ang((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
        if (!sweep && dt > 0) dt -= 2 * PI else if (sweep && dt < 0) dt += 2 * PI
        val segs = ceil(abs(dt) / (PI / 2)).toInt().coerceAtLeast(1)
        val step = dt / segs
        val k = 4.0 / 3.0 * tan(step / 4)
        var a = t1
        for (s in 0 until segs) {
            val c1 = cos(a); val s1 = sin(a); val c2 = cos(a + step); val s2 = sin(a + step)
            fun px(ex: Double, ey: Double) = (cx + cp * rx * ex - sp * ry * ey).toFloat()
            fun py(ex: Double, ey: Double) = (cy + sp * rx * ex + cp * ry * ey).toFloat()
            p.cubicTo(px(c1 - k * s1, s1 + k * c1), py(c1 - k * s1, s1 + k * c1), px(c2 + k * s2, s2 - k * c2), py(c2 + k * s2, s2 - k * c2), px(c2, s2), py(c2, s2))
            a += step
        }
    }
}
