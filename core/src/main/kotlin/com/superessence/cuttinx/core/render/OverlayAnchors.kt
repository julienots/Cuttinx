package com.superessence.cuttinx.core.render

import kotlin.math.abs

/**
 * Media3 places an overlay with two anchors that must both stay inside [-1, 1] (NDC), otherwise
 * `StaticOverlaySettings.Builder` throws and the whole video pipeline stops with
 * `ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED`. Layers that slide out of the frame (slide/whip
 * transitions, position keyframes, text animations near an edge...) need positions beyond that range.
 *
 * Media3 draws the overlay centre at `B - A·S·o` (B = background anchor, o = overlay anchor,
 * A·S = overlay half size in output NDC). [solve] returns anchors that are both in range and
 * produce the same centre as the requested (unbounded) [posX]/[posY] + [anchorX]/[anchorY].
 */
object OverlayAnchors {
    data class Anchors(val bgX: Float, val bgY: Float, val ovX: Float, val ovY: Float)

    /**
     * @param posX/posY requested background anchor (NDC, may be outside [-1, 1])
     * @param anchorX/anchorY requested overlay anchor (NDC of the overlay, normally in [-1, 1])
     * @param halfW/halfH overlay half size in output NDC including the user scale (may be negative when flipped)
     * @return null when the layer is entirely outside the frame (nothing to draw)
     */
    fun solve(posX: Double, posY: Double, anchorX: Double, anchorY: Double, halfW: Double, halfH: Double, rotationDeg: Double = 0.0): Anchors? {
        if (!posX.isFinite() || !posY.isFinite() || !halfW.isFinite() || !halfH.isFinite()) return null
        if (abs(halfW) < 1e-6 || abs(halfH) < 1e-6) return null
        val (bx, ox) = axis(posX, anchorX, halfW, rotationDeg) ?: return null
        val (by, oy) = axis(posY, anchorY, halfH, rotationDeg) ?: return null
        return Anchors(bx.toFloat(), by.toFloat(), ox.toFloat(), oy.toFloat())
    }

    private fun axis(pos: Double, anchor: Double, half: Double, rotationDeg: Double): Pair<Double, Double>? {
        val a = anchor.coerceIn(-1.0, 1.0)
        val b = pos.coerceIn(-1.0, 1.0)
        val o = a + (b - pos) / half
        if (o in -1.0..1.0) return b to o
        // Unrotated: |o| > 1 means the layer lies completely outside the frame on this axis.
        val rotated = abs(Math.IEEEremainder(rotationDeg, 180.0)) > 0.5
        // Rotated layers can poke back into the frame with a corner (extent up to ~2 half sizes):
        // keep the closest valid placement, hide beyond.
        if (!rotated || abs(o) > 2.0) return null
        return b to o.coerceIn(-1.0, 1.0)
    }
}
