package com.superessence.cuttinx.core.create

import com.superessence.cuttinx.core.brand.Niche
import java.text.Normalizer

/** Voice of a caption. */
enum class CaptionTone(val label: String) { FUN("Fun"), PRO("Pro"), INSPIRING("Inspirant"), MYSTERY("Mystère"), CHILL("Chill") }

/** A ready caption: the text, its hashtags, and the whole thing as posted. */
data class Caption(val hook: String, val body: String, val cta: String, val hashtags: List<String>) {
    val text: String get() = listOf(hook, body, cta).filter { it.isNotBlank() }.joinToString("\n\n") + if (hashtags.isEmpty()) "" else "\n\n" + hashtags.joinToString(" ")
}

/**
 * Local caption and hashtag writer (templates and the words of the subject, no language model,
 * nothing sent anywhere), plus the checks of each network's limits.
 */
object Captions {
    private val stop = setOf(
        "le", "la", "les", "un", "une", "des", "du", "de", "d", "l", "et", "ou", "a", "à", "au", "aux", "en", "dans", "sur", "pour", "par", "avec",
        "sans", "mon", "ma", "mes", "ton", "ta", "tes", "son", "sa", "ses", "notre", "nos", "votre", "vos", "leur", "leurs", "ce", "cet", "cette",
        "ces", "qui", "que", "quoi", "est", "sont", "je", "tu", "il", "elle", "on", "nous", "vous", "ils", "elles", "the", "of", "and", "to", "my", "in",
        "plus", "tres", "très", "comment", "pourquoi", "quand", "fait", "faire",
    )

