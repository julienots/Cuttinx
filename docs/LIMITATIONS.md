# Limitations connues (honnêtes)

## Non testé sur appareil
L'environnement de développement n'avait ni téléphone ni émulateur (pas de KVM). Le décodage,
l'aperçu temps réel, les shaders sur GPU réel, l'export MediaCodec, le micro, Vosk et l'interface
tactile n'ont **pas** été exécutés sur Android. Le cœur et la construction des compositions sont
testés (voir TESTS.md) et les shaders compilent avec le compilateur de référence Khronos, mais des
réglages (performances, gestes, compatibilité de certains GPU/encodeurs) restent à valider sur de
vrais téléphones. **Première étape recommandée : installer l'APK et tester sur 2–3 appareils.**

## Fonctions limitées
- **Export** : MP4 uniquement (H.264, HEVC ; AV1 sur Android 14+ si encodeur présent). Le muxer
  Android utilisé n'écrit ni WebM ni MOV : ils ne sont donc pas proposés.
- **Modes de fusion** : le compositeur Media3 ne mélange qu'en « Normal » (transparence). Les modes
  Produit/Écran/Incrustation existent comme effets de superposition sur un calque, pas entre calques.
- **Stabilisation** : non implémentée (champ réservé dans le modèle, aucun bouton factice).
- **Séparation voix/musique** : méthode spectrale (canal central, HPSS) ; qualité inférieure aux
  modèles neuronaux (non intégrés : trop lourds pour un mobile, licences/tailles à étudier).
- **Suppression d'arrière-plan** : images seulement, efficace sur fonds unis. Pour la vidéo : fond vert.
- **Titres / résumé** : générateurs locaux à base de mots-clés et de modèles, pas un modèle de langage.
- **Sous-titres automatiques** : nécessitent un téléchargement unique du modèle (≈40 Mo, internet).
- **Nombre de pistes vidéo simultanées** limité par les décodeurs matériels du téléphone (souvent 2–4 vidéos en même temps ; les images ne comptent pas).
- **Débruitage temps réel** : ajoute ~21 ms de décalage audio (dans la tolérance de synchronisation) ;
  la version « IA » hors ligne n'a pas de décalage.
- **Appareil < Android 8** non pris en charge.
- **Accessibilité** : descriptions de contenu sur les boutons, grandes commandes en option ;
  pas encore de tutoriel interactif pas-à-pas (aide écrite + projet d'exemple guidé).

## Prochaines étapes
1. Tests sur appareils réels (aperçu multipiste, export 4K, mémoire) et corrections.
2. Compositeur personnalisé pour les modes de fusion et le flou d'arrière-plan.
3. Stabilisation (estimation de mouvement + recadrage lissé).
4. Modèle de segmentation/séparation optionnel téléchargeable (licence libre).
5. Cache de rendu intermédiaire pour les effets coûteux, version PC à partir de `core`.
