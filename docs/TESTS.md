# Résultats des tests (exécutés réellement, 16/10/2026)

Environnement : conteneur Linux sans GPU ni KVM, JDK 21. **Aucun appareil ni émulateur Android
n'était disponible** : rien n'a été testé sur téléphone réel (voir LIMITATIONS.md).

## `./gradlew :core:test` — 61 tests, 0 échec, 0 ignoré
| Suite | Tests | Ce qui est vérifié |
|---|---|---|
| AnimTest | 4 | Bézier (bornes, monotonie, identité), images clés (maintien, bornes), vitesse constante, courbe de vitesse aller-retour temps timeline ↔ source |
| TimelineTest | 14 | Ajout, division (durée et continuité source conservées), fusion, limites de découpe, piste magnétique, chevauchement → poussée, transitions (recouvrement, retrait, bornage), suppression/duplication, vitesse + images clés, groupes, copier-coller, pistes verrouillées, annuler/rétablir + fusion des gestes, remplacement/relink de média, magnétisme, **2000 clips / 50 coupes < 5 s** |
| RenderTest | 8 | Répartition en voies, trous, fondu indépendant de l'ordre de dessin, toutes les transitions aux bornes (pas d'image noire en fin), couches pendant une transition, transformations animées, placement fit/fill, gain + fondus, animations de texte, paramètres d'effet animés, **compilation des ~55 shaders GLSL ES avec glslangValidator** |
| AudioTest | 11 | Réponse du filtre (−3 dB à fc), égaliseur, compresseur (fort réduit, faible intact), limiteur (plafond jamais dépassé), écho au bon délai, traîne de réverb, **débruitage : RSB +4 dB min. et −10 dB sur le bruit seul**, latence STFT, **loudness BS.1770 (−3,01 LKFS de référence ±0,15)**, normalisation, saturations, chaîne de tous les effets audio sans NaN, suppression de voix centrée |
| AiTest | 13 | Silences (position ±60 ms), suppression des silences sur la timeline, **tempo de pistes clic 96/120/140 BPM ±3 %**, scènes, moments forts, suivi d'un objet mobile, détection de sujet + recadrage, détourage, titres et résumé, montage auto calé sur les temps + rejet des décisions, synchro des coupes, suggestions, organisation des médias |
| ProjectTest | 9 | Sauvegarde/chargement, **fichier corrompu → version de secours**, **récupération après crash**, duplication/renommage/archivage, archive `.cuttinx` aller-retour, SRT/VTT, segmentation des sous-titres, planification d'export (bornage aux capacités réelles), préréglages import/export |
| SynthTest | 2 | Les 20 bruitages sont valides ; musiques : durée, niveau (LUFS) et tempo détecté cohérent |

## `./gradlew :app:testDebugUnitTest` (Robolectric, SDK 35) — 6 tests, 0 échec
Construction de vraies `Composition` Media3 à partir de projets : ordre des séquences, chaque
séquence couvre toute la durée, effets GPU attachés, réglages du compositeur (calque masqué hors de
son intervalle, placement, fondu à 50 % au milieu de la transition, une seule voie visible après),
édition « à chaud » sans reconstruction, médias manquants remplacés par des trous, fournisseur de
vitesse variable, rendu de texte (taille stable pendant l'effet machine à écrire).

## Autres vérifications
- `./gradlew :app:lintDebug` : 0 erreur (contrôle d'API expérimentale Media3 désactivé volontairement).
- `./gradlew :app:assembleRelease` : APK R8 de 33,9 Mo, signature vérifiée (`apksigner verify`),
  3 ABI, sérialiseurs conservés.

## Bugs trouvés et corrigés grâce aux tests
Latence STFT mal déclarée (768 → 1024), filtre K BS.1770 approximatif (−3,27 au lieu de −3,01),
mot réservé GLSL (`active`), suppression sans resserrement de la piste magnétique, coupes du montage
auto décalées par les transitions, tempo erroné (144 au lieu de 108 BPM) sur rythme syncopé.
