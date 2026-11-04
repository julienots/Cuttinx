plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    // icon set shared with the PC app
    sourceSets["main"].java.srcDir("../ui-shared/kotlin")
    namespace = "com.superessence.cuttinx"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.superessence.cuttinx"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "2.2.0"
        vectorDrawables.useSupportLibrary = true
        // AdMob (pubs plein écran du Labo CUTTINX). Par défaut : identifiants de TEST de Google.
        // Pour gagner de l'argent : -Pcuttinx.admobAppId=… -Pcuttinx.admobRewardedId=… (ou gradle.properties / secrets CI).
        val admobAppId = (project.findProperty("cuttinx.admobAppId") as String?)?.takeIf { it.isNotBlank() } ?: System.getenv("ADMOB_APP_ID")?.takeIf { it.isNotBlank() } ?: "ca-app-pub-3940256099942544~3347511713"
        val admobRewarded = (project.findProperty("cuttinx.admobRewardedId") as String?)?.takeIf { it.isNotBlank() } ?: System.getenv("ADMOB_REWARDED_ID")?.takeIf { it.isNotBlank() } ?: "ca-app-pub-3940256099942544/5224354917"
        manifestPlaceholders["admobAppId"] = admobAppId
        buildConfigField("String", "ADMOB_REWARDED_ID", "\"$admobRewarded\"")
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    signingConfigs {
        // Clé de signature de développement versionnée pour produire un APK installable.
        // Pour une publication, remplacez-la par votre propre clé (voir docs/BUILD.md).
        create("cuttinxDev") {
            storeFile = rootProject.file("debug-cuttinx.keystore")
            storePassword = "cuttinx"
            keyAlias = "cuttinx"
            keyPassword = "cuttinx"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("cuttinxDev")
        }
        debug {
            signingConfig = signingConfigs.getByName("cuttinxDev")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/*.kotlin_module")
        jniLibs.useLegacyPackaging = false
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2")
        }
    }
    lint {
        // Media3 CompositionPlayer / Transformer composition APIs are marked @UnstableApi /
        // @ExperimentalApi: CUTTINX uses them deliberately (pinned Media3 version).
        disable += "UnsafeOptInUsageError"
        abortOnError = true
        checkReleaseBuilds = false
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":core"))
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.navigation.compose)
    implementation(libs.documentfile)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)
    implementation(libs.media3.common)
    implementation(libs.kotlinx.coroutines.android)
    // Vosk + JNA (AAR with Android natives, as recommended by the Vosk project)
    implementation(libs.vosk.android) { exclude(group = "net.java.dev.jna") }
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    // Pubs récompensées (Labo CUTTINX) + consentement RGPD
    implementation("com.google.android.gms:play-services-ads:23.6.0")
    implementation("com.google.android.ump:user-messaging-platform:3.1.0")
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core:1.6.1")
}
