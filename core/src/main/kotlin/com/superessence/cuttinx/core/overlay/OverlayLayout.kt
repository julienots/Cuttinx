package com.superessence.cuttinx.core.overlay

import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

/** Zone layouts of every scene, computed on a 1920×1080 reference then scaled to the output size. */
object OverlayLayout {
    const val REF_W = 1920f
    const val REF_H = 1080f

    fun key(scene: SceneKind, kind: ZoneKind) = "${scene.name}:${kind.name}"

    /** Reference canvas of a scene (the vertical live is laid out on 1080×1920). */
    fun refSize(scene: SceneKind) = if (scene == SceneKind.VERTICAL) 1080f to 1920f else REF_W to REF_H

    fun zones(scene: SceneKind, cfg: OverlayConfig): List<Zone> {
        val (rw, rh) = refSize(scene)
        val ref = (refZones(scene) + logoZone(scene, cfg)).map { z -> if (cfg.cameraRight || z.kind == ZoneKind.TITLE || scene == SceneKind.VERTICAL) z else z.copy(x = rw - z.x - z.w) }
            .map { z -> cfg.zoneMoves[key(scene, z.kind)]?.let { m -> moved(z, m, rw, rh) } ?: z }
        val sx = cfg.width / rw; val sy = cfg.height / rh
        return ref.map { Zone(it.kind, it.x * sx, it.y * sy, it.w * sx, it.h * sy) }
    }

    private fun moved(z: Zone, m: ZoneMove, rw: Float, rh: Float): Zone {
        val s = m.scale.coerceIn(0.3f, 2.5f)
        val w = (z.w * s).coerceAtMost(rw); val h = (z.h * s).coerceAtMost(rh)
        val x = (z.x + z.w / 2 + m.dx - w / 2).coerceIn(0f, rw - w)
        val y = (z.y + z.h / 2 + m.dy - h / 2).coerceIn(0f, rh - h)
        return Zone(z.kind, x, y, w, h)
    }

    /** Zone under the point ([x], [y]) in output pixels (topmost = smallest), for drag & drop editing. */
    fun hit(scene: SceneKind, cfg: OverlayConfig, x: Float, y: Float): ZoneKind? =
        zones(scene, cfg).filter { x >= it.x && x <= it.right && y >= it.y && y <= it.bottom }.minByOrNull { it.w * it.h }?.kind

    /** Moves a zone by ([dx], [dy]) output pixels and / or multiplies its size by [scale]. */
    fun move(cfg: OverlayConfig, scene: SceneKind, kind: ZoneKind, dx: Float, dy: Float, scale: Float = 1f): OverlayConfig {
        val (rw, rh) = refSize(scene)
        val k = key(scene, kind)
        val old = cfg.zoneMoves[k] ?: ZoneMove()
        // mirrored layouts (camera on the left) move the other way round horizontally
        val mirror = !cfg.cameraRight && kind != ZoneKind.TITLE && scene != SceneKind.VERTICAL
        val m = ZoneMove(old.dx + dx * rw / cfg.width * (if (mirror) -1 else 1), old.dy + dy * rh / cfg.height, (old.scale * scale).coerceIn(0.3f, 2.5f))
        return cfg.copy(zoneMoves = cfg.zoneMoves + (k to m))
    }

    fun reset(cfg: OverlayConfig, scene: SceneKind) = cfg.copy(zoneMoves = cfg.zoneMoves.filterKeys { !it.startsWith(scene.name + ":") })

    fun zone(scene: SceneKind, cfg: OverlayConfig, kind: ZoneKind) = zones(scene, cfg).firstOrNull { it.kind == kind }

    /** Where the custom logo sits by default (only when the streamer imported one). */
    private fun logoZone(scene: SceneKind, cfg: OverlayConfig): List<Zone> {
        if (cfg.logo == null || !cfg.logoOnScenes) return emptyList()
        return when {
            scene.fullBackground -> listOf(Zone(ZoneKind.LOGO, 860f, 80f, 200f, 200f))
            scene == SceneKind.GAMEPLAY || scene == SceneKind.SCREEN_ONLY || scene == SceneKind.DUO -> listOf(Zone(ZoneKind.LOGO, 60f, 60f, 150f, 150f))
            scene == SceneKind.JUST_CHATTING -> listOf(Zone(ZoneKind.LOGO, 1100f, 830f, 130f, 130f))
            scene == SceneKind.FULLCAM -> listOf(Zone(ZoneKind.LOGO, 1720f, 40f, 160f, 160f))
            scene == SceneKind.VERTICAL -> listOf(Zone(ZoneKind.LOGO, 880f, 1740f, 160f, 160f))
            else -> emptyList()
        }
    }

