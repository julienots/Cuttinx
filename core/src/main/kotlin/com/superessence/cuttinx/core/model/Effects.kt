package com.superessence.cuttinx.core.model

import kotlinx.serialization.Serializable

/** An effect applied to a clip. [effectId] references the [com.superessence.cuttinx.core.effects.EffectRegistry]. */
@Serializable
data class EffectInstance(
    val id: String,
    val effectId: String,
    val params: Map<String, Double> = emptyMap(),
    val enabled: Boolean = true,
)

@Serializable
data class TransitionInstance(
    val transitionId: String,
    val durationUs: Long,
    val params: Map<String, Double> = emptyMap(),
    val easing: Easing = Easing.Smooth,
)

@Serializable
enum class BlendMode { NORMAL, ADD, MULTIPLY, SCREEN, OVERLAY, DARKEN, LIGHTEN, DIFFERENCE }

@Serializable
data class ChromaKey(
    val enabled: Boolean = false,
    /** Key color as 0xRRGGBB. */
    val color: Long = 0x00FF00,
    val tolerance: Double = 0.35,
    val softness: Double = 0.1,
    /** Spill suppression (removes the green/blue fringe). */
    val spill: Double = 0.5,
)

@Serializable
enum class MaskShape { NONE, RECTANGLE, ELLIPSE, LINEAR, MIRROR }

@Serializable
data class Mask(
    val shape: MaskShape = MaskShape.NONE,
    /** Normalised centre (0..1). */
    val centerX: Double = 0.5,
    val centerY: Double = 0.5,
    val width: Double = 0.6,
    val height: Double = 0.6,
    val rotation: Double = 0.0,
    val feather: Double = 0.05,
    val invert: Boolean = false,
)
