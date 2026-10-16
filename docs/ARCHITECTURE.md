# Architecture

```
core/  Kotlin/JVM pur (aucune dépendance Android) — réutilisable pour une version PC
  model/      Projet, pistes, clips, transformations, images clés, effets, texte, audio, export
  timeline/   TimelineEditor (opérations pures Project -> Project), History, EditSession, Snapping
  anim/       Interpolations (Bézier CSS), évaluation des images clés, SpeedMath (courbes de vitesse)
  effects/    EffectRegistry (shaders GLSL ES originaux), TransitionRegistry, Presets
  render/     FrameEvaluator (état de chaque couche à t), LanePlanner (pistes -> flux séquentiels)
  audio/      FFT, Biquad, EQ, compresseur, limiteur, réverb, écho, débruitage STFT, analyses BS.1770
  analysis/   Silences, tempo/beats, scènes, moments forts, sujet/recadrage/suivi, détourage, texte, médias
  autoedit/   Assistant de montage, actions IA sur la timeline, suggestions
  subtitles/  SRT/WebVTT, segmentation des mots reconnus
  project/    ProjectStore (écriture atomique, versions, autosave, récupération), archives, ExportPlanner
  resources/  Synthèse procédurale des musiques et bruitages originaux
app/   Android
  engine/     CompositionFactory (projet -> Composition Media3), ShaderEffect, ClipAudioProcessor,
              TextRenderer, PreviewEngine (CompositionPlayer), ExportEngine (Transformer), ExportService
  media/      Sonde des médias, détection réelle des codecs (MediaCodecList), PCM, miniatures, formes d'onde
  ai/         Vosk (modèles téléchargés à la demande)
  ui/         Compose : splash, accueil, éditeur (aperçu interactif, timeline Canvas, panneaux), paramètres, aide
```

## Rendu : un seul pipeline pour l'aperçu et l'export
`CompositionFactory` traduit le projet en `Composition` Media3 :
séquence 0 = « base de temps » transparente portant titres et sous-titres (dessinée au‑dessus),
puis une séquence par *voie* vidéo (une piste avec transitions est répartie sur deux voies qui se
chevauchent), puis le fond et les pistes audio. Les trous de piste sont des `gap`. Un
`VideoCompositorSettings` place chaque voie à chaque image (position, échelle, rotation, opacité,
progression des transitions), et chaque clip reçoit ses effets GPU (`ShaderEffect`, uniformes
évalués par image). La même composition alimente `CompositionPlayer` (aperçu) et `Transformer` (export).

Les modifications non structurelles (transformations, images clés, paramètres d'effet, volume…)
sont appliquées « à chaud » via `LiveProject` sans reconstruire la composition (`StructureKey`).
