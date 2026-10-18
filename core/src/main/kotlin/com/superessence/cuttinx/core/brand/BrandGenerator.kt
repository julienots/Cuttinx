package com.superessence.cuttinx.core.brand

import com.superessence.cuttinx.core.overlay.OverlayPalette
import com.superessence.cuttinx.core.overlay.OverlayThemes
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@Serializable
enum class Niche(val label: String, val keywords: List<String>) {
    GAMING("Gaming", listOf("gaming", "gamer", "live", "twitch")),
    BEAUTY("Beauté & maquillage", listOf("beauté", "makeup", "skincare", "glow")),
    FASHION("Mode", listOf("mode", "outfit", "style", "ootd")),
    FITNESS("Sport & fitness", listOf("fitness", "sport", "motivation", "workout")),
    FOOD("Cuisine", listOf("recette", "food", "cuisine", "miam")),
    TECH("Tech", listOf("tech", "astuce", "setup", "geek")),
    TRAVEL("Voyage", listOf("voyage", "travel", "aventure", "explore")),
    MUSIC("Musique", listOf("musique", "cover", "beat", "son")),
    HUMOR("Humour", listOf("humour", "drôle", "sketch", "lol")),
    EDUCATION("Éducation", listOf("apprendre", "astuce", "savoir", "tuto")),
    LIFESTYLE("Lifestyle", listOf("lifestyle", "vlog", "quotidien", "routine")),
    ART("Art & dessin", listOf("art", "dessin", "créatif", "illustration")),
    BUSINESS("Business & entrepreneuriat", listOf("business", "entrepreneur", "mindset", "succès")),
    ANIME("Anime & manga", listOf("anime", "manga", "otaku", "japon")),
}

@Serializable
enum class Trait(val label: String) {
    FUN("Fun"), PRO("Professionnel"), CHILL("Chill"), ENERGETIC("Énergique"), MYSTERIOUS("Mystérieux"),
    INSPIRING("Inspirant"), LUXURY("Luxe"), REBEL("Rebelle"), SOFT("Doux"), GEEK("Geek"), AUTHENTIC("Authentique"), BOLD("Audacieux"),
}

@Serializable
enum class Audience(val label: String) { TEENS("Ados"), YOUNG_ADULTS("Jeunes adultes"), ADULTS("Adultes"), PROS("Professionnels"), FAMILIES("Familles"), EVERYONE("Tout le monde") }

/** What the creator tells the generator. */
@Serializable
data class BrandBrief(
    val name: String,
    val niche: Niche,
    val traits: Set<Trait>,
    val audience: Audience = Audience.YOUNG_ADULTS,
    /** Optional favourite hue (0..360), -1 = let the generator choose. */
    val favoriteHue: Int = -1,
    val keywords: String = "",
)

@Serializable
data class BrandColor(val name: String, val role: String, val argb: Long) {
    val hex: String get() = "#%06X".format(argb and 0xFFFFFF)
}

@Serializable
data class FontPairing(val title: String, val body: String, val why: String)

@Serializable
data class ContentPillar(val name: String, val description: String, val ideas: List<String>)

/** Complete identity ("DA") of a creator: everything needed to look consistent everywhere. */
@Serializable
data class BrandKit(
    val brief: BrandBrief,
    val archetype: String,
    val archetypeDescription: String,
    val palette: List<BrandColor>,
    val fonts: FontPairing,
    val taglines: List<String>,
    val bios: Map<String, String>,
    val pillars: List<ContentPillar>,
    val hashtags: List<String>,
    val toneDo: List<String>,
    val toneDont: List<String>,
    val moodboard: List<String>,
    val monogram: String,
    val logoIdea: String,
    val overlayThemeId: String,
    val postingRhythm: String,
    val seed: Int,
) {
    /** Palette ready for the Twitch overlay generator. */
    fun overlayPalette(): OverlayPalette {
        val c = palette.associateBy { it.role }
        return OverlayPalette(
            background = c["Fond"]!!.argb, backgroundAlt = c["Fond secondaire"]!!.argb,
            primary = c["Principale"]!!.argb, secondary = c["Secondaire"]!!.argb, accent = c["Accent"]!!.argb,
            text = c["Texte"]!!.argb, glow = c["Principale"]!!.argb,
        )
    }
}

