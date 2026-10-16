# CUTTINX — Plan de développement (SUPERESSENCE CREATIVE STUDIO)

## Audit initial (Phase 1)
- Dépôt `julienots/Cuttinx` : **vide** (aucun commit, aucun fichier). Rien à conserver ni à migrer.
- Environnement de build disponible : JDK 21, Gradle 8.14.3, Android SDK installé (platform 36,
  build-tools 36). **Pas d'émulateur Android** (pas de KVM) et pas d'appareil connecté : les tests
  sur appareil réel ne sont pas exécutables ici.

## Choix techniques
| Besoin | Choix | Licence |
|---|---|---|
| Langage | Kotlin | Apache 2.0 |
| UI | Jetpack Compose + Material 3 | Apache 2.0 |
| Lecture / aperçu | AndroidX Media3 `CompositionPlayer` (même pipeline que l'export) | Apache 2.0 |
| Export | AndroidX Media3 `Transformer` (encodeurs matériels MediaCodec) | Apache 2.0 |
| Effets GPU | Shaders OpenGL ES originaux via `media3-effect` | Apache 2.0 / code original |
| Audio DSP | Code original pur Kotlin (module `core`) | Licence du projet |
| Reconnaissance vocale | Vosk (modèles téléchargés à la demande, hors APK) | Apache 2.0 |
| Sérialisation | kotlinx.serialization (JSON) | Apache 2.0 |

## Architecture
```
core/  (Kotlin/JVM pur, sans Android -> réutilisable pour une future version PC, testé ici)
  model/       Projet, pistes, clips, transformations, keyframes, effets, texte, export
  timeline/    Éditeur de timeline (opérations), historique annuler/rétablir, magnétisme
  anim/        Interpolations (linéaire, douce, Bézier), évaluation des keyframes, courbes de vitesse
  effects/     Registre extensible d'effets / transitions + préréglages
  render/      Plan de rendu : état évalué à un instant t (partagé aperçu/export)
  audio/       DSP : gain, pan, fondus, EQ biquad, compresseur, limiteur, réverb, delay,
               réduction de bruit spectrale, normalisation, détection de crêtes/saturation
  analysis/    IA locale : silences, beats/onsets, changements de scène, moments forts,
               recadrage auto, qualité audio, organisation des médias, titres
  autoedit/    Assistant de montage automatique -> vrai projet modifiable
  subtitles/   SRT / WebVTT (import/export), outils de synchronisation
  project/     Stockage JSON atomique, autosave, journal de récupération, archives .cuttinx
app/   (Android)
  ui/          Compose : splash animé, accueil, médias, éditeur, espaces de travail, AI Lab, export, paramètres, aide
  media/       Import (Photo Picker / SAF), sonde, miniatures, formes d'onde, détection codecs
  engine/      Projet -> Composition Media3, effets GL, processeur audio, aperçu, export
  ai/          Échantillonnage d'images, Vosk, liaisons vers core/analysis
```

## Phases
1. Audit & architecture ✔
2. Interface, projets, import médias
3. Timeline multipiste et découpage
4. Lecture et synchronisation (CompositionPlayer)
5. Titres, transitions, effets
6. Audio et images clés
7. Export
8. Sauvegardes et récupération
9. IA locale
10. Compositing (chroma key, modes de fusion, masques)
11. Ressources gratuites (générées / originales, licences documentées)
12. Optimisation, tests, corrections
