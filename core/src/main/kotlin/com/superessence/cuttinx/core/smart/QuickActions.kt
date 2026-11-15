package com.superessence.cuttinx.core.smart

import com.superessence.cuttinx.core.autoedit.ProTools
import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.timeline.TimelineEditor
import com.superessence.cuttinx.core.effects.EffectRegistry
import com.superessence.cuttinx.core.effects.TransitionRegistry

/** What an action needs before it can run. */
enum class Need(val hint: String) {
    NONE(""), CLIP("Sélectionne un élément"), TWO("Sélectionne au moins deux éléments (le premier sert de modèle)"),
    VIDEO("Sélectionne un plan vidéo"), PLAYHEAD("Place la tête de lecture à l'intérieur d'un plan"),
}

/** The state an action works on: the montage, the selection (in selection order) and the playhead. */
class EditContext(val project: Project, val selection: List<String>, val playheadUs: Long, val platform: Platform = Platform.REELS) {
    val clips: List<Clip> get() = selection.mapNotNull { project.findClip(it)?.second }
    val first: Clip? get() = clips.firstOrNull()
    val firstVideo: Clip? get() = first?.takeIf { it.kind == ClipKind.MEDIA && project.asset(it.assetId)?.type == MediaType.VIDEO }
    val mainTrackId: String? get() = SmartTools.mainTrack(project)?.id
    /** Track of the first selected element, else the main track. */
    val trackId: String? get() = selection.firstOrNull()?.let { project.findClip(it)?.first?.id } ?: mainTrackId
    /** Selected shots crossing the playhead, else the main-track shot under it. */
    val trimTargets: List<String> get() = clips.filter { playheadUs > it.startUs && playheadUs < it.endUs }.map { it.id }.ifEmpty { EditTools.mainClipsAt(project, playheadUs) }
}

class QuickAction(
    val id: String, val label: String, val group: String, val keywords: String, val shortcut: String?, val need: Need,
    /** Name of an icon of the shared icon set. */
    val icon: String,
    /** Labo CUTTINX technology this action belongs to (unlocked by watching an advert, or CUTTINX Plus for [Tech.plus]), null = free. */
    val tech: Tech? = null,
    val run: (EditContext) -> Project,
) {
    /** True while the technology of this action is still locked. */
    val locked: Boolean get() = tech != null && !TechVault.isUnlocked(tech)

    fun available(c: EditContext): Boolean = when (need) {
        Need.NONE -> true
        Need.CLIP -> c.first != null
        Need.TWO -> c.clips.size >= 2
        Need.VIDEO -> c.firstVideo != null
        Need.PLAYHEAD -> c.trimTargets.isNotEmpty()
    }
}

/**
 * "Actions rapides": every editing tool of CUTTINX in one searchable list (Ctrl + K on PC, the
 * search button of the toolbar on Android). Type a few letters ("couper", "boucle", "tiktok"),
 * run with Enter or a tap. Each action is one undoable edit.
 */
object QuickActions {
    val groups = listOf("CUTTINX Plus", "Labo CUTTINX (technologies)", "Modèles en un geste", "Couper et découper", "Durée et rythme", "Ordre et répétition", "Son", "Son pro", "Style et mouvement",
        "Animation et caméra", "Textes et titres", "Disposition à l'écran", "Couleur et looks", "Effets vidéo", "Effets audio", "Transitions", "Vitesse", "Réglages", "Organisation", "Publication")

    val all: List<QuickAction> by lazy { build() }

