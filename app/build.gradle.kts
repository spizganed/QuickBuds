import java.util.Properties

// Plain desktop Gradle — see docs/CONTRIBUTING.md.
//
// This was a CodeAssist project up to v1.1.0 (built from app/module.toml, which
// is now retired from the repo). From here the build is ordinary Gradle.

plugins {
    id("com.android.application")
}

// Release signing, from v2.0.0. The key and its passwords live in
// local/keys/ (git-ignored, local-only — BACK IT UP: the in-app updater can only install over an app
// signed with the same key). Without that file, release builds come out unsigned, as before.
val keyProps = Properties().apply {
    val f = rootProject.file("local/keys/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.spizganed.quickbuds"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        targetSdk = 37

        // THE ONLY PLACE the version is declared. It used to live on <application> in the manifest,
        // where Android ignores it: every PC build up to 2026-09-23 shipped with NO version, which
        // UpdateActivity (reads PackageManager) and bundletool ("Version code not found") both hit.
        versionCode = 49
        versionName = "5.4.2"
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

    // Lists every values-xx folder as a supported locale, so Android 13+ offers a per-app
    // language (Settings > Apps > QuickBuds > Language, or the app's own Settings > Language row).
    // The default locale comes from res/resources.properties.
    androidResources {
        generateLocaleConfig = true
    }

    // Every language goes into the base module of the AAB. A language split makes the Play Store install
    // one language, and the in-app picker then has no other language to show. The APK keeps all languages
    // already, so this changes the AAB only.
    bundle {
        language {
            enableSplit = false
        }
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
    testImplementation("junit:junit:4.13.2")
    // The Android stub of org.json throws in JVM tests.
    testImplementation("org.json:json:20240303")
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    // The protocol-case generator writes testdata/protocol_cases.json only when asked: -Dqb.generate=true.
    systemProperty("qb.generate", System.getProperty("qb.generate") ?: "false")
}
