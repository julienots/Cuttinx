# CUTTINX PRO — SUPERESSENCE CREATIVE STUDIO

> **Édition professionnelle** pour créateurs de contenu et influenceurs.

Éditeur vidéo multipiste **gratuit, sans abonnement, sans filigrane, sans crédits** pour Android
(Kotlin, Jetpack Compose, AndroidX Media3). Toutes les fonctions, IA comprise, tournent sur le
téléphone. Seuls les modèles de reconnaissance vocale (~40 Mo, Apache 2.0) se téléchargent une
fois, sur demande.

- **Montage** : pistes vidéo/incrustation, audio (musique, voix off, ambiance, bruitages), texte,
  sous-titres et calques d'effets ; diviser, raccourcir/étendre, déplacer (y compris d'une piste à
  l'autre), magnétisme, zoom de timeline, marqueurs, groupes, sélection multiple, copier/coller,
  fusion de segments, verrouillage/masquage/muet de piste, piste principale magnétique,
  annuler/rétablir illimité avec historique. Non destructif : les fichiers sources ne sont jamais modifiés.
- **Vidéo** : vitesse 0,1×–100×, courbes de vitesse progressives, position/échelle/rotation/opacité/
  point d'ancrage, miroirs, recadrage, adaptation/remplissage, formats 9:16, 16:9, 1:1, 4:5, 21:9, 4:3, personnalisé.
- **Images clés** sur position, échelle, rotation, opacité, volume, balance, paramètres d'effet et masques,
  avec interpolations linéaire, douce, accélérée, ralentie, maintien et Bézier (éditeur de courbe).
- **Texte** : 20 polices (dont 15 libres OFL/Apache intégrées) + import TTF/OTF, couleur, dégradé,
  contour, ombre, fond, espacement, interligne, 12 animations d'entrée/sortie, 13 modèles de titres.
- **Sous-titres** : manuels, import/export SRT et WebVTT, recalage, automatiques (Vosk, 6 langues).
- **Effets** : ~45 effets GPU originaux (réglages colorimétriques complets, flou, netteté, vignettage,
  grain, glitch, VHS, CRT, aberration chromatique, lueur, fuite de lumière, distorsions, particules,
  effets de vitesse…), 15 filtres prêts, préréglages utilisateur (enregistrer, renommer, dupliquer,
  importer/exporter JSON). 17 transitions réglables.
- **Audio** : volume, fondus, balance, EQ 8 bandes, compresseur, limiteur, porte de bruit,
  réverbération, écho, de-esser, largeur stéréo, réduction de bruit spectrale, 7 effets de voix,
  normalisation BS.1770 (LUFS), détection des crêtes/saturations, mixage par piste, voix off.
- **Compositing** : incrustation fond vert/bleu (tolérance, douceur, suppression des franges),
  masques animables (rectangle, ellipse, linéaire, bande) avec contour progressif, opacité.
- **CUTTINX AI LAB** (local) : sous-titres automatiques, silences, changements de scène, moments forts,
  points de coupe, rythme/BPM et synchronisation des coupes, réduction de bruit, séparation
  voix/musique (spectrale), qualité audio, recadrage automatique, suivi de sujet, détourage d'image,
  titres, résumé, suggestions de montage, organisation des médias, montage automatique modifiable.
- **Animations (nouveau)** : 25 animations d'entrée/sortie pour tout élément (vidéo, image, autocollant,
  texte : zoom, tourbillon, pop, rebond, élastique, chute, glissements, fouet, roulade, retournements 3D,
  étirements, secousse, glitch, néon…), 15 animations en boucle (pulsation, battement de cœur, zoom choc
  calé sur le tempo, Ken Burns, flottement, orbite, gelée, tremblement caméra…), 8 combos prêts
  (Hook TikTok, Cinématique, Énergie…) et 24 animations de texte (dont karaoké, glitch, néon, élastique).
