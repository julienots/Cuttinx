package com.superessence.cuttinx.core.smart

import com.superessence.cuttinx.core.autoedit.ProTools
import com.superessence.cuttinx.core.model.*

/** One-tap edit recipes: each one is a chain of the tools above, applied as a single undoable edit. */
enum class Template(val label: String, val description: String) {
    VLOG("Vlog dynamique", "Zooms sur les coupes, transitions, look éclatant, son propre, titre d'accroche"),
    MEMORIES("Souvenirs", "Photos animées, look doré, fondus doux, zoom lent, musique calée sur la fin"),
    TRAILER("Bande-annonce", "Bandes cinéma, look blockbuster, teaser au début, titre, fondus au noir"),
    TOP("Top classement", "Numéros #5 à #1 sur chaque plan, zooms, transitions, carte de fin"),
    TUTORIAL("Tutoriel clair", "Voix nette, sous-titres lisibles, textes hors des boutons, chapitres"),
    SPORT("Sport / action", "Montage au tempo 128 BPM, pulsations, look contrasté, transitions énergiques"),
    PRODUCT("Présentation produit", "Plein cadre, zoom lent, look éclatant, titre, carte de fin"),
    PODCAST("Podcast vidéo", "Chaîne voix radio sur toutes les voix, musique baissée, sous-titres karaoké"),
    CINEMATIC("Cinématique", "Look film, bandes cinéma, panoramiques lents, fondus longs"),
    STORY("Story verticale", "Format 9:16, fond flou, accroche, sous-titres lisibles, boucle parfaite"),
    // 2.5
    TRAVEL("Carnet de voyage", "Photos animées, heure dorée, transitions fouettées, titre manuscrit, barre de progression"),
    WEDDING("Mariage / émotion", "Portrait doux, zooms lents, fondus longs, titre élégant, musique calée sur la fin"),
    GAMING("Gaming / highlights", "Compte à rebours, néon bleu, zooms sur les coupes, transitions glitch, accroche"),
    RECIPE("Recette / tuto cuisine", "Couleurs vibrantes, étapes en chapitres, sous-titres lisibles, carte de fin"),
    MUSIC_CLIP("Clip musical", "Coupes au tempo 120 BPM, pulsations, look clip, transitions glitch"),
    RETRO_MEMORIES("Souvenir rétro", "Bobine 16 mm, fondus, titre rétro, panoramique doux"),
    NIGHT("Ambiance nuit", "Nuit américaine, travelling lent, fondus au noir, bandes cinéma"),
    PORTRAIT("Portrait / interview", "Peau lissée, voix nette, sous-titres pro, bandeau nom et fonction"),
}

