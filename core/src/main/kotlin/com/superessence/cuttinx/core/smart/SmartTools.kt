package com.superessence.cuttinx.core.smart

import com.superessence.cuttinx.core.autoedit.ProTools
import com.superessence.cuttinx.core.effects.Presets
import com.superessence.cuttinx.core.effects.TransitionRegistry
import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.timeline.TimelineEditor
import com.superessence.cuttinx.core.util.Ids
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** Where a video will be published: format, interface zones to keep free, editing pace. */
enum class Platform(
    val label: String,
    val aspect: AspectPreset,
    /** Fractions of the frame covered by the app interface (top, bottom, left, right). */
    val safeTop: Double, val safeBottom: Double, val safeLeft: Double, val safeRight: Double,
    /** Average shot length that keeps viewers watching (seconds). */
    val idealShotSec: Double,
    /** Recommended maximum length (seconds), 0 = none. */
    val maxSec: Int,
) {
    TIKTOK("TikTok", AspectPreset.PORTRAIT_9_16, 0.10, 0.22, 0.04, 0.16, 2.2, 180),
    REELS("Instagram Reels", AspectPreset.PORTRAIT_9_16, 0.12, 0.24, 0.04, 0.14, 2.5, 90),
    SHORTS("YouTube Shorts", AspectPreset.PORTRAIT_9_16, 0.10, 0.20, 0.04, 0.14, 2.5, 180),
    YOUTUBE("YouTube", AspectPreset.LANDSCAPE_16_9, 0.06, 0.12, 0.04, 0.04, 5.0, 0),
    ;

    val vertical get() = aspect.h > aspect.w
}

/** Speed ramp shapes for the "rampe de vitesse" tool. */
enum class RampPreset(val label: String, val curve: List<SpeedPoint>) {
    HERO("Héros (rapide → ralenti → rapide)", listOf(SpeedPoint(0.0, 2.2), SpeedPoint(0.35, 2.2), SpeedPoint(0.45, 0.3), SpeedPoint(0.65, 0.3), SpeedPoint(0.75, 2.2), SpeedPoint(1.0, 2.2))),
    BULLET("Bullet time (ralenti au centre)", listOf(SpeedPoint(0.0, 1.0), SpeedPoint(0.4, 1.0), SpeedPoint(0.5, 0.2), SpeedPoint(0.6, 1.0), SpeedPoint(1.0, 1.0))),
    RUSH("Montée (de plus en plus vite)", listOf(SpeedPoint(0.0, 0.6), SpeedPoint(0.5, 1.4), SpeedPoint(1.0, 3.0))),
    BRAKE("Freinage (rapide puis ralenti)", listOf(SpeedPoint(0.0, 3.0), SpeedPoint(0.5, 1.2), SpeedPoint(1.0, 0.4))),
    PULSE("Pulsation (alterné)", listOf(SpeedPoint(0.0, 1.8), SpeedPoint(0.2, 0.5), SpeedPoint(0.4, 1.8), SpeedPoint(0.6, 0.5), SpeedPoint(0.8, 1.8), SpeedPoint(1.0, 0.5))),
}

/**
 * Exclusive "smart" editing tools. Every function is a pure project edit (one undo step in the
 * editors) that only produces ordinary clips, keyframes, fades and transitions: the result stays
 * fully editable by hand afterwards.
 */
object SmartTools {
    const val FRAME_US = 33_333L

    fun mainTrack(p: Project) = p.tracks.firstOrNull { it.kind == TrackKind.VIDEO }

    private fun replaceTrack(p: Project, t: Track) = p.copy(tracks = p.tracks.map { if (it.id == t.id) t else it })

    // ------------------------------------------------------------------ duration