    private fun build(): List<QuickAction> {
        val l = ArrayList<QuickAction>()
        fun act(id: String, label: String, group: String, kw: String, shortcut: String?, need: Need, icon: String, run: (EditContext) -> Project) { l += QuickAction(id, label, group, kw, shortcut, need, icon, run = run) }
        val g = listOf("Couper et découper", "Durée et rythme", "Ordre et répétition", "Son", "Style et mouvement", "Réglages", "Publication")

        // ---- cut
        act("split", "Couper à la tête de lecture", g[0], "diviser trancher scinder cut", "S", Need.NONE, "Split") { c ->
            val t = c.playheadUs
            val targets = c.clips.filter { t > it.startUs + 50_000 && t < it.endUs - 50_000 }
            if (targets.isEmpty()) TimelineEditor.splitAll(c.project, t) else targets.fold(c.project) { a, x -> TimelineEditor.split(a, x.id, t).first }
        }
        act("trim_before", "Couper avant la tête de lecture et resserrer", g[0], "rogner début ripple trim", "Q", Need.PLAYHEAD, "TrimBefore") { c -> EditTools.rippleTrim(c.project, c.playheadUs, true, c.trimTargets) }
        act("trim_after", "Couper après la tête de lecture et resserrer", g[0], "rogner fin ripple trim", "W", Need.PLAYHEAD, "TrimAfter") { c -> EditTools.rippleTrim(c.project, c.playheadUs, false, c.trimTargets) }
        for (n in listOf(2, 3, 4, 6)) act("split_$n", "Découper en $n parties égales", g[0], "diviser tranches morceaux", null, Need.CLIP, "Split") { c -> ClipOps.splitEqual(c.project, c.first!!.id, n) }
        for (s in listOf(1, 2, 5)) act("every_$s", "Découper toutes les $s seconde${if (s > 1) "s" else ""}", g[0], "diviser régulier intervalle", null, Need.CLIP, "Split") { c -> ClipOps.splitEvery(c.project, c.first!!.id, s * 1_000_000L) }
        act("split_markers", "Couper à tous les marqueurs", g[0], "marqueur repère diviser", null, Need.NONE, "Marker") { c -> EditTools.splitAtMarkers(c.project) }
        act("markers_cuts", "Marqueurs sur chaque coupe", g[0], "repère plan chapitre", null, Need.NONE, "Marker") { c -> EditTools.markersAtCuts(c.project) }
        act("delete", "Supprimer", g[0], "effacer retirer enlever", "Suppr", Need.CLIP, "Delete") { c -> TimelineEditor.deleteClips(c.project, c.selection) }
        act("delete_ripple", "Supprimer et resserrer", g[0], "effacer retirer trou ripple", "Maj + Suppr", Need.CLIP, "RippleDelete") { c -> TimelineEditor.deleteClips(c.project, c.selection, ripple = true) }
        act("duplicate", "Dupliquer", g[0], "copier doubler cloner", "Ctrl + D", Need.CLIP, "Duplicate") { c -> TimelineEditor.duplicate(c.project, c.first!!.id).first }
        act("close_gaps", "Fermer les espaces de la piste", g[0], "trous vides combler resserrer", null, Need.NONE, "CloseGaps") { c -> EditTools.closeGaps(c.project, c.trackId) }

        // ---- duration & rhythm
        for (s in listOf(15, 30, 45, 60)) act("fit_$s", "Raccourcir à $s secondes", g[1], "durée couper intelligent réduire montage", null, Need.NONE, "Fit") { c -> SmartTools.fitToDuration(c.project, s * 1_000_000L) }
        for (s in listOf(2, 3, 5)) act("equal_$s", "Plans de $s s chacun (milieu conservé)", g[1], "durée égale uniforme rythme rafale", null, Need.NONE, "Equalize") { c -> ClipOps.equalShots(c.project, c.mainTrackId ?: "", s * 1_000_000L) }
        for (s in listOf(2, 3)) act("stills_$s", "Photos et textes de $s s", g[1], "diaporama image durée", null, Need.NONE, "Image") { c -> EditTools.equalizeDurations(c.project, c.mainTrackId ?: "", s * 1_000_000L) }
        act("punch", "Zooms alternés sur les coupes", g[1], "jump cut punch dynamique", null, Need.NONE, "ZoomIn") { c -> SmartTools.punchIn(c.project) }
        act("tempo", "Montage au tempo (120 BPM)", g[1], "rythme musique beat coupes", null, Need.NONE, "Music") { c -> SmartTools.tempoCut(c.project, SmartTools.beatsFromBpm(c.project, 120.0)) }
        for (r in RampPreset.entries) act("ramp_${r.name.lowercase()}", "Vitesse : ${r.label}", g[1], "ralenti accéléré rampe speed", null, Need.VIDEO, "ZoomSlow") { c -> SmartTools.speedRamp(c.project, c.firstVideo!!.id, r) }

        // ---- order & repetition
        for (n in listOf(2, 3)) act("repeat_$n", "Répéter $n fois", g[2], "boucle copie multiplier", null, Need.CLIP, "Replay") { c -> ClipOps.repeat(c.project, c.first!!.id, n) }
        act("loop_end", "Boucler jusqu'à la fin du projet", g[2], "répéter remplir musique prolonger", null, Need.CLIP, "Replay") { c -> ClipOps.fillTo(c.project, c.first!!.id, c.project.durationUs) }
        act("reverse", "Inverser l'ordre des plans", g[2], "retourner dernier premier", null, Need.NONE, "Back") { c -> ClipOps.reverseOrder(c.project, c.mainTrackId ?: "") }
        act("shuffle", "Mélanger l'ordre des plans", g[2], "aléatoire hasard random", null, Need.NONE, "Replay") { c -> ClipOps.shuffle(c.project, c.mainTrackId ?: "", c.project.durationUs) }

        // ---- sound
        act("duck", "Baisser la musique sous la voix", g[3], "ducking volume dialogue mixage", null, Need.NONE, "Voice") { c -> SmartTools.autoDuck(c.project) }
        act("micro_fades", "Supprimer les clics audio", g[3], "fondu coupe son bruit", null, Need.NONE, "NoiseOff") { c -> SmartTools.microFades(c.project) }
        act("music_fit", "Caler la musique sur la fin", g[3], "fondu sortie fin", null, Need.NONE, "Music") { c -> SmartTools.fitMusicToVideo(c.project) }
        act("j_cut", "Coupe en J (le son démarre avant l'image)", g[3], "dialogue audio avant", null, Need.VIDEO, "JlCut") { c -> EditTools.jlCut(c.project, c.firstVideo!!.id, 500_000, 0) }
        act("l_cut", "Coupe en L (le son continue après l'image)", g[3], "dialogue audio après", null, Need.VIDEO, "JlCut") { c -> EditTools.jlCut(c.project, c.firstVideo!!.id, 0, 500_000) }
        act("jl_cut", "Coupe en J + L", g[3], "dialogue audio déborde", null, Need.VIDEO, "JlCut") { c -> EditTools.jlCut(c.project, c.firstVideo!!.id, 500_000, 500_000) }
        act("fade_inout", "Fondus au noir début et fin", g[3], "ouverture fermeture image son", null, Need.NONE, "Volume") { c -> SmartTools.fadeInOut(c.project) }

        // ---- style & motion
        for ((id, name, lab) in listOf(Triple("cinema", "Blockbuster", "Look cinéma"), Triple("vintage", "Nostalgie", "Look nostalgique"), Triple("bw", "Polar", "Look noir et blanc"), Triple("warm", "Doré", "Look doré"), Triple("cold", "Glacier", "Look glacé")))
            act("look_$id", "$lab sur tous les plans", g[4], "couleur filtre étalonnage", null, Need.NONE, "Effects") { c -> SmartTools.applyLook(c.project, name) }
        act("fill", "Remplir le cadre (sans bandes noires)", g[4], "plein écran recadrer", null, Need.NONE, "Background") { c -> SmartTools.fillFrame(c.project) }
        act("auto_trans", "Transitions dynamiques à chaque coupe", g[4], "transition enchaînement", null, Need.NONE, "Transitions") { c -> c.mainTrackId?.let { ProTools.autoTransitions(c.project, it) } ?: c.project }
        act("auto_anim", "Animation d'entrée sur chaque plan", g[4], "mouvement apparition", null, Need.NONE, "Animation") { c -> c.mainTrackId?.let { ProTools.autoAnimate(c.project, it) } ?: c.project }
        act("slow_zoom", "Zoom lent sur tous les plans", g[4], "ken burns mouvement", null, Need.NONE, "ZoomSlow") { c -> c.mainTrackId?.let { ProTools.slowZoomAll(c.project, it) } ?: c.project }
        act("ken_burns", "Photos animées", g[4], "image mouvement", null, Need.NONE, "Image") { c -> ProTools.kenBurnsImages(c.project) }

        // ---- settings
        act("paste_all", "Coller tous les réglages du premier élément", g[5], "attributs copier style effets", null, Need.TWO, "Paste") { c -> EditTools.copyAttributes(c.project, c.selection.first(), c.selection.drop(1)) }
        act("paste_fx", "Coller seulement les effets du premier élément", g[5], "attributs copier filtre", null, Need.TWO, "Effects") { c -> EditTools.copyAttributes(c.project, c.selection.first(), c.selection.drop(1), setOf(Attribute.EFFECTS)) }

        // ---- publication
        for (pf in Platform.entries) act("for_${pf.name.lowercase()}", "Adapter à ${pf.label}", g[6], "format publier export ${pf.aspect.label} réseau", null, Need.NONE, "Fit") { c -> SmartTools.forPlatform(c.project, pf) }
        act("safe", "Textes hors des boutons de la plateforme", g[6], "zone sûre interface sous-titres", null, Need.NONE, "Eye") { c -> SmartTools.safeZones(c.project, c.platform) }
        act("readable", "Sous-titres lisibles (blocs courts)", g[6], "texte lecture découper", null, Need.NONE, "Captions") { c -> SmartTools.readableSubtitles(c.project) }
        act("clean", "Nettoyage minutieux", g[6], "fragments trous images noires propre", null, Need.NONE, "Delete") { c -> SmartTools.cleanUp(c.project) }
        act("hook", "Ajouter un titre d'accroche", g[6], "texte début attention", null, Need.NONE, "Hook") { c -> ProTools.hookTitle(c.project, "ATTENDS LA FIN !") }
        act("teaser", "Teaser du meilleur moment au début", g[6], "bande-annonce accroche", null, Need.NONE, "Hook") { c -> SmartTools.teaser(c.project) }
        act("perfect_loop", "Boucle parfaite", g[6], "replay raccord", null, Need.NONE, "Replay") { c -> SmartTools.perfectLoop(c.project) }
        act("magic", "Passe magique", g[6], "tout automatique optimiser", null, Need.NONE, "Pro") { c -> SmartTools.magic(c.project, c.platform) }
        V2.add(::act)
        V3.add(l)
        return l
    }

