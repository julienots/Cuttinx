# WinPocket — lancer des logiciels Windows sur Android

Deuxième appli du dépôt, indépendante de CUTTINX. Elle fait tourner des programmes Windows
(`.exe`, x86 / x86-64) sur un téléphone Android ARM64, sans installer Windows :

- **Wine** réimplémente Windows (fenêtres, fichiers, registre, DirectX…) ;
- **Box64 / Box86** traduisent à la volée les instructions des processeurs de PC (x86) vers
  celles du téléphone (ARM64) ;
- **DXVK / VKD3D / WineD3D** convertissent DirectX vers Vulkan / OpenGL, **Turnip** est le pilote
  Vulkan libre des GPU Adreno (Snapdragon).

Le moteur est celui du projet libre [Winlator](https://github.com/brunodev85/winlator)
(licence **LGPL-2.1**, voir `LICENSE-LGPL-2.1.txt`). WinPocket le récupère à une version figée
(`upstream.properties`) et lui applique son habillage (`overlay/`) : nom, icône, couleurs et
**traduction française complète** de l'interface.

## Récupérer l'APK

La CI du dépôt construit l'APK à chaque envoi : onglet *Actions* > dernier build > artefact
**winpocket-apk**. En local : `bash emulator/build.sh` (JDK 17+, SDK Android avec NDK
24.0.8215888 et CMake 3.22.1) → `emulator/dist/WinPocket.apk`.

## Utilisation

1. Installe l'APK et ouvre-le : au premier lancement il installe ses fichiers système (≈ 1 Go
   une fois décompressés, prévoir de la place).
2. **PC virtuels** > `+` : crée un PC virtuel (résolution, version de Wine, pilote graphique :
   *Turnip* sur Snapdragon, sinon *VirGL*), puis **Lancer**.
3. Copie tes programmes dans `Téléchargements` du téléphone : ils apparaissent dans le lecteur
   **D:** du PC virtuel. Double-tape sur le `.exe` (ou sur l'installateur) pour le lancer.
4. Pavé tactile : 1 doigt = clic gauche, 2 doigts = clic droit / molette, 4 doigts = menu
   (clavier, contrôles de manette à l'écran, plein écran, gestionnaire des tâches).
5. Clavier / souris Bluetooth et manettes fonctionnent directement.

## Ce qu'il faut savoir

- Téléphone **ARM64** obligatoire ; idéal : Snapdragon (Adreno 6xx/7xx) avec 8 Go de RAM.
- Ça marche bien pour les logiciels légers et beaucoup de jeux PC anciens ou indépendants ;
  les jeux AAA récents et les anti-triche (Valorant, Fortnite…) ne fonctionnent pas.
- Pour les programmes .NET : menu Démarrer > Outils système > installateurs (Wine Mono).
- L'identifiant Android reste `com.winlator`, parce que les fichiers système compilés en amont
  contiennent ce chemin. WinPocket remplace donc une éventuelle installation de Winlator ;
  les deux ne peuvent pas cohabiter sur le même téléphone.

## Crédits

Winlator (brunodev85), Wine (winehq.org), Box86/Box64 (ptitSeb), Mesa / Turnip / Zink / VirGL,
DXVK, VKD3D, CNC DDraw, paquets GLIBC de Termux Pacman. Le code source complet de chaque
version est accessible (lien et commit dans `upstream.properties`), comme l'exige la LGPL.