    /**
     * Makes the montage last [targetUs] by tightening every shot of the main track proportionally
     * to how much it can lose (the middle of each shot, where the action usually is, is kept).
     * Titles, music and overlays follow their shot. Returns the project unchanged when already short enough.
     */
    fun fitToDuration(p: Project, targetUs: Long, minShotUs: Long = 700_000): Project {
        val main = mainTrack(p) ?: return p
        val total = main.endUs
        if (total <= targetUs || main.clips.isEmpty()) return p
        val sorted = main.sortedClips()
        val canLose = sorted.map { c -> if (c.speedCurve.isNotEmpty()) 0L else (c.durationUs - minShotUs).coerceAtLeast(0) }
        val loseTotal = canLose.sum()
        if (loseTotal <= 0) return p
        val k = min(1.0, (total - targetUs).toDouble() / loseTotal)
        // old -> new time map, piecewise along the main track
        val anchors = ArrayList<Pair<Long, Long>>() // (old, new)
        var cursor = 0L
        val newClips = sorted.mapIndexed { i, c ->
            val cut = (canLose[i] * k).roundToLong()
            val newDur = c.durationUs - cut
            val src = (cut / 2 * c.speed).roundToLong()
            val start = if (c.transitionIn != null) cursor - min(c.transitionIn.durationUs, newDur / 2) else cursor
            val nc = c.copy(
                startUs = start.coerceAtLeast(0),
                sourceInUs = c.sourceInUs + src,
                sourceOutUs = (c.sourceOutUs - ((cut - cut / 2) * c.speed).roundToLong()).coerceAtLeast(c.sourceInUs + src + 1),
                keyframes = c.keyframes.mapValues { (_, tr) -> KeyframeTrack(tr.keyframes.map { kf -> kf.copy(timeUs = ((kf.timeUs - cut / 2).coerceIn(0, newDur))) }.distinctBy { it.timeUs }) },
            )
            anchors += c.startUs to nc.startUs
            anchors += (c.startUs + cut / 2) to nc.startUs
            anchors += (c.endUs - (cut - cut / 2)) to nc.endUs
            anchors += c.endUs to nc.endUs
            cursor = nc.endUs
            nc
        }
        val newTotal = cursor
        fun map(t: Long): Long {
            val a = anchors.sortedBy { it.first }
            if (t <= a.first().first) return a.first().second
            for (j in 1 until a.size) {
                val (o0, n0) = a[j - 1]; val (o1, n1) = a[j]
                if (t <= o1) return if (o1 == o0) n1 else n0 + ((t - o0).toDouble() / (o1 - o0) * (n1 - n0)).roundToLong()
            }
            return a.last().second + (t - a.last().first)
        }
        var np = replaceTrack(p, main.copy(clips = newClips))
        np = np.copy(tracks = np.tracks.map { t ->
            if (t.id == main.id) t else t.copy(clips = t.clips.mapNotNull { c ->
                val ns = map(c.startUs)
                if (ns >= newTotal) return@mapNotNull null
                val isMusic = t.kind == TrackKind.AUDIO && t.audioRole == AudioRole.MUSIC
                // overlays keep their length (clamped to their shot), music is simply cut at the end
                val wanted = if (isMusic) c.durationUs else max(map(c.endUs) - ns, min(c.durationUs, 600_000))
                val d = min(wanted, newTotal - ns)
                c.copy(startUs = ns, sourceOutUs = c.sourceInUs + (d * c.speed).roundToLong().coerceAtLeast(1), speedCurve = if (d != c.durationUs) emptyList() else c.speedCurve,
                    audio = if (isMusic) c.audio.copy(fadeOutUs = max(c.audio.fadeOutUs, 1_200_000)) else c.audio)
            })
        }, markers = p.markers.map { it.copy(timeUs = map(it.timeUs)) }.filter { it.timeUs < newTotal })
        return np
    }

    // ------------------------------------------------------------------ audio

    /** Time ranges where someone speaks: voice-over clips and main-track videos that have sound. */
    fun voiceRanges(p: Project, mergeGapUs: Long = 400_000): List<LongRange> {
        val raw = p.tracks.flatMap { t ->
            when {
                t.muted -> emptyList()
                t.kind == TrackKind.AUDIO && t.audioRole == AudioRole.VOICE_OVER -> t.clips.filter { !it.audio.muted }
                t.kind == TrackKind.VIDEO -> t.clips.filter { c -> !c.audio.muted && c.audio.volume > 0.05 && p.asset(c.assetId)?.let { it.hasAudio && it.type == MediaType.VIDEO } == true }
                else -> emptyList()
            }
        }.map { it.startUs..it.endUs }.sortedBy { it.first }
        val out = ArrayList<LongRange>()
        for (r in raw) {
            val last = out.lastOrNull()
            if (last != null && r.first <= last.last + mergeGapUs) out[out.lastIndex] = last.first..max(last.last, r.last) else out += r
        }
        return out
    }