    /** Every technology of the Labo with its actions. */
    fun byTech(t: Tech): List<QuickAction> = all.filter { it.tech == t }

    /** Actions matching [query] (all tokens must match, accents ignored), best first; available ones before the others. */
    fun search(query: String, c: EditContext): List<QuickAction> {
        val tokens = SmartTools.norm(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
        val scored = all.mapNotNull { a ->
            if (tokens.isEmpty()) return@mapNotNull a to 0
            val label = SmartTools.norm(a.label); val kw = SmartTools.norm(a.keywords + " " + a.group)
            var score = 0
            for (t in tokens) {
                score += when {
                    label.split(" ").any { it.startsWith(t) } -> 3
                    label.contains(t) -> 2
                    kw.contains(t) -> 1
                    else -> return@mapNotNull null
                }
            }
            a to score
        }
        return scored.sortedWith(compareByDescending<Pair<QuickAction, Int>> { it.first.available(c) }.thenByDescending { it.second }).map { it.first }
    }

    operator fun get(id: String): QuickAction? = all.firstOrNull { it.id == id }
}

/** The actions added by CUTTINX 2.0. */
private object V2 {
    private const val TPL = "Modèles en un geste"; private const val ANIM = "Animation et caméra"; private const val TXT = "Textes et titres"
    private const val LAY = "Disposition à l'écran"; private const val SND = "Son pro"; private const val COL = "Couleur et looks"
    private const val TRN = "Transitions"; private const val SPD = "Vitesse"; private const val ORG = "Organisation"

