package com.superessence.cuttinx.core.smart

import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.timeline.TimelineEditor
import kotlin.math.PI
import kotlin.math.sin

/** Camera moves written as real keyframes: they show on the timeline and stay editable afterwards. */
enum class CameraMove(val label: String) {
    ZOOM_IN("Zoom avant progressif"), ZOOM_OUT("Dézoom révélation"), PAN_RIGHT("Panoramique vers la droite"),
    PAN_LEFT("Panoramique vers la gauche"), TILT_UP("Travelling vers le haut"), TILT_DOWN("Travelling vers le bas"),
    DOLLY("Zoom cinéma (lent puis rapide)"), SPIN("Tour complet"), SWAY("Balancement doux"),
}

/** Keyframe and animation tools (pure, undoable project edits). */
object Animator {
    private val ANIM_PROPS = listOf(Prop.SCALE, Prop.POS_X, Prop.POS_Y, Prop.ROTATION, Prop.OPACITY)

    /** Selected visual elements, else every visual shot of the main track. */
    fun targets(p: Project, selection: List<String>): List<String> {
        val sel = selection.mapNotNull { p.findClip(it)?.second }.filter { Kit.isVisual(p, it) }
        if (sel.isNotEmpty()) return sel.map { it.id }
        return SmartTools.mainTrack(p)?.takeUnless { it.locked }?.sortedClips()?.filter { Kit.isVisual(p, it) }?.map { it.id }.orEmpty()
    }

    private fun track(vararg k: Pair<Long, Double>, easing: Easing = Easing.Smooth) = KeyframeTrack(k.map { Keyframe(it.first, it.second, easing) })

    /** Writes [move] over the whole duration of each clip (previous transform keyframes are replaced). */
    fun camera(p: Project, ids: List<String>, move: CameraMove): Project = ids.fold(p) { acc, id ->
        Kit.edit(acc, id) { c ->
            val d = c.durationUs.coerceAtLeast(1)
            val x = c.transform.posX; val y = c.transform.posY; val r = c.transform.rotation
            val k: Map<String, KeyframeTrack> = when (move) {
                CameraMove.ZOOM_IN -> mapOf(Prop.SCALE to track(0L to 1.0, d to 1.22, easing = Easing.Linear))
                CameraMove.ZOOM_OUT -> mapOf(Prop.SCALE to track(0L to 1.4, d to 1.0, easing = Easing.EaseOut))
                CameraMove.PAN_RIGHT -> mapOf(Prop.SCALE to track(0L to 1.25), Prop.POS_X to track(0L to x + 0.1, d to x - 0.1, easing = Easing.Linear))
                CameraMove.PAN_LEFT -> mapOf(Prop.SCALE to track(0L to 1.25), Prop.POS_X to track(0L to x - 0.1, d to x + 0.1, easing = Easing.Linear))
                CameraMove.TILT_UP -> mapOf(Prop.SCALE to track(0L to 1.25), Prop.POS_Y to track(0L to y - 0.1, d to y + 0.1, easing = Easing.Linear))
                CameraMove.TILT_DOWN -> mapOf(Prop.SCALE to track(0L to 1.25), Prop.POS_Y to track(0L to y + 0.1, d to y - 0.1, easing = Easing.Linear))
                CameraMove.DOLLY -> mapOf(Prop.SCALE to KeyframeTrack(listOf(Keyframe(0, 1.0, Easing.EaseIn), Keyframe(d, 1.35, Easing.EaseIn))))
                CameraMove.SPIN -> mapOf(Prop.ROTATION to track(0L to r, d to r + 360.0, easing = Easing.Smooth), Prop.SCALE to track(0L to 1.0, d / 2 to 0.85, d to 1.0))
                CameraMove.SWAY -> {
                    val steps = (d / 1_000_000L).toInt().coerceIn(2, 40)
                    mapOf(Prop.ROTATION to KeyframeTrack((0..steps).map { i -> Keyframe(d * i / steps, r + if (i % 2 == 0) -2.5 else 2.5, Easing.Smooth) }), Prop.SCALE to track(0L to 1.08))
                }
            }
            c.copy(keyframes = c.keyframes - ANIM_PROPS.toSet() + k)
        }
    }

    /** Fast zoom-in hit at [atUs] (timeline time) on every target crossing it: 0.12 s up, held, 0.4 s back. */
    fun zoomHit(p: Project, ids: List<String>, atUs: Long, amount: Double = 1.3): Project = ids.fold(p) { acc, id ->
        Kit.edit(acc, id) { c ->
            if (atUs <= c.startUs || atUs >= c.endUs) return@edit c
            val rel = atUs - c.startUs
            val kept = c.keyframes[Prop.SCALE]?.keyframes.orEmpty().filter { it.timeUs < rel - 50_000 || it.timeUs > rel + 700_000 }
            val hit = listOf(Keyframe(rel, 1.0, Easing.EaseOut), Keyframe((rel + 120_000).coerceAtMost(c.durationUs), amount, Easing.Smooth),
                Keyframe((rel + 300_000).coerceAtMost(c.durationUs), amount, Easing.Smooth), Keyframe((rel + 700_000).coerceAtMost(c.durationUs), 1.0, Easing.Smooth))
            c.copy(keyframes = c.keyframes + (Prop.SCALE to KeyframeTrack((kept + hit).distinctBy { it.timeUs }.sortedBy { it.timeUs })))
        }
    }

