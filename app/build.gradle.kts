import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: keystore.properties (git-ignored, see README "Release build") holds
//   storeFile=/absolute/path/to/upload.jks
//   storePassword=...
//   keyAlias=upload
//   keyPassword=...
// Without it the release build type falls back to the debug key so `bundleRelease` still runs.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreProps.getProperty("storeFile")?.let { file(it).exists() } == true

android {
    namespace = "com.azura.tvlauncher"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.azura.tvlauncher"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "1.1.3"

        // Phones and tablets are supported too (the launcher works as a kiosk-style home screen
        // there), so Android TV is optional by default. `-PtvOnly=true` builds a TV-only bundle.
        manifestPlaceholders["leanbackRequired"] = (project.findProperty("tvOnly") ?: "false").toString()
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Kept off on purpose: no ProGuard rules have been written or tested for this app.
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = true   // Net.USER_AGENT carries the version name
    }

    bundle {
        // One-APK-per-language splits are pointless for a single-language app and would stop
        // `bundletool build-apks --mode=universal` users from getting every resource.
        language { enableSplit = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