    fun add(act: (String, String, String, String, String?, Need, String, (EditContext) -> Project) -> Unit) {
        // ---- templates
        for (t in Template.entries) act("tpl_${t.name.lowercase()}", "Modèle : ${t.label}", TPL, "recette automatique style ${t.description}", null, Need.NONE, "Studio") { c -> Templates.apply(c.project, t, c.platform) }

        // ---- animation & camera
        fun vis(c: EditContext) = Animator.targets(c.project, c.selection)
        for (m in CameraMove.entries) act("cam_${m.name.lowercase()}", m.label, ANIM, "caméra mouvement images clés keyframe ken burns", null, Need.NONE, "Animation") { c -> Animator.camera(c.project, vis(c), m) }
        act("zoom_hit", "Zoom choc à la tête de lecture", ANIM, "punch impact zoom rapide keyframe", null, Need.PLAYHEAD, "ZoomIn") { c -> Animator.zoomHit(c.project, c.trimTargets, c.playheadUs) }
        act("shake_hit", "Secousse à la tête de lecture", ANIM, "tremblement impact caméra keyframe", null, Need.PLAYHEAD, "Move") { c -> Animator.shakeAt(c.project, c.trimTargets, c.playheadUs) }
        for (bpm in listOf(100, 120, 128, 140)) act("pulse_$bpm", "Pulsation au tempo ($bpm BPM)", ANIM, "rythme beat musique zoom battement", null, Need.NONE, "Music") { c -> Animator.pulseOnBeats(c.project, vis(c), bpm.toDouble()) }
        act("fade_edges", "Apparition et disparition en fondu", ANIM, "opacité transparence keyframe", null, Need.NONE, "Eye") { c -> Animator.fadeEdges(c.project, vis(c)) }
        for (m in listOf(MotionPreset.POP, MotionPreset.ZOOM_IN, MotionPreset.SLIDE_UP, MotionPreset.SLIDE_LEFT, MotionPreset.ELASTIC, MotionPreset.WHIP, MotionPreset.DROP, MotionPreset.SPIN_ZOOM, MotionPreset.GLITCH, MotionPreset.FLIP_H))
            act("in_${m.name.lowercase()}", "Entrée : ${m.label}", ANIM, "animation apparition début", null, Need.NONE, "Animation") { c -> Animator.motion(c.project, vis(c), animIn = m) }
        for (m in listOf(MotionPreset.FADE, MotionPreset.ZOOM_OUT, MotionPreset.SLIDE_DOWN, MotionPreset.SLIDE_RIGHT, MotionPreset.SPIN, MotionPreset.FLICKER))
            act("out_${m.name.lowercase()}", "Sortie : ${m.label}", ANIM, "animation disparition fin", null, Need.NONE, "Animation") { c -> Animator.motion(c.project, vis(c), animOut = m) }
        for (m in listOf(LoopMotion.BREATHE, LoopMotion.FLOAT, LoopMotion.HEARTBEAT, LoopMotion.SHAKE, LoopMotion.WIGGLE, LoopMotion.ORBIT, LoopMotion.JELLY, LoopMotion.KEN_BURNS))
            act("loop_${m.name.lowercase()}", "En continu : ${m.label}", ANIM, "animation boucle permanente", null, Need.NONE, "Replay") { c -> Animator.motion(c.project, vis(c), loop = m) }
        act("anim_clear", "Retirer animations et images clés", ANIM, "supprimer mouvement keyframe effacer", null, Need.NONE, "KeyframeRemove") { c -> Animator.clear(c.project, vis(c)) }
        act("reset_tr", "Réinitialiser position et taille", ANIM, "centrer transformation remise à zéro", null, Need.CLIP, "Fit") { c -> Animator.resetTransform(c.project, vis(c)) }
        act("mirror_h", "Miroir horizontal", ANIM, "retourner inverser flip symétrie", null, Need.CLIP, "Mirror") { c -> Animator.mirror(c.project, vis(c), true) }
        act("mirror_v", "Miroir vertical", ANIM, "retourner flip tête en bas", null, Need.CLIP, "Mirror") { c -> Animator.mirror(c.project, vis(c), false) }
        act("rot_90", "Pivoter de 90°", ANIM, "rotation tourner", null, Need.CLIP, "Replay") { c -> Animator.rotate(c.project, vis(c), 90.0) }
        act("zoom_120", "Agrandir de 20 %", ANIM, "zoom recadrer taille plus grand", null, Need.CLIP, "ZoomIn") { c -> Animator.zoom(c.project, vis(c), 1.2) }
        act("zoom_80", "Réduire de 20 %", ANIM, "dézoom taille plus petit", null, Need.CLIP, "ZoomOut") { c -> Animator.zoom(c.project, vis(c), 0.8) }

        // ---- texts & titles
        for (lk in TextLook.entries) act("txt_${lk.name.lowercase()}", "Style de texte : ${lk.label}", TXT, "police titre sous-titres design", null, Need.NONE, "Text") { c -> Titles.applyLook(c.project, c.selection, lk) }
        act("txt_bigger", "Textes plus grands", TXT, "taille police agrandir", null, Need.NONE, "ZoomIn") { c -> Titles.resize(c.project, c.selection, 1.15) }
        act("txt_smaller", "Textes plus petits", TXT, "taille police réduire", null, Need.NONE, "ZoomOut") { c -> Titles.resize(c.project, c.selection, 1 / 1.15) }
        act("txt_upper", "Textes en majuscules", TXT, "capitales casse", null, Need.NONE, "Text") { c -> Titles.uppercase(c.project, c.selection, true) }
        act("txt_lower", "Textes en minuscules", TXT, "casse normale", null, Need.NONE, "Text") { c -> Titles.uppercase(c.project, c.selection, false) }
        for ((a, lab) in listOf(TextAlign.LEFT to "à gauche", TextAlign.CENTER to "au centre", TextAlign.RIGHT to "à droite"))
            act("txt_align_${a.name.lowercase()}", "Textes $lab", TXT, "aligner alignement position", null, Need.NONE, "Text") { c -> Titles.align(c.project, c.selection, a) }
        for ((col, lab) in listOf(0xFFFFFFFF to "blanc", 0xFFFFD21F to "jaune", 0xFF111111 to "noir", 0xFFFF2BD6 to "rose", 0xFF2BD9FF to "cyan"))
            act("txt_color_$lab", "Textes en $lab", TXT, "couleur police teinte", null, Need.NONE, "Text") { c -> Titles.recolor(c.project, c.selection, col) }
        act("title_open", "Titre d'ouverture", TXT, "début nom vidéo intro", null, Need.NONE, "Text") { c -> Titles.opening(c.project) }
        act("countdown", "Compte à rebours 3, 2, 1", TXT, "décompte chiffres départ", null, Need.NONE, "Progress") { c -> Titles.countdown(c.project, c.playheadUs) }
        act("ranking", "Classement #5 à #1 sur les plans", TXT, "top numéros liste", null, Need.NONE, "Star") { c -> Titles.ranking(c.project, true) }
        act("ranking_up", "Numéros 1, 2, 3 sur les plans", TXT, "étapes liste ordre", null, Need.NONE, "Star") { c -> Titles.ranking(c.project, false) }
        act("lower_third", "Bandeau nom à la tête de lecture", TXT, "lower third présentation personne interview", null, Need.NONE, "Captions") { c -> Titles.lowerThird(c.project, c.playheadUs) }
        act("end_card", "Carte de fin (merci + abonne-toi)", TXT, "outro fin abonnement appel action", null, Need.NONE, "Hook") { c -> Titles.endCard(c.project) }
        act("chapters_titles", "Titre de chapitre à chaque marqueur", TXT, "partie section marqueur", null, Need.NONE, "Marker") { c -> Titles.chapterTitles(c.project) }
        act("watermark", "Filigrane @compte", TXT, "signature pseudo logo protection", null, Need.NONE, "Watermark") { c -> Titles.watermark(c.project) }
        act("karaoke", "Sous-titres karaoké (mot par mot)", TXT, "révélation sous-titres animés", null, Need.NONE, "Captions") { c -> Titles.karaoke(c.project) }

        // ---- layout
        act("split_screen", "Écran partagé avec la sélection", LAY, "côte à côte duo split screen comparaison", null, Need.TWO, "Grid") { c -> Layouts.splitScreen(c.project, c.selection) }
        act("split_stacked", "Écran partagé haut / bas", LAY, "split screen vertical empilé", null, Need.TWO, "Grid") { c -> Layouts.splitScreen(c.project, c.selection, stacked = true) }
        act("split_side", "Écran partagé gauche / droite", LAY, "split screen côte à côte", null, Need.TWO, "Grid") { c -> Layouts.splitScreen(c.project, c.selection, stacked = false) }
        act("before_after", "Avant / Après", LAY, "comparaison split screen résultat", null, Need.TWO, "Grid") { c ->
            val vertical = c.project.format.height > c.project.format.width
            val np = Layouts.splitScreen(c.project, c.selection.take(2))
            val a = np.findClip(c.selection[0])?.second; val b = np.findClip(c.selection[1])?.second
            if (a == null || b == null) np else Titles.beforeAfter(np, a.startUs..a.endUs, b.startUs..b.endUs, vertical)
        }
        for (corner in Corner.entries) act("pip_${corner.name.lowercase()}", "Incrustation ${corner.label}", LAY, "pip picture in picture vignette réaction", null, Need.VIDEO, "Image") { c -> Layouts.pictureInPicture(c.project, c.firstVideo!!.id, corner) }
        act("pip_round", "Incrustation ronde (réaction)", LAY, "pip bulle cercle webcam facecam", null, Need.VIDEO, "Image") { c -> Layouts.pictureInPicture(c.project, c.firstVideo!!.id, Corner.BOTTOM_RIGHT, round = true) }
        act("blur_bg", "Fond flou (vidéo horizontale en vertical)", LAY, "arrière-plan flou bandes noires remplir", null, Need.NONE, "Background") { c -> Layouts.blurredBackground(c.project) }
        act("full_screen", "Tout en plein écran", LAY, "réinitialiser disposition centrer", null, Need.NONE, "Fit") { c -> Layouts.fullScreen(c.project) }

        // ---- sound pro
        fun snd(c: EditContext) = AudioPro.targets(c.project, c.selection)
        act("voice_podcast", "Voix de podcast (radio)", SND, "voix broadcast compresseur clarté micro", null, Need.NONE, "Voice") { c -> AudioPro.podcastVoice(c.project, AudioPro.voices(c.project, c.selection)) }
        act("denoise", "Réduire le bruit de fond", SND, "souffle bruit débruiter nettoyer", null, Need.NONE, "NoiseOff") { c -> AudioPro.denoise(c.project, AudioPro.voices(c.project, c.selection)) }
        for (e in EqPreset.entries) act("eq_${e.name.lowercase()}", "Égaliseur : ${e.label}", SND, "eq fréquences basses aigus", null, Need.NONE, "Volume") { c -> AudioPro.eq(c.project, snd(c), e) }
        for ((id, lab) in listOf("v_robot" to "robot", "v_radio" to "radio", "v_telephone" to "téléphone", "v_helium" to "hélium", "v_deep" to "grave", "v_monster" to "monstre", "v_cave" to "caverne", "v_underwater" to "sous l'eau"))
            act("vfx_$id", "Voix $lab", SND, "effet voix transformer", null, Need.NONE, "Voice") { c -> AudioPro.voiceEffect(c.project, AudioPro.voices(c.project, c.selection), id) }
        act("audio_fx_clear", "Retirer les effets audio", SND, "supprimer égaliseur voix normal", null, Need.NONE, "Delete") { c -> AudioPro.removeAudioEffects(c.project, snd(c)) }
        act("mute_videos", "Couper le son des vidéos", SND, "muet silence garder musique", null, Need.NONE, "VolumeOff") { c -> AudioPro.muteVideos(c.project, true) }
        act("unmute_videos", "Remettre le son des vidéos", SND, "activer son original", null, Need.NONE, "Volume") { c -> AudioPro.muteVideos(c.project, false) }
        for (db in listOf(-6, -12, -18)) act("music_$db", "Musique à $db dB", SND, "volume baisser fond", null, Need.NONE, "Music") { c -> AudioPro.level(c.project, AudioPro.music(c.project), db.toDouble()) }
        act("balance", "Mixage équilibré automatique", SND, "niveaux volume uniforme", null, Need.NONE, "Equalize") { c -> AudioPro.balance(c.project) }
        act("soft_edges", "Fondus doux sur tous les sons", SND, "clic début fin progressif", null, Need.NONE, "Volume") { c -> AudioPro.softEdges(c.project, snd(c)) }
        act("crossfade", "Fondu enchaîné entre les musiques", SND, "crossfade transition audio", null, Need.NONE, "Music") { c -> AudioPro.crossfadeMusic(c.project) }
        act("stereo_alt", "Stéréo alternée gauche / droite", SND, "pan spatial largeur", null, Need.NONE, "Volume") { c -> AudioPro.stereoAlternate(c.project, snd(c)) }
        act("stereo_center", "Tout au centre (mono)", SND, "pan centrer", null, Need.NONE, "Volume") { c -> AudioPro.center(c.project, snd(c)) }

        // ---- colour looks (every built-in look on all the shots)
        for (pr in com.superessence.cuttinx.core.effects.Presets.builtIn) {
            val slug = SmartTools.norm(pr.name).replace(Regex("[^a-z0-9]+"), "_")
            act("look2_$slug", "Look ${pr.name}", COL, "couleur filtre étalonnage ambiance", null, Need.NONE, "Effects") { c -> SmartTools.applyLook(c.project, pr.name) }
        }
        act("fx_clear", "Retirer tous les effets", COL, "supprimer filtre original", null, Need.NONE, "Delete") { c -> Organizer.removeEffects(c.project) }

        // ---- transitions on every cut
        for (id in listOf("fade", "fade_black", "fade_white", "dissolve", "slide", "zoom_in", "whip", "spin_zoom", "glitch", "flash_zoom", "swipe_up", "push_zoom", "blur", "light", "heart_wipe", "pixelate"))
            com.superessence.cuttinx.core.effects.TransitionRegistry[id]?.let { d ->
                act("tr_$id", "Transition ${d.name} partout", TRN, "enchaînement coupe raccord", null, Need.NONE, "Transitions") { c -> SmartTools.transitionsEverywhere(c.project, id) }
            }
        act("tr_clear", "Retirer toutes les transitions", TRN, "supprimer coupe franche cut", null, Need.NONE, "Delete") { c -> Organizer.removeTransitions(c.project) }

        // ---- speed
        for ((sp, lab) in listOf(0.25 to "Super ralenti (x0,25)", 0.5 to "Ralenti (x0,5)", 0.75 to "Légèrement ralenti (x0,75)", 1.5 to "Accéléré (x1,5)", 2.0 to "Rapide (x2)", 4.0 to "Très rapide (x4)", 8.0 to "Timelapse (x8)"))
            act("speed_${(sp * 100).toInt()}", "Vitesse : $lab", SPD, "slow motion accélérer vitesse", null, Need.VIDEO, "ZoomSlow") { c -> Animator.speed(c.project, c.selection, sp) }
        act("speed_reset", "Vitesse normale partout", SPD, "x1 réinitialiser", null, Need.NONE, "Replay") { c -> Organizer.normalSpeed(c.project) }

        // ---- organisation
        act("labels_type", "Couleurs par type d'élément", ORG, "étiquettes ranger trier", null, Need.NONE, "Grid") { c -> Organizer.labelByType(c.project) }
        act("labels_clear", "Retirer les couleurs d'étiquette", ORG, "étiquettes", null, Need.NONE, "Grid") { c -> Organizer.clearLabels(c.project) }
        act("number_shots", "Numéroter les plans", ORG, "renommer nom ordre", null, Need.NONE, "Marker") { c -> Organizer.numberShots(c.project) }
        act("group", "Grouper la sélection", ORG, "lier ensemble", null, Need.TWO, "Duplicate") { c -> Organizer.group(c.project, c.selection) }
        act("ungroup", "Dégrouper", ORG, "délier séparer", null, Need.CLIP, "Split") { c -> Organizer.ungroup(c.project, c.selection) }
        act("toggle_enabled", "Activer / désactiver la sélection", ORG, "masquer cacher ignorer", null, Need.CLIP, "EyeOff") { c -> Organizer.toggleEnabled(c.project, c.selection) }
        act("enable_all", "Tout réactiver", ORG, "afficher désactivés", null, Need.NONE, "Eye") { c -> Organizer.enableAll(c.project) }
        act("lock_all", "Verrouiller toutes les pistes", ORG, "bloquer protéger", null, Need.NONE, "Lock") { c -> Organizer.lockAll(c.project, true) }
        act("unlock_all", "Déverrouiller toutes les pistes", ORG, "débloquer", null, Need.NONE, "Unlock") { c -> Organizer.lockAll(c.project, false) }
        act("show_all", "Afficher et réactiver le son de toutes les pistes", ORG, "visible solo muet", null, Need.NONE, "Eye") { c -> Organizer.showAll(c.project) }
        act("empty_tracks", "Supprimer les pistes vides", ORG, "nettoyer ranger", null, Need.NONE, "Delete") { c -> Organizer.removeEmptyTracks(c.project) }
        act("unused_media", "Retirer les médias inutilisés", ORG, "nettoyer bibliothèque", null, Need.NONE, "Delete") { c -> Organizer.removeUnusedMedia(c.project) }
        act("anims_clear_all", "Retirer toutes les animations du projet", ORG, "supprimer mouvement", null, Need.NONE, "KeyframeRemove") { c -> Organizer.removeAnimations(c.project) }
        for (s in listOf(5, 10, 30)) act("markers_$s", "Marqueur toutes les $s secondes", ORG, "repère régulier", null, Need.NONE, "Marker") { c -> Organizer.markersEvery(c.project, s * 1_000_000L) }
        act("markers_beats", "Marqueurs sur les mesures (120 BPM)", ORG, "tempo rythme beat", null, Need.NONE, "Music") { c -> Organizer.markersOnBeats(c.project, 120.0) }
        act("markers_clear", "Supprimer tous les marqueurs", ORG, "effacer repères", null, Need.NONE, "Delete") { c -> Organizer.clearMarkers(c.project) }
    }
}

/** CUTTINX 2.2: the Labo technologies (unlocked by an advert) and the full effect / transition catalogue. */
private object V3 {
    private const val LAB = "Labo CUTTINX (technologies)"; private const val VFX = "Effets vidéo"; private const val AFX = "Effets audio"
    private const val TRN = "Transitions"; private const val CUT = "Couper et découper"; private const val DUR = "Durée et rythme"

