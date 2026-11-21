package com.superessence.cuttinx.core.create

import com.superessence.cuttinx.core.autoedit.ProTools
import com.superessence.cuttinx.core.effects.EffectRegistry
import com.superessence.cuttinx.core.effects.TransitionRegistry
import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.smart.Kit
import com.superessence.cuttinx.core.smart.TextLook
import com.superessence.cuttinx.core.smart.Titles
import com.superessence.cuttinx.core.timeline.TimelineEditor
import com.superessence.cuttinx.core.util.Ids
import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * Visual direction of a script video: text looks used in turn, animated backdrops with their
 * colours, transitions, built-in music and reading speed.
 */
enum class ScriptStyle(
    val label: String,
    val description: String,
    val looks: List<TextLook>,
    val backdrops: List<String>,
    /** (colour 1, colour 2) of the backdrops, used in turn. */
    val hues: List<Pair<Double, Double>>,
    val transitions: List<String>,
    /** Built-in music (Synth) that fits the style. */
    val musicId: String,
    /** Reading speed in words per minute (lyrics use the music tempo instead). */
    val wpm: Int,
    /** Dark tint under the backdrop of each scene. */
    val base: Long,
) {
    MOTIVATION("Motivation", "Gros titres dorés, rayons et tunnel de lumière, musique épique",
        listOf(TextLook.GOLD, TextLook.POSTER, TextLook.IMPACT), listOf("bd_sunburst", "bd_tunnel", "bd_gradient_mesh"),
        listOf(250.0 to 225.0, 30.0 to 280.0, 20.0 to 330.0), listOf("flash_zoom", "supernova", "zoom_blur"), "epic", 150, 0xFF0B0805),
    FACTS("Le savais-tu ?", "Faits et astuces : bulles colorées, pop art, rythme vif",
        listOf(TextLook.BUBBLE, TextLook.BANNER, TextLook.OUTLINE), listOf("bd_halftone_pop", "bd_stripes", "bd_sunburst"),
        listOf(50.0 to 190.0, 330.0 to 290.0, 170.0 to 60.0), listOf("swipe_up", "punch_zoom", "elastic_slide"), "funk", 170, 0xFF101018),
    STORY("Storytime", "Récit en chapitres : typographie de film, ciel étoilé et aurore, piano ambient",
        listOf(TextLook.CREDITS, TextLook.EDITORIAL, TextLook.HANDWRITTEN), listOf("bd_starfield", "bd_aurora_sky", "bd_waves"),
        listOf(230.0 to 280.0, 150.0 to 270.0, 210.0 to 250.0), listOf("fade", "luma_fade", "fade_black"), "ambient", 140, 0xFF05060C),
    PROMO("Pub / promo", "Lancement de produit : métal liquide, or et chrome, transitions franches",
        listOf(TextLook.CHROME, TextLook.GOLD, TextLook.BANNER), listOf("bd_liquid_metal", "bd_gradient_mesh", "bd_stripes"),
        listOf(220.0 to 40.0, 280.0 to 200.0, 0.0 to 45.0), listOf("push_zoom", "shatter", "glitch_slide"), "pop", 160, 0xFF0A0A0C),
    TECH("Tech / futur", "Hologrammes, terminal et grille néon, coupes glitch",
        listOf(TextLook.HOLO, TextLook.TERMINAL, TextLook.GLITCH), listOf("bd_retro_grid", "bd_tunnel", "bd_liquid_metal"),
        listOf(300.0 to 190.0, 190.0 to 300.0, 200.0 to 280.0), listOf("holo_glitch", "teleport", "rgb_glitch_cut"), "tech", 160, 0xFF020208),
    CHILL("Citation douce", "Pensée du jour : vagues pastel, bokeh, écriture élégante, lo-fi",
        listOf(TextLook.MINIMAL, TextLook.Y2K, TextLook.CREDITS), listOf("bd_waves", "bd_bokeh_field", "bd_gradient_mesh"),
        listOf(320.0 to 200.0, 40.0 to 320.0, 280.0 to 180.0), listOf("liquid_morph", "fade", "dissolve"), "lofi", 130, 0xFF100C14),
    LYRICS("Paroles (clip)", "Chaque ligne tombe pile sur la mesure de la musique, lampe à lave et tunnel",
        listOf(TextLook.Y2K, TextLook.NEON, TextLook.GLITCH), listOf("bd_lava", "bd_tunnel", "bd_gradient_mesh"),
        listOf(330.0 to 30.0, 190.0 to 300.0, 280.0 to 200.0), listOf("flash", "zoom_blur", "cells"), "pop", 0, 0xFF0A0510),
}

/** One screen of the script video. */
data class ScriptScene(val text: String, val words: Int, val durationUs: Long, val chapter: Boolean = false, val title: Boolean = false)