/**
 * Deterministic brand identity generator: the same brief and seed always give the same kit;
 * "Régénérer" simply increments the seed. No network, no AI service: rules + curated vocabularies.
 */
object BrandGenerator {

    fun generate(brief: BrandBrief, seed: Int = 0): BrandKit {
        val rnd = java.util.Random(brief.name.lowercase().hashCode() * 31L + brief.niche.ordinal * 7L + seed * 1_000_003L)
        val traits = brief.traits.ifEmpty { setOf(Trait.AUTHENTIC) }
        val (arch, archDesc) = archetype(traits)
        val palette = palette(brief, traits, rnd)
        val name = brief.name.trim().ifEmpty { "Créateur" }
        return BrandKit(
            brief = brief,
            archetype = arch,
            archetypeDescription = archDesc,
            palette = palette,
            fonts = fonts(traits, brief.niche, rnd),
            taglines = taglines(name, brief.niche, traits, rnd),
            bios = bios(name, brief, traits, rnd),
            pillars = pillars(brief.niche, rnd),
            hashtags = hashtags(name, brief, rnd),
            toneDo = toneDo(traits),
            toneDont = toneDont(traits),
            moodboard = moodboard(brief.niche, traits, rnd),
            monogram = monogram(name),
            logoIdea = logoIdea(name, traits, rnd),
            overlayThemeId = overlayTheme(brief.niche, traits),
            postingRhythm = rhythm(brief.niche),
            seed = seed,
        )
    }

    // ------------------------------------------------------------------ archetype

    private fun archetype(t: Set<Trait>): Pair<String, String> = when {
        Trait.REBEL in t || Trait.BOLD in t -> "Le Rebelle" to "Tu bouscules les codes et tu assumes un avis tranché : contrastes forts, ton direct, visuels qui claquent."
        Trait.LUXURY in t -> "L'Élégant" to "Tu inspires l'exigence et le raffinement : peu de couleurs, beaucoup d'espace, des détails soignés."
        Trait.INSPIRING in t -> "Le Guide" to "Tu motives et tu fais progresser ta communauté : messages positifs, conseils concrets, visuels lumineux."
        Trait.GEEK in t -> "L'Explorateur geek" to "Tu partages ta passion avec précision et enthousiasme : références, détails techniques, univers riche."
        Trait.FUN in t || Trait.ENERGETIC in t -> "L'Amuseur" to "Tu fais sourire et tu donnes de l'énergie : couleurs vives, rythme rapide, autodérision."
        Trait.MYSTERIOUS in t -> "Le Mystique" to "Tu intrigues : ambiances sombres, teasing, révélations progressives."
        Trait.SOFT in t || Trait.CHILL in t -> "Le Réconfortant" to "Ta chaîne est un refuge : tons doux, voix posée, ambiance cosy."
        Trait.PRO in t -> "L'Expert" to "On vient chez toi pour une info fiable : structure claire, ton posé, visuels nets."
        else -> "L'Authentique" to "Tu montres les coulisses sans filtre : spontanéité, proximité, visuels naturels."
    }

    // ------------------------------------------------------------------ colours (HSL harmony)

    private fun hsl(h: Double, s: Double, l: Double): Long {
        val c = (1 - abs(2 * l - 1)) * s
        val hp = ((h % 360 + 360) % 360) / 60
        val x = c * (1 - abs(hp % 2 - 1))
        val (r1, g1, b1) = when (hp.toInt()) { 0 -> Triple(c, x, 0.0); 1 -> Triple(x, c, 0.0); 2 -> Triple(0.0, c, x); 3 -> Triple(0.0, x, c); 4 -> Triple(x, 0.0, c); else -> Triple(c, 0.0, x) }
        val m = l - c / 2
        fun ch(v: Double) = ((v + m).coerceIn(0.0, 1.0) * 255).toLong()
        return 0xFF000000L or (ch(r1) shl 16) or (ch(g1) shl 8) or ch(b1)
    }

