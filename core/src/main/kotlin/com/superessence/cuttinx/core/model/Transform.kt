package com.superessence.cuttinx.core.model

import kotlinx.serialization.Serializable

/**
 * Spatial transform of a visual clip inside the project canvas.
 * Position is the clip centre in normalised canvas coordinates (0..1, origin top-left).
 * Scale 1.0 means "fit inside the canvas" (letterboxed).
 */
@Serializable
data class Transform(
    val posX: Double = 0.5,
    val posY: Double = 0.5,
    val scaleX: Double = 1.0,
    val scaleY: Double = 1.0,
    val rotation: Double = 0.0,
    val opacity: Double = 1.0,
    val anchorX: Double = 0.5,
    val anchorY: Double = 0.5,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val crop: CropRect = CropRect(),
    /** When true the clip fills the canvas (cropping) instead of fitting it. */
    val fill: Boolean = false,
)

/** Fractions cropped from each side (0..1). */
@Serializable
data class CropRect(val left: Double = 0.0, val top: Double = 0.0, val right: Double = 0.0, val bottom: Double = 0.0) {
    val isIdentity get() = left == 0.0 && top == 0.0 && right == 0.0 && bottom == 0.0
}
