package com.superessence.cuttinx.core.smart

import com.superessence.cuttinx.core.effects.EffectRegistry
import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.timeline.TimelineEditor
import com.superessence.cuttinx.core.util.Ids
import kotlin.math.min

enum class Corner(val label: String, val x: Double, val y: Double) {
    TOP_LEFT("en haut à gauche", 0.2, 0.17), TOP_RIGHT("en haut à droite", 0.8, 0.17),
    BOTTOM_LEFT("en bas à gauche", 0.2, 0.8), BOTTOM_RIGHT("en bas à droite", 0.8, 0.8),
}

/** Screen layouts: split screens, picture in picture, blurred background. */
object Layouts {
    /** Native size of a visual clip, rotation of the file taken into account. */
    private fun size(p: Project, c: Clip): Pair<Int, Int> {
        val a = p.asset(c.assetId) ?: return p.format.width to p.format.height
        if (a.width <= 0 || a.height <= 0) return p.format.width to p.format.height
        return if (a.rotationDegrees % 180 != 0) a.height to a.width else a.width to a.height
    }

    /** Scale (relative to "fit the canvas") that fits the clip inside a box of [bw]x[bh] canvas fractions. */
    private fun fitScale(p: Project, c: Clip, bw: Double, bh: Double): Double {
        val (w, h) = size(p, c)
        val cw = p.format.width.toDouble(); val ch = p.format.height.toDouble()
        val base = min(cw / w, ch / h)
        return min(cw * bw / w, ch * bh / h) / base
    }

    private fun boxes(n: Int, vertical: Boolean): List<DoubleArray> = when (n) {
        // x, y, w, h (centre + size in canvas fractions)
        2 -> if (vertical) listOf(doubleArrayOf(0.5, 0.25, 1.0, 0.5), doubleArrayOf(0.5, 0.75, 1.0, 0.5)) else listOf(doubleArrayOf(0.25, 0.5, 0.5, 1.0), doubleArrayOf(0.75, 0.5, 0.5, 1.0))
        3 -> if (vertical) listOf(doubleArrayOf(0.5, 1 / 6.0, 1.0, 1 / 3.0), doubleArrayOf(0.5, 0.5, 1.0, 1 / 3.0), doubleArrayOf(0.5, 5 / 6.0, 1.0, 1 / 3.0))
             else listOf(doubleArrayOf(1 / 6.0, 0.5, 1 / 3.0, 1.0), doubleArrayOf(0.5, 0.5, 1 / 3.0, 1.0), doubleArrayOf(5 / 6.0, 0.5, 1 / 3.0, 1.0))
        else -> listOf(doubleArrayOf(0.25, 0.25, 0.5, 0.5), doubleArrayOf(0.75, 0.25, 0.5, 0.5), doubleArrayOf(0.25, 0.75, 0.5, 0.5), doubleArrayOf(0.75, 0.75, 0.5, 0.5))
    }

    /**
     * Split screen from the selected shots (2 to 4, in selection order): the first one stays where it
     * is, the others are moved onto new video tracks above it, starting at the same time.
     * [stacked] forces top/bottom (else: top/bottom on a vertical canvas, side by side on a wide one).
     */
    fun splitScreen(p: Project, selection: List<String>, stacked: Boolean? = null): Project {
        val clips = selection.mapNotNull { p.findClip(it) }.filter { (t, c) -> !t.locked && Kit.isVisual(p, c) && c.kind == ClipKind.MEDIA }.take(4)
        if (clips.size < 2) return p
        val vertical = stacked ?: (p.format.height > p.format.width)
        val box = boxes(clips.size, vertical)
        val anchor = clips[0].second
        var np = p
        for ((i, tc) in clips.withIndex()) {
            val (_, c0) = tc
            val c = np.findClip(c0.id)?.second ?: continue
            val b = box[i]
            val s = fitScale(np, c, b[2], b[3])
            val tr = c.transform.copy(posX = b[0], posY = b[1], scaleX = s, scaleY = s, fill = false, rotation = 0.0)
            if (i == 0) { np = Kit.edit(np, c.id) { it.copy(transform = tr) }; continue }
            // remove from its place (the magnetic main track closes the gap) and put it on its own layer
            np = runCatching { TimelineEditor.deleteClips(np, listOf(c.id)) }.getOrDefault(np)
            val (q, t) = TimelineEditor.addTrack(np, TrackKind.VIDEO, "Écran partagé ${i + 1}")
            val startUs = np.findClip(anchor.id)?.second?.startUs ?: anchor.startUs
            np = TimelineEditor.insertClip(q, t.id, c.copy(startUs = startUs, transitionIn = null, transform = tr))
        }
        return np
    }