    private fun nicheHue(n: Niche) = when (n) {
        Niche.GAMING -> 265.0; Niche.BEAUTY -> 335.0; Niche.FASHION -> 20.0; Niche.FITNESS -> 140.0; Niche.FOOD -> 28.0
        Niche.TECH -> 200.0; Niche.TRAVEL -> 185.0; Niche.MUSIC -> 290.0; Niche.HUMOR -> 48.0; Niche.EDUCATION -> 215.0
        Niche.LIFESTYLE -> 35.0; Niche.ART -> 310.0; Niche.BUSINESS -> 222.0; Niche.ANIME -> 350.0
    }

    private fun palette(b: BrandBrief, t: Set<Trait>, rnd: java.util.Random): List<BrandColor> {
        val hue = if (b.favoriteHue in 0..360) b.favoriteHue.toDouble() else nicheHue(b.niche) + (rnd.nextDouble() - 0.5) * 40
        val dark = Trait.MYSTERIOUS in t || Trait.GEEK in t || Trait.REBEL in t || b.niche == Niche.GAMING
        val soft = Trait.SOFT in t || Trait.CHILL in t
        val lux = Trait.LUXURY in t
        val sat = when { lux -> 0.45; soft -> 0.55; Trait.ENERGETIC in t || Trait.FUN in t || Trait.BOLD in t -> 0.9; else -> 0.72 }
        // harmony: complementary for bold, analogous for soft, split-complementary otherwise
        val second = hue + when { soft -> 35.0; Trait.BOLD in t || Trait.ENERGETIC in t -> 180.0; else -> 150.0 }
        val accent = hue + when { lux -> 40.0; soft -> -40.0; else -> 210.0 } + (rnd.nextDouble() - 0.5) * 20
        val bg = if (dark) hsl(hue, 0.35, 0.08) else hsl(hue, if (lux) 0.18 else 0.35, if (soft) 0.94 else 0.97)
        val bg2 = if (dark) hsl(hue, 0.3, 0.15) else hsl(hue, 0.3, if (soft) 0.86 else 0.9)
        val text = if (dark) hsl(hue, 0.15, 0.96) else hsl(hue, 0.35, 0.14)
        val prim = hsl(hue, sat, if (dark) 0.6 else 0.5)
        val sec = hsl(second, sat * 0.85, if (dark) 0.62 else 0.55)
        val acc = if (lux) hsl(43.0, 0.7, 0.6) else hsl(accent, min(1.0, sat + 0.05), if (dark) 0.65 else 0.55)
        return listOf(
            BrandColor(colorName(hue, dark, "Principale"), "Principale", prim),
            BrandColor(colorName(second, dark, "Secondaire"), "Secondaire", sec),
            BrandColor(if (lux) "Or doux" else colorName(accent, dark, "Accent"), "Accent", acc),
            BrandColor(if (dark) "Nuit profonde" else "Blanc cassé", "Fond", bg),
            BrandColor(if (dark) "Ardoise" else "Sable clair", "Fond secondaire", bg2),
            BrandColor(if (dark) "Blanc lumière" else "Encre", "Texte", text),
        )
    }

    private fun colorName(h0: Double, dark: Boolean, role: String): String {
        val h = (h0 % 360 + 360) % 360
        val base = when {
            h < 15 || h >= 345 -> "Rouge cerise"; h < 35 -> "Corail"; h < 50 -> "Orange soleil"; h < 70 -> "Jaune citron"
            h < 100 -> "Vert lime"; h < 160 -> "Vert émeraude"; h < 190 -> "Turquoise"; h < 215 -> "Bleu ciel"; h < 245 -> "Bleu royal"
            h < 275 -> "Violet électrique"; h < 300 -> "Améthyste"; h < 325 -> "Magenta"; else -> "Rose framboise"
        }
        return if (dark && role == "Principale") "$base néon" else base
    }

