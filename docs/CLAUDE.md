# CUTTINX × Claude (MCP)

Claude peut regarder les médias que tu lui partages et créer de **vrais projets CUTTINX**
(pistes, coupes, vitesses et rampes, transitions, effets GPU, zooms animés, musique calée sur le
BPM, textes), que tu retouches ensuite à la main dans l'éditeur, puis les exporter.

## Mise en route
1. Accueil → icône ✨ **Claude** → active **Connecté à Claude**.
2. **Copier** l'adresse, puis sur claude.ai : Paramètres → Connecteurs → Ajouter un connecteur
   personnalisé (nom : CUTTINX).
3. **Ajouter des médias** : Claude ne voit que les fichiers ajoutés dans cet écran.
4. Dans une conversation : « Fais un edit calisthenics de mes vidéos sur la musique *X* ».
   Le projet apparaît sur l'écran d'accueil ; l'export va dans Movies/Cuttinx.

## Fonctionnement
```
Claude ──MCP──▶ relais (Supabase Edge Function « cuttinx », relay/index.ts) ◀── ClaudeService (téléphone)
```
- Les vidéos ne quittent jamais le téléphone : seules des images d'aperçu (planches contact,
  images) et des résultats d'analyse remontent à Claude.
- L'adresse contient un jeton secret propre au téléphone (régénérable dans l'écran Claude).
- Code : `app/.../claude/` — `ClaudeService` (connexion), `ClaudeTools` (outils),
  `ProjectSpecs` (recette JSON → `Project`), `ClaudeLibrary` (médias partagés), `ClaudeScreen` (UI).
- Relais : `relay/index.ts` + `relay/schema.sql` (tables `vs_devices`, `vs_commands`, fonction `vs_claim`).

## Outils MCP
device_status, list_media, update_media, view_frame, view_storyboard, detect_scenes,
find_highlights, analyze_audio, analyze_music (BPM, beats, downbeats, drops), list_effects,
list_projects, get_project, create_project, render_project, get_render, import_from_url.

## Tests
`ProjectSpecsTest` : une recette complète produit un projet valide, sérialisable, et une
composition Media3 construite par `CompositionFactory`. Non testé sur appareil réel à ce jour.
