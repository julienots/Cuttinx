package com.superessence.cuttinx.engine

import com.superessence.cuttinx.core.model.Project

/**
 * Everything that requires rebuilding the Media3 composition. Edits that leave this key unchanged
 * (transform, keyframes, effect parameters, volume, chroma/mask parameters...) are applied live.
 */
object StructureKey {
    fun of(p: Project, outW: Int, outH: Int, fps: Int): String = buildString {
        append(outW).append('x').append(outH).append('@').append(fps).append('|').append(p.format.backgroundColor and 0xFFFFFF).append('|')
        for (t in p.tracks) {
            append(t.id).append(t.kind).append(t.hidden).append(t.muted).append('[')
            for (c in t.clips) {
                append(c.id).append(c.kind).append(c.assetId).append(c.enabled).append(':').append(c.startUs).append(',').append(c.sourceInUs).append(',').append(c.sourceOutUs)
                append(',').append(c.speed).append(c.speedCurve.hashCode()).append(c.audio.preservePitch).append(c.audio.muted)
                append(c.effects.joinToString(",") { it.id + it.effectId })
                append(c.audio.effects.joinToString(",") { it.effectId + it.enabled + (if (it.effectId == "v_pitch") it.params.hashCode() else 0) })
                append(c.transitionIn?.let { it.transitionId + it.durationUs } ?: "-")
                append(c.chromaKey.enabled).append(c.mask.shape).append(c.transform.crop.hashCode()).append(c.transform.fill)
                append(c.text?.hashCode() ?: 0).append(c.color)
                append(';')
            }
            append(']')
        }
        for (a in p.media) append(a.id).append(a.uri).append(a.missing)
    }
}
