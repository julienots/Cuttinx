package com.superessence.cuttinx.core.smart

import com.superessence.cuttinx.core.model.*
import kotlin.math.max
import kotlin.math.roundToInt

enum class Severity(val label: String, val weight: Int) { CRITICAL("Bloquant", 18), MAJOR("Important", 9), MINOR("Détail", 3), TIP("Idée", 0) }

/** Automatic repair attached to a quality item (one undo step). */
enum class QuickFix(val label: String) {
    HOOK("Ajouter une accroche"), TEASER("Mettre un teaser au début"), FIT_DURATION("Raccourcir à la bonne durée"),
    READABLE("Découper les textes"), SAFE_ZONES("Déplacer hors des boutons"), DUCK("Baisser la musique sous la voix"),
    MICRO_FADES("Supprimer les clics"), CLEAN("Nettoyer"), MUSIC_END("Caler la musique"), FILL("Remplir le cadre"),
    PUNCH("Zooms alternés"), LOOP("Faire une boucle parfaite"), FADES("Fondus début / fin"),
    ;

    fun apply(p: Project, platform: Platform): Project = when (this) {
        HOOK -> SmartTools.hook(p, "ATTENDS LA FIN !")
        TEASER -> SmartTools.teaser(p)
        FIT_DURATION -> if (platform.maxSec > 0) SmartTools.fitToDuration(p, platform.maxSec * 1_000_000L) else p
        READABLE -> SmartTools.readableSubtitles(p)
        SAFE_ZONES -> SmartTools.safeZones(p, platform)
        DUCK -> SmartTools.autoDuck(p)
        MICRO_FADES -> SmartTools.microFades(p)
        CLEAN -> SmartTools.cleanUp(p)
        MUSIC_END -> SmartTools.fitMusicToVideo(p)
        FILL -> SmartTools.fillFrame(p)
        PUNCH -> SmartTools.punchIn(p)
        LOOP -> SmartTools.perfectLoop(p)
        FADES -> SmartTools.fadeInOut(p)
    }
}

data class QualityItem(val severity: Severity, val title: String, val detail: String, val fix: QuickFix? = null, val atUs: Long? = null)

data class QualityReport(val score: Int, val items: List<QualityItem>, val platform: Platform, val stats: Map<String, String>) {
    val grade: String get() = when { score >= 90 -> "Prêt à percer"; score >= 75 -> "Très bon"; score >= 55 -> "Correct"; else -> "À retravailler" }
}

/**
 * Meticulous pre-publication check of a montage, tuned per platform: hook, pace, reading speed,
 * interface zones, audio mix, micro-defects (flash frames, clicks, fragments)… Every problem
 * comes with an explanation and, when possible, a one-click repair.
 */