    private fun musicClips(p: Project) = p.tracks.filter { it.kind == TrackKind.AUDIO && (it.audioRole == AudioRole.MUSIC || it.audioRole == AudioRole.GENERAL) }.flatMap { t -> t.clips.map { t to it } }

    /**
     * Auto-ducking: the music smoothly goes down to [duckTo] (relative) while someone speaks and
     * comes back up in between, with [rampUs] ramps. Written as volume keyframes, editable after.
     */
    fun autoDuck(p: Project, duckTo: Double = 0.25, rampUs: Long = 250_000): Project {
        val voice = voiceRanges(p)
        if (voice.isEmpty()) return p
        var np = p
        for ((_, c) in musicClips(p)) {
            val base = c.audio.volume
            val low = base * duckTo
            val kfs = ArrayList<Keyframe>()
            kfs += Keyframe(0, base, Easing.Linear)
            for (r in voice) {
                val s = r.first - c.startUs; val e = r.last - c.startUs
                if (e <= 0 || s >= c.durationUs) continue
                val a = (s - rampUs).coerceAtLeast(0)
                if (a > (kfs.last().timeUs)) kfs += Keyframe(a, base, Easing.Smooth)
                kfs += Keyframe(s.coerceIn(0, c.durationUs), low, Easing.Linear)
                kfs += Keyframe(e.coerceIn(0, c.durationUs), low, Easing.Smooth)
                kfs += Keyframe((e + rampUs).coerceAtMost(c.durationUs), base, Easing.Linear)
            }
            if (kfs.size <= 1) continue
            // when two keys share a time, the later one (the duck) wins
            val track = KeyframeTrack(kfs.groupBy { it.timeUs }.map { it.value.last() }.sortedBy { it.timeUs })
            np = TimelineEditor.mapClips(np) { if (it.id == c.id) it.copy(keyframes = it.keyframes + (Prop.VOLUME to track)) else it }
        }
        return np
    }

    /** 15 ms micro-fades on every audio cut: removes the little "clicks" heard at hard cuts. */
    fun microFades(p: Project, fadeUs: Long = 15_000): Project = p.copy(tracks = p.tracks.map { t ->
        if (t.kind != TrackKind.AUDIO && t.kind != TrackKind.VIDEO) t
        else t.copy(clips = t.clips.map { c ->
            if (c.kind != ClipKind.MEDIA) c
            else c.copy(audio = c.audio.copy(fadeInUs = max(c.audio.fadeInUs, fadeUs), fadeOutUs = max(c.audio.fadeOutUs, fadeUs)))
        })
    })

    /** Music that stops exactly with the video, with a gentle fade-out. */
    fun fitMusicToVideo(p: Project, fadeUs: Long = 1_500_000): Project {
        val end = mainTrack(p)?.endUs ?: return p
        return p.copy(tracks = p.tracks.map { t ->
            if (t.kind != TrackKind.AUDIO || t.audioRole != AudioRole.MUSIC) t
            else t.copy(clips = t.clips.mapNotNull { c ->
                when {
                    c.startUs >= end -> null
                    c.endUs > end -> c.copy(sourceOutUs = c.sourceInUs + ((end - c.startUs) * c.speed).roundToLong(), speedCurve = emptyList(), audio = c.audio.copy(fadeOutUs = fadeUs))
                    c.endUs >= end - 300_000 -> c.copy(audio = c.audio.copy(fadeOutUs = max(c.audio.fadeOutUs, fadeUs)))
                    else -> c
                }
            })
        })
    }

    // ------------------------------------------------------------------ cleaning

    /**
     * Meticulous clean-up: removes leftover fragments shorter than [minFrames] frames, closes
     * "flash frame" gaps of the main track, deletes empty extra tracks and duplicate markers.
     */
    fun cleanUp(p: Project, minFrames: Int = 2): Project {
        val frame = 1_000_000L / p.format.frameRate.coerceAtLeast(1)
        val main = mainTrack(p)
        var tracks = p.tracks.map { t -> t.copy(clips = t.clips.filter { it.durationUs >= frame * minFrames }) }
        tracks = tracks.map { t -> if (t.id == main?.id) TimelineEditor.closeGaps(t) else closeTinyGaps(t, frame * 3) }
        tracks = tracks.filter { it.clips.isNotEmpty() || it.id == main?.id }
        val markers = p.markers.sortedBy { it.timeUs }.fold(ArrayList<Marker>()) { acc, m -> if (acc.none { abs(it.timeUs - m.timeUs) < frame }) acc += m; acc }
        return p.copy(tracks = tracks, markers = markers)
    }

