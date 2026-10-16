package com.superessence.cuttinx.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class MediaType { VIDEO, IMAGE, AUDIO, SUBTITLE, FONT, STICKER }

/**
 * A media file referenced by a project. CUTTINX never modifies the original file:
 * the project only stores a reference (content URI or path) plus probed metadata.
 */
@Serializable
data class MediaAsset(
    val id: String,
    val uri: String,
    val displayName: String,
    val type: MediaType,
    val mimeType: String = "",
    val durationUs: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val rotationDegrees: Int = 0,
    val frameRate: Float = 0f,
    val hasVideo: Boolean = type == MediaType.VIDEO || type == MediaType.IMAGE,
    val hasAudio: Boolean = type == MediaType.AUDIO,
    val sampleRate: Int = 0,
    val channelCount: Int = 0,
    val bitrate: Int = 0,
    val sizeBytes: Long = 0,
    val dateAddedMs: Long = 0,
    val folder: String = "",
    val favorite: Boolean = false,
    val missing: Boolean = false,
    val videoCodec: String = "",
    val audioCodec: String = "",
    val tags: List<String> = emptyList(),
) {
    /** Display width/height taking rotation metadata into account. */
    val displayWidth: Int get() = if (rotationDegrees % 180 != 0) height else width
    val displayHeight: Int get() = if (rotationDegrees % 180 != 0) width else height
    val isVisual: Boolean get() = type == MediaType.VIDEO || type == MediaType.IMAGE || type == MediaType.STICKER
}