    fun add(l: MutableList<QuickAction>) {
        fun tech(t: Tech, id: String, label: String, kw: String, need: Need = Need.NONE, run: (EditContext) -> Project) {
            l += QuickAction("lab_$id", label, LAB, "${t.label} ${t.pitch} technologie labo $kw", null, need, t.icon, t, run)
        }
        fun free(id: String, label: String, group: String, kw: String, need: Need, icon: String, run: (EditContext) -> Project) {
            l += QuickAction(id, label, group, kw, null, need, icon, run = run)
        }

        // ---- Labo (locked until an advert is watched)
        tech(Tech.KINETIC, "kinetic", "Texte cinétique (mot par mot)", "typographie animée kinetic") { c -> Lab.kinetic(c.project, c.selection) }
        tech(Tech.ECHO, "echo", "Traînée temporelle", "écho trail fantôme pose longue", Need.NONE) { c -> Lab.echo(c.project, c.selection) }
        tech(Tech.ECHO, "echo_long", "Traînée temporelle longue", "écho trail fantôme", Need.NONE) { c -> Lab.echo(c.project, c.selection, 150_000) }
        tech(Tech.CLONES, "clones", "Clones x3", "multiplier copie triple") { c -> Lab.clones(c.project, c.selection) }
        tech(Tech.WALL, "wall", "Mur de clips en ouverture", "mosaïque grille intro") { c -> Lab.wall(c.project) }
        tech(Tech.IMPACT, "impacts", "Impacts sur les coupes", "flou vitesse rvb choc coupe") { c -> Lab.impacts(c.project) }
        tech(Tech.ZOOM_FLOW, "zoom_flow", "Zoom infini entre les plans", "transition zoom fluide plongée") { c -> Lab.zoomFlow(c.project) }
        for (bpm in listOf(100, 120, 128, 140)) tech(Tech.BEAT_FX, "beat_$bpm", "Flashs et secousses à $bpm BPM", "tempo beat musique flash") { c -> Lab.beatFx(c.project, bpm.toDouble()) }
        tech(Tech.BEAT_FX, "beat_flash_120", "Flashs seuls à 120 BPM", "tempo beat flash") { c -> Lab.beatFx(c.project, 120.0, shake = false) }
        for (m in Pacing.entries) tech(Tech.PACING, "pacing_${m.name.lowercase()}", "Rythme ${m.label}", "durée plans accélération montage") { c -> Lab.pacing(c.project, m) }
        tech(Tech.VELOCITY, "velocity", "Velocity sur tous les plans", "vitesse rampe edit ralenti accéléré") { c -> Lab.velocity(c.project) }
        for (seed in 1..5) tech(Tech.REMIX, "remix_$seed", "Remix IA : variante $seed", "version nouvelle aléatoire montage automatique") { c -> Lab.remix(c.project, seed) }
        tech(Tech.TAP_TEMPO, "tap_tempo", "Monter sur le tempo de mes marqueurs", "bpm tap rythme beat marqueur") { c -> Lab.tapTempo(c.project) }
        tech(Tech.REWIND, "rewind", "Rembobinage VHS à la fin", "retour arrière cassette fin") { c -> Lab.rewind(c.project) }
        for ((n, lab) in listOf(3 to "légère", 6 to "moyenne", 12 to "intense")) tech(Tech.GLITCH_STORM, "glitch_$n", "Tempête glitch $lab", "datamosh rvb éclats") { c -> Lab.glitchStorm(c.project, n) }
        tech(Tech.CREDITS, "credits", "Générique de fin déroulant", "crédits fin film cinéma") { c -> Lab.credits(c.project) }

        // ---- CUTTINX Plus (subscribers only)
        fun plus(t: Tech, id: String, label: String, kw: String, need: Need = Need.NONE, run: (EditContext) -> Project) {
            l += QuickAction("plus_$id", label, "CUTTINX Plus", "${t.label} ${t.pitch} plus abonnement premium $kw", null, need, t.icon, t, run)
        }
        plus(Tech.FRAME_LOCK, "frame_lock", "Tout caler à l'image près", "précision frame image exacte trous chevauchements") { c -> PlusLab.frameLock(c.project) }
        for (bpm in listOf(90, 100, 110, 120, 128, 140, 150)) plus(Tech.BEAT_LOCK, "beat_lock_$bpm", "Synchro au temps près à $bpm BPM", "tempo beat musique coupe précise") { c -> PlusLab.beatLock(c.project, bpm.toDouble()) }
        plus(Tech.BEAT_LOCK, "beat_lock_markers", "Synchro au temps près (tempo de mes marqueurs)", "tempo beat marqueurs tap") { c -> PlusLab.beatLock(c.project, 120.0) }
        plus(Tech.BEAT_LOCK, "beat_lock_every", "Synchro au temps près : une coupe par temps", "tempo beat rapide") { c -> PlusLab.beatLock(c.project, 120.0, everyBeats = 1) }
        for (g in Grade.entries) plus(Tech.CINE_GRADE, "grade_${g.name.lowercase()}", "Étalonnage cinéma : ${g.label}", "couleur look film lut étalonnage") { c -> PlusLab.cineGrade(c.project, g) }
        plus(Tech.MASTERING, "mastering", "Mastering broadcast de tout le son", "audio son mixage loudness compression limiteur voix musique") { c -> PlusLab.mastering(c.project, c.selection) }
        plus(Tech.RACK_FOCUS, "rack_up", "Mise au point : du bas vers le haut", "flou net focus profondeur") { c -> PlusLab.rackFocus(c.project, c.selection, true) }
        plus(Tech.RACK_FOCUS, "rack_down", "Mise au point : du haut vers le bas", "flou net focus profondeur") { c -> PlusLab.rackFocus(c.project, c.selection, false) }
        plus(Tech.SMOOTH_SLOWMO, "slowmo", "Super ralenti lissé", "ralenti slow motion vitesse courbe", Need.VIDEO) { c -> PlusLab.smoothSlowmo(c.project, c.selection) }
        plus(Tech.PARALLAX, "parallax", "Caméra virtuelle sur toutes les photos", "ken burns mouvement photo zoom travelling") { c -> PlusLab.parallax(c.project) }
        plus(Tech.VOLUMETRIC, "volumetric", "Lumière volumétrique", "rayons lumière halo glow") { c -> PlusLab.volumetric(c.project) }

        // ---- every video effect: on the selection, else as an effects layer over the whole video
        for (d in EffectRegistry.video().filter { !it.id.startsWith("_") }) {
            free("vfx_${d.id}", "Effet : ${d.name}", VFX, "filtre ${d.category.label} ${d.description}", Need.NONE, "Effects") { c -> Catalog.videoEffect(c.project, c.selection, d.id) }
        }
        free("vfx_layer", "Calque d'effets sur toute la vidéo", VFX, "adjustment calque vide", Need.NONE, "Effects") { c -> Catalog.layer(c.project) }

        // ---- every audio effect (the voice changers already have their own actions)
        val voices = setOf("v_robot", "v_radio", "v_telephone", "v_helium", "v_deep", "v_monster", "v_cave", "v_underwater")
        for (d in EffectRegistry.audio().filter { it.id !in voices }) {
            free("afx_${d.id}", "Effet audio : ${d.name}", AFX, "son ${d.category.label} ${d.description}", Need.NONE, "Volume") { c -> AudioPro.voiceEffect(c.project, AudioPro.targets(c.project, c.selection), d.id) }
        }

        // ---- the transitions not offered yet
        val offered = setOf("fade", "fade_black", "fade_white", "dissolve", "slide", "zoom_in", "whip", "spin_zoom", "glitch", "flash_zoom", "swipe_up", "push_zoom", "blur", "light", "heart_wipe", "pixelate")
        for (d in TransitionRegistry.all.filter { it.id !in offered }) {
            free("tr_${d.id}", "Transition ${d.name} partout", TRN, "enchaînement coupe raccord", Need.NONE, "Transitions") { c -> SmartTools.transitionsEverywhere(c.project, d.id) }
        }

        // ---- more cutting and timing
        for (n in listOf(8, 10, 12)) free("split_$n", "Découper en $n parties égales", CUT, "diviser tranches morceaux", Need.CLIP, "Split") { c -> ClipOps.splitEqual(c.project, c.first!!.id, n) }
        free("every_half", "Découper toutes les demi-secondes", CUT, "diviser rapide intervalle", Need.CLIP, "Split") { c -> ClipOps.splitEvery(c.project, c.first!!.id, 500_000) }
        free("black_1s", "Insérer 1 s de noir à la tête de lecture", CUT, "pause écran noir respiration", Need.NONE, "Add") { c -> Catalog.insertColor(c.project, c.playheadUs, 0xFF000000, 1_000_000) }
        free("white_flash", "Flash blanc à la tête de lecture", CUT, "éclair lumière impact", Need.NONE, "Add") { c -> Catalog.flash(c.project, c.playheadUs) }
        for (s in listOf(10, 20, 90, 120)) free("fit_$s", "Raccourcir à $s secondes", DUR, "durée couper intelligent réduire", Need.NONE, "Fit") { c -> SmartTools.fitToDuration(c.project, s * 1_000_000L) }
        for (s in listOf(1)) free("equal_$s", "Plans de $s s chacun (milieu conservé)", DUR, "durée égale uniforme rafale", Need.NONE, "Equalize") { c -> ClipOps.equalShots(c.project, c.mainTrackId ?: "", s * 1_000_000L) }
        // ---- 3.3: new montage gestures (also in the editor's toolbar)
        val kw33 = mapOf(
            "rhythm" to "rapide dynamique court plans nerveux", "velocity" to "vitesse ralenti accéléré rampe edit", "slowmo_end" to "ralenti fin cinématique",
            "stutter" to "répétition bégaiement accroche hook saccade", "cinema" to "film bandes noires ciné look grain", "countdown" to "compte à rebours 3 2 1 intro",
            "end_card" to "fin abonne-toi merci outro", "glitch_cuts" to "glitch coupe transition rvb", "slow_zoom" to "zoom lent ken burns caméra",
            "slideshow" to "photos diaporama souvenirs", "shorts59" to "shorts tiktok minute 59 vertical",
        )
        for ((id, label) in MontageKit.gestures) free("m33_$id", "Montage : $label", if (id in setOf("velocity", "slowmo_end")) "Vitesse" else DUR, kw33[id] ?: label, Need.NONE, "Fit") { c -> MontageKit.run(id, c.project) ?: c.project }
    }
}

/** Effect catalogue helpers. */
object Catalog {
    /** Adds the effect [effectId] to the selected visual clips (once), or an effects layer with it over the whole video. */
    fun videoEffect(p: Project, selection: List<String>, effectId: String): Project {
        val d = EffectRegistry[effectId] ?: return p
        val sel = selection.mapNotNull { p.findClip(it)?.second }.filter { Kit.isVisual(p, it) || it.kind == ClipKind.ADJUSTMENT }
        if (sel.isNotEmpty()) return Kit.editAll(p, sel.map { it.id }) { c ->
            if (c.effects.any { it.effectId == effectId }) c else c.copy(effects = c.effects + EffectInstance(com.superessence.cuttinx.core.util.Ids.new("fx"), effectId, d.defaults()))
        }
        val dur = p.durationUs.coerceAtLeast(TimelineEditor.DEFAULT_IMAGE_DURATION_US)
        val clip = TimelineEditor.adjustmentClip(0, dur).copy(name = d.name, effects = listOf(EffectInstance(com.superessence.cuttinx.core.util.Ids.new("fx"), effectId, d.defaults())))
        return Kit.place(p, TrackKind.EFFECT, "Effets", clip)
    }

