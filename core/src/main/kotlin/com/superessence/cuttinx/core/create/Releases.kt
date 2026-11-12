package com.superessence.cuttinx.core.create

/** What each version brings, shown in « Quoi de neuf » (newest first). */
object Releases {
    class Entry(val version: String, val title: String, val items: List<Pair<String, String>>)

    val all = listOf(
        Entry("3.2", "Le réalisateur autonome", listOf(
            "Réalisateur autonome (Plus)" to "CUTTINX regarde toutes tes prises, écoute ta musique et monte le film entier : accroche, montée, climax sur le drop, final, coupes sur les temps",
            "4 styles" to "Viral, Cinéma, Hype ou Récit, de 15 s à 3 min, avec le compte rendu de chaque décision",
            "Stabilisation IA (Plus)" to "Le tremblement de la caméra est mesuré image par image et compensé, les panoramiques voulus sont gardés",
            "Raccords mouvement (Plus)" to "Flou de vitesse et panoramique filé dans le sens exact du mouvement de la caméra à chaque coupe",
        )),
        Entry("3.1", "Prépare, publie, fais le buzz", listOf(
            "Studio GIF" to "Une vidéo devient un GIF animé : boomerang, inversé, ralenti ou accéléré, légende façon mème, partage direct",
            "Planning de publication" to "Ta semaine sur TikTok, Instagram, YouTube… les meilleurs moments pour publier et un rappel à l'heure",
            "Légendes et hashtags" to "Trois légendes au choix selon le ton, hashtags adaptés, vérification des limites de chaque réseau",
            "Suivi" to "Publications à venir, en retard, série de semaines publiées",
        )),
        Entry("3.0", "Bien plus que la vidéo", listOf(
            "Studio Photo" to "Filtres en direct, réglages pro, recadrage aux formats des réseaux, amélioration automatique (Plus)",
            "Collage et carrousel" to "Grilles, mosaïques, une grande photo, bandes ; carrousel Instagram sans raccord visible",
            "Studio Audio" to "Couper, fondus, volume aux normes (-14 LUFS), voix radio, débruitage studio (Plus)",
            "Nouvel accueil" to "Version mise en avant, barre de navigation, accès direct à tous les studios",
        )),
        Entry("2.7", "Filme, monte, publie", listOf(
            "Caméra CUTTINX" to "4K stabilisé, prompteur, prises directement dans le montage, coupe des blancs (Plus)",
            "Publication directe" to "TikTok, Instagram, YouTube, Snapchat, WhatsApp… en un geste",
        )),
        Entry("2.6", "Des outils en un geste", listOf(
            "Barre d'outils" to "Zoom sur les coupes, transitions, fondus, accroche, boucle… sans menu",
            "6 outils Plus" to "Montage sur la musique, coupe des blancs, best-of, égalisation des plans, vertical intelligent, son studio",
        )),
        Entry("2.5", "Filtres en image", listOf(
            "Aperçus" to "Chaque filtre et chaque effet montré sur ton plan",
            "Nouveautés" to "22 filtres, 12 effets, 8 modèles de montage",
        )),
    )
    val current get() = all.first()
}
