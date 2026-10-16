package com.superessence.cuttinx.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class TrackKind {
    /** Video/image clips. The first VIDEO track is the main (bottom) track. */
    VIDEO,
    /** Audio-only clips: music, voice-over, ambience, sound effects. */
    AUDIO,
    /** Titles and graphics rendered over the video. */
    TEXT,
    /** Subtitle segments. */
    SUBTITLE,
    /** Adjustment layers: effects applied to everything below. */
    EFFECT,
}

@Serializable
enum class AudioRole { GENERAL, MUSIC, VOICE_OVER, AMBIENCE, SFX }

@Serializable
data class Track(
    val id: String,
    val kind: TrackKind,
    val name: String = "",
    val clips: List<Clip> = emptyList(),
    val locked: Boolean = false,
    val hidden: Boolean = false,
    val muted: Boolean = false,
    val volume: Double = 1.0,
    val audioRole: AudioRole = AudioRole.GENERAL,
) {
    val endUs: Long get() = clips.maxOfOrNull { it.endUs } ?: 0
    fun sortedClips() = clips.sortedBy { it.startUs }
}