- **Outils pro (nouveau)** : accroche d'ouverture, barre de progression, filigrane @compte, sous-titres
  style créateur, zoom sur les temps de la musique, animation automatique du montage, transitions
  dynamiques automatiques, Ken Burns sur les photos, guides de cadrage (tiers, zones TikTok/Reels/Shorts,
  titre sûr). 10 nouvelles transitions (tourbillon zoom, zoom flou, rebond, chute, swipe, poussée zoom,
  glissement glitch, flash zoom, pivot, secousse) et 13 nouveaux effets GPU (couleur isolée, tilt-shift,
  contours néon, secousse RVB, flash rythmé, lignes de balayage, dessin animé, brume de chaleur, écran
  triple, prisme, look film, rembobinage VHS, projecteur).
- **Pack créateur (nouveau)** : 29 effets GPU supplémentaires (pluie, flammes, bokeh, cœurs
  flottants, reflet d'objectif, datamosh, écran fissuré, onde de choc, tunnel, peinture à l'huile,
  aquarelle, mosaïque hexagonale, écran x4, caméra de surveillance, chaud vintage, froid cinéma…),
  37 transitions (dont cœur, étoile, horloge, losange, stores, portes, damier, roue, pixelisation,
  ondulation), zoom lent avant / arrière et panoramiques, 47 bruitages et jingles originaux (cymbale
  inversée, bass drop, explosion, ba dum tss, disque rayé, sons 8-bit, jingles d'intro / fin…),
  20 effets de voix (hélium, monstre, alien, fantôme, sous l'eau, talkie-walkie…), réducteur de bruit
  de fond et « voix studio » en un geste, tutoriel pas à pas et guide intégré avec recherche.
- **Lecteur fiable** : les calques qui sortent du cadre (glissements, transitions, animations) ne bloquent
  plus la lecture ; en cas d'échec GPU propre à un téléphone, l'aperçu se reconstruit automatiquement
  (mode simplifié) et un bouton « Réessayer » relance la lecture.
- **Export** : MP4 H.264 / HEVC / AV1 (selon les encodeurs réels du téléphone), 480p → 4K,
  24–60 i/s, débit et qualité réglables, progression, annulation, mesure réelle du fichier produit.
- **Projets** : autosave, 5 versions de secours, récupération après crash, duplication, archivage,
  export/import `.cuttinx` (avec ou sans médias), détection et remplacement des médias manquants.

## Version PC (Windows) — Twitch Studio + Identité & DA

Le module `desktop` (Compose Multiplatform, même cœur Kotlin que l'appli Android) fournit :

- **Twitch Studio** : 31 thèmes originaux (armure high-tech, comics, justicier nocturne, arcade 8-bit,
  synthwave, cyber, anime shōnen, manga, Japon traditionnel, Tokyo, mignon rose, pastel, aesthetic,
  vaporwave, cozy, esport, minimal, fantasy, galaxie, océan, forêt, printemps, été, automne, hiver,
  Halloween, Noël, Saint-Valentin, Nouvel An…) ; scènes animées (le live va commencer avec compte à
  rebours, je reviens, pause, fin), cadres jeu / discussion / écran / caméra / chat, alerte de follow,
  12 transitions stinger (toile d'araignée, tempête de sakura, explosion BD, hexagones, portail…).
  Export d'un **pack OBS** prêt à l'emploi (HTML + PNG animés transparents, guide d'installation).
- **Identité & DA** : génère l'univers d'un créateur (archétype, palette, polices, slogans, bios
  TikTok / Instagram / YouTube / Twitch, piliers de contenu, hashtags, ton, moodboard, logo), exporte
  une planche PNG et un guide, et applique la palette aux overlays.

Installateurs `.exe` / `.msi` produits par la CI (artefact `cuttinx-pro-windows`) ;
en local : `./gradlew :desktop:run` ou `./gradlew :desktop:packageExe`.

Documentation : [docs/BUILD.md](docs/BUILD.md) · [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) ·
[docs/TESTS.md](docs/TESTS.md) · [docs/LIMITATIONS.md](docs/LIMITATIONS.md) · [docs/LICENSES.md](docs/LICENSES.md) · [docs/PLAN.md](docs/PLAN.md)