    private fun refZones(scene: SceneKind): List<Zone> = when (scene) {
        SceneKind.GAMEPLAY -> listOf(
            Zone(ZoneKind.SCREEN, 40f, 40f, 1376f, 774f),
            Zone(ZoneKind.CAMERA, 1456f, 40f, 424f, 318f),
            Zone(ZoneKind.CHAT, 1456f, 398f, 424f, 642f),
            Zone(ZoneKind.INFO, 40f, 854f, 1376f, 186f),
        )
        SceneKind.JUST_CHATTING -> listOf(
            Zone(ZoneKind.TITLE, 60f, 30f, 1180f, 70f),
            Zone(ZoneKind.CAMERA, 60f, 120f, 1180f, 664f),
            Zone(ZoneKind.CHAT, 1300f, 120f, 560f, 840f),
            Zone(ZoneKind.SOCIALS, 60f, 820f, 1180f, 140f),
        )
        SceneKind.SCREEN_ONLY -> listOf(
            Zone(ZoneKind.SCREEN, 40f, 40f, 1840f, 920f),
            Zone(ZoneKind.CAMERA, 1500f, 740f, 360f, 270f),
            Zone(ZoneKind.INFO, 40f, 980f, 1400f, 70f),
        )
        SceneKind.WEBCAM -> listOf(Zone(ZoneKind.CAMERA, 160f, 90f, 1600f, 900f))
        SceneKind.CHAT -> listOf(Zone(ZoneKind.CHAT, 660f, 60f, 600f, 960f))
        SceneKind.ALERT, SceneKind.ALERT_SUB, SceneKind.ALERT_RAID, SceneKind.ALERT_DONATION -> listOf(Zone(ZoneKind.TITLE, 460f, 80f, 1000f, 220f))
        SceneKind.GOAL -> listOf(Zone(ZoneKind.INFO, 560f, 40f, 800f, 110f))
        SceneKind.TICKER -> listOf(Zone(ZoneKind.INFO, 0f, 1000f, 1920f, 80f))
        SceneKind.SOCIALS -> listOf(Zone(ZoneKind.SOCIALS, 40f, 880f, 660f, 140f))
        SceneKind.VERTICAL -> listOf(
            Zone(ZoneKind.TITLE, 60f, 50f, 960f, 90f),
            Zone(ZoneKind.CAMERA, 60f, 180f, 960f, 540f),
            Zone(ZoneKind.SCREEN, 60f, 760f, 960f, 540f),
            Zone(ZoneKind.CHAT, 60f, 1340f, 960f, 520f),
        )
        SceneKind.DUO -> listOf(
            Zone(ZoneKind.SCREEN, 40f, 40f, 1376f, 774f),
            Zone(ZoneKind.CAMERA, 1456f, 40f, 424f, 318f),
            Zone(ZoneKind.CAMERA2, 1456f, 418f, 424f, 318f),
            Zone(ZoneKind.CHAT, 1456f, 796f, 424f, 244f),
            Zone(ZoneKind.INFO, 40f, 854f, 1376f, 186f),
        )
        SceneKind.FULLCAM -> listOf(
            Zone(ZoneKind.CAMERA, 24f, 24f, 1872f, 1032f),
            Zone(ZoneKind.SOCIALS, 460f, 960f, 1000f, 80f),
        )
        SceneKind.STARTING, SceneKind.BRB, SceneKind.PAUSE, SceneKind.TECH, SceneKind.ENDING, SceneKind.OFFLINE,
        SceneKind.QNA, SceneKind.GIVEAWAY, SceneKind.SPECIAL, SceneKind.INTERMISSION -> listOf(
            Zone(ZoneKind.TITLE, 260f, 330f, 1400f, 260f),
            Zone(ZoneKind.INFO, 460f, 620f, 1000f, 120f),
            Zone(ZoneKind.SOCIALS, 360f, 900f, 1200f, 90f),
        )
    }
}

/** One particle of an animated motif, in output pixels. */
data class Particle(val x: Float, val y: Float, val size: Float, val rotation: Float, val alpha: Float, val variant: Int)

/**
 * Deterministic animation maths shared by every renderer (preview, PNG, animated PNG): the same
 * time always gives the same frame, and every motif loops seamlessly over [LOOP_SEC].
 */
