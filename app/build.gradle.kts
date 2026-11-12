import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Optional stable signing key (so updates install over each other).
// Set these env vars (the GitHub workflow does it from secrets); otherwise the debug key is used.
val keystorePath: String? = System.getenv("ORBIT_KEYSTORE")

android {
    namespace = "com.aniq.orbit"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aniq.orbit"
        minSdk = 33          // Android 13 — Galaxy Tab S9 series and newer
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("ORBIT_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ORBIT_KEY_ALIAS")
                keyPassword = System.getenv("ORBIT_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // Personal sideloaded app: don't let lint warnings block the CI build.
        abortOnError = false
        checkReleaseBuilds = false
    }

    buildFeatures {
        buildConfig = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// No dependencies on purpose: plain Android framework + Kotlin stdlib keeps the APK tiny and startup fast.
