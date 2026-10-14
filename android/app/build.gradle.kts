import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jlleitschuh.gradle.ktlint")
    id("org.jetbrains.kotlinx.kover")
}

val keystoreProperties =
    Properties().apply {
        val file = rootProject.file("keystore.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }

fun signingValue(
    property: String,
    variable: String,
): String? = keystoreProperties.getProperty(property) ?: System.getenv(variable)

val releaseStoreFile = signingValue("storeFile", "KEYSTORE_FILE")
val hasReleaseKeystore = releaseStoreFile != null && rootProject.file(releaseStoreFile).exists()

android {
    namespace = "org.peblum.mapsforpebble"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.peblum.mapsforpebble"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.2.0"
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = signingValue("storePassword", "KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (hasReleaseKeystore) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    jvmToolchain(21)
}

ktlint {
    version.set("1.8.0")
}

kover {
    reports {
        filters {
            excludes {
                classes(
                    "org.peblum.mapsforpebble.MainActivity*",
                    "org.peblum.mapsforpebble.MapsForPebbleApp",
                    "org.peblum.mapsforpebble.Navigator*",
                    "org.peblum.mapsforpebble.Preferences",
                    "org.peblum.mapsforpebble.service.*",
                    "org.peblum.mapsforpebble.pebble.WatchLink*",
                    "org.peblum.mapsforpebble.pebble.WatchListenerService",
                    "org.peblum.mapsforpebble.map.MapRenderer*",
                    "org.peblum.mapsforpebble.map.TileStore*",
                    "org.peblum.mapsforpebble.nav.GoogleMapsNotification*",
                    "org.peblum.mapsforpebble.nav.NotificationRead*",
                )
            }
        }
        verify {
            rule {
                minBound(100, coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE)
            }
            rule {
                minBound(100, coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.INSTRUCTION)
            }
            rule {
                minBound(100, coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH)
            }
        }
    }
}

dependencies {
    implementation("io.rebble.pebblekit2:client:1.3.0")
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
}
