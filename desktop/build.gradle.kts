import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

// The PC app reuses the fonts bundled with the Android app (same licences, see docs/LICENSES.md).
sourceSets { main { resources { srcDir(file("../app/src/main/assets")) } } }

// FFmpeg (decoding / encoding of the video editor) with the native libraries of the build machine:
// the Windows installer is built on Windows and embeds the Windows libraries.
val javacvVersion = "1.5.13"
val ffmpegVersion = "8.0.1-$javacvVersion"
val nativeClassifier = System.getProperty("os.name").lowercase().let { os ->
    val arm = System.getProperty("os.arch").lowercase().contains("aarch64")
    when {
        os.contains("win") -> "windows-x86_64"
        os.contains("mac") -> if (arm) "macosx-arm64" else "macosx-x86_64"
        else -> if (arm) "linux-arm64" else "linux-x86_64"
    }
}

dependencies {
    implementation(project(":core"))
    implementation("org.bytedeco:javacv:$javacvVersion") {
        // only FFmpeg is used: keep the installer small
        listOf("opencv", "openblas", "flycapture", "libdc1394", "libfreenect", "libfreenect2", "librealsense", "librealsense2",
            "videoinput", "artoolkitplus", "flandmark", "leptonica", "tesseract").forEach { exclude(group = "org.bytedeco", module = it) }
    }
    implementation("org.bytedeco:ffmpeg:$ffmpegVersion")
    implementation("org.bytedeco:ffmpeg:$ffmpegVersion:$nativeClassifier")
    implementation("org.bytedeco:javacpp:$javacvVersion:$nativeClassifier")
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.swing)
    testImplementation(libs.junit)
    testImplementation(compose.desktop.currentOs)
}

compose.desktop {
    application {
        mainClass = "com.superessence.cuttinx.desktop.MainKt"
        jvmArgs += listOf("-Xmx3g")
        nativeDistributions {
            modules("java.desktop", "java.sql", "java.naming", "java.management", "jdk.unsupported", "jdk.crypto.ec")
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Deb)
            packageName = "CuttinxPRO"
            packageVersion = "1.1.0"
            description = "Cuttinx PRO — éditeur vidéo, Twitch Editor et ID Influenceur"
            vendor = "SuperEssence Creative Studio"
            windows {
                menuGroup = "Cuttinx PRO"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "6f1d7c1e-3c0b-4f7e-9a51-2a8f0b6c4d21"
            }
        }
    }
}

tasks.test {
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
