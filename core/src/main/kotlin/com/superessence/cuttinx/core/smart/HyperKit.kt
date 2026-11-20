package com.superessence.cuttinx.core.smart

import com.superessence.cuttinx.core.effects.EffectRegistry
import com.superessence.cuttinx.core.effects.TransitionRegistry
import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.timeline.TimelineEditor
import com.superessence.cuttinx.core.util.Ids

/**
 * CUTTINX 3.5 « Hyper réalité » montage gestures: one tap each, no analysis, one undoable edit.
 * Like [MontageKit], they never touch locked tracks and return the project unchanged when there is
 * nothing to do. Layers they add are replaced, not stacked, when the gesture is used again.
 */
object HyperKit {
    val gestures = listOf(
        "hyper_mode" to "Mode Hyper", "zoom_cuts" to "Zoom alterné", "hyper_transitions" to "Transitions Hyper",
        "flash_cuts" to "Flash coupes", "sound_8d" to "Son 8D", "loud_master" to "Son pro express", "golden_hour" to "Soleil couchant",
    )

    /** Search words of each gesture (Actions rapides). */
    val keywords = mapOf(
        "hyper_mode" to "cosmique énergie aura bloom effet wow impressionnant", "zoom_cuts" to "zoom coupe jump cut alterner rapproché youtube",
        "hyper_transitions" to "transition supernova éclair verre trou noir cellules liquide", "flash_cuts" to "flash blanc coupe impact éclair",
        "sound_8d" to "8d casque tourner spatial musique", "loud_master" to "mastering volume fort clair compresseur limiteur",
        "golden_hour" to "coucher soleil chaud doré lumière look",
    )

    /** The 6 transitions of 3.5, used in turn by [hyperTransitions]. */
    val TRANSITIONS = listOf("supernova", "lightning", "shatter", "liquid_morph", "cells", "black_hole")

    fun run(id: String, p: Project): Project? = when (id) {
        "hyper_mode" -> hyperMode(p)
        "zoom_cuts" -> zoomCuts(p)
        "hyper_transitions" -> hyperTransitions(p)
        "flash_cuts" -> flashCuts(p)
        "sound_8d" -> sound8d(p)
        "loud_master" -> loudMaster(p)
        "golden_hour" -> SmartTools.applyLook(p, "Soleil couchant")
        else -> null
    }

    private fun main(p: Project) = SmartTools.mainTrack(p)?.takeIf { !it.locked }

    const val HYPER_TRACK = "Mode Hyper"

    /** One effects layer over the whole film: bloom, a soft energy aura and lens character (replaced, not stacked). */
    fun hyperMode(p: Project): Project {
        val d = p.durationUs
        if (d <= 0) return p
        val cleared = p.copy(tracks = p.tracks.map { t -> if (t.kind == TrackKind.EFFECT && t.name == HYPER_TRACK && !t.locked) t.copy(clips = emptyList()) else t })
        val fx = listOf(
            "ultra_bloom" to mapOf("amount" to 0.45, "threshold" to 0.55),
            "cos_aura" to mapOf("amount" to 0.55, "size" to 4.0),
            "ultra_lens_pro" to mapOf("amount" to 0.5, "flare" to 0.4),
            "adjust" to mapOf("contrast" to 0.08, "vibrance" to 0.2),
        ).mapNotNull { (id, prm) -> EffectRegistry[id]?.let { def -> EffectInstance(Ids.new("fx"), id, def.defaults() + prm.filterKeys { k -> def.param(k) != null }) } }
        val layer = TimelineEditor.adjustmentClip(0, d).copy(name = HYPER_TRACK, effects = fx)
        val (q, track) = Kit.namedTrack(cleared, TrackKind.EFFECT, HYPER_TRACK)
        return runCatching { TimelineEditor.insertClip(q, track.id, layer) }.getOrDefault(p)
    }

    /**
     * Alternate zoom ("jump-cut zoom" of YouTubers): every other shot of the main track is framed
     * [zoom]× tighter, so each cut feels like a camera change. Shots already zoomed are left alone.
     */
    fun zoomCuts(p: Project, zoom: Double = 1.18): Project {
        val t = main(p) ?: return p
        return t.sortedClips().filter { it.kind == ClipKind.MEDIA }.withIndex().filter { it.index % 2 == 1 }.fold(p) { acc, (_, c) ->
            if (c.transform.scaleX > 1.01 || c.transform.scaleY > 1.01) acc
            else Kit.edit(acc, c.id) { x -> x.copy(transform = x.transform.copy(scaleX = x.transform.scaleX * zoom, scaleY = x.transform.scaleY * zoom)) }
        }
    }

    /** The 3.5 transitions on every cut of the main track, in turn. */
    fun hyperTransitions(p: Project): Project {
        val t = main(p) ?: return p
        return t.sortedClips().drop(1).withIndex().fold(p) { acc, (i, c) ->
            val id = TRANSITIONS[i % TRANSITIONS.size]
            val def = TransitionRegistry[id] ?: return@fold acc
            runCatching { TimelineEditor.setTransition(acc, c.id, TransitionInstance(id, minOf(def.defaultDurationUs, c.durationUs / 2), def.params.associate { it.key to it.default })) }.getOrDefault(acc)
        }
    }

    const val FLASH_TRACK = "Flashs"

    /** A short white flash on every cut of the main track (the previous flashes are replaced). */
    fun flashCuts(p: Project): Project {
        val t = main(p) ?: return p
        val cuts = t.sortedClips().drop(1).map { it.startUs }.filter { it > 0 }
        if (cuts.isEmpty()) return p
        val cleared = p.copy(tracks = p.tracks.map { tr -> if (tr.kind == TrackKind.EFFECT && tr.name == FLASH_TRACK && !tr.locked) tr.copy(clips = emptyList()) else tr })
        return cuts.fold(cleared) { acc, at -> Catalog.flash(acc, (at - 40_000).coerceAtLeast(0)) }
    }

    /** Clips that carry sound: music first; if there is no music, every audio clip and every video with sound. */
    private fun soundClips(p: Project, musicOnly: Boolean): List<String> {
        val music = p.tracks.filter { it.kind == TrackKind.AUDIO && it.audioRole == AudioRole.MUSIC && !it.locked }.flatMap { it.clips }.map { it.id }
        if (musicOnly && music.isNotEmpty()) return music
        val audio = p.tracks.filter { it.kind == TrackKind.AUDIO && !it.locked }.flatMap { it.clips }.map { it.id }
        val video = p.tracks.filter { it.kind != TrackKind.AUDIO && !it.locked }.flatMap { it.clips }.filter { Kit.hasSound(p, it) }.map { it.id }
        return if (musicOnly) audio.ifEmpty { video } else audio + video
    }

    private fun addAudioFx(p: Project, ids: List<String>, effectId: String, params: Map<String, Double> = emptyMap()): Project {
        val def = EffectRegistry[effectId] ?: return p
        return Kit.editAll(p, ids) { c ->
            val others = c.audio.effects.filterNot { it.effectId == effectId }
            c.copy(audio = c.audio.copy(effects = others + EffectInstance(Ids.new("fx"), effectId, def.defaults() + params)))
        }
    }

    /** The music turns around the head (8D audio); without music, all the sound. */
    fun sound8d(p: Project): Project = addAudioFx(p, soundClips(p, musicOnly = true), "a_8d")

    /** Mastering express on every clip with sound: loud, clear, never clipping. */
    fun loudMaster(p: Project): Project = addAudioFx(p, soundClips(p, musicOnly = false), "a_master")
}
