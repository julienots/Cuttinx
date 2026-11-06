package com.superessence.cuttinx.core.smart

import com.superessence.cuttinx.core.autoedit.ProTools
import com.superessence.cuttinx.core.effects.Presets
import com.superessence.cuttinx.core.effects.TransitionRegistry
import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.timeline.TimelineEditor

/** One step of the director's plan, shown to the user before it is applied. */
class DirectorStep(val label: String, val apply: (Project) -> Project)

data class DirectorPlan(val steps: List<DirectorStep>, val platform: Platform?, val notUnderstood: List<String>) {
    fun apply(p: Project): Project = steps.fold(p) { acc, s -> runCatching { s.apply(acc) }.getOrDefault(acc) }
}

/**
 * "Réalisateur IA": the creator writes what they want in plain French ("format TikTok, 30
 * secondes, sous-titres jaunes, look cinéma, transitions glitch, baisse la musique quand je
 * parle, boucle parfaite") and gets an ordered plan of edits applied in one undo step.
 * Fully offline: a keyword grammar over the smart tools, no network involved.
 */
object SmartDirector {
    val examples = listOf(
        "Optimise pour TikTok en 30 secondes",
        "Look cinéma, transitions fondu, fondus au noir",
        "Baisse la musique quand je parle et supprime les clics",
        "Montage dynamique au tempo 128 bpm",
        "Accroche \"Tu ne vas pas y croire\" et teaser au début",
        "Boucle parfaite, sous-titres lisibles, format Reels",
        "Ralenti héros sur le plan le plus long",
        "Nettoie tout de façon minutieuse",
    )

    private val looks = listOf(
        listOf("cinema", "film", "blockbuster", "hollywood") to "Blockbuster",
        listOf("vintage", "nostalgi", "ancien") to "Nostalgie",
        listOf("vhs", "retro", "annees 90") to "Rétro VHS",
        listOf("noir et blanc", "polar", "n&b") to "Polar",
        listOf("chaud", "dore", "golden", "soleil") to "Doré",
        listOf("froid", "glace", "hiver") to "Glacier",
        listOf("neon", "nuit") to "Néon nocturne",
        listOf("pastel", "doux") to "Pastel",
        listOf("eclat", "lumineux", "vif") to "Éclat",
        listOf("dramatique", "contraste") to "Contraste dramatique",
        listOf("super 8", "pellicule") to "Super 8",
        listOf("reve", "onirique") to "Rêve",
        listOf("clip", "musical") to "Clip musical",
        listOf("coucher", "sunset") to "Coucher de soleil",
        listOf("foret", "nature", "vert") to "Forêt",
    )