object OverlayAnim {
    const val LOOP_SEC = 4.0
    const val STINGER_SEC = 1.2
    /** Moment where the stinger fully covers the screen (OBS "transition point"). */
    const val STINGER_COVER_SEC = 0.6

    private fun hash(i: Int, salt: Int): Float {
        val v = sin((i * 127.1 + salt * 311.7)) * 43758.5453
        return (v - floor(v)).toFloat()
    }

    /** Falling / rising / drifting particles for [motif] at time [t] (loops every [LOOP_SEC]). */
    fun particles(motif: Motif, t: Double, w: Int, h: Int, count: Int = defaultCount(motif)): List<Particle> {
        val phase = (t / LOOP_SEC) % 1.0
        val out = ArrayList<Particle>(count)
        for (i in 0 until count) {
            val r0 = hash(i, 1); val r1 = hash(i, 2); val r2 = hash(i, 3); val r3 = hash(i, 4)
            // each particle crosses the screen an integer number of times per loop: seamless
            val laps = 1 + (r2 * 2).toInt()
            val p = ((phase * laps + r1) % 1.0).toFloat()
            val sway = sin((phase * 2 * PI * laps) + r0 * 6.28).toFloat()
            val size = when (motif) {
                Motif.SNOW, Motif.PIXEL_STARS, Motif.SPARKLES -> 4f + r3 * 10f
                Motif.CONFETTI -> 8f + r3 * 10f
                Motif.BUBBLES -> 10f + r3 * 34f
                Motif.SAKURA, Motif.LEAVES, Motif.FLOWERS -> 14f + r3 * 22f
                Motif.HEARTS -> 26f + r3 * 34f
                Motif.BATS -> 20f + r3 * 26f
                else -> 6f + r3 * 12f
            } * (w / 1920f)
            val (x, y) = when (motif) {
                Motif.BUBBLES, Motif.HEARTS -> (r0 * w + sway * 30f) to (h + size - p * (h + 2 * size))       // rising
                Motif.BATS -> (-size + p * (w + 2 * size)) to (r0 * h * 0.7f + sway * 40f)                    // flying across
                Motif.PIXEL_STARS, Motif.SPARKLES, Motif.GALAXY -> (r0 * w) to (r1 * h)                       // twinkling in place
                else -> (r0 * w + sway * 50f) to (-size + p * (h + 2 * size))                                  // falling
            }
            val alpha = when (motif) {
                Motif.PIXEL_STARS, Motif.SPARKLES, Motif.GALAXY -> (0.5f + 0.5f * sin((phase * 2 * PI * laps * 2) + r2 * 6.28).toFloat()).coerceIn(0f, 1f)
                else -> 0.55f + 0.45f * r2
            }
            out += Particle(x, y, size, (p * 360f * (if (r3 > 0.5f) 1 else -1) * laps), alpha, (r3 * 4).toInt())
        }
        return out
    }

    fun defaultCount(m: Motif) = when (m) {
        Motif.SNOW -> 90; Motif.PIXEL_STARS, Motif.SPARKLES -> 60; Motif.GALAXY -> 140; Motif.CONFETTI -> 80
        Motif.SAKURA, Motif.LEAVES, Motif.FLOWERS -> 34; Motif.HEARTS, Motif.BUBBLES -> 28; Motif.BATS -> 9
        else -> 0
    }

    /** Smooth 0..1..0 pulse looping with the scene. */
    fun pulse(t: Double, cyclesPerLoop: Int = 2): Float = (0.5 - 0.5 * kotlin.math.cos(2 * PI * cyclesPerLoop * (t / LOOP_SEC))).toFloat()

    /** Stinger progress helpers: 0 → covering → fully covered at [STINGER_COVER_SEC] → revealing → 0. */
    fun stingerCover(t: Double): Float {
        val c = STINGER_COVER_SEC; val d = STINGER_SEC
        return when {
            t <= 0 -> 0f
            t < c -> easeInOut((t / c).toFloat())
            t < c + 0.08 -> 1f
            t < d -> 1f - easeInOut(((t - c - 0.08) / (d - c - 0.08)).toFloat())
            else -> 0f
        }
    }

    fun easeInOut(x: Float): Float { val v = x.coerceIn(0f, 1f); return v * v * (3 - 2 * v) }

    /** Number of frames exported for an animation of [seconds] at [fps]. */
    fun frameCount(seconds: Double, fps: Int) = (seconds * fps).toInt().coerceAtLeast(1)
}
