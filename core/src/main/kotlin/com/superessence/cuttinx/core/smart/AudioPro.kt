package com.superessence.cuttinx.core.smart

import com.superessence.cuttinx.core.effects.EffectRegistry
import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.timeline.TimelineEditor
import com.superessence.cuttinx.core.util.Ids
import kotlin.math.pow

/** 8-band equaliser presets (gains in dB for 60, 170, 310, 600 Hz, 1, 3, 6, 12 kHz). */
enum class EqPreset(val label: String, val gains: List<Double>) {
    CLEAR_VOICE("Voix claire", listOf(-6.0, -3.0, -1.0, 0.0, 1.5, 3.5, 3.0, 1.5)),
    WARM_VOICE("Voix chaude (podcast)", listOf(-2.0, 2.5, 2.0, 0.5, 0.0, 1.0, 0.0, -1.0)),
    BASS_BOOST("Basses renforcées", listOf(6.0, 4.0, 1.5, 0.0, 0.0, 0.0, 0.0, 0.0)),
    BRIGHT("Aigus brillants", listOf(0.0, 0.0, 0.0, 0.0, 0.5, 2.0, 4.0, 5.0)),
    LESS_HISS("Atténuer le souffle", listOf(0.0, 0.0, 0.0, 0.0, 0.0, -1.0, -4.0, -8.0)),
    LESS_RUMBLE("Supprimer les grondements", listOf(-12.0, -5.0, -1.0, 0.0, 0.0, 0.0, 0.0, 0.0)),
    LOUDNESS("Volume perçu (loudness)", listOf(4.0, 2.0, 0.0, -1.0, -1.0, 0.0, 2.0, 3.0)),
    FLAT("Égaliseur à plat", listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)),
}

/** Sound tools: voice chains, equaliser, mix levels, fades and stereo. */
object AudioPro {
    private fun fx(id: String, vararg params: Pair<String, Double>): EffectInstance? =
        EffectRegistry[id]?.let { d -> EffectInstance("au_" + Ids.new(), id, d.defaults() + params.toMap().filterKeys { k -> d.params.any { it.key == k } }) }

    /** Selected sound clips, else every clip that carries sound. */
    fun targets(p: Project, selection: List<String>) = Kit.selOrAll(p, selection) { t, c -> !t.muted && (Kit.hasSound(p, c)) }

    /** Clips of voice tracks, else every clip with sound outside the music tracks. */
    fun voices(p: Project, selection: List<String>): List<String> {
        val sel = selection.mapNotNull { p.findClip(it) }.filter { (_, c) -> Kit.hasSound(p, c) }.map { it.second.id }
        if (sel.isNotEmpty()) return sel
        val vo = p.tracks.filter { it.kind == TrackKind.AUDIO && it.audioRole == AudioRole.VOICE_OVER && !it.locked }.flatMap { it.clips }.map { it.id }
        if (vo.isNotEmpty()) return vo
        return p.tracks.filter { !it.locked && it.kind == TrackKind.VIDEO }.flatMap { t -> t.clips.filter { Kit.hasSound(p, it) } }.map { it.id }
    }

    fun music(p: Project): List<String> = p.tracks.filter { it.kind == TrackKind.AUDIO && it.audioRole == AudioRole.MUSIC && !it.locked }.flatMap { it.clips }.map { it.id }

    fun eq(p: Project, ids: List<String>, preset: EqPreset): Project = Kit.editAll(p, ids) { c ->
        val bands = AudioSettings.DEFAULT_EQ.mapIndexed { i, b -> b.copy(gainDb = preset.gains.getOrElse(i) { 0.0 }) }
        c.copy(audio = c.audio.copy(eqEnabled = preset != EqPreset.FLAT, eqBands = bands))
    }

    private fun chain(p: Project, ids: List<String>, tag: String, effects: List<EffectInstance>): Project = Kit.editAll(p, ids) { c ->
        c.copy(audio = c.audio.copy(effects = c.audio.effects.filterNot { it.id.startsWith("au_$tag") } + effects.map { it.copy(id = "au_${tag}_" + Ids.new()) }))
    }

