package com.superessence.cuttinx.core.autoedit

import com.superessence.cuttinx.core.model.*

enum class SuggestionKind { LONG_CLIP, NO_TRANSITIONS, MISSING_MEDIA, MUSIC_TOO_LONG, LOUD_VIDEO_UNDER_MUSIC, NO_TITLE, ASPECT_MISMATCH, NO_FADE_OUT, GAP, EMPTY, TOO_MANY_EFFECTS, NO_SUBTITLES }

data class Suggestion(val kind: SuggestionKind, val message: String, val clipId: String? = null, val actionLabel: String? = null)

/** Rule-based editing suggestions computed from the project. */
object Suggestions {
    fun analyze(p: Project): List<Suggestion> {
        val out = ArrayList<Suggestion>()
        val main = p.tracks.firstOrNull { it.kind == TrackKind.VIDEO }
        if (main == null || main.clips.isEmpty()) return listOf(Suggestion(SuggestionKind.EMPTY, "Ajoutez des vidéos ou images pour commencer.", actionLabel = "Importer"))
        p.missingMedia.forEach { out += Suggestion(SuggestionKind.MISSING_MEDIA, "Média introuvable : ${it.displayName}", actionLabel = "Remplacer") }
        for (c in main.clips) if (c.durationUs > 15_000_000 && c.kind == ClipKind.MEDIA)
            out += Suggestion(SuggestionKind.LONG_CLIP, "Plan long (%.0f s) : coupez les silences ou les passages lents.".format(c.durationUs / 1e6), c.id, "Détecter les silences")
        if (main.clips.size >= 3 && main.clips.none { it.transitionIn != null })
            out += Suggestion(SuggestionKind.NO_TRANSITIONS, "Aucune transition : un fondu léger rend les coupes plus douces.", actionLabel = "Ajouter des fondus")
        val sorted = main.sortedClips()
        for (i in 1 until sorted.size) if (sorted[i].startUs - sorted[i - 1].endUs > 100_000)
            out += Suggestion(SuggestionKind.GAP, "Trou noir de %.1f s dans la piste principale.".format((sorted[i].startUs - sorted[i - 1].endUs) / 1e6), sorted[i].id, "Combler")
        val videoEnd = main.endUs
        val music = p.tracks.filter { it.kind == TrackKind.AUDIO && it.audioRole == AudioRole.MUSIC }.flatMap { it.clips }
        music.filter { it.endUs > videoEnd + 500_000 }.forEach { out += Suggestion(SuggestionKind.MUSIC_TOO_LONG, "La musique dépasse la vidéo de %.1f s.".format((it.endUs - videoEnd) / 1e6), it.id, "Ajuster") }
        music.filter { it.audio.fadeOutUs == 0L && it.endUs >= videoEnd - 200_000 }.forEach { out += Suggestion(SuggestionKind.NO_FADE_OUT, "Ajoutez un fondu de sortie à la musique.", it.id, "Fondu 1,5 s") }
        if (music.isNotEmpty() && main.clips.any { it.audio.volume >= 1.0 && !it.audio.muted && p.asset(it.assetId)?.hasAudio == true })
            out += Suggestion(SuggestionKind.LOUD_VIDEO_UNDER_MUSIC, "Le son des vidéos peut couvrir la musique : baissez-le ou utilisez la voix uniquement.", actionLabel = "Baisser à 30 %")
        if (p.tracks.none { it.kind == TrackKind.TEXT && it.clips.isNotEmpty() })
            out += Suggestion(SuggestionKind.NO_TITLE, "Ajoutez un titre d'accroche dans les 3 premières secondes.", actionLabel = "Générer un titre")
        val canvasR = p.format.aspectRatio
        main.clips.filter { c -> val a = p.asset(c.assetId); a != null && a.width > 0 && !c.transform.fill && kotlin.math.abs(a.displayWidth.toDouble() / a.displayHeight - canvasR) > 0.3 }
            .take(3).forEach { out += Suggestion(SuggestionKind.ASPECT_MISMATCH, "« ${it.name} » n'a pas le format du projet (bandes noires).", it.id, "Recadrer auto") }
        p.allClips.filter { it.effects.size > 6 }.forEach { out += Suggestion(SuggestionKind.TOO_MANY_EFFECTS, "Beaucoup d'effets sur « ${it.name} » : l'aperçu peut ralentir.", it.id) }
        if (p.tracks.none { it.kind == TrackKind.SUBTITLE } && main.clips.any { p.asset(it.assetId)?.hasAudio == true })
            out += Suggestion(SuggestionKind.NO_SUBTITLES, "Les sous-titres augmentent l'engagement (beaucoup de vidéos sont vues sans le son).", actionLabel = "Sous-titres auto")
        return out
    }
}
