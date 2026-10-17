package com.superessence.cuttinx.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class TextAlign { LEFT, CENTER, RIGHT }

@Serializable
enum class TextAnimation {
    NONE, FADE, POP, SLIDE_UP, SLIDE_DOWN, SLIDE_LEFT, SLIDE_RIGHT, TYPEWRITER, ZOOM, BOUNCE, BLUR_IN, WAVE,
    ELASTIC, DROP, SPIN, FLICKER, GLITCH, STRETCH, ROLL, RISE, SWING, SHAKE, ZOOM_OUT, KARAOKE,
}

@Serializable
data class TextStyle(
    val fontFamily: String = "sans-serif",
    /** Optional asset id of an imported font file. */
    val fontAssetId: String? = null,
    val sizeSp: Double = 48.0,
    /** 0xAARRGGBB */
    val color: Long = 0xFFFFFFFF,
    val bold: Boolean = true,
    val italic: Boolean = false,
    val weight: Int = 700,
    val align: TextAlign = TextAlign.CENTER,
    val letterSpacing: Double = 0.0,
    val lineSpacing: Double = 1.1,
    val strokeWidth: Double = 0.0,
    val strokeColor: Long = 0xFF000000,
    val shadowRadius: Double = 6.0,
    val shadowDx: Double = 0.0,
    val shadowDy: Double = 3.0,
    val shadowColor: Long = 0xAA000000,
    val backgroundColor: Long = 0x00000000,
    val backgroundPadding: Double = 16.0,
    val backgroundCorner: Double = 12.0,
    val gradientEnabled: Boolean = false,
    val gradientStart: Long = 0xFFFFD21F,
    val gradientEnd: Long = 0xFFFF6A00,
    val uppercase: Boolean = false,
)

@Serializable
data class TextContent(
    val text: String,
    val style: TextStyle = TextStyle(),
    val animIn: TextAnimation = TextAnimation.FADE,
    val animOut: TextAnimation = TextAnimation.FADE,
    val animInUs: Long = 400_000,
    val animOutUs: Long = 300_000,
    /** Max width as fraction of the frame width before wrapping. */
    val maxWidth: Double = 0.9,
)