object QualityCoach {
    fun analyze(p: Project, platform: Platform): QualityReport {
        val items = ArrayList<QualityItem>()
        val main = SmartTools.mainTrack(p)
        val shots = main?.sortedClips().orEmpty()
        val frame = 1_000_000L / p.format.frameRate.coerceAtLeast(1)
        val total = p.durationUs
        if (shots.isEmpty()) return QualityReport(0, listOf(QualityItem(Severity.CRITICAL, "Montage vide", "Ajoute des vidéos ou des photos pour commencer.")), platform, emptyMap())

        // ---- media
        p.missingMedia.forEach { items += QualityItem(Severity.CRITICAL, "Média introuvable", "« ${it.displayName} » a été déplacé ou supprimé : il sera noir à l'export.") }

        // ---- format
        val vertical = p.format.height > p.format.width
        if (vertical != platform.vertical) items += QualityItem(Severity.MAJOR, "Mauvais format pour ${platform.label}", "Le projet est en ${p.format.aspect.label}, ${platform.label} attend du ${platform.aspect.label}. Utilise « Adapter à ${platform.label} ».")
        val bars = shots.filter { c -> val a = p.asset(c.assetId); a != null && a.isVisual && a.width > 0 && !c.transform.fill && kotlin.math.abs(a.displayWidth.toDouble() / a.displayHeight - p.format.aspectRatio) > 0.25 }
        if (bars.isNotEmpty()) items += QualityItem(Severity.MAJOR, "Bandes noires sur ${bars.size} plan(s)", "Ces plans n'ont pas le format du projet. Les remplir rend la vidéo plus pro.", QuickFix.FILL, bars.first().startUs)

        // ---- hook & pace (short formats)
        val texts = p.tracks.filter { it.kind == TrackKind.TEXT || it.kind == TrackKind.SUBTITLE }.flatMap { it.clips }
        val short = platform != Platform.YOUTUBE
        if (short && texts.none { it.startUs < 1_500_000 })
            items += QualityItem(Severity.MAJOR, "Pas d'accroche dans la première seconde", "Sur ${platform.label}, tout se joue dans les 1,5 premières secondes : un texte fort retient le pouce.", QuickFix.HOOK, 0)
        val firstShot = shots.first()
        if (short && firstShot.durationUs > 3_500_000)
            items += QualityItem(Severity.MAJOR, "Premier plan trop long (%.1f s)".format(firstShot.durationUs / 1e6), "Les spectateurs décrochent si rien ne bouge au début. Un teaser du meilleur moment est très efficace.", QuickFix.TEASER, 0)
        val avg = shots.map { it.durationUs }.average() / 1e6
        if (avg > platform.idealShotSec * 1.8)
            items += QualityItem(if (short) Severity.MAJOR else Severity.MINOR, "Rythme lent : %.1f s par plan en moyenne".format(avg), "Le rythme idéal sur ${platform.label} est d'environ %.1f s. Coupe les temps morts ou ajoute des zooms alternés.".format(platform.idealShotSec), QuickFix.PUNCH)
        shots.filter { it.durationUs > platform.idealShotSec * 4 * 1_000_000 && it.kind == ClipKind.MEDIA }.take(3).forEach {
            items += QualityItem(Severity.MINOR, "Plan très long (%.0f s) à %s".format(it.durationUs / 1e6, SmartTools.clock(it.startUs)), "Découpe-le ou ajoute du mouvement (zoom lent, recadrage).", null, it.startUs)
        }
        if (platform.maxSec > 0 && total > platform.maxSec * 1_000_000L)
            items += QualityItem(Severity.MAJOR, "Trop long pour ${platform.label}", "%d s alors que ${platform.maxSec} s est la limite conseillée. Le raccourcissement intelligent garde le cœur de chaque plan.".format(total / 1_000_000), QuickFix.FIT_DURATION)

        // ---- texts
        val fast = texts.filter { c -> val n = c.text?.text?.length ?: 0; n > 12 && n / (c.durationUs / 1e6).coerceAtLeast(0.05) > SmartTools.READING_CPS * 1.25 }
        if (fast.isNotEmpty()) items += QualityItem(Severity.MAJOR, "${fast.size} texte(s) impossibles à lire à temps", "Plus de %.0f caractères par seconde : le spectateur n'a pas le temps de lire.".format(SmartTools.READING_CPS * 1.25), QuickFix.READABLE, fast.first().startUs)
        val longLines = texts.filter { (it.text?.text?.length ?: 0) > 64 }
        if (longLines.isNotEmpty()) items += QualityItem(Severity.MINOR, "${longLines.size} texte(s) trop longs à l'écran", "Au-delà de 2 lignes l'œil décroche : découpe en plusieurs blocs.", QuickFix.READABLE, longLines.first().startUs)
        val unsafe = texts.filter { c -> val y = c.transform.posY; y > 1 - platform.safeBottom - 0.03 || y < platform.safeTop + 0.03 || c.transform.posX > 1 - platform.safeRight - 0.08 }
        if (unsafe.isNotEmpty() && vertical == platform.vertical)
            items += QualityItem(Severity.MAJOR, "${unsafe.size} texte(s) cachés par l'interface de ${platform.label}", "Les boutons j'aime / commentaires et la description recouvrent ces zones.", QuickFix.SAFE_ZONES, unsafe.first().startUs)
        val clash = texts.sortedBy { it.startUs }.zipWithNext().count { (a, b) -> b.startUs < a.endUs - 100_000 && kotlin.math.abs(a.transform.posY - b.transform.posY) < 0.08 }
        if (clash > 0) items += QualityItem(Severity.MINOR, "$clash texte(s) se superposent", "Deux textes au même endroit au même moment deviennent illisibles.")

        // ---- audio
        val voice = SmartTools.voiceRanges(p)
        val music = p.tracks.filter { it.kind == TrackKind.AUDIO && it.audioRole == AudioRole.MUSIC }.flatMap { it.clips }
        if (voice.isNotEmpty() && music.any { it.keyframes[Prop.VOLUME] == null && it.audio.volume > 0.35 })
            items += QualityItem(Severity.MAJOR, "La musique couvre la voix", "Le ducking automatique baisse la musique uniquement quand quelqu'un parle.", QuickFix.DUCK)
        val mainEnd = main!!.endUs
        if (music.any { it.endUs > mainEnd + 300_000 }) items += QualityItem(Severity.MINOR, "La musique dépasse la fin de la vidéo", "Elle sera coupée net à l'export.", QuickFix.MUSIC_END)
        else if (music.isNotEmpty() && music.maxOf { it.endUs } >= mainEnd - 300_000 && music.maxBy { it.endUs }.audio.fadeOutUs < 300_000)
            items += QualityItem(Severity.MINOR, "Fin de musique abrupte", "Un fondu de sortie rend la fin plus propre.", QuickFix.MUSIC_END)
        else if (music.isNotEmpty() && music.maxOf { it.endUs } < mainEnd - 2_000_000)
            items += QualityItem(Severity.TIP, "La musique s'arrête avant la fin", "Prolonge-la ou ajoute un second morceau.")
        val audible = p.tracks.filter { it.kind == TrackKind.AUDIO || it.kind == TrackKind.VIDEO }.flatMap { it.clips }.filter { it.kind == ClipKind.MEDIA && p.asset(it.assetId)?.hasAudio == true }
        val hard = audible.count { it.audio.fadeInUs < 5_000 || it.audio.fadeOutUs < 5_000 }
        if (hard >= 2) items += QualityItem(Severity.MINOR, "$hard coupes son sans micro-fondu", "Risque de petits « clics » audibles aux coupes : des fondus de 15 ms les suppriment sans rien changer d'autre.", QuickFix.MICRO_FADES)
        val loud = audible.filter { it.audio.volume > 1.6 }
        if (loud.isNotEmpty()) items += QualityItem(Severity.MINOR, "Volume très poussé sur ${loud.size} clip(s)", "Au-delà de 160 % le son peut saturer.", null, loud.first().startUs)

        // ---- micro defects
        val fragments = p.allClips.filter { it.durationUs < frame * 2 }
        if (fragments.isNotEmpty()) items += QualityItem(Severity.MAJOR, "${fragments.size} fragment(s) d'une image", "Restes de coupe presque invisibles qui font « sauter » l'image.", QuickFix.CLEAN, fragments.first().startUs)
        val gaps = shots.zipWithNext().filter { (a, b) -> b.startUs - a.endUs in 1..(frame * 3) }
        if (gaps.isNotEmpty()) items += QualityItem(Severity.MAJOR, "${gaps.size} image(s) noire(s) entre deux plans", "Un micro-trou provoque un flash noir à la coupe.", QuickFix.CLEAN, gaps.first().first.endUs)
        if (shots.first().startUs > 0) items += QualityItem(Severity.MAJOR, "Écran noir au début", "La vidéo commence par du vide.", QuickFix.CLEAN, 0)

        // ---- ideas
        if (short && total in 5_000_000..35_000_000 && shots.none { it.name == "Boucle" })
            items += QualityItem(Severity.TIP, "Idée : boucle parfaite", "Sur une vidéo courte, une fin qui se raccorde au début fait revoir la vidéo (et booste le temps de visionnage).", QuickFix.LOOP)
        if (!short && shots.first().keyframes[Prop.OPACITY] == null) items += QualityItem(Severity.TIP, "Idée : fondus d'ouverture et de fin", "Un fondu au noir donne un aspect plus cinéma.", QuickFix.FADES)

        val penalty = items.sumOf { it.severity.weight }
        val score = (100 - penalty).coerceIn(0, 100)
        val stats = linkedMapOf(
            "Durée" to SmartTools.clock(total),
            "Plans" to "${shots.size}",
            "Rythme" to "%.1f s / plan".format(avg),
            "Textes" to "${texts.size}",
            "Voix" to if (voice.isEmpty()) "aucune" else "%.0f %%".format(voice.sumOf { it.last - it.first } * 100.0 / max(1, total)),
        )
        return QualityReport(score, items.sortedBy { it.severity.ordinal }, platform, stats)
    }

    /** Applies every available repair, most important first. */
    fun fixAll(p: Project, platform: Platform): Project {
        var np = p
        val fixes = analyze(p, platform).items.mapNotNull { it.fix }.distinct().filter { it != QuickFix.LOOP && it != QuickFix.FADES && it != QuickFix.TEASER }
        for (f in fixes) np = f.apply(np, platform)
        return np
    }

    fun percent(score: Int) = (score.coerceIn(0, 100) / 100.0 * 100).roundToInt()
}