    /** Scale pulse on each beat of [bpm] (beats counted from the start of the project). */
    fun pulseOnBeats(p: Project, ids: List<String>, bpm: Double = 120.0, amount: Double = 1.06): Project {
        val beat = (60_000_000.0 / bpm).toLong().coerceAtLeast(150_000)
        return ids.fold(p) { acc, id ->
            Kit.edit(acc, id) { c ->
                val first = ((c.startUs + beat - 1) / beat) * beat
                val keys = ArrayList<Keyframe>()
                var b = first
                while (b < c.endUs && keys.size < 2000) {
                    val rel = b - c.startUs
                    keys += Keyframe(rel, amount, Easing.EaseOut)
                    if (rel + beat / 2 < c.durationUs) keys += Keyframe(rel + beat / 2, 1.0, Easing.Smooth)
                    b += beat
                }
                if (keys.isEmpty()) c else c.copy(keyframes = c.keyframes + (Prop.SCALE to KeyframeTrack(keys.distinctBy { it.timeUs })))
            }
        }
    }

    /** Short camera shake (0.5 s) at the playhead, written as position keyframes. */
    fun shakeAt(p: Project, ids: List<String>, atUs: Long, amount: Double = 0.02): Project = ids.fold(p) { acc, id ->
        Kit.edit(acc, id) { c ->
            if (atUs <= c.startUs || atUs >= c.endUs) return@edit c
            val rel = atUs - c.startUs
            val x = c.transform.posX; val y = c.transform.posY
            val n = 10
            val xs = (0..n).map { i -> val t = (rel + i * 50_000L).coerceAtMost(c.durationUs); val f = 1.0 - i.toDouble() / n; Keyframe(t, x + amount * f * sin(i * 2.4), Easing.Linear) }
            val ys = (0..n).map { i -> val t = (rel + i * 50_000L).coerceAtMost(c.durationUs); val f = 1.0 - i.toDouble() / n; Keyframe(t, y + amount * f * sin(i * 3.7 + PI / 3), Easing.Linear) }
            c.copy(keyframes = c.keyframes + (Prop.POS_X to KeyframeTrack(xs.distinctBy { it.timeUs })) + (Prop.POS_Y to KeyframeTrack(ys.distinctBy { it.timeUs })))
        }
    }

    /** Opacity keyframes: fade from transparent and back at the edges of each clip. */
    fun fadeEdges(p: Project, ids: List<String>, fadeUs: Long = 400_000): Project = ids.fold(p) { acc, id ->
        Kit.edit(acc, id) { c ->
            val d = c.durationUs
            val f = fadeUs.coerceAtMost(d / 3)
            if (f < 20_000) c else c.copy(keyframes = c.keyframes + (Prop.OPACITY to track(0L to 0.0, f to c.transform.opacity, d - f to c.transform.opacity, d to 0.0)))
        }
    }

    /** Entry / exit / loop animation of the targets (null = unchanged). */
    fun motion(p: Project, ids: List<String>, animIn: MotionPreset? = null, animOut: MotionPreset? = null, loop: LoopMotion? = null): Project = ids.fold(p) { acc, id ->
        Kit.edit(acc, id) { c ->
            val m = c.motion
            val d = c.durationUs
            c.copy(motion = m.copy(
                animIn = animIn ?: m.animIn, inUs = if (animIn != null) (d / 3).coerceIn(150_000, 600_000) else m.inUs,
                animOut = animOut ?: m.animOut, outUs = if (animOut != null) (d / 3).coerceIn(150_000, 500_000) else m.outUs,
                loop = loop ?: m.loop,
            ))
        }
    }

    /** Removes every keyframe and animation of the targets (the base transform is kept). */
    fun clear(p: Project, ids: List<String>): Project = ids.fold(p) { acc, id ->
        Kit.edit(acc, id) { c -> c.copy(keyframes = c.keyframes.filterKeys { it.startsWith("fx:") || it == Prop.VOLUME || it == Prop.PAN }, motion = ClipMotion()) }
    }

    /** Back to centred, full size, upright, opaque. */
    fun resetTransform(p: Project, ids: List<String>): Project = ids.fold(p) { acc, id ->
        Kit.edit(acc, id) { c -> c.copy(transform = Transform(fill = c.transform.fill, posY = if (c.kind == ClipKind.SUBTITLE) 0.85 else 0.5), keyframes = c.keyframes - ANIM_PROPS.toSet()) }
    }

    fun mirror(p: Project, ids: List<String>, horizontal: Boolean = true): Project = ids.fold(p) { acc, id ->
        Kit.edit(acc, id) { c -> c.copy(transform = if (horizontal) c.transform.copy(flipH = !c.transform.flipH) else c.transform.copy(flipV = !c.transform.flipV)) }
    }

    fun rotate(p: Project, ids: List<String>, degrees: Double): Project = ids.fold(p) { acc, id ->
        Kit.edit(acc, id) { c -> c.copy(transform = c.transform.copy(rotation = ((c.transform.rotation + degrees) % 360 + 360) % 360)) }
    }

    fun zoom(p: Project, ids: List<String>, factor: Double): Project = ids.fold(p) { acc, id ->
        Kit.edit(acc, id) { c -> c.copy(transform = c.transform.copy(scaleX = (c.transform.scaleX * factor).coerceIn(0.05, 10.0), scaleY = (c.transform.scaleY * factor).coerceIn(0.05, 10.0))) }
    }

    /** Speed of the selected media shots (pitch is kept). */
    fun speed(p: Project, ids: List<String>, speed: Double): Project = ids.fold(p) { acc, id ->
        val c = acc.findClip(id)?.second
        if (c == null || c.kind != ClipKind.MEDIA || Kit.isStill(acc, c) || acc.findClip(id)?.first?.locked == true) acc
        else runCatching { TimelineEditor.setSpeed(acc, id, speed) }.getOrDefault(acc)
    }
}