    private fun ascii(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")

    /** Meaningful words of the subject, in order. */
    fun keywords(subject: String): List<String> =
        subject.split(Regex("[^\\p{L}\\p{N}]+")).map { it.lowercase() }.filter { it.length > 2 && it !in stop && ascii(it) !in stop }.distinct()

    private fun tag(word: String): String = "#" + ascii(word).replace(Regex("[^A-Za-z0-9]"), "").lowercase()

    /** Hashtags for the subject: its own words, the niche, then the network's discovery tags, within the network's advice. */
    fun hashtags(subject: String, network: Network, niche: Niche? = null, max: Int = network.hashtagAdvice.last.coerceAtLeast(0)): List<String> {
        if (max <= 0) return emptyList()
        val words = keywords(subject)
        val joined = if (words.size in 2..3) listOf("#" + words.joinToString("") { ascii(it).replace(Regex("[^A-Za-z0-9]"), "").lowercase() }) else emptyList()
        val own = words.map { tag(it) }
        val fromNiche = niche?.keywords?.map { tag(it) }.orEmpty()
        val discovery = when (network) {
            Network.TIKTOK -> listOf("#pourtoi", "#fyp")
            Network.INSTAGRAM -> listOf("#reels", "#instadaily")
            Network.YOUTUBE, Network.SHORTS -> listOf("#shorts")
            Network.LINKEDIN -> listOf("#conseil")
            else -> emptyList()
        }
        // mix: subject first, then one discovery tag guaranteed a place, then the niche
        val ordered = (joined + own.take(3) + discovery.take(1) + fromNiche + own.drop(3) + discovery.drop(1)).filter { it.length > 2 }.distinct()
        return ordered.take(max)
    }

    private fun hooks(s: String, t: CaptionTone) = when (t) {
        CaptionTone.FUN -> listOf("POV : tu découvres $s 😂", "Personne n'est prêt pour $s 👀", "J'ai testé $s… et voilà ce qui s'est passé", "$s, mais en mode chaos 🤪")
        CaptionTone.PRO -> listOf("$s : l'essentiel en moins d'une minute", "3 choses à savoir sur $s", "Ce que personne ne t'explique sur $s", "$s, expliqué simplement")
        CaptionTone.INSPIRING -> listOf("Et si $s changeait tout ? ✨", "Il y a un an, je n'aurais jamais osé : $s", "Petit rappel : $s, c'est possible pour toi aussi", "$s — un pas après l'autre 🌱")
        CaptionTone.MYSTERY -> listOf("Attends la fin… $s 🤫", "Je ne devrais peut-être pas montrer ça : $s", "Tu n'as jamais vu $s comme ça", "Le secret derrière $s 👁️")
        CaptionTone.CHILL -> listOf("Un moment simple : $s ☕", "$s, tranquille", "Juste $s, rien d'autre 🌙", "Aujourd'hui c'était $s")
    }

    private fun bodies(s: String, t: CaptionTone) = when (t) {
        CaptionTone.FUN -> listOf("Franchement je ne m'attendais pas à ça.", "Dis-moi que je ne suis pas le seul 😅", "Note sur 10 ? Sois honnête.")
        CaptionTone.PRO -> listOf("Enregistre ce post pour le retrouver au bon moment.", "Je t'explique tout étape par étape.", "Le détail qui fait toute la différence est à la fin.")
        CaptionTone.INSPIRING -> listOf("Commence petit, mais commence aujourd'hui.", "Chaque essai compte, même ceux qui ratent.", "Ce n'est pas la perfection qui compte, c'est la régularité.")
        CaptionTone.MYSTERY -> listOf("Regarde bien le détail à la fin.", "Tu as remarqué ce qui change ?", "La suite arrive bientôt…")
        CaptionTone.CHILL -> listOf("Prends ton temps.", "Le genre de moment qu'on oublie trop vite.", "Moins de bruit, plus de vrai.")
    }

    private fun ctas(n: Network, t: CaptionTone) = when (n) {
        Network.TIKTOK, Network.SHORTS -> listOf("Abonne-toi pour la partie 2 👉", "Commente « OUI » si tu veux la suite", "Partage à quelqu'un qui doit voir ça")
        Network.INSTAGRAM -> listOf("Enregistre et partage en story ✨", "Dis-moi en commentaire ce que tu en penses 👇", "Lien dans la bio pour en savoir plus")
        Network.YOUTUBE -> listOf("Abonne-toi et active la cloche 🔔", "Dis-moi en commentaire la prochaine vidéo que tu veux", "Like si ça t'a aidé 👍")
        Network.LINKEDIN -> listOf("Et vous, qu'en pensez-vous ?", "Partagez si cela peut aider quelqu'un de votre réseau.", "Je réponds à toutes les questions en commentaire.")
        Network.X -> listOf("RT si tu es d'accord", "Ton avis ?", "")
        Network.TWITCH -> listOf("En live ce soir, viens !", "Rejoins le live 🎮", "")
        else -> if (t == CaptionTone.PRO) listOf("Partage si ça peut aider.", "Ton avis en commentaire.") else listOf("Partage à tes potes 🔁", "Ton avis en commentaire 👇")
    }

    /**
     * [count] different captions for [subject] on [network] (deterministic for a given [seed]),
     * each cut to the network's caption limit.
     */
    fun generate(subject: String, network: Network, tone: CaptionTone, niche: Niche? = null, seed: Int = 0, count: Int = 3): List<Caption> {
        val s = subject.trim().ifEmpty { "ma nouvelle vidéo" }
        val rnd = java.util.Random(seed.toLong() * 31 + s.hashCode() + network.ordinal * 7 + tone.ordinal)
        val hk = hooks(s, tone).shuffled(rnd); val bd = bodies(s, tone).shuffled(rnd); val ct = ctas(network, tone).shuffled(rnd)
        val tags = hashtags(s, network, niche)
        val short = network.captionLimit <= 300
        return List(count.coerceIn(1, 4)) { i ->
            var c = Caption(hk[i % hk.size], if (short) "" else bd[i % bd.size], ct[i % ct.size], tags)
            // too long for the network: drop the body, then the CTA, then hashtags, then cut the hook
            if (c.text.length > network.captionLimit) c = c.copy(body = "")
            if (c.text.length > network.captionLimit) c = c.copy(cta = "")
            while (c.text.length > network.captionLimit && c.hashtags.isNotEmpty()) c = c.copy(hashtags = c.hashtags.dropLast(1))
            if (c.text.length > network.captionLimit) c = c.copy(hook = c.hook.take(network.captionLimit - 1).trimEnd() + "…")
            c
        }
    }

    /** Problems of a caption for a network, in plain words (empty = all good). */
    fun check(text: String, network: Network): List<String> {
        val out = ArrayList<String>()
        if (text.length > network.captionLimit) out += "Trop long pour ${network.label} : ${text.length} / ${network.captionLimit} caractères"
        val tags = Regex("#[\\p{L}\\p{N}_]+").findAll(text).count()
        if (tags > network.hashtagAdvice.last && network.hashtagAdvice.last > 0) out += "$tags hashtags : ${network.hashtagAdvice.first} à ${network.hashtagAdvice.last} suffisent sur ${network.label}"
        if (network.hashtagAdvice.last == 0 && tags > 0) out += "Les hashtags servent peu sur ${network.label}"
        val firstLine = text.lineSequence().firstOrNull().orEmpty()
        if (firstLine.length > 125 && network in setOf(Network.INSTAGRAM, Network.TIKTOK, Network.FACEBOOK, Network.LINKEDIN)) out += "Première ligne longue : elle est coupée par « … plus » (125 caractères visibles)"
        if (text.isNotBlank() && !text.contains('?') && !Regex("(?i)abonne|commente|partage|enregistre|like|lien|rejoins|dis-moi|avis").containsMatchIn(text)) out += "Pas d'appel à l'action : demande un commentaire, un partage ou un abonnement"
        return out
    }
}
