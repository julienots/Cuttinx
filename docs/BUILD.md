# Compilation

Prérequis : JDK 17+ (testé avec 21), Android SDK (platform 36, build-tools 36), `glslangValidator`
(facultatif, pour le test de compilation des shaders).

```bash
echo "sdk.dir=/chemin/vers/android-sdk" > local.properties
./gradlew :core:test                # tests du cœur (JVM pur)
./gradlew :app:testDebugUnitTest    # tests du moteur Media3 (Robolectric)
./gradlew :app:lintDebug
./gradlew :app:assembleRelease      # app/build/outputs/apk/release/app-release.apk
```

Versions : AGP 8.13.2, Gradle 8.14.3, Kotlin 2.3.21, Compose BOM 2026.03.01, Media3 1.11.1,
minSdk 26 (Android 8), targetSdk 36. ABI : arm64-v8a, armeabi-v7a, x86_64.

Dépôts : `settings.gradle.kts` utilise le miroir Google de Maven Central en plus de `mavenCentral()`
(utile dans les environnements où repo1.maven.org est limité).

**Signature** : l'APK est signé avec `debug-cuttinx.keystore` (clé de développement versionnée,
mot de passe `cuttinx`) pour être installable directement. Pour publier (Play Store, F-Droid…),
générez votre propre clé et remplacez `signingConfigs` dans `app/build.gradle.kts`.

CI : `.github/workflows/android.yml` lance tests, lint et build, et publie l'APK en artefact.
