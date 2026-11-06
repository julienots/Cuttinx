package com.superessence.cuttinx.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class ClipKind { MEDIA, TEXT, SUBTITLE, COLOR, ADJUSTMENT }

/** A point of a progressive speed curve: [position] is 0..1 along the clip's source range. */
@Serializable
data class SpeedPoint(val position: Double, val speed: Double)

/**
 * One element on a track. Non destructive: [sourceInUs]/[sourceOutUs] select a range of the
 * referenced media, which is never modified.
 */
@Serializable
data class Clip(
    val id: String,
    val kind: ClipKind = ClipKind.MEDIA,
    val assetId: String? = null,
    val name: String = "",
    /** Start on the timeline (µs). */
    val startUs: Long = 0,
    /** For still images/text/colour clips sourceIn=0 and sourceOut=duration. */
    val sourceInUs: Long = 0,
    val sourceOutUs: Long = 0,
    /** Constant speed multiplier (0.1x..100x) used when [speedCurve] is empty. */
    val speed: Double = 1.0,
    val speedCurve: List<SpeedPoint> = emptyList(),
    val transform: Transform = Transform(),
    val keyframes: Map<String, KeyframeTrack> = emptyMap(),
    val effects: List<EffectInstance> = emptyList(),
    val audio: AudioSettings = AudioSettings(),
    val text: TextContent? = null,
    /** Colour clip fill (0xAARRGGBB). */
    val color: Long = 0xFF000000,
    /** Transition that blends the previous clip of the same track into this one. */
    val transitionIn: TransitionInstance? = null,
    val blendMode: BlendMode = BlendMode.NORMAL,
    val chromaKey: ChromaKey = ChromaKey(),
    val mask: Mask = Mask(),
    val groupId: String? = null,
    val label: Int = 0,
    val enabled: Boolean = true,
    /** Requested stabilisation strength (0 = off). Applied by the engine when supported. */
    val stabilization: Double = 0.0,
    /** Entry / exit / loop animations (applied on top of keyframes). */
    val motion: ClipMotion = ClipMotion(),
) {
    val sourceDurationUs: Long get() = (sourceOutUs - sourceInUs).coerceAtLeast(0)

    /** Duration on the timeline, taking the (possibly variable) speed into account. */
    val durationUs: Long get() = com.superessence.cuttinx.core.anim.SpeedMath.timelineDuration(this)

    val endUs: Long get() = startUs + durationUs

    fun contains(timeUs: Long) = timeUs >= startUs && timeUs < endUs
}
