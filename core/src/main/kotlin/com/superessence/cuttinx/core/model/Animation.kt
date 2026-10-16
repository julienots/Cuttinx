package com.superessence.cuttinx.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class EasingType { HOLD, LINEAR, EASE_IN, EASE_OUT, EASE_IN_OUT, BEZIER }

/**
 * Interpolation used from this keyframe to the next one.
 * For [EasingType.BEZIER], (x1,y1,x2,y2) are the two control points of a CSS-like cubic Bézier.
 */
@Serializable
data class Easing(
    val type: EasingType = EasingType.LINEAR,
    val x1: Double = 0.25, val y1: Double = 0.1,
    val x2: Double = 0.25, val y2: Double = 1.0,
) {
    companion object {
        val Linear = Easing(EasingType.LINEAR)
        val Hold = Easing(EasingType.HOLD)
        val Smooth = Easing(EasingType.EASE_IN_OUT)
        val EaseIn = Easing(EasingType.EASE_IN)
        val EaseOut = Easing(EasingType.EASE_OUT)
        fun bezier(x1: Double, y1: Double, x2: Double, y2: Double) = Easing(EasingType.BEZIER, x1, y1, x2, y2)
    }
}

/** A keyframe; [timeUs] is relative to the start of the clip on the timeline. */
@Serializable
data class Keyframe(val timeUs: Long, val value: Double, val easing: Easing = Easing.Smooth)

/** Keyframes of a single animatable property, always kept sorted by time. */
@Serializable
data class KeyframeTrack(val keyframes: List<Keyframe> = emptyList()) {
    fun sorted() = KeyframeTrack(keyframes.sortedBy { it.timeUs })
    val isEmpty get() = keyframes.isEmpty()
}

/** Names of animatable properties. Effect parameters use "fx:<effectInstanceId>:<param>". */
object Prop {
    const val POS_X = "posX"
    const val POS_Y = "posY"
    const val SCALE = "scale"
    const val SCALE_X = "scaleX"
    const val SCALE_Y = "scaleY"
    const val ROTATION = "rotation"
    const val OPACITY = "opacity"
    const val VOLUME = "volume"
    const val PAN = "pan"
    const val TEXT_COLOR_R = "textR"
    const val TEXT_COLOR_G = "textG"
    const val TEXT_COLOR_B = "textB"
    const val MASK_X = "maskX"
    const val MASK_Y = "maskY"
    const val MASK_W = "maskW"
    const val MASK_H = "maskH"
    const val MASK_FEATHER = "maskFeather"
    const val CROP_LEFT = "cropL"
    const val CROP_TOP = "cropT"
    const val CROP_RIGHT = "cropR"
    const val CROP_BOTTOM = "cropB"
    fun effect(instanceId: String, param: String) = "fx:$instanceId:$param"

    val transformProps = listOf(POS_X, POS_Y, SCALE, ROTATION, OPACITY)
}