    // ------------------------------------------------------------------ typography

    private fun fonts(t: Set<Trait>, n: Niche, rnd: java.util.Random): FontPairing = when {
        Trait.LUXURY in t -> FontPairing("Playfair Display", "Montserrat", "Un sérif élégant pour les titres et une linéale propre pour lire facilement.")
        Trait.GEEK in t || n == Niche.TECH -> listOf(
            FontPairing("Righteous", "Montserrat", "Rondeurs futuristes pour les titres, lisibilité parfaite pour le texte."),
            FontPairing("Press Start 2P", "Montserrat", "Le clin d'œil pixel pour les titres courts, une linéale pour le reste."),
        )[rnd.nextInt(2)]
        Trait.REBEL in t || Trait.BOLD in t -> FontPairing("Anton", "Oswald", "Des capitales serrées qui frappent fort, parfaites sur les miniatures.")
        Trait.FUN in t || Trait.ENERGETIC in t || n == Niche.HUMOR -> listOf(
            FontPairing("Bangers", "Fredoka", "Un style BD plein d'énergie et une ronde amicale."),
            FontPairing("Fredoka", "Montserrat", "Rond, joyeux et toujours lisible en petit."),
        )[rnd.nextInt(2)]
        Trait.SOFT in t || Trait.CHILL in t -> listOf(
            FontPairing("Pacifico", "Fredoka", "Une script décontractée pour les titres, une ronde douce pour le texte."),
            FontPairing("Caveat", "Montserrat", "Effet manuscrit chaleureux, texte net à côté."),
        )[rnd.nextInt(2)]
        Trait.PRO in t || n == Niche.BUSINESS || n == Niche.EDUCATION -> FontPairing("Bebas Neue", "Montserrat", "Titres nets et modernes, corps de texte très lisible.")
        else -> FontPairing("Montserrat ExtraBold", "Montserrat", "Une seule famille, deux graisses : simple, cohérent, efficace.")
    }

    // ------------------------------------------------------------------ texts

    private fun pick(rnd: java.util.Random, l: List<String>, n: Int): List<String> = l.shuffled(rnd).take(n)