/** What the script video is made of besides the text. */
data class ScriptOptions(
    /** Music asset already in the project (or null = silent video). */
    val music: MediaAsset? = null,
    /** Tempo of [music] (lyrics mode); defaults to the style's built-in music tempo. */
    val bpm: Int? = null,
    /** Photos / videos used as scene backgrounds instead of the animated backdrops (in turn). */
    val media: List<MediaAsset> = emptyList(),
    val progressBar: Boolean = true,
    val endCard: Boolean = true,
    val endText: String = "Merci d'avoir regardé",
    val endCall: String = "Abonne-toi pour la suite",
)

/**
 * « Studio Script » (CUTTINX 4.0) : un texte devient une vidéo complète et modifiable. Le texte est
 * découpé en écrans (lignes, phrases, puis virgules pour les phrases trop longues), chaque écran
 * dure le temps de le lire (ou un nombre entier de mesures en mode Paroles), reçoit un fond animé
 * (ou une de tes photos avec un lent zoom), un style de texte animé, une transition, et la musique
 * suit jusqu'à la carte de fin. Tout est fait sur le téléphone, sans réseau.
 */
object ScriptStudio {
    const val MIN_SCENE_US = 1_600_000L
    const val MAX_SCENE_US = 6_500_000L
    const val MAX_SCENES = 60
    const val END_US = 3_000_000L
    private const val MAX_WORDS = 14

    private val sentenceEnd = Regex("(?<=[.!?…])\\s+")

    /** Splits [script] into screens. A first line ending without punctuation, or starting with "#", is a title. */
    fun scenes(script: String, style: ScriptStyle, bpm: Int = defaultBpm(style)): List<ScriptScene> {
        val lines = script.replace("\r", "").split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val out = ArrayList<ScriptScene>()
        lines.forEachIndexed { li, raw ->
            val chapter = raw.startsWith("#")
            val line = raw.trimStart('#', ' ').replace("*", "").trim()
            if (line.isEmpty()) return@forEachIndexed
            val pieces = if (chapter || style == ScriptStyle.LYRICS) listOf(line) else line.split(sentenceEnd).flatMap { splitLong(it) }
            for (piece in pieces) {
                val words = wordCount(piece)
                if (words == 0) continue
                val isTitle = li == 0 && out.isEmpty() && !chapter && lines.size > 1 && words <= 8 && !piece.trimEnd().last().let { it == '.' || it == ',' }
                out += ScriptScene(piece, words, duration(words, style, bpm, chapter || isTitle), chapter, isTitle)
                if (out.size >= MAX_SCENES) return out
            }
        }
        return out
    }

    /** A ready-to-try text for each style (shown by the « Exemple » button). */
    fun example(style: ScriptStyle): String = when (style) {
        ScriptStyle.MOTIVATION -> "Ne lâche rien\nTout le monde voit le résultat. Personne ne voit les heures de travail.\nChaque matin, tu as le choix : rester le même, ou devenir meilleur.\n# La règle\nUn petit pas par jour, c'est 365 pas par an.\nCommence maintenant. Pas demain."
        ScriptStyle.FACTS -> "Le savais-tu ?\nLes pieuvres ont trois cœurs.\nLe miel ne se périme jamais : on en a trouvé de 3000 ans, encore comestible.\nUn nuage pèse en moyenne plus de 500 tonnes.\nEt la tour Eiffel grandit de 15 cm en été !\nAbonne-toi pour d'autres faits incroyables."
        ScriptStyle.STORY -> "La nuit où tout a changé\nIl était presque minuit quand mon téléphone a sonné.\nUn numéro inconnu. J'ai failli ne pas répondre.\n# L'appel\nAu bout du fil, une voix que je n'avais pas entendue depuis dix ans.\nElle a juste dit : « Regarde par la fenêtre. »\nLa suite dans la prochaine vidéo…"
        ScriptStyle.PROMO -> "Le nouveau standard\nPlus léger. Plus rapide. Plus beau.\nUne autonomie de deux jours, une charge en vingt minutes.\nDisponible dès aujourd'hui.\n-20 % avec le code LANCEMENT."
        ScriptStyle.TECH -> "Le futur est déjà là\nEn 2030, ton assistant connaîtra ton agenda mieux que toi.\nLes voitures se gareront seules, les maisons s'éclaireront avant ton arrivée.\n# La vraie question\nQue feras-tu de tout ce temps gagné ?"
        ScriptStyle.CHILL -> "Pensée du jour\nRalentis.\nLes plus belles choses arrivent souvent quand on arrête de courir après.\nRespire, bois un café, regarde le ciel.\nTout va bien."
        ScriptStyle.LYRICS -> "Je marche seul dans la ville qui dort\nLes néons dansent, je n'ai pas sommeil\nJe garde ton nom au fond de mon corps\nEt chaque nuit je t'attends jusqu'au soleil\nOh oh, jusqu'au soleil\nOh oh, jusqu'au soleil"
    }