    /**
     * Picture in picture: the clip goes in a corner, a third of the size, with a thin frame. A clip of
     * the main track is lifted onto its own layer at the same time (the main track closes up behind it).
     */
    fun pictureInPicture(p: Project, clipId: String, corner: Corner, round: Boolean = false): Project {
        val (t, c) = p.findClip(clipId) ?: return p
        if (t.locked || !Kit.isVisual(p, c)) return p
        val s = fitScale(p, c, 0.36, 0.36)
        val tr = c.transform.copy(posX = corner.x, posY = corner.y, scaleX = s, scaleY = s, fill = false)
        val mask = if (round) Mask(MaskShape.ELLIPSE, width = 0.95, height = 0.95, feather = 0.01) else c.mask
        val isMain = SmartTools.mainTrack(p)?.id == t.id
        if (!isMain) return Kit.edit(p, clipId) { it.copy(transform = tr, mask = mask) }
        val main = SmartTools.mainTrack(p)!!
        if (main.clips.size < 2) return Kit.edit(p, clipId) { it.copy(transform = tr, mask = mask) }
        // the shot sits over the one that follows it once lifted
        var np = runCatching { TimelineEditor.deleteClips(p, listOf(clipId)) }.getOrDefault(p)
        val (q, nt) = TimelineEditor.addTrack(np, TrackKind.VIDEO, "Incrustation")
        np = TimelineEditor.insertClip(q, nt.id, c.copy(startUs = c.startUs.coerceAtMost((np.durationUs - c.durationUs).coerceAtLeast(0)), transitionIn = null, transform = tr, mask = mask))
        return np
    }

    /**
     * Blurred background: each shot of the main track fills the frame, blurred and darkened, and a
     * sharp copy (silent, so the sound is not doubled) sits above it, whole. The classic fix for a
     * horizontal video in a vertical format.
     */
    fun blurredBackground(p: Project): Project {
        val main = SmartTools.mainTrack(p) ?: return p
        if (main.locked) return p
        val shots = main.sortedClips().filter { it.kind == ClipKind.MEDIA && p.asset(it.assetId)?.isVisual == true }
        if (shots.isEmpty()) return p
        val blur = EffectRegistry["blur"] ?: return p
        val adjust = EffectRegistry["adjust"]
        val (np0, top) = Kit.namedTrack(p, TrackKind.VIDEO, "Premier plan net")
        if (top.clips.isNotEmpty()) return p           // already done
        var np = np0
        for (s in shots) {
            val sharp = s.copy(id = Ids.new("clp"), transform = s.transform.copy(fill = false), audio = s.audio.copy(muted = true), groupId = null,
                effects = s.effects.filterNot { it.id.startsWith("bg_") }.map { it.copy(id = Ids.new("fx")) })
            np = TimelineEditor.insertClip(np, top.id, sharp)
            np = Kit.edit(np, s.id) { c ->
                val fx = listOfNotNull(
                    EffectInstance("bg_" + Ids.new(), "blur", blur.defaults() + ("radius" to 28.0)),
                    adjust?.let { EffectInstance("bg_" + Ids.new(), "adjust", it.defaults() + mapOf("brightness" to -0.18, "saturation" to 0.1)) },
                )
                c.copy(transform = c.transform.copy(fill = true, scaleX = 1.08, scaleY = 1.08), effects = c.effects.filterNot { it.id.startsWith("bg_") } + fx)
            }
        }
        return np
    }

    /** Puts every shot back to full screen and removes the layout layers created above. */
    fun fullScreen(p: Project): Project {
        var np = p.copy(tracks = p.tracks.filterNot { it.kind == TrackKind.VIDEO && it.name == "Premier plan net" && !it.locked })
        np = TimelineEditor.mapClips(np) { c ->
            if (c.kind != ClipKind.MEDIA || np.asset(c.assetId)?.isVisual != true) c
            else c.copy(transform = c.transform.copy(posX = 0.5, posY = 0.5, scaleX = 1.0, scaleY = 1.0, rotation = 0.0), effects = c.effects.filterNot { it.id.startsWith("bg_") }, mask = if (c.mask.shape == MaskShape.ELLIPSE) Mask() else c.mask)
        }
        return np
    }
}
