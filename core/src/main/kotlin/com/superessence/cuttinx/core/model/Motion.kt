package com.superessence.cuttinx.core.model

import kotlinx.serialization.Serializable

/** Entry/exit animation of any visual clip (video, image, sticker, text, colour). */
@Serializable
enum class MotionPreset(val label: String, val group: String) {
    NONE("Aucune", "Base"),
    FADE("Fondu", "Base"),
    ZOOM_IN("Zoom avant", "Zoom"),
    ZOOM_OUT("Zoom arrière", "Zoom"),
    SPIN_ZOOM("Tourbillon", "Zoom"),
    POP("Pop", "Rebond"),
    BOUNCE("Rebond", "Rebond"),
    ELASTIC("Élastique", "Rebond"),
    DROP("Chute", "Rebond"),
    SLIDE_LEFT("Glisser ←", "Glissement"),
    SLIDE_RIGHT("Glisser →", "Glissement"),
    SLIDE_UP("Glisser ↑", "Glissement"),
    SLIDE_DOWN("Glisser ↓", "Glissement"),
    WHIP("Fouet", "Glissement"),
    RISE("Envol", "Glissement"),
    ROLL("Roulade", "Rotation"),
    SPIN("Rotation", "Rotation"),
    SWING("Balancier", "Rotation"),
    FLIP_H("Retournement horizontal", "3D"),
    FLIP_V("Retournement vertical", "3D"),
    STRETCH_H("Étirement horizontal", "3D"),
    STRETCH_V("Étirement vertical", "3D"),
    SHAKE("Secousse", "Énergie"),
    GLITCH("Saut glitch", "Énergie"),
    FLICKER("Scintillement néon", "Énergie"),
}

/** Continuous animation applied for the whole life of a clip. */
@Serializable
enum class LoopMotion(val label: String) {
    NONE("Aucune"),
    PULSE("Pulsation"),
    BREATHE("Respiration"),
    HEARTBEAT("Battement de cœur"),
    ZOOM_PUNCH("Zoom choc (rythme)"),
    KEN_BURNS("Ken Burns"),
    SLOW_ZOOM_IN("Zoom lent avant"),
    SLOW_ZOOM_OUT("Zoom lent arrière"),
    PAN_LEFT("Panoramique lent ←"),
    PAN_RIGHT("Panoramique lent →"),
    FLOAT("Flottement"),
    BOUNCE("Rebond continu"),
    ORBIT("Orbite"),
    SWING("Balancier"),
    WIGGLE("Frétillement"),
    SPIN("Rotation continue"),
    JELLY("Gelée"),
    SHAKE("Tremblement caméra"),
    FLICKER("Néon"),
}

/**
 * Animations of a clip: entry, exit and loop ("combo"). Evaluated by
 * [com.superessence.cuttinx.core.anim.MotionEvaluator] on top of the keyframed transform, so preview
 * and export render them identically.
 */
@Serializable
data class ClipMotion(
    val animIn: MotionPreset = MotionPreset.NONE,
    val inUs: Long = 500_000,
    val animOut: MotionPreset = MotionPreset.NONE,
    val outUs: Long = 500_000,
    val loop: LoopMotion = LoopMotion.NONE,
    /** Loop cycles speed multiplier (0.1..5). */
    val loopSpeed: Double = 1.0,
    /** Loop intensity multiplier (0..3). */
    val loopAmount: Double = 1.0,
) {
    val isIdentity get() = animIn == MotionPreset.NONE && animOut == MotionPreset.NONE && loop == LoopMotion.NONE
}