    fun layer(p: Project): Project = Kit.place(p, TrackKind.EFFECT, "Effets", TimelineEditor.adjustmentClip(0, p.durationUs.coerceAtLeast(TimelineEditor.DEFAULT_IMAGE_DURATION_US)))

    /** A colour card inserted on the main track at [atUs] (the shot under the playhead is cut in two). */
    fun insertColor(p: Project, atUs: Long, color: Long, durUs: Long): Project {
        val (q, main) = TimelineEditor.mainTrack(p)
        if (main.locked) return p
        var np = q
        val under = main.clips.firstOrNull { atUs > it.startUs + 50_000 && atUs < it.endUs - 50_000 }
        if (under != null) np = runCatching { TimelineEditor.split(np, under.id, atUs).first }.getOrDefault(np)
        val at = if (under != null) atUs else (main.clips.filter { it.startUs >= atUs }.minOfOrNull { it.startUs } ?: main.endUs)
        return runCatching { TimelineEditor.insertClip(np, main.id, TimelineEditor.colorClip(color, at, durUs), ripple = true) }.getOrDefault(np)
    }

    /** White flash at [atUs]: an effects layer whose white overlay fades out in 0.25 s. */
    fun flash(p: Project, atUs: Long): Project {
        val d = EffectRegistry["color_overlay"] ?: return p
        val e = EffectInstance(com.superessence.cuttinx.core.util.Ids.new("fx"), "color_overlay", d.defaults() + mapOf("opacity" to 0.9, "saturation" to 0.0))
        val clip = TimelineEditor.adjustmentClip(atUs.coerceAtLeast(0), 250_000).copy(name = "Flash", effects = listOf(e),
            keyframes = mapOf(Prop.effect(e.id, "opacity") to KeyframeTrack(listOf(Keyframe(0, 0.9, Easing.EaseOut), Keyframe(250_000, 0.0, Easing.Smooth)))))
        return Kit.place(p, TrackKind.EFFECT, "Flashs", clip)
    }
}
