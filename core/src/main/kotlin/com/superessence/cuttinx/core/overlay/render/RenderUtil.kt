package com.superessence.cuttinx.core.overlay.render

import com.superessence.cuttinx.core.gfx.Gfx
import com.superessence.cuttinx.core.gfx.Paint
import com.superessence.cuttinx.core.gfx.TextDrawer

/** Rounded rectangle value (with outward inflation used by double frames). */
class RR(val x: Float, val y: Float, val w: Float, val h: Float, val r: Float) {
    fun inflate(d: Float) = RR(x - d, y - d, w + 2 * d, h + 2 * d, r + d)
}

fun Gfx.rrect(rr: RR, p: Paint) = rrect(rr.x, rr.y, rr.w, rr.h, rr.r, p)

/** Text helpers bound to this surface. */
val Gfx.txt: TextDrawer get() = TextDrawer(this)