    private fun closeTinyGaps(t: Track, maxGapUs: Long): Track {
        val s = t.sortedClips()
        var shift = 0L
        val out = s.mapIndexed { i, c ->
            if (i > 0) { val gap = c.startUs - shift - s[i - 1].endUs; if (gap in 1..maxGapUs) shift += gap }
            c.copy(startUs = c.startUs - shift)
        }
        return t.copy(clips = out)
    }

    // ------------------------------------------------------------------ texts

    /** Moves titles and subtitles out of the zones covered by the app buttons / captions of [platform]. */
    fun safeZones(p: Project, platform: Platform): Project {
        val halfH = 0.05
        val top = platform.safeTop + halfH; val bottom = 1 - platform.safeBottom - halfH
        fun clampY(y: Double) = y.coerceIn(top, bottom)
        return p.copy(tracks = p.tracks.map { t ->
            if (t.kind != TrackKind.TEXT && t.kind != TrackKind.SUBTITLE) t
            else t.copy(clips = t.clips.map { c ->
                val tx = c.text ?: return@map c
                val x = c.transform.posX.coerceIn(platform.safeLeft + 0.1, 1 - platform.safeRight - 0.1)
                // the text block must not run under the side buttons
                val room = 2 * min(x - platform.safeLeft, 1 - platform.safeRight - x)
                c.copy(
                    transform = c.transform.copy(posX = x, posY = clampY(c.transform.posY)),
                    keyframes = c.keyframes.mapValues { (k, tr) -> if (k == Prop.POS_Y) KeyframeTrack(tr.keyframes.map { it.copy(value = clampY(it.value)) }) else tr },
                    text = tx.copy(maxWidth = min(tx.maxWidth, room.coerceAtLeast(0.4))),
                )
            })
        })
    }

    /** Characters a viewer reads per second, comfortably. */
    const val READING_CPS = 17.0

    /**
     * Splits long titles / subtitles into short readable chunks (at most [lines] lines of
     * [maxChars] characters), each shown for a time proportional to its length.
     */
    fun readableSubtitles(p: Project, maxChars: Int = 32, lines: Int = 2): Project {
        var np = p
        for (t in p.tracks.filter { it.kind == TrackKind.SUBTITLE || it.kind == TrackKind.TEXT }) {
            val out = ArrayList<Clip>()
            for (c in t.sortedClips()) {
                val tx = c.text
                if (tx == null || tx.text.length <= maxChars * lines) { out += c; continue }
                val chunks = chunk(tx.text, maxChars, lines)
                val total = chunks.sumOf { it.length }.coerceAtLeast(1)
                var s = c.startUs
                chunks.forEachIndexed { i, ch ->
                    val d = if (i == chunks.lastIndex) c.endUs - s else c.durationUs * ch.length / total
                    out += c.copy(id = if (i == 0) c.id else Ids.new("txt"), startUs = s, sourceInUs = 0, sourceOutUs = d, speed = 1.0, speedCurve = emptyList(),
                        text = tx.copy(text = ch, animIn = if (i == 0) tx.animIn else TextAnimation.NONE, animOut = if (i == chunks.lastIndex) tx.animOut else TextAnimation.NONE),
                        keyframes = emptyMap(), transitionIn = null, groupId = null, name = ch.take(24))
                    s += d
                }
            }
            np = replaceTrack(np, t.copy(clips = out))
        }
        return np
    }