    /** Broadcast voice: rumble cut, gentle compression, de-essing, presence and a limiter. */
    fun podcastVoice(p: Project, ids: List<String>): Project = eq(
        chain(p, ids, "voice", listOfNotNull(fx("a_highpass"), fx("a_denoise"), fx("a_compressor"), fx("a_deesser"), fx("a_limiter"))), ids, EqPreset.WARM_VOICE)

    fun denoise(p: Project, ids: List<String>): Project = chain(p, ids, "clean", listOfNotNull(fx("a_gate"), fx("a_denoise")))

    /** One of the voice changers (robot, radio, hélium…). */
    fun voiceEffect(p: Project, ids: List<String>, effectId: String): Project =
        chain(p, ids, "vfx", listOfNotNull(fx(effectId)))

    fun removeAudioEffects(p: Project, ids: List<String>): Project = Kit.editAll(p, ids) { c -> c.copy(audio = c.audio.copy(effects = emptyList(), eqEnabled = false)) }

    /** Original sound of the video shots off (music and voice-over stay). */
    fun muteVideos(p: Project, mute: Boolean = true): Project = Kit.editAll(p, p.tracks.filter { it.kind == TrackKind.VIDEO }.flatMap { it.clips }.map { it.id }) { c ->
        c.copy(audio = c.audio.copy(muted = mute))
    }

    /** Sets the level of the clips to [db] decibels. */
    fun level(p: Project, ids: List<String>, db: Double): Project = Kit.editAll(p, ids) { c -> c.copy(audio = c.audio.copy(volume = 10.0.pow(db / 20.0).coerceIn(0.0, 4.0))) }

    /** Soft fades on every sound clip so nothing starts or stops abruptly. */
    fun softEdges(p: Project, ids: List<String>, fadeUs: Long = 300_000): Project = Kit.editAll(p, ids) { c ->
        val f = fadeUs.coerceAtMost(c.durationUs / 3)
        c.copy(audio = c.audio.copy(fadeInUs = maxOf(c.audio.fadeInUs, f), fadeOutUs = maxOf(c.audio.fadeOutUs, f)))
    }

    /** Overlaps consecutive music clips by [overlapUs] on two tracks so they cross-fade instead of cutting. */
    fun crossfadeMusic(p: Project, overlapUs: Long = 1_500_000): Project {
        val t = p.tracks.firstOrNull { it.kind == TrackKind.AUDIO && it.audioRole == AudioRole.MUSIC && !it.locked && it.clips.size >= 2 } ?: return p
        val clips = t.sortedClips()
        if (clips.any { it.durationUs < overlapUs * 2 }) return softEdges(p, clips.map { it.id })
        val (q, alt) = Kit.namedTrack(p, TrackKind.AUDIO, "Musique (fondus)", AudioRole.MUSIC)
        var np = q
        var shift = 0L
        for ((i, c) in clips.withIndex()) {
            if (i > 0) shift += overlapUs
            val faded = c.copy(startUs = c.startUs - shift, audio = c.audio.copy(fadeInUs = if (i > 0) overlapUs else c.audio.fadeInUs, fadeOutUs = if (i < clips.lastIndex) overlapUs else c.audio.fadeOutUs))
            np = if (i % 2 == 1) {
                val r = runCatching { TimelineEditor.deleteClips(np, listOf(c.id)) }.getOrDefault(np)
                TimelineEditor.insertClip(r, alt.id, faded)
            } else Kit.edit(np, c.id) { faded }
        }
        return np
    }

    /** Alternates shots slightly left / right for a wider, livelier mix. */
    fun stereoAlternate(p: Project, ids: List<String>, amount: Double = 0.25): Project =
        ids.withIndex().fold(p) { acc, (i, id) -> Kit.edit(acc, id) { c -> c.copy(audio = c.audio.copy(pan = if (i % 2 == 0) -amount else amount)) } }

    fun center(p: Project, ids: List<String>): Project = Kit.editAll(p, ids) { c -> c.copy(audio = c.audio.copy(pan = 0.0)) }

    /** Same level everywhere: every clip back to 0 dB, music at -12 dB under the rest. */
    fun balance(p: Project): Project {
        val m = music(p).toSet()
        return Kit.editAll(p, p.allClips.filter { Kit.hasSound(p, it) }.map { it.id }) { c ->
            c.copy(audio = c.audio.copy(volume = if (c.id in m) 10.0.pow(-12.0 / 20.0) else 1.0, muted = false))
        }
    }
}