    private fun taglines(name: String, n: Niche, t: Set<Trait>, rnd: java.util.Random): List<String> {
        val byNiche = when (n) {
            Niche.GAMING -> listOf("Du skill, du fun et zéro rage quit (presque)", "On lance la partie ?", "Chaque live est un nouveau niveau", "GG ou rien")
            Niche.BEAUTY -> listOf("Ta dose de glow quotidienne", "La beauté sans prise de tête", "Le maquillage, mais en mieux", "Révèle ton éclat")
            Niche.FASHION -> listOf("Le style, c'est toi", "Des looks qui racontent une histoire", "Ose le look", "Mode accessible, style inoubliable")
            Niche.FITNESS -> listOf("Plus fort chaque jour", "Ton coach de poche", "Sueur, sourire, résultats", "On ne lâche rien")
            Niche.FOOD -> listOf("Des recettes qui donnent faim", "Cuisiner simple, manger heureux", "À table !", "Le goût avant tout")
            Niche.TECH -> listOf("La tech expliquée simplement", "Les meilleures astuces, sans jargon", "Upgrade ta vie", "Ton setup, en mieux")
            Niche.TRAVEL -> listOf("Le monde en grand angle", "Prochaine destination ?", "Partir pour mieux raconter", "Explore plus, rêve plus")
            Niche.MUSIC -> listOf("Le son qui te ressemble", "Monte le volume", "Des notes et des émotions", "Play, repeat")
            Niche.HUMOR -> listOf("Rire tous les jours, c'est la base", "Ta pause fou rire", "Sérieux s'abstenir", "On rigole ?")
            Niche.EDUCATION -> listOf("Apprendre n'a jamais été aussi simple", "1 minute, 1 savoir", "Deviens incollable", "Le savoir qui change tout")
            Niche.LIFESTYLE -> listOf("Ma vie, sans filtre (ou presque)", "Des petits moments qui comptent", "Inspire ton quotidien", "Viens, je t'emmène")
            Niche.ART -> listOf("Créer chaque jour", "Du blanc à l'œuvre", "L'art de l'imaginaire", "Chaque trait compte")
            Niche.BUSINESS -> listOf("Construis le futur qui te ressemble", "Mindset, stratégie, action", "Entreprendre sans bullshit", "Passe au niveau supérieur")
            Niche.ANIME -> listOf("Ta dose d'anime quotidienne", "Otaku et fier de l'être", "Les meilleures pépites manga", "Arc après arc")
        }
        val personal = listOf("$name, en vrai.", "Bienvenue dans l'univers de $name", "$name — ${if (Trait.FUN in t) "mode fun activé" else "l'essentiel, avec style"}")
        return pick(rnd, byNiche, 3) + personal.shuffled(rnd).first()
    }

    private fun emojiFor(n: Niche) = when (n) {
        Niche.GAMING -> "🎮"; Niche.BEAUTY -> "💄"; Niche.FASHION -> "👗"; Niche.FITNESS -> "💪"; Niche.FOOD -> "🍳"; Niche.TECH -> "💻"
        Niche.TRAVEL -> "✈️"; Niche.MUSIC -> "🎵"; Niche.HUMOR -> "😂"; Niche.EDUCATION -> "📚"; Niche.LIFESTYLE -> "☕"; Niche.ART -> "🎨"
        Niche.BUSINESS -> "📈"; Niche.ANIME -> "🌸"
    }

    private fun bios(name: String, b: BrandBrief, t: Set<Trait>, rnd: java.util.Random): Map<String, String> {
        val e = emojiFor(b.niche)
        val vibe = t.take(2).joinToString(" & ") { it.label.lowercase() }
        val who = b.audience.label.lowercase()
        return linkedMapOf(
            "TikTok" to "$e ${b.niche.label} $vibe\n✨ Nouvelle vidéo chaque jour\n👇 Abonne-toi pour la suite",
            "Instagram" to "$name $e\n${b.niche.label} · $vibe\nPour les $who qui veulent plus\n📩 Collab en DM",
            "YouTube" to "Bienvenue sur la chaîne de $name ! Ici on parle ${b.niche.label.lowercase()} avec un ton $vibe. Nouvelles vidéos chaque semaine — abonne-toi et active la cloche 🔔",
            "Twitch" to "$e Lives ${b.niche.label.lowercase()} $vibe · Communauté bienveillante · Viens dire bonjour dans le chat !",
        )
    }

    private fun pillars(n: Niche, rnd: java.util.Random): List<ContentPillar> {
        val common = listOf(
            ContentPillar("Coulisses", "Montre comment tu travailles : ça crée de la proximité.", listOf("Une journée avec moi", "Mon setup / mon matériel", "Les ratés du tournage")),
            ContentPillar("Communauté", "Fais participer ton public.", listOf("Je réponds à vos questions", "Je teste vos idées", "Top de vos meilleurs commentaires")),
        )
        val specific = when (n) {
            Niche.GAMING -> listOf(ContentPillar("Moments forts", "Les meilleurs clips de tes lives.", listOf("Top 5 des clutchs de la semaine", "Mes pires fails", "Speedrun challenge")),
                ContentPillar("Guides", "Aide ta communauté à progresser.", listOf("3 astuces pour débuter", "Tier list", "Build optimisé")))
            Niche.BEAUTY, Niche.FASHION -> listOf(ContentPillar("Tutos", "Des looks reproductibles pas à pas.", listOf("Look en 5 minutes", "Avant / après", "Dupes pas chers")),
                ContentPillar("Tendances", "Ton avis sur les nouveautés.", listOf("Je teste la tendance du moment", "Haul du mois", "Top / flop")))
            Niche.FITNESS -> listOf(ContentPillar("Programmes", "Des séances à suivre.", listOf("10 min abdos", "Routine sans matériel", "Défi 30 jours")),
                ContentPillar("Transformation", "Montre la progression.", listOf("Mon évolution", "Ce que je mange en une journée", "Mes erreurs de débutant")))
            Niche.FOOD -> listOf(ContentPillar("Recettes express", "Courtes et efficaces.", listOf("Recette en 60 secondes", "Avec 3 ingrédients", "Version healthy")),
                ContentPillar("Découvertes", "Restaurants et produits.", listOf("Je teste le plat viral", "Street food", "Note sur 10")))
            else -> listOf(ContentPillar("Valeur", "Apprends quelque chose d'utile à ton public.", listOf("3 choses que j'aurais aimé savoir", "Mythe ou réalité", "Le guide du débutant")),
                ContentPillar("Divertissement", "Le contenu qui se partage.", listOf("Réaction à une tendance", "Défi", "POV / sketch")))
        }
        return (specific + common.shuffled(rnd)).take(4)
    }

    private fun slug(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").replace(Regex("[^A-Za-z0-9]"), "")

    private fun hashtags(name: String, b: BrandBrief, rnd: java.util.Random): List<String> {
        val own = "#" + slug(name).ifEmpty { "MaChaine" }
        val extra = b.keywords.split(',', ' ', ';').map { slug(it) }.filter { it.length > 2 }.map { "#$it" }
        val niche = b.niche.keywords.map { "#" + slug(it) }
        val generic = listOf("#fyp", "#pourtoi", "#viral", "#createur", "#contentcreator")
        return (listOf(own) + extra + niche + generic.shuffled(rnd).take(2)).distinct().take(10)
    }

    private fun toneDo(t: Set<Trait>) = buildList {
        if (Trait.FUN in t || Trait.ENERGETIC in t) add("Commence fort : une phrase choc dans les 2 premières secondes")
        if (Trait.PRO in t || Trait.INSPIRING in t) add("Donne un conseil concret et actionnable dans chaque vidéo")
        if (Trait.SOFT in t || Trait.CHILL in t) add("Parle comme à un ami, voix posée, rythme calme")
        if (Trait.AUTHENTIC in t) add("Montre les coulisses et assume les imperfections")
        if (Trait.GEEK in t) add("Glisse des références que ta communauté adore")
        if (Trait.LUXURY in t) add("Peu de mots, des visuels très soignés")
        if (Trait.REBEL in t || Trait.BOLD in t) add("Donne ton avis franchement, sans tiédeur")
        if (Trait.MYSTERIOUS in t) add("Tease avant de révéler : crée l'attente")
        add("Réponds aux commentaires dans la première heure")
        add("Garde toujours les mêmes couleurs et polices sur tes miniatures")
    }

    private fun toneDont(t: Set<Trait>) = buildList {
        add("Changer de style visuel à chaque vidéo")
        add("Copier une tendance sans y mettre ta touche")
        if (Trait.PRO in t || Trait.LUXURY in t) add("Abuser des emojis et des majuscules")
        if (Trait.SOFT in t || Trait.CHILL in t) add("Crier ou surjouer")
        if (Trait.FUN in t || Trait.ENERGETIC in t) add("Les intros trop longues")
        add("Promettre dans le titre ce que la vidéo ne montre pas")
    }

    private fun moodboard(n: Niche, t: Set<Trait>, rnd: java.util.Random): List<String> {
        val pool = buildList {
            if (Trait.LUXURY in t) addAll(listOf("marbre", "doré", "lumière naturelle", "minimalisme"))
            if (Trait.SOFT in t || Trait.CHILL in t) addAll(listOf("lumière tamisée", "pastels", "plaids et bougies", "grain photo"))
            if (Trait.GEEK in t) addAll(listOf("néons RGB", "pixels", "circuits", "figurines"))
            if (Trait.ENERGETIC in t || Trait.FUN in t) addAll(listOf("couleurs saturées", "stickers", "zooms rapides", "confettis"))
            if (Trait.MYSTERIOUS in t) addAll(listOf("clair-obscur", "fumée", "silhouettes", "noir et violet"))
            if (Trait.REBEL in t || Trait.BOLD in t) addAll(listOf("contraste fort", "typo XXL", "collage", "street"))
            addAll(when (n) {
                Niche.TRAVEL -> listOf("grands paysages", "golden hour", "drone"); Niche.FOOD -> listOf("vue de dessus", "bois et lin", "vapeur")
                Niche.ANIME -> listOf("cel-shading", "sakura", "onomatopées"); Niche.FITNESS -> listOf("sueur", "contre-jour", "chrono")
                else -> listOf("cohérence des couleurs", "visages expressifs", "texte lisible")
            })
        }
        return pool.distinct().shuffled(rnd).take(6)
    }

    fun monogram(name: String): String {
        val words = name.trim().split(Regex("[\\s_\\-.]+")).filter { it.isNotEmpty() }
        val m = when {
            words.size >= 2 -> "${words[0].first()}${words[1].first()}"
            words.isNotEmpty() -> words[0].take(2)
            else -> "CX"
        }
        return m.uppercase()
    }

    private fun logoIdea(name: String, t: Set<Trait>, rnd: java.util.Random): String {
        val shape = when {
            Trait.GEEK in t -> "un hexagone"; Trait.SOFT in t -> "un cercle aux bords doux"; Trait.LUXURY in t -> "un fin cadre losange"
            Trait.REBEL in t || Trait.BOLD in t -> "un écusson incliné"; else -> listOf("un cercle", "un carré arrondi", "une bulle de dialogue").random(rnd.asKotlin())
        }
        return "Le monogramme « ${monogram(name)} » dans $shape, couleur principale sur fond sombre — lisible même en 48 px (photo de profil)."
    }

    private fun java.util.Random.asKotlin() = kotlin.random.Random(nextLong())

    private fun overlayTheme(n: Niche, t: Set<Trait>) = when {
        n == Niche.ANIME -> "japan"
        Trait.GEEK in t -> "cyber"
        Trait.LUXURY in t -> "aesthetic"
        Trait.SOFT in t && (n == Niche.BEAUTY || n == Niche.FASHION) -> "kawaii"
        Trait.SOFT in t || Trait.CHILL in t -> "cozy"
        Trait.MYSTERIOUS in t -> "galaxy"
        Trait.FUN in t || Trait.ENERGETIC in t -> "comics"
        n == Niche.GAMING -> "esport"
        else -> "minimal"
    }.let { id -> OverlayThemes[id].id }

    private fun rhythm(n: Niche) = when (n) {
        Niche.GAMING -> "3 lives par semaine aux mêmes horaires + 3 clips courts (TikTok/Shorts) tirés des lives"
        Niche.EDUCATION, Niche.BUSINESS -> "2 vidéos longues par mois + 4 formats courts par semaine"
        else -> "1 format court par jour en semaine + 1 vidéo plus longue le week-end"
    }

    /** Contrast ratio (WCAG) between two colours, used to keep the generated text readable. */
    fun contrast(a: Long, b: Long): Double {
        fun lum(c: Long): Double {
            fun ch(v: Long): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4) }
            return 0.2126 * ch((c shr 16) and 0xFF) + 0.7152 * ch((c shr 8) and 0xFF) + 0.0722 * ch(c and 0xFF)
        }
        val l1 = lum(a); val l2 = lum(b)
        return (max(l1, l2) + 0.05) / (min(l1, l2) + 0.05)
    }
}
