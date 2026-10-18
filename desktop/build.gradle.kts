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

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.swing)
    testImplementation(libs.junit)
    testImplementation(compose.desktop.currentOs)
}

compose.desktop {
    application {
        mainClass = "com.superessence.cuttinx.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Deb)
            packageName = "CuttinxPRO"
            packageVersion = "1.0.0"
            description = "Cuttinx PRO — studio créateur : overlays Twitch et identité visuelle"
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
