package com.superessence.cuttinx.core.smart

import com.superessence.cuttinx.core.autoedit.ProTools
import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.timeline.TimelineEditor

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
    val icon: String, val run: (EditContext) -> Project,
) {
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
    val groups = listOf("Couper et découper", "Durée et rythme", "Ordre et répétition", "Son", "Style et mouvement", "Réglages", "Publication")

    val all: List<QuickAction> by lazy { build() }

    private fun build(): List<QuickAction> {
        val l = ArrayList<QuickAction>()
        fun act(id: String, label: String, group: String, kw: String, shortcut: String?, need: Need, icon: String, run: (EditContext) -> Project) { l += QuickAction(id, label, group, kw, shortcut, need, icon, run) }
        val g = groups

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
        return l
    }

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