    /** Word-wraps [text] into blocks of [lines] lines of at most [maxChars] characters. */
    fun chunk(text: String, maxChars: Int, lines: Int): List<String> {
        val words = text.replace('\n', ' ').split(Regex("\\s+")).filter { it.isNotEmpty() }
        val outLines = ArrayList<String>()
        val cur = StringBuilder()
        for (w in words) {
            if (cur.isNotEmpty() && cur.length + 1 + w.length > maxChars) { outLines += cur.toString(); cur.clear() }
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(w)
            // natural break after a sentence
            if (w.endsWith('.') || w.endsWith('!') || w.endsWith('?')) { outLines += cur.toString(); cur.clear() }
        }
        if (cur.isNotEmpty()) outLines += cur.toString()
        val blocks = ArrayList<String>()
        var i = 0
        while (i < outLines.size) {
            val b = ArrayList<String>()
            while (i < outLines.size && b.size < lines) { b += outLines[i]; i++; if (b.last().last() in ".!?") break }
            blocks += b.joinToString("\n")
        }
        return blocks
    }

    // ------------------------------------------------------------------ rhythm

    /** Alternating "punch-in" zoom on consecutive shots of the same video: jump cuts look intentional. */
    fun punchIn(p: Project, amount: Double = 0.14): Project {
        val main = mainTrack(p) ?: return p
        val s = main.sortedClips()
        var zoomed = false
        val out = s.mapIndexed { i, c ->
            val prev = s.getOrNull(i - 1)
            val jump = prev != null && prev.assetId != null && prev.assetId == c.assetId && c.kind == ClipKind.MEDIA
            zoomed = if (jump) !zoomed else false
            if (!jump && i > 0) c
            else c.copy(transform = c.transform.copy(scaleX = if (zoomed) 1.0 + amount else 1.0, scaleY = if (zoomed) 1.0 + amount else 1.0))
        }
        return replaceTrack(p, main.copy(clips = out))
    }

    /** Beat times every 60/[bpm] seconds over the montage. */
    fun beatsFromBpm(p: Project, bpm: Double, offsetUs: Long = 0): List<Long> {
        val step = (60_000_000.0 / bpm.coerceIn(40.0, 240.0)).roundToLong()
        val end = p.durationUs
        return generateSequence(offsetUs) { it + step }.takeWhile { it < end }.toList()
    }

    /**
     * Tempo edit: cuts the long shots of the main track on the beat (every [everyBeats] beats) and
     * alternates the framing at each cut, the classic "edit on the music" without moving anything.
     */
    fun tempoCut(p: Project, beatsUs: List<Long>, everyBeats: Int = 2, minShotUs: Long = 500_000): Project {
        val main = mainTrack(p) ?: return p
        var np = p
        val cuts = beatsUs.sorted().filterIndexed { i, _ -> i % everyBeats.coerceAtLeast(1) == 0 }
        for (b in cuts) {
            val c = np.track(main.id)!!.clips.firstOrNull { it.startUs + minShotUs < b && b < it.endUs - minShotUs && it.kind == ClipKind.MEDIA && it.speedCurve.isEmpty() } ?: continue
            np = TimelineEditor.split(np, c.id, b).first
        }
        return punchIn(np, 0.1)
    }

    /** Applies a speed ramp shape to a clip. */
    fun speedRamp(p: Project, clipId: String, preset: RampPreset): Project = TimelineEditor.setSpeedCurve(p, clipId, preset.curve)

    // ------------------------------------------------------------------ structure