    fun plan(prompt: String, p: Project): DirectorPlan {
        val q = " " + SmartTools.norm(prompt).replace(Regex("[,;.!]+"), " ").replace(Regex("\\s+"), " ") + " "
        fun has(vararg w: String) = w.any { q.contains(SmartTools.norm(it)) }
        val steps = ArrayList<DirectorStep>()
        val understood = HashSet<String>()
        fun step(label: String, word: String, f: (Project) -> Project) { steps += DirectorStep(label, f); understood += word }

        // platform & format first: everything else is computed in the final format
        val platform = when {
            has("tiktok", "tik tok") -> Platform.TIKTOK
            has("reels", "reel", "instagram", "insta") -> Platform.REELS
            has("shorts", "short") -> Platform.SHORTS
            has("youtube", "16:9", "paysage", "horizontal") -> Platform.YOUTUBE
            else -> null
        }
        if (platform != null) step("Adapter à ${platform.label} (format ${platform.aspect.label}, cadre rempli, textes hors des boutons)", platform.name) { SmartTools.forPlatform(it, platform) }
        else if (has("carre", "1:1")) step("Format carré 1:1", "carre") { TimelineEditor.changeFormat(it, ProjectFormat.forAspect(AspectPreset.SQUARE_1_1, 1080, it.format.frameRate), fillMode = true) }

        // target duration ("30 secondes", "1 minute", "45s")
        Regex("(\\d+(?:[.,]\\d+)?) ?(secondes|seconde|sec|s|minutes|minute|min|mn)\\b").find(q)?.let { m ->
            val n = m.groupValues[1].replace(',', '.').toDouble()
            val us = (n * (if (m.groupValues[2].startsWith("m")) 60 else 1) * 1_000_000).toLong()
            if (us in 3_000_000..3_600_000_000) step("Raccourcir intelligemment à ${SmartTools.clock(us)} (le cœur de chaque plan est gardé)", "duree") { SmartTools.fitToDuration(it, us) }
        }

        // quoted text = hook title
        Regex("[\"«“]([^\"»”]{2,80})[\"»”]").find(prompt)?.let { m ->
            val t = m.groupValues[1].trim()
            step("Titre d'accroche « $t »", "accroche") { ProTools.hookTitle(it, t) }
        } ?: run { if (has("accroche", "hook")) step("Titre d'accroche", "accroche") { ProTools.hookTitle(it, "ATTENDS LA FIN !") } }
        if (has("teaser", "commence par le meilleur", "meilleur moment au debut")) step("Teaser du meilleur moment au début", "teaser") { SmartTools.teaser(it) }

        // rhythm
        Regex("(\\d{2,3}) ?bpm").find(q)?.let { m ->
            val bpm = m.groupValues[1].toDouble()
            step("Montage au tempo ($bpm BPM) avec zooms alternés", "tempo") { SmartTools.tempoCut(it, SmartTools.beatsFromBpm(it, bpm)) }
        } ?: run {
            if (has("tempo", "rythme", "dynamique", "energique", "nerveux")) step("Montage au tempo (120 BPM) avec zooms alternés", "tempo") { SmartTools.tempoCut(it, SmartTools.beatsFromBpm(it, 120.0)) }
            else if (has("jump cut", "punch", "zooms alternes")) step("Zooms alternés sur les jump cuts", "punch") { SmartTools.punchIn(it) }
        }
        if (has("zoom lent", "ken burns", "slow zoom")) step("Zoom lent sur tous les plans", "zoomlent") { m -> SmartTools.mainTrack(m)?.let { ProTools.slowZoomAll(m, it.id) } ?: m }
        if (has("animation", "anime les plans", "entrees")) step("Animation d'entrée différente sur chaque plan", "anim") { m -> SmartTools.mainTrack(m)?.let { ProTools.autoAnimate(m, it.id) } ?: m }

        // speed
        RampPreset.entries.firstOrNull { r -> when (r) {
            RampPreset.HERO -> has("heros", "hero", "ralenti")
            RampPreset.BULLET -> has("bullet", "matrix")
            RampPreset.RUSH -> has("acceler progressivement", "montee de vitesse")
            RampPreset.BRAKE -> has("freinage")
            RampPreset.PULSE -> has("pulsation")
        } }?.let { r ->
            step("Rampe de vitesse « ${r.label} » sur le plan le plus long", "vitesse") { m ->
                val c = SmartTools.mainTrack(m)?.clips?.filter { it.kind == ClipKind.MEDIA && m.asset(it.assetId)?.type == MediaType.VIDEO }?.maxByOrNull { it.durationUs }
                if (c == null) m else SmartTools.speedRamp(m, c.id, r)
            }
        }
        Regex("(?<![a-z])x ?(\\d(?:[.,]\\d)?)\\b|\\b(\\d(?:[.,]\\d)?) ?x\\b").find(q)?.let { m ->
            val v = (m.groupValues[1].ifEmpty { m.groupValues[2] }).replace(',', '.').toDoubleOrNull()
            if (v != null && v in 0.2..8.0) step("Vitesse ×$v sur tout le montage", "x") { pr ->
                var np = pr; SmartTools.mainTrack(pr)?.clips?.filter { it.kind == ClipKind.MEDIA }?.forEach { np = TimelineEditor.setSpeed(np, it.id, v) }; np
            }
        }

        // look
        looks.firstOrNull { (ws, _) -> ws.any { has(it) } }?.let { (_, name) -> step("Look « $name » sur tous les plans", "look") { SmartTools.applyLook(it, name) } }
            ?: Presets.builtIn.firstOrNull { has(it.name) }?.let { pr -> step("Look « ${pr.name} » sur tous les plans", "look") { SmartTools.applyLook(it, pr.name) } }

        // transitions
        if (has("transition")) {
            val named = TransitionRegistry.all.firstOrNull { d -> has(d.name) || q.contains(" ${d.id.replace('_', ' ')} ") }
                ?: TransitionRegistry.all.firstOrNull { d -> d.name.split(" ").first().let { w -> w.length >= 4 && q.contains(" " + SmartTools.norm(w)) } }
            if (named != null) step("Transition « ${named.name} » à chaque coupe", "transition") { SmartTools.transitionsEverywhere(it, named.id) }
            else step("Transitions dynamiques variées à chaque coupe", "transition") { m -> SmartTools.mainTrack(m)?.let { ProTools.autoTransitions(m, it.id) } ?: m }
        }
        if (has("fondu au noir", "fondus au noir", "fondu debut", "fondus debut", "fondu final", "fade")) step("Fondu au noir au début et à la fin", "fondus") { SmartTools.fadeInOut(it) }

        // texts
        if (has("sous-titre", "sous titre", "texte")) {
            if (has("jaune", "createur", "gros")) step("Sous-titres style créateur (gros, jaunes, contour)", "soustitres") { ProTools.creatorCaptions(it) }
            if (has("lisible", "decoupe", "coupe les textes", "trop long")) step("Découper les textes en blocs lisibles", "lisible") { SmartTools.readableSubtitles(it) }
        }
        if (has("zone", "boutons", "interface", "cache")) step("Sortir les textes des zones de l'interface", "zones") { SmartTools.safeZones(it, platform ?: Platform.TIKTOK) }
        if (has("barre de progression", "progression")) step("Barre de progression", "progression") { ProTools.progressBar(it) }
        Regex("@[a-z0-9_.]{2,30}").find(q)?.let { m -> step("Pseudo ${m.value} en filigrane", "pseudo") { ProTools.handleWatermark(it, m.value) } }
        if (has("chapitre")) understood += "chapitres" // read-only: shown by the panel

        // audio
        if (has("baisse la musique", "ducking", "quand je parle", "sous la voix", "voix")) step("Ducking : la musique baisse quand quelqu'un parle", "duck") { SmartTools.autoDuck(it) }
        if (has("clic", "click", "micro")) step("Micro-fondus anti-clics à chaque coupe", "clics") { SmartTools.microFades(it) }
        if (has("musique a la fin", "cale la musique", "fin de musique")) step("Musique calée sur la fin avec fondu", "musique") { SmartTools.fitMusicToVideo(it) }

        // structure
        if (has("boucle", "loop")) step("Boucle parfaite (la fin se raccorde au début)", "boucle") { SmartTools.perfectLoop(it) }
        if (has("bandes", "remplir le cadre", "plein cadre")) step("Remplir le cadre (plus de bandes noires)", "bandes") { SmartTools.fillFrame(it) }
        if (has("nettoie", "nettoyage", "propre", "minutieu", "clean", "corrige tout", "repare")) {
            step("Nettoyage minutieux (fragments, images noires, pistes vides)", "nettoie") { SmartTools.cleanUp(it) }
            step("Micro-fondus anti-clics", "clics") { SmartTools.microFades(it) }
            if (has("corrige tout", "repare")) step("Toutes les corrections du contrôle qualité", "qualite") { QualityCoach.fixAll(it, platform ?: Platform.TIKTOK) }
        }
        if (has("optimise", "magique", "automatique", "tout seul", "fais le", "ameliore")) {
            step("Passe magique : nettoyage, anti-clics, ducking, textes lisibles, musique calée", "magie") { SmartTools.magic(it, platform ?: Platform.TIKTOK) }
        }

        // keep only one step per label (the user may repeat themselves)
        val unique = steps.distinctBy { it.label }
        val words = q.trim().split(" ").filter { it.length >= 4 }
        val notUnderstood = if (unique.isEmpty()) words.take(6) else emptyList()
        return DirectorPlan(unique, platform, notUnderstood)
    }
}