    fun defaultBpm(style: ScriptStyle): Int = com.superessence.cuttinx.core.resources.Synth.musicList.firstOrNull { it.id == style.musicId }?.bpm ?: 120

    /** Total length of the video the script gives (without the end card). */
    fun totalUs(scenes: List<ScriptScene>): Long = scenes.sumOf { it.durationUs }

    private fun wordCount(s: String) = s.split(Regex("\\s+")).count { w -> w.any { it.isLetterOrDigit() } }

    /** Long sentences are cut at commas / semicolons, then every [MAX_WORDS] words. */
    private fun splitLong(s: String): List<String> {
        if (wordCount(s) <= MAX_WORDS) return listOf(s.trim())
        val parts = s.split(Regex("(?<=[,;:])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
        val merged = ArrayList<String>()
        for (p in parts) {
            val last = merged.lastOrNull()
            if (last != null && wordCount(last) + wordCount(p) <= MAX_WORDS) merged[merged.size - 1] = "$last $p" else merged += p
        }
        return merged.flatMap { m ->
            val w = m.split(Regex("\\s+"))
            if (w.size <= MAX_WORDS) listOf(m) else w.chunked(MAX_WORDS).map { it.joinToString(" ") }
        }
    }

    private fun duration(words: Int, style: ScriptStyle, bpm: Int, emphasis: Boolean): Long {
        if (style == ScriptStyle.LYRICS) {
            val bar = 4 * 60_000_000L / bpm.coerceIn(40, 220)
            val bars = ceil(words / 5.0).toLong().coerceIn(1, 4)
            return bar * bars
        }
        val read = 700_000L + (words * 60_000_000.0 / style.wpm).roundToLong()
        return (if (emphasis) read + 500_000 else read).coerceIn(MIN_SCENE_US, MAX_SCENE_US)
    }

    /** Text size for a screen: big for short lines, smaller for long ones. */
    fun sizeFor(base: Double, text: String, portrait: Boolean): Double {
        val n = text.length
        val k = when { n <= 12 -> 1.15; n <= 28 -> 1.0; n <= 50 -> 0.78; n <= 80 -> 0.62; else -> 0.52 }
        return base * k * (if (portrait) 1.0 else 0.85)
    }

    /**
     * Builds the video in [base] (its format is kept, its tracks are replaced). Returns a normal
     * project: every scene, text, effect and transition can be edited afterwards.
     */
    fun build(base: Project, script: String, style: ScriptStyle, options: ScriptOptions = ScriptOptions()): Project {
        val bpm = options.bpm ?: defaultBpm(style)
        val scenes = scenes(script, style, bpm)
        require(scenes.isNotEmpty()) { "Écris au moins une phrase" }
        var p = base.copy(tracks = emptyList(), markers = emptyList())
        for (m in options.media) p = TimelineEditor.addMedia(p, m)
        options.music?.let { p = TimelineEditor.addMedia(p, it) }
        val (p0, main) = TimelineEditor.mainTrack(p); p = p0
        val portrait = p.format.height >= p.format.width

        // ---- backgrounds: each scene is lengthened by the transition that will overlap it
        val transIds = style.transitions.filter { TransitionRegistry[it] != null }.ifEmpty { listOf("fade") }
        // one more background for the end card (3 s), entered with a soft fade
        val shots = scenes.map { it.durationUs } + if (options.endCard) listOf(END_US) else emptyList()
        val transIdOf = { i: Int -> if (options.endCard && i == scenes.size) "fade" else transIds[(i - 1) % transIds.size] }
        val transUs = shots.indices.map { i -> if (i == 0) 0L else minOf(TransitionRegistry[transIdOf(i)]!!.defaultDurationUs, 600_000L) }
        var t = 0L
        val sceneClipIds = ArrayList<String>()
        shots.forEachIndexed { i, sceneUs ->
            val overlap = if (i + 1 < shots.size) transUs[i + 1] else 0L
            val dur = sceneUs + overlap
            val clip = background(p, style, options.media, i, t, dur)
            p = TimelineEditor.insertClip(p, main.id, clip)
            sceneClipIds += clip.id
            t += dur
        }
        // ---- transitions (they pull the following scenes earlier by their overlap)
        for (i in 1 until sceneClipIds.size) {
            val id = transIdOf(i)
            val def = TransitionRegistry[id]!!
            p = runCatching { TimelineEditor.setTransition(p, sceneClipIds[i], TransitionInstance(id, transUs[i], def.params.associate { it.key to it.default })) }.getOrDefault(p)
        }
        // ---- texts: visible while their scene is fully on screen
        val placed = sceneClipIds.map { id -> p.findClip(id)!!.second }
        scenes.forEachIndexed { i, s ->
            val c = placed[i]
            val inPad = (c.transitionIn?.durationUs ?: 0L) * 6 / 10 + 120_000
            val outPad = (placed.getOrNull(i + 1)?.transitionIn?.durationUs ?: 0L) * 6 / 10 + 120_000
            val start = c.startUs + inPad
            val dur = (c.endUs - outPad - start).coerceAtLeast(500_000)
            val look = when {
                s.title -> style.looks.first()
                s.chapter -> style.looks.last()
                else -> style.looks[(i + if (scenes.first().title) 0 else 1) % style.looks.size]
            }
            val clip = Titles.title(s.text, start, dur, look, posY = if (s.chapter) 0.4 else 0.47).let { tc ->
                val st = tc.text!!.style
                tc.copy(name = "Écran ${i + 1}", text = tc.text.copy(maxWidth = if (portrait) 0.84 else 0.76,
                    style = st.copy(sizeSp = sizeFor(st.sizeSp, s.text, portrait) * (if (s.title) 1.15 else 1.0))))
            }
            p = Kit.place(p, TrackKind.TEXT, "Script", clip)
            if (s.chapter || s.title) p = p.copy(markers = p.markers + Marker(Ids.new("mrk"), c.startUs, s.text.take(40)))
        }
        // ---- music, looped to cover the whole video, fading out at the end
        if (options.endCard) p = Titles.endCard(p, options.endText, options.endCall)
        options.music?.let { m -> p = addMusic(p, m) }
        if (options.progressBar) p = ProTools.progressBar(p)
        return p.copy(name = base.name.ifBlank { scenes.first().text.take(32) })
    }

    private fun background(p: Project, style: ScriptStyle, media: List<MediaAsset>, i: Int, startUs: Long, durUs: Long): Clip {
        val visual = media.filter { it.isVisual }
        if (visual.isNotEmpty()) {
            val a = visual[i % visual.size]
            val isVideo = a.type == MediaType.VIDEO
            val srcDur = if (isVideo) minOf(durUs, a.durationUs.coerceAtLeast(MIN_SCENE_US)) else durUs
            val zoomIn = i % 2 == 0
            // darker, slightly blurred picture so the text stays readable, with a slow zoom
            val fx = listOfNotNull(
                fx("adjust", "brightness" to -0.18, "contrast" to 0.05),
                fx("vignette", "amount" to 0.45),
            )
            return Clip(Ids.new("clp"), ClipKind.MEDIA, a.id, a.displayName, startUs, 0, srcDur, transform = Transform(fill = true), effects = fx,
                keyframes = mapOf(Prop.SCALE to KeyframeTrack(listOf(Keyframe(0, if (zoomIn) 1.0 else 1.12, Easing.Smooth), Keyframe(srcDur, if (zoomIn) 1.12 else 1.0)))))
                .let { if (isVideo && srcDur < durUs) it.copy(speed = srcDur.toDouble() / durUs, sourceOutUs = srcDur) else it }
        }
        val bd = style.backdrops[i % style.backdrops.size]
        val (h1, h2) = style.hues[i % style.hues.size]
        // the backdrop is toned down and darkened at the edges so the text always reads
        val effects = listOfNotNull(fx(bd, "hue1" to h1, "hue2" to h2, "speed" to 0.8), fx("adjust", "brightness" to -0.12, "saturation" to -0.22, "contrast" to -0.05), fx("vignette", "amount" to 0.5))
        return TimelineEditor.colorClip(style.base, startUs, durUs).copy(name = "Fond ${EffectRegistry[bd]?.name ?: ""}".trim(), effects = effects)
    }

    private fun fx(id: String, vararg prm: Pair<String, Double>): EffectInstance? =
        EffectRegistry[id]?.let { d -> EffectInstance(Ids.new("fx"), id, d.defaults() + prm.toMap().filterKeys { d.param(it) != null }) }

    private fun addMusic(p: Project, m: MediaAsset): Project {
        val total = p.durationUs
        if (total <= 0 || m.durationUs <= 0) return p
        val (q, tr) = TimelineEditor.addTrack(p, TrackKind.AUDIO, "Musique", AudioRole.MUSIC)
        var np = q
        var at = 0L
        while (at < total) {
            val len = minOf(m.durationUs, total - at)
            val last = at + len >= total
            np = TimelineEditor.insertClip(np, tr.id, Clip(Ids.new("clp"), ClipKind.MEDIA, m.id, m.displayName, at, 0, len,
                audio = AudioSettings(fadeInUs = if (at == 0L) 300_000 else 0, fadeOutUs = if (last) minOf(1_500_000L, len / 2) else 0)))
            at += len
        }
        return np
    }
}