object Templates {
    fun apply(p: Project, t: Template, platform: Platform = Platform.REELS): Project {
        val main = SmartTools.mainTrack(p)?.id
        fun all(q: Project) = Animator.targets(q, emptyList())
        return runCatching {
            when (t) {
                Template.VLOG -> p.let { SmartTools.punchIn(it) }.let { q -> main?.let { ProTools.autoTransitions(q, it) } ?: q }
                    .let { SmartTools.applyLook(it, "Éclat") }.let { SmartTools.microFades(it) }.let { SmartTools.autoDuck(it) }.let { ProTools.hookTitle(it, "REGARDE ÇA") }
                Template.MEMORIES -> ProTools.kenBurnsImages(p).let { SmartTools.applyLook(it, "Doré") }.let { Animator.motion(it, all(it), MotionPreset.FADE, MotionPreset.FADE) }
                    .let { q -> main?.let { ProTools.slowZoomAll(q, it) } ?: q }.let { SmartTools.transitionsEverywhere(it, "fade") }.let { SmartTools.fitMusicToVideo(it) }
                Template.TRAILER -> SmartTools.applyLook(p, "Blockbuster").let { SmartTools.teaser(it) }.let { Titles.opening(it, "BIENTÔT", TextLook.LUXURY) }
                    .let { SmartTools.transitionsEverywhere(it, "fade_black") }.let { SmartTools.fadeInOut(it) }
                Template.TOP -> Titles.ranking(p).let { SmartTools.punchIn(it) }.let { SmartTools.transitionsEverywhere(it, "whip") }.let { Titles.endCard(it) }
                Template.TUTORIAL -> AudioPro.podcastVoice(p, AudioPro.voices(p, emptyList())).let { SmartTools.readableSubtitles(it) }.let { SmartTools.safeZones(it, platform) }
                    .let { EditTools.markersAtCuts(it) }.let { Titles.applyLook(it, Kit.selOrAll(it, emptyList()) { _, c -> c.kind == ClipKind.SUBTITLE }, TextLook.SUBTITLE_PRO) }
                Template.SPORT -> SmartTools.tempoCut(p, SmartTools.beatsFromBpm(p, 128.0)).let { Animator.pulseOnBeats(it, all(it), 128.0, 1.05) }
                    .let { SmartTools.applyLook(it, "Contraste dramatique") }.let { SmartTools.transitionsEverywhere(it, "flash_zoom") }
                Template.PRODUCT -> SmartTools.fillFrame(p).let { q -> main?.let { ProTools.slowZoomAll(q, it) } ?: q }.let { SmartTools.applyLook(it, "Éclat") }
                    .let { Titles.opening(it, "NOUVEAU", TextLook.MINIMAL) }.let { Titles.endCard(it, "Disponible maintenant", "Lien en description") }
                Template.PODCAST -> AudioPro.podcastVoice(p, AudioPro.voices(p, emptyList())).let { AudioPro.level(it, AudioPro.music(it), -18.0) }.let { SmartTools.readableSubtitles(it) }.let { Titles.karaoke(it) }
                Template.CINEMATIC -> SmartTools.applyLook(p, "Pellicule cinéma").let { Animator.camera(it, all(it), CameraMove.PAN_RIGHT) }
                    .let { SmartTools.transitionsEverywhere(it, "fade") }.let { SmartTools.fadeInOut(it, 1_200_000) }
                Template.STORY -> SmartTools.forPlatform(p, Platform.REELS).let { Layouts.blurredBackground(it) }.let { ProTools.hookTitle(it, "ATTENDS LA FIN") }
                    .let { SmartTools.readableSubtitles(it) }.let { SmartTools.perfectLoop(it) }
                Template.TRAVEL -> ProTools.kenBurnsImages(p).let { SmartTools.applyLook(it, "Heure dorée") }.let { SmartTools.transitionsEverywhere(it, "whip") }
                    .let { Titles.opening(it, "CARNET DE VOYAGE", TextLook.HANDWRITTEN) }.let { ProTools.progressBar(it) }.let { SmartTools.fitMusicToVideo(it) }
                Template.WEDDING -> SmartTools.applyLook(p, "Portrait doux").let { q -> main?.let { ProTools.slowZoomAll(q, it) } ?: q }.let { SmartTools.transitionsEverywhere(it, "fade") }
                    .let { Titles.opening(it, "Notre histoire", TextLook.LUXURY) }.let { SmartTools.fadeInOut(it, 1_500_000) }.let { SmartTools.fitMusicToVideo(it) }
                Template.GAMING -> Titles.countdown(p, 0).let { SmartTools.applyLook(it, "Néon bleu") }.let { SmartTools.punchIn(it) }
                    .let { SmartTools.transitionsEverywhere(it, "glitch") }.let { ProTools.hookTitle(it, "REGARDE CE MOMENT") }
                Template.RECIPE -> SmartTools.applyLook(p, "Pop vibrant").let { EditTools.markersAtCuts(it) }.let { Titles.chapterTitles(it) }
                    .let { SmartTools.readableSubtitles(it) }.let { Titles.endCard(it, "Bon appétit !", "Abonne-toi pour la prochaine recette") }
                Template.MUSIC_CLIP -> SmartTools.tempoCut(p, SmartTools.beatsFromBpm(p, 120.0)).let { Animator.pulseOnBeats(it, all(it), 120.0, 1.05) }
                    .let { SmartTools.applyLook(it, "Clip musical") }.let { SmartTools.transitionsEverywhere(it, "glitch") }
                Template.RETRO_MEMORIES -> SmartTools.applyLook(p, "Bobine 16 mm").let { SmartTools.transitionsEverywhere(it, "fade") }
                    .let { Titles.opening(it, "SOUVENIRS", TextLook.RETRO) }.let { Animator.camera(it, all(it), CameraMove.SWAY) }
                Template.NIGHT -> SmartTools.applyLook(p, "Nuit américaine").let { Animator.camera(it, all(it), CameraMove.PAN_LEFT) }
                    .let { SmartTools.transitionsEverywhere(it, "fade_black") }.let { SmartTools.fadeInOut(it, 1_000_000) }
                Template.PORTRAIT -> SmartTools.applyLook(p, "Portrait doux").let { AudioPro.podcastVoice(it, AudioPro.voices(it, emptyList())) }
                    .let { SmartTools.readableSubtitles(it) }.let { Titles.applyLook(it, Kit.selOrAll(it, emptyList()) { _, c -> c.kind == ClipKind.SUBTITLE }, TextLook.SUBTITLE_PRO) }
                    .let { Titles.lowerThird(it, 500_000) }
            }
        }.getOrDefault(p)
    }
}
