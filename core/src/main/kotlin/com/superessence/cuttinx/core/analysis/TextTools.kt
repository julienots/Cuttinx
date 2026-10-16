package com.superessence.cuttinx.core.analysis

import java.text.Normalizer
import kotlin.math.ln

/**
 * Local, rule-based text tools. They do not use a language model: results come from keyword
 * extraction and templates, run instantly offline, and are always editable by the user.
 */
object TextTools {
    private val stopFr = setOf("le","la","les","un","une","des","du","de","d","l","et","ou","en","au","aux","a","à","ce","ces","cet","cette","mon","ma","mes","ton","ta","tes","son","sa","ses","notre","nos","votre","vos","leur","leurs","je","tu","il","elle","on","nous","vous","ils","elles","me","te","se","y","que","qui","quoi","dont","où","pour","par","avec","sans","sur","sous","dans","chez","entre","vers","est","sont","été","être","avoir","ai","as","avons","avez","ont","fait","faire","pas","plus","moins","très","trop","tout","tous","toute","toutes","mais","donc","or","ni","car","comme","si","alors","aussi","bien","ça","cela","c","j","n","s","qu","m","t","ne","même","vidéo","video","juste","encore","déjà","ici","là","quand","puis","après","avant","fois","jour","faut","va","vais","était")
    private val stopEn = setOf("the","a","an","and","or","of","to","in","on","at","for","with","without","is","are","was","were","be","been","it","this","that","these","those","i","you","he","she","we","they","my","your","our","their","me","him","her","us","them","so","but","if","then","just","very","really","video","from","by","as","about","into","have","has","had","do","does","did","not","no","yes","get","got")

    fun normalize(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    fun tokens(text: String): List<String> = Regex("[\\p{L}\\p{N}']+").findAll(text).map { it.value.trim('\'') }.filter { it.isNotEmpty() }.toList()

    fun isStop(w: String) = w.length <= 1 || w.lowercase() in stopFr || w.lowercase() in stopEn || normalize(w) in stopFr

    /** Keywords ranked by frequency × length, ties keep first appearance. */
    fun keywords(text: String, max: Int = 6): List<String> {
        val toks = tokens(text)
        val counts = LinkedHashMap<String, Pair<String, Int>>()
        for (t in toks) {
            if (isStop(t) || t.all { it.isDigit() }) continue
            val key = normalize(t).removeSuffix("s")
            val prev = counts[key]
            counts[key] = (prev?.first ?: t) to ((prev?.second ?: 0) + 1)
        }
        return counts.values.sortedByDescending { (w, c) -> c * (1 + ln(w.length.toDouble())) }.take(max).map { it.first }
    }

    private fun cap(s: String) = s.replaceFirstChar { it.uppercase() }

    /** Several title proposals from a free description. */
    fun generateTitles(description: String, count: Int = 8): List<String> {
        val kw = keywords(description, 5)
        if (kw.isEmpty()) return listOf("Mon histoire", "Un moment à part", "Instant capturé")
        val a = cap(kw[0]); val b = kw.getOrNull(1)?.let(::cap); val c = kw.getOrNull(2)?.let(::cap)
        val num = Regex("\\b(\\d{1,4})\\b").find(description)?.value
        val ideas = buildList {
            add(if (b != null) "$a & $b" else a)
            add("$a : le récit")
            add(if (b != null) "$a, $b et moi" else "$a et moi")
            add("Dans les coulisses : $a")
            add("${a.uppercase()}${if (b != null) " • ${b.uppercase()}" else ""}")
            add(if (b != null) "Pourquoi $a change tout pour $b" else "Pourquoi $a change tout")
            add(if (num != null) "$num secondes de $a" else "Une journée $a")
            if (c != null) add("$a, $b, $c")
            add("Le meilleur de $a")
            add("$a — édition spéciale")
            add("Ce que personne ne dit sur $a")
            if (num != null) add("$num choses à savoir sur $a")
        }
        return ideas.distinct().take(count)
    }

    data class Sentence(val text: String, val startUs: Long = 0, val endUs: Long = 0)

    /** Extractive summary: picks the most representative sentences (word-frequency scoring). */
    fun summarize(sentences: List<Sentence>, maxSentences: Int = 3): List<Sentence> {
        if (sentences.size <= maxSentences) return sentences
        val freq = HashMap<String, Int>()
        for (s in sentences) for (t in tokens(s.text)) if (!isStop(t)) freq.merge(normalize(t), 1, Int::plus)
        val maxF = (freq.values.maxOrNull() ?: 1).toDouble()
        val scored = sentences.mapIndexed { i, s ->
            val toks = tokens(s.text).filterNot(::isStop)
            val score = if (toks.isEmpty()) 0.0 else toks.sumOf { (freq[normalize(it)] ?: 0) / maxF } / (1 + ln(toks.size.toDouble())) + if (i == 0) 0.2 else 0.0
            i to score
        }
        return scored.sortedByDescending { it.second }.take(maxSentences).sortedBy { it.first }.map { sentences[it.first] }
    }

    fun splitSentences(text: String): List<String> = text.split(Regex("(?<=[.!?…])\\s+|\\n+")).map { it.trim() }.filter { it.isNotEmpty() }
}
