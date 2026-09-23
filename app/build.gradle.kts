import java.util.Properties

// Plain desktop Gradle — see CLAUDE.md.
//
// This was a CodeAssist project up to v1.1.0 (built from app/module.toml, which
// is now retired from the repo). From here the build is ordinary Gradle.

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing — `[USER]` 2026-09-23, from v2.0.0. The key and its passwords live in
// local/keys/ (git-ignored, PC-only — BACK IT UP: the in-app updater can only install over an app
// signed with the same key). Without that file, release builds come out unsigned, as before.
val keyProps = Properties().apply {
    val f = rootProject.file("local/keys/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.spizganed.quickbuds"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        targetSdk = 35

        // THE ONLY PLACE the version is declared. It used to live on <application> in the manifest,
        // where Android ignores it: every PC build up to 2026-09-23 shipped with NO version, which
        // UpdateActivity (reads PackageManager) and bundletool ("Version code not found") both hit.
        versionCode = 3
        versionName = "2.0.0"
    }

    signingConfigs {
        if (!keyProps.isEmpty) create("release") {
            storeFile = rootProject.file(keyProps.getProperty("storeFile"))
            storePassword = keyProps.getProperty("storePassword")
            keyAlias = keyProps.getProperty("keyAlias")
            keyPassword = keyProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // buildConfig is NOT enabled, deliberately.
    //
    // UpdateActivity reads the installed version through PackageManager, because
    // BuildConfig was not guaranteed to exist when the build script was
    // generated. Enabling it now would create a second, unused source of the
    // version number. Leave it off unless something genuinely needs BuildConfig.
    buildFeatures {
        buildConfig = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}

dependencies {
    implementation("androidx.core:core:1.13.1")
}