    /**
     * Teaser: copies the strongest moment (the given range, or the middle of the longest shot)
     * to the very beginning with a flash and a "watch till the end" title, everything else is pushed.
     */
    fun teaser(p: Project, range: LongRange? = null, durationUs: Long = 1_600_000, title: String = "ATTENDS LA FIN…"): Project {
        val main = mainTrack(p) ?: return p
        val src = if (range != null) main.clips.firstOrNull { range.first >= it.startUs && range.first < it.endUs }
        else main.clips.filter { it.kind == ClipKind.MEDIA }.maxByOrNull { it.durationUs }
        src ?: return p
        val d = min(durationUs, src.durationUs)
        val atUs = range?.first ?: (src.startUs + (src.durationUs - d) / 2)
        val rel = ((atUs - src.startUs) * src.speed).roundToLong()
        val copy = src.copy(id = Ids.new("clp"), startUs = 0, sourceInUs = src.sourceInUs + rel, sourceOutUs = src.sourceInUs + rel + (d * src.speed).roundToLong(),
            speedCurve = emptyList(), transitionIn = null, keyframes = emptyMap(), groupId = null, motion = ClipMotion(loop = LoopMotion.SLOW_ZOOM_IN), name = "Teaser")
        // push every track, then insert the teaser
        var np = p.copy(tracks = p.tracks.map { t -> t.copy(clips = t.clips.map { it.copy(startUs = it.startUs + d) }) }, markers = p.markers.map { it.copy(timeUs = it.timeUs + d) })
        np = replaceTrack(np, np.track(main.id)!!.let { it.copy(clips = listOf(copy) + it.clips) })
        // flash into the real start
        val next = np.track(main.id)!!.sortedClips().getOrNull(1)
        if (next != null) TransitionRegistry["flash"]?.let { def -> np = TimelineEditor.setTransition(np, next.id, TransitionInstance("flash", def.defaultDurationUs, def.params.associate { it.key to it.default })) }
        val (p2, tt) = TimelineEditor.ensureTrack(np, TrackKind.TEXT)
        val txt = TimelineEditor.textClip(title, 0, d, TextStyle(fontFamily = "asset:fonts/Anton_400Regular.ttf", sizeSp = 64.0, uppercase = true, strokeWidth = 5.0, color = 0xFFFFE14D))
            .let { it.copy(text = it.text!!.copy(animIn = TextAnimation.POP), transform = it.transform.copy(posY = 0.3)) }
        return p2.copy(tracks = p2.tracks.map { if (it.id == tt.id) it.copy(clips = it.clips + txt) else it })
    }

    /**
     * Perfect loop: the video ends on its own opening image (a copy of the first instants faded in
     * from the last shot), so when TikTok / Reels replays it the cut is invisible.
     */
    fun perfectLoop(p: Project, durationUs: Long = 600_000): Project {
        val main = mainTrack(p) ?: return p
        val s = main.sortedClips()
        val first = s.firstOrNull() ?: return p
        val last = s.last()
        if (s.size < 1 || first.durationUs < durationUs * 2) return p
        val d = durationUs
        val tail = first.copy(id = Ids.new("clp"), startUs = last.endUs, sourceOutUs = first.sourceInUs + (d * first.speed).roundToLong(), speedCurve = emptyList(),
            transitionIn = null, keyframes = emptyMap(), motion = ClipMotion(), groupId = null, name = "Boucle",
            audio = first.audio.copy(volume = 0.0))
        var np = replaceTrack(p, main.copy(clips = main.clips + tail))
        TransitionRegistry["fade"]?.let { def -> np = TimelineEditor.setTransition(np, tail.id, TransitionInstance("fade", d / 2, def.params.associate { it.key to it.default })) }
        // the opening has no entry animation (it must match the last image exactly)
        np = TimelineEditor.mapClips(np) { if (it.id == first.id) it.copy(motion = it.motion.copy(animIn = MotionPreset.NONE), transitionIn = null) else it }
        return fitMusicToVideo(np, d)
    }

    /** Fade from black at the start and to black at the end (opacity keyframes + sound fades). */
    fun fadeInOut(p: Project, fadeUs: Long = 700_000): Project {
        val main = mainTrack(p) ?: return p
        val s = main.sortedClips()
        val first = s.firstOrNull() ?: return p
        val last = s.last()
        var np = TimelineEditor.updateClip(p, first.id) { c -> c.copy(keyframes = c.keyframes + (Prop.OPACITY to KeyframeTrack(listOf(Keyframe(0, 0.0), Keyframe(min(fadeUs, c.durationUs / 2), c.transform.opacity)))), audio = c.audio.copy(fadeInUs = max(c.audio.fadeInUs, fadeUs))) }
        np = TimelineEditor.updateClip(np, last.id) { c ->
            val base = if (c.id == first.id) c.keyframes[Prop.OPACITY]?.keyframes.orEmpty() else emptyList()
            c.copy(keyframes = c.keyframes + (Prop.OPACITY to KeyframeTrack((base + listOf(Keyframe(max(c.durationUs - fadeUs, c.durationUs / 2), c.transform.opacity), Keyframe(c.durationUs, 0.0))).sortedBy { it.timeUs }.distinctBy { it.timeUs })),
                audio = c.audio.copy(fadeOutUs = max(c.audio.fadeOutUs, fadeUs)))
        }
        return fitMusicToVideo(np)
    }

