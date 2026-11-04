package com.superessence.cuttinx.core.smart

/**
 * The "Labo CUTTINX" technologies. Each one is unlocked for [TechVault.UNLOCK_HOURS] hours by
 * watching one full-screen advert (Android) or a sponsor screen (PC).
 */
enum class Tech(val label: String, val pitch: String, val icon: String) {
    KINETIC("Texte cinétique", "Ton texte explose mot par mot, au rythme, avec les mots forts mis en valeur", "Text"),
    ECHO("Traînée temporelle", "Trois échos décalés dans le temps suivent le mouvement, comme une pose longue", "Animation"),
    CLONES("Clones", "Le même plan trois fois à l'écran, décalé dans le temps", "Grid"),
    WALL("Mur de clips", "Ouverture en mosaïque : tous tes plans à l'écran en même temps", "Grid"),
    IMPACT("Impacts sur les coupes", "Flou de vitesse et séparation des couleurs qui frappent à chaque coupe", "Rocket"),
    ZOOM_FLOW("Zoom infini", "Chaque plan plonge dans le suivant, sans coupure visible", "ZoomIn"),
    BEAT_FX("Effets sur le tempo", "Flashs et secousses calés sur chaque temps de la musique", "Music"),
    PACING("Moteur de rythme", "Plans de plus en plus courts (crescendo), de plus en plus longs, ou pulsés", "Equalize"),
    VELOCITY("Velocity", "Rampes de vitesse alternées sur tous les plans, façon edit", "ZoomSlow"),
    REMIX("Remix IA", "Une nouvelle version complète du montage : ordre, look, rythme et transitions", "Idea"),
    TAP_TEMPO("Tempo par marqueurs", "Pose quelques marqueurs sur les temps : CUTTINX trouve le tempo et monte dessus", "Marker"),
    REWIND("Rembobinage VHS", "La vidéo se rembobine à toute vitesse à la fin, plan par plan", "Replay"),
    GLITCH_STORM("Tempête glitch", "Des éclats glitch, datamosh et RVB dispersés sur toute la vidéo", "Effects"),
    CREDITS("Générique déroulant", "Un générique de fin qui défile comme au cinéma", "Captions"),
}

/** Remembers which technologies are unlocked, and until when. The apps plug in their own storage. */
object TechVault {
    const val UNLOCK_HOURS = 24L
    private const val UNLOCK_MS = UNLOCK_HOURS * 3_600_000L

    interface Store { fun get(key: String): Long; fun put(key: String, value: Long) }

    /** In-memory until an app installs a persistent store. */
    var store: Store = object : Store {
        private val m = HashMap<String, Long>()
        override fun get(key: String) = m[key] ?: 0L
        override fun put(key: String, value: Long) { m[key] = value }
    }
    var clock: () -> Long = { System.currentTimeMillis() }

    private fun key(t: Tech) = "tech_" + t.name.lowercase()

    fun unlockedUntil(t: Tech): Long = store.get(key(t))
    fun isUnlocked(t: Tech): Boolean = unlockedUntil(t) > clock()

    /** Unlocks [t] for 24 h from now (or 24 h more when it is still unlocked). */
    fun unlock(t: Tech) {
        val now = clock()
        store.put(key(t), maxOf(now, unlockedUntil(t)) + UNLOCK_MS)
    }

    /** "encore 23 h" / "encore 12 min", or null when locked. */
    fun remaining(t: Tech): String? {
        val ms = unlockedUntil(t) - clock()
        if (ms <= 0) return null
        val h = ms / 3_600_000L
        return if (h >= 1) "encore $h h" else "encore ${(ms / 60_000L).coerceAtLeast(1)} min"
    }
}
