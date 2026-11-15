package com.superessence.cuttinx.core.smart

/**
 * The "Labo CUTTINX" technologies. Each one is unlocked for [TechVault.UNLOCK_HOURS] hours by
 * watching one full-screen advert (Android) or a sponsor screen (PC). The [plus] ones are only
 * for CUTTINX Plus subscribers, who also get every other technology for good (see [Plus]).
 */
enum class Tech(val label: String, val pitch: String, val icon: String, val plus: Boolean = false,
    /** Runs a whole analysis of the media (in the editor's toolbar), not an action of the list. */
    val pipeline: Boolean = false) {
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

    // ---------------------------------------------------------------- CUTTINX Plus exclusives
    FRAME_LOCK("Précision à l'image", "Chaque coupe, marqueur et image clé calé à l'image près ; les micro-trous et chevauchements disparaissent", "Magnet", true),
    BEAT_LOCK("Synchro au temps près", "Coupes posées sur les temps à l'image près, avec impact sur les temps forts de chaque mesure", "Music", true),
    CINE_GRADE("Étalonnage cinéma", "Étalonnage de précision en couches : exposition, contraste filmique, halation, grain fin, format large", "Star", true),
    MASTERING("Mastering broadcast", "Chaîne son pro sur chaque piste : nettoyage, de-essing, compression, ducking et limiteur à -1 dB", "Volume", true),
    RACK_FOCUS("Mise au point cinéma", "La zone nette glisse dans l'image comme un pointeur de cinéma, avec bokeh doux", "ZoomIn", true),
    SMOOTH_SLOWMO("Super ralenti lissé", "Rampe de vitesse en courbe vers 0,25×, flou de mouvement dosé et stabilisation", "ZoomSlow", true),
    PARALLAX("Caméra virtuelle", "Mouvements de caméra courbés sur les photos : travelling, plongée et rotation douce, chaque plan différent", "Image", true),
    VOLUMETRIC("Lumière volumétrique", "Rayons de lumière, halo et éclat qui respirent sur toute la vidéo", "Idea", true),

    // ---------------------------------------------------------------- Plus: one-gesture tools that analyse the media
    BEAT_EDIT("Montage sur la musique", "CUTTINX écoute ta musique, trouve chaque temps et coupe tes plans dessus, à l'image près", "Music", true, true),
    JUMP_CUT("Coupe des blancs", "Tous les silences de toutes tes prises retirés d'un coup, avec un zoom alterné sur chaque coupe", "TrimAfter", true, true),
    BEST_OF("Best-of automatique", "Analyse l'image et le son de toutes tes vidéos et ne garde que les meilleurs moments", "Star", true, true),
    SHOT_MATCH("Égalisation des plans", "Mesure la couleur et la lumière de chaque plan et les corrige pour qu'ils se ressemblent", "Effects", true, true),
    SMART_VERTICAL("Vertical intelligent", "Passe en 9:16 et cadre chaque plan sur son sujet, suivi tout du long", "Fit", true, true),
    STUDIO_SOUND("Son studio", "Volume mesuré et réglé à -14 LUFS sur chaque piste, puis chaîne son broadcast complète", "Volume", true, true),

    // ---------------------------------------------------------------- 3.2: the autonomous editing engine
    HYPER_DIRECTOR("Réalisateur autonome", "CUTTINX regarde toutes tes prises et écoute ta musique, écrit la structure (accroche, montée, climax sur le drop, final) et monte le film entier : coupes sur les temps, meilleurs moments, couleurs, ralentis, impacts, stabilisation, recadrage", "Star", true, true),
    STABILIZE("Stabilisation IA", "Mesure le mouvement de la caméra image par image, lisse la trajectoire et compense le tremblement avec un léger zoom", "CenterFocus", true, true),
    MOTION_MATCH("Raccords mouvement", "À chaque coupe où la caméra bouge, un flou de vitesse et un panoramique filé dans le sens exact du mouvement", "SwapHoriz", true, true),
}

/** Remembers which technologies are unlocked, and until when. The apps plug in their own storage. */
object TechVault {
    const val UNLOCK_HOURS = 24L
    private const val UNLOCK_MS = UNLOCK_HOURS * 3_600_000L

    interface Store {
        fun get(key: String): Long
        fun put(key: String, value: Long)
        fun getText(key: String): String? = null
        fun putText(key: String, value: String) {}
    }

    /** In-memory until an app installs a persistent store. */
    var store: Store = object : Store {
        private val m = HashMap<String, Long>()
        private val t = HashMap<String, String>()
        override fun get(key: String) = m[key] ?: 0L
        override fun put(key: String, value: Long) { m[key] = value }
        override fun getText(key: String) = t[key]
        override fun putText(key: String, value: String) { t[key] = value }
    }
    var clock: () -> Long = { System.currentTimeMillis() }

    private fun key(t: Tech) = "tech_" + t.name.lowercase()

    fun unlockedUntil(t: Tech): Long = store.get(key(t))
    /** Plus subscribers have everything; the others the free technologies they unlocked with an advert. */
    fun isUnlocked(t: Tech): Boolean = Plus.active || (!t.plus && unlockedUntil(t) > clock())

    /** Unlocks [t] for 24 h from now (or 24 h more when it is still unlocked). Plus technologies cannot be unlocked this way. */
    fun unlock(t: Tech) {
        if (t.plus) return
        val now = clock()
        store.put(key(t), maxOf(now, unlockedUntil(t)) + UNLOCK_MS)
    }

    /** "encore 23 h" / "encore 12 min", or null when locked. */
    fun remaining(t: Tech): String? {
        if (Plus.active) return "Plus"
        val ms = unlockedUntil(t) - clock()
        if (ms <= 0) return null
        val h = ms / 3_600_000L
        return if (h >= 1) "encore $h h" else "encore ${(ms / 60_000L).coerceAtLeast(1)} min"
    }
}