    /** Fills the frame with every shot (no black bars), useful after a format change. */
    fun fillFrame(p: Project): Project = TimelineEditor.mapClips(p) { c -> if (c.kind == ClipKind.MEDIA && p.asset(c.assetId)?.isVisual == true) c.copy(transform = c.transform.copy(fill = true)) else c }

    /** Same colour look on every shot of the main track. */
    fun applyLook(p: Project, presetName: String): Project {
        val preset = Presets.builtIn.firstOrNull { norm(it.name) == norm(presetName) } ?: return p
        val main = mainTrack(p) ?: return p
        val ids = main.clips.filter { it.kind == ClipKind.MEDIA }.map { it.id }.toSet()
        return TimelineEditor.mapClips(p) { c ->
            if (c.id !in ids) c
            else c.copy(effects = c.effects.filterNot { e -> e.id.startsWith("look_") } + preset.effects.map { it.copy(id = "look_" + Ids.new()) })
        }
    }

    /** Same transition on every cut of the main track. */
    fun transitionsEverywhere(p: Project, transitionId: String): Project {
        val main = mainTrack(p) ?: return p
        val def = TransitionRegistry[transitionId] ?: return p
        return TimelineEditor.setTransitionAll(p, main.id, TransitionInstance(transitionId, def.defaultDurationUs, def.params.associate { it.key to it.default }))
    }

    /** Converts the project for [platform]: format, full-frame shots, safe text zones. */
    fun forPlatform(p: Project, platform: Platform): Project {
        var np = p
        if (p.format.aspect != platform.aspect) {
            np = TimelineEditor.changeFormat(np, ProjectFormat.forAspect(platform.aspect, min(p.format.width, p.format.height).coerceAtLeast(720), p.format.frameRate), fillMode = true)
        }
        np = safeZones(np, platform)
        if (platform.maxSec > 0 && np.durationUs > platform.maxSec * 1_000_000L) np = fitToDuration(np, platform.maxSec * 1_000_000L)
        return np
    }

    /** YouTube chapters ("00:00 Introduction") from markers and titles; at least 10 s apart. */
    fun chapters(p: Project): String {
        val cands = ArrayList<Pair<Long, String>>()
        cands += 0L to "Introduction"
        p.markers.sortedBy { it.timeUs }.forEachIndexed { i, m -> cands += m.timeUs to m.label.ifBlank { "Partie ${i + 2}" } }
        p.tracks.filter { it.kind == TrackKind.TEXT }.flatMap { it.clips }.sortedBy { it.startUs }.forEach { c ->
            c.text?.text?.lines()?.firstOrNull()?.trim()?.takeIf { it.length in 3..60 }?.let { cands += c.startUs to it.replaceFirstChar { ch -> ch.uppercase() } }
        }
        val out = ArrayList<Pair<Long, String>>()
        for ((t, l) in cands.sortedBy { it.first }) if (out.isEmpty() || t - out.last().first >= 10_000_000) out += t to l
        if (out.size < 3) {
            // not enough markers: split the main track evenly on its cuts
            val main = mainTrack(p)?.sortedClips().orEmpty()
            val total = p.durationUs
            if (total >= 40_000_000) {
                out.clear(); out += 0L to "Introduction"
                for (part in 1..3) {
                    val wanted = total * part / 4
                    val cut = main.minByOrNull { abs(it.startUs - wanted) }?.startUs ?: wanted
                    if (cut - out.last().first >= 10_000_000) out += cut to "Partie ${out.size + 1}"
                }
            }
        }
        return out.joinToString("\n") { (t, l) -> "%s %s".format(clock(t), l) }
    }

    fun clock(us: Long): String {
        val s = us / 1_000_000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
    }

    /** One-tap "magic" pass: clean-up, anti-click fades, ducking, readable and safe texts, music fit. */
    fun magic(p: Project, platform: Platform): Project {
        var np = cleanUp(p)
        np = microFades(np)
        np = autoDuck(np)
        np = readableSubtitles(np)
        if (platform.vertical == (p.format.height > p.format.width)) np = safeZones(np, platform)
        np = fitMusicToVideo(np)
        return np
    }

    internal fun norm(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    /** Kept for the director: hook title through the existing creator tool. */
    fun hook(p: Project, text: String) = ProTools.hookTitle(p, text)
}
