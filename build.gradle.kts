// Plain desktop Gradle. This project was built with CodeAssist on a phone up to
// v1.1.0; from here it is a normal Gradle project — see CLAUDE.md.

// AGP 9 compiles Kotlin itself (built-in Kotlin) and rejects the kotlin-android plugin.
// This classpath entry only pins the Kotlin version it uses; AGP alone would bring an older one.
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")
    }
}

plugins {
    id("com.android.application") version "9.4.0" apply false
}
